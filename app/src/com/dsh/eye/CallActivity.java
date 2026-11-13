package com.dsh.eye;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
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
    private View strip;
    private View dot;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    /** 顶部那行字：在看 / 在想 / 在听。 */
    private TextView status;
    private TextView autoBtn;
    /** 点一下画面把上下两条栏收起来（沉浸看）。再点一下回来。 */
    private boolean chromeHidden;

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
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        releaseShowing();
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
        bottomBar = bottom;

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

    /** 点画面：收起/放回上下两条栏。字幕留着（不然就成纯相机了）。 */
    private void toggleChrome() {
        chromeHidden = !chromeHidden;
        int v = chromeHidden ? View.GONE : View.VISIBLE;
        if (topBar != null) topBar.setVisibility(v);
        if (bottomBar != null) bottomBar.setVisibility(chromeHidden ? View.INVISIBLE : View.VISIBLE);
    }

    private void paintAuto(boolean on) {
        if (autoBtn == null) return;
        autoBtn.setText(on ? "自动 · 开" : "自动 · 关");
        autoBtn.setTextColor(on ? Theme.OK : Theme.TEXT_DIM);
    }

    private void setStatus(String text, int color) {
        if (status == null) return;
        status.setText(text);
        if (dot != null) dot.setBackground(Theme.circle(color));
        if (Theme.ACCENT == color || Theme.OK == color) Anim.pulse(dot);
        else Anim.stopPulse(dot);
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
                if ("running".equals(phase)) {
                    showSubtitle(detail, true);
                    setStatus("在想…", Theme.BUSY);
                } else {
                    setStatus("在看", Theme.ACCENT);
                }
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
                long now = System.currentTimeMillis();
                if (now - lastLiveAt < 80) return;
                lastLiveAt = now;
                showSubtitle(liveBuf.toString(), false);
                setStatus("在说…", Theme.ACCENT);
            }
        });
    }

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
                setStatus("在看", Theme.ACCENT);
            }
        });
    }

    @Override
    public void onRoundReset() {
        ui.post(new Runnable() {
            public void run() {
                liveBuf.setLength(0);
                showSubtitle("正在想…", true);
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
