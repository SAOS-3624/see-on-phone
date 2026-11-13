package com.dsh.eye;

/**
 * Tiers —— 档位：这一帧该占多大格子。
 *
 * **`dsh-see-now/src/tiers.js` 的逐行移植**，不是另写一套。因果版：密度 → 卷积平滑 →
 * 7 道阈值 + 滞回 → 1..8 档。
 *
 * ## 与 docx / storyboard 的三处换算（照抄 JS 注释，改之前先读）
 *
 * docx 写死了三件事：`dt = 100 帧`、阈值是「**每帧**碰上切点的概率」、默认 30fps。
 * 三者绑死，单独改 dt 会让整套阈值失效。所以这里阈值整体换成 **「帧/秒」**（= 原表 × 30），
 * 跟 fps 脱钩，`dt` 才真的变成「看过去几秒」：
 *
 *   [0.26, 0.6, 0.9, 1.2, 1.8, 2.4, 3.0] 帧/秒
 *
 * ## 密度数的是什么
 *
 * 数**所有边界**（切点 + 积分帧 + 兜底帧），也就是「每秒留下几帧」——
 * 它跟「一张图能放几格」是同一件事：档 k 的格子占比 1/k²，一张图放 k² 格。
 *
 * ## 两处因果化
 *
 * - 密度窗：居中 `[t-dt/2, t+dt/2]` → **只看过去** `[t-dt, t]`
 * - 平滑：`movmean(50 帧)` → 每帧走一步的一阶 EMA
 *
 * `tick(t)` 必须**每帧都调**，不能只在留帧时调 —— 只在留帧时调的话 EMA 的步长跟着
 * 留帧间隔走，两帧留得远就一步跳满，密度会瞬间冲顶。
 */
final class Tiers {

    /** 帧/秒。docx 的 7 道阈值 × 30fps。 */
    static final double[] LEVEL_THRESHOLDS = { 0.26, 0.6, 0.9, 1.2, 1.8, 2.4, 3.0 };

    /**
     * 感知档位阈值 —— 用「人眼瞬时记忆 / 主观现在」的实测时长当端点重排的 7 道。
     * 用 `tierCurve: "perceptual"` 打开；默认仍是 `docx`。
     */
    static final double[] PERCEPTUAL_THRESHOLDS = { 0.333, 0.489, 0.718, 1.054, 1.547, 2.271, 3.333 };

    static final double HYSTERESIS = 0.05;
    static final int TIER_MIN = 1;
    static final int TIER_MAX = 8;
    /** 自由尺寸（补位帧用）。它本来就"占剩下的地方"，不参与档位映射。 */
    static final int FREE = 0;

    private Tiers() { }

    // ------------------------------------------------------------------ 策略

    /**
     * 逐档查表：档位 1..8 → 目标档位。
     * 表里的坏值**逐项忽略，保留原档位** —— 一份手改坏的 `tierMap` 不该把整个画面搞崩。
     */
    static int[] applyTierMap(int[] tiers, int[] map) {
        int[] out = new int[tiers.length];
        for (int i = 0; i < tiers.length; i += 1) {
            int n = tiers[i];
            if (map == null) { out[i] = n; continue; }
            if (n == 0) { out[i] = 0; continue; }          // 自由尺寸不参与映射
            int idx = Math.max(TIER_MIN, Math.min(TIER_MAX, n)) - 1;
            int v = map[idx];
            out[i] = (v >= TIER_MIN && v <= TIER_MAX) ? v : n;
        }
        return out;
    }

    /**
     * 策略表 —— **`dsh-see-now/src/tiers.js` 的 `STRATEGIES` 的同一张表**（同名同值）。
     *
     * 每个值是三元组 `{bump, floor, fixed}`：
     *
     * | 键 | bump | floor | fixed | 意思 |
     * |---|---|---|---|---|
     * | `native`   | 0 | 0 | 0 | 不压：算到几档就几档 |
     * | `standard` | 0 | 2 | 0 | **1 档压成 1/2**，其余不动 —— 桌面默认 |
     * | `full`     | -1 | 1 | 0 | 格子更大（先降一档，最低 1 档） |
     * | `compact`  | 1 | 0 | 0 | 格子更小（升一档） |
     * | `single`   | 0 | 0 | 1 | 全部固定 1 档（逐帧模式：一张图 = 一个整幅画面） |
     *
     * `custom` **故意不在这张表里**：它要逐档查 `tierMap`，不是"bump/floor/fixed"
     * 能表达的，所以 `applyStrategy` 在查表之前就把它分流出去了。
     *
     * 为什么用表而不是 switch：这张表和 JS 那边是一对一的，表能一眼对完五行；
     * switch 加一档要动三处（case / 变量声明 / 注释），改错一个就静默走 default。
     */
    private static final java.util.Map<String, int[]> STRATEGIES = strategies();

    /** 表里下标的含义（别用魔法数字 0/1/2 直接读，见 `STRATEGIES`）。 */
    private static final int BUMP = 0;
    private static final int FLOOR = 1;
    private static final int FIXED = 2;

    private static java.util.Map<String, int[]> strategies() {
        java.util.Map<String, int[]> m = new java.util.HashMap<>(8);
        //                     bump, floor, fixed
        m.put("native",   new int[] {  0, 0, 0 });
        m.put("standard", new int[] {  0, 2, 0 });
        m.put("full",     new int[] { -1, 1, 0 });
        m.put("compact",  new int[] {  1, 0, 0 });
        m.put("single",   new int[] {  0, 0, 1 });
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * 档位映射。与 `tiers.js::applyStrategy` 同义（**行为必须一字不差**）：
     *
     * 1. `custom` → 交给 {@link #applyTierMap}（逐档查 `map`）；
     * 2. 其余按 {@link #STRATEGIES} 查三元组 `{bump, floor, fixed}`：
     *    - `fixed = 1`：每帧一律压到 1 档（`0` 自由尺寸原样保留，它是补位帧）；
     *    - 否则：`v = tier + bump`，`floor > 0` 时 `v = max(floor, v)`，最后夹到 1..8；
     * 3. **未知值退回 `native`**（表里查不到就当 `{0,0,0}`）—— 一份写错档名的
     *    `tuning.json` 不该让整个画面没有档位。
     *
     * @param tiers    原始档位（1..8，0 = 自由尺寸补位帧）
     * @param strategy `native` / `standard` / `full` / `compact` / `single` / `custom`
     * @param map      只在 `custom` 时用：8 个数，第 i 个是"档位 i+1 映射到哪一档"
     */
    static int[] applyStrategy(int[] tiers, String strategy, int[] map) {
        String s = strategy == null ? "native" : strategy;
        if ("custom".equals(s)) return applyTierMap(tiers, map);
        int[] rule = STRATEGIES.get(s);
        if (rule == null) rule = STRATEGIES.get("native");     // 未知值 → native
        int bump = rule[BUMP];
        int floor = rule[FLOOR];
        boolean fixed = rule[FIXED] != 0;
        int[] out = new int[tiers.length];
        for (int i = 0; i < tiers.length; i += 1) {
            int t = tiers[i];
            if (fixed) { out[i] = (t == 0) ? 0 : 1; continue; }
            int v = t + bump;
            if (floor > 0) v = Math.max(floor, v);
            out[i] = Math.max(TIER_MIN, Math.min(TIER_MAX, v));
        }
        return out;
    }

    // ------------------------------------------------------------------ 状态机

    /** 一条档位时间线（每帧 `tick`，留帧时 `mark`）。 */
    static final class Tracker {
        private final int fps;
        private final int dtFrames;
        private final double alpha;
        private final double[] up;
        private final double[] down;

        private double[] bounds = new double[1024];
        private int boundsLen = 0;
        private double smoothed = 0;
        private int level = 1;

        Tracker(int fps, int dtFrames, int smoothFrames, String curve, double[] thresholds) {
            this.fps = Math.max(1, fps);
            this.dtFrames = Math.max(1, dtFrames);
            this.alpha = 1.0 / Math.max(1, smoothFrames);
            double[] table = thresholds;
            if (table == null || table.length != 7) {
                table = "perceptual".equals(curve) ? PERCEPTUAL_THRESHOLDS : LEVEL_THRESHOLDS;
            }
            this.up = new double[7];
            this.down = new double[7];
            for (int i = 0; i < 7; i += 1) {
                up[i] = table[i] * (1 + HYSTERESIS);
                down[i] = table[i] * (1 - HYSTERESIS);
            }
        }

        double widthSec() { return (double) dtFrames / fps; }

        /** 记一个边界（帧被留下的时刻，单位秒）。 */
        void mark(double t) {
            if (boundsLen == bounds.length) {
                double[] grow = new double[bounds.length * 2];
                System.arraycopy(bounds, 0, grow, 0, boundsLen);
                bounds = grow;
            }
            bounds[boundsLen] = t;
            boundsLen += 1;
        }

        /**
         * 每帧调一次：更新密度与档位。**不含当前帧**（它还没被 mark）。
         *
         * @return 当前档位 1..8
         */
        int tick(double t) {
            double w = widthSec();
            double lo = t - w;
            int i = boundsLen - 1;
            int n = 0;
            while (i >= 0 && bounds[i] > lo) { n += 1; i -= 1; }
            double raw = n / w;
            smoothed += (raw - smoothed) * alpha;

            double val = smoothed;
            if (level < TIER_MAX && val > up[level - 1]) {
                level = 1 + countAtLeast(val, up);
            } else if (level > TIER_MIN && val < down[level - 2]) {
                level = 1 + countAtLeast(val, down);
            }
            level = Math.max(TIER_MIN, Math.min(TIER_MAX, level));

            // 窗口外的边界再也用不到（多留 1s 余量），别让数组无限长
            if (i > 64) {
                int drop = i - 1;
                System.arraycopy(bounds, drop, bounds, 0, boundsLen - drop);
                boundsLen -= drop;
            }
            return level;
        }

        int levelAt() { return level; }
        double density() { return smoothed; }

        private static int countAtLeast(double val, double[] arr) {
            int k = 0;
            for (double v : arr) if (val >= v) k += 1;
            return k;
        }
    }
}
