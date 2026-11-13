package com.dsh.eye;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

/**
 * 「看一眼」的执行者：**开相机 → 喂几秒 → 封箱 → 拼一张宫格 → 关相机**。
 *
 * ## 和 0.13.x 的 `Watcher` 是什么关系
 *
 * 同一个 `Pipeline`（切点检测 / 档位 / 排布 / 拼图，和桌面逐字节对拍过的那一套），
 * 但**不再常驻**：以前是"点开始看 → 一直跑 → 攒满一箱就发模型"，现在是
 * "模型要图 → 现场抓 2~4 秒 → 给一张 → 相机可以关了"。
 *
 * 删掉的东西（都跟着"连续看"这个产品形态走的）：
 *   播报节奏、攒批（`wakeBatch`）、待发队列、预览重绘（8fps）、待发条带、轮数上限、空闲自动停。
 *
 * ## 为什么只有一个线程
 *
 * 以前是三层（采集 / 打分 / 网络），因为连续看要"慢一帧就丢一帧"地追着跑。
 * 抓 2.5 秒不需要那套：调用方（{@link Asker} 那条线程）自己循环取样就够了 ——
 * 少两层线程，也少三种"谁先关谁"的竞态。
 *
 * ## 相机是借来的
 *
 * 这里**不开关相机**，只要求"现在有帧"。开关由 {@link EyeService} 管（它才知道
 * 有没有 MJPEG 客户端也在等着看）。这样 `/frame.jpg` 和 `look` 不会互相把相机
 * 关掉又打开。
 */
final class Looker {

    /** 打分分辨率：和 `Pipeline` 的对拍基线一致（桌面版是 798×450 的等比缩放，手机固定这个）。 */
    private static final int SCORE_W = 240;
    private static final int SCORE_H = 320;

    /** 等第一帧最多多久（相机冷启动 0.5~1.5 秒，留一倍余量）。 */
    private static final long FIRST_FRAME_MS = 2800;

    private final Config cfg;

    /** 采集线程写、抓取线程读：永远是"最新那一帧"。 */
    private volatile byte[] slot;
    private volatile long slotAt;

    /** 复用缓冲：每帧新分配 230KB 会把 GC 逼疯。 */
    private byte[] scoreBuf;
    private int[] pixelBuf;

    Looker(Config cfg) { this.cfg = cfg; }

    /** 摄像头每出一帧就喂进来（**在采集线程上**，只做一次赋值）。 */
    void onFrame(byte[] jpg) {
        if (jpg == null || jpg.length == 0) return;
        slot = jpg;
        slotAt = SystemClock.elapsedRealtime();
    }

    /**
     * 抓一张宫格。**阻塞**，必须在后台线程调。
     *
     * @param seconds 喂几秒（1~5）
     * @return 拼好的 JPEG + 元信息；相机一直没出帧 → null
     */
    Tools.Look grab(double seconds) {
        long callAt = SystemClock.elapsedRealtime();
        // ① 等第一帧（相机是冷的，0.5~1.5 秒）
        while (SystemClock.elapsedRealtime() - callAt < FIRST_FRAME_MS) {
            if (slot != null && slotAt >= callAt - 200) break;
            sleep(20);
        }
        if (slot == null) {
            EyeLog.w("look", "等 " + FIRST_FRAME_MS + "ms 还没有帧，放弃");
            return null;
        }

        // ② 喂 seconds 秒
        Pipeline.Opts o = opts();
        Pipeline pipe = new Pipeline(o);
        long t0 = SystemClock.elapsedRealtime();
        long end = t0 + (long) (Math.max(1, Math.min(5, seconds)) * 1000);
        byte[] last = null;
        int fed = 0;
        while (SystemClock.elapsedRealtime() < end) {
            byte[] jpg = slot;
            if (jpg != null && jpg != last) {          // 同一帧别重复喂（静止画面时省一半 CPU）
                last = jpg;
                byte[] rgb = toRgb24(jpg);
                if (rgb != null) {
                    synchronized (pipe) {
                        pipe.push((SystemClock.elapsedRealtime() - t0) / 1000.0, rgb, jpg);
                    }
                    fed += 1;
                }
            }
            sleep(25);
        }

        // ③ 封箱
        double now = (SystemClock.elapsedRealtime() - t0) / 1000.0;
        Pipeline.Sheet s;
        int[] tiers = null;
        synchronized (pipe) {
            s = pipe.flushNow(now, "time");
            // **必须取走**：flushNow 会把这张推进交付队列，不取走就等于白拼（还会被下一次 take 当一轮）
            pipe.take();
            if (s != null) tiers = pipe.tiersOf(s.frames);
        }
        if (s == null || s.frames.isEmpty()) {
            // 一帧都没留下（画面从头到尾一样）：退回"当前帧"，至少让它看见东西
            byte[] jpg = last != null ? last : slot;
            if (jpg == null) return null;
            Tools.Look l = new Tools.Look();
            l.jpeg = jpg;
            l.frames = 1;
            l.tokens = Tokens.imageTokens(480, 640);
            l.why = "still";
            EyeLog.w("look", "管线没留帧，退回单帧 " + jpg.length + "B");
            return l;
        }

        // 和通话页同一套版面（主次拉开 + 小格抬亮）：管线给的档位 → 手机版面
        Pipeline.Sheet phone = CallSheet.layout(s.frames, tiers, s.why, o.packMode, o.maxCrop);
        if (phone != null) s = phone;
        byte[] sheet = Sheet.compose(s);
        if (sheet == null) return null;
        Tools.Look l = new Tools.Look();
        l.jpeg = sheet;
        l.frames = s.frames.size();
        l.tokens = s.layout == null ? Tokens.SHEET_TOKENS : s.layout.tokens();
        l.why = s.why == null ? "time" : s.why;
        EyeLog.w("look", "grab " + String.format(java.util.Locale.ROOT, "%.1fs", seconds)
            + " fed=" + fed + " frames=" + l.frames + " why=" + l.why
            + " tokens=" + l.tokens + " jpeg=" + sheet.length);
        return l;
    }

    /**
     * 一张宫格 → JPEG（**发给模型的就是这一张**）。
     *
     * 挑帧/档位/排布全在 `Pipeline` + `CallStream`，这一层只剩"画"。
     */
    Tools.Look lookOf(Pipeline.Sheet s) {
        if (s == null) return null;
        byte[] jpg = Sheet.compose(s);
        if (jpg == null) return null;
        Tools.Look l = new Tools.Look();
        l.jpeg = jpg;
        l.frames = s.frames.size();
        l.tokens = s.layout == null ? Tokens.SHEET_TOKENS : s.layout.tokens();
        l.why = s.why == null ? "call" : s.why;
        // 每 30 秒落一份**整张**（不是缩略图）到 Download：真机排障直接 pull 它看编号/版面
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastSheetDump > 30_000) {
            lastSheetDump = nowMs;
            try { Files.write(new java.io.File("/sdcard/Download/DSH-Go/sheet.jpg"), jpg, false); }
            catch (Throwable ignored) { }
        }
        EyeLog.w("look", "sheet cells=" + s.frames.size()
            + String.format(java.util.Locale.ROOT, " span=%.1fs", s.to - s.from)
            + " why=" + l.why + " canvas=" + s.layout.canvasW + "x" + s.layout.canvasH
            + " tiers=" + java.util.Arrays.toString(s.tiers)
            + " tokens=" + l.tokens + " jpeg=" + jpg.length);
        return l;
    }

    /** 缩略图（通话页左下角预览；宽 260，够看清版面）。 */
    byte[] thumb(Pipeline.Sheet s) {
        if (s == null) return null;
        byte[] jpg = Sheet.compose(s, 260, 70);
        // 每 30 秒落一份到 Download：真机上说"版面不对"时，直接 pull 这张来看，
        // 不用装调试环境（`/sdcard/Download/DSH-Go/preview.jpg`）
        long now = System.currentTimeMillis();
        if (jpg != null && now - lastPreviewDump > 30_000) {
            lastPreviewDump = now;
            try { Files.write(new java.io.File("/sdcard/Download/DSH-Go/preview.jpg"), jpg, false); }
            catch (Throwable ignored) { }
        }
        return jpg;
    }

    private long lastPreviewDump;
    private long lastSheetDump;

    /**
     * 建管线参数。**优先级：tuning.json > 设置页 > `Pipeline.Opts` 默认值**，
     * 和 0.13.x 完全一致（`tune` 改的就是前两级里的值）。
     */
    Pipeline.Opts opts() {
        Pipeline.Opts o = new Pipeline.Opts();
        o.width = SCORE_W;
        o.height = SCORE_H;
        double measured = 0;
        EyeService svc = EyeService.peek();
        if (svc != null) measured = svc.measuredFps();
        o.fps = Math.max(1, (int) Math.round(measured >= 4 ? measured : 12));
        // 三处"按帧的窗口"（smoothFrames / BAR_WINDOW / cutBgWindow）按 fps 折算，
        // 保持**秒数**恒定 —— 电脑版固定 12fps，手机换帧率就不成立了。
        o.timeNormalizeFrames = true;

        Tuning tuning = Tuning.load();
        String pace = tuning.str("defaultPace", cfg.pace());
        applyPace(o, pace);
        o.strategy = tuning.str("tierStyle", cfg.tierStyle());
        /**
         * **档位判据 = see-now 的 `tierMode`**，默认 **`density`**，和 `pipeline.js:290` 一致。
         *
         * | 值 | 判据 | 电脑版出处 |
         * |---|---|---|
         * | `density`（默认） | 过去 1 秒**平均每秒几帧**（邻域密度，走 EMA；`Tiers.Tracker`） | `pipeline.js:290` |
         * | `weight` | 出图时按这一帧的**变化量**切面积（`area_i ∝ score_i`） | `pipeline.js:897` |
         *
         * 两者都是"尺寸 = 重要性"的近似，`weight` 才有"同一张图里大小对比"。
         *
         * ⚠️ **默认必须是 `density`**：那是 see-now 的原生默认。手机端擅自把默认换成
         * `weight`，就等于和电脑版对不上（2026-11-14 干过一次，用户当场指出来）。
         * `weight` 只能由 `tuning.json` 显式指定（模型 `tune` / 真机排障）。
         *
         * ⚠️ 也别把 `density` 叫"等分" —— 它判的是**密度**，匀速采样下才恰好同档。
         */
        o.tierMode = tuning.str("tierMode", "density");
        o.queueDepth = 1;
        tuning.applyTo(o);
        if (cfg.maxFrames() > 0) o.maxFrames = cfg.maxFrames();
        return o;
    }

    /** 节奏档 → 管线参数。数值照抄 `dsh-see-now/src/runner.js` 的 PACES。 */
    private static void applyPace(Pipeline.Opts o, String pace) {
        if ("calm".equals(pace)) {
            o.cutAbs = 20;
            o.cutIsolatedAbs = 0;
            o.cutMcAbs = 0;
            o.floorGapK = 3; o.floorMin = 3; o.floorMax = 30;
        } else if ("dense".equals(pace)) {
            o.cutAbs = 7;
            o.floorGapK = 1.2; o.floorMin = 2; o.floorMax = 20;
        } else if ("game-economic".equals(pace)) {
            o.cutAbs = 80;
            o.integralSeconds = 30;
            o.maxFrames = 8;
            o.maxWaitSec = 60;
            o.silentFloorSec = 40;
            o.cutSpike = 1.5;
            o.cutSpikeAbs = 30;
            o.cutIsolatedAbs = 0;
            o.cutJumpAbs = 0;
            o.cutMcAbs = 0;
            o.floorGapK = 2; o.floorMin = 2; o.floorMax = 20;
        } else {
            o.cutAbs = 12;
            o.floorGapK = 2; o.floorMin = 2; o.floorMax = 20;
        }
    }

    /**
     * JPEG → 240×320 rgb24（`Pipeline` 的打分器吃这个）。
     *
     * **必须用 ARGB_8888**：RGB_565 只有 5/6/5 位色深，而打分是 HSV 三通道差，
     * 色深一降，静态画面的分数就变成一格一格地跳。
     */
    /**
     * JPEG → 240×320 rgb24。
     *
     * ⚠️ **`synchronized`**：实时预览（预览线程）和发送（对话线程）会同时调它，
     * 而里面 `scoreBuf/pixelBuf` 是复用的 —— 不串行化就会互踩，出来是花屏。
     */
    synchronized byte[] toRgb24(byte[] jpg) {
        Bitmap b = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, probe);
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= SCORE_W) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
            if (b == null) return null;
            Bitmap src = b;
            if (b.getWidth() != SCORE_W || b.getHeight() != SCORE_H) {
                scaled = Bitmap.createScaledBitmap(b, SCORE_W, SCORE_H, true);
                src = scaled;
            }
            if (pixelBuf == null) pixelBuf = new int[SCORE_W * SCORE_H];
            if (scoreBuf == null) scoreBuf = new byte[SCORE_W * SCORE_H * 3];
            int[] px = pixelBuf;
            src.getPixels(px, 0, SCORE_W, 0, 0, SCORE_W, SCORE_H);
            byte[] rgb = scoreBuf;
            for (int i = 0, j = 0; i < px.length; i += 1, j += 3) {
                int p = px[i];
                rgb[j] = (byte) ((p >> 16) & 0xff);
                rgb[j + 1] = (byte) ((p >> 8) & 0xff);
                rgb[j + 2] = (byte) (p & 0xff);
            }
            return rgb;
        } catch (Throwable e) {
            return null;
        } finally {
            if (scaled != null) { try { scaled.recycle(); } catch (Throwable ignored) { } }
            if (b != null) { try { b.recycle(); } catch (Throwable ignored) { } }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
