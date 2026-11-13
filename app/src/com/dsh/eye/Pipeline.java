package com.dsh.eye;

import java.util.ArrayList;
import java.util.List;

/**
 * Pipeline —— 帧进、图出。**`dsh-see-now/src/pipeline.js` 的逐行移植**。
 *
 * 这一层决定**什么时候封箱、箱里放哪几帧、每帧什么档位**。手机 App 以前自己另写了一套
 * （采样 5 次/秒 → 平均绝对差 → 阈值 6 留帧 → 最多 N 帧 → 2.5 秒窗口到就发），
 * 那套和电脑版没有任何共同点：
 *
 * | 电脑版（这里） | App 旧逻辑 |
 * |---|---|
 * | 每帧都进管线，`decide()` 逐帧判四种理由 | 按采样间隔抽帧 |
 * | 切点 = 尖峰 / 台阶 / 运动补偿 / 孤立弱切点 四选一 | 单一阈值 |
 * | 积分帧：变化量累到 `4×fps×3s` 才留一帧 | 无 |
 * | 兜底帧：`silentFloorSec` 或 `floorGapK × 近期间隔` | 无（静止就永远不发） |
 * | 档位由密度算出（1..8），决定格子多大 | 无档位，等分格 |
 * | 装不下 / 时间到 / 碎格 / 满 `maxFrames` 才封箱 | 固定 2.5 秒窗口 |
 *
 * ## 和 JS 原版的差异（只有一处，刻意的）
 *
 * `flush()` 不生成 PNG：JS 那边在这里 `pack → composePacked → encodePng`，App 侧把
 * 「哪几帧 + 什么档位」交给 `Sheet` 去画（它要顺手写编号和 JPEG 压缩）。
 * **排布仍然走 `Packer`（= pack.js）**，所以版面完全一致。
 *
 * 另外裁掉了三处手机用不到的：`region` 模式（抓屏幕的一小块）、`audio` 音轨条
 * （手机没有音频通道）、`changeLog` 的交付文本（App 直接说话，不出摘要）。
 */
final class Pipeline {

    /** 积分口径的参考分（见 pipeline.js 的注释）。 */
    static final double REF_SCORE = 4;

    // ------------------------------------------------------------------ 参数

    /** 与 `pipeline.js::DEFAULTS` 同名同值。**改任何一项都要先回去改桌面版**。 */
    static final class Opts {
        int width = 798;
        int height = 450;
        int fps = 12;
        double cutAbs = 12;
        double cutSpike = 2.5;
        double cutSpikeAbs = 0;
        double cutMinGap = 0.4;
        int cutMcRadius = 4;
        double cutMcAbs = 60;
        double cutMcMinScore = 6;
        double cutSpikeMcAbs = 20;
        double cutIsolatedAbs = 6;
        double cutIsolatedSpike = 3.0;
        double cutIsolatedMcAbs = 8;
        double cutIsolatedMcBBox = 20;
        double cutIsolatedFill = 0.2;
        double cutBgAbs = 100;
        double cutBgMaxScore = 60;
        int cutBgMinCells = 128;
        int cutBgMaxChanged = 600;
        double cutBgMaxSpanY = 0.85;
        int cutBgWindow = 6;
        double cutFutureDelay = 0.5;
        double cutFutureMinScore = 60;
        double cutFutureKeep = 100;
        int cutFutureMinChanged = 64;
        int cutFutureRefWindow = 24;
        double cutFutureBorderMax = 10;
        double cutFutureLowBgMax = 160;
        double cutFutureLowBorderMin = 60;
        int cutFutureLowStableMax = 700;
        double cutFutureLowKeep = 210;
        int cutFutureLowStableTight = 300;
        double cutFutureLowKeepTight = 220;
        double cutFutureLowBgHigh = 220;
        double cutFutureBuildMinScore = 12;
        double cutJumpAbs = 80;
        double cutJumpMargin = 12;
        double cutJumpRatio = 1.5;
        double integralSeconds = 3;
        double markMinGap = 0.5;
        double silentFloorSec = 20;
        double floorGapK = 0;
        double floorMin = 2;
        double floorMax = 20;
        double dupMin = 8;
        String scoreMode = "abs";
        double mcTau = 15;
        int mcRadius = 2;
        boolean scoreNormalize = true;
        int dtFrames = 12;
        int smoothFrames = 6;
        String tierCurve = "docx";
        double[] tierThresholds = null;
        String tierMode = "density";
        String packMode = "rows";
        /**
         * 为凑行高最多裁掉多少画面（`pipeline.js::DEFAULTS.maxCrop`，**默认 0 = 一格不裁**）。
         *
         * 0 的时候行宽不满就留空（`Sheet` 铺灰块），这是电脑版的默认版面；
         * 写 0.3 就是"宁可每格裁 30%，也要把行铺满"。
         */
        double maxCrop = 0;
        String strategy = "native";
        int[] tierMap = null;
        int markBoost = 2;
        int floorBoost = 1;
        int boostMinFrames = 4;
        double maxWaitSec = 15;
        double flushGain = 0.015;
        int maxFrames = 40;
        /** 多一箱的代价。pipeline 用 1000（宁可挤，也不多开一张），不是 pack.js 的 1.02。 */
        double binCost = 1000;
        int queueDepth = 1;
        boolean oneShot = false;
        /**
         * 把"按帧的窗口"折算成"按秒"（0.4.8）。
         *
         * 阈值（切点/积分/兜底/档位密度）本来就全是秒，不受帧率影响；
         * 但有三处内部窗口电脑版是按**帧数**写的（它固定 12fps，帧数 = 时间）：
         *
         * | 参数 | 电脑版 | 12fps 下 |
         * |---|---|---|
         * | `smoothFrames` | 6 帧 | 0.5 秒 |
         * | `BAR_WINDOW` | 24 帧 | 2 秒 |
         * | `cutBgWindow` | 6 帧 | 0.5 秒 |
         *
         * 手机帧率是 8/15 这种档位，直接照抄就是"窗口时长随帧率变"：
         * 15fps 时平滑只剩 0.4 秒、黑边窗只剩 1.6 秒。打开这个开关就按 fps 折算，
         * **秒数恒定**。默认关 —— 关了才和电脑版逐帧一致（对拍用的就是这个口径）。
         */
        boolean timeNormalizeFrames = false;

        Opts copy() {
            Opts o = new Opts();
            o.width = width; o.height = height; o.fps = fps;
            o.cutAbs = cutAbs; o.cutSpike = cutSpike; o.cutSpikeAbs = cutSpikeAbs;
            o.cutMinGap = cutMinGap; o.cutMcRadius = cutMcRadius; o.cutMcAbs = cutMcAbs;
            o.cutMcMinScore = cutMcMinScore; o.cutSpikeMcAbs = cutSpikeMcAbs;
            o.cutIsolatedAbs = cutIsolatedAbs; o.cutIsolatedSpike = cutIsolatedSpike;
            o.cutIsolatedMcAbs = cutIsolatedMcAbs; o.cutIsolatedMcBBox = cutIsolatedMcBBox;
            o.cutIsolatedFill = cutIsolatedFill;
            o.cutBgAbs = cutBgAbs; o.cutBgMaxScore = cutBgMaxScore; o.cutBgMinCells = cutBgMinCells;
            o.cutBgMaxChanged = cutBgMaxChanged; o.cutBgMaxSpanY = cutBgMaxSpanY; o.cutBgWindow = cutBgWindow;
            o.cutFutureDelay = cutFutureDelay; o.cutFutureMinScore = cutFutureMinScore;
            o.cutFutureKeep = cutFutureKeep; o.cutFutureMinChanged = cutFutureMinChanged;
            o.cutFutureRefWindow = cutFutureRefWindow; o.cutFutureBorderMax = cutFutureBorderMax;
            o.cutFutureLowBgMax = cutFutureLowBgMax; o.cutFutureLowBorderMin = cutFutureLowBorderMin;
            o.cutFutureLowStableMax = cutFutureLowStableMax; o.cutFutureLowKeep = cutFutureLowKeep;
            o.cutFutureLowStableTight = cutFutureLowStableTight;
            o.cutFutureLowKeepTight = cutFutureLowKeepTight; o.cutFutureLowBgHigh = cutFutureLowBgHigh;
            o.cutFutureBuildMinScore = cutFutureBuildMinScore;
            o.cutJumpAbs = cutJumpAbs; o.cutJumpMargin = cutJumpMargin; o.cutJumpRatio = cutJumpRatio;
            o.integralSeconds = integralSeconds; o.markMinGap = markMinGap;
            o.silentFloorSec = silentFloorSec; o.floorGapK = floorGapK;
            o.floorMin = floorMin; o.floorMax = floorMax; o.dupMin = dupMin;
            o.scoreMode = scoreMode; o.mcTau = mcTau; o.mcRadius = mcRadius;
            o.scoreNormalize = scoreNormalize; o.dtFrames = dtFrames; o.smoothFrames = smoothFrames;
            o.tierCurve = tierCurve; o.tierThresholds = tierThresholds;
            o.tierMode = tierMode; o.packMode = packMode; o.maxCrop = maxCrop;
            o.strategy = strategy; o.tierMap = tierMap;
            o.markBoost = markBoost; o.floorBoost = floorBoost; o.boostMinFrames = boostMinFrames;
            o.maxWaitSec = maxWaitSec; o.flushGain = flushGain; o.maxFrames = maxFrames;
            o.binCost = binCost; o.queueDepth = queueDepth; o.oneShot = oneShot;
            o.timeNormalizeFrames = timeNormalizeFrames;
            o.timeNormalizeFrames = timeNormalizeFrames;
            return o;
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 待判/已留的一帧。字段与 `pipeline.js` 的 entry 对齐。 */
    static final class Entry {
        double t;
        byte[] buf;
        Double score;          // 当前帧 vs 上一帧
        Double raw;
        double gain = 1;
        double bar = 0;
        Double mc;
        Double mcBox;
        Cuts.Mc mcRect;
        Double mcFill;
        double mcSpanY;
        double activeY;
        double guardY;
        Double bg;
        int bgStable;
        int bgChanged;
        Cuts.BgModel bgModel;
        Cuts.BgModel futureModel;
        float[] prevHsv;
        Double prev;           // 上一帧的 score
        float[] hsv;
        long wall;
        /** 原始 JPEG。pipeline 里的 `buf` 是为了打分降采样过的 rgb24，拼图要用这一份。 */
        byte[] jpeg;
        // 留下之后才有的
        int tier;
        int level;
        String via;
    }

    /** 一箱（一张宫格）。 */
    static final class Sheet {
        double at;
        double from;
        double to;
        String why;
        List<Entry> frames = new ArrayList<>();
        int[] tiers;
        /**
         * 已经算好的排布：**格子 + 画布 + 判定数字，一整个**。
         *
         * 为什么不是"三个字段"（cells / canvasW / canvasH）：那样谁都能只改一半，
         * 出现"画布缩了、坐标没归一化"的错（踩过一次，图能出但内容挤在上半幅）。
         * `Packer.Result` 把三者绑在一起，`tokens()` 也算在自己身上，外面拼不出来。
         *
         * **必须由这里带出去，不能让 Sheet 重算** —— 重算要再传一次 `binCost`，
         * 那个值一旦和 flush 用的不一致（pack.js 默认 1.02 / pipeline 用 1000），
         * 箱数和格子大小就对不上，画出来会和"判定装得下"矛盾。
         */
        Packer.Result layout;
    }

    /** 统计（`status` 用）。 */
    static final class Stats {
        int frames;
        int kept;
        int cuts;
        int marks;
        int floors;
        int dupSkipped;
        int sheets;
        int overflow;
        double scoreSum;
        int scoreN;
        double scoreMax;
        int[] tierHist = new int[9];
    }

    // ------------------------------------------------------------------ 状态

    private final Opts o;
    private final Cuts.Scorer scorer;
    private final Tiers.Tracker tiers;
    private final Stats stats = new Stats();

    private final List<Entry> q = new ArrayList<>();
    private List<Entry> frames = new ArrayList<>();
    private final List<Sheet> sheets = new ArrayList<>();

    private Double lastScore = null;
    private float[] lastKeptHsv = null;
    private double lastCut = Double.NEGATIVE_INFINITY;
    private double lastBound = Double.NEGATIVE_INFINITY;
    private double acc = 0;
    private double gapEwma = 0;
    private Double lastKeepT = null;
    private Entry lastSeen = null;
    private double sheetStart = 0;

    /** 电脑版的基准帧率：所有"按帧"的窗口都是在这个帧率下标定的。 */
    private static final double BASE_FPS = 12.0;

    Pipeline(Opts opts) {
        this.o = opts.copy();
        // 帧数 → 秒 → 当前帧率下的帧数。关闭时原样透传（= 电脑版口径）
        int barWindow = normFrames(Cuts.BAR_WINDOW);
        int bgWindow = normFrames(o.cutBgWindow);
        int refWindow = normFrames(o.cutFutureRefWindow);
        int smooth = normFrames(o.smoothFrames);
        // 卷积窗也是帧数：12 帧在 8fps 下是 1.5 秒、30fps 下 0.4 秒（见 normFrames 的注释）
        // 卷积窗也是帧数：12 帧在 8fps 下是 1.5 秒、30fps 下 0.4 秒（见 normFrames 的注释）
        this.scorer = new Cuts.Scorer(
            o.width, o.height, o.scoreMode, o.mcTau, o.mcRadius, o.cutMcRadius,
            bgWindow, 24, 15, o.cutFutureDelay > 0,
            o.cutFutureDelay > 0 ? refWindow : 0,
            o.cutFutureDelay > 0 ? Math.min(o.cutFutureMinScore, o.cutFutureBuildMinScore) : o.cutFutureMinScore,
            o.scoreNormalize, barWindow
        );
        this.tiers = new Tiers.Tracker(
            o.fps, normFrames(o.dtFrames), smooth, o.tierCurve, o.tierThresholds
        );
    }

    /** 按帧数定的窗口 → 当前 fps 下的等效帧数（保持秒数不变）。 */
    private int normFrames(int frames) {
        if (!o.timeNormalizeFrames || frames <= 0) return frames;
        double sec = frames / BASE_FPS;
        return (int) Math.max(1, Math.round(sec * o.fps));
    }

    Opts opts() { return o; }
    Stats stats() { return stats; }
    Tiers.Tracker tierTracker() { return tiers; }

    // ------------------------------------------------------------------ 内部

    /**
     * 积分阈值：**用基准帧率（12），不用实际 fps**。
     *
     * `acc` 累的是每帧的 `scorePair`，而那是绝对差 —— 帧间隔越短差越小，
     * 所以 Σscore 只与**时间**有关（实测同一段 40 秒素材五档 fps 都是 5090±3）。
     * 旧写法 `REF_SCORE * o.fps * 3` 随 fps 线性涨，高帧率永远攒不够：
     * 实测 marks 在 8fps 有 8 个、24/30fps 是 0 个。
     *
     * 改成常数后五档都是 144，marks 变成 3/3/5/5/5。**12fps 下逐位不变。**
     */
    private double integralNeed() {
        return REF_SCORE * BASE_FPS * o.integralSeconds;
    }

    /** 出图时的真实档位序列（假切点加成拖到这一步才生效）。 */
    private int[] seqOf(List<Entry> batch) {
        int[] out = new int[batch.size()];
        boolean on = batch.size() >= Math.max(1, o.boostMinFrames);
        if ("weight".equals(o.tierMode)) {
            double[] w = new double[batch.size()];
            double sum = 0;
            for (int i = 0; i < batch.size(); i += 1) {
                Entry f = batch.get(i);
                Double sc = f.score;
                double base = (sc != null && sc > 0) ? sc : 4;
                double dampen = 1;
                if (on) {
                    if ("mark".equals(f.via)) dampen = 1.0 / (1 + Math.max(0, o.markBoost));
                    else if ("floor".equals(f.via)) dampen = 1.0 / (1 + Math.max(0, o.floorBoost));
                }
                w[i] = Math.max(0.5, base * dampen);
                sum += w[i];
            }
            if (sum == 0) sum = 1;
            for (int i = 0; i < batch.size(); i += 1) {
                double area = Math.max(w[i] / sum, 1e-6);
                int k = (int) Math.ceil(1 / Math.sqrt(area));
                out[i] = Tiers.applyStrategy(new int[] { Math.min(8, Math.max(1, k)) },
                    o.strategy, o.tierMap)[0];
            }
            return out;
        }
        for (int i = 0; i < batch.size(); i += 1) {
            Entry f = batch.get(i);
            int boost = 0;
            if (on) {
                int raw = "mark".equals(f.via) ? o.markBoost : "floor".equals(f.via) ? o.floorBoost : 0;
                boost = Math.max(0, raw);
            }
            out[i] = Tiers.applyStrategy(new int[] { Math.min(8, f.level + boost) },
                o.strategy, o.tierMap)[0];
        }
        return out;
    }

    /**
     * 这一批帧的**最终档位序列** —— 和 {@link #flush} 里用的是同一个函数（{@code seqOf}）。
     *
     * 为什么要把这个读口开出来：通话页一次只发**一张**图，一窗可能留下十几帧，
     * 必须先压到"最多几格"，压完还得知道"每帧该多大"。档位只有这一处口径
     * （`strategy` + `markBoost/floorBoost` + `tierMode=weight` 的面积归一化全在里面），
     * 外面自己从 `Entry.tier` 推会把这些**全部**丢掉 —— 1.1.3 就是这么退化成等分宫的。
     */
    int[] tiersOf(List<Entry> batch) {
        return seqOf(batch);
    }

    /**
     * 当前这批帧的排布预案。**必须和 flush 用同一个档位口径**。
     */
    private Packer.Result plan() {
        return solveFor(frames);
    }

    /**
     * 几何的**唯一出口**：所有"要画出来 / 要发出去"的排布都从这里出。
     *
     * 为什么非要有这个函数：`plan()`（预览）和 `flush()`（真正发出去的那张）是两处独立
     * 调用 `Packer.solve` 的地方。谁要是只在其中一处打补丁，就会出现"预览满幅、发出去
     * 只有四分之一"这种对不上的情况 —— `fillGaps` 当年就是漏掉第二个调用点栽的。
     *
     * @param list 要排的帧（`plan()` 传现行 frames，`flush()` 传刚摘下来的那一批）
     */
    private Packer.Result solveFor(List<Entry> list) {
        // `lay` 在这里调、**只在这里调**：单帧铺满 + 裁掉整行空着的带子，一趟做完。
        // 预览（preview）和真正发出去的那张（flush）都从这拿，几何不可能分叉。
        // 排布器按 `packMode` 分派（rows / skyline），和 `splitOneBin` 必须是同一个（见 `Packer.pack`）。
        return Packer.lay(Packer.pack(seqOf(list), Packer.DEFAULT_STRETCH, o.maxCrop,
            o.maxCrop, o.binCost, o.packMode));
    }

    /**
     * 把"这一格是哪一帧、为什么留下"贴回排布结果。
     *
     * 为什么按 `cell.seq` 而不是下标：`seq` 是 **solve 内部的全局帧序号**（跨箱连续），
     * `cells` 里只有 bin 0 的那几个时下标记号就对不上了。贴 `via` 是给画图用的
     * （积分假切点不编号，见 `Sheet`），漏了它图上就会多出几个"假镜头"的编号。
     */
    static void attachVia(Packer.Result p, List<Entry> batch) {
        for (Packer.Cell c : p.cells) {
            int i = c.seq;
            c.via = (i >= 0 && i < batch.size()) ? batch.get(i).via : null;
        }
    }

    /**
     * **只读快照**：当前正在攒的这一箱会排成什么样。不改变任何状态。
     *
     * 界面靠它做实时预览 —— 之前只有 flush + 发送时才更新，用户看不到"它正在攒什么"，
     * 要等一轮跑完才知道拼进去的是哪几帧。
     *
     * 桌面端的调试窗（`debug/live.mjs`）是自己复算 `seqOf()` 的：插件没有读口，
     * 它只能抄一遍，然后每出一张图对拍一次防止悄悄错。App 这边是我们自己的代码，
     * 直接开一个读口更准 —— 复算一旦和 `applyStrategy` / `markBoost` 对不上，
     * 画出来的排布就是错的。
     *
     * 调用方负责加 `pipeLock`（和 push 互斥）。
     */
    Sheet preview() {
        if (frames.isEmpty()) return null;
        Sheet s = new Sheet();
        s.frames = new ArrayList<>(frames);
        s.tiers = seqOf(s.frames);
        Packer.Result p = plan();
        attachVia(p, s.frames);
        s.layout = p;          // 画布已经由 plan() 里的 lay() 算好，这里不再二次加工
        s.from = s.frames.get(0).t;
        s.to = s.frames.get(s.frames.size() - 1).t;
        s.at = s.to;
        s.why = "live";
        return s;
    }

    // ------------------------------------------------------------------ 留帧

    private boolean keep(Entry entry, String via) {
        Double dup = null;
        if (lastKeptHsv != null && entry.hsv != null) dup = Cuts.scorePair(lastKeptHsv, entry.hsv);
        if (dup != null && dup < o.dupMin && !"cut".equals(via) && !"floor".equals(via)) {
            stats.dupSkipped += 1;
            return false;
        }
        tiers.mark(entry.t);
        int lv = tiers.levelAt();
        int tier = Tiers.applyStrategy(new int[] { lv }, o.strategy, o.tierMap)[0];
        if (frames.isEmpty()) sheetStart = entry.t;
        entry.tier = tier;
        entry.level = lv;
        entry.via = via;
        frames.add(entry);
        if (entry.hsv != null) {
            if (lastKeptHsv == null) lastKeptHsv = new float[entry.hsv.length];
            System.arraycopy(entry.hsv, 0, lastKeptHsv, 0, entry.hsv.length);
        }
        lastBound = entry.t;
        if (lastKeepT != null) {
            double g = entry.t - lastKeepT;
            gapEwma = gapEwma == 0 ? g : gapEwma + (g - gapEwma) * 0.3;
        }
        lastKeepT = entry.t;
        stats.kept += 1;
        stats.tierHist[Math.max(0, Math.min(8, tier))] += 1;
        if ("cut".equals(via)) stats.cuts += 1;
        else if ("mark".equals(via)) stats.marks += 1;
        else if ("floor".equals(via)) stats.floors += 1;
        if (o.oneShot && frames.size() == 1) { o.oneShot = false; flush(entry.t, "oneshot"); return true; }
        checkFlush(entry.t);
        return true;
    }

    // ------------------------------------------------------------------ 封箱判定

    private void checkFlush(double t) {
        if (frames.isEmpty()) return;
        Packer.Result p = plan();

        // 硬：装不下了。**从末尾退到真装得下为止**，退出来的留给下一张（保序、不丢帧）
        if (p.bins > 1) {
            int[] seq = seqOf(frames);
            List<int[]> segs = splitOneBin(seq);
            int keepN = segs.get(0).length;
            if (keepN >= frames.size()) { flush(t, "full"); return; }
            List<Entry> carry = new ArrayList<>(frames.subList(keepN, frames.size()));
            List<Entry> head = new ArrayList<>(frames.subList(0, keepN));
            frames = head;
            flush(t, "full");
            frames = carry;
            sheetStart = frames.get(0).t;
            lastBound = frames.get(frames.size() - 1).t;
            return;
        }

        Packer.Cell last = p.cells.isEmpty() ? null : p.cells.get(p.cells.size() - 1);
        double cellArea = last != null ? last.w * last.h : 1;

        // 软：这一帧只占得到 1.5% 了，再塞就是碎格
        if (frames.size() >= 2 && cellArea < o.flushGain) { flush(t, "crumb"); return; }

        // 时间：从**这一张的第一帧**算起
        if (frames.size() >= 3 && t - sheetStart >= o.maxWaitSec) { flush(t, "time"); return; }
        if (frames.size() >= 2 && t - sheetStart >= o.maxWaitSec) { flush(t, "time"); return; }
        if (frames.size() == 1 && t - sheetStart >= o.maxWaitSec * 2) { flush(t, "time"); return; }

        if (frames.size() >= o.maxFrames) flush(t, "cap");
    }

    /** 把一串档位切成每段都能装进一张图的若干段（保序、不丢帧、尽量往前塞）。 */
    private List<int[]> splitOneBin(int[] tiers) {
        List<int[]> segs = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        for (int t : tiers) {
            List<Integer> next = new ArrayList<>(cur);
            next.add(t);
            int[] na = toArr(next);
            if (!cur.isEmpty() && Packer.pack(na, Packer.DEFAULT_STRETCH, o.maxCrop, o.maxCrop,
                o.binCost, o.packMode).bins > 1) {
                segs.add(toArr(cur));
                cur = new ArrayList<>();
                cur.add(t);
            } else {
                cur = next;
            }
        }
        if (!cur.isEmpty()) segs.add(toArr(cur));
        return segs;
    }

    private static int[] toArr(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i += 1) a[i] = list.get(i);
        return a;
    }

    // ------------------------------------------------------------------ 出箱

    private Sheet flush(double t, String why) {
        if (frames.isEmpty()) return null;
        List<Entry> batch = frames;
        frames = new ArrayList<>();
        int[] seq = seqOf(batch);
        Packer.Result p = solveFor(batch);
        if (p.cells.isEmpty()) return null;
        // **先贴 via，再取 bin 0**：via 按 seq 取，取完再贴就只能按下标猜了
        attachVia(p, batch);
        // 一张图只画一个箱子（最后一个闸：宁可少画几帧，也不能交糊图）
        int keptN = 0;
        for (Packer.Cell c : p.cells) if (c.bin == 0) keptN += 1;
        List<Entry> used = new ArrayList<>(batch.subList(0, Math.min(keptN, batch.size())));
        if (used.size() < batch.size()) stats.overflow += 1;
        if (used.isEmpty()) return null;
        Sheet sh = new Sheet();
        sh.at = t;
        sh.from = used.get(0).t;
        sh.to = used.get(used.size() - 1).t;
        sh.why = why;
        sh.frames = used;
        sh.tiers = new int[used.size()];
        System.arraycopy(seq, 0, sh.tiers, 0, used.size());
        // 这一箱的最终几何：取 bin 0 的格子 + 重新落画布，两件事由 `oneSheet` 焊在一起
        sh.layout = Packer.oneSheet(p, 0);
        if (o.queueDepth > 0 && sheets.size() >= o.queueDepth) sheets.remove(0);
        sheets.add(sh);
        stats.sheets += 1;
        return sh;
    }

    // ------------------------------------------------------------------ 判据

    /** 停住的大面积前景：外圈还是旧背景，且背景变化不到整片。 */
    private boolean stillForeground(Entry entry) {
        double guardY = entry.guardY;
        if (entry.futureModel == null
            || !(guardY >= 0.25)
            || entry.bg == null
            || entry.bg <= 0
            || entry.bg >= 220) return false;
        Cuts.Border border = Cuts.bgBorderChanged(entry.futureModel, entry.hsv, guardY, 4, 15);
        return border != null && border.score < o.cutFutureBorderMax;
    }

    /** 低/中分但背景还有一块没被换掉、外圈也没动：可能是大手/产品挡住，也可能是真切。 */
    private boolean lowAmbiguousFuture(Entry entry) {
        if (o.cutFutureDelay <= 0
            || o.cutBgAbs <= 0
            || o.cutFutureLowBgMax <= 0
            || entry.score == null
            || entry.score >= o.cutFutureMinScore
            || entry.futureModel == null
            || entry.bg == null
            || entry.bg <= 0
            || entry.bgStable > o.cutFutureLowStableMax) return false;
        int stable = entry.bgStable;
        boolean highBg = entry.bg >= o.cutFutureLowBgHigh && stable <= o.cutFutureLowStableTight;
        if (entry.bg >= o.cutFutureLowBgMax && !highBg) return false;
        double guardY = entry.guardY;
        if (!(guardY >= 0.25)) return false;
        Cuts.Border border = Cuts.bgBorderChanged(entry.futureModel, entry.hsv, guardY, 4, 15);
        return border != null && border.score >= o.cutFutureLowBorderMin;
    }

    private void decide(Entry entry, Double nextScore, Double nextMc, Entry future) {
        Double sObj = entry.score;
        if (sObj == null) { keep(entry, "start"); return; }
        double s = sObj;

        double px = entry.prev == null ? 0 : entry.prev;
        double nx = nextScore == null ? 0 : nextScore;
        double base = Math.max(1, Math.max(px, nx));
        boolean mcOkForSpike = o.cutMcAbs <= 0 || o.cutSpikeMcAbs <= 0
            || (entry.mc == null ? 0 : entry.mc) >= o.cutSpikeMcAbs;
        boolean spike = nextScore == null
            || (s > o.cutSpike * base && s >= o.cutSpikeAbs && mcOkForSpike);
        // 运动中的切机位：前后都在动时 spike 的 2.5 倍凑不出来，改用「台阶」
        double jump = s - base;
        boolean step = o.cutJumpAbs > 0
            && s >= o.cutAbs
            && s >= o.cutJumpAbs
            && jump >= o.cutJumpMargin
            && s >= o.cutJumpRatio * base;
        double mc = entry.mc == null ? 0 : entry.mc;
        double mcBox = entry.mcBox == null ? 0 : entry.mcBox;
        double mcFill = entry.mcFill == null ? 0 : entry.mcFill;
        boolean mcCut = o.cutMcAbs > 0
            && s >= o.cutMcMinScore
            && mc >= o.cutMcAbs;
        // 相似构图/相似主体的弱切点：低于 cutAbs，但几乎孤立
        boolean isolated = o.cutIsolatedAbs > 0
            && nextScore != null
            && (o.cutMcAbs <= 0 || nextMc == null || nextMc < o.cutMcAbs)
            && (o.cutIsolatedMcAbs <= 0 || mc >= o.cutIsolatedMcAbs)
            && (o.cutIsolatedMcBBox <= 0 || mcBox >= o.cutIsolatedMcBBox)
            && (o.cutIsolatedFill <= 0 || mcFill >= o.cutIsolatedFill)
            && s >= o.cutIsolatedAbs
            && s < o.cutAbs
            && s > o.cutIsolatedSpike * base;
        // 前景运动不是切镜头：稳定背景大部分还在，且变化区域没盖满高度
        Double bg = entry.bg;
        double guardY = entry.guardY;
        boolean fgMotion = o.cutBgAbs > 0
            && s < o.cutBgMaxScore
            && !isolated
            && (o.cutBgMinCells <= 0 || entry.bgStable >= o.cutBgMinCells)
            && bg != null
            && bg > 0
            && bg < o.cutBgAbs
            && (o.cutBgMaxChanged <= 0 || entry.bgChanged <= o.cutBgMaxChanged)
            && guardY >= 0.25
            && (o.cutBgMaxSpanY <= 0
              || (guardY > 0 ? entry.mcSpanY / guardY : entry.mcSpanY) < o.cutBgMaxSpanY);
        // 高分候选不能只靠当前帧：等 0.5 秒，看候选帧改掉的稳定背景有没有回来
        boolean futureFg = false;
        boolean lowFuture = lowAmbiguousFuture(entry);
        if (o.cutFutureDelay > 0
            && entry.futureModel != null
            && (s >= o.cutFutureMinScore || lowFuture)) {
            Cuts.Keep fk = (future != null && future.hsv != null)
                ? Cuts.bgKeep(entry.futureModel, entry.hsv, future.hsv, 15)
                : null;
            Cuts.Keep rk = entry.prevHsv != null
                ? Cuts.bgKeep(entry.futureModel, entry.prevHsv, entry.hsv, 15)
                : null;
            double keepLimit = lowFuture
                ? (entry.bgStable <= o.cutFutureLowStableTight
                    ? o.cutFutureLowKeepTight : o.cutFutureLowKeep)
                : o.cutFutureKeep;
            futureFg = gone(fk, keepLimit)
                || (!lowFuture && gone(rk, keepLimit))
                || (s >= o.cutFutureMinScore && stillForeground(entry));
        }
        if (entry.t - lastCut >= o.cutMinGap
            && !fgMotion
            && !futureFg
            && (mcCut || (s >= o.cutAbs && (spike || step)) || isolated)) {
            lastCut = entry.t;
            acc = 0;                      // 切点之后重新攒（那一帧的分属于跨刀，不算运动）
            keep(entry, "cut");
            return;
        }

        acc += s;
        if (acc >= integralNeed() && entry.t - lastBound >= o.markMinGap) {
            // 只有**真的录进去**才清零；被去重挡掉时保留累积值
            if (keep(entry, "mark")) { acc = 0; return; }
            /**
             * ⚠️ 被去重挡掉时**不能 return**。
             *
             * `acc` 一旦攒够就永远 ≥ `integralNeed()`，而画面没变时每一帧都会被下面的
             * `dupMin` 挡掉 —— 直接 return 就等于把**兜底（`silentFloorSec`）永远屏蔽掉**：
             * 一帧都留不下来，管线彻底静止（真机实测：对着墙角，一箱停在 1 格两分钟，
             * 自动看一张也发不出去）。让代码继续往下走，兜底才有机会。
             */
        }
        double floorSec = o.silentFloorSec;
        if (o.floorGapK > 0 && gapEwma > 0) {
            floorSec = Math.min(o.floorMax, Math.max(o.floorMin, o.floorGapK * gapEwma));
        }
        if (entry.t - lastBound >= floorSec) keep(entry, "floor");
    }

    private boolean gone(Cuts.Keep r, double keepLimit) {
        return r != null && r.score != null
            && r.changed >= o.cutFutureMinChanged
            && r.score < keepLimit;
    }

    // ------------------------------------------------------------------ 对外

    /** 喂一帧（不带原图，对拍用）。 */
    void push(double t, byte[] buf) {
        push(t, buf, null);
    }

    /**
     * 喂一帧。
     *
     * @param buf  `width * height * 3` 的 rgb24（打分用，可以是降采样过的）
     * @param jpeg 原始 JPEG（拼图用）。null 时退化成用 buf 画，会糊。
     */
    void push(double t, byte[] buf, byte[] jpeg) {
        stats.frames += 1;
        tiers.tick(t);
        Cuts.Score r = scorer.score(buf);
        Double s = r.score;
        if (s != null) {
            stats.scoreSum += s;
            stats.scoreN += 1;
            if (s > stats.scoreMax) stats.scoreMax = s;
        }
        Entry entry = new Entry();
        entry.t = t;
        entry.buf = buf;
        entry.score = s;
        entry.raw = r.rawScore;
        entry.gain = r.gain;
        entry.bar = r.barFrac;
        entry.mc = r.mcScore;
        entry.mcBox = r.mcBox;
        entry.mcRect = r.mcRect;
        entry.mcFill = r.mcFill;
        entry.mcSpanY = r.mcSpanY;
        entry.activeY = r.activeY;
        entry.guardY = r.guardY;
        entry.bg = r.bgScore;
        entry.bgStable = r.bgStable;
        entry.bgChanged = r.bgChanged;
        entry.bgModel = r.bgModel;
        entry.futureModel = r.futureModel;
        entry.prevHsv = r.prevHsv;
        entry.prev = lastScore;
        entry.hsv = r.hsv;
        entry.wall = System.currentTimeMillis();
        entry.jpeg = jpeg;

        lastSeen = entry;
        lastScore = s;
        q.add(entry);

        while (q.size() >= 2) {
            Entry front = q.get(0);
            if (front.score == null) {
                decide(front, q.get(1).score, q.get(1).mc, null);
                q.remove(0);
                continue;
            }
            boolean needFuture = o.cutFutureDelay > 0
                && ((front.score >= o.cutFutureMinScore && !stillForeground(front))
                    || lowAmbiguousFuture(front));
            Entry future = null;
            if (o.cutFutureDelay > 0) {
                for (Entry e : q) {
                    if (e != front && e.t - front.t >= o.cutFutureDelay) { future = e; break; }
                }
            }
            if (needFuture && future == null) break;
            decide(front, q.get(1).score, q.get(1).mc, future);
            q.remove(0);
        }
    }

    /** 流结束：把还剩的那一帧判掉，再把最后一张发出去。 */
    void finish() {
        while (!q.isEmpty()) {
            Entry front = q.get(0);
            Entry future = null;
            if (o.cutFutureDelay > 0) {
                for (Entry e : q) {
                    if (e != front && e.t - front.t >= o.cutFutureDelay) { future = e; break; }
                }
            }
            decide(front, q.size() > 1 ? q.get(1).score : null,
                q.size() > 1 ? q.get(1).mc : null, future);
            q.remove(0);
        }
        int guard = 0;
        while (!frames.isEmpty() && guard < 64) {
            guard += 1;
            int[] seq = seqOf(frames);
            List<int[]> segs = splitOneBin(seq);
            if (segs.size() <= 1) {
                flush(lastBound, "end");
                break;
            }
            int keepN = segs.get(0).length;
            List<Entry> carry = new ArrayList<>(frames.subList(keepN, frames.size()));
            List<Entry> head = new ArrayList<>(frames.subList(0, keepN));
            frames = head;
            flush(frames.get(frames.size() - 1).t, "end");
            frames = carry;
        }
    }

    /** 取走队列里最老的一张。 */
    Sheet take() {
        return sheets.isEmpty() ? null : sheets.remove(0);
    }

    /**
     * **用完即废**：一轮发出去之后清掉 draft，下一轮从 0 帧长起。
     *
     * 三条"不要"——每一条都有具体后果，别顺手加上：
     *   - **不要动 `q`**（待判定的打分队列）：里面是还没判完的帧，清掉就等于丢帧；
     *   - **不要动 `lastKeptHsv`**：跨轮去重基准必须保留，它正好挡住"上一轮最后那帧
     *     在下一轮又出现一次"（否则每轮开头都会多一张和前一轮结尾重复的图）；
     *   - **不要动 `sheets`**（待发队列）：那是"已经封箱、还没发出去"的东西，
     *     和 draft（正在攒的这一箱）是两回事。
     */
    void resetDraft() {
        frames.clear();
        sheetStart = 0;
        acc = 0;
        // 兜底帧的"多久没留过帧"从**最后看到的那一帧**重新计时：
        // 不清成 0 的话，下一轮会立刻补一张"距离 0 秒"的兜底帧。
        lastBound = lastSeen != null ? lastSeen.t : lastBound;
    }

    /** 正在攒的这一箱有几帧（`Watcher.hasDraft()` 用；0 = 没有 draft）。 */
    int draftFrames() {
        return frames.size();
    }

    /**
     * 正在攒的这一箱（**copy**，可以安全地在锁外拿去排版/画图）。
     *
     * 通话页的"实时预览"靠它：预览必须是**这一箱**，不是"最近 N 秒的原始帧重跑一遍管线"。
     */
    List<Entry> draftList() {
        return new ArrayList<>(frames);
    }

    Sheet peek() {
        return sheets.isEmpty() ? null : sheets.get(0);
    }

    int pending() { return sheets.size(); }

    /**
     * 立刻截断出图（用户插话走这条：不等判据，也不排队）。
     * 本张是空的就把「最近解出来的那一帧」塞进去 —— 用户问的是现在。
     */
    Sheet flushNow(double t, String why) {
        if (lastSeen != null && (frames.isEmpty() || lastSeen.t > frames.get(frames.size() - 1).t + 1e-6)) {
            int lv = tiers.levelAt();
            tiers.mark(lastSeen.t);
            if (frames.isEmpty()) sheetStart = lastSeen.t;
            Entry e = lastSeen;
            e.level = lv;
            e.tier = Tiers.applyStrategy(new int[] { lv }, o.strategy, o.tierMap)[0];
            e.via = "user".equals(why) ? "user" : "now";
            frames.add(e);
        }
        // 装不下就从**最老的**开始丢：插话要的是最新画面
        while (frames.size() > 1 && plan().bins > 1) {
            frames.remove(0);
            sheetStart = frames.get(0).t;
        }
        if (frames.isEmpty()) return null;
        double at = frames.get(frames.size() - 1).t;
        Sheet made = flush(at, why);
        sheets.clear();
        if (made != null) sheets.add(made);
        return made;
    }
}
