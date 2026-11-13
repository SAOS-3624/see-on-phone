package com.dsh.eye;

import java.util.List;
import java.util.Locale;

/**
 * 一次性探针：**同一条帧流**，看 `Pipeline` 自己出的 `Sheet.tiers`（不是我们重算的那种）。
 *
 * 目的：验证"自适应缩放（有大有小）"到底由谁决定 —— 结论写进交接文档。
 */
public final class TierProbe {

    private static final int W = 240;
    private static final int H = 320;

    /** 平移里夹两次整体换场景（和 StreamProbe 的素材同构）。 */
    private static List<byte[]> cuts(int n) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        for (int k = 0; k < n; k += 1) {
            int phase = k < n / 3 ? 0 : (k < 2 * n / 3 ? 1 : 2);
            byte[] f = base();
            int cx = 20 + (k % (n / 3)) * 3;
            if (phase == 0) box(f, cx, 90, 70, 70, 240);
            else if (phase == 1) box(f, 60, 40 + (k % 30) * 2, 90, 90, 60);
            else {
                for (int i = 0; i < f.length; i += 3) { f[i] = (byte) 200; f[i+1] = (byte) 30; f[i+2] = (byte) 90; }
                box(f, 120, 160, 60, 60, 250);
            }
            out.add(f);
        }
        return out;
    }

    private static byte[] base() {
        byte[] f = new byte[W * H * 3];
        for (int i = 0; i < f.length; i += 3) { f[i] = (byte) 35; f[i+1] = (byte) 38; f[i+2] = (byte) 42; }
        return f;
    }

    private static void box(byte[] f, int x0, int y0, int w, int h, int v) {
        for (int y = y0; y < Math.min(H, y0 + h); y += 1) {
            for (int x = Math.max(0, x0); x < Math.min(W, x0 + w); x += 1) {
                int i = (y * W + x) * 3;
                f[i] = (byte) v; f[i+1] = (byte) v; f[i+2] = (byte) v;
            }
        }
    }

    public static void main(String[] args) {
        int n = 10 * CallCapture.FPS;
        List<byte[]> stream = cuts(n);
        for (String mode : new String[] { "density", "weight" }) {
            for (String strat : new String[] { "standard", "native" }) {
                Pipeline.Opts o = new Pipeline.Opts();
                o.width = W;
                o.height = H;
                o.fps = CallCapture.FPS;
                o.timeNormalizeFrames = true;
                o.cutAbs = 7;
                o.floorGapK = 1.2;
                o.floorMin = 2;
                o.floorMax = 20;
                o.tierMode = mode;
                o.strategy = strat;
                o.queueDepth = 0;
                o.maxFrames = 4;
                o.silentFloorSec = 2.5;

                Pipeline p = new Pipeline(o);
                for (int i = 0; i < stream.size(); i += 1) {
                    p.push(i / (double) CallCapture.FPS, stream.get(i).clone(), new byte[] { 1 });
                }
                Pipeline.Sheet s;
                synchronized (p) {
                    p.finish();
                    s = p.take();
                }
                if (s == null) { System.out.println(mode + "+" + strat + " → 没有箱"); continue; }
                StringBuilder t = new StringBuilder();
                for (int k : s.tiers) t.append(k).append(' ');
                StringBuilder via = new StringBuilder();
                for (Pipeline.Entry e : s.frames) via.append(e.via).append(' ');
                System.out.println(String.format(Locale.ROOT,
                    "%-8s %-9s frames=%d canvas=%dx%d tiers=[%s] via=[%s]",
                    mode, strat, s.frames.size(), s.layout.canvasW, s.layout.canvasH,
                    t.toString().trim(), via.toString().trim()));
            }
        }
    }
}
