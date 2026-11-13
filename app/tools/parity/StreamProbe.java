package com.dsh.eye;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 合成帧流探针：**离线**验证"连续帧流 → 一张宫格"这条链（走的就是 App 的真实路径）。
 *
 * 用法：`bash tools/parity/stream.sh`
 *
 * 为什么要它在电脑上跑：真机上"画面动没动"完全不由我控制，而这里可以精确造出
 * 静止 / 平移 / 硬切三种素材，看**连续管线**（`CallStream`，一个会话一条）到底留了几帧、
 * 给了几档 —— 这正是用户抱怨的那两件事（"怎么拼都是四宫格" / "只有两帧"）。
 *
 * 判据：
 *   ① 静止画面一窗也要有 **≥3 格**（兜底固定成窗口/4，见 `CallStream`）；
 *   ② 主次差 **≥2 档**（差 1 档会被打包器靠成一样大 ⇒ 又成等大宫格）；
 *   ③ 不管哪种，`cells == frames` 且只出**一张**。
 *
 * 帧数**没有上限可测**：几帧是 `checkFlush()` 判的（碎格 `flushGain` / 时间 `maxWaitSec`），
 * `maxFrames: 40` 只是保险 —— 以前这里那个"不超宫格张数"的断言跟着设置项一起废了。
 */
public final class StreamProbe {

    private static final int W = 240;
    private static final int H = 320;
    private static final int FPS = CallCapture.FPS;
    /** 假 JPEG：探针不需要真图，`push` 只要求非空。 */
    private static final byte[] JPG = { 1 };
    private static int failed;

    public static void main(String[] args) {
        Pipeline.Opts opts = new Pipeline.Opts();
        opts.width = W;
        opts.height = H;
        opts.timeNormalizeFrames = true;
        // 灵敏档（用户当前就是这一档）
        opts.cutAbs = 7;
        opts.floorGapK = 1.2;
        opts.floorMin = 2;
        opts.floorMax = 20;
        // ★ App 现在的默认口径（`Looker.opts()`：设置页默认 standard + `density`）
        opts.tierMode = "density";
        opts.strategy = "standard";

        double win = 8;   // = 灵敏档的发送间隔
        scene("静止（桌面上的手机，画面几乎不动）", still(25 * FPS), opts, win, 1, false);
        scene("平移（拿着手机慢慢转）", pan(25 * FPS), opts, win, 1, false);
        // 硬切：`density` 下匀速采样本来就同档 —— "主次差"只在 `weight` 那一支才有意义（见下）。
        scene("硬切两次（场景整体换了）", cuts(25 * FPS), opts, win, 2, false);

        // 对照：同一段素材，看"密度（density，默认）"和"自适应（weight）"的档位差
        Pipeline.Opts weightOpts = opts.copy();
        weightOpts.tierMode = "weight";
        System.out.println("== 对照（硬切素材）");
        scene("density + standard（现在的默认）", cuts(25 * FPS), opts, win, 2, false);
        scene("weight  + standard（对照）", cuts(25 * FPS), weightOpts, win, 2, true);

        System.out.println("== 攒批（wakeBatch）");
        batch(opts, 1);
        batch(opts, 3);

        System.out.println(failed == 0 ? "ALL OK" : (failed + " FAILED"));
        if (failed != 0) System.exit(1);
    }

    /**
     * 攒批三件事（see-now `wakeBatch` 的全部语义，`runner.js:418` / `loop.js:477`）：
     * ① 队列最多压 `wakeBatch` 张（压不住就等于没攒）；
     * ② **攒够才叫**：`pending < batch` 时 `ready()` 必须 false（否则第 1 张就叫）；
     * ③ 一次取走一批，**时间正序**（老的在前）。
     */
    private static void batch(Pipeline.Opts base, int n) {
        Pipeline.Opts o = base.copy();
        CallStream cs = new CallStream();
        cs.open(o, 2, n);                     // 窗口 2s：20 秒素材能封好几箱
        List<byte[]> rgbs = switches(20 * FPS, 8);   // 整屏换色，1.6 秒一刀 ⇒ 一箱接一箱
        boolean early = false;
        int maxPending = 0;
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < rgbs.size(); i += 1) {
            cs.push(t0 + i * 200L, rgbs.get(i), JPG);
            int p = cs.pending();
            maxPending = Math.max(maxPending, p);
            if (p < n && cs.ready()) early = true;
        }
        List<Pipeline.Sheet> got = cs.takeMany(n);
        String tag = "wakeBatch=" + n;
        System.out.println("   —— " + tag + " 队列峰值=" + maxPending + " 取走=" + got.size());
        check(tag + "：队列最多压 " + n + " 张", maxPending <= n);
        check(tag + "：不够 " + n + " 张时 ready 必须 false", !early);
        check(tag + "：一次取走 " + n + " 张", got.size() == n);
        boolean ordered = true;
        for (int i = 1; i < got.size(); i += 1) {
            if (got.get(i).from < got.get(i - 1).to) ordered = false;
        }
        check(tag + "：时间正序（老的在前）", ordered);
        check(tag + "：取完就空、也不再 ready", cs.pending() == 0 && !cs.ready());
    }

    /**
     * 一段连续帧喂进**一条**管线（= 一个通话会话），然后取它自己封好的那一箱。
     *
     * **必须走 `take()`**（自动发送那条路）：`takeNow` 是插话，会把队列清空重新截断，
     * 取到的是"此刻那几帧"，测不到管线自己的封箱节奏。
     *
     * 第 3 个参数是 `wakeBatch`（攒批）。这里**恒传 1** = 一次一箱，
     * 才能量到"管线自己那份封箱节奏"；>1 时队列会压好几箱，`take()` 只拿走最老的一箱。
     */
    static Pipeline.Sheet run(Pipeline.Opts opts, List<byte[]> rgbs, double windowSec) {
        CallStream cs = new CallStream();
        cs.open(opts.copy(), windowSec, 1);
        long base = System.currentTimeMillis();
        for (int i = 0; i < rgbs.size(); i += 1) {
            cs.push(base + i * 200L, rgbs.get(i), JPG);
        }
        Pipeline.Sheet s = cs.take();
        if (s == null) s = cs.takeNow(base + (rgbs.size() - 1) * 200L, "probe");
        return s;
    }

    private static void scene(String name, List<byte[]> rgbs, Pipeline.Opts opts, double win,
                              int minCells, boolean wantContrast) {
        Pipeline.Sheet s = run(opts, rgbs, win);
        int frames = s == null ? 0 : s.frames.size();
        int cells = s == null ? 0 : s.layout.cells.size();
        int bins = s == null ? 0 : s.layout.bins;
        StringBuilder tiers = new StringBuilder();
        if (s != null) for (int t : s.tiers) tiers.append(t).append(' ');
        StringBuilder vias = new StringBuilder();
        if (s != null) for (Pipeline.Entry e : s.frames) vias.append(e.via).append('@')
            .append(String.format(Locale.ROOT, "%.1f", e.t)).append(' ');
        System.out.println(String.format(Locale.ROOT,
            "%s\n   —— fed=%d frames=%d cells=%d bins=%d canvas=%dx%d tiers=[%s]",
            name, rgbs.size(), frames, cells, bins,
            s == null ? 0 : s.layout.canvasW, s == null ? 0 : s.layout.canvasH,
            tiers.toString().trim() + "\n   —— " + vias.toString().trim()));
        check(name + "：至少 " + minCells + " 格", cells >= minCells);
        check(name + "：只出一张", bins == 1);
        check(name + "：一帧都不丢（cells == frames）", cells == frames);
        if (wantContrast) {
            int min = 99;
            int max = 0;
            for (int t : s.tiers) { min = Math.min(min, t); max = Math.max(max, t); }
            check(name + "：主格档位 ≤2（看得清重点）", min <= 2);
            check(name + "：主次差 ≥2 档（否则又成等大宫格）", max - min >= 2);
        }
    }

    // ---------------------------------------------------------------- 素材

    /** 整屏换色，每 `every` 帧一次 —— 在切点判据眼里就是"切镜头"，一箱接一箱地封。 */
    private static List<byte[]> switches(int n, int every) {
        List<byte[]> out = new ArrayList<>();
        int[] v = { 20, 90, 160, 230 };
        for (int k = 0; k < n; k += 1) {
            int c = v[(k / every) % v.length];
            byte[] f = new byte[W * H * 3];
            for (int i = 0; i < f.length; i += 3) {
                f[i] = (byte) c;
                f[i + 1] = (byte) (c / 2);
                f[i + 2] = (byte) (255 - c);
            }
            out.add(f);
        }
        return out;
    }

    /** 静止：一块渐变 + ±1 的传感器噪声（模拟真实摄像头）。 */
    private static List<byte[]> still(int n) {
        List<byte[]> out = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int k = 0; k < n; k += 1) {
            byte[] f = new byte[W * H * 3];
            for (int y = 0; y < H; y += 1) {
                for (int x = 0; x < W; x += 1) {
                    int i = (y * W + x) * 3;
                    int v = 40 + (y * 80 / H) + (x * 30 / W);
                    f[i] = (byte) clamp(v + rnd.nextInt(3) - 1);
                    f[i + 1] = (byte) clamp(v + rnd.nextInt(3) - 1);
                    f[i + 2] = (byte) clamp(v + 10 + rnd.nextInt(3) - 1);
                }
            }
            out.add(f);
        }
        return out;
    }

    /** 平移：一个亮块每帧走 3px（拿着手机转视角）。 */
    private static List<byte[]> pan(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) {
            byte[] f = base();
            int cx = 20 + (k * 3) % (W - 80);
            box(f, cx, 100, 60, 60, 235);
            box(f, cx / 2 + 10, 220, 40, 40, 170);
            out.add(f);
        }
        return out;
    }

    /** 硬切：平移中在 1/3、2/3 处整体换场景（切镜头）。 */
    private static List<byte[]> cuts(int n) {
        List<byte[]> out = new ArrayList<>();
        for (int k = 0; k < n; k += 1) {
            int phase = k < n / 3 ? 0 : (k < 2 * n / 3 ? 1 : 2);
            byte[] f = base();
            int cx = 20 + (k % (n / 3)) * 3;
            if (phase == 0) box(f, cx, 90, 70, 70, 240);
            else if (phase == 1) box(f, 60, 40 + (k % 30) * 2, 90, 90, 60);
            else {
                for (int i = 0; i < f.length; i += 3) {
                    f[i] = (byte) 200;
                    f[i + 1] = (byte) 30;
                    f[i + 2] = (byte) 90;
                }
                box(f, 120, 160, 60, 60, 250);
            }
            out.add(f);
        }
        return out;
    }

    private static byte[] base() {
        byte[] f = new byte[W * H * 3];
        for (int i = 0; i < f.length; i += 3) {
            f[i] = (byte) 35;
            f[i + 1] = (byte) 38;
            f[i + 2] = (byte) 42;
        }
        return f;
    }

    private static void box(byte[] f, int x0, int y0, int w, int h, int v) {
        for (int y = y0; y < Math.min(H, y0 + h); y += 1) {
            for (int x = x0; x < Math.min(W, x0 + w); x += 1) {
                if (x < 0) continue;
                int i = (y * W + x) * 3;
                f[i] = (byte) v;
                f[i + 1] = (byte) v;
                f[i + 2] = (byte) v;
            }
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static void check(String what, boolean ok) {
        if (ok) {
            System.out.println("   ✓ " + what);
        } else {
            failed += 1;
            System.out.println("   ✗ " + what);
        }
    }
}
