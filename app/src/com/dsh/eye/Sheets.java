package com.dsh.eye;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「从底部升起来的那类东西」：动作栏、全屏看图、长文本、保存到相册。
 *
 * 为什么不用 `AlertDialog.setItems`：那是系统灰底弹窗，在深色界面里像十年前的东西，
 * 而且**长按气泡弹菜单**是这个界面里出现频率最高的交互之一（每次长按都看到）。
 *
 * 抽成独立文件是因为 `MainActivity` 只该负责"用户点了哪一个 → 走哪条路"，
 * 长什么样、怎么弹、圆角多大，都是这一层的事。
 */
final class Sheets {

    /** 动作栏点了哪一项（按 label 回调，和界面上的文案一一对应）。 */
    interface OnPick {
        void onPick(String label);
    }

    private Sheets() { }

    /**
     * 底部动作栏。
     *
     * @param labels 动作文案，顺序即显示顺序
     * @param danger 需要染成红色的那个 label（null = 没有）
     */
    static void menu(Activity a, String[] labels, String danger, final OnPick cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE);
        int r = dp(a, Theme.R_CARD + 4);
        bg.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        col.setBackground(bg);
        col.setPadding(dp(a, Theme.S4 - 2), dp(a, Theme.S2), dp(a, Theme.S4 - 2), dp(a, Theme.S5));

        // 顶上那道小把手：告诉用户"这条可以往下滑掉"
        View grip = new View(a);
        grip.setBackground(Theme.round(Theme.LINE, dp(a, 2)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(a, Theme.S1);
        glp.bottomMargin = dp(a, 10);
        col.addView(grip, glp);

        for (final String label : labels) {
            TextView row = new TextView(a);
            row.setText(label);
            row.setTextSize(16);
            row.setTextColor(label.equals(danger) ? Theme.DANGER : Theme.TEXT);
            row.setGravity(Gravity.CENTER);
            row.setPadding(dp(a, Theme.S3), dp(a, 15), dp(a, Theme.S3), dp(a, 15));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    d.dismiss();
                    if (cb != null) cb.onPick(label);
                }
            });
            col.addView(row);
        }

        d.setContentView(col);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    /** 全屏看图：黑底、FIT_CENTER（不裁）、右上角 ✕ 或点任意处关闭。 */
    static void image(final Activity a, Bitmap b) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout f = new FrameLayout(a);
        f.setBackgroundColor(Color.BLACK);

        ImageView iv = new ImageView(a);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(iv, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout close = new FrameLayout(a);
        close.setBackground(Theme.circle(Theme.SCRIM));
        ImageView ci = new ImageView(a);
        ci.setImageDrawable(Glyph.of(a, "close", 16, Theme.WHITE));
        FrameLayout.LayoutParams cilp = new FrameLayout.LayoutParams(dp(a, 16), dp(a, 16));
        cilp.gravity = Gravity.CENTER;
        close.addView(ci, cilp);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(a, 40), dp(a, 40));
        clp.gravity = Gravity.TOP | Gravity.END;
        clp.topMargin = dp(a, Theme.S5);
        clp.rightMargin = dp(a, Theme.S4);
        f.addView(close, clp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });

        d.setContentView(f);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        d.show();
    }

    /** 一段可滚动的长文本（"它当时在想什么"）。 */
    static void longText(Activity a, String title, String body) {
        TextView tv = new TextView(a);
        tv.setText(body == null ? "" : body);
        tv.setTextSize(13);
        tv.setTextColor(Theme.TEXT);
        tv.setLineSpacing(dp(a, 3), 1f);
        tv.setPadding(dp(a, Theme.S4), dp(a, Theme.S3), dp(a, Theme.S4), dp(a, Theme.S3));
        tv.setTextIsSelectable(true);
        ScrollView sc = new ScrollView(a);
        sc.addView(tv);
        new AlertDialog.Builder(a)
            .setTitle(title)
            .setView(sc)
            .setPositiveButton("知道了", null)
            .show();
    }

    /**
     * 保存到相册：写进 `Pictures/鱼眼/` 再让媒体库扫一遍。
     *
     * 走的是老式外部存储（targetSdk 29 + requestLegacyExternalStorage），
     * 所以不需要 MediaStore 那套 API，文件管理器里也**立刻能看到**。
     */
    static void toGallery(final Activity a, final String file) {
        Toast.makeText(a, "正在保存…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    byte[] b = ConvStore.readImage(file);
                    if (b == null || b.length == 0) {
                        msg = "这张图读不出来了";
                    } else {
                        java.io.File dir = new java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_PICTURES), "鱼眼");
                        if (!dir.exists()) dir.mkdirs();
                        java.io.File out =
                            new java.io.File(dir, "yuyan-" + System.currentTimeMillis() + ".jpg");
                        Files.write(out, b, false);
                        android.media.MediaScannerConnection.scanFile(a,
                            new String[] { out.getAbsolutePath() },
                            new String[] { "image/jpeg" }, null);
                        msg = "已存到相册 · 鱼眼/" + out.getName();
                    }
                } catch (Throwable e) {
                    msg = "保存失败：" + e.getMessage();
                }
                final String m = msg;
                a.runOnUiThread(new Runnable() {
                    public void run() { Toast.makeText(a, m, Toast.LENGTH_SHORT).show(); }
                });
            }
        }).start();
    }

    private static int dp(Activity a, float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
