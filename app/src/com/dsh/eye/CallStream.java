package com.dsh.eye;

import java.util.ArrayList;
import java.util.List;

/**
 * 通话页的**连续管线**：一个通话会话 = **一条** `Pipeline`，和电脑版 `dsh-see-now` 完全同构。
 *
 * ## 为什么不是"每次发送重建一条管线"
 *
 * 1.1.3~1.1.6 的写法是：每次要发图，把"最近 N 秒的连续帧"重新喂一遍新管线。
 * 这看着像 see-now，其实是**另一种算法** —— 管线里有一半状态是跨时间累积的：
 *
 * | 状态 | 作用 | 每窗重建的后果 |
 * |---|---|---|
 * | `gapEwma` | 兜底间隔 = `floorGapK × 近期出帧间隔` | 永远是 0，只能用写死的 `silentFloorSec` |
 * | `Tiers.Tracker` | 档位密度（平滑 + 滞回） | 每窗从 1 档重新爬，永远算不准"密不密" |
 * | `lastKeptHsv` | 跨帧去重 | 每窗第一帧都不会被去重 |
 * | `lastBound/lastKeepT` | 兜底计时、积分计时 | 每窗从头计 |
 * | `acc`（积分） | 攒够 3 秒的变化量留一帧 | 每窗清零，慢变化永远攒不满 |
 *
 * 所以重建出来的图必然"只有切点、没有积分帧和兜底帧" —— 用户看到的
 * "只有两帧 / 一整窗都是等大格子"就是这条链路造成的。
 *
 * 现在：`callOpen()` 建一条，5fps 的帧**逐帧喂进去**，管线自己按
 * `maxWaitSec`（= 发送间隔）/ 兜底 / 积分 / 切点 封箱。
 * App 只做两件事：**把封好的那一箱取走发出去**、**把正在攒的那一箱画成预览**。
 *
 * ## 兜底/档位/排布**一个都不许自己调**
 *
 * `silentFloorSec`（默认 20）、`floorGapK`（灵敏度档给的）、`tierMode`（默认 density）、
 * `maxFrames`（默认 40）、`packMode`（rows）—— 全都是 see-now 同一套数，从 `Looker.opts()` 进来，
 * 这里只在**通话页必须不同的地方**动：`fps`（5，采样帧率）、`maxWaitSec`（=发送间隔）、
 * `queueDepth`（= `wakeBatch`，默认 1）。
 *
 * 用户 2026-11-13 的原话："你就老老实实按 see now 的来，别乱加东西，也别乱删东西"。
 */
final class CallStream {

    private Pipeline pipe;
    private long t0Ms;
    /** 最近封出来的一张（预览在 draft 空的时候用它 —— 刚发完那一瞬间 draft 是空的）。 */
    private Pipeline.Sheet last;
    /**
     * 攒够几张才交（= `Config.wakeBatch()`，见 {@link #open}）。
     *
     * 同时是"算不算该发"的判据（{@link #ready()}）—— see-now 就是同一个数管三件事：
     * `queueDepth` / 唤醒判据 / `takeSheets()` 一次交几张（`loop.js:477`）。
     */
    private int batch = 1;

    /** 开一个通话会话的管线。`base` 是 `Looker.opts()` 那份（灵敏度/档位映射/tuning 都在里面）。 */
    synchronized void open(Pipeline.Opts base, double maxWaitSec, int wakeBatch) {
        close();
        if (base == null) return;
        Pipeline.Opts o = base;
        o.fps = CallCapture.FPS;
        /**
         * 队列能压几张 = 一次交几张 = `wakeBatch`（see-now `runner.js:666` 同一个写法）。
         *
         * 压不住就等于没攒：默认 `queueDepth: 1` 是"出新图就挤掉旧的"，
         * 第 3 张出来时前两张已经没了，`takeMany()` 只能拿到一张。
         */
        o.queueDepth = Math.max(1, Math.min(4, wakeBatch));
        batch = o.queueDepth;
        o.maxWaitSec = Math.max(2, maxWaitSec);
        /**
         * ⚠️ **`maxFrames` 不许覆盖**（2026-11-14 纠正）。
         *
         * 曾经这里写 `o.maxFrames = 宫格张数(2~8)`，于是 `checkFlush` 最后那条
         * `frames.length >= maxFrames` 跑到前面去了 —— 2 帧时立刻封箱，
         * **把 see-now 真正的两条判据全架空**：
         *   · `cellArea < flushGain(0.015)`（再塞就碎格）
         *   · `frames >= 3 && t - sheetStart >= maxWaitSec`（等第三帧，token/帧 116 → 78）
         *
         * see-now 里 `maxFrames: 40` 只是**防爆保险**，几乎永不触发；一张图几帧是
         * "装不下 / 碎格 / 时间到"决定的。
         */
        // `silentFloorSec` / `floorGapK` / `maxFrames` **不动** —— 那是 `Looker.opts()`
        // （灵敏度档 + tuning）和 `Pipeline.Opts` 默认值的事，和电脑版同一套数。
        pipe = new Pipeline(o);
        t0Ms = System.currentTimeMillis();
        last = null;
    }

    synchronized void close() {
        pipe = null;
        last = null;
        batch = 1;
    }

    /**
     * 喂一帧（**必须按时间顺序**，5fps）。
     *
     * @param atMs 采集时刻（墙钟）；内部换算成"这条管线活了多久"
     */
    synchronized void push(long atMs, byte[] rgb, byte[] jpeg) {
        if (pipe == null || rgb == null || jpeg == null) return;
        pipe.push((atMs - t0Ms) / 1000.0, rgb, jpeg);
    }

    /**
     * 取走管线**自己封好的**那一张（没封箱 → null）。
     *
     * 封箱时机全在 `Pipeline`（`maxWaitSec` / `maxFrames` / 装不下 / 碎格），
     * 这一层一个字都不判 —— 判两次就会和电脑版不一样。
     */
    synchronized Pipeline.Sheet take() {
        if (pipe == null) return null;
        Pipeline.Sheet raw = pipe.take();
        if (raw == null) return null;
        Pipeline.Sheet s = dress(raw);
        last = s;
        return s;
    }

    /**
     * 一次取走队列里最多 `n` 张（**时间正序**：老的在前）。
     *
     * `n = 1` 时逐位等价于上面的 {@link #take()}。多张就是 see-now 的
     * `takeSheets()`（`runner.js:418`）—— 叫醒一次只交一张等于白攒：模型还得再
     * `peek` 两次，唤醒次数一点没少，"每轮重过一遍上下文"那笔钱一分没省。
     */
    synchronized List<Pipeline.Sheet> takeMany(int n) {
        List<Pipeline.Sheet> out = new ArrayList<>();
        if (pipe == null) return out;
        for (int i = 0; i < Math.max(1, n); i += 1) {
            Pipeline.Sheet raw = pipe.take();
            if (raw == null) break;
            Pipeline.Sheet s = dress(raw);
            if (s == null) continue;          // 这一箱排不出来（极少见）：跳过，别把后面的也扔了
            last = s;
            out.add(s);
        }
        return out;
    }

    /** 队列里现在攒着几张封好的（离线探针和排障用；`ready()` 就是它和 `batch` 比)。 */
    synchronized int pending() { return pipe == null ? 0 : pipe.pending(); }

    /**
     * 够不够发了：**攒够 `batch` 张**才算（see-now `loop.js:477`）。
     *
     * 不能只是 `pending > 0` —— 那样第 1 张就叫，攒第二、三张的时间里模型已经看完一轮了，
     * 队列压得住也没用，唤醒次数根本没减少。`batch = 1` 时 `pending >= 1` 就是原来的
     * `pending > 0`，行为逐位相同。
     */
    synchronized boolean ready() { return pending() >= batch; }

    /** 插话：**立刻**截断出图（不等判据，也不排队）。用户问的是"现在"。 */
    synchronized Pipeline.Sheet takeNow(long atMs, String why) {
        if (pipe == null) return null;
        double t = (atMs - t0Ms) / 1000.0;
        Pipeline.Sheet raw = pipe.flushNow(t, why);
        pipe.take();                       // flushNow 会把图推进队列，取走它（同 Looker.grab 的写法）
        if (raw == null) return null;
        Pipeline.Sheet s = dress(raw);
        last = s;
        return s;
    }

    /**
     * 预览用哪一张：**正在攒的这一箱**（draft）。
     *
     * 原来是"已封箱 → 上一张 → draft"，理由是 draft 会 1→2→3→4 格地长，用户看着像图在乱跳。
     * 但**调试要看的恰恰是这个**：3 秒刷一次、还显示几秒前封好的那张，根本判断不出
     * "这一帧有没有被抓进去、排成什么样"。2026-11-14 用户明确要求"每抓一帧都要变"。
     *
     * 代价只是多一点 layout 计算（几何是纯算术，几毫秒），帧的打分早就算过了，不重复。
     */
    synchronized Pipeline.Sheet preview() {
        if (pipe == null) return last;
        List<Pipeline.Entry> draft = pipe.draftList();
        if (!draft.isEmpty()) return dress(draft, "live");
        // 刚封箱那一瞬间 draft 是空的：挂着上一张，别闪成一块空白
        return last;
    }

    /** 正在攒的这一箱有几帧（界面显示用）。 */
    synchronized int draftFrames() { return pipe == null ? 0 : pipe.draftFrames(); }

    // ------------------------------------------------------------------ 排版

    /**
     * 管线封好的那一箱 → 手机版面。
     *
     * **档位仍然由管线给**（`tiersOf` 是 `flush()` 用的同一个函数），这里只做两件手机端的事：
     * 只做几何（{@link CallSheet#layout} = `flush()` 的几何部分）。帧一帧不增不减。
     */
    private Pipeline.Sheet dress(Pipeline.Sheet raw) {
        return dress(raw.frames, raw.why);
    }

    private Pipeline.Sheet dress(List<Pipeline.Entry> frames, String why) {
        Pipeline.Opts o = pipe.opts();
        Pipeline.Sheet s = CallSheet.layout(frames, pipe.tiersOf(frames), why, o.packMode, o.maxCrop);
        if (s == null) return null;
        return s;
    }
}
