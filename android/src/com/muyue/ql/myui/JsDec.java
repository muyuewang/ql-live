package com.muyue.ql.myui;

/**
 * 引擎算法 JS（encrypt_*.js）的数字串解密。
 *
 * <p>服务商下发的 js.zip 里，算法脚本是「数字串 + RSA 变换」，格式历版恒定：
 *
 * <pre>
 *   x    = N + c            (c 为密文块，0 &lt; c &lt; N)
 *   byte = c^d mod N
 *   JS   = base64( 所有 byte 顺序拼接 )
 * </pre>
 *
 * <p>官方实现把 N/d 写死在解密函数里（旧版 12382/343），服务商一换参数就整套解析失效。
 * 这里改成<b>运行时可还原参数</b>：
 *
 * <ul>
 *   <li>{@code N} 被数字区间夹死：{@code N <= min(x)} 且 {@code 2N > max(x)}，候选只有几十个；
 *   <li>{@code d} 是小整数（历版 343 / 2023），上限 4000 足够；
 * </ul>
 *
 * <p>于是枚举 {@code (N, d)}，用「解出的前若干字节必须落在 base64 字母表内」做判定，
 * 就能把当次下发的参数直接算出来，不再依赖任何随包二进制。
 *
 * <p>纯 Java、零 Android 依赖（自带 base64 解码），因此可以在桌面上直接跑回归。
 */
public final class JsDec {

    /** 已知参数快速通道：{N, d}。命中即免枚举。 */
    private static final int[][] KNOWN = {{12367, 2023}, {12382, 343}};

    /** 枚举 d 的上限。历版最大 2023，留足余量。 */
    private static final int D_MAX = 4000;

    /** 判定用的探测字节数。 */
    private static final int PROBE = 8;

    /** 采样数字个数（够 recover 判定用）。 */
    private static final int SAMPLE = 24;

    /** 上次成功的参数，避免同一份文件反复枚举。 */
    private static volatile int sLastN = 0;
    private static volatile int sLastD = 0;

    /**
     * 解密结果缓存。
     *
     * <p>引擎脚本有三十多万字符，每次切台都重算一遍模幂是纯浪费（ART 上约百毫秒量级）。
     * 用「长度 + hashCode」做指纹几乎不花钱，命中率却是 100%——同一份文件内容不会变。
     */
    private static final int CACHE_SLOTS = 4;
    private static final int[] sKeyLen = new int[CACHE_SLOTS];
    private static final int[] sKeyHash = new int[CACHE_SLOTS];
    private static final String[] sVal = new String[CACHE_SLOTS];
    private static int sCacheIdx = 0;

    private JsDec() {}

    private static String cacheGet(String text) {
        int len = text.length();
        int hash = text.hashCode();
        synchronized (sVal) {
            for (int i = 0; i < CACHE_SLOTS; i++) {
                if (sVal[i] != null && sKeyLen[i] == len && sKeyHash[i] == hash) {
                    return sVal[i];
                }
            }
        }
        return null;
    }

    private static void cachePut(String text, String js) {
        int len = text.length();
        int hash = text.hashCode();
        synchronized (sVal) {
            int i = sCacheIdx;
            sKeyLen[i] = len;
            sKeyHash[i] = hash;
            sVal[i] = js;
            sCacheIdx = (i + 1) % CACHE_SLOTS;
        }
    }

    // ---------------------------------------------------------------- 对外入口

    /**
     * 解密引擎算法脚本；传入明文时原样返回。
     *
     * <p>任何异常都回退为原文，保持与官方实现的容错行为一致。
     */
    public static String decrypt(String text) {
        if (text == null || text.length() < 8) {
            return text;
        }
        if (!looksNumeric(text)) {
            return text; // 已经是明文 JS
        }
        String cached = cacheGet(text);
        if (cached != null) {
            return cached;
        }
        try {
            Shot shot = Shot.scan(text);
            if (shot == null || shot.count < PROBE) {
                return text;
            }
            int[] p = recover(shot);
            if (p == null) {
                return text;
            }
            byte[] raw = shot.decode(text, p[0], p[1]);
            if (raw == null) {
                return text;
            }
            byte[] js = b64(raw);
            if (js == null) {
                return text;
            }
            String out = new String(js, "UTF-8");
            cachePut(text, out);
            // 每份文件只会打一次（命中缓存后不再走到这里）。
            // Android 会把 System.err 收进 logcat 的 System.err 标签，便于真机排查。
            System.err.println("[JsDec] 解密成功 " + lastParams()
                    + " 输入 " + text.length() + " 字符 -> 输出 " + out.length() + " 字符");
            return out;
        } catch (Throwable t) {
            return text;
        }
    }

    /** 上次解出的参数，形如 "N=12367,d=2023"；未解出时为空串。用于日志排查。 */
    public static String lastParams() {
        int n = sLastN;
        int d = sLastD;
        return n == 0 ? "" : ("N=" + n + ",d=" + d);
    }

    // ---------------------------------------------------------------- 参数还原

    static int[] recover(Shot shot) {
        int ln = sLastN;
        int ld = sLastD;
        if (ln != 0 && probeOk(shot.sample, shot.sampleLen, ln, ld)) {
            return new int[]{ln, ld};
        }
        for (int[] p : KNOWN) {
            if (probeOk(shot.sample, shot.sampleLen, p[0], p[1])) {
                sLastN = p[0];
                sLastD = p[1];
                return p;
            }
        }
        long lo = shot.max / 2 + 1;
        long hi = shot.min;
        if (lo >= hi) {
            // 区间太窄（可能不是这种编码），仍然扫一遍最可能的几个值
            lo = hi - 8;
            if (lo < 1) {
                lo = 1;
            }
        }
        long x0 = shot.sample[0];
        for (long n = lo; n <= hi; n++) {
            if (x0 <= n || n > Integer.MAX_VALUE) {
                continue;
            }
            long c1 = x0 - n;
            long v = 1;
            for (int d = 1; d <= D_MAX; d++) {
                v = (v * c1) % n;
                if (v > 255 || !isB64((int) v)) {
                    continue;
                }
                if (probeOk(shot.sample, shot.sampleLen, (int) n, d)) {
                    sLastN = (int) n;
                    sLastD = d;
                    return new int[]{(int) n, d};
                }
            }
        }
        return null;
    }

    static boolean probeOk(long[] sample, int len, int n, int d) {
        int seen = 0;
        for (int i = 0; i < len; i++) {
            long x = sample[i];
            if (x <= n) {
                continue;
            }
            long b = modPow(x - n, d, n);
            if (b > 255 || !isB64((int) b)) {
                return false;
            }
            if (++seen >= PROBE) {
                return true;
            }
        }
        return false;
    }

    /** base^exp mod m，long 中间量足够（m 远小于 2^31）。 */
    static long modPow(long base, long exp, long m) {
        if (m <= 1) {
            return 0;
        }
        long result = 1;
        long b = base % m;
        while (exp > 0) {
            if ((exp & 1) == 1) {
                result = (result * b) % m;
            }
            b = (b * b) % m;
            exp >>= 1;
        }
        return result;
    }

    private static boolean isB64(int b) {
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9') || b == '+' || b == '/' || b == '=';
    }

    // ---------------------------------------------------------------- 文本扫描

    /** 快速判断是否为「加密数字串」形态（不是明文 JS）。 */
    static boolean looksNumeric(String s) {
        boolean any = false;
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                any = true;
            } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return false;
            }
        }
        return any;
    }

    /**
     * 第一遍扫描的结果：数字区间、总个数、开头若干个采样值。
     *
     * <p>只存采样值而不是全部数字，避免几万个 Long 撑爆内存（大脚本 base64 有 46 万字符）。
     */
    static final class Shot {
        final long min;
        final long max;
        final int count;
        final long[] sample;
        final int sampleLen;

        private Shot(long min, long max, int count, long[] sample, int sampleLen) {
            this.min = min;
            this.max = max;
            this.count = count;
            this.sample = sample;
            this.sampleLen = sampleLen;
        }

        static Shot scan(String s) {
            long min = Long.MAX_VALUE;
            long max = 0;
            int count = 0;
            long[] sample = new long[SAMPLE];
            int sampleLen = 0;
            final int len = s.length();
            int i = 0;
            while (i < len) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                    continue;
                }
                if (c < '0' || c > '9') {
                    return null;
                }
                long v = 0;
                while (i < len) {
                    char d = s.charAt(i);
                    if (d < '0' || d > '9') {
                        break;
                    }
                    v = v * 10 + (d - '0');
                    if (v > Integer.MAX_VALUE) {
                        v = Integer.MAX_VALUE; // 异常输入，钳制避免溢出
                    }
                    i++;
                }
                if (sampleLen < SAMPLE) {
                    sample[sampleLen++] = v;
                }
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
                count++;
            }
            if (count == 0 || sampleLen == 0) {
                return null;
            }
            return new Shot(min, max, count, sample, sampleLen);
        }

        /** 第二遍扫描：按参数解出全部字节。 */
        byte[] decode(String s, int n, int d) {
            byte[] raw = new byte[count];
            int idx = 0;
            final int len = s.length();
            int i = 0;
            while (i < len) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                    continue;
                }
                long v = 0;
                while (i < len) {
                    char e = s.charAt(i);
                    if (e < '0' || e > '9') {
                        break;
                    }
                    v = v * 10 + (e - '0');
                    i++;
                }
                if (v <= n) {
                    return null;
                }
                long b = modPow(v - n, d, n);
                if (b > 255) {
                    return null;
                }
                if (idx >= raw.length) {
                    return null;
                }
                raw[idx++] = (byte) b;
            }
            return idx == raw.length ? raw : null;
        }
    }

    // ---------------------------------------------------------------- base64

    /** 标准 base64 解码；容忍换行/空白、无 padding。 */
    static byte[] b64(byte[] in) {
        byte[] out = new byte[in.length * 3 / 4 + 3];
        int o = 0;
        int acc = 0;
        int bits = 0;
        for (byte value : in) {
            int c = value & 0xff;
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+') {
                v = 62;
            } else if (c == '/') {
                v = 63;
            } else if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            } else {
                return null;
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (o >= out.length) {
                    return null;
                }
                out[o++] = (byte) ((acc >> bits) & 0xff);
                acc &= (1 << bits) - 1;
            }
        }
        if (o == out.length) {
            return out;
        }
        byte[] trim = new byte[o];
        System.arraycopy(out, 0, trim, 0, o);
        return trim;
    }
}
