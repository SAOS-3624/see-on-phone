package com.dsh.eye;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入栏：**预览条 + 52dp 胶囊容器**（拍一张 / 附件 / 输入框 / 语音 / 发送）。
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │ [图46] [图46] [📄 合同.pdf 1.2MB ▓▓▓░ ✕]  ← 预览条（空时 GONE）
 * ├──────────────────────────────────────────────┤
 * │ ( ⚉ ) ( ＋ )   说点什么…          ( ◎ ) ( ➤ ) │  ← 52dp 胶囊
 * └──────────────────────────────────────────────┘
 * ```
 *
 * ## 待发的东西存在哪
 *
 * **图片存在 `EyeService` 里**（它有前台服务的生命周期），不是存在 Activity 字段里。
 * Activity 会因为切后台被回收、旋屏重建 —— 字段一丢，用户刚选好的四张图就没了。
 * 这里每次变化都同步一份过去，重建后 `pullFromService()` 还原。
 *
 * **文档不往服务里塞**：它有自己的副本（`cache/uploads/`）和 `file_id`，
 * 进程一死这些都没意义了（`file_id` 是真身，但"还没发出去"这件事本身就没了）。
 * 进程被杀之后要求"附件还在"，等于要求一个没有主人的上传任务 —— 不做。
 *
 * ## 两个入口，两套路
 *
 * | 入口 | 上限 | 走哪条 |
 * |---|---|---|
 * | 图片（相册） | 4 张 | 缩到 1568px → `image_url` base64（**现有行为，没动**） |
 * | 文件（任意） | 5 个 | 复制到 cache → `/files` 上传 → `file_id`（`Uploads`） |
 *
 * 「文件」里选中图片时**自动回退到图片那条路**：用户从"文件"里挑一张截图，
 * 期望的是"看这张图"，不是"把它当附件解析文字"。
 *
 * ## 模式闸
 *
 * `setDocGate(false, hint)`：联网/某些模型不能带文档时，文件入口置灰，点了给一句
 * 调用方给的话（文案不在这里写死 —— 判断在 `Caps.plan`，这里只是执行）。
 */
final class ChatInputBar extends LinearLayout {

    /**
     * 一条待发的东西。**要么是图片，要么是文档**（`job != null` 就是文档）。
     *
     * 文档的三个字段（`att / purpose / size`）是给 `MainActivity` 分流用的：
     * `SendListener.onSend(String, List<Pick>)` 的签名**没变**，上层靠 `att != null` 认文档。
     */
    static final class Pick {
        /** 图片：落盘路径（显示用）；文档：null。 */
        final String path;
        /** 图片：jpeg 字节（进 API 用）；文档：null（字节在 `job.cache` 里，走流式上传）。 */
        final byte[] bytes;

        /** 文档附件（上传成功后才有值；图片恒为 null）。 */
        Conv.Att att;
        /** 文档的用途（`rich_layout_recognition` / `user_data`）。 */
        String purpose;
        /** 原始字节数。 */
        long size;
        /** 文档的上传任务（图片恒为 null）。 */
        Uploads.Job job;

        // ── 视图引用：进度回调里**就地改**，不整条重画（100MB 会来 1600 次回调）──
        View card;
        TextView status;
        ProgressBar bar;

        Pick(String path, byte[] bytes) {
            this.path = path;
            this.bytes = bytes;
        }

        Pick(Uploads.Job j) {
            this.path = null;
            this.bytes = null;
            this.job = j;
            this.purpose = j.purpose;
            this.size = j.size;
            this.att = Uploads.ref(j);
        }

        boolean doc() { return job != null; }

        int state() { return job == null ? Uploads.ST_OK : job.state; }

        int pct() { return job == null ? 100 : job.pct; }

        /** 还没传完（含"排着队还没开始"）—— 发送键要等它。 */
        boolean working() {
            return job != null && (job.state == Uploads.ST_UP || job.state == Uploads.ST_WAIT);
        }
    }

    /**
     * 发送回调。
     *
     * @return true = 已发出（输入栏清空自己）；false = 服务没起来之类，**别清**，
     *         否则用户刚打的字和刚选的图就白费了
     */
    interface SendListener {
        boolean onSend(String text, List<Pick> picks);
    }

    private static final int REQ_PICK = 0x51;
    private static final int REQ_SPEECH = 0x52;
    /** 文件（文档）选择器。 */
    private static final int REQ_FILES = 0x53;
    /** 一次最多带几张图。 */
    static final int MAX_PICK = 4;
    /** 相册图缩到长边 1568 / JPEG 85%（视觉接口的推荐上限，再大会被服务端压一遍）。 */
    private static final int MAX_EDGE = 1568;
    private static final int QUALITY = 85;

    private final Activity host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Pick> picks = new ArrayList<>();
    /** 上传要用 key（`Uploads.upload` 得有个 Config）。 */
    private final Config cfg;

    private LinearLayout previewBar;
    private EditText input;
    private FrameLayout sendBtn;
    private ImageView sendIcon;
    private PopupWindow menu;

    private SendListener sendL;
    private Runnable stopL;
    private Runnable focusL;
    private Runnable lookL;
    private boolean sending;

    /** 允许带文档吗（由 `Caps.plan` 的结论灌进来，见 {@link #setDocGate}）。 */
    private boolean docGate = true;
    /** 不允许时点文件入口的那句提示（调用方给文案）。 */
    private String docGateHint = "";

    ChatInputBar(Activity a) {
        super(a);
        this.host = a;
        this.cfg = new Config(a);
        setOrientation(VERTICAL);
        build();
        pullFromService();
    }

    // ------------------------------------------------------------ 对外 API

    void setOnSend(SendListener l) { this.sendL = l; }

    /** 流式进行中，点发送键 = 停止。 */
    void setOnStop(Runnable r) { this.stopL = r; }

    /** 输入框获得焦点（界面用来滚到底）。 */
    void setOnFocus(Runnable r) { this.focusL = r; }

    /**
     * 「拍一张」：让手机现场看一眼（走 `EyeService.lookNow()` 那条现成流程）。
     *
     * 为什么这个按钮必须有：模型会自己调 `look`，但**用户没法指定"就看现在这个"** ——
     * 没有它，想让它看眼前的东西只能打字描述，而"看一眼"恰恰是这个 App 的原始卖点。
     */
    void setOnLook(Runnable r) { this.lookL = r; }

    /**
     * 文件入口开不开。
     *
     * @param allowed false = 置灰 + 点了只给 {@code hint}（联网模式下模型不能读文件、
     *                或文档闸没通过时都是 false）
     * @param hint    中文一句话，例如 `Caps.degradeHint(Caps.CAP_FILES)`
     */
    void setDocGate(boolean allowed, String hint) {
        this.docGate = allowed;
        this.docGateHint = hint == null ? "" : hint;
        // 弹窗要是正开着，直接关掉：下次弹按新状态画，免得看到的是旧状态
        if (menu != null && menu.isShowing()) {
            try { menu.dismiss(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 已经上传成功的文档（给 `MainActivity` 塞进 `EyeService.Turn.atts`）。
     *
     * **只返回成功的**：失败/上传中的不发 —— 带一个没有 `file_id` 的附件进请求，
     * 服务端只会回 400，用户看到的却是一句"请求失败"。
     */
    List<Conv.Att> atts() {
        List<Conv.Att> out = new ArrayList<>();
        for (Pick p : picks) {
            if (p.doc()) p.att = Uploads.ref(p.job);
            if (p.att != null) out.add(p.att);
        }
        return out;
    }

    /** 还有文档在传吗（发送键要等）。 */
    boolean uploading() {
        for (Pick p : picks) if (p.working()) return true;
        return false;
    }

    void setText(String t) {
        if (input == null) return;
        input.setText(t == null ? "" : t);
        input.setSelection(input.getText().length());
        refreshSend();
    }

    String text() {
        return input == null ? "" : input.getText().toString().trim();
    }

    boolean hasImages() {
        for (Pick p : picks) if (!p.doc()) return true;
        return false;
    }

    /** 待发图的落盘路径（`onSaveInstanceState` 用它兜一层，见 MainActivity）。 */
    List<String> imagePaths() {
        List<String> out = new ArrayList<>();
        for (Pick p : picks) if (!p.doc()) out.add(p.path);
        return out;
    }

    int imageCount() {
        int n = 0;
        for (Pick p : picks) if (!p.doc()) n += 1;
        return n;
    }

    int docCount() {
        int n = 0;
        for (Pick p : picks) if (p.doc()) n += 1;
        return n;
    }

    /** 清空（发送成功后调）。进行中的上传要**取消**，否则它还在花流量。 */
    void clear() {
        if (input != null) input.setText("");
        for (Pick p : picks) {
            // 已成功的别取消：file_id 已经交给上层落盘了，这里只是不再显示
            if (p.doc() && p.state() != Uploads.ST_OK) Uploads.cancel(p.job);
        }
        picks.clear();
        renderPreview();
        pushToService();
        refreshSend();
    }

    /** 流式开/关：发送键在「➤」和「■」之间换。 */
    void setSending(boolean on) {
        this.sending = on;
        refreshSend();
    }

    /** 把一张已经落盘的图加进待发（长按历史图「再问一句」走这条）。 */
    void addImage(final String file) {
        if (file == null || file.length() == 0) return;
        if (imageCount() >= MAX_PICK) { toast("最多 " + MAX_PICK + " 张"); return; }
        for (Pick p : picks) if (!p.doc() && file.equals(p.path)) return;
        new Thread(new Runnable() {
            public void run() {
                final byte[] b = ConvStore.readImage(file);
                if (b == null || b.length == 0) {
                    ui.post(new Runnable() { public void run() { toast("这张图读不出来了"); } });
                    return;
                }
                ui.post(new Runnable() {
                    public void run() {
                        picks.add(new Pick(file, b));
                        renderPreview();
                        pushToService();
                        refreshSend();
                    }
                });
            }
        }).start();
    }

    /** 把焦点和键盘给输入框（空态里的建议胶囊点完之后用）。 */
    void focus() {
        if (input == null) return;
        input.requestFocus();
        try {
            InputMethodManager imm = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignored) { }
    }

    /** 主界面把 `onActivityResult` 转进来。 */
    boolean onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK) {
            if (res == Activity.RESULT_OK && data != null) collectPicked(data);
            return true;
        }
        if (req == REQ_FILES) {
            if (res == Activity.RESULT_OK && data != null) collectFiles(data);
            return true;
        }
        if (req == REQ_SPEECH) {
            if (res == Activity.RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty() && r.get(0) != null) {
                    String t = r.get(0).trim();
                    if (t.length() > 0) voiceSend(t);
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 搭建

    private void build() {
        previewBar = new LinearLayout(host);
        previewBar.setOrientation(HORIZONTAL);
        previewBar.setGravity(Gravity.CENTER_VERTICAL);
        previewBar.setPadding(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S1 + 2));
        previewBar.setVisibility(GONE);
        addView(previewBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 52dp 胶囊 ----
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinimumHeight(dp(52));
        box.setBackground(Theme.pillStroke(Theme.SURFACE, Theme.LINE, dp(1)));
        box.setPadding(dp(6), dp(4), dp(6), dp(4));

        // 拍一张（现场看）——故意排在附件前面：它是"现在就看"，比"选一张旧图"常用
        ImageView shot = icon("camera");
        shot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (lookL != null) lookL.run();
            }
        });
        box.addView(shot, new LinearLayout.LayoutParams(dp(36), dp(36)));

        // 附件（▣）：一个入口弹「图片 / 文件」两条路
        final ImageView clip = icon("plus");
        clip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { attachMenu(clip); }
        });
        box.addView(clip, new LinearLayout.LayoutParams(dp(36), dp(36)));

        // 输入框
        input = new PasteEdit(host);
        input.setHint("说点什么…");
        input.setTextSize(Theme.T_BODY);
        input.setTextColor(Theme.TEXT);
        input.setHintTextColor(Theme.TEXT_DIM);
        input.setMaxLines(5);
        input.setMinLines(1);
        input.setBackground(null);
        input.setPadding(dp(6), dp(8), dp(6), dp(8));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setVerticalScrollBarEnabled(false);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent ev) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { fire(); return true; }
                return false;
            }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                if (has && focusL != null) focusL.run();
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { refreshSend(); }
            public void afterTextChanged(Editable s) { }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.addView(input, ilp);

        // 语音
        ImageView mic = icon("mic");
        mic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { speak(); }
        });
        box.addView(mic, new LinearLayout.LayoutParams(dp(36), dp(36)));

        // 发送（40dp 渐变圆）
        sendBtn = new FrameLayout(host);
        sendBtn.setBackground(Theme.circle(Theme.SURFACE_2));
        sendIcon = new ImageView(host);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(19), dp(19));
        slp.gravity = Gravity.CENTER;
        sendBtn.addView(sendIcon, slp);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 流式进行中：这个键是"停止"（豆包就是这样，一个键两种身份）
                if (sending) { if (stopL != null) stopL.run(); return; }
                if (uploading()) { toast("等上传完成"); return; }
                fire();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(40), dp(40));
        blp.leftMargin = dp(2);
        box.addView(sendBtn, blp);

        LinearLayout wrap = new LinearLayout(host);
        wrap.setOrientation(HORIZONTAL);
        wrap.setPadding(dp(Theme.S2), dp(Theme.S2), dp(Theme.S2), dp(Theme.S3));
        wrap.addView(box, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(wrap, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshSend();
    }

    private ImageView icon(String name) {
        ImageView v = new ImageView(host);
        v.setImageDrawable(Glyph.of(host, name, 20, Theme.TEXT_DIM));
        v.setBackground(Theme.rippleCircle(Theme.RIPPLE));
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        return v;
    }

    /**
     * 附件菜单：「图片 / 文件」。
     *
     * 为什么不用两个常驻按钮：52dp 的胶囊里已经有 4 个图标（拍/附件/语音/发送），
     * 再加一个"文件"，输入框就只剩 100dp 了。收进一个小弹窗，点两次和点一次的区别
     * 远小于"输入框被挤没"的代价。
     */
    private void attachMenu(View anchor) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.roundStroke(Theme.SURFACE_2, dp(14), Theme.LINE, dp(1)));
        box.setPadding(dp(4), dp(4), dp(4), dp(4));

        box.addView(menuItem("image", "图片", true, new Runnable() {
            public void run() { pick(); }
        }));
        box.addView(menuItem("folder", "文件", docGate, new Runnable() {
            public void run() {
                if (!docGate) { toast(docGateHint); return; }
                pickFiles();
            }
        }));

        final PopupWindow pw = new PopupWindow(box, dp(128),
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(new ColorDrawable(Theme.NONE));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        menu = pw;
        try {
            // 向上弹：锚点在屏幕底部，往下弹会被键盘/屏幕边吃掉
            pw.showAsDropDown(anchor, 0, -dp(104));
        } catch (Throwable e) {
            pw.showAsDropDown(anchor);
        }
    }

    /** 弹窗里的一行：图标 + 文字。`enabled=false` 时压暗（点了给提示，不是静默无反应）。 */
    private View menuItem(String glyph, String label, boolean enabled, final Runnable click) {
        LinearLayout r = new LinearLayout(host);
        r.setOrientation(HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(9), dp(10), dp(9));
        r.setBackground(Theme.rippleCircle(Theme.RIPPLE));

        ImageView ic = new ImageView(host);
        ic.setImageDrawable(Glyph.of(host, glyph, 17, enabled ? Theme.TEXT : Theme.TEXT_DIM));
        r.addView(ic, new LinearLayout.LayoutParams(dp(17), dp(17)));

        TextView t = new TextView(host);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(enabled ? Theme.TEXT : Theme.TEXT_DIM);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(9);
        r.addView(t, lp);

        r.setAlpha(enabled ? 1f : 0.45f);
        r.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (menu != null) { try { menu.dismiss(); } catch (Throwable ignored) { } }
                click.run();
            }
        });
        return r;
    }

    // ------------------------------------------------------------ 发送键状态

    /** 空输入时：暗淡、不可点。有内容：渐变 + 亮图标。流式中：变停止键。上传中：置灰。 */
    private void refreshSend() {
        if (sendBtn == null) return;
        boolean hasText = input != null && input.getText().toString().trim().length() > 0;
        boolean armed = sending || hasText || !picks.isEmpty();
        if (sending) {
            sendBtn.setBackground(Theme.gradient(512f, Theme.GRAD[0], Theme.GRAD[1]));
            sendIcon.setImageDrawable(Glyph.of(host, "stop", 14, Theme.WHITE));
            sendBtn.setClickable(true);
            sendBtn.setAlpha(1f);
            return;
        }
        if (uploading()) {
            // 置灰但仍可点：点了给一句"等上传完成"（不可点就没法解释为什么按不动）
            sendBtn.setBackground(Theme.circle(Theme.SURFACE_2));
            sendIcon.setImageDrawable(Glyph.of(host, "send", 19, Theme.TEXT_DIM));
            sendBtn.setClickable(true);
            sendBtn.setAlpha(0.55f);
            return;
        }
        if (armed) {
            sendBtn.setBackground(Theme.gradient(512f, Theme.GRAD[0], Theme.GRAD[1]));
            sendIcon.setImageDrawable(Glyph.of(host, "send", 19, Theme.WHITE));
        } else {
            sendBtn.setBackground(Theme.circle(Theme.SURFACE_2));
            sendIcon.setImageDrawable(Glyph.of(host, "send", 19, Theme.TEXT_DIM));
        }
        sendBtn.setClickable(armed);
        sendBtn.setAlpha(armed ? 1f : 0.55f);
    }

    /** 按下发送（或回车）。 */
    private void fire() {
        if (sending) { if (stopL != null) stopL.run(); return; }
        if (uploading()) { toast("等上传完成"); return; }
        String t = text();
        if (t.length() == 0 && picks.isEmpty()) return;
        if (sendL == null) return;
        List<Pick> send = new ArrayList<>(picks);
        if (sendL.onSend(t, send)) clear();
    }

    private void voiceSend(String t) {
        if (sendL == null || t == null || t.trim().length() == 0) return;
        // 语音**不带你正在选的图/文件**（"我说的话 + 我刚选的四张图"语义很怪），
        // 也不清空已选的 —— 识别出来的这句话和那几张图是两件事。
        sendL.onSend(t.trim(), new ArrayList<Pick>());
    }

    // ------------------------------------------------------------ 选：图片

    private void pick() {
        if (imageCount() >= MAX_PICK) { toast("最多 " + MAX_PICK + " 张，先发一批"); return; }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            host.startActivityForResult(i, REQ_PICK);
        } catch (Throwable e) {
            toast("这台机器没有文件选择器，换个方式选图吧");
        }
    }

    /** 从 `onActivityResult` 里把 1~N 个 uri 捞出来。 */
    private void collectPicked(Intent data) {
        List<Uri> uris = urisOf(data);
        int room = MAX_PICK - imageCount();
        if (uris.size() > room) {
            toast("一次最多 " + MAX_PICK + " 张，多的没加");
            uris = uris.subList(0, room);
        }
        for (Uri u : uris) addFromUri(u);
    }

    // ------------------------------------------------------------ 选：文件

    private void pickFiles() {
        if (docCount() >= Uploads.MAX_DOCS) {
            toast("最多 " + Uploads.MAX_DOCS + " 个文件，先发一批");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // 不过滤 mime：用户会从"最近"里挑到任何东西，先让他选，选完再说行不行
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            host.startActivityForResult(i, REQ_FILES);
        } catch (Throwable e) {
            toast("这台机器没有文件选择器");
        }
    }

    /**
     * 文件选择结果：**图片自动回流到图片那条路**，其余走上传。
     *
     * 超量的处理是"加前 5 个 + 提示"，不是整批拒绝 —— 整批拒绝会逼用户重选一遍，
     * 而他已经明确表达了"我要这几个"。
     */
    private void collectFiles(Intent data) {
        List<Uri> uris = urisOf(data);
        int room = Uploads.MAX_DOCS - docCount();
        int skipped = 0;
        int pics = 0;
        for (Uri u : uris) {
            if (Uploads.isImage(host, u)) {
                if (imageCount() + 1 > MAX_PICK) { skipped += 1; continue; }
                addFromUri(u);
                pics += 1;
                continue;
            }
            if (room <= 0) { skipped += 1; continue; }
            room -= 1;
            startDoc(u);
        }
        if (skipped > 0) {
            toast("一次最多 " + Uploads.MAX_DOCS + " 个文件、" + MAX_PICK + " 张图，多的没加");
        }
        if (pics > 0) EyeLog.w("pick", "文件入口选了 " + pics + " 张图 → 走相册那条");
    }

    private List<Uri> urisOf(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data == null) return uris;
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i += 1) {
                ClipData.Item it = cd.getItemAt(i);
                if (it != null && it.getUri() != null) uris.add(it.getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    /**
     * 收一个文档：后台**校验 + 复制**（100MB 会花几百毫秒到几秒），再开始上传。
     *
     * 顺序不能反：先校验（太大/不支持当场拒，不白拷 100MB），再复制，再上传。
     */
    private void startDoc(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                final String name = Uploads.nameOf(host, uri);
                final long size = Uploads.sizeOf(host, uri);
                final Uploads.Job job = Uploads.attach(host, uri, name, size);
                ui.post(new Runnable() {
                    public void run() {
                        if (job.state == Uploads.ST_FAIL) {
                            toast(job.error == null ? "这个文件加不进来" : job.error);
                            return;
                        }
                        final Pick p = new Pick(job);
                        picks.add(p);
                        renderPreview();
                        pushToService();
                        refreshSend();
                        Uploads.upload(cfg, job, docCb(p));
                    }
                });
            }
        }, "attach").start();
    }

    /** 文档的回调：**不整条重画**，只改那一张卡上的字和进度条。 */
    private Uploads.Cb docCb(final Pick p) {
        return new Uploads.Cb() {
            public void onProgress(Uploads.Job j) {
                refreshDoc(p);
                refreshSend();
            }
            public void onDone(Uploads.Job j) {
                p.att = Uploads.ref(j);
                refreshDoc(p);
                refreshSend();
                if (p.card != null) p.card.setBackground(Theme.roundStroke(
                    Theme.SURFACE_2, dp(9), Theme.LINE, dp(1)));
            }
            public void onFail(Uploads.Job j) {
                p.att = null;
                refreshDoc(p);
                refreshSend();
            }
        };
    }

    // ------------------------------------------------------------ 选：图片 → 字节

    /**
     * 读一个 uri → 缩到长边 1568 → 存 `filesDir/album/` → 加进待发。
     *
     * **全程后台线程**：相册图动辄 4000px，在 UI 线程解会直接 ANR。
     */
    private void addFromUri(final Uri uri) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final byte[] jpeg = readScaledJpeg(uri);
                    if (jpeg == null || jpeg.length == 0) {
                        ui.post(new Runnable() {
                            public void run() { toast("这张图读不出来（格式不支持？）"); }
                        });
                        return;
                    }
                    java.io.File dir = new java.io.File(host.getFilesDir(), "album");
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File out = new java.io.File(dir, "a" + System.currentTimeMillis()
                        + "-" + (int) (Math.random() * 1000) + ".jpg");
                    Files.write(out, jpeg, false);
                    final String path = out.getAbsolutePath();
                    EyeLog.w("pick", "ok " + jpeg.length + "B → " + out.getName());
                    ui.post(new Runnable() {
                        public void run() {
                            picks.add(new Pick(path, jpeg));
                            renderPreview();
                            pushToService();
                            refreshSend();
                        }
                    });
                } catch (final Throwable e) {
                    final String msg = String.valueOf(e.getMessage());
                    ui.post(new Runnable() {
                        public void run() { toast("读图失败：" + msg); }
                    });
                }
            }
        }).start();
    }

    /**
     * 两趟解码：先只读尺寸算 `inSampleSize`，再真解。
     *
     * 一步 `decodeStream` 会把整张 4000×3000 读进内存，几张就 OOM。
     */
    private byte[] readScaledJpeg(Uri uri) throws Exception {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        java.io.InputStream in1 = host.getContentResolver().openInputStream(uri);
        if (in1 == null) return null;
        try { BitmapFactory.decodeStream(in1, null, probe); } finally { in1.close(); }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

        int sample = 1;
        while (probe.outWidth / sample > MAX_EDGE * 2 && probe.outHeight / sample > MAX_EDGE * 2) {
            sample *= 2;
        }

        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        java.io.InputStream in2 = host.getContentResolver().openInputStream(uri);
        if (in2 == null) return null;
        Bitmap bmp;
        try { bmp = BitmapFactory.decodeStream(in2, null, opt); } finally { in2.close(); }
        if (bmp == null) return null;

        int w = bmp.getWidth();
        int h = bmp.getHeight();
        float k = Math.min(1f, (float) MAX_EDGE / Math.max(w, h));
        if (k < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    // ------------------------------------------------------------ 预览条

    private void renderPreview() {
        if (previewBar == null) return;
        previewBar.removeAllViews();
        if (picks.isEmpty()) { previewBar.setVisibility(GONE); return; }

        HorizontalScrollView hs = new HorizontalScrollView(host);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(host);
        strip.setOrientation(HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < picks.size(); i += 1) {
            final Pick p = picks.get(i);
            if (p.doc()) {
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    dp(144), dp(40));
                dlp.rightMargin = dp(Theme.S2);
                strip.addView(docCard(p), dlp);
            } else {
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(46), dp(46));
                clp.rightMargin = dp(Theme.S2);
                strip.addView(imageCell(p), clp);
            }
        }

        TextView count = new TextView(host);
        count.setText(countLabel());
        count.setTextSize(Theme.T_TINY);
        count.setTextColor(Theme.TEXT_DIM);
        strip.addView(count);

        hs.addView(strip);
        previewBar.addView(hs, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        previewBar.setVisibility(VISIBLE);
    }

    private String countLabel() {
        int imgs = imageCount();
        int docs = docCount();
        if (docs == 0) return imgs + "/" + MAX_PICK;
        if (imgs == 0) return docs + "/" + Uploads.MAX_DOCS;
        return imgs + "/" + MAX_PICK + " · " + docs + "/" + Uploads.MAX_DOCS;
    }

    /** 一张待发图：46dp 缩略图 + 右上角 ✕。 */
    private View imageCell(final Pick p) {
        FrameLayout cell = new FrameLayout(host);
        ImageView iv = new ImageView(host);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(Theme.round(Theme.SURFACE_2, dp(Theme.R_IMAGE - 4)));
        iv.setClipToOutline(true);
        setThumb(iv, p.path);
        cell.addView(iv, new FrameLayout.LayoutParams(dp(46), dp(46)));

        // 右上角的 ✕（16dp 圆心，压在缩略图上）
        FrameLayout x = new FrameLayout(host);
        x.setBackground(Theme.circle(Theme.SCRIM_DARK));
        ImageView xi = new ImageView(host);
        xi.setImageDrawable(Glyph.of(host, "close", 9, Theme.TEXT));
        FrameLayout.LayoutParams xilp = new FrameLayout.LayoutParams(dp(9), dp(9));
        xilp.gravity = Gravity.CENTER;
        x.addView(xi, xilp);
        x.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                picks.remove(p);
                renderPreview();
                pushToService();
                refreshSend();
            }
        });
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(16), dp(16));
        xlp.gravity = Gravity.TOP | Gravity.END;
        cell.addView(x, xlp);
        return cell;
    }

    /**
     * 一张文档卡：`144×40dp`，图标 + 文件名单行省略 + 大小/进度 + ✕，
     * 上传中底下一条 2dp 进度线；失败时边框转警示色，**点卡片 = 重试**。
     */
    private View docCard(final Pick p) {
        final boolean failed = p.state() == Uploads.ST_FAIL;
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(VERTICAL);
        card.setBackground(failed
            ? Theme.roundStroke(Theme.DANGER_SOFT, dp(9), Theme.DANGER, dp(1))
            : Theme.roundStroke(Theme.SURFACE_2, dp(9), Theme.LINE, dp(1)));
        card.setPadding(dp(8), dp(5), dp(6), dp(4));
        p.card = card;

        LinearLayout row = new LinearLayout(host);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ic = new ImageView(host);
        ic.setImageDrawable(Glyph.of(host, docGlyph(p), 15,
            failed ? Theme.DANGER : Theme.ACCENT));
        row.addView(ic, new LinearLayout.LayoutParams(dp(15), dp(15)));

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(VERTICAL);

        TextView name = new TextView(host);
        name.setText(p.job == null ? "" : p.job.name);
        name.setTextColor(Theme.TEXT);
        name.setTextSize(11);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);

        TextView status = new TextView(host);
        status.setText(p.job == null ? "" : p.job.statusText());
        status.setTextColor(failed ? Theme.DANGER : Theme.TEXT_DIM);
        status.setTextSize(9);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(0, dp(1), 0, 0);
        col.addView(status);
        p.status = status;

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(7);
        row.addView(col, clp);

        ImageView close = new ImageView(host);
        close.setImageDrawable(Glyph.of(host, "close", 10, Theme.TEXT_DIM));
        close.setPadding(dp(3), dp(3), dp(3), dp(3));
        close.setBackground(Theme.rippleCircle(Theme.RIPPLE));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (p.state() != Uploads.ST_OK) Uploads.cancel(p.job);
                picks.remove(p);
                renderPreview();
                pushToService();
                refreshSend();
            }
        });
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(16), dp(16));
        xlp.leftMargin = dp(4);
        row.addView(close, xlp);

        card.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ProgressBar bar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(p.pct());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        blp.topMargin = dp(3);
        card.addView(bar, blp);
        p.bar = bar;
        applyProgress(p);

        // 失败的卡整块可点 = 重试（✕ 在右上，两者不冲突）
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (p.state() != Uploads.ST_FAIL) return;
                refreshDoc(p);
                Uploads.retry(cfg, p.job, docCb(p));
                renderPreview();
                refreshSend();
            }
        });
        return card;
    }

    private String docGlyph(Pick p) {
        String e = Uploads.ext(p.job == null ? "" : p.job.name);
        if ("pdf".equals(e)) return "menu";
        if ("xlsx".equals(e) || "xls".equals(e) || "csv".equals(e)) return "grid";
        return "folder";
    }

    /** 就地改一张卡：状态文字 + 进度条 + 边框（不整条重画）。 */
    private void refreshDoc(Pick p) {
        if (p.card == null || p.status == null || p.bar == null) {
            renderPreview();
            return;
        }
        boolean failed = p.state() == Uploads.ST_FAIL;
        p.status.setText(p.job == null ? "" : p.job.statusText());
        p.status.setTextColor(failed ? Theme.DANGER : Theme.TEXT_DIM);
        p.card.setBackground(failed
            ? Theme.roundStroke(Theme.DANGER_SOFT, dp(9), Theme.DANGER, dp(1))
            : Theme.roundStroke(Theme.SURFACE_2, dp(9), Theme.LINE, dp(1)));
        applyProgress(p);
    }

    private void applyProgress(Pick p) {
        if (p.bar == null) return;
        boolean working = p.working();
        p.bar.setVisibility(working ? VISIBLE : GONE);
        if (working) p.bar.setProgress(p.pct());
    }

    private void setThumb(final ImageView iv, final String file) {
        iv.setTag(file);
        new Thread(new Runnable() {
            public void run() {
                BitmapFactory.Options probe = new BitmapFactory.Options();
                probe.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file, probe);
                int sample = 1;
                while (probe.outWidth / (sample * 2) >= 120) sample *= 2;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                final Bitmap b = BitmapFactory.decodeFile(file, o);
                if (b == null) return;
                ui.post(new Runnable() {
                    public void run() {
                        if (file.equals(iv.getTag())) iv.setImageBitmap(b);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ 状态同步

    private void pushToService() {
        EyeService s = EyeService.peek();
        if (s == null) return;
        List<String> paths = new ArrayList<>();
        List<byte[]> bytes = new ArrayList<>();
        for (Pick p : picks) {
            if (p.doc()) continue;                 // 文档不进服务（见类注释）
            paths.add(p.path);
            bytes.add(p.bytes);
        }
        s.stashPending(paths, bytes);
    }

    private void pullFromService() {
        EyeService s = EyeService.peek();
        if (s == null) return;
        List<String> paths = s.pendingPaths();
        List<byte[]> bytes = s.pendingBytes();
        if (paths.isEmpty() || paths.size() != bytes.size()) return;
        picks.clear();
        for (int i = 0; i < paths.size(); i += 1) picks.add(new Pick(paths.get(i), bytes.get(i)));
        renderPreview();
        refreshSend();
    }

    // ------------------------------------------------------------ 粘贴 / 语音

    /**
     * 输入框：**粘贴时如果剪贴板里是图片，直接进待发**。
     *
     * 比点「相册」再挑一遍快得多（截图 → 切回来 → 长按 → 粘贴，两步）。拦截点在
     * `onTextContextMenuItem(android.R.id.paste)` —— 这是系统粘贴菜单唯一会走的口子。
     */
    private final class PasteEdit extends EditText {
        PasteEdit(Context c) {
            super(c);
        }

        @Override
        public boolean onTextContextMenuItem(int id) {
            if (id == android.R.id.paste && tryPasteImage()) return true;
            return super.onTextContextMenuItem(id);
        }
    }

    private boolean tryPasteImage() {
        try {
            ClipboardManager cm = (ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return false;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return false;
            ClipData.Item it = clip.getItemAt(0);
            Uri uri = it == null ? null : it.getUri();
            if (uri == null) return false;
            String type = host.getContentResolver().getType(uri);
            boolean isImage = (type != null && type.startsWith("image/"))
                || (clip.getDescription() != null
                    && clip.getDescription().hasMimeType("image/*"));
            if (!isImage) return false;
            if (imageCount() >= MAX_PICK) { toast("最多 " + MAX_PICK + " 张"); return true; }
            addFromUri(uri);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 语音输入：系统的 `RecognizerIntent`（华为上是小艺），**一句话一次调用**。
     *
     * 这台机器上常驻 `SpeechRecognizer` 只会回 `ERROR_RECOGNIZER_BUSY`，别再试。
     * 没有引擎就如实说 —— 假按钮比没有按钮更糟。
     */
    private void speak() {
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, "说点什么");
            host.startActivityForResult(i, REQ_SPEECH);
        } catch (Throwable e) {
            toast("这台机器没有语音识别，打字吧");
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(host, s == null ? "" : s,
            android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
