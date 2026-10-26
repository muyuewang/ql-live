package com.muyue.ql.myui;

import android.util.Log;

/**
 * 切台耗时打点。输出到 logcat 标签 QLPERF，格式：
 *   ▶ 切台 CCTV-1
 *      lines.cache      +2ms    (累计 2ms)
 *      parse.done       +180ms  (累计 182ms)
 *      first.frame      +420ms  (累计 602ms)
 * 用法：logcat -s QLPERF
 */
public class Perf {

    public static final String TAG = "QLPERF";

    /** 打点总开关：正式版也保留，开销只是几次 Log.i */
    public static boolean ON = true;

    private static final long T0 = System.currentTimeMillis();
    private static long lastMarkAt = T0;
    private static String cur = "";

    /** 开始一次切台计时 */
    public static synchronized void start(String name) {
        cur = name == null ? "" : name;
        lastMarkAt = System.currentTimeMillis();
        if (ON) Log.i(TAG, "▶ 切台 " + cur);
    }

    /** 标记一个阶段完成 */
    public static synchronized void mark(String what) {
        if (!ON) return;
        long now = System.currentTimeMillis();
        Log.i(TAG, String.format("   %-14s +%-6d (累计 %dms) %s",
                what, now - lastMarkAt, now - T0, cur));
        lastMarkAt = now;
    }

    /** 阶段附带说明信息 */
    public static synchronized void mark(String what, String extra) {
        if (!ON) return;
        long now = System.currentTimeMillis();
        Log.i(TAG, String.format("   %-14s +%-6d (累计 %dms) %s %s",
                what, now - lastMarkAt, now - T0, cur, extra == null ? "" : extra));
        lastMarkAt = now;
    }
}
