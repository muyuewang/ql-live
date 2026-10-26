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
        /** 某条线路成功出帧：lineIdx 为当前线路序号，ms 为 prepare->首帧耗时 */
        void onLineOk(int lineIdx, long ms);
    }

    public static final int ASPECT_FIT = 0;
    public static final int ASPECT_FILL = 1;
    public static final int ASPECT_16_9 = 2;
    public static final int ASPECT_4_3 = 3;
    public static final int ASPECT_STRETCH = 4;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---------------- 二次解析结果缓存 ----------------
    // kdsvod 这类线路每次解析都要起引擎跑一遍 JS（WebView eval，几百毫秒）。
    // 同一频道换线路/切回来时直接复用上次结果，TTL 150s（sign 有时效，不宜过长）。
    private static final long RESOLVE_TTL = 45_000L;
    private static final HashMap<String, Resolved> RESOLVED = new HashMap<String, Resolved>();

    private static class Resolved {
        String url;
        Map<String, String> headers;
        long ts;
    }

    private static Resolved cacheGet(String raw) {
        if (raw == null) return null;
        synchronized (RESOLVED) {
            Resolved r = RESOLVED.get(raw);
            if (r == null) return null;
            if (System.currentTimeMillis() - r.ts > RESOLVE_TTL) {
                RESOLVED.remove(raw);
                return null;
            }
            return r;
        }
    }

    private static void cachePut(String raw, String url, Map<String, String> headers) {
        if (raw == null || url == null) return;
        Resolved r = new Resolved();
        r.url = url;
        r.headers = new HashMap<String, String>(headers);
        r.ts = System.currentTimeMillis();
        synchronized (RESOLVED) {
            if (RESOLVED.size() > 200) RESOLVED.clear(); // 简单容量控制
            RESOLVED.put(raw, r);
        }
    }

    private static void cacheDrop(String raw) {
        if (raw == null) return;
        synchronized (RESOLVED) { RESOLVED.remove(raw); }
    }

    private HtmlContentParser parser;
    private IMediaPlayer player;
    /** 切台时被冻结的上一路播放器，新线路出帧后释放 */
    private IMediaPlayer oldPlayer;
    private String decodeMode = "ijk1"; // ijk1=硬解 ijk0=软解 media=系统播放器，默认硬解
    private boolean autoLineSwitch = true; // 持续缓冲超时自动换线
    private long bufferingSince;
    private long lastAutoSwitchAt;

    private final Runnable bufferWatchdog = new Runnable() {
        public void run() {
            if (stopped || !autoLineSwitch || bufferingSince == 0) return;
            // 只有「已经出过画面」之后卡住才算真卡顿。
            // 起播阶段 ijk 本来就会先发一个 BUFFERING_START，这时画面还没出，
            // 若也当成卡顿就会在 12s 后把一条即将出画的正常线路掐掉
            // （实测江苏卫视第 3 条就这么被误杀，多绕了 8s+）。
            if (!renderedThisLine) return;
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
    /** 当前线路的解析缓存 key（kdsvod:// 原始串） */
    private String resolveKey;
    /** prepareAsync 发起时刻，用于统计该线路的首帧耗时 */
    private long prepareAt;
    /** 当前线路是否已 onPrepared（已连上流并填充完 AVFormatContext） */
    private boolean prepared;
    /** 当前线路是否已经出过画面（用于区分「起播缓冲」和「播着播着卡住」） */
    private boolean renderedThisLine;
    private boolean parsing;
    private boolean stopped;
    private int videoW, videoH;
    private android.view.Surface surface;

    // ---------------- 分阶段超时 ----------------
    // 原来用单一 8s 卡首帧：可是 prepareAsync→首帧 在这套 ijk 上并不均匀，
    // 实测（雷电 9 / 9 个频道）正常线路 1.0s~6.4s 都有，慢的到 31s 才出画。
    // 一刀切成 8s 会把「其实能播」的线路提前毙掉，用户看到的就是反复换线 + 长时间黑屏，
    // 观感等同于「播不了」。拆成两段判断才准确：
    //   连接段 prepareAsync→onPrepared：9s，连不上是真没流（源站挂了/线路失效）
    //   出画段 onPrepared→首帧：10s，已经连上只是解码/缓冲慢，值得多等
    private static final long T_CONNECT_MS = 9000;
    private static final long T_FRAME_MS = 10000;
    /** 解析段：引擎跑 JS，正常 0.3~1.5s，个别慢站 10s+，给足 20s */
    private static final long T_PARSE_MS = 20000;

    /** 一次性清掉两个阶段计时器（换线/停止时必须成对清，否则旧计时器会误伤新线路） */
    private void cancelTimeouts() {
        main.removeCallbacks(timeout);
        main.removeCallbacks(connectTimeout);
    }

    private final Runnable timeout = new Runnable() {
        public void run() {
            if (parsing) failToNext("解析超时");
            else if (prepared) failToNext("出画超时");
            else failToNext("连接超时");
        }
    };

    /** 连接段超时：等 onPrepared，到了就换成出画段的计时 */
    private final Runnable connectTimeout = new Runnable() {
        public void run() {
            if (!prepared) {
                Perf.mark("line.connectFail");
                failToNext("连接超时");
            }
        }
    };

    public PlayerEngine(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        parser = new HtmlContentParser(ctx);
        parser.addCallBack(new HtmlContentParser.CallBack() {
            public void startVideo(String res) {
                parsing = false;
                Perf.mark("parse.done");
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
                cachePut(resolveKey, pl.url, pl.headers); // 供下次切回同线路秒开
                playDirect(pl.url, pl.headers);
            }
            public void onError() {
                parsing = false;
                Perf.mark("parse.fail");
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
        cancelTimeouts();
        freezePlayer();   // 切换过程中保留上一帧画面，新线路出帧后再释放
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
                // 之前解析过同一条线路 → 直接复用，跳过引擎（省 300ms~1.5s）
                Resolved cached = cacheGet(pl.url);
                if (cached != null && !cached.url.startsWith("127.0.0.1")
                        && !cached.url.contains("127.0.0.1:19192")) {
                    Perf.mark("parse.cache");
                    resolveKey = pl.url;
                    currentRawUrl = cached.url;
                    currentHeaders = cached.headers;
                    playDirect(cached.url, cached.headers);
                    return;
                }
                resolveKey = pl.url;
                parsing = true;
                Perf.mark("parse.begin");
                // 解析阶段单独计时：引擎跑 JS 正常 0.3~1.5s，个别慢站会到 10s+。
                // 这里不设短超时，否则解析没完就被判死；出画有自己的两段计时。
                main.postDelayed(timeout, T_PARSE_MS);
            } else {
                Perf.mark("line.direct");
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
            cancelTimeouts();
            releaseCurrent();   // 冻结中的上一路保留到新画面出现

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
                // 起播加速：默认 probesize=5MB / analyzeduration=5s，直播场景完全没必要，
                // 前者会让播放器先下几 MB 才起步，后者要攒 5 秒音视频才判定流格式。
                // 调到 512KB / 0.5s 后不改变稳态缓冲与解码策略，只缩短出画时间。
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 524288L);
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 500000L);
                im.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L);
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
            mp.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
                public void onPrepared(IMediaPlayer mp) {
                    prepared = true;
                    // 已连上流：把「连接段」计时换成更宽松的「出画段」计时
                    cancelTimeouts();
                    main.postDelayed(timeout, T_FRAME_MS);
                }
            });
            mp.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
                public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                    if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        cancelTimeouts();
                        main.removeCallbacks(bufferWatchdog);
                        bufferingSince = 0;
                        renderedThisLine = true;
                        Perf.mark("first.frame");
                        releaseOld();   // 新画面已出，放掉冻结的上一路
                        listener.onRendering();
                        long spent = prepareAt > 0 ? System.currentTimeMillis() - prepareAt : 0;
                        prepareAt = 0;
                        listener.onLineOk(currentLineIdx, spent);
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
            Perf.mark("prepare");
            prepareAt = System.currentTimeMillis();
            prepared = false;
            renderedThisLine = false;
            bufferingSince = 0;
            main.removeCallbacks(bufferWatchdog);
            // 连接段先给 9s；一旦 onPrepared 就切成 T_FRAME_MS 的出画段计时。
            main.postDelayed(connectTimeout, T_CONNECT_MS);
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
        cacheDrop(resolveKey);           // 这条解析结果已失效，下次重新解析
        Perf.mark("line.fail", reason);
        if (currentLineIdx + 1 < currentLines.size()) {
            currentLineIdx++;
            playLine(currentLines.get(currentLineIdx));
        } else {
            listener.onFailed(reason);
        }
    }

    private boolean pausedByLifecycle;
    private float savedVolume = 1f;

    /** 切后台/失去焦点时静音 + 暂停（HLS 直播流 pause 可能停不住音轨，静音双保险） */
    public void pausePlayback() {
        if (player == null || pausedByLifecycle) return;
        try {
            player.setVolume(0f, 0f); // 先静音，杜绝后台出声
            player.pause();
            pausedByLifecycle = true;
        } catch (Throwable ignored) {}
    }

    /** 回前台时恢复音量并继续播放 */
    public void resumePlayback() {
        if (player == null || !pausedByLifecycle) return;
        try {
            player.setVolume(savedVolume, savedVolume);
            player.start();
            pausedByLifecycle = false;
        } catch (Throwable ignored) {}
    }

    public boolean isPaused() { return pausedByLifecycle; }

    public void stopAll() {
        stopped = true;
        pausedByLifecycle = false;
        cancelTimeouts();
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
        releaseOld();
        releaseCurrent();
    }

    /** 只释放当前实例，保留冻结中的上一路（切台过渡期间用） */
    private void releaseCurrent() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) {}
            try { player.reset(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    /**
     * 切台时把上一条线路「冻住」而不是立刻销毁：画面停在最后一帧，
     * 新线路出帧后再释放旧实例。否则用户要盯着黑屏等 1~5 秒才看到画面。
     * pause 后不再拉流，不会和新线路抢带宽。
     */
    private void freezePlayer() {
        releaseOld();
        if (player != null) {
            try { player.pause(); } catch (Throwable ignored) {}
            oldPlayer = player;
            player = null;
        }
    }

    private void releaseOld() {
        if (oldPlayer != null) {
            try { oldPlayer.stop(); } catch (Throwable ignored) {}
            try { oldPlayer.reset(); } catch (Throwable ignored) {}
            try { oldPlayer.release(); } catch (Throwable ignored) {}
            oldPlayer = null;
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
