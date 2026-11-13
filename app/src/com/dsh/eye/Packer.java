package com.dsh.eye;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Packer —— 宫格排布器。**这是 `dsh-see-now/src/pack.js` 的逐行移植**，不是另写一套。
 *
 * 为什么要在 App 里再实现一遍：`Sheet` 原来只做「等分格 + 编号」（1 帧整幅 / 2 帧并排 /
 * 3~4 帧 2×2）。等分格有两个硬伤，电脑版都没有：
 *
 * ① **按帧数定版式，不按帧的重要性**。电脑版每帧有自己的档位（1/1、1/4、1/9…），
 *    变化大的给大格、变化小的给小格，一张图里大小格混排 —— 这才是"宫格"的信息量所在。
 * ② **3 帧时右下角白白空着 25%**，5 帧以上根本放不下（现在直接截断）。
 *
 * 电脑版的做法是**行式 DP 装箱**：每行可以是任意连续的一段帧，行高取该行最宽的格，
 * 目标函数 `Σ面积 − 箱数 × 1.02`（多一张图要真付一份钱，所以同占用率时选箱数少的）。
 * 这套东西没有替代品 —— `Sheet.compose` 换成它之后，出来的版面才和 `see_now` 一致。
 *
 * ## 和 JS 原版的对应关系（改任何一处都要两边一起改）
 *
 * | 这里 | pack.js |
 * |---|---|
 * | `bounds(k)` | `bounds(k, grow)` |
 * | `rowOptions(...)` | `rowOptions(...)`：夹预算 + 查缓存，然后落 `rowOptionsRaw` |
 * | `solve(tiers, stretch, cropX, cropY, binCost)` | `solve(tiers, {stretch, binCost, crop})` |
 * | `toRects(...)` | `toRects(cells, {width, height, gutter})` |
 * | `stretchForCrop` / `effectiveStretch` | 同名函数（0.7.4 的裁切预算） |
 *
 * 没搬的：`solveSkyline`（`packMode:'skyline'` 是电脑版的**实验开关**，默认走行式）、
 * `splitOneBin`（那是"一批帧装不下时怎么切段"的调度逻辑，App 侧由 `Watcher` 按
 * `maxFrames` 截断，不需要）。
 *
 * 几何约定与 JS 一致：在**归一化方形容器** [0,1]² 里排方块，边长 s = 占容器宽度的比例；
 * 行高 = 该行最大边长；行内上沿对齐。换回像素时格子自然变成画布比例的矩形。
 */
final class Packer {

    /** 允许"升"的档位（1 和 1/2 一律不许升：它们已经是"满格"了）。 */
    static final int[] DEFAULT_GROW = {3, 4, 5, 6, 7, 8};
    /** 高度网格精度：容器宽度分成多少份。840 = lcm(1..8)，所有档位都是整数。 */
    static final int UNIT = 840;
    /**
     * 想要的最大宽高比容差（与 storyboard / pack.js 同一个数）。
     *
     * ⚠️ **它只是"想要"**：实际能用多少由裁切预算决定（见 `effectiveStretch`）。
     * 默认预算 0 → 实际拉伸 0，这个 0.3 一格都用不上。想让拉伸生效，调用方必须
     * 明确给出"这幅画面最多能裁多少"，而不是靠这里的默认值。
     */
    static final double DEFAULT_STRETCH = 0.3;
    /**
     * 默认一格都不裁。
     *
     * 拉伸（格内纵向拉高）的代价是**裁掉画面**：拉 s 倍就等于 cover 时裁 `1 − 1/(1+s)`。
     * 所以"能拉多少"不该由排布器自己决定，必须由调用方给出"这幅画面最多能裁多少"的预算。
     * 不给预算（= 这里）就一个像素都不裁 —— 用户要的是"看到的画面完整"。
     */
    static final double[] DEFAULT_CROP = { 0, 0 };
    /** 自由尺寸（补位帧用）。 */
    static final int FREE = 0;
    /** 封箱代价：多一张图要真付一份图像钱，所以同占用率时选箱数少的。 */
    static final double DEFAULT_BIN_COST = 1.02;

    /**
     * 一行候选的缓存（同 `pack.js::ROW_CACHE`）。
     *
     * DP 内层对同一个 `tiers` 段会反复问同一件事，而一组候选要跑 24 次二分开方级循环。
     * 键**必须覆盖所有影响结果的参数** —— 早先 JS 那边只把 `stretch > 0.15` 编进键里，
     * 0.2 和 0.3 共用一份缓存，flat 候选直接缺失（见 pack.js 的注释）。
     */
    private static final Map<String, List<RowOpt>> ROW_CACHE = new java.util.HashMap<>();
    private static final int ROW_CACHE_MAX = 20000;
    /** 键里的 grow 段：Java 侧只有一套 grow，用它的字符串形式，改 grow 时键自动跟着变。 */
    private static final String GROW_KEY = java.util.Arrays.toString(DEFAULT_GROW);

    private Packer() { }

    // ------------------------------------------------------------------ 数据结构

    /** 一行的候选排法。c=行高（UNIT 单位），a=行内面积，s=各格边长。 */
    private static final class RowOpt {
        final double c;
        final double a;
        final double[] s;
        final boolean flat;
        RowOpt(double c, double a, double[] s, boolean flat) {
            this.c = c; this.a = a; this.s = s; this.flat = flat;
        }
    }

    /** 归一化格子。字段含义与 `pack.js` 的 cells 一一对应。 */
    static final class Cell {
        double x, y, w, h, s;
        double stretch = 1;
        /** 实际缩放系数 = `(s × 档位)`，照 `pack.js` 的 `f: (sv*tier)/UNIT`。 */
        double f;
        /** 档位（0 = 自由尺寸）。 */
        int t;
        /** 第几张图（0-based）。 */
        int bin;
        /** 时序（= 帧序号）。 */
        int seq;
        /** 留下这一帧的理由（cut/mark/floor/…）。`mark` 的格子不编号，见 `Sheet`。 */
        String via;
    }

    /**
     * 排布结果 —— **格子、画布、判定数字在同一个对象里**。
     *
     * 为什么画布要挂在这上面：`solve` 出来的是"归一化容器里的方块"，落到画布之前还有一步
     * {@link #lay}（单帧铺满 / 裁掉整行空着的带子）。那一步会**同时**改格子坐标和画布尺寸，
     * 两者一旦分开存（曾经就是 `Sheet` 里三个字段 + 一个单独的裁剪方法），
     * 就会出现"缩了画布忘了归一化坐标"这种错 —— 而且错得很安静：图能出，只是内容挤在上半幅。
     *
     * 所以：**拿到 cells 就必须拿到 canvas，别自己拼**。
     */
    static final class Result {
        int bins;
        double fill;
        double area;
        List<Cell> cells = new ArrayList<>();
        /** 这张排布落在多大的画布上（默认 {@link Tokens#CANVAS}，被 {@link #lay} 裁过会变小）。 */
        int canvasW = Tokens.CANVAS[0];
        int canvasH = Tokens.CANVAS[1];

        /** 计费 token。**唯一换算口**是 {@link Tokens#imageTokens}，别在别处乘系数。 */
        int tokens() { return Tokens.imageTokens(canvasW, canvasH); }
    }

    /** 像素矩形（`toRects` 的输出）。 */
    static final class Rect {
        int px, py, pw, ph;
        int index;
        int t;
        int seq;
        int bin;
        String via;
    }

    // ------------------------------------------------------------------ 裁切预算

    /**
     * 裁切比例 → 允许的拉伸系数（`pack.js::stretchForCrop`）。
     *
     * 一格的裁剪比例和拉伸系数是**一一对应**的：格子被拉高 `s` 倍，cover 就裁掉
     * `1 − 1/(1+s)`。所以"最多裁 c" ⇔ "最多拉 c/(1−c)"：0.1 → 0.111、0.3 → 0.429。
     *
     * 两个方向取大的那个：横向裁 10% 和纵向裁 10% 都是"有 10% 的画面看不到"，
     * 格子内是等比缩放的，谁才是约束取决于拉伸方向，取大值才不冤枉调用方。
     */
    static double stretchForCrop(double crop) {
        double c = Math.max(0, Math.min(0.9, crop));
        return c / (1 - c);
    }

    /**
     * **预算是硬上限**：不管调用方传多大的 stretch，实际拉伸都不许超过它。
     *
     * 反过来预算为 0 时结果恒为 0 —— 这就是默认行为（一格不裁）。
     */
    static double effectiveStretch(double stretch, double cropX, double cropY) {
        double c = Math.max(0, Math.min(0.9, Math.max(cropX, cropY)));
        return Math.max(0, Math.min(stretch, stretchForCrop(c)));
    }

    // ------------------------------------------------------------------ 档位

    /**
     * 档位 k 的边长区间（UNIT 单位）。
     *
     *   k=1  [1/2, 1]     —— 整幅只许缩到一半（两张 1 档正好并排）
     *   k=2  [1/3, 1/2]   —— 只许缩小（1/2 已经是"能看清"的上限）
     *   k≥3  [1/(k+1), 1/(k-1)] —— 相邻一档之间连续取值
     *   FREE [1/24, 1/2]  —— 补位帧：剩多窄填多窄，但不许长成整幅
     */
    static double[] bounds(int k) {
        if (k == FREE) return new double[] { UNIT / 24.0, UNIT / 2.0 };
        double lo = UNIT / (double) (k + 1);
        boolean canGrow = false;
        if (k >= 3) {
            for (int g : DEFAULT_GROW) if (g == k) { canGrow = true; break; }
        }
        double hi = canGrow ? UNIT / (double) (k - 1) : UNIT / (double) k;
        return new double[] { lo, hi };
    }

    // ------------------------------------------------------------------ 一行

    /**
     * 一行（连续若干格）的候选方案，与 `pack.js::rowOptions` 同序同值。
     *
     * **第一件事就是把 stretch 夹进裁切预算**，后面所有候选（含格内拉伸那条的
     * `c2 = wmax/(1+st)`）都用夹过的 `st`。只夹不丢 —— 早先试过"先生成再丢弃超预算的
     * 整行"，结果 0.1 预算下反而只能装 7 帧（不裁是 9 帧）：行被整条扔掉，连它没超预算的
     * 排法也一起没了。
     */
    private static List<RowOpt> rowOptions(int[] tiers, double stretch, boolean fillRows,
                                           double cropX, double cropY) {
        double st = effectiveStretch(stretch, cropX, cropY);
        String key = rowKey(tiers, st, fillRows);
        List<RowOpt> hit = ROW_CACHE.get(key);
        if (hit != null) return hit;
        List<RowOpt> val = rowOptionsRaw(tiers, st, fillRows);
        if (ROW_CACHE.size() > ROW_CACHE_MAX) ROW_CACHE.clear();
        ROW_CACHE.put(key, val);
        return val;
    }

    /** 缓存键：段档位 + grow + 夹过的 stretch + 是否强行铺满。 */
    private static String rowKey(int[] tiers, double st, boolean fillRows) {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < tiers.length; i += 1) {
            if (i > 0) sb.append(',');
            sb.append(tiers[i]);
        }
        return sb.append('|').append(GROW_KEY)
            .append('|').append(Double.toString(st))
            .append('|').append(fillRows ? 1 : 0).toString();
    }

    /** 真正的候选生成（不带缓存，`pack.js::rowOptionsRaw`）。 */
    private static List<RowOpt> rowOptionsRaw(int[] tiers, double stretch, boolean fillRows) {
        int n = tiers.length;
        double[] cs = new double[n];
        double[] los = new double[n];
        double[] his = new double[n];
        double sumLos = 0, sumCs = 0;
        for (int i = 0; i < n; i += 1) {
            int k = tiers[i];
            cs[i] = (k == FREE) ? UNIT / 4.0 : UNIT / (double) k;
            double[] b = bounds(k);
            los[i] = b[0];
            his[i] = b[1];
            sumLos += los[i];
            sumCs += cs[i];
        }
        List<RowOpt> out = new ArrayList<>(4);
        if (sumLos > UNIT + 1e-9) return out;

        // ① 原尺寸正好铺满（或没铺满）
        if (sumCs <= UNIT) {
            double c = max(cs);
            out.add(new RowOpt(c, areaOf(cs), cs.clone(), false));
        }

        // ② **最小档候选**：每格都压到自己的下限。
        //    没有它，"2 个 1 档并排 + 1 个占半行"这种 L 形排法根本不存在，
        //    3 个 1 档会被误判成装不下、每张只放 2 帧。
        double cMin = max(los);
        boolean allShrunk = true;
        for (int i = 0; i < n; i += 1) {
            if (Math.abs(los[i] - cs[i]) <= 1e-9) { allShrunk = false; break; }
        }
        if (allShrunk) out.add(new RowOpt(cMin, areaOf(los), los.clone(), false));

        if (!fillRows) return out;

        // ③ 二分出行高，把矮的往高的拉平，使总宽正好 = UNIT
        double[] s;
        double sumHis = 0;
        for (double v : his) sumHis += v;
        if (sumHis < UNIT - 1e-9) {
            s = his.clone();                        // 全放到最大也填不满
        } else {
            double a = 0, b = max(his);
            for (int it = 0; it < 24; it += 1) {
                double m = (a + b) / 2;
                double tot = 0;
                for (int j = 0; j < n; j += 1) tot += Math.min(Math.max(m, los[j]), his[j]);
                if (tot < UNIT) a = m; else b = m;
            }
            s = new double[n];
            double tot = 0;
            for (int j = 0; j < n; j += 1) {
                s[j] = Math.min(Math.max(b, los[j]), his[j]);
                tot += s[j];
            }
            if (tot > UNIT) for (int j = 0; j < n; j += 1) s[j] = s[j] * UNIT / tot;
        }
        double c3 = max(s);
        double a3 = areaOf(s);
        if (out.isEmpty() || Math.abs(out.get(0).c - c3) > 1e-9 || Math.abs(out.get(0).a - a3) > 1e-9) {
            out.add(new RowOpt(c3, a3, s, false));
        }

        // ④ **格内拉伸**：同一行里各自纵向拉高到行高（宽高比变化 <= stretch）。
        //    可行条件是"最宽的格 / 最窄的格 <= (1+stretch)²"，此时行高取 最宽/(1+stretch)，
        //    这一行**零空隙**（面积 = 行高 × 本行实占宽度）。
        if (stretch > 0 && s.length > 1) {
            double wmax = max(s), wmin = min(s);
            if (wmax <= wmin * (1 + stretch) * (1 + stretch) + 1e-9) {
                double c2 = wmax / (1 + stretch);
                double a2 = c2 * sumOf(s);
                boolean dup = false;
                for (RowOpt o : out) {
                    if (Math.abs(o.c - c2) < 1e-9 && Math.abs(o.a - a2) < 1e-9) { dup = true; break; }
                }
                if (!dup) out.add(new RowOpt(c2, a2, s, true));
            }
        }
        return out;
    }

    /** `ropt[i][ln-1]` = 从 i 开始、长 ln 的这一行的候选。 */
    private static List<List<RowOpt>>[] precompute(int[] tiers, double st, boolean fillRows,
                                                   double cropX, double cropY) {
        int n = tiers.length;
        @SuppressWarnings("unchecked")
        List<List<RowOpt>>[] ropt = new List[n];
        for (int i = 0; i < n; i += 1) {
            List<List<RowOpt>> row = new ArrayList<>(8);
            for (int ln = 1; ln <= 8 && i + ln <= n; ln += 1) {
                int[] seg = new int[ln];
                System.arraycopy(tiers, i, seg, 0, ln);
                List<RowOpt> opts = rowOptions(seg, st, fillRows, cropX, cropY);
                if (opts.isEmpty()) break;          // 更长只会更宽
                row.add(opts);
            }
            ropt[i] = row;
        }
        return ropt;
    }

    // ------------------------------------------------------------------ 主求解

    /** DP 里的一个状态：分数、累计面积、回溯指针。 */
    private static final class State {
        final double v;
        final double a;
        final Back back;
        State(double v, double a, Back back) { this.v = v; this.a = a; this.back = back; }
    }

    /** 回溯指针。`close` >= 0 表示"这一步是就地封箱"，否则是一行。 */
    private static final class Back {
        int close = -1;         // >=0 → 封箱到该累计行高
        int i;                  // 这一行从第几帧开始
        int from;               // 上一行结束时的累计行高
        /** 原始行高（**不是** round 后的）：拉伸行是 323.077 这种小数，截成 323 就偏了 */
        double c;
        double[] s;
        boolean flat;
    }

    /**
     * 精确 DP：给定档位序列，求占用率最高的按行排布。
     *
     * @param tiers 档位序列（时间顺序），1..8 或 0(FREE)
     */
    static Result solve(int[] tiers, double stretch, double binCost) {
        return solve(tiers, stretch, DEFAULT_CROP[0], DEFAULT_CROP[1], binCost);
    }

    /**
     * 带裁切预算的版本。
     *
     * **入口夹一次 `st`，候选生成和几何重建共用它**：候选按预算夹过、这里没夹，
     * 就会造出"DP 以为是 10%、实际裁了 30%"的图（JS 侧真踩过）。
     */
    static Result solve(int[] tiers, double stretch, double cropX, double cropY, double binCost) {
        Result empty = new Result();
        int n = tiers.length;
        if (n == 0) return empty;

        final double st = effectiveStretch(stretch, cropX, cropY);
        List<List<RowOpt>>[] ropt = precompute(tiers, st, true, cropX, cropY);
        final double CAP = UNIT;
        final double BIN = (double) UNIT * UNIT;
        final double CLOSE = BIN * binCost;

        // dp[i] = Map(累计行高 -> State)。**必须是 LinkedHashMap**：JS 那边是 Map(插入序)，
        // 平局时靠"先到先得"决定回溯路径；用 HashMap 会排出几何等价但顺序不同的版面。
        List<LinkedHashMap<Integer, State>> dp = new ArrayList<>(n + 1);
        for (int i = 0; i <= n; i += 1) dp.add(new LinkedHashMap<>());
        dp.get(0).put(0, new State(0, 0, null));

        for (int i = 0; i < n; i += 1) {
            LinkedHashMap<Integer, State> cur = dp.get(i);
            if (cur.isEmpty()) continue;

            // ① 就地封箱：从这一帧起开一张新图
            double bc = Double.NEGATIVE_INFINITY;
            double bestA = 0;
            Integer bC = null;
            for (Map.Entry<Integer, State> e : cur.entrySet()) {
                double v2 = e.getValue().v - CLOSE;
                if (v2 > bc) { bc = v2; bestA = e.getValue().a; bC = e.getKey(); }
            }
            if (bC != null && !cur.containsKey(0)) {
                Back bk = new Back();
                bk.close = bC;
                cur.put(0, new State(bc, bestA, bk));
            }

            // ② 往后接一行。**快照**：封箱刚塞进去的 0 键也要参与（同 JS 的 [...cur]）
            List<Map.Entry<Integer, State>> snap = new ArrayList<>(cur.entrySet());
            for (Map.Entry<Integer, State> e : snap) {
                int C = e.getKey();
                double v = e.getValue().v;
                double a = e.getValue().a;
                List<List<RowOpt>> rows = ropt[i];
                for (int ln = 1; ln <= rows.size(); ln += 1) {
                    int j = i + ln;
                    LinkedHashMap<Integer, State> tgt = dp.get(j);
                    for (RowOpt opt : rows.get(ln - 1)) {
                        int ci = (int) Math.round(opt.c);
                        int nC = C + ci;
                        double nv = v + opt.a;
                        double na = a + opt.a;
                        if (nC > CAP) { nv -= CLOSE; nC = ci; }
                        State old = tgt.get(nC);
                        if (old == null || nv > old.v) {
                            Back bk = new Back();
                            bk.i = i;
                            bk.c = opt.c;
                            bk.s = opt.s;
                            bk.flat = opt.flat;
                            // 记录"上一格的累计行高"：Back.close 是封箱专用，这里复用 c 之外的字段不够，
                            // 所以把 C 借位存进 seqNeg 不体面 —— 直接加一个字段。
                            bk.from = C;
                            tgt.put(nC, new State(nv, na, bk));
                        }
                    }
                }
            }
        }

        // ③ 收尾：最后一箱也要付封箱代价
        double bestV = Double.NEGATIVE_INFINITY;
        double aSum = 0;
        int bestC = 0;
        for (Map.Entry<Integer, State> e : dp.get(n).entrySet()) {
            double v2 = e.getValue().v - CLOSE;
            if (v2 > bestV) { bestV = v2; aSum = e.getValue().a; bestC = e.getKey(); }
        }
        if (dp.get(n).isEmpty()) return empty;

        // ④ 回溯
        List<Back> rows = new ArrayList<>();
        int C = bestC;
        int i = n;
        while (i > 0) {
            State node = dp.get(i).get(C);
            if (node == null || node.back == null) break;
            Back bk = node.back;
            if (bk.close >= 0) { C = bk.close; continue; }
            rows.add(bk);
            i = bk.i;
            C = bk.from;
        }
        java.util.Collections.reverse(rows);

        // ⑤ 生成 cells（与 JS 完全同一套坐标）
        Result res = new Result();
        double binH = 0;
        int bin = 0;
        int seq = 0;
        for (Back r : rows) {
            double rowH = r.flat ? r.c : max(r.s);
            double cN = rowH / UNIT;
            if (binH + cN > 1 + 1e-9) { binH = 0; bin += 1; }
            double x = 0;
            for (int k = 0; k < r.s.length; k += 1) {
                double sv = r.s[k];
                Cell cell = new Cell();
                cell.x = x / UNIT;
                cell.y = binH;
                cell.w = sv / UNIT;
                cell.h = (r.flat ? rowH : Math.min(rowH, sv * (1 + st))) / UNIT;
                cell.s = sv / UNIT;
                cell.stretch = sv / (r.flat ? rowH : Math.min(rowH, sv * (1 + st)));
                cell.t = tiers[r.i + k];
                // 实际缩放系数：档位 1 的一格占满整幅时是 1.0，1/4 档是 0.25。
                cell.f = (sv * cell.t) / UNIT;
                cell.bin = bin;
                cell.seq = seq;
                res.cells.add(cell);
                seq += 1;
                x += sv;
            }
            binH += cN;
        }

        // ⑥ 箱数与占用率按**重建后的真实几何**算（避免 DP 记账与渲染不一致）
        int binsReal = 0;
        double areaReal = 0;
        for (Cell c : res.cells) {
            binsReal = Math.max(binsReal, c.bin + 1);
            areaReal += c.w * c.h;
        }
        res.bins = binsReal;
        res.area = areaReal;
        res.fill = binsReal > 0 ? areaReal / binsReal : 0;
        return res;
    }

    static Result solve(int[] tiers) {
        return solve(tiers, DEFAULT_STRETCH, DEFAULT_BIN_COST);
    }

    /**
     * 取**一张图**（某个 bin）的最终排布：只留这个 bin 的格子，并落好画布。
     *
     * 为什么要有这个函数，而不是让调用方自己"先取子集、再 lay 一次"两步：
     * 取子集之后内容框会变（可能更小），**必须重新 lay 一次**才落得对；
     * 但"必须记得再 lay 一次"这种约定迟早会漏 —— 漏了就是"画布还是整幅、格子挤在上半幅"。
     * 所以把两步焊死在一个入口里。
     */
    static Result oneSheet(Result r, int bin) {
        Result one = new Result();
        if (r == null) return one;
        one.bins = r.bins;
        one.fill = r.fill;
        one.area = r.area;
        // 画布跟着走：格子是按 `r.canvasW/H` 归一化过的，换回默认值会算错尺寸
        one.canvasW = r.canvasW;
        one.canvasH = r.canvasH;
        if (r.cells != null) {
            for (Cell c : r.cells) if (c.bin == bin) one.cells.add(c);
        }
        return lay(one);
    }

    /**
     * 把 `solve` 的结果**落到画布上**。这是"排布 → 图"之间唯一的一步补算，
     * 而且**只能有一处**（`Pipeline.solveFor` 里调，预览和发送共用）。
     *
     * ## 它做两件事（顺序不能换）
     *
     * 1. **只有一格 → 铺满画布**。画布是 3:4、帧也是 3:4，"一格占满"既不裁也不变形，
     *    就是把那张图原样放大。标准档位会把单帧压成 2 档（占 1/4 画布 + 3/4 灰块），
     *    而 token 按画布算 —— 等于花 197 token 只买 1/4 的画面。
     * 2. **整行空着 → 把那条带子从画布上裁掉**（不是裁画面！格子像素一个不动）。
     *    两帧并排时下半幅是空的，裁完 token 197 → 189，灰块也没了。
     *
     * 做完 ① 之后一格已经满了，②自然什么都不做 —— 两条规则叠在同一趟里，不会互相打架。
     *
     * ## 为什么不能推广到多帧
     *
     * 用 3:4 的小块恰好铺满 3:4 的画布只有**方阵**可行（1×1、2×2、3×3）。
     * 2 帧要铺满得每块变成 1:2 长条（裁 50% 宽）；3 帧的空洞在行内右侧，
     * 内容框仍是整幅，**裁边界碰不到它**。这两种"空白"是几何决定的，不是漏了。
     *
     * ## 为什么不写进 {@link #solve}
     *
     * `solve` 是和 `pack.js` **逐字节对拍**的基线（`.tmp/parity`），往里加规则等于让对拍
     * 失去意义。这一步只改几何、不改 bin 数，放在外面，对拍永远是 0 差异。
     *
     * @return 同一个对象（原地改了 cells / canvasW / canvasH），方便链式调用
     */
    static Result lay(Result r) {
        if (r == null || r.cells == null || r.cells.isEmpty()) return r;

        // ① 单格铺满。自由尺寸补位帧（t == 0）不碰：它是"占位",不是画面。
        if (r.cells.size() == 1 && r.cells.get(0).t != 0) {
            Cell c = r.cells.get(0);
            c.x = 0;
            c.y = 0;
            c.w = 1;
            c.h = 1;
            c.stretch = 1;
        }

        // ② 内容框。没有可裁的就直接返回（最常见的 4 格 2×2 / 单格满画布走这条）。
        double x0 = 1, y0 = 1, x1 = 0, y1 = 0;
        for (Cell c : r.cells) {
            if (c.x < x0) x0 = c.x;
            if (c.y < y0) y0 = c.y;
            if (c.x + c.w > x1) x1 = c.x + c.w;
            if (c.y + c.h > y1) y1 = c.y + c.h;
        }
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(1, x1);
        y1 = Math.min(1, y1);
        double bw = Math.max(0.01, x1 - x0);
        double bh = Math.max(0.01, y1 - y0);
        if (x0 < 1e-9 && y0 < 1e-9 && bw > 0.999 && bh > 0.999) return r;

        // ⚠️ **缩画布必须同时重新归一化格子**：坐标是归一化的，画布缩一半之后 `h=0.5`
        // 仍然只占新画布的一半 —— 结果"内容挤到上面、下面又空出来"，格子还变小了。
        // 归一化之后格子的**像素尺寸逐像素不变**（0.5×471=235、1.0×314=314）。
        for (Cell c : r.cells) {
            c.x = (c.x - x0) / bw;
            c.y = (c.y - y0) / bh;
            c.w = c.w / bw;
            c.h = c.h / bh;
        }
        // 以**当前**画布为基准缩（不是 Tokens.CANVAS）：这样取子集之后再 lay 一次
        // 得到的还是对的尺寸 —— 幂等，不会"取子集时把之前裁掉的那部分又变回来"。
        r.canvasW = Math.max(1, (int) Math.round(r.canvasW * bw));
        r.canvasH = Math.max(1, (int) Math.round(r.canvasH * bh));
        return r;
    }

    // ------------------------------------------------------------------ 像素化

    /**
     * 归一化格子 → 像素矩形（与 `pack.js::toRects` 同一套取整修复）。
     *
     * @param width  画布宽（= 容器宽）
     * @param height 画布高（= 容器高）
     * @param gutter 格子之间的缝（像素）
     */
    static List<Rect> toRects(List<Cell> cells, int width, int height, int gutter) {
        List<Rect> out = new ArrayList<>(cells.size());
        int idx = 0;
        for (Cell c : cells) {
            Rect r = new Rect();
            r.index = c.seq + 1;                     // 编号全局连续（从 1 开始）
            r.t = c.t;
            r.seq = c.seq;
            r.bin = c.bin;
            r.via = c.via;
            r.px = (int) Math.round(c.x * width) + gutter;
            r.py = (int) Math.round(c.y * height) + gutter;
            r.pw = Math.max(1, (int) Math.round(c.w * width));
            r.ph = Math.max(1, (int) Math.round(c.h * height));
            out.add(r);
            idx += 1;
        }
        // 取整会让相邻格子差 1px（重叠/裂缝）。两个方向各修一次，只动 1~2px。
        //   水平：同一行里把左边缘贴到前一格的右边缘
        //   垂直：下一行的上边缘贴到本行的下边缘（否则后画的盖住前一行）
        Map<String, List<Rect>> rows = new LinkedHashMap<>();
        for (Rect r : out) {
            String k = r.py + "|" + r.ph;
            List<Rect> list = rows.get(k);
            if (list == null) { list = new ArrayList<>(); rows.put(k, list); }
            list.add(r);
        }
        for (List<Rect> row : rows.values()) {
            row.sort((p, q) -> Integer.compare(p.px, q.px));
            for (int j = 1; j < row.size(); j += 1) {
                Rect prev = row.get(j - 1);
                Rect cur = row.get(j);
                int edge = prev.px + prev.pw;
                if (Math.abs(cur.px - edge) <= 2) {
                    cur.pw += cur.px - edge;
                    cur.px = edge;
                }
            }
        }
        // 垂直：按 top 排序，逐行把上边缘顶到上一行的下边缘
        List<Integer> tops = new ArrayList<>();
        for (Rect r : out) if (!tops.contains(r.py)) tops.add(r.py);
        java.util.Collections.sort(tops);
        Integer prevBottom = null;
        for (Integer top : tops) {
            int shift = 0;
            if (prevBottom != null && top < prevBottom && prevBottom - top <= 2) shift = prevBottom - top;
            int bottom = top;
            for (Rect r : out) {
                if (r.py != top) continue;
                if (shift > 0) {
                    r.py += shift;
                    r.ph = Math.max(1, r.ph - shift);
                }
                bottom = Math.max(bottom, r.py + r.ph);
            }
            prevBottom = bottom;
        }
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    private static double max(double[] a) {
        double m = a[0];
        for (double v : a) if (v > m) m = v;
        return m;
    }

    private static double min(double[] a) {
        double m = a[0];
        for (double v : a) if (v < m) m = v;
        return m;
    }

    private static double sumOf(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    private static double areaOf(double[] a) {
        double s = 0;
        for (double v : a) s += v * v;
        return s;
    }
}
