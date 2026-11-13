package com.dsh.eye;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 通话页 —— **实时画面 + 字幕**，就是 0.13 那个形态，单独做成一个页面。
 *
 * ```
 * ┌────────────────────────────┐
 * │   ● 在看      自动 · 开      │  ← 顶部胶囊（浮在画面上）
 * │                            │
 * │         画面（不裁切）        │  ← FIT_CENTER，上下可以留黑边
 * │                            │
 * │  我：往左挪一点              │
 * │  ┌──────────────────────┐  │
 * │  │ 门口有两级台阶         │  │  ← 字幕卡片（SURFACE 90% + 18 圆角）
 * │  └──────────────────────┘  │
 * │  (说) [ 说点什么… ] (↥) (✕)│  ← ✕ = 挂断（右下圆形）
 * └────────────────────────────┘
 * ```
 *
 * ## 两个必须守住的规矩
 *
 * 1. **画面必须和模型看到的一致**：`FIT_CENTER`（不裁），宁可上下留黑边。
 *    用 `CENTER_CROP` 更"像视频通话"，但你看到的是裁过的、它看到的是全的 ——
 *    你说"左边那个"它答的却是画面外的东西。
 * 2. **离开就还相机**（`onPause` 里 `callClose()`）。这是电池与隐私承诺：
 *    没人看的时候指示灯不该亮。这条是红线，一次都不能破。
 */
public class CallActivity extends AppActivity implements EyeService.Ui, EyeService.PreviewSink {

    private static final int REQ_SPEECH = 3;

    private ImageView video;
    private TextView hint;
    private TextView subtitle;
    private TextView mine;
    private EditText input;
    private View dot;
    private LinearLayout topBar;
    /** 顶部那行字：在看 / 在想 / 在听。 */
    private TextView status;
    private TextView autoBtn;
    /** 取帧进度：窗口里几帧 / 覆盖几秒 / 最多几格（显示在"自动 · 开"和宫格标题里）。 */
    /** 当前显示的预览图里有几格 / 覆盖多少秒（**跟着图一起来**，不再去问"攒了几帧"）。 */
    private int shownCells;
    private double shownSpan;

    /** 点一下画面把上下两条栏收起来（沉浸看）。再点一下回来。 */
    private boolean chromeHidden;

    // ---- 宫格预览：**拼好的那张图本身**（和发出去的那张同一个函数算出来的） ----
    /** "宫格 · 12.4s · 4 格" 那行小字。 */
    private TextView gridLabel;
    /** 显示/隐藏预览的小按钮（右侧）。 */
    private TextView gridToggle;
    /** 预览开不开（跟服务里的设置同步）。 */
    private boolean previewOn = true;
    /** 拼好的预览图（3:4，132dp 宽）。 */
    private ImageView gridView;
    /** 预览位图。换新的时候**先挂后回收**，不能反。 */
    private Bitmap gridBmp;
    /** 解码只在这一条线程上做（主线程解码 JPEG 会掉帧）。 */
    private final java.util.concurrent.ExecutorService thumbPool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    /** 第几代请求：解码慢于新请求时，旧结果直接扔掉（否则预览会倒退回上一张）。 */
    private int thumbGen;
    /** 已经**真的画上去**的那一代（判据是"比它新"，不是"是最新"）。 */
    private int shownGen;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 当前显示中的位图。换下一张时回收它（一帧 600KB，不能等 GC）。 */
    private Bitmap showing;
    /** 采集线程写、主线程取：**只留最新一张**，积压的没有意义。 */
    private Bitmap pending;
    private final Object frameLock = new Object();
    private boolean drainPosted;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来"); finish(); return; }
        if (Build.VERSION.SDK_INT >= 23
            && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toast("没有相机权限，看不到画面");
        }
        s.setUi(this);
        s.setPreviewSink(this);
        previewOn = s.previewOn();
        applyPreview();
        s.callOpen();
        paintAuto(s.autoWatch());
        String last = s.lastLine();
        if (last != null && last.length() > 0) showSubtitle(last, false);
    }

    @Override
    protected void onPause() {
        EyeService s = EyeService.peek();
        if (s != null) {
            s.setPreviewSink(null);
            s.setUi(null);
            s.callClose();          // ⚠️ 红线：离开就还相机
        }
        // 摘掉之后不会再有新帧了，把手里这张也还掉
        synchronized (frameLock) {
            if (pending != null) { try { pending.recycle(); } catch (Throwable ignored) { } }
            pending = null;
        }
        // 缩略图同理：**同时把代次号打掉**，让已经排队的解码结果自己作废
        synchronized (this) { thumbGen += 1; }
        recycleGrid();
        if (gridLabel != null) gridLabel.setText("宫格 · 还没抓到");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        releaseShowing();
        recycleGrid();
        try { thumbPool.shutdownNow(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 界面

    private void buildUi() {
        immersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        video = new ImageView(this);
        // ⚠️ FIT_CENTER 不是"偷懒"：裁切会让预览和模型看到的不一致（见类注释）
        video.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(video, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setText("正在开相机…");
        hint.setTextColor(Theme.TEXT_DIM);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶部胶囊 ----
        LinearLayout topWrap = new LinearLayout(this);
        topWrap.setOrientation(LinearLayout.HORIZONTAL);
        topWrap.setGravity(Gravity.CENTER);
        topWrap.setPadding(0, statusBarHeight() + dp(Theme.S3), 0, 0);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(Theme.pillStroke(Theme.SURFACE_GLASS_2, Theme.RIPPLE, dp(1)));
        topBar.setPadding(dp(13), dp(7), dp(13), dp(7));

        dot = new View(this);
        dot.setBackground(Theme.circle(Theme.ACCENT));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(7);
        topBar.addView(dot, dlp);

        status = new TextView(this);
        status.setText("在看");
        status.setTextColor(Theme.TEXT);
        status.setTextSize(Theme.T_SUB);
        topBar.addView(status);

        // 分隔点
        TextView sep = new TextView(this);
        sep.setText("·");
        sep.setTextColor(Theme.TEXT_DIM);
        sep.setTextSize(Theme.T_SUB);
        sep.setPadding(dp(8), 0, dp(8), 0);
        topBar.addView(sep);

        // 自动看：默认开（"攒满就发"），点一下能闭嘴
        autoBtn = new TextView(this);
        autoBtn.setTextSize(Theme.T_SUB);
        autoBtn.setPadding(0, 0, 0, 0);
        autoBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                EyeService s = EyeService.peek();
                if (s == null) return;
                boolean on = !s.autoWatch();
                s.setAutoWatch(on);
                paintAuto(on);
            }
        });
        topBar.addView(autoBtn);

        topWrap.addView(topBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.TOP;
        root.addView(topWrap, tlp);

        // ---- 底部：字幕 + 输入 + 挂断 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4),
            dp(Theme.S4) + navigationBarHeight());

        mine = new TextView(this);
        mine.setTextColor(Theme.TEXT_GLASS);
        mine.setTextSize(13);
        mine.setGravity(Gravity.END);
        mine.setPadding(dp(Theme.S2), 0, dp(Theme.S2), dp(6));
        mine.setVisibility(View.GONE);
        bottom.addView(mine);

        subtitle = new TextView(this);
        subtitle.setTextColor(Theme.TEXT);
        subtitle.setTextSize(16);
        subtitle.setLineSpacing(dp(5), 1f);
        subtitle.setMaxLines(5);              // 再多就把画面吃掉了
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setPadding(dp(15), dp(13), dp(15), dp(13));
        subtitle.setBackground(Theme.round(Theme.SURFACE_GLASS, dp(Theme.R_CARD)));
        subtitle.setVisibility(View.GONE);     // 没话说的时候不留一个空盒子
        bottom.addView(subtitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 宫格预览：它正在攒哪几张 ----
        // 放在字幕和输入行中间：竖着排所以永远不会挡输入框，也不会被键盘压住。
        LinearLayout gridWrap = new LinearLayout(this);
        gridWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(Theme.S3);
        gridWrap.setLayoutParams(glp);

        /**
         * 标题行 = 「宫格 · 7.9s · 4 格」 + 右侧「隐藏 / 显示」。
         *
         * 为什么要这个开关：预览现在**每抓一帧就重排一次**（5fps ⇒ 200ms，拼图 + 解码 ~40ms），
         * 有人只想听声音/看字幕时就该能关掉 —— 关掉之后后台**连拼图都不算**（见 `schedulePreview`）。
         */
        LinearLayout gridHead = new LinearLayout(this);
        gridHead.setOrientation(LinearLayout.HORIZONTAL);
        gridHead.setGravity(Gravity.CENTER_VERTICAL);
        gridWrap.addView(gridHead);

        gridLabel = new TextView(this);
        gridLabel.setText("宫格 · 还没抓到");
        gridLabel.setTextColor(Theme.TEXT_GLASS);
        gridLabel.setTextSize(Theme.T_TINY);
        gridHead.addView(gridLabel, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        gridToggle = new TextView(this);
        gridToggle.setText("隐藏");
        gridToggle.setTextColor(Theme.TEXT_DIM);
        gridToggle.setTextSize(Theme.T_TINY);
        gridToggle.setPadding(dp(10), dp(3), dp(10), dp(3));
        gridToggle.setBackground(Theme.pillStroke(Theme.SURFACE_GLASS_2, Theme.RIPPLE, dp(1)));
        gridToggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                EyeService s = EyeService.peek();
                boolean on = !previewOn;
                previewOn = on;
                if (s != null) s.setPreviewOn(on);
                applyPreview();
            }
        });
        gridHead.addView(gridToggle);

        // 拼好的预览：3:4、宽 132dp，和发出去的那张同比例 —— 一眼能看出"现在发出去长什么样"
        gridView = new ImageView(this);
        gridView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        gridView.setBackground(Theme.roundStroke(Theme.SURFACE_GLASS_2, dp(10), Theme.LINE, dp(1)));
        gridView.setClipToOutline(true);
        gridView.setPadding(dp(2), dp(2), dp(2), dp(2));
        gridView.setVisibility(View.GONE);
        LinearLayout.LayoutParams grp = new LinearLayout.LayoutParams(dp(132), dp(176));
        grp.topMargin = dp(6);
        gridWrap.addView(gridView, grp);

        bottom.addView(gridWrap);

        // 输入行：说 | 输入框 | 发送 | 挂断
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(Theme.S3);
        row.setLayoutParams(rlp);

        ImageView mic = new ImageView(this);
        mic.setImageDrawable(Glyph.of(this, "mic", 20, Theme.TEXT_DIM));
        mic.setBackground(Theme.rippleCircle(Theme.RIPPLE));
        mic.setPadding(dp(8), dp(8), dp(8), dp(8));
        mic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { speak(); }
        });
        row.addView(mic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        input = new EditText(this);
        input.setHint("说点什么…");
        input.setTextSize(Theme.T_BODY);
        input.setTextColor(Theme.TEXT);
        input.setHintTextColor(Theme.TEXT_DIM);
        input.setMaxLines(3);
        input.setBackground(Theme.pillStroke(Theme.INPUT_GLASS, Theme.RIPPLE, dp(1)));
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); return true; }
                return false;
            }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ilp.leftMargin = dp(Theme.S1);
        ilp.rightMargin = dp(Theme.S2);
        row.addView(input, ilp);

        // 发送（40dp 渐变圆）
        FrameLayout send = new FrameLayout(this);
        send.setBackground(Theme.gradient(512f, Theme.GRAD[0], Theme.GRAD[1]));
        ImageView si = new ImageView(this);
        si.setImageDrawable(Glyph.of(this, "send", 19, Theme.WHITE));
        FrameLayout.LayoutParams silp = new FrameLayout.LayoutParams(dp(19), dp(19));
        silp.gravity = Gravity.CENTER;
        send.addView(si, silp);
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendTyped(); }
        });
        row.addView(send, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // 挂断：右下圆形
        FrameLayout hang = new FrameLayout(this);
        hang.setBackground(Theme.circle(Theme.DANGER_SOFT));
        ImageView hi = new ImageView(this);
        hi.setImageDrawable(Glyph.of(this, "close", 18, Theme.DANGER));
        FrameLayout.LayoutParams hilp = new FrameLayout.LayoutParams(dp(18), dp(18));
        hilp.gravity = Gravity.CENTER;
        hang.addView(hi, hilp);
        hang.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(44), dp(44));
        hlp.leftMargin = dp(Theme.S2);
        row.addView(hang, hlp);

        bottom.addView(row);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottom, blp);

        video.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggleChrome(); }
        });
        setContentView(root);
        // 通话页也有输入框：键盘弹起时不能让输入行被压住
        keepAboveKeyboard(root);
    }

    /**
     * 点画面：收起/放回上下两条栏。
     *
     * ⚠️ **宫格预览不跟着收**。它回答的是"它到底抓到没有、抓到的是哪几张"，
     * 收起来用户就又只能猜了（而且以前那个 `2/3` 就是被这一步连坐隐藏的）。
     */
    private void toggleChrome() {
        chromeHidden = !chromeHidden;
        int v = chromeHidden ? View.GONE : View.VISIBLE;
        if (topBar != null) topBar.setVisibility(v);
        if (mine != null) mine.setVisibility(chromeHidden ? View.GONE
            : (mine.getText().length() > 0 ? View.VISIBLE : View.GONE));
        if (input != null && input.getParent() instanceof View) {
            ((View) input.getParent()).setVisibility(chromeHidden ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /**
     * 顶部胶囊上的"自动 · 开 · 74 帧"。
     *
     * **数字从服务读，不从界面自己的缓存读** —— 以前是界面自己记 `bufHave/bufNeed`，
     * 重进通话页时它们被重置成 0，于是胶囊上那截 `2/3` 就"有时候有有时候没有"。
     */
    private void paintAuto(boolean on) {
        if (autoBtn == null) return;
        // 胶囊只说开/关：帧数在下面"宫格 · N 格"那行，量纲不同别混着显示
        autoBtn.setText(on ? "自动 · 开" : "自动 · 关");
        autoBtn.setTextColor(on ? Theme.OK : Theme.TEXT_DIM);
    }

    /**
     * 顶部那行字：在看 / 在想 / 在听 / 在说。
     *
     * 只动胶囊，**不动字幕** —— 字幕只放"它真说过的话"（以前这里同时刷字幕，
     * 每调一次工具就把上一句顶掉，用户看到的是"话说出来了、字幕却没了"）。
     */
    private void setStatus(String text, int color) {
        if (status == null) return;
        status.setText(text);
        if (dot != null) dot.setBackground(Theme.circle(color));
        if (Theme.ACCENT == color || Theme.OK == color) Anim.pulse(dot);
        else Anim.stopPulse(dot);
    }

    /** 取帧进度（采集线程调过来的）。 */
    /**
     * 这段时间内不要用"在看"盖掉那句"看过 · 没变化"。
     *
     * 自动看判成"没什么可说的"时，这一轮**不朗读不落盘**（见 `looksLikeNoop`）——
     * 界面上要是也什么都不留，用户看到的就是"在想…闪一下、然后没下文"，像坏了。
     */
    private long quietUntil;

    /** 预览的显示状态：**只在这一个地方**动 `gridView` 和按钮文案。 */
    private void applyPreview() {
        if (gridToggle != null) {
            gridToggle.setText(previewOn ? "隐藏" : "显示");
            gridToggle.setTextColor(previewOn ? Theme.TEXT_DIM : Theme.OK);
        }
        if (gridView != null) gridView.setVisibility(previewOn ? View.VISIBLE : View.GONE);
        if (!previewOn) recycleGrid();
        paintGridLabel();
    }

    /** "宫格 · 7.9s · 4 格"：一句话说清"这一张里有什么"（管线攒了多久、几格）。 */
    private void paintGridLabel() {
        if (gridLabel == null) return;
        if (!previewOn) {
            gridLabel.setText("宫格 · 预览已关（点右侧打开）");
            gridLabel.setTextColor(Theme.TEXT_GLASS);
            return;
        }
        if (shownCells <= 0) {
            gridLabel.setText("宫格 · 攒着呢…");
            gridLabel.setTextColor(Theme.TEXT_GLASS);
            return;
        }
        gridLabel.setText(String.format(java.util.Locale.ROOT,
            "宫格 · %.1fs · %d 格", shownSpan, shownCells));
        gridLabel.setTextColor(Theme.OK);
    }

    /**
     * **拼好的那张图**（服务端每 1.5 秒算一张，和真发出去的那张同一个函数）。
     *
     * 解码放到 {@link #thumbPool}；期间又来了新的一张就作废旧的（代次号），
     * 免得"新图被旧图盖回去"。
     */
    @Override
    public void onSheetPreview(final byte[] jpeg, final int cells, final double spanSec) {
        if (jpeg == null || jpeg.length == 0 || !previewOn) return;
        final int gen;
        synchronized (this) { gen = ++thumbGen; }
        try {
            thumbPool.submit(new Runnable() {
                public void run() {
                    /**
                     * **不在这里判"是不是最新一张"**。
                     *
                     * 原来这里（和 `applyGrid` 里）都要求 `gen == thumbGen`，而预览是 5fps、
                     * 主线程一忙（相机预览 + 字幕），`ui.post` 就可能晚于下一帧 ——
                     * 于是每一张都"已经过期"，框里**永远是空的**（真机报过）。
                     * 现在：照解，交给 `applyGrid` 按"只画比已画的更新的一代"决定。
                     */
                    final Bitmap bmp = decode(jpeg);
                    if (bmp == null) return;
                    ui.post(new Runnable() {
                        public void run() { applyGrid(gen, bmp, cells, spanSec); }
                    });
                }
            });
        } catch (Throwable ignored) { }
    }

    /** 预览图解码（260px 宽的小图，直接解原尺寸就够）。 */
    private Bitmap decode(byte[] jpg) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 换上预览图：**只画"比已经画上去的那张更新"的一代**。
     *
     * 判据是 `gen > shownGen`，不是 `gen == thumbGen` —— 后者要求"你必须是刚生成的那一张"，
     * 主线程稍微慢一点就永远不成立（框一直空）。宁可晚一帧，也不能一帧都不画。
     */
    private void applyGrid(int gen, Bitmap bmp, int cells, double span) {
        if (bmp == null) return;
        if (destroyed) { try { bmp.recycle(); } catch (Throwable ignored) { } return; }
        synchronized (this) {
            if (gen <= shownGen) { try { bmp.recycle(); } catch (Throwable ignored) { } return; }
            shownGen = gen;
        }
        shownCells = cells;
        shownSpan = span;
        Bitmap old = gridBmp;
        gridBmp = bmp;
        if (gridView != null) {
            gridView.setImageBitmap(bmp);
            gridView.setVisibility(View.VISIBLE);
        }
        // 先挂新的，再回收旧的（反了会闪一下黑）
        if (old != null && old != bmp) { try { old.recycle(); } catch (Throwable ignored) { } }
        paintGridLabel();
    }

    private void recycleGrid() {
        Bitmap b = gridBmp;
        gridBmp = null;
        if (b != null) { try { b.recycle(); } catch (Throwable ignored) { } }
        if (gridView != null) gridView.setImageDrawable(null);
    }

    // ---------------------------------------------------------------- 实时画面

    /** 采集线程调这里。**只留最新一张**，然后丢一个"去取"的任务给主线程。 */
    @Override
    public void onPreview(Bitmap bmp) {
        if (destroyed || bmp == null) { if (bmp != null) bmp.recycle(); return; }
        synchronized (frameLock) {
            if (pending != null) { try { pending.recycle(); } catch (Throwable ignored) { } }
            pending = bmp;
            if (drainPosted) return;
            drainPosted = true;
        }
        ui.post(drain);
    }

    private final Runnable drain = new Runnable() {
        public void run() {
            Bitmap b;
            synchronized (frameLock) {
                drainPosted = false;
                b = pending;
                pending = null;
            }
            if (b == null) return;
            if (destroyed || video == null) { try { b.recycle(); } catch (Throwable ignored) { } return; }
            Bitmap old = showing;
            showing = b;
            video.setImageBitmap(b);
            if (old != null && old != b) {
                try { old.recycle(); } catch (Throwable ignored) { }
            }
            if (hint.getVisibility() != View.GONE) hint.setVisibility(View.GONE);
        }
    };

    private void releaseShowing() {
        Bitmap b = showing;
        showing = null;
        if (b != null) { try { b.recycle(); } catch (Throwable ignored) { } }
    }

    // ---------------------------------------------------------------- EyeService.Ui

    @Override
    public void onTool(final String name, final String phase, final String detail) {
        ui.post(new Runnable() {
            public void run() {
                // ⚠️ 工具状态**只进胶囊，不进字幕**。
                //    以前这里 `showSubtitle(detail)` —— 它看一眼就把它上一句真说过的话
                //    顶掉了，用户看到的是"话说出来了、字幕却没了"。
                if ("running".equals(phase)) setStatus("在想…", Theme.BUSY);
                else if (System.currentTimeMillis() >= quietUntil) setStatus("在看", Theme.ACCENT);
            }
        });
    }

    @Override
    public void onReply(final String text, String reasoning, long thinkMs,
                        java.util.List<String> imageFiles, long at) {
        ui.post(new Runnable() {
            public void run() {
                liveBuf.setLength(0);
                showSubtitle(text, false);
                setStatus("在看", Theme.ACCENT);
            }
        });
    }

    @Override
    public void onError(final String msg) {
        ui.post(new Runnable() {
            public void run() { showSubtitle("（没答上：" + msg + "）", false); }
        });
    }

    @Override
    public void onState() { }

    /**
     * 字幕跟着**流式增量**走，不等最终答案。
     *
     * 之前只在 `onReply`（最终）时更新，结果声音先出来、字后到 ——
     * 因为朗读是另一条线程，而字幕要等整段收完。现在边收边上。
     * 渲染节流到 80ms：每来一小段就重排一次 TextView 会把主线程占满。
     */
    private final StringBuilder liveBuf = new StringBuilder();
    private long lastLiveAt;

    @Override
    public void onDelta(final String text) {
        ui.post(new Runnable() {
            public void run() {
                if (text != null) liveBuf.append(text);
                renderLive();
            }
        });
    }

    /**
     * 把 {@link #liveBuf} 刷到字幕上，**并保证最后一段一定刷得出去**。
     *
     * 节流 80ms 是为了别把主线程占满，但只"跳过着一次"不补一次的话，
     * 最后那几个字就永远只活在缓冲里 —— 声音念完了、字幕停在半句。
     */
    private void renderLive() {
        long now = System.currentTimeMillis();
        if (now - lastLiveAt < 80) {
            ui.removeCallbacks(flushLive);
            ui.postDelayed(flushLive, 90);
            return;
        }
        lastLiveAt = now;
        if (liveBuf.length() == 0) return;
        showSubtitle(liveBuf.toString(), false);
        setStatus("在说…", Theme.ACCENT);
    }

    private final Runnable flushLive = new Runnable() {
        public void run() { renderLive(); }
    };

    @Override
    public void onQuiet() {
        ui.post(new Runnable() {
            public void run() {
                // 自动看之后判断"没什么变化"：字幕回到**上一句真的说过的话**
                liveBuf.setLength(0);
                EyeService s = EyeService.peek();
                String last = s == null ? null : s.lastLine();
                if (last != null && last.length() > 0) showSubtitle(last, false);
                else if (subtitle != null) subtitle.setVisibility(View.GONE);
                /**
                 * 胶囊上留一句"看过 · 没变化"再退回"在看"。
                 *
                 * 这一轮是不朗读、不落盘的（设计如此），但**界面上必须有痕迹** ——
                 * 否则"在想…"闪一下就没下文，用户会以为卡死了（2026-11-14 就是这么报的）。
                 */
                quietUntil = System.currentTimeMillis() + 2500;
                setStatus("看过 · 没变化", Theme.TEXT_DIM);
                ui.postDelayed(new Runnable() {
                    public void run() {
                        if (!destroyed && System.currentTimeMillis() >= quietUntil) {
                            setStatus("在看", Theme.ACCENT);
                        }
                    }
                }, 2600);
            }
        });
    }

    @Override
    public void onRoundReset() {
        ui.post(new Runnable() {
            public void run() {
                // 同上：**别把字幕换成"正在想…"**。字幕只放"它真说过的话"，
                // "在想"这件事交给上面那个胶囊。否则每调一次工具字幕就闪一下，
                // 而声音（朗读是异步排队）可能还在念上一句，看起来就像"话说了一半没了"。
                liveBuf.setLength(0);
                setStatus("在想…", Theme.BUSY);
            }
        });
    }

    /** 字幕只显示**最后一段**：整段贴上来会盖住画面，也来不及看。 */
    private void showSubtitle(String text, boolean thinking) {
        if (subtitle == null || text == null) return;
        // 字幕是"念给人听的"，不该出现 `**`、`- `、`###` 这些东西
        String t = Speaker.plain(text).trim();
        if (t.length() > 160) {
            int cut = t.lastIndexOf('\n', t.length() - 160);
            if (cut < 40) cut = t.length() - 159;
            t = "…" + t.substring(cut).trim();
        }
        if (t.length() == 0) { subtitle.setVisibility(View.GONE); return; }
        subtitle.setText(t);
        subtitle.setTextColor(thinking ? Theme.TEXT_DIM : Theme.TEXT);
        subtitle.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- 说话

    private void sendTyped() {
        if (input == null) return;
        String t = input.getText().toString().trim();
        if (t.length() == 0) return;
        input.setText("");
        hideIme();
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来"); return; }
        mine.setText("我：" + t);
        mine.setVisibility(View.VISIBLE);
        showSubtitle("正在想…", true);
        setStatus("在听…", Theme.OK);
        s.send(t);
    }

    private void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
        } catch (Throwable ignored) { }
    }

    private void speak() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么");
            startActivityForResult(i, REQ_SPEECH);
        } catch (Throwable e) {
            toast("这台机器没有语音识别，打字吧");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SPEECH || res != RESULT_OK || data == null) return;
        java.util.ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        String t = r.get(0);
        if (t == null || t.trim().length() == 0) return;
        if (input != null) input.setText(t.trim());
        sendTyped();
    }
}
