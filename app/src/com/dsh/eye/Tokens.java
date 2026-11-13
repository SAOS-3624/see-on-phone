package com.dsh.eye;

/**
 * 图像计费口径：像素 → token，以及"这块内容该画多大画布"。
 *
 * **照抄 `dsh-video-storyboard/src/storyboard/tiers.js` 的 `imageTokens` + `fitCanvas`**，
 * 不是照 `dsh-see-now/src/tokens.js` 的 `fitCanvasToTokens` —— 那两个不是一套东西，
 * 前者是宿主的真实计费实现（报数 = 计费），后者只是插件侧的粗估。
 *
 * ## 口径（官方《图像理解 · Token 用量》2026-11）
 *
 *   - 图先按 14px 的 patch 对齐（**向上**取整到 14 的倍数）；
 *   - 每轴再 3:1 降采样 → 一个 token 格 = 42×42 px；
 *   - **每行多带 1 个分隔 token，整张再加 2 个框架 token**：`rows × (cols + 1) + 2`；
 *   - 小于 544×544 的图**计费时**会被放大到 544²（发送时并不放大，只是这么收钱）；
 *   - 超过 1024 时按长宽比解出能塞进预算的最大网格。
 *
 * 所以 471×628 = **197 token**，而"像素÷1650"那种粗估给不出来（它会说 179）。
 */
final class Tokens {

    /** 计费 patch 边长（px）。 */
    static final int PATCH = 14;
    /** 每轴降采样倍数。 */
    static final int DOWNSAMPLE = 3;
    /** 一个 token 格覆盖的像素边长（14×3）。 */
    static final int CELL = PATCH * DOWNSAMPLE;
    /** 单张封顶（官方 1024；宿主按 patch 网格算，实际最大是 994）。 */
    static final int TOKEN_CAP = 1024;
    /** 计费地板：小于 544² 的图一律按这个面积收。 */
    static final int UPSCALE_AREA = 544 * 544;
    /** 画布单边上限。超过它的部分宿主会缩回去，画了也白画。 */
    static final int MAX_SIDE = 800;

    /**
     * 宫格画布：3:4（= 摄像头画面比例），471×628。
     *
     * 这个数不是拍出来的：`fit(3,4)` 在"面积抬到计费地板 544²、单边 ≤ 800"下正好落在
     * 471×628（面积 295,788，比 544² 只差 148px，等于**整块画布都在计费地板里**，
     * 再画大一分钱不多花、再画小反而亏像素）。而且 471 ≤ 采集源宽 480，不会被放大。
     */
    static final int[] CANVAS = fit(3, 4);

    /** 一张宫格的 token（= 197，见 `Tokens` 的类注释；改画布必须重新报这个数）。 */
    static final int SHEET_TOKENS = imageTokens(CANVAS[0], CANVAS[1]);

    private Tokens() { }

    static int canvasW() { return CANVAS[0]; }

    static int canvasH() { return CANVAS[1]; }

    // ------------------------------------------------------------------ 像素 → token

    private static int intDiv(int v, int d) { return v / d; }

    private static int ceilDiv(int v, int d) { return (v + d - 1) / d; }

    /** patch 对齐（向上取整到 14 的倍数）。 */
    private static int padToPatch(int px) { return ceilDiv(px, PATCH) * PATCH; }

    /** patch 对齐后这一轴有多少个 token 格。 */
    private static int gridCells(int padded) { return ceilDiv(intDiv(padded, PATCH), DOWNSAMPLE); }

    /** 网格 → token：每行带 1 个分隔符，另加 2 个框架 token。 */
    private static int gridTokens(int rows, int cols) { return rows * (cols + 1) + 2; }

    /**
     * 一次"对齐 → 投影"：塞得下就原样，塞不下解出最大网格（宿主的闭式解）。
     *
     * @return `{W, H, tokens}`
     */
    private static int[] projectSize(int w, int h, int cap) {
        int tw = gridTokens(gridCells(padToPatch(h)), gridCells(padToPatch(w)));
        if (tw <= cap) return new int[] { w, h, tw };
        double aspect = h / (double) w;
        double idealW = Math.sqrt((cap - 2) / aspect + 0.25) - 0.5;
        int solvedW = (int) idealW;
        int solvedH = (int) (idealW * aspect);
        if (solvedW < 1) {
            solvedW = 1;
            solvedH = intDiv(cap - 2, 2);
        } else if (solvedH < 1) {
            solvedH = 1;
            solvedW = intDiv(cap - 2, solvedH) - 1;
        }
        double scale = Math.min((solvedW * (double) CELL) / w, (solvedH * (double) CELL) / h);
        int bw = (int) ((w * scale) / PATCH) * PATCH;
        int bh = (int) ((h * scale) / PATCH) * PATCH;
        return new int[] { bw, bh, gridTokens(gridCells(bh), gridCells(bw)) };
    }

    /**
     * **像素 → token**。全工程只有这一处把尺寸换成钱，别在别处再写死一个数。
     *
     * @return 估算 token（整数，≤ 1024）
     */
    static int imageTokens(int w, int h) {
        int cw = Math.round(w);
        int ch = Math.round(h);
        if (cw <= 0 || ch <= 0) return 0;
        // 地板：计费时先按面积放大到 544×544（宿主发送时不会放大，只是这么收费）
        long px = (long) cw * ch;
        if (px < UPSCALE_AREA) {
            double scale = Math.sqrt(UPSCALE_AREA / (double) px);
            cw = Math.max(1, (int) (cw * scale));
            ch = Math.max(1, (int) (ch * scale));
        }
        int[] cur = projectSize(cw, ch, TOKEN_CAP);
        // 宿主迭代到不动点（缩完再投影可能又变），这里同样最多跑 10 轮
        for (int i = 0; i < 10; i += 1) {
            int[] next = projectSize(cur[0], cur[1], TOKEN_CAP);
            if (next[2] == cur[2] && next[0] == cur[0] && next[1] == cur[1]) return next[2];
            cur = next;
        }
        return cur[2];
    }

    // ------------------------------------------------------------------ 画布尺寸

    /**
     * 把高度压回"42 的整数倍"，**只在溢出量 <= 高度 1.8% 时生效**。
     *
     * ⚠️ **本工程的抬头高度恒为 0**：App 的 `Sheet` 不画时间抬头（那是 storyboard 的
     * 时间码条），所以这里不能照抄 `snapHeightToHeader` 里的 `headerHeight(width)` ——
     * 把不存在的页眉算进去，报出来的 token 就和实际发出去的图不符。别以为这里漏了。
     *
     * 压多少才划算：只压掉"本来就要多付一整行"的那点余量，而且必须**真降价**才压
     * （地板区怎么压都是同一个价，白掉像素反而是亏）。
     */
    private static int snapHeight(int height, int width) {
        int over = height % CELL;
        int gate = Math.max(8, Math.round(height * 0.018f));
        if (over <= 0 || over > gate) return height;
        int next = height - over;
        if (next < 36) return height;
        return imageTokens(width, next) < imageTokens(width, height) ? next : height;
    }

    /**
     * 画布尺寸：面积 = 计费地板（544²），且任一边不超过 `MAX_SIDE`。
     *
     * 为什么面积直接取地板：小于 544² 的图**一样收 544² 的钱**，画小了只赔像素。
     * 那为什么不画得更大：超过地板的每一个 px 都是真金白银，而这一格画面本身只有
     * 480×640 —— 再放大不会多出细节。
     *
     * 宽度对齐到 42 的倍数只在"真能降价"时生效（`pack.js` 那边的 `fitCanvas` 同一条规矩）：
     * 471 → 462 落在同一行数上，价格不变，那就保持 471，别白扔 9px 画面。
     *
     * @param aspectNum 宽
     * @param aspectDen 高
     * @return `{w, h}`
     */
    static int[] fit(int aspectNum, int aspectDen) {
        double a = Math.max(0.2, Math.min(5, aspectNum / (double) aspectDen));
        int area = UPSCALE_AREA;
        double w = Math.sqrt(area * a);
        double h = w / a;
        double shrink = Math.min(1, Math.min(MAX_SIDE / w, MAX_SIDE / h));
        int width = Math.max(64, (int) Math.round(w * shrink));
        int height = snapHeight(Math.max(36, (int) Math.round(h * shrink)), width);

        int snapped = intDiv(width, CELL) * CELL;
        if (snapped >= 64) {
            int snapH = snapHeight(Math.max(36, (int) Math.round(snapped / a)), snapped);
            if (imageTokens(snapped, snapH) < imageTokens(width, height)) {
                return new int[] { snapped, snapH };
            }
        }
        return new int[] { width, height };
    }
}
