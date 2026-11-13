package com.dsh.eye;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Camera2 采集：出一串 JPEG。
 *
 * 为什么不照抄 IP Camera 的架构：那边是 Kotlin + Gradle + 一堆依赖，编一次要 1GB SDK。
 * 这里只用 framework 的原生 API，纯 javac 就能编出 100KB 的包。
 *
 * 三个关键取舍：
 *  1. **硬件 JPEG**（ImageReader + ImageFormat.JPEG）：不自己写编码器，省 CPU 也省体积。
 *  2. **像素级旋转放在这儿**：sensor 成像相对手机自然方向是歪的（nova 11 = 90°）。
 *     插件里的 jpeg-js 不认 EXIF，所以必须真的把像素转过来，否则画面横躺、
 *     cover 缩放还要横向裁掉 44%。旋转只在小分辨率上做（默认 640x480），
 *     解码+旋转+压缩 ≈ 18ms/帧，15fps 下占不到三成单核。
 *  3. **rotate 按摄像头分别记**：`?rotate=90` 是插件给后摄下发的（配置项默认 90）。
 *     如果切到前摄还照搬 90 就歪了，所以前摄默认走「自动」（sensorOrientation），
 *     只有显式给前摄下发过 rotate 才覆盖。
 */
final class CameraEngine {

    interface Sink {
        void onJpeg(byte[] jpg, int w, int h, int rotate);
        /** 已经转好向的那张位图，所有权转移给宿主（预览直接用，不必再解一次码）。 */
        void onPreviewBitmap(android.graphics.Bitmap bmp);
        void onEngineState(String text);
    }

    private static final String TAG = "DshEyeCam";

    /** 三档目标长边。源都是 4:3 横向，转 90° 之后正好 3:4 竖构图 = 插件要的 336x448。 */
    private static final int LONG_LOW = 640;
    private static final int LONG_MED = 1024;
    private static final int LONG_HIGH = 1600;

    private final Context app;
    private final Sink sink;

    private HandlerThread thread;
    private Handler h;
    private CameraManager mgr;

    private CameraDevice dev;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Surface previewSurface;
    /** 预览 Surface 是否真的进了当前会话 —— 界面靠这个判断"还要不要再挂一次" */
    private volatile boolean previewInSession;

    private String camId = "0";
    private boolean front;
    private int sensorOrientation = 90;
    private boolean hasFlash;
    private String res = "low";
    /** [0]=后摄 [1]=前摄；-1 = 自动（按 sensorOrientation 算） */
    private final int[] rotHint = { -1, -1 };
    private boolean torch;
    /**
     * 默认 15。别指望改成 12 省电：nova 11 只给 [15,15]/[30,30] 这类**固定档**，
     * 请求 12 会落到 15（实测 14.9fps）。真要更省只能降分辨率或减帧率上限。
     */
    private int fps = 15;

    private volatile boolean wantRun;
    private volatile int frames;
    private volatile long lastFrameAt;
    private volatile int outW;
    private volatile int outH;
    private volatile int outRot;
    /** 相机原生输出尺寸（未旋转），预览 Surface 的缓冲区要按这个设 */
    /** 最近一帧（已转好向的 JPEG），直连模型时直接发它，不必再等一次抓拍。 */
    private volatile byte[] lastJpeg;
    private volatile int nativeW;
    private volatile int nativeH;
    private volatile String error;
    private long lastRetryAt;

    private final BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
    private final ByteArrayOutputStream encodeBuf = new ByteArrayOutputStream(96 * 1024);

    CameraEngine(Context ctx, Sink sink) {
        this.app = ctx.getApplicationContext();
        this.sink = sink;
        // RGB_565：面板上要的是"看得清台阶"，不是相册画质。内存减半、旋转更快。
        decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;
    }

    // ---------------------------------------------------------------- 对外

    void start() {
        if (wantRun) return;
        wantRun = true;
        thread = new HandlerThread("dsh-eye-cam");
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(new Runnable() { public void run() { open(); } });
    }

    void stop() {
        wantRun = false;
        final Handler hh = h;
        if (hh != null) {
            hh.post(new Runnable() { public void run() { close(); } });
            hh.postDelayed(new Runnable() {
                public void run() {
                    if (thread != null) { thread.quitSafely(); thread = null; }
                }
            }, 300);
        }
        h = null;
    }

    void flip() {
        front = !front;
        reopen("切换摄像头");
    }

    /**
     * 按设置页的值对齐前后摄。和 `flip()` 的区别：**值没变就什么都不做** ——
     * 设置页每次点任意一行都会走到这里，不能每点一下都重开一次相机。
     */
    void setFront(boolean want) {
        if (want != front) { front = want; reopen("切换摄像头"); }
    }

    void setTorch(boolean on) {
        torch = on;
        applyRepeating();
    }

    void setResolution(String r) {
        if (r == null) return;
        String v = r.toLowerCase();
        if (!v.equals(res)) { res = v; reopen("切换分辨率"); }
    }

    /** deg < 0 表示恢复自动。 */
    void setRotate(int deg) {
        int idx = front ? 1 : 0;
        rotHint[idx] = deg < 0 ? -1 : ((deg % 360) + 360) % 360;
    }

    void setFps(int v) {
        if (v >= 1 && v <= 30 && v != fps) { fps = v; applyRepeating(); }
    }

    void setPreview(Surface s) {
        // 幂等：界面每 800ms 会来问一次"挂上了吗"，挂上了就别再重建会话。
        // s == null（视图销毁）必须放行，否则预览再也摘不掉。
        if (s != null && s == previewSurface && previewInSession) return;
        previewSurface = s;
        previewInSession = false;
        Handler hh = h;
        if (hh != null) {
            hh.post(new Runnable() {
                public void run() { if (dev != null) createSession(); }
            });
        }
    }

    boolean isFront() { return front; }
    boolean isTorch() { return torch; }
    String resolutionName() { return res; }
    int frameCount() { return frames; }
    long lastFrameAt() { return lastFrameAt; }
    byte[] lastJpeg() { return lastJpeg; }
    int outWidth() { return outW; }
    int outHeight() { return outH; }
    int nativeWidth() { return nativeW; }
    int nativeHeight() { return nativeH; }
    int rotateDegree() { return rotationDegrees(); }
    int outRotate() { return outRot; }
    String lastError() { return error; }

    // ---------------------------------------------------------------- 打开 / 关闭

    private void reopen(String why) {
        Handler hh = h;
        if (hh == null) return;
        hh.post(new Runnable() {
            public void run() { if (wantRun) { close(); open(); } }
        });
        Log.i(TAG, "reopen: " + why);
    }

    private void open() {
        if (!wantRun) return;
        close();
        try {
            if (mgr == null) mgr = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
            camId = pickCameraId(front);
            CameraCharacteristics cc = mgr.getCameraCharacteristics(camId);
            Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so.intValue();
            Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flash != null && flash.booleanValue();

            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map == null ? null : map.getOutputSizes(ImageFormat.JPEG));

            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(onImage, h);
            outW = size.getWidth();
            outH = size.getHeight();
            nativeW = outW;
            nativeH = outH;
            error = null;
            state("打开摄像头 " + camId + " @ " + outW + "x" + outH);
            mgr.openCamera(camId, deviceCb, h);
        } catch (Throwable e) {
            fail("打不开摄像头：" + e);
            retryLater();
        }
    }

    private void close() {
        try { if (session != null) { session.close(); session = null; } } catch (Throwable ignored) { }
        try { if (dev != null) { dev.close(); dev = null; } } catch (Throwable ignored) { }
        try { if (reader != null) { reader.close(); reader = null; } } catch (Throwable ignored) { }
    }

    private void retryLater() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRetryAt < 1000) return;
        lastRetryAt = now;
        Handler hh = h;
        if (hh != null) hh.postDelayed(new Runnable() { public void run() { open(); } }, 1200);
    }

    private String pickCameraId(boolean wantFront) throws Exception {
        String fallback = null;
        for (String id : mgr.getCameraIdList()) {
            Integer facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing == null) continue;
            boolean isFront = facing.intValue() == CameraCharacteristics.LENS_FACING_FRONT;
            if (isFront == wantFront) return id;
            if (fallback == null) fallback = id;
        }
        return fallback == null ? "0" : fallback;
    }

    /**
     * 挑一档源尺寸。不是"越小越好"：插件最终要 336x448，
     * 源短边低于 448 就得放大，放大比缩小更糊。
     */
    private Size pickSize(Size[] all) {
        int target = LONG_LOW;
        if ("medium".equals(res)) target = LONG_MED;
        else if ("high".equals(res)) target = LONG_HIGH;
        if (all == null || all.length == 0) return new Size(640, 480);

        Size best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Size s : all) {
            int l = Math.max(s.getWidth(), s.getHeight());
            int sh = Math.min(s.getWidth(), s.getHeight());
            if (sh < 240 || l > 4096) continue;
            double ar = (double) l / sh;
            // 4:3 不加罚分，其它比例罚 400 —— 宁可长边偏一点，也别要 16:9 被裁
            int score = Math.abs(l - target) + (Math.abs(ar - 4.0 / 3.0) < 0.06 ? 0 : 400);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best == null ? all[all.length - 1] : best;
    }

    // ---------------------------------------------------------------- 会话

    private final CameraDevice.StateCallback deviceCb = new CameraDevice.StateCallback() {
        public void onOpened(CameraDevice camera) {
            dev = camera;
            createSession();
        }
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            if (dev == camera) dev = null;
            fail("摄像头被抢占（onDisconnected）");
            retryLater();
        }
        public void onError(CameraDevice camera, int code) {
            camera.close();
            if (dev == camera) dev = null;
            fail("摄像头出错 code=" + code);
            retryLater();
        }
    };

    private void createSession() {
        if (dev == null || reader == null) return;
        try { if (session != null) { session.close(); session = null; } } catch (Throwable ignored) { }

        List<Surface> outs = new ArrayList<Surface>();
        outs.add(reader.getSurface());
        Surface pv = previewSurface;
        if (pv != null && pv.isValid()) outs.add(pv);
        previewInSession = outs.size() > 1;

        try {
            dev.createCaptureSession(outs, sessionCb, h);
        } catch (Throwable e) {
            // 有些机型对非常规比例的预览 Surface 会直接失败 —— 退回"只出流、不预览"
            fail("建会话失败，退回无预览：" + e);
            previewSurface = null;
            previewInSession = false;
            try { dev.createCaptureSession(outs.subList(0, 1), sessionCb, h); }
            catch (Throwable e2) { fail("建会话失败：" + e2); retryLater(); }
        }
    }

    private final CameraCaptureSession.StateCallback sessionCb = new CameraCaptureSession.StateCallback() {
        public void onConfigured(CameraCaptureSession s) {
            session = s;
            applyRepeating();
            error = null;
            state("采集中 " + outW + "x" + outH + " @" + fps + "fps");
        }
        public void onConfigureFailed(CameraCaptureSession s) {
            fail("会话配置失败");
            retryLater();
        }
    };

    private void applyRepeating() {
        if (session == null || dev == null) return;
        try {
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            Surface pv = previewSurface;
            if (pv != null && pv.isValid()) b.addTarget(pv);
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            // 旋转由我们自己转像素，EXIF 保持"正常"，免得下游二次旋转
            b.set(CaptureRequest.JPEG_ORIENTATION, Integer.valueOf(0));
            b.set(CaptureRequest.JPEG_QUALITY, Byte.valueOf((byte) 72));
            try {
                Range<Integer> range = pickFpsRange();
                if (range != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
            } catch (Throwable ignored) { }
            if (torch && hasFlash) {
                b.set(CaptureRequest.FLASH_MODE, Integer.valueOf(CaptureRequest.FLASH_MODE_TORCH));
            } else {
                b.set(CaptureRequest.FLASH_MODE, Integer.valueOf(CaptureRequest.FLASH_MODE_OFF));
            }
            session.setRepeatingRequest(b.build(), null, h);
        } catch (Throwable e) {
            fail("下发采集请求失败：" + e);
        }
    }

    /**
     * 挑一个帧率范围。
     *
     * 旧逻辑**只在"上下限相同"的固定档里挑**，在很多机型（OPPO/ColorOS 尤其）会挑空：
     * 它们可能只报 `[15,30] [10,30]` 这种变档。挑空 = 根本没设置
     * `CONTROL_AE_TARGET_FPS_RANGE` = 相机用自己的默认值，弱光下常常就是 10fps。
     *
     * 现在的优先级（**宁可高一点，不要低**）：
     *   1. 正好等于目标帧率的固定档
     *   2. **下限 ≥ 目标的固定档里最接近的**（要 12 → 给 [15,15]，
     *      而不是给 [10,15] —— 后者 AE 会在弱光下掉到 10，正是用户抱怨的那个现象）
     *   3. 包住目标、区间最窄的变档
     *   4. 上限最接近目标的
     */
    private Range<Integer> pickFpsRange() {
        if (mgr == null) return null;
        try {
            CameraCharacteristics cc = mgr.getCameraCharacteristics(camId);
            Range<Integer>[] all = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (all == null || all.length == 0) {
                EyeLog.w("cam", "这台机器不报 fps 档位，只能用系统默认");
                return null;
            }
            Range<Integer> exact = null, fixedUp = null, contain = null, above = null;
            int upGap = Integer.MAX_VALUE, containSpan = Integer.MAX_VALUE, aboveGap = Integer.MAX_VALUE;
            for (Range<Integer> r : all) {
                if (r == null) continue;
                int lo = r.getLower().intValue();
                int hi = r.getUpper().intValue();
                if (lo == hi) {
                    if (hi == fps) { exact = r; break; }
                    if (hi > fps && hi - fps < upGap) { upGap = hi - fps; fixedUp = r; }
                    continue;
                }
                if (lo <= fps && fps <= hi && hi - lo < containSpan) {
                    containSpan = hi - lo;
                    contain = r;
                } else if (hi >= fps && hi - fps < aboveGap) {
                    aboveGap = hi - fps;
                    above = r;
                }
            }
            Range<Integer> pick = exact != null ? exact
                : (fixedUp != null ? fixedUp : (contain != null ? contain : above));
            EyeLog.w("cam", "fps 要 " + fps + " → 选 " + pick + "（可选 " + java.util.Arrays.toString(all) + "）");
            return pick;
        } catch (Throwable e) {
            EyeLog.w("cam", "读 fps 档位失败: " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- 取帧

    private final ImageReader.OnImageAvailableListener onImage = new ImageReader.OnImageAvailableListener() {
        public void onImageAvailable(ImageReader r) {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                byte[] raw;
                int iw, ih;
                try {
                    java.nio.ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    raw = new byte[buf.remaining()];
                    buf.get(raw);
                    iw = img.getWidth();
                    ih = img.getHeight();
                } finally {
                    img.close();
                }
                int deg = rotationDegrees();
                byte[] out = transform(raw, deg);
                if (deg == 90 || deg == 270) { outW = ih; outH = iw; }
                else { outW = iw; outH = ih; }
                outRot = deg;
                frames += 1;
                lastFrameAt = System.currentTimeMillis();
                lastJpeg = out;
                sink.onJpeg(out, outW, outH, deg);
            } catch (Throwable e) {
                fail("取帧失败：" + e);
            }
        }
    };

    private int displayRotation() {
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            return d.getRotation() * 90 % 360;
        } catch (Throwable e) {
            return 0;
        }
    }

    int rotationDegrees() {
        int hint = rotHint[front ? 1 : 0];
        if (hint >= 0) return hint;
        int display = displayRotation();
        int deg = front
            ? (sensorOrientation - display + 360) % 360
            : (sensorOrientation + display) % 360;
        return deg;
    }

    /**
     * 解码 → 旋转 → 重编码。转好的那张位图**顺手交给预览**（所有权转移），
     * 这样屏幕上看到的和插件拿到的是同一帧，不可能出现"预览转歪 / 视场角不对"。
     */
    private byte[] transform(byte[] jpg, int deg) {
        Bitmap src = null;
        Bitmap rot = null;
        boolean handed = false;
        try {
            src = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, decodeOpts);
            if (src == null) return jpg;
            if (deg == 0) {
                rot = src;
                src = null;
            } else {
                Matrix m = new Matrix();
                m.postRotate(deg);
                rot = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, false);
            }
            encodeBuf.reset();
            rot.compress(Bitmap.CompressFormat.JPEG, 72, encodeBuf);
            byte[] out = encodeBuf.toByteArray();
            // 交出去；宿主负责 recycle。只有真的交成功了才不回收。
            try {
                sink.onPreviewBitmap(rot);
                handed = true;
            } catch (Throwable ignored) { }
            return out;
        } catch (Throwable e) {
            return jpg;
        } finally {
            if (src != null) { try { src.recycle(); } catch (Throwable ignored) { } }
            if (rot != null && !handed) { try { rot.recycle(); } catch (Throwable ignored) { } }
        }
    }

    private void fail(String msg) {
        error = msg;
        Log.w(TAG, msg);
        state(msg);
    }

    private void state(String s) {
        try { sink.onEngineState(s); } catch (Throwable ignored) { }
    }
}
