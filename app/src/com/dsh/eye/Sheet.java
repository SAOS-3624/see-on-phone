package com.dsh.eye;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 `Pipeline` 封出来的一箱画成一张宫格（"连续看"每轮就发这一张）。
 *
 * ## 这里只负责画，不负责决定放哪几帧
 *
 * 什么时候封箱、箱里放哪几帧、每帧多大 —— 全在 `Pipeline`（= 电脑版 `pipeline.js`）。
 * 排布用的是 `Packer`（= 电脑版 `pack.js`），而且 **cells 是 Pipeline 算好带过来的**，
 * 这里不重算：重算要再传一次 `binCost`，一旦和判定时用的口径不一致（pack.js 默认 1.02、
 * pipeline 用 1000），画出来的版面就会和"当时判它装得下"矛盾。
 *
 * 电脑版这一层是 `sheet.js::composePacked` —— 缝 1px、空白铺灰块、编号画左上角。
 * 唯一不同的是编码：那边出 PNG（要交给另一个进程），这边出 JPEG（直接发 HTTP）。
 *
 * ## 画布尺寸固定，不再按内容裁
 *
 * 以前按"内容用到的高度"裁（2 帧并排就只画半高）。现在**固定 471×628**：那个尺寸是
 * 计费地板（544²）下的最优解，裁掉一半并不能少付钱（地板按面积收），却会让模型以为
 * "下面就是画面尽头"。尺寸的唯一来源是 `Tokens.CANVAS`。
 */
final class Sheet {

    /**
     * 格子之间的缝，**同电脑版的 `SEAM`**：每格右/下少画 1px，底色透出来就是分隔线。
     * 留空白会把画布撑大、白花 token；没有缝时模型会把相邻两张图连起来读。
     */
    private static final int SEAM = 1;

    /** 发送用的 JPEG 质量。85 = 编号边缘还清楚，文件也不至于大到拖慢上传。 */
    static final int DEFAULT_QUALITY = 85;

    /** 底色（`sheet.js` 的 BACKGROUND = [18,18,20]）。 */
    private static final int BACKGROUND = 0xFF121214;
    /** 空洞的灰块（`sheet.js` 的 PAD_FILL = [42,42,46]）—— 纯黑空白看起来像渲染失败。 */
    private static final int PAD_FILL = 0xFF2A2A2E;
    /** 空洞灰块里那根居中横杠（`sheet.js` 的 PAD_GLYPH = [110,110,116]）。 */
    private static final int PAD_GLYPH = 0xFF6E6E74;
    /** 短于这么宽的未覆盖段不铺：1px 级的细缝铺了只会让整张图变成噪点。 */
    private static final int PAD_MIN_RUN = 8;

    private Sheet() { }

    /** 全尺寸宫格（画布由这一箱自己定，通常是 471×628，两帧并排时是 471×314）—— 发给模型的就是这一张。 */
    static byte[] compose(Pipeline.Sheet sheet) {
        return compose(sheet, sheet.layout.canvasW, DEFAULT_QUALITY);
    }

    /**
     * @param sheet   Pipeline 封出来的一箱
     * @param wantW   画布宽度（预览用得到；高度按**这一箱自己的**宽高比算）
     * @param quality JPEG 质量
     * @return 拼好的 JPEG；一帧都解不出来时返回 null
     */
    static byte[] compose(Pipeline.Sheet sheet, int wantW, int quality) {
        if (sheet == null || sheet.layout == null || sheet.layout.cells.isEmpty()) return null;
        if (sheet.frames.isEmpty()) return null;

        int canvasW = Math.max(1, wantW);
        // 按这张宫格**自己的**比例：整行空着时画布已经被裁小了（见 `Packer#lay`），
        // 还按 3:4 算会把缩略图拉长。
        int canvasH = canvasW == sheet.layout.canvasW
            ? Math.max(1, sheet.layout.canvasH)
            : Math.max(1, Math.round(canvasW * (sheet.layout.canvasH / (float) sheet.layout.canvasW)));

        List<Packer.Rect> rects = Packer.toRects(sheet.layout.cells, canvasW, canvasH, 0);
        int n = sheet.layout.cells.size();

        // 每个格子多大解码：取最大那格，小格再靠 drawBitmap 缩下去
        int maxTileW = 1;
        for (Packer.Rect r : rects) maxTileW = Math.max(maxTileW, r.pw);

        Bitmap canvas = null;
        try {
            canvas = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(canvas);
            c.drawColor(BACKGROUND);

            Paint fill = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            Paint pad = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setColor(Color.WHITE);
            label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
            plate.setColor(0x99000000);

            // **覆盖掩码只在真有空洞时才建**：单帧铺满、4 帧 2×2 这类最常见的情况
            // 覆盖了整张画布，扫 30 万像素纯属白干。
            long covered = 0;
            for (Packer.Rect r : rects) covered += (long) r.pw * r.ph;
            boolean[] mask = covered < (long) canvasW * canvasH - 1
                ? new boolean[canvasW * canvasH] : null;

            int drawn = 0;
            for (int i = 0; i < n; i += 1) {
                Packer.Rect r = rects.get(i);
                // 右/下各少画 SEAM 像素，底色透出来就是分隔线（同电脑版 sheet.js）
                int cw = Math.max(1, r.pw - SEAM);
                int ch = Math.max(1, r.ph - SEAM);
                mark(mask, canvasW, canvasH, r);
                Bitmap bmp = i < sheet.frames.size()
                    ? decode(sheet.frames.get(i).jpeg, maxTileW) : null;
                if (bmp == null) {
                    /**
                     * 缺帧/解不出来：铺灰块 + 横杠（"这里本来该有画面"）。
                     * **一定留日志** —— 真机上出现过"一格灰块"，没有日志只能靠猜。
                     */
                    byte[] src = i < sheet.frames.size() ? sheet.frames.get(i).jpeg : null;
                    EyeLog.w("sheet", "第 " + (i + 1) + " 格画不出来："
                        + (src == null ? "jpeg=null" : src.length + "B（解码失败）"));
                    drawPad(c, pad, r.px, r.py, cw, ch);
                    continue;
                }
                Rect dst = new Rect(r.px, r.py, r.px + cw, r.py + ch);
                c.drawBitmap(bmp, coverSrc(bmp, dst), dst, fill);
                bmp.recycle();
                drawn += 1;
            }

            // 空洞铺灰：**扫掩码找连续未覆盖段**，不是"每行最后一格的右边 + 画布底部"。
            // 档位混排时会出现真空洞（一行里 1 档 225 高、3 档 113 高，右下角空出一块黑），
            // 旧写法一条都盖不到。
            if (mask != null) padHoles(c, pad, mask, canvasW, canvasH);

            // 编号单独一轮：后画的格子会盖掉前一格伸出来的底板
            for (int i = 0; i < n; i += 1) {
                Packer.Rect r = rects.get(i);
                if (i >= sheet.frames.size()) continue;
                // 积分假切点不是"换镜头"，只是"这一段一直在变"。给它编号会让模型
                // 以为那是独立镜头，所以图上会跳号（清单里的 k= 和时间才是准的）。
                if ("mark".equals(r.via)) continue;
                int cw = Math.max(1, r.pw - SEAM);
                int ch = Math.max(1, r.ph - SEAM);
                label.setTextSize(Math.max(14, Math.min(cw, ch) / 18f));
                String num = String.valueOf(r.index);
                float p = label.getTextSize() * 0.28f;
                float tw = label.measureText(num);
                float th = label.getTextSize();
                int left = r.px;
                int top = r.py;
                c.drawRect(left + 2, top + 2, left + tw + p * 2 + 2, top + th + p * 2 + 2, plate);
                c.drawText(num, left + p + 2, top + th + p + 2 - label.descent() * 0.2f, label);
            }

            if (drawn == 0) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
            canvas.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return out.toByteArray();
        } catch (Throwable e) {
            EyeLog.w("sheet", "拼图失败: " + e);
            return null;
        } finally {
            if (canvas != null) { try { canvas.recycle(); } catch (Throwable ignored) { } }
        }
    }

    /** 把一格（含缝）在掩码上标成已覆盖 —— 缝里透出底色，但不算"空洞"。 */
    private static void mark(boolean[] mask, int w, int h, Packer.Rect r) {
        if (mask == null) return;
        int x0 = Math.max(0, r.px);
        int x1 = Math.min(w, r.px + r.pw);
        int y0 = Math.max(0, r.py);
        int y1 = Math.min(h, r.py + r.ph);
        if (x1 <= x0) return;
        for (int y = y0; y < y1; y += 1) {
            java.util.Arrays.fill(mask, y * w + x0, y * w + x1, true);
        }
    }

    /**
     * 扫掩码铺空洞。
     *
     * 逐行找**连续未覆盖段**，把"位置和宽度完全一致"的相邻行合并成一块再铺 ——
     * 这样横竖都对得上，也不会把一个方块拆成几百次 fillRect。
     */
    private static void padHoles(Canvas c, Paint pad, boolean[] mask, int w, int h) {
        List<int[]> prev = null;
        int prevY = 0;
        for (int y = 0; y < h; y += 1) {
            List<int[]> runs = new ArrayList<>();
            int x = 0;
            while (x < w) {
                if (mask[y * w + x]) { x += 1; continue; }
                int start = x;
                while (x < w && !mask[y * w + x]) x += 1;
                if (x - start > PAD_MIN_RUN) runs.add(new int[] { start, x - start });
            }
            if (!sameRuns(prev, runs)) {
                flushRuns(c, pad, prev, prevY, y);
                prev = runs;
                prevY = y;
            }
        }
        flushRuns(c, pad, prev, prevY, h);
    }

    private static boolean sameRuns(List<int[]> a, List<int[]> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i += 1) {
            if (a.get(i)[0] != b.get(i)[0] || a.get(i)[1] != b.get(i)[1]) return false;
        }
        return true;
    }

    private static void flushRuns(Canvas c, Paint pad, List<int[]> runs, int yFrom, int yTo) {
        if (runs == null || yTo <= yFrom) return;
        for (int[] r : runs) drawPad(c, pad, r[0], yFrom, r[1], yTo - yFrom);
    }

    /** 灰底 + 居中横杠（`sheet.js::drawPadding`）。 */
    private static void drawPad(Canvas c, Paint pad, int x, int y, int w, int h) {
        pad.setColor(PAD_FILL);
        c.drawRect(x, y, x + w, y + h, pad);
        int barW = Math.max(6, Math.round(w * 0.32f));
        int barH = Math.max(2, Math.round(h * 0.03f));
        pad.setColor(PAD_GLYPH);
        c.drawRect(x + Math.round((w - barW) / 2f), y + Math.round((h - barH) / 2f),
            x + Math.round((w - barW) / 2f) + barW, y + Math.round((h - barH) / 2f) + barH, pad);
    }

    /** 居中裁切（source 跟格子同比例时就是整幅缩放，不裁）。 */
    private static Rect coverSrc(Bitmap bmp, Rect dst) {
        float srcAspect = bmp.getWidth() / (float) bmp.getHeight();
        if (srcAspect > dst.width() / (float) dst.height()) {
            int w = Math.round(bmp.getHeight() * dst.width() / (float) dst.height());
            int x = (bmp.getWidth() - w) / 2;
            return new Rect(x, 0, x + w, bmp.getHeight());
        }
        int h = Math.round(bmp.getWidth() * dst.height() / (float) dst.width());
        int y = (bmp.getHeight() - h) / 2;
        return new Rect(0, y, bmp.getWidth(), y + h);
    }

    /** 按目标格宽降采样解码：几帧一起拼时不解几张原图，省内存也省时间。 */
    private static Bitmap decode(byte[] jpg, int tileW) {
        if (jpg == null || jpg.length == 0) return null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, probe);
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= tileW) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        } catch (Throwable e) {
            return null;
        }
    }
}
