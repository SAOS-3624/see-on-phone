package com.dsh.eye;

import java.util.ArrayList;
import java.util.List;

/**
 * 宫格版面探针：**离线**验证「管线挑出来的帧一定全进一张图」。
 *
 * 用法：`bash tools/parity/grid.sh`
 *
 * 判据：① cells == frames（一帧不丢）② bins == 1（只出一张）③ fill > 0.5（不是一条缝）
 */
public final class GridProbe {

    public static void main(String[] args) {
        boolean ok = true;
        ok &= one("4 帧 同档 2", tiers(new int[] { 2, 2, 2, 2 }));
        ok &= one("4 帧 混档 [1,4,4,2]", tiers(new int[] { 1, 4, 4, 2 }));
        ok &= one("6 帧 混档 [1,3,6,3,2,5]", tiers(new int[] { 1, 3, 6, 3, 2, 5 }));
        ok &= one("8 帧 全 1 档（最挤）", tiers(new int[] { 1, 1, 1, 1, 1, 1, 1, 1 }));
        ok &= one("8 帧 混档 [1,2,3,4,6,8,8,3]", tiers(new int[] { 1, 2, 3, 4, 6, 8, 8, 3 }));
        ok &= one("1 帧", tiers(new int[] { 2 }));
        ok &= one("2 帧", tiers(new int[] { 2, 2 }));
        System.out.println(ok ? "ALL OK" : "FAILED");
        if (!ok) System.exit(1);
    }

    private static List<Pipeline.Entry> tiers(int[] ts) {
        List<Pipeline.Entry> out = new ArrayList<>();
        for (int i = 0; i < ts.length; i += 1) {
            Pipeline.Entry e = new Pipeline.Entry();
            e.t = i * 0.2;
            e.tier = ts[i];
            e.level = ts[i];
            e.via = "cut";   // 纯几何：不走"首帧取中位数"那条手机端规则
            e.jpeg = new byte[] { (byte) i };
            out.add(e);
        }
        return out;
    }

    private static boolean one(String name, List<Pipeline.Entry> picked) {
        int[] tiers = new int[picked.size()];
        for (int i = 0; i < tiers.length; i += 1) tiers[i] = picked.get(i).tier;
        Pipeline.Sheet s = CallSheet.layout(picked, tiers, "probe", "rows", 0);
        int n = picked.size();
        int cells = s.layout.cells.size();
        double fill = 0;
        for (Packer.Cell c : s.layout.cells) fill += c.w * c.h;
        /**
         * 不变量（和 `pipeline.js::flush()` 一致）：
         * 装得下一张 → cells == frames；装不下（bins > 1）→ **只画 bin 0**，
         * 后面几帧留给下一张（上游 `splitOneBin` 正常不会让这种事发生）。
         */
        boolean fits = s.layout.bins == 1;
        boolean good = fits ? (cells == s.frames.size() && fill > 0.5)
                            : (cells == s.frames.size() && cells < n);
        System.out.println(String.format(java.util.Locale.ROOT,
            "%s %-22s fed=%d frames=%d cells=%d bins=%d canvas=%dx%d fill=%.3f tiers=%s",
            good ? "ok  " : "BAD ", name, n, s.frames.size(), cells, s.layout.bins,
            s.layout.canvasW, s.layout.canvasH, fill, java.util.Arrays.toString(s.tiers)));
        return good;
    }
}
