package com.dsh.eye;

/**
 * Cuts —— 逐帧画面变化分。**`dsh-see-now/src/cuts.js` 的逐行移植**。
 *
 * 因果，不看未来（除了一帧的尖峰确认）。`scorePair` 照抄 storyboard 的 scan.js
 * （HSV 三通道加权绝对差，0–255），唯一改动：喂进来的不是整帧，而是 64×36 的网格，
 * 顺带把降采样的平均做了。
 *
 * 下面这些常量的取值理由全在 JS 那份注释里（实测表也在那），**改任何数字之前先回去读**。
 */
final class Cuts {

    static final int SCAN_W = 64;
    static final int SCAN_H = 36;
    static final int CELLS = SCAN_W * SCAN_H;

    /** 参考亮度（视频/影视内容的常规亮度；见 cuts.js 的实测）。 */
    static final double REF_V = 96;
    /** 放大上限。 */
    static final double GAIN_MAX = 1.85;
    /** 参考亮度下限（防除零）。 */
    static final double V_FLOOR = 40;
    /** 噪声地板：地板以下不放大，暗场的编码噪声 ×3 会变成假切点。 */
    static final double NOISE_FLOOR = 2;
    /** 黑边判定：窗口内每行的 V 均值 < BAR_MEAN，且该行一次都没亮过（< BAR_MAX）。 */
    static final double BAR_MEAN = 16;
    static final double BAR_MAX = 24;
    /** 黑边占比上限：超过它多半是"整段黑场"，不做补偿。 */
    static final double BAR_CAP = 0.35;
    /** 黑边窗口（帧，按 12fps 定的基准 = 2 秒）。实际帧数由 `Scorer` 按 fps 折算。 */
    static final int BAR_WINDOW = 24;

    private Cuts() { }

    // ------------------------------------------------------------------ 网格 / 色彩

    /** 64×36 网格：每格取 4 个采样点求平均（降噪，且只花 9k 次读）。 */
    static void gridRgb(byte[] rgb, int width, int height, float[] out) {
        double sx = width / (double) SCAN_W;
        double sy = height / (double) SCAN_H;
        for (int gy = 0; gy < SCAN_H; gy += 1) {
            int y0 = (int) ((gy + 0.25) * sy);
            int y1 = (int) ((gy + 0.75) * sy);
            int r0 = y0 * width;
            int r1 = y1 * width;
            for (int gx = 0; gx < SCAN_W; gx += 1) {
                int x0 = (int) ((gx + 0.25) * sx);
                int x1 = (int) ((gx + 0.75) * sx);
                int a = (r0 + x0) * 3;
                int b = (r0 + x1) * 3;
                int c = (r1 + x0) * 3;
                int d = (r1 + x1) * 3;
                int i = (gy * SCAN_W + gx) * 3;
                out[i] = (float) (((rgb[a] & 0xff) + (rgb[b] & 0xff) + (rgb[c] & 0xff) + (rgb[d] & 0xff)) * 0.25);
                out[i + 1] = (float) (((rgb[a + 1] & 0xff) + (rgb[b + 1] & 0xff) + (rgb[c + 1] & 0xff) + (rgb[d + 1] & 0xff)) * 0.25);
                out[i + 2] = (float) (((rgb[a + 2] & 0xff) + (rgb[b + 2] & 0xff) + (rgb[c + 2] & 0xff) + (rgb[d + 2] & 0xff)) * 0.25);
            }
        }
    }

    /** RGB → HSV（各通道归一到 0–255，H 由角度缩放）。就地写进 out，不分配。 */
    static void rgbToHsv(float[] rgb, float[] out) {
        int n = rgb.length;
        for (int i = 0, j = 0; i < n; i += 3, j += 3) {
            double rn = rgb[i] / 255.0;
            double gn = rgb[i + 1] / 255.0;
            double bn = rgb[i + 2] / 255.0;
            double max = rn > gn ? (rn > bn ? rn : bn) : (gn > bn ? gn : bn);
            double min = rn < gn ? (rn < bn ? rn : bn) : (gn < bn ? gn : bn);
            double d = max - min;
            double h = 0;
            if (d != 0) {
                if (max == rn) h = ((gn - bn) / d) % 6;
                else if (max == gn) h = (bn - rn) / d + 2;
                else h = (rn - gn) / d + 4;
                h *= 60;
                if (h < 0) h += 360;
            }
            out[j] = (float) ((h / 360) * 255);
            out[j + 1] = (float) (max == 0 ? 0 : (d / max) * 255);
            out[j + 2] = (float) (max * 255);
        }
    }

    /** 两帧 HSV 的加权绝对差均值，0–255。色相按环形处理，低饱和像素的色相降权。 */
    static double hsvDiff(float[] a, float[] b, int pa, int pb) {
        double dh = Math.abs(a[pa] - b[pb]);
        double hd = Math.min(dh, 255 - dh);
        double w = Math.min(a[pa + 1], b[pb + 1]) / 255.0;
        return (hd * w + Math.abs(a[pa + 1] - b[pb + 1]) + Math.abs(a[pa + 2] - b[pb + 2])) / 3;
    }

    static double scorePair(float[] prev, float[] cur) {
        double sum = 0;
        for (int i = 0; i < cur.length; i += 3) {
            double dh = Math.abs(cur[i] - prev[i]);
            double hueDiff = Math.min(dh, 255 - dh);
            double hueWeight = Math.min(cur[i + 1], prev[i + 1]) / 255.0;
            sum += hueDiff * hueWeight
                + Math.abs(cur[i + 1] - prev[i + 1])
                + Math.abs(cur[i + 2] - prev[i + 2]);
        }
        return sum / ((cur.length / 3.0) * 3);
    }

    // ------------------------------------------------------------------ 亮度归一 / 黑边

    /** 亮度增益：只在暗场 > 1，亮画面（V ≥ REF_V）恒等于 1。 */
    static double brightnessGain(double meanV) {
        if (Double.isNaN(meanV) || meanV <= 0) return 1;
        return Math.min(GAIN_MAX, Math.max(1, REF_V / Math.max(V_FLOOR, meanV)));
    }

    /** 把绝对分换算到"参考亮度、无黑边"的刻度上。纯函数。 */
    static double normalizeScore(double raw, double meanV, double barFrac) {
        if (Double.isNaN(raw) || raw <= 0) return raw;
        if (raw < NOISE_FLOOR) return raw;
        double gain = brightnessGain(meanV);
        double bf = (!Double.isNaN(barFrac) && barFrac > 0) ? Math.min(barFrac, BAR_CAP) : 0;
        if (gain == 1 && bf == 0) return raw;
        return (raw * gain) / Math.max(0.05, 1 - bf);
    }

    /** 黑边行数：窗口里"一直是黑"的顶部/底部连续行。 */
    static int[] barRows(float[] rowV, float[] rowVMax, int frames) {
        int h = SCAN_H;
        if (rowV == null || rowVMax == null || !(frames > 0)) return new int[] { 0, 0 };
        int top = 0;
        while (top < h && isDark(rowV, rowVMax, top, frames)) top += 1;
        int bottom = 0;
        while (bottom < h - top && isDark(rowV, rowVMax, h - 1 - bottom, frames)) bottom += 1;
        return new int[] { top, bottom };
    }

    private static boolean isDark(float[] rowV, float[] rowVMax, int y, int frames) {
        return rowV[y] / frames < BAR_MEAN && rowVMax[y] < BAR_MAX;
    }

    /** 黑边占比：0 – BAR_CAP；整段黑场返回 0（没有"补偿"可言）。 */
    static double barFraction(float[] rowV, float[] rowVMax, int frames) {
        if (rowV == null || rowVMax == null || !(frames > 0)) return 0;
        int[] tb = barRows(rowV, rowVMax, frames);
        double frac = (tb[0] + tb[1]) / (double) rowV.length;
        if (frac >= BAR_CAP || frac >= 0.999) return 0;
        return Math.round(frac * 1000) / 1000.0;
    }

    /** 画面有效高度占比：去掉上下黑边，全黑帧返回 0。 */
    static double contentHeightRatio(float[] hsv) {
        int top = 0;
        int bottom = 0;
        while (top < SCAN_H && darkRow(hsv, top)) top += 1;
        while (top + bottom < SCAN_H && darkRow(hsv, SCAN_H - 1 - bottom)) bottom += 1;
        return (SCAN_H - top - bottom) / (double) SCAN_H;
    }

    private static boolean darkRow(float[] hsv, int y) {
        double maxV = 0;
        double sum = 0;
        int base = y * SCAN_W;
        for (int x = 0; x < SCAN_W; x += 1) {
            double v = hsv[(base + x) * 3 + 2];
            if (v > maxV) maxV = v;
            sum += v;
        }
        return maxV < 24 && sum / SCAN_W < 12;
    }

    /** 前景守卫用的有效高度：暗场里有亮内容时不再被判成没有画面。 */
    static double guardHeightRatio(float[] hsv) {
        double ratio = contentHeightRatio(hsv);
        if (ratio >= 0.25) return ratio;
        double maxV = 0;
        int y0 = (int) Math.floor(SCAN_H * 0.25);
        int y1 = (int) Math.ceil(SCAN_H * 0.75);
        for (int y = y0; y < y1; y += 1) {
            for (int x = 0; x < SCAN_W; x += 1) {
                double v = hsv[(y * SCAN_W + x) * 3 + 2];
                if (v > maxV) maxV = v;
            }
        }
        return maxV >= 48 ? 1 : ratio;
    }

    // ------------------------------------------------------------------ 运动补偿 / 背景

    /** mcStats 的返回值。 */
    static final class Mc {
        double score;
        double bbox;
        double fill;
        double spanY;
        int x0, y0, x1, y1;
        boolean boxNull = true;
    }

    /**
     * 运动补偿打分：每个格子在下一帧 ±radius 邻域里找最像的，连最像的都超 tau 才算"变了"。
     * 返回 0–255 的占比刻度。
     */
    static Mc mcStats(float[] prev, float[] cur, double tau, int radius) {
        Mc out = new Mc();
        int changed = 0;
        int minX = SCAN_W;
        int minY = SCAN_H;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < SCAN_H; y += 1) {
            for (int x = 0; x < SCAN_W; x += 1) {
                int p3 = (y * SCAN_W + x) * 3;
                // **先测同位置（dx=dy=0）**：屏幕最常见的是"这一格没变"
                double dh0 = Math.abs(cur[p3] - prev[p3]);
                double hd0 = dh0 < 255 - dh0 ? dh0 : 255 - dh0;
                double hw0 = Math.min(cur[p3 + 1], prev[p3 + 1]) / 255.0;
                double d0 = (hd0 * hw0
                    + Math.abs(cur[p3 + 1] - prev[p3 + 1])
                    + Math.abs(cur[p3 + 2] - prev[p3 + 2])) / 3;
                boolean matched = d0 <= tau;
                if (!matched) {
                    outer:
                    for (int dy = -radius; dy <= radius; dy += 1) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= SCAN_H) continue;
                        for (int dx = -radius; dx <= radius; dx += 1) {
                            if (dx == 0 && dy == 0) continue;
                            int xx = x + dx;
                            if (xx < 0 || xx >= SCAN_W) continue;
                            int q3 = (yy * SCAN_W + xx) * 3;
                            double dh = Math.abs(cur[q3] - prev[p3]);
                            double hueDiff = Math.min(dh, 255 - dh);
                            double hueWeight = Math.min(cur[q3 + 1], prev[p3 + 1]) / 255.0;
                            double d = (hueDiff * hueWeight
                                + Math.abs(cur[q3 + 1] - prev[p3 + 1])
                                + Math.abs(cur[q3 + 2] - prev[p3 + 2])) / 3;
                            if (d <= tau) { matched = true; break outer; }
                        }
                    }
                }
                if (!matched) {
                    changed += 1;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        int w = changed == 0 ? 0 : maxX - minX + 1;
        int h = changed == 0 ? 0 : maxY - minY + 1;
        out.score = (changed / (double) CELLS) * 255;
        out.bbox = ((w * h) / (double) CELLS) * 255;
        out.fill = changed == 0 ? 0 : changed / (double) (w * h);
        out.spanY = h / (double) SCAN_H;
        if (changed > 0) {
            out.boxNull = false;
            out.x0 = minX; out.y0 = minY; out.x1 = maxX; out.y1 = maxY;
        }
        return out;
    }

    /** 背景模型（`bgStats` 的 withModel 产物）。 */
    static final class BgModel {
        float[] med = new float[CELLS * 3];
        byte[] stable = new byte[CELLS];
        double tau = 15;
    }

    /** bgStats 的返回值。 */
    static final class Bg {
        Double score = null;
        int stable = 0;
        int changed = 0;
        BgModel model = null;
    }

    /**
     * 背景模型：在最近 `history.size()` 帧里找**一直没怎么动**的格子，
     * 再看当前帧把这些稳定背景格改掉了多少。
     *
     * 前景运动只盖住一部分背景，稳定背景格大部分还在；真正切镜头会把旧背景整片换掉。
     */
    static Bg bgStats(java.util.List<float[]> history, float[] cur, double stableRange, double tau, boolean withModel) {
        Bg out = new Bg();
        int m = history.size();
        if (m == 0) return out;
        double[] vals = new double[m];
        int half = m >> 1;
        if (withModel) {
            out.model = new BgModel();
            out.model.tau = tau;
        }
        for (int c = 0; c < CELLS; c += 1) {
            int p = c * 3;
            double range = 0;
            double medR = 0, medG = 0, medB = 0;
            for (int ch = 0; ch < 3; ch += 1) {
                for (int j = 0; j < m; j += 1) vals[j] = history.get(j)[p + ch];
                // m ≤ 8 的插入排序（比通用排序快一倍多）
                for (int i = 1; i < m; i += 1) {
                    double v = vals[i];
                    int k = i - 1;
                    while (k >= 0 && vals[k] > v) { vals[k + 1] = vals[k]; k -= 1; }
                    vals[k + 1] = v;
                }
                double mid = vals[half];
                if (ch == 0) medR = mid; else if (ch == 1) medG = mid; else medB = mid;
                double r = vals[m - 1] - vals[0];
                if (r > range) range = r;
            }
            if (out.model != null) {
                out.model.med[p] = (float) medR;
                out.model.med[p + 1] = (float) medG;
                out.model.med[p + 2] = (float) medB;
            }
            if (range > stableRange) continue;
            out.stable += 1;
            if (out.model != null) out.model.stable[c] = 1;
            double dh = Math.abs(cur[p] - medR);
            double hd = dh < 255 - dh ? dh : 255 - dh;
            double hw = Math.min(cur[p + 1], medG) / 255.0;
            double d = (hd * hw + Math.abs(cur[p + 1] - medG) + Math.abs(cur[p + 2] - medB)) / 3;
            if (d > tau) out.changed += 1;
        }
        out.score = out.stable == 0 ? null : (out.changed / (double) out.stable) * 255;
        return out;
    }

    /** bgKeep 的返回值。 */
    static final class Keep {
        Double score = null;
        int changed = 0;
        int kept = 0;
    }

    /** 用候选帧后的画面确认：候选帧改掉的格子，有多少到未来帧还没回到参考背景。 */
    static Keep bgKeep(BgModel model, float[] candidate, float[] future, double tau) {
        Keep out = new Keep();
        if (model == null) return out;
        for (int c = 0; c < CELLS; c += 1) {
            int p = c * 3;
            if (hsvDiff(candidate, model.med, p, p) > tau) {
                out.changed += 1;
                if (hsvDiff(future, model.med, p, p) > tau) out.kept += 1;
            }
        }
        out.score = out.changed == 0 ? null : (out.kept / (double) out.changed) * 255;
        return out;
    }

    /** bgBorderChanged 的返回值。 */
    static final class Border {
        double score;
        int changed;
        int total;
    }

    /** 候选帧改掉了多少外圈：大面积前景停住时外圈通常还是旧背景；真正换镜头外圈也会被替换。 */
    static Border bgBorderChanged(BgModel model, float[] candidate, double activeY, int thick, double tau) {
        if (model == null || !(activeY >= 0.25)) return null;
        int bar = activeY >= 0.999 ? 0 : (int) Math.round((1 - activeY) * SCAN_H / 2);
        int t = Math.max(1, Math.min(thick, (SCAN_H - bar * 2) / 4));
        int y0 = bar + t;
        int y1 = SCAN_H - bar - t;
        if (y1 <= y0) return null;
        int total = 0;
        int changed = 0;
        for (int y = bar; y < SCAN_H - bar; y += 1) {
            boolean edgeRow = y < y0 || y >= y1;
            for (int x = 0; x < SCAN_W; x += 1) {
                if (!(edgeRow || x < t || x >= SCAN_W - t)) continue;
                total += 1;
                if (hsvDiff(candidate, model.med, (y * SCAN_W + x) * 3, (y * SCAN_W + x) * 3) > tau) changed += 1;
            }
        }
        if (total == 0) return null;
        Border b = new Border();
        b.score = (changed / (double) total) * 255;
        b.changed = changed;
        b.total = total;
        return b;
    }

    // ------------------------------------------------------------------ 打分器

    /** 一帧的打分结果。字段与 `cuts.js::createScorer` 的返回值一一对应。 */
    static final class Score {
        Double score = null;
        Double rawScore = null;
        double gain = 1;
        double barFrac = 0;
        Double meanV = null;
        Double mcScore = null;
        Double mcBox = null;
        Mc mcRect = null;
        Double mcFill = null;
        double mcSpanY = 0;
        double activeY;
        double guardY;
        Double bgScore = null;
        int bgStable = 0;
        int bgChanged = 0;
        BgModel bgModel = null;
        BgModel futureModel = null;
        float[] prevHsv = null;
        float[] hsv;
    }

    /** 打分器：内部维护上一帧的 HSV。构造参数与 `createScorer` 同名同义。 */
    static final class Scorer {
        private final int width;
        private final int height;
        private final String scoreMode;
        private final double mcTau;
        private final int mcRadius;
        private final int gateMcRadius;
        private final int bgWindow;
        private final double bgStableRange;
        private final double bgTau;
        private final boolean bgModelOn;
        private final int bgRefWindow;
        private final double futureMinScore;
        private final boolean normalize;

        private final float[] rgb = new float[CELLS * 3];
        private final java.util.List<float[]> bgHistory = new java.util.ArrayList<>();
        private final java.util.List<float[]> refHistory = new java.util.ArrayList<>();
        private float[] prev = null;
        private final float[] cur = new float[CELLS * 3];

        /**
         * 黑边窗口**帧数**。
         *
         * 电脑版是写死的 24（它固定 12fps，等于 2 秒）。手机帧率是 8/15 这种档位，
         * 帧数写死就等于"窗口时长随帧率变" —— 所以这里按 fps 折算，保持**秒数**不变。
         * 折算由 `Pipeline` 决定（`timeNormalizeFrames` 关掉时就是 24，跟电脑版逐帧一致）。
         */
        private final int barWindow;
        private final float[][] rowV;
        private final float[][] rowVMax;
        private final float[] barV = new float[SCAN_H];
        private final float[] barVMax = new float[SCAN_H];
        private int barSlot = 0;
        private int barFrames = 0;

        Scorer(int width, int height, String scoreMode, double mcTau, int mcRadius, int gateMcRadius,
               int bgWindow, double bgStableRange, double bgTau, boolean bgModelOn,
               int bgRefWindow, double futureMinScore, boolean scoreNormalize, int barWindow) {
            this.width = width;
            this.height = height;
            this.scoreMode = scoreMode == null ? "abs" : scoreMode;
            this.mcTau = mcTau;
            this.mcRadius = mcRadius;
            this.gateMcRadius = gateMcRadius;
            this.bgWindow = bgWindow;
            this.bgStableRange = bgStableRange;
            this.bgTau = bgTau;
            this.bgModelOn = bgModelOn;
            this.bgRefWindow = bgRefWindow;
            this.futureMinScore = futureMinScore;
            this.barWindow = Math.max(1, barWindow);
            this.rowV = new float[this.barWindow][SCAN_H];
            this.rowVMax = new float[this.barWindow][SCAN_H];
            // 亮度归一只对 `abs` 口径有意义（mc 的刻度是另一套）
            this.normalize = scoreNormalize && !"mc".equals(this.scoreMode);
        }

        private double[] readBarWindow(float[] hsv) {
            float[] sums = rowV[barSlot];
            float[] maxes = rowVMax[barSlot];
            for (int y = 0; y < SCAN_H; y += 1) {
                int base = y * SCAN_W * 3;
                float sum = 0;
                float mx = 0;
                for (int x = 0; x < SCAN_W; x += 1) {
                    float v = hsv[base + x * 3 + 2];
                    sum += v;
                    if (v > mx) mx = v;
                }
                sums[y] = sum;
                maxes[y] = mx;
            }
            barSlot = (barSlot + 1) % barWindow;
            if (barFrames < barWindow) barFrames += 1;
            for (int y = 0; y < SCAN_H; y += 1) {
                float sum = 0;
                float mx = 0;
                for (int k = 0; k < barFrames; k += 1) {
                    sum += rowV[k][y];
                    if (rowVMax[k][y] > mx) mx = rowVMax[k][y];
                }
                barV[y] = sum;
                barVMax[y] = mx;
            }
            int[] tb = barRows(barV, barVMax, barFrames);
            // **亮度只算有效行**（跳过上下黑边），否则同一件事会补两遍
            double vSumActive = 0;
            for (int y = tb[0]; y < SCAN_H - tb[1]; y += 1) vSumActive += sums[y];
            int activeRows = Math.max(1, SCAN_H - tb[0] - tb[1]);
            return new double[] {
                vSumActive / (activeRows * (double) SCAN_W),
                barFraction(barV, barVMax, barFrames),
            };
        }

        /** 喂一帧 rgb24（`width * height * 3`）。 */
        Score score(byte[] frame) {
            Score out = new Score();
            gridRgb(frame, width, height, rgb);
            rgbToHsv(rgb, cur);
            Double s = null;
            double gain = 1;
            double barFrac = 0;
            Double meanV = null;
            out.activeY = contentHeightRatio(cur);
            out.guardY = guardHeightRatio(cur);
            if (normalize) {
                double[] st = readBarWindow(cur);
                meanV = st[0];
                barFrac = st[1];
            }
            if (bgWindow > 0 && bgHistory.size() >= bgWindow) {
                Bg bg = bgStats(bgHistory, cur, bgStableRange, bgTau, bgModelOn);
                out.bgScore = bg.score;
                out.bgStable = bg.stable;
                out.bgChanged = bg.changed;
                out.bgModel = bg.model;
            }
            if (prev != null) {
                Mc mc = mcStats(prev, cur, mcTau, gateMcRadius);
                out.mcScore = mc.score;
                out.mcBox = mc.bbox;
                out.mcRect = mc.boxNull ? null : mc;
                out.mcFill = mc.fill;
                out.mcSpanY = mc.spanY;
                if ("mc".equals(scoreMode)) {
                    s = gateMcRadius == mcRadius
                        ? mc.score
                        : mcStats(prev, cur, mcTau, mcRadius).score;
                } else {
                    double rawScore = scorePair(prev, cur);
                    out.rawScore = rawScore;
                    s = normalize ? normalizeScore(rawScore, meanV, barFrac) : rawScore;
                    gain = normalize ? brightnessGain(meanV) : 1;
                }
            }
            if (bgRefWindow > 0
                && s != null
                && s >= futureMinScore
                && refHistory.size() >= Math.min(bgRefWindow, 8)) {
                Bg ref = bgStats(refHistory, cur, bgStableRange, bgTau, true);
                out.futureModel = ref.model;
            }
            out.prevHsv = prev != null ? prev.clone() : null;
            // prev 复用同一块缓冲（JS 也是 tmp.set(cur) 后复用）
            if (prev == null) prev = new float[CELLS * 3];
            System.arraycopy(cur, 0, prev, 0, cur.length);
            if (bgWindow > 0) {
                bgHistory.add(cur.clone());
                while (bgHistory.size() > bgWindow) bgHistory.remove(0);
            }
            if (bgRefWindow > 0) {
                refHistory.add(cur.clone());
                while (refHistory.size() > bgRefWindow) refHistory.remove(0);
            }
            out.score = s;
            out.gain = gain;
            out.barFrac = barFrac;
            out.meanV = meanV;
            out.hsv = cur.clone();
            return out;
        }
    }
}
