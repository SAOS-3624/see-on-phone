package com.dsh.eye;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 输入栏：**预览条 + 52dp 胶囊容器**（相册 / 输入框 / 语音 / 发送）。
 *
 * 从 `MainActivity` 拆出来的（原来输入行、待发图、相册选择、语音识别、预览条
 * 全散在 1350 行里，状态在三个字段之间飘）。现在它自己管自己：
 *
 * ```
 * ┌────────────────────────────────────────┐
 * │ [缩略] [缩略] ✕              ← 预览条（没图时 GONE）│
 * ├────────────────────────────────────────┤
 * │ ( ▣ )  说点什么…            ( ◎ ) ( ➤ ) │  ← 52dp 胶囊
 * └────────────────────────────────────────┘
 * ```
 *
 * ## 待发图存在哪
 *
 * **存在 `EyeService` 里**（它有前台服务的生命周期），不是存在 Activity 字段里。
 * Activity 会因为切后台被回收、旋屏重建 —— 字段一丢，用户刚选好的四张图就没了。
 * 这里每次变化都同步一份过去，重建后 `pullFromService()` 还原。
 *
 * ## 相册多选
 *
 * `ACTION_OPEN_DOCUMENT` + `EXTRA_ALLOW_MULTIPLE`，**最多 4 张**（再多模型也看不过来，
 * 而且一次请求的 token 会翻倍）。走 SAF 所以**不需要任何存储权限**。
 */
final class ChatInputBar extends LinearLayout {

    /** 一张待发送的图：落盘路径（显示用）+ jpeg 字节（进 API 用）。 */
    static final class Pick {
        final String path;
        final byte[] bytes;
        Pick(String path, byte[] bytes) { this.path = path; this.bytes = bytes; }
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
    /** 一次最多带几张图。 */
    static final int MAX_PICK = 4;
    /** 相册图缩到长边 1568 / JPEG 85%（视觉接口的推荐上限，再大会被服务端压一遍）。 */
    private static final int MAX_EDGE = 1568;
    private static final int QUALITY = 85;

    private final Activity host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Pick> picks = new ArrayList<>();

    private LinearLayout previewBar;
    private EditText input;
    private FrameLayout sendBtn;
    private ImageView sendIcon;

    private SendListener sendL;
    private Runnable stopL;
    private Runnable focusL;
    private Runnable lookL;
    private boolean sending;

    ChatInputBar(Activity a) {
        super(a);
        this.host = a;
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

    void setText(String t) {
        if (input == null) return;
        input.setText(t == null ? "" : t);
        input.setSelection(input.getText().length());
        refreshSend();
    }

    String text() {
        return input == null ? "" : input.getText().toString().trim();
    }

    boolean hasImages() { return !picks.isEmpty(); }

    /** 待发图的落盘路径（`onSaveInstanceState` 用它兜一层，见 MainActivity）。 */
    List<String> imagePaths() {
        List<String> out = new ArrayList<>();
        for (Pick p : picks) out.add(p.path);
        return out;
    }

    int imageCount() { return picks.size(); }

    /** 清空（发送成功后调）。 */
    void clear() {
        if (input != null) input.setText("");
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
        if (picks.size() >= MAX_PICK) { toast("最多 " + MAX_PICK + " 张"); return; }
        for (Pick p : picks) if (file.equals(p.path)) return;
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

        // 拍一张（现场看）——故意排在相册前面：它是"现在就看"，比"选一张旧图"常用
        ImageView shot = icon("camera");
        shot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (lookL != null) lookL.run();
            }
        });
        box.addView(shot, new LinearLayout.LayoutParams(dp(36), dp(36)));

        // 相册
        ImageView pic = icon("image");
        pic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pick(); }
        });
        box.addView(pic, new LinearLayout.LayoutParams(dp(36), dp(36)));

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

    // ------------------------------------------------------------ 发送键状态

    /** 空输入时：暗淡、不可点。有内容：渐变 + 亮图标。流式中：变停止键。 */
    private void refreshSend() {
        if (sendBtn == null) return;
        boolean hasText = input != null && input.getText().toString().trim().length() > 0;
        boolean armed = sending || hasText || !picks.isEmpty();
        if (sending) {
            sendBtn.setBackground(Theme.gradient(512f, Theme.GRAD[0], Theme.GRAD[1]));
            sendIcon.setImageDrawable(Glyph.of(host, "stop", 14, Theme.WHITE));
        } else if (armed) {
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
        String t = text();
        if (t.length() == 0 && picks.isEmpty()) return;
        if (sendL == null) return;
        List<Pick> send = new ArrayList<>(picks);
        if (sendL.onSend(t, send)) clear();
    }

    private void voiceSend(String t) {
        if (sendL == null || t == null || t.trim().length() == 0) return;
        // 语音**不带你正在选的图**（"我说的话 + 我刚选的四张图"语义很怪），
        // 也不清空已选的图 —— 识别出来的这句话和那几张图是两件事。
        sendL.onSend(t.trim(), new ArrayList<Pick>());
    }

    // ------------------------------------------------------------ 相册

    private void pick() {
        if (picks.size() >= MAX_PICK) { toast("最多 " + MAX_PICK + " 张，先发一批"); return; }
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
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i += 1) {
                ClipData.Item it = cd.getItemAt(i);
                if (it != null && it.getUri() != null) uris.add(it.getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int room = MAX_PICK - picks.size();
        if (uris.size() > room) {
            toast("一次最多 " + MAX_PICK + " 张，多的没加");
            uris = uris.subList(0, room);
        }
        for (Uri u : uris) addFromUri(u);
    }

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

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(46), dp(46));
            clp.rightMargin = dp(Theme.S2);
            strip.addView(cell, clp);
        }

        TextView count = new TextView(host);
        count.setText(picks.size() + "/" + MAX_PICK);
        count.setTextSize(Theme.T_TINY);
        count.setTextColor(Theme.TEXT_DIM);
        strip.addView(count);

        hs.addView(strip);
        previewBar.addView(hs, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        previewBar.setVisibility(VISIBLE);
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
        for (Pick p : picks) { paths.add(p.path); bytes.add(p.bytes); }
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
            if (picks.size() >= MAX_PICK) { toast("最多 " + MAX_PICK + " 张"); return true; }
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
