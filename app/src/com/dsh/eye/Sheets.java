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

import java.util.List;

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

    /**
     * 引用来源面板：`来源 2 / 4` + 标题 + 域名 + 摘要 + `[ 用浏览器打开 ]`。
     *
     * <p>骨架**复用本类**（`Dialog` + `Gravity.BOTTOM` + 顶上那道把手），只是内容换成
     * "一次看一个来源、左右滑切上下一个"。⚠️ 别跟 `Sheet.java` 搞混 ——
     * 那个是**拼图合成器**（把几帧拼成一张宫格），跟"从底部升起来的面板"毫无关系。
     *
     * <p>三条行为来自 PRD §6.5：
     * <ul>
     *   <li>左右滑动切上/下一个来源（不弹二级菜单）；</li>
     *   <li>下拉关闭（和动作栏同一个手感）；</li>
     *   <li>`用浏览器打开` 走 {@code Intent.ACTION_VIEW}，拉起系统浏览器。</li>
     * </ul>
     *
     * @param cites   这条消息的引用（可能一个 ordinal 出现多次，按 ordinal 去重）
     * @param ordinal 从哪个来源开始看（就是角标上的数字）
     */
    static void sources(final Activity a, List<Cites.Cite> cites, int ordinal) {
        final List<Cites.Cite> list = byOrdinal(cites);
        if (list.isEmpty()) {
            Toast.makeText(a, "这条回答没带来源", Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE);
        int r = dp(a, Theme.R_CARD + 4);
        bg.setCornerRadii(new float[] { r, r, r, r, 0, 0, 0, 0 });
        col.setBackground(bg);
        col.setPadding(dp(a, Theme.S4), dp(a, Theme.S2), dp(a, Theme.S4), dp(a, Theme.S5));

        View grip = new View(a);
        grip.setBackground(Theme.round(Theme.LINE, dp(a, 2)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = dp(a, Theme.S1);
        glp.bottomMargin = dp(a, 10);
        col.addView(grip, glp);

        final TextView count = new TextView(a);
        count.setTextSize(Theme.T_SUB + 1);
        count.setTextColor(Theme.TEXT_DIM);

        FrameLayout close = new FrameLayout(a);
        close.setBackground(Theme.circle(Theme.SURFACE_2));
        ImageView ci = new ImageView(a);
        ci.setImageDrawable(Glyph.of(a, "close", 14, Theme.TEXT_DIM));
        FrameLayout.LayoutParams cilp = new FrameLayout.LayoutParams(dp(a, 14), dp(a, 14));
        cilp.gravity = Gravity.CENTER;
        close.addView(ci, cilp);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        });

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(count, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(close, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
        col.addView(head);

        View line = new View(a);
        line.setBackgroundColor(Theme.LINE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(a, 1)));
        llp.topMargin = dp(a, 10);
        col.addView(line, llp);

        final LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(a, 10), 0, 0);
        col.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int[] at = new int[] { indexOfOrdinal(list, ordinal) };
        final Runnable[] paint = new Runnable[1];
        paint[0] = new Runnable() {
            public void run() {
                int i = at[0];
                if (i < 0) i = 0;
                if (i >= list.size()) i = list.size() - 1;
                at[0] = i;
                Cites.Cite c = list.get(i);

                count.setText("来源 " + (i + 1) + " / " + list.size());
                body.removeAllViews();

                String title = c.title == null ? "" : c.title.trim();
                String host = Cards.domain(c.url);
                if (title.length() == 0) title = host.length() == 0 ? "（没有标题）" : host;

                TextView t = new TextView(a);
                t.setText(title);
                t.setTextSize(Theme.T_ROW);
                t.setTextColor(Theme.TEXT);
                t.setMaxLines(2);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                body.addView(t);

                TextView dm = new TextView(a);
                dm.setText(host);
                dm.setTextSize(Theme.T_TINY);
                dm.setTextColor(Theme.TEXT_DIM);
                dm.setPadding(0, dp(a, 3), 0, 0);
                body.addView(dm);

                String sn = c.snippet == null ? "" : c.snippet.trim();
                if (sn.length() > 0) {
                    TextView s = new TextView(a);
                    s.setText(sn);
                    s.setTextSize(Theme.T_SUB + 1);
                    s.setTextColor(Theme.TEXT_SOFT);
                    s.setLineSpacing(dp(a, 3), 1f);
                    s.setMaxLines(3);
                    s.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    s.setPadding(0, dp(a, Theme.S2), 0, 0);
                    body.addView(s);
                }

                TextView open = new TextView(a);
                open.setText("[ 用浏览器打开 ]");
                open.setTextSize(Theme.T_ROW);
                open.setTextColor(Theme.ACCENT);
                open.setGravity(Gravity.CENTER);
                open.setPadding(0, dp(a, 13), 0, dp(a, 13));
                open.setBackground(Theme.pillStroke(fade(Theme.ACCENT, 0x1A),
                    fade(Theme.ACCENT, 0x33), dp(a, 1)));
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                olp.topMargin = dp(a, Theme.S3);
                body.addView(open, olp);
                open.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        Cites.Cite cur = list.get(at[0]);
                        openUrl(a, cur == null ? null : cur.url);
                    }
                });
                Anim.press(open);

                // 有多个来源时给一句手势提示（一个来源时左右滑没意义）
                if (list.size() > 1) {
                    TextView tip = new TextView(a);
                    tip.setText("左右滑动看其它来源，下拉关闭。");
                    tip.setTextSize(Theme.T_TINY);
                    tip.setTextColor(Theme.TEXT_DIM);
                    tip.setGravity(Gravity.CENTER);
                    tip.setPadding(0, dp(a, Theme.S2), 0, 0);
                    body.addView(tip);
                }
            }
        };
        paint[0].run();

        // 手势：左右滑换来源，下拉关闭（用 fling，不用"移动超过多少" —— 那会跟正文滚动打架）
        final android.view.GestureDetector gd = new android.view.GestureDetector(a,
            new android.view.GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(android.view.MotionEvent e) { return true; }

                @Override
                public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2,
                                       float vx, float vy) {
                    if (e1 == null || e2 == null) return false;
                    float dx = e2.getX() - e1.getX();
                    float dy = e2.getY() - e1.getY();
                    if (Math.abs(dx) > Math.abs(dy) * 1.3f && Math.abs(dx) > dp(a, 52)) {
                        at[0] = at[0] + (dx < 0 ? 1 : -1);
                        if (at[0] < 0) at[0] = 0;
                        if (at[0] >= list.size()) at[0] = list.size() - 1;
                        paint[0].run();
                        return true;
                    }
                    if (dy > dp(a, 64) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                        d.dismiss();
                        return true;
                    }
                    return false;
                }
            });
        col.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, android.view.MotionEvent e) {
                gd.onTouchEvent(e);
                return false;          // 不吃事件：里面那个「用浏览器打开」还要能点
            }
        });

        d.setContentView(col);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        d.show();
    }

    /** 按 ordinal 去重、升序（同一个来源被引用多次时只留一条）。 */
    private static List<Cites.Cite> byOrdinal(List<Cites.Cite> cites) {
        List<Cites.Cite> out = new java.util.ArrayList<Cites.Cite>();
        if (cites == null) return out;
        for (int i = 0; i < cites.size(); i += 1) {
            Cites.Cite c = cites.get(i);
            if (c == null || c.ordinal <= 0) continue;
            boolean dup = false;
            for (int k = 0; k < out.size(); k += 1) {
                if (out.get(k).ordinal == c.ordinal) { dup = true; break; }
            }
            if (!dup) out.add(c);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Cites.Cite>() {
            public int compare(Cites.Cite x, Cites.Cite y) {
                return x.ordinal - y.ordinal;
            }
        });
        return out;
    }

    private static int indexOfOrdinal(List<Cites.Cite> list, int ordinal) {
        for (int i = 0; i < list.size(); i += 1) {
            if (list.get(i).ordinal == ordinal) return i;
        }
        return 0;
    }

    /** 拉起系统浏览器。**任何异常都不许崩**（有些 ROM 没有默认浏览器）。 */
    static void openUrl(Activity a, String url) {
        if (url == null || url.trim().length() == 0) {
            Toast.makeText(a, "这条来源没有链接", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            android.content.Intent i = new android.content.Intent(
                android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url.trim()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(a, "打不开浏览器，链接是：" + url, Toast.LENGTH_LONG).show();
        }
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

    /** 同一个色相换一个透明度（**不新建配色**）。 */
    private static int fade(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
