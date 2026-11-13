package com.dsh.eye;

import java.util.ArrayList;
import java.util.List;

/**
 * 通话页的「一张宫格」—— **`dsh-see-now/src/pipeline.js::flush()` 的几何部分，逐行移植**。
 *
 * ## 它做的事（和电脑版一模一样，不多不少）
 *
 * ```
 * Pipeline 封好的一箱（挑帧/判据已经做完）
 *   └─ seqOf() 给的档位（`Pipeline.tiersOf`，和 flush 同一个函数）
 *        └─ packTiers（= Packer.pack：rows / skyline）
 *             └─ 只取 bin 0（flush 最后那道闸）
 *                  └─ lay（单格铺满 / 裁掉整行空着的带子）
 * ```
 *
 * ## 三条"不许"（2026-11-13 用户拍板说的）
 *
 * 1. **不许改顺序**：格子按 `seq`（时间顺序）一行一行铺，编号 `index = seq + 1`，
 *    所以图上的 1/2/3/4 一定是从上到下、从左到右。别做"把主格搬到第一格"的模板 ——
 *    试过，用户原话："为什么 4 编号在最上面？为什么最上面的永远最大？这不符合逻辑"。
 * 2. **不许自己改档位**：面积 ∝ 这一帧的变化量是 `seqOf()` 算的，这里**一个数都不动**。
 *    曾经在这里加过"主格钉 2 档、其余钉 4 档"、"小格抬到 5 档"、"首帧取中位数" ——
 *    全是自作主张，全删了。
 * 3. **不许自己补洞**：行宽不满就空着（`Sheet` 会铺灰块，电脑版也这样）。
 *    试过"把每行按比例拉满"，那是另一种版面，不是 see-now 的。
 *
 * 帧一帧不增不减：装不下时按 `flush()` 的做法**只画 bin 0**（多出来的丢给下一张，
 * `Pipeline` 的 `splitOneBin` 早就切过了，走到这里还溢出就是 `stats.overflow`）。
 *
 * 纯 Java、不碰 Bitmap —— `tools/parity/{stream,render}.sh` 在电脑上验。
 */
final class CallSheet {

    /** 多开一张图的代价。和 `Pipeline.Opts.binCost` 同一口径（宁可挤，也不多开）。 */
    private static final double BIN_COST = 1000;

    private CallSheet() { }

    /**
     * 管线封好的一箱 → 一张宫格（描述，还没画）。
     *
     * @param picked   这一箱的帧（时间顺序；`Pipeline` 给的）
     * @param tiers    `Pipeline.tiersOf(picked)`（**必须**是它，和 flush 同一口径）
     * @param why      封箱理由（`cap` / `time` / `user` …，只用于日志和元信息）
     * @param packMode `rows`（默认）/ `skyline`
     */
    static Pipeline.Sheet layout(List<Pipeline.Entry> picked, int[] tiers, String why,
                                 String packMode, double maxCrop) {
        if (picked == null || picked.isEmpty()) return null;
        int n = picked.size();
        int[] t = new int[n];
        for (int i = 0; i < n; i += 1) {
            int v = (tiers != null && i < tiers.length) ? tiers[i] : picked.get(i).tier;
            t[i] = Math.max(1, Math.min(8, v));
        }

        // packTiers(seq, { binCost, packMode })
        Packer.Result p = Packer.pack(t, Packer.DEFAULT_STRETCH, maxCrop, maxCrop, BIN_COST, packMode);
        // 一张图只画一个箱子：**先贴 via，再取 bin 0**（flush 的注释：取完再贴只能按下标猜）
        Pipeline.attachVia(p, picked);
        int keptN = 0;
        for (Packer.Cell c : p.cells) if (c.bin == 0) keptN += 1;
        if (keptN <= 0) return null;
        int use = Math.min(keptN, n);
        List<Pipeline.Entry> used = new ArrayList<>(picked.subList(0, use));

        // lay(toRects(kept)) —— 单格铺满 / 裁掉整行空着的带子；两件事由 `oneSheet` 焊在一起
        Packer.Result one = Packer.oneSheet(p, 0);

        Pipeline.Sheet sh = new Pipeline.Sheet();
        sh.why = why == null ? "call" : why;
        sh.frames = used;
        sh.tiers = new int[use];
        System.arraycopy(t, 0, sh.tiers, 0, use);
        sh.layout = one;
        sh.from = used.get(0).t;
        sh.to = used.get(used.size() - 1).t;
        sh.at = sh.to;
        if (use < n) EyeLog.w("sheet", "这一箱有 " + n + " 帧，一张只画得下 " + use
            + "（多出来的丢给下一张，见 Pipeline.splitOneBin）");
        return sh;
    }
}
