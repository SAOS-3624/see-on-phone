package com.dsh.eye;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 拍照弹层：**全屏预览 + 快门**。
 *
 * <p>预览不用系统相机 App，也不用另写一套 Camera2：直接接 `EyeService` 的
 * `PreviewSink`（通话页用的同一条「已解码、已转正」的位图流）。好处是：
 * <ul>
 *   <li>各型号一致：取景/旋转/宽高比都由现有 `CameraEngine` 处理，不依赖厂商相机 UI；</li>
 *   <li>不打断会话：预览就在 App 里，关掉回聊天；</li>
 *   <li>快门和「拍一张」走同一条 `lookNow()`，发出去的图与预览同源。</li>
 * </ul>
 *
 * <p>相机借用是**计数**的（`camAcquire/camRelease`）：弹层开着时别人借不走，
 * 关掉后延迟释放；快门先关弹层再抓图，不会和预览抢相机。
 */
final class CameraSheet extends Dialog implements EyeService.PreviewSink {

    interface Cb {
        /** 按了快门（弹层已经关掉、相机已释放）。jpeg 为 null = 预览还没出帧。 */
        void onShot(byte[] jpeg);
    }

    private final Activity act;
    private final EyeService svc;
    private final Cb cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageView preview;
    private TextView hint;
    private TextView torchBtn;
    private Bitmap showing;
    private boolean acquired;
    private boolean released;

    CameraSheet(Activity a, EyeService s, Cb cb) {
        super(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.act = a;
        this.svc = s;
        this.cb = cb;
        setCanceledOnTouchOutside(false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setDimAmount(0.85f);
        }

        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(Color.BLACK);

        preview = new ImageView(getContext());
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部：关闭 + 标题（左右留 48dp，标题才真的居中）
        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), TopBar.statusBarHeight(act) + dp(4), dp(8), dp(4));

        TextView close = new TextView(getContext());
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(24);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismiss(); }
        });
        top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(getContext());
        title.setText("拍照");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View pad = new View(getContext());
        top.addView(pad, new LinearLayout.LayoutParams(dp(48), dp(48)));

        root.addView(top, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP));

        // 底部：提示 + 快门 + 文字
        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(0, 0, 0, dp(28));

        hint = new TextView(getContext());
        hint.setText("正在打开相机…");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), 0, dp(16), dp(12));
        bottom.addView(hint);

        FrameLayout shutter = new FrameLayout(getContext());
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);
        shutter.setBackground(ring);

        View dot = new View(getContext());
        GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(Color.WHITE);
        dot.setBackground(inner);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(56), dp(56));
        dlp.gravity = Gravity.CENTER;
        shutter.addView(dot, dlp);

        shutter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shot(); }
        });
        Anim.press(shutter);
        TextView flip = new TextView(getContext());
        flip.setText("翻转");
        styleCircle(flip, false);
        flip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { svc.flipCamera(); } catch (Throwable e) {
                    Toast.makeText(getContext(), "切不了：" + e, Toast.LENGTH_SHORT).show();
                    return;
                }
                // 切到前置通常没闪光灯：顺手关掉手电，别让它留一个"开着但没光"的状态
                try { svc.setTorch(false); } catch (Throwable ignored) { }
                paintTorch();
                ui.postDelayed(new Runnable() {
                    public void run() { paintTorch(); }
                }, 800);
            }
        });

        torchBtn = new TextView(getContext());
        torchBtn.setText("手电");
        styleCircle(torchBtn, false);
        torchBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (svc.cameraFront() || !svc.hasFlash()) {
                    Toast.makeText(getContext(), "这颗镜头没有闪光灯", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean on = !svc.torchOn();
                try { svc.setTorch(on); } catch (Throwable e) {
                    Toast.makeText(getContext(), "手电打不开：" + e, Toast.LENGTH_SHORT).show();
                    return;
                }
                paintTorch();
            }
        });

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(4), 0, 0);
        row.addView(flip, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(dp(76), dp(76));
        shlp.leftMargin = dp(22);
        shlp.rightMargin = dp(22);
        row.addView(shutter, shlp);
        row.addView(torchBtn, new LinearLayout.LayoutParams(dp(58), dp(58)));
        bottom.addView(row);

        TextView label = new TextView(getContext());
        label.setText("拍照");
        label.setTextColor(0xAAFFFFFF);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, 0);
        bottom.addView(label);

        root.addView(bottom, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM));

        setContentView(root);

        // 2.5 秒还没一帧：多半是没授权，别让它一直显示"正在打开"
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!released && showing == null) {
                    hint.setText("相机没打开（可能没给相机权限）");
                }
            }
        }, 2500);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (acquired) return;
        acquired = true;
        try {
            svc.setPreviewSink(this);
            svc.camAcquire("拍照预览");
        } catch (Throwable e) {
            hint.setText("相机打不开：" + e);
        }
        ui.postDelayed(new Runnable() {
            public void run() { paintTorch(); }
        }, 800);
    }

    /** 采集线程回调：只留最新一张，旧的在界面线程回收。 */
    @Override
    public void onPreview(final Bitmap bmp) {
        if (bmp == null) return;
        if (released) {
            try { bmp.recycle(); } catch (Throwable ignored) { }
            return;
        }
        ui.post(new Runnable() {
            public void run() {
                if (released || preview == null) {
                    try { bmp.recycle(); } catch (Throwable ignored) { }
                    return;
                }
                Bitmap old = showing;
                showing = bmp;
                preview.setImageBitmap(bmp);
                if (hint != null && hint.getVisibility() != View.GONE) hint.setVisibility(View.GONE);
                if (old != null && old != bmp) {
                    try { old.recycle(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private void shot() {
        // 先把当前预览帧压成一张 JPEG（不是 lookNow 那种多帧拼图）
        byte[] jpg = null;
        Bitmap b = showing;
        if (b != null && !b.isRecycled()) {
            try {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 90, bo);
                jpg = bo.toByteArray();
            } catch (Throwable ignored) { }
        }
        release();
        try { super.dismiss(); } catch (Throwable ignored) { }
        if (cb != null) cb.onShot(jpg);
    }

    @Override
    public void dismiss() {
        release();
        super.dismiss();
    }

    private void release() {
        if (released) return;
        released = true;
        try { svc.setTorch(false); } catch (Throwable ignored) { }
        try { svc.setPreviewSink(null); } catch (Throwable ignored) { }
        try { svc.camRelease(); } catch (Throwable ignored) { }
        if (showing != null) {
            try { showing.recycle(); } catch (Throwable ignored) { }
            showing = null;
        }
        if (preview != null) preview.setImageDrawable(null);
    }

    private void styleCircle(TextView v, boolean on) {
        v.setTextColor(Color.WHITE);
        v.setTextSize(13);
        v.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(on ? 0x33FFD54F : 0x33000000);
        g.setStroke(dp(1), on ? 0xFFFFD54F : 0x88FFFFFF);
        v.setBackground(g);
    }

    /** 前置/无闪光灯时手电置灰；开着时黄一圈。 */
    private void paintTorch() {
        if (torchBtn == null) return;
        boolean usable = !svc.cameraFront() && svc.hasFlash();
        boolean on = svc.torchOn();
        torchBtn.setText(on ? "手电开" : "手电");
        torchBtn.setAlpha(usable ? 1f : 0.35f);
        styleCircle(torchBtn, on);
    }

    private int dp(float v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }
}
