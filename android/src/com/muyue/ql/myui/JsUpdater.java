package com.muyue.ql.myui;

import android.content.Context;
import android.content.SharedPreferences;

import com.jiaoxiang.fangnale.net.NetConstant;
import com.jiaoxiang.fangnale.utils.AesUtil;
import com.jiaoxiang.fangnale.utils.ApiUtils;
import com.jiaoxiang.fangnale.utils.NativeEncrypt;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 引擎算法包（js.zip）热更新。
 *
 * <p>原版靠 {@code StartActivity} 启动页把配置写进 {@code startStr}，再由
 * {@code StartActivity.downloadParse()} 下载 js.zip 解压到 filesDir；本重构改用自绘
 * {@code MainActivity} 作入口，那条链路根本不会执行——所以算法脚本会一直停在随包
 * assets 的旧版本，服务商哪天停用旧签名就当场失效。
 *
 * <p>这里把「按服务端配置拉 js.zip → 解压覆盖 filesDir」这一步补回来：
 *
 * <ol>
 *   <li>请求 {@code get_status_kgq.php}（带原版 {@code addStartParams} 签名）拿 jsVersion/jsUrl；
 *   <li>比对本地版本，服务端更新过才下载；
 *   <li>解压时只取 basename 且必须是 .js，避免 zip 里的路径穿越写坏别处。
 * </ol>
 *
 * <p>全程在子线程跑，任何失败都静默——拿不到新包时继续用本地已有的 JS，不影响播放。
 */
public final class JsUpdater {

    private static final String PREF = "ql_js";
    private static final String K_VER = "ver";
    private static final String K_TIME = "time";
    private static final String K_URL = "url";

    /** 随包兜底：APK 内置配置里的那版（服务端当前在用的就是它）。 */
    private static final int FALLBACK_VER = 166;
    private static final String FALLBACK_URL = "http://file.91kds.cn/js/js-20241115.zip";

    /** 超过这个时间即使版本号没变也重下一次，防止服务商原地替换等长内容。 */
    private static final long REFRESH_AFTER_MS = 3L * 24 * 3600 * 1000;

    private static final int CONNECT_TIMEOUT = 8000;
    private static final int READ_TIMEOUT = 20000;

    private JsUpdater() {}

    /** 后台检查并更新；调用方无需等待。 */
    public static void updateAsync(final Context ctx) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    sync(ctx.getApplicationContext());
                } catch (Throwable e) {
                    log("更新异常 " + e);
                }
            }
        }, "ql-jsupd");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void sync(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, 0);
        int localVer = sp.getInt(K_VER, 0);
        long last = sp.getLong(K_TIME, 0);
        boolean stale = System.currentTimeMillis() - last > REFRESH_AFTER_MS;

        Config cfg = fetchConfig(ctx);
        if (cfg == null) {
            // 拿不到线上配置就退回随包兜底，至少保证第一次安装能拿到算法包
            String u = sp.getString(K_URL, FALLBACK_URL);
            cfg = new Config(localVer > 0 ? localVer : FALLBACK_VER, u);
            log("配置接口不可用，使用兜底 " + cfg.url);
        }

        boolean need = cfg.ver > localVer || (stale && cfg.ver >= localVer);
        if (!need) {
            log("已是最新 ver=" + localVer + "（远端 " + cfg.ver + "）");
            return;
        }

        File dir = ctx.getFilesDir();
        File zip = new File(dir, "js.zip");
        if (!download(cfg.url, zip)) {
            log("下载失败 " + cfg.url);
            return;
        }
        int n = unzip(zip, dir);
        if (n <= 0) {
            log("解压失败（无有效 .js）");
            return;
        }
        sp.edit()
                .putInt(K_VER, cfg.ver)
                .putLong(K_TIME, System.currentTimeMillis())
                .putString(K_URL, cfg.url)
                .apply();
        // 与厂商 UnZipUtil 一致：zip 留在 filesDir，不删。
        log("已更新 ver " + localVer + " -> " + cfg.ver + "，写入 " + n + " 个文件");
    }

    // ------------------------------------------------------------ 配置

    private static final class Config {
        final int ver;
        final String url;

        Config(int ver, String url) {
            this.ver = ver;
            this.url = url;
        }
    }

    private static Config fetchConfig(Context ctx) {
        try {
            String url = NetConstant.STATUS;
            if (ctx instanceof android.app.Activity) {
                url = ApiUtils.addStartParams((android.app.Activity) ctx, url);
            }
            String res = httpGetString(url, CONNECT_TIMEOUT);
            if (res == null || res.trim().length() == 0) {
                return null;
            }
            String plain = res.trim();
            if (plain.charAt(0) != '{') {
                plain = AesUtil.decrypt2024(res, NativeEncrypt.getSalt());
            }
            if (plain == null) {
                return null;
            }
            JSONObject o = new JSONObject(plain.trim());
            int ver = o.optInt("jsVersion", -1);
            String ju = o.optString("jsUrl", "");
            if (ju.indexOf("\\/") >= 0) {
                ju = ju.replace("\\/", "/");
            }
            if (ver <= 0 || ju.length() == 0) {
                return null;
            }
            return new Config(ver, ju);
        } catch (Throwable t) {
            log("取配置失败 " + t);
            return null;
        }
    }

    // ------------------------------------------------------------ 下载 / 解压

    private static boolean download(String url, File out) {
        File tmp = new File(out.getAbsolutePath() + ".part");
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            HttpURLConnection c = open(url, READ_TIMEOUT);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                log("HTTP " + code);
                c.disconnect();
                return false;
            }
            in = c.getInputStream();
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
                if (total > 20L * 1024 * 1024) {
                    log("包体异常大，放弃");
                    return false;
                }
            }
            fos.close();
            fos = null;
            in.close();
            in = null;
            c.disconnect();
            if (total < 1024) {
                log("包体过小 " + total + "B");
                return false;
            }
            if (out.exists() && !out.delete()) {
                return false;
            }
            return tmp.renameTo(out);
        } catch (Throwable t) {
            log("下载异常 " + t);
            return false;
        } finally {
            close(fos);
            close(in);
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 解压到 dir，只取 basename 且必须是 .js，返回写入文件数。 */
    private static int unzip(File zip, File dir) {
        ZipInputStream zis = null;
        int written = 0;
        try {
            zis = new ZipInputStream(new java.io.FileInputStream(zip));
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (name.length() == 0 || !name.toLowerCase().endsWith(".js")
                        || name.contains("..")) {
                    continue;
                }
                File dst = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(dst);
                try {
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                } finally {
                    close(fos);
                }
                written++;
            }
        } catch (Throwable t) {
            log("解压异常 " + t);
        } finally {
            close(zis);
        }
        return written;
    }

    // ------------------------------------------------------------ 工具

    private static HttpURLConnection open(String url, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(readTimeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private static String httpGetString(String url, int timeout) {
        InputStream in = null;
        try {
            HttpURLConnection c = open(url, timeout);
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                c.disconnect();
                return null;
            }
            in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024) {
                    break;
                }
            }
            c.disconnect();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void log(String msg) {
        System.err.println("[JsUpd] " + msg);
    }
}
