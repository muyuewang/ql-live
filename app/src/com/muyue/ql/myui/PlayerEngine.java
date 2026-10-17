package com.muyue.ql.myui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.jiaoxiang.fangnale.utils.HtmlContentParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import tv.danmaku.ijk.media.player.AndroidMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

/** Resolves a channel line (direct or engine second-parse) and drives IjkMediaPlayer. */
public class PlayerEngine {

    public interface Listener {
        void onRendering();
        void onFailed(String reason);
        void onBuffering(boolean start);
        void onVideoSize(int w, int h);
        void onNotice(String msg);
    }

    public static final int ASPECT_FIT = 0;
    public static final int ASPECT_FILL = 1;
    public static final int ASPECT_16_9 = 2;
    public static final int ASPECT_4_3 = 3;
    public static final int ASPECT_STRETCH = 4;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HtmlContentParser parser;
    private IMediaPlayer player;
    private String decodeMode = "ijk1"; // ijk1=硬解 ijk0=软解 media=系统播放器，默认硬解
    private boolean autoLineSwitch = true; // 持续缓冲超时自动换线
    private long bufferingSince;
    private long lastAutoSwitchAt;

    private final Runnable bufferWatchdog = new Runnable() {
        public void run() {
            if (stopped || !autoLineSwitch || bufferingSince == 0) return;
            if (System.currentTimeMillis() - lastAutoSwitchAt < 25000) return;
            lastAutoSwitchAt = System.currentTimeMillis();
            listener.onNotice("当前线路卡顿，自动切换线路");
            failToNext("卡顿自动换线");
        }
    };
    private int aspect = ASPECT_FIT;

    private String currentRawUrl;
    private Map<String, String> currentHeaders;
    private Map<String, String> origHeaders;
    private boolean parsing;
    private boolean stopped;
    private int videoW, videoH;
    private android.view.Surface surface;

    private final Runnable timeout = new Runnable() {
        public void run() {
            failToNext("播放超时");
        }
    };

    public PlayerEngine(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        parser = new HtmlContentParser(ctx);
        parser.addCallBack(new HtmlContentParser.CallBack() {
            public void startVideo(String res) {
                parsing = false;
                // engine result format: ["url@@User-Agent=..@@#label"] or plain url
                String u = res == null ? "" : res.trim();
                if (u.startsWith("[")) {
                    try {
                        org.json.JSONArray a = new org.json.JSONArray(u);
                        if (a.length() > 0) u = a.optString(0, u);
                    } catch (Throwable ignored) {}
                }
                int hash = u.indexOf('#');
                if (hash > 0) u = u.substring(0, hash);
                ParsedLine pl = parseLine(u);
                if (pl == null) { failToNext("解析结果为空"); return; }
                // merge original line headers (Referer etc.) with result headers (UA etc.)
                if (origHeaders != null) {
                    for (Map.Entry<String, String> e : origHeaders.entrySet())
                        if (!pl.headers.containsKey(e.getKey()))
                            pl.headers.put(e.getKey(), e.getValue());
                }
                currentRawUrl = pl.url;
                currentHeaders = pl.headers;
                playDirect(pl.url, pl.headers);
            }
            public void onError() {
                parsing = false;
                failToNext("二次解析失败");
            }
            public void pauseVideo() {
                if (player != null) player.pause();
            }
        });
    }

    public void setSurface(android.view.Surface s) {
        surface = s;
        if (player != null) player.setSurface(s);
    }

    public void setHwDecode(boolean hw) { setDecodeMode(hw ? "ijk1" : "ijk0"); }

    /** ijk1=硬解 ijk0=软解 media=系统播放器 */
    public void setDecodeMode(String mode) {
        if (mode != null && !mode.equals(decodeMode)) {
            decodeMode = mode;
            if (currentRawUrl != null) restartCurrent();
        }
    }

    public String getDecodeMode() { return decodeMode; }

    public boolean isHwDecode() { return "ijk1".equals(decodeMode); }

    public void setAspect(int a) {
        aspect = a;
        listener.onVideoSize(videoW, videoH);
    }

    public int getAspect() { return aspect; }

    public String getCurrentUrl() { return currentRawUrl; }
    public boolean isBusyParsing() { return parsing; }
    private String lastFinalUrl;
    /** the actually-playing (resolved) url, usable for DLNA cast */
    public String getLastFinalUrl() { return lastFinalUrl; }

    /** play one raw line (may be kdsvod:// / htmlplay:// / direct http) */
    public synchronized void playLine(String rawLine) {
        stopped = false;
        parsing = false;
        main.removeCallbacks(timeout);
        releasePlayer();
        cancelParser();

        ParsedLine pl = parseLine(rawLine);
        if (pl == null) { failToNext("空线路"); return; }
        lastRawLine = rawLine;
        currentRawUrl = pl.url;
        currentHeaders = pl.headers;
        origHeaders = pl.headers;

        if (pl.url.startsWith("htmlplay://") || pl.url.startsWith("lunbo://")
                || pl.url.startsWith("ahhftv://") || pl.url.startsWith("kdsvod://ahhftv://")) {
            failToNext("不支持该线路类型");
            return;
        }
        try {
            boolean needSecond = parser.needSecondParse(pl.url);
            if (needSecond) {
                parsing = true;
                main.postDelayed(timeout, 15000);
            } else {
                playDirect(pl.url, pl.headers);
            }
        } catch (Throwable t) {
            failToNext("解析异常");
        }
    }

    private static class ParsedLine {
        String url;
        Map<String, String> headers;
    }

    /** split "@@Referer=...@@UA=...@@" suffix into headers */
    static ParsedLine parseLine(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        Map<String, String> headers = new HashMap<String, String>();
        int idx;
        while ((idx = url.indexOf("@@")) >= 0) {
            int end = url.indexOf("@@", idx + 2);
            if (end < 0) break;
            String token = url.substring(idx + 2, end);
            url = url.substring(0, idx) + url.substring(end + 2);
            int eq = token.indexOf('=');
            if (eq > 0) {
                String k = token.substring(0, eq).trim();
                String v = token.substring(eq + 1).trim();
                if (k.equalsIgnoreCase("Referer")) headers.put("Referer", v);
                else if (k.equalsIgnoreCase("UA") || k.equalsIgnoreCase("User-Agent")) headers.put("User-Agent", v);
            }
        }
        url = url.trim();
        if (url.startsWith("kdsvod://ahhftv://")) { /* handled by caller skip logic */ }
        // keep kdsvod:// prefix: engine's needSecondParse relies on it
        ParsedLine pl = new ParsedLine();
        pl.url = url;
        pl.headers = headers;
        return url.length() > 4 ? pl : null;
    }

    private void playDirect(String url, Map<String, String> headers) {
        try {
            main.removeCallbacks(timeout);
            releasePlayer();

            // ---- decode selection per original IjkDefPlayerActivity.openVideo ----
            // URL hint (&kdsplayer=ijk1/ijk0/media, &kdsipsign=) overrides user pref
            String u = url;
            String decode = decodeMode;
            if (u.contains("&kdsplayer=media")) {
                u = u.replace("&kdsplayer=media", "");
                decode = "media";
            } else if (u.contains("&kdsplayer=ijk0")) {
                u = u.replace("&kdsplayer=ijk0", "");
                decode = "ijk0";
            } else if (u.contains("&kdsplayer=ijk1") || u.contains("&kdsipsign=")) {
                u = u.replace("&kdsplayer=ijk1", "");
                decode = "ijk1";
            }

            IMediaPlayer mp;
            if ("media".equals(decode)) {
                // system player branch
                mp = new AndroidMediaPlayer();
            } else {
                IjkMediaPlayer im = new IjkMediaPlayer();
                im.setAudioStreamType(3); // STREAM_MUSIC
                // kdsipsign -> http_proxy
                if (u.contains("&kdsipsign=")) {
                    try {
                        int i = u.indexOf("&kdsipsign=") + "&kdsipsign=".length();
                        String rest = u.substring(i);
                        int j = rest.indexOf('&');
                        String token = (j >= 0 ? rest.substring(0, j) : rest).trim();
                        if (token.length() > 0) {
                            String proxy = token;
                            // only skip decrypt when token looks like host:port
                            if (!(token.contains(".") && token.contains(":"))) {
                                String dec = com.jiaoxiang.fangnale.utils.AesUtil.decrypt(token, "3e8d5dcb8fd93e4a");
                                if (dec != null && dec.length() > 0) proxy = dec;
                            }
                            if (!proxy.startsWith("http")) proxy = "http://" + proxy;
                            if (proxy.length() > 7)
                                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http_proxy", proxy);
                        }
                    } catch (Throwable ignored) {}
                    u = u.replaceAll("&?kdsipsign=[^&]*", "");
                }
                if (u.startsWith("rtsp")) im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp");
                // exact original openVideo option set: mediacodec + overlay-format + safe + dns_cache_clear + protocol_whitelist.
                // NOTE: no framedrop/soundtouch/packet-buffering/reconnect/timeout — original never sets them,
                // and framedrop=1 causes visible frame stutter on weak devices.
                if (hwDecodeHint(decode)) {
                    im.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1);
                } else {
                    im.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0);
                }
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", 0x32335652L);
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", "0");
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1L);
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist",
                        "async,cache,crypto,file,http,https,ijkhttphook,ijkinject,ijklivehook,ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data,rtsp,mmsh,mmst,mms,rtmp");
                im.setKeepInBackground(true);
                mp = im;
            }

            lastFinalUrl = u;
            if (headers != null && !headers.isEmpty()) {
                mp.setDataSource(ctx, android.net.Uri.parse(u), headers);
            } else {
                mp.setDataSource(u);
            }
            if (surface != null) mp.setSurface(surface);
            mp.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
                public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                    if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        main.removeCallbacks(timeout);
                        main.removeCallbacks(bufferWatchdog);
                        bufferingSince = 0;
                        listener.onRendering();
                    } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        bufferingSince = System.currentTimeMillis();
                        main.removeCallbacks(bufferWatchdog);
                        main.postDelayed(bufferWatchdog, 12000);
                        listener.onBuffering(true);
                    } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        main.removeCallbacks(bufferWatchdog);
                        bufferingSince = 0;
                        listener.onBuffering(false);
                    } else if (what == IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED) {
                        listener.onVideoSize(videoW, videoH);
                    }
                    return false;
                }
            });
            mp.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
                public void onVideoSizeChanged(IMediaPlayer mp, int w, int h, int sarNum, int sarDen) {
                    videoW = w; videoH = h;
                    listener.onVideoSize(w, h);
                }
            });
            mp.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
                public boolean onError(IMediaPlayer mp, int what, int extra) {
                    failToNext("播放错误 " + what);
                    return true;
                }
            });
            mp.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
                public void onCompletion(IMediaPlayer mp) {
                    failToNext("播放结束");
                }
            });
            player = mp;
            main.postDelayed(timeout, 13000);
            mp.prepareAsync();
        } catch (Throwable t) {
            failToNext("初始化失败");
        }
    }

    public void setAutoLineSwitch(boolean v) { autoLineSwitch = v; }

    private static boolean hwDecodeHint(String decode) { return "ijk1".equals(decode); }

    private void restartCurrent() {
        String raw = lastRawLine;
        if (raw != null) playLine(raw);
    }

    private String lastRawLine;

    /** try the next available line of the current channel */
    private ArrayList<String> currentLines = new ArrayList<String>();
    private int currentLineIdx = -1;

    public void setChannelLines(ArrayList<String> lines, int startIndex) {
        currentLines = lines;
        currentLineIdx = startIndex;
    }

    private void failToNext(String reason) {
        if (stopped) return;
        if (currentLineIdx + 1 < currentLines.size()) {
            currentLineIdx++;
            playLine(currentLines.get(currentLineIdx));
        } else {
            listener.onFailed(reason);
        }
    }

    public void stopAll() {
        stopped = true;
        main.removeCallbacks(timeout);
        main.removeCallbacks(bufferWatchdog);
        bufferingSince = 0;
        cancelParser();
        releasePlayer();
    }

    private void cancelParser() {
        try {
            if (currentRawUrl != null) parser.cancelExistParser(currentRawUrl);
        } catch (Throwable ignored) {}
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.reset(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    public void destroy() {
        stopAll();
        try { parser.release(); } catch (Throwable ignored) {}
    }

    public int getVideoWidth() { return videoW; }
    public int getVideoHeight() { return videoH; }

    /** query size from live player instance (fallback when size-changed event missed) */
    public int[] fetchVideoSize() {
        if (player != null) {
            try {
                int w = player.getVideoWidth();
                int h = player.getVideoHeight();
                if (w > 0 && h > 0) { videoW = w; videoH = h; }
            } catch (Throwable ignored) {}
        }
        return new int[]{videoW, videoH};
    }
}
