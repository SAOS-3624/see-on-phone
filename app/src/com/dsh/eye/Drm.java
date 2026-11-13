package com.dsh.eye;

/**
 * `Sec-MS-GEC` 令牌 —— 连 Edge 朗读服务要带的那串 `<16位hex>-<64位hex>`。
 *
 * ## 这是什么，为什么要逐位搬
 *
 * 微软给"Edge 朗读"这个免费接口加了一道闸：URL 上必须带 `Sec-MS-GEC`，
 * 算法是**五分钟一格的 Windows FILETIME** 拼上固定串，再取两个哈希：
 * CS64（16 位 hex）和 SHA256（64 位 hex）。
 *
 * 2026-11 的 edge-tts 7.3.1 里，这两个哈希**都被改写过**（不是标准 SHA256：
 * 初始状态由前 7 字节推出、还带一堆调整常量）。实测拿假令牌去连，服务端直接 403 ——
 * 所以没有"简化版"可走，只能照着官方实现一位一位搬。
 *
 * **正确性靠对拍**：`.tmp/drm/vec.txt` 是用官方 Python 实现生成的 12 组
 * `时间戳 → 令牌`，`VecTest` 逐组比对。改这个文件之前先跑它。
 *
 * ## 和 Python 原版的对应关系
 *
 * | Python | 这里 |
 * |---|---|
 * | `_sha256_digest` | {@link #sha256Digest} |
 * | `_sha256_init` | {@link #sha256Init} |
 * | `_sha256_rounds` | {@link #sha256Rounds} |
 * | `_expand_sha256_schedule` | {@link #expandSchedule} |
 * | `_cs64_digest` | {@link #cs64Digest} |
 * | `generate_sec_ms_gec_v2` | {@link #token} |
 *
 * ⚠️ **Python 的 `>>` 在这里必须写 `>>>`**：Python 那边所有值都掩到 32 位，
 * 是无符号右移；Java 的 `int >>` 是算术右移，负数会把高位补 1，结果就不一样了。
 * 反过来 `<<` 天然截断到 32 位，和 Python 的 `(x << n) & 0xFFFFFFFF` 等价。
 */
final class Drm {

    /** 写死在 edge-tts `constants.py` 里的固定串，不是密钥。 */
    static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";

    /** 1970 → 1601 的秒差（Windows FILETIME 纪元）。 */
    private static final long WIN_EPOCH = 11644473600L;

    /** 时钟偏移（秒）。手机时间不准会 403，从 HTTP `Date` 头校正。 */
    private static volatile double skewSeconds;

    private Drm() { }

    static void setSkew(double s) { skewSeconds = s; }

    static double skew() { return skewSeconds; }

    /**
     * 生成当前时刻的令牌。五分钟一格，所以同一个 5 分钟窗口里反复调用结果一样
     * （缓存一下不亏，但这里没缓 —— 一天才 288 个值）。
     */
    static String token() {
        return token(System.currentTimeMillis() / 1000.0 + skewSeconds);
    }

    /** 给定 unix 秒（可带小数）生成令牌。测试用固定值。 */
    static String token(double unix) {
        double ticks = unix + WIN_EPOCH;
        ticks -= ticks % 300;
        ticks *= 1e7;                     // 100ns 单位
        // ⚠️ 用 %.0f 而不是 (long)（会截断）也不是 Math.round（负数方向不同）：
        //    Python 的 f"{x:.0f}" 就是四舍五入到最近的整数，和这里一致。
        String s = String.format(java.util.Locale.ROOT, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN;
        byte[] data = s.getBytes(java.nio.charset.Charset.forName("US-ASCII"));
        byte[] digest = sha256Digest(data);
        int[] cs = cs64Digest(data, digest);
        return hexLe32(cs[0]) + hexLe32(cs[1]) + "-" + hexBe(digest);
    }

    // ---------------------------------------------------------------- 常量表

    private static final int[] SHA256_INITIAL = {
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    };

    /** 这是个"已经跑了 14 轮"的状态，不是标准 IV。 */
    private static final int[] SHA256_STATE = {
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    };

    private static final int[] SHA256_SCHEDULE = {
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    };

    private static final int[] ROUND = {
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4,
        0xAB1C5ED5, 0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE,
        0x9BDC06A7, 0xC19BF174, 0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F,
        0x4A7484AA, 0x5CB0A9DC, 0x76F988DA, 0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7,
        0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967, 0x27B70A85, 0x2E1B2138, 0x4D2C6DFC,
        0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85, 0xA2BFE8A1, 0xA81A664B,
        0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070, 0x19A4C116,
        0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7,
        0xC67178F2,
    };

    private static final int SHA256_ADJUSTMENT = 0xC9DC06A7;

    /** 第 14 个常量被换掉了（只在"从第 14 轮接着跑"时用）。 */
    private static final int[] BLOCK = blockConstants();

    private static final int SIGMA0_ADJUSTMENT = 0x059C0B80;
    private static final int SIGMA1_ADJUSTMENT = 0x000B92C0;

    private static int[] blockConstants() {
        int[] a = new int[64];
        System.arraycopy(ROUND, 0, a, 0, 14);
        a[14] = SHA256_ADJUSTMENT;
        System.arraycopy(ROUND, 15, a, 15, 64 - 15);
        return a;
    }

    private static final int[][] CS64 = {
        { 0xE82E91B1, 0xB32700D0 }, { 0x44DE24C6, 0x88DFF51D },
        { 0x3BEB65BC, 0x29514038 }, { 0xC89C5933, 0xB328CBDF },
        { 0x8D1EC211, 0xD405B4D5 }, { 0x314EF5D5, 0x26E55F3C },
        { 0xE92491B1, 0x607801C6 }, { 0xF0A5B9D5, 0x82B2C1FB },
        { 0x06064CF3, 0xFA51A3DA }, { 0xDA79FA60, 0x207CC464 },
        { 0xBED20560, 0x3C3C74F3 }, { 0x89F9C9F8, 0xD2D14B61 },
        { 0x35FA38C6, 0xBB04A2C1 }, { 0xCB49E3C6, 0xC5626167 },
    };
    private static final int CS64_FACTOR = 0x82893933;
    private static final int CS64_ADJ0 = 0xF2F90B1A;
    private static final int CS64_ADJ1 = 0x0B1A0000;

    // ---------------------------------------------------------------- 基础设施

    private static int rol16(int v) {
        return (v << 16) | (v >>> 16);
    }

    private static int sigma0(int v) {
        return (v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3);
    }

    private static int sigma1(int v) {
        return (v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10);
    }

    /** 小端 32 位字，凑成偶数个（CS64 两两配对，少一个会越界）。 */
    private static int[] wordsLe4Even(byte[] data) {
        int len = (data.length + 3) / 4;
        int n = (len % 2 == 0) ? len : len + 1;
        int[] w = new int[Math.max(n, 0)];
        for (int i = 0; i < len; i += 1) {
            int b0 = i * 4 < data.length ? data[i * 4] & 0xff : 0;
            int b1 = i * 4 + 1 < data.length ? data[i * 4 + 1] & 0xff : 0;
            int b2 = i * 4 + 2 < data.length ? data[i * 4 + 2] & 0xff : 0;
            int b3 = i * 4 + 3 < data.length ? data[i * 4 + 3] & 0xff : 0;
            w[i] = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
        }
        return w;
    }

    private static String hexLe32(int v) {
        return String.format(java.util.Locale.ROOT, "%02X%02X%02X%02X",
            v & 0xff, (v >>> 8) & 0xff, (v >>> 16) & 0xff, (v >>> 24) & 0xff);
    }

    private static String hexBe(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02X", x & 0xff));
        return sb.toString();
    }

    // ---------------------------------------------------------------- SHA256（改写版）

    /**
     * 跑若干轮。`firstRound` 不是 0 的时候用 `BLOCK` 那套常量 ——
     * 官方实现就是这么"从第 14 轮接着跑"的，不是能省就省的优化。
     */
    private static int[] sha256Rounds(int[] state, int[] words, int firstRound) {
        int a = state[0], b = state[1], c = state[2], d = state[3];
        int e = state[4], f = state[5], g = state[6], h = state[7];
        int[] constants = (firstRound == 14) ? BLOCK : ROUND;
        int aligned = Math.min(64, ((firstRound + 7) / 8) * 8);

        for (int index = firstRound; index < aligned; index += 1) {
            long rotE = (e & 0xFFFFFFFFL) * 0x100000001L;
            long rotA = (a & 0xFFFFFFFFL) * 0x100000001L;
            int xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            int xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            int t1 = h + xe + (g ^ (e & (f ^ g))) + constants[index] + words[index];
            int t2 = xa + ((a & b) | (c & (a | b)));
            int nA = t1 + t2;
            int nD = d + t1;
            h = g; g = f; f = e; e = nD;
            d = c; c = b; b = a; a = nA;
        }

        // 官方实现把剩下 8 轮一份手写展开（寄存器角色在组内轮换），
        // **必须照样展开**：这里的运算顺序和逐轮循环不等价（省了几次搬移）。
        for (int index = aligned; index < 64; index += 8) {
            long rotE = (e & 0xFFFFFFFFL) * 0x100000001L;
            long rotA = (a & 0xFFFFFFFFL) * 0x100000001L;
            int xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            int xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            int t1 = h + xe + (g ^ (e & (f ^ g))) + constants[index] + words[index];
            d = d + t1;
            h = t1 + xa + ((a & b) | (c & (a | b)));

            rotE = (d & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (h & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = g + xe + (f ^ (d & (e ^ f))) + constants[index + 1] + words[index + 1];
            c = c + t1;
            g = t1 + xa + ((h & a) | (b & (h | a)));

            rotE = (c & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (g & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = f + xe + (e ^ (c & (d ^ e))) + constants[index + 2] + words[index + 2];
            b = b + t1;
            f = t1 + xa + ((g & h) | (a & (g | h)));

            rotE = (b & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (f & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = e + xe + (d ^ (b & (c ^ d))) + constants[index + 3] + words[index + 3];
            a = a + t1;
            e = t1 + xa + ((f & g) | (h & (f | g)));

            rotE = (a & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (e & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = d + xe + (c ^ (a & (b ^ c))) + constants[index + 4] + words[index + 4];
            h = h + t1;
            d = t1 + xa + ((e & f) | (g & (e | f)));

            rotE = (h & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (d & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = c + xe + (b ^ (h & (a ^ b))) + constants[index + 5] + words[index + 5];
            g = g + t1;
            c = t1 + xa + ((d & e) | (f & (d | e)));

            rotE = (g & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (c & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = b + xe + (a ^ (g & (h ^ a))) + constants[index + 6] + words[index + 6];
            f = f + t1;
            b = t1 + xa + ((c & d) | (e & (c | d)));

            rotE = (f & 0xFFFFFFFFL) * 0x100000001L;
            rotA = (b & 0xFFFFFFFFL) * 0x100000001L;
            xe = (int) ((rotE >>> 6) ^ (rotE >>> 11) ^ (rotE >>> 25));
            xa = (int) ((rotA >>> 2) ^ (rotA >>> 13) ^ (rotA >>> 22));
            t1 = a + xe + (h ^ (f & (g ^ h))) + constants[index + 7] + words[index + 7];
            e = e + t1;
            a = t1 + xa + ((b & c) | (d & (b | c)));
        }
        return new int[] { a, b, c, d, e, f, g, h };
    }

    private static void expandSchedule(int[] words, int firstWord) {
        for (int index = firstWord; index < 64; index += 1) {
            int x = words[index - 2];
            int y = words[index - 15];
            int s1 = (x >>> 17) ^ (x << 15) ^ (x >>> 19) ^ (x << 13) ^ (x >>> 10);
            int s0 = (y >>> 7) ^ (y << 25) ^ (y >>> 18) ^ (y << 14) ^ (y >>> 3);
            words[index] = s1 + words[index - 7] + s0 + words[index - 16];
        }
    }

    /** 用**前 7 个字节**推出"已经跑过一段"的初始状态。 */
    private static int[] sha256Init(byte[] data) {
        int[] w = new int[64];
        w[14] = ((data[0] & 0xff) << 16) | ((data[1] & 0xff) << 8) | (data[2] & 0xff);
        w[15] = ((data[3] & 0xff) << 24) | ((data[4] & 0xff) << 16)
            | ((data[5] & 0xff) << 8) | (data[6] & 0xff);

        for (int index = 16; index < 30; index += 1) {
            int value = SHA256_SCHEDULE[index - 16];
            if (index - 2 >= 14) {
                value += (index == 16) ? (sigma1(w[index - 2]) ^ SIGMA1_ADJUSTMENT)
                                       : sigma1(w[index - 2]);
            }
            if (index - 7 >= 14) value += w[index - 7];
            if (index - 15 >= 14) {
                value += (index == 29) ? (sigma0(w[index - 15]) ^ SIGMA0_ADJUSTMENT)
                                       : sigma0(w[index - 15]);
            }
            w[index] = value;
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA256_ADJUSTMENT - ROUND[14];
        expandSchedule(w, 31);

        int[] working = sha256Rounds(SHA256_STATE, w, 14);
        int[] out = new int[8];
        for (int i = 0; i < 8; i += 1) out[i] = SHA256_INITIAL[i] + working[i];
        return out;
    }

    static byte[] sha256Digest(byte[] data) {
        if (data.length < 7) throw new IllegalArgumentException("令牌原料至少 7 字节");
        int length = 57 + data.length;
        int zeros = (55 - length) % 64;
        if (zeros < 0) zeros += 64;
        byte[] padded = new byte[data.length - 7 + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, data.length - 7);
        int p = data.length - 7;
        padded[p] = (byte) 0x80;
        p += 1 + zeros;
        long bits = (long) length * 8;
        for (int i = 7; i >= 0; i -= 1) padded[p + (7 - i)] = (byte) ((bits >>> (i * 8)) & 0xff);

        int[] state = sha256Init(data);
        for (int off = 0; off < padded.length; off += 64) {
            int[] w = new int[64];
            for (int i = 0; i < 16; i += 1) {
                int b = off + i * 4;
                w[i] = ((padded[b] & 0xff) << 24) | ((padded[b + 1] & 0xff) << 16)
                    | ((padded[b + 2] & 0xff) << 8) | (padded[b + 3] & 0xff);
            }
            expandSchedule(w, 16);
            int[] working = sha256Rounds(state, w, 0);
            for (int i = 0; i < 8; i += 1) state[i] = state[i] + working[i];
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i += 1) {
            out[i * 4] = (byte) ((state[i] >>> 24) & 0xff);
            out[i * 4 + 1] = (byte) ((state[i] >>> 16) & 0xff);
            out[i * 4 + 2] = (byte) ((state[i] >>> 8) & 0xff);
            out[i * 4 + 3] = (byte) (state[i] & 0xff);
        }
        return out;
    }

    // ---------------------------------------------------------------- CS64

    static int[] cs64Digest(byte[] data, byte[] digest) {
        int keyA = ((digest[0] & 0xff) | ((digest[1] & 0xff) << 8)
            | ((digest[2] & 0xff) << 16) | ((digest[3] & 0xff) << 24)) | 1;
        int keyB = ((digest[4] & 0xff) | ((digest[5] & 0xff) << 8)
            | ((digest[6] & 0xff) << 16) | ((digest[7] & 0xff) << 24)) | 1;

        int[] factors = { keyA * CS64_FACTOR, keyB * CS64_FACTOR };

        byte[] salted = new byte[data.length + 1];
        salted[0] = 0;
        System.arraycopy(data, 0, salted, 1, data.length);
        int[] src = wordsLe4Even(salted);

        int n = CS64.length + src.length;
        int[] terms = new int[n];
        int[] offsets = new int[n];
        for (int i = 0; i < CS64.length; i += 1) {
            terms[i] = CS64[i][0] * factors[i % 2];
            offsets[i] = CS64[i][1];
        }
        for (int i = 0; i < src.length; i += 1) {
            int word = src[i];
            int term = word * (i % 2 == 0 ? keyA : keyB);
            int offset = rol16(word) * (i % 2 == 0 ? 0xEF0569FB : 0xC31713DB);
            if (i == 0) {
                term += CS64_ADJ0 * factors[0];
                offset += CS64_ADJ1;
            }
            terms[CS64.length + i] = term;
            offsets[CS64.length + i] = offset;
        }

        int swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int x = terms[i];
            int y = terms[i + 1];

            int t = rol16(offsets[i] + x);
            int q = t * 0x76F50F34;
            swapAcc += rol16(q) * 0xC3EFEA97 + q;

            t = rol16(offsets[i + 1] + y);
            q = t * 0x1881CE3C;
            swapLast = rol16(q) * 0x35BD1EC9 + q;

            swapAcc += swapLast;

            state = state * keyA + x;
            state = rol16(state) * 0xCF98B111;
            state = rol16(state) * 0x87085B9F;
            state = rol16(state) * 0x12CEB96D;
            state = rol16(state) * 0x257E1D83;
            acc += state;

            t = state * keyB + y;
            t = rol16(t) * 0xA27416F5;
            t = rol16(t) * 0xD38396FF;
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69;
            acc += state;
        }
        return new int[] { swapLast ^ state, swapAcc ^ acc };
    }
}
