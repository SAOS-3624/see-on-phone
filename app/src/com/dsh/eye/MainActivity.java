package com.dsh.eye;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话页 —— 「鱼眼」的主界面。
 *
 * ```
 * ☰  会话名 ~~模型·思考·上下文~~  ▶ ⚙     ← 56dp 顶栏（TopBar）
 *                 你说的话 ▊             ← 用户气泡：渐变 + 白字
 * ▊ 它说的话                              ← 助手气泡：卡片 + 描边
 * [思考 2.0s · 点开看过程]  [图]          ← 思考 chip + 缩略图
 * ● 正在看 3 帧…                          ← 工具胶囊（ToolChip）
 * ( ▣ ) 说点什么…        ( ◎ ) ( ↥ )      ← 52dp 胶囊输入栏（ChatInputBar）
 * ```
 *
 * ## 这个文件只做调度
 *
 * 上一版 1356 行，界面和业务混在一起。这一版把"长什么样"的活全分出去了：
 *
 * | 去哪了 | 管什么 |
 * |---|---|
 * | {@link ChatInputBar} | 输入行、预览条、相册多选、粘贴板图、语音 |
 * | {@link Bubbles} | 一条消息长什么样（气泡/图/思考 chip/重试） |
 * | {@link TopBar} / {@link EmptyView} / {@link ToolChip} | 顶栏 / 空态 / 工具胶囊 |
 * | {@link Sheets} | 底部动作栏、全屏看图、长文本、保存到相册 |
 * | {@link Theme} / {@link Glyph} / {@link Anim} | 颜色、图标、动效 |
 *
 * 剩下的就是：**谁在什么时候该出现**、用户的一个动作该走哪条路。
 *
 * ## 数据从哪来（这条一直没变）
 *
 * 落盘只有服务做（`ConvStore`），界面**只读磁盘 + 接事件**（进页面 `messages()` 重铺、
 * 新回答走 `Ui.onReply`），否则"同一句话既在内存又在文件里，重进就重复"。
 */
public class MainActivity extends AppActivity implements EyeService.Ui, Bubbles.Cb {

    private static final int REQ_PERM = 1;
    private static final int REQ_STORAGE = 2;
    /** 一屏最多铺多少条（再多没人往回翻，但每条都要建视图 + 解码）。 */
    /** 离底部超过这么多 dp 才出现「回到底部」按钮。 */
    private static final int MAX_RENDER = 60;
    private static final int FAB_THRESHOLD = 240;

    /**
     * 一次发送的全部输入。
     *
     * 上一版有**三个入口**（`send(t)` / `send(t,img,frames)` / `sendWithImage(t,img)`），
     * 语义靠记：`send(t,img)` 的 img **不进 API**，只有 `sendWithImage` 进。
     * 这就是交接文档点名的"容易搞混"（也就有了那个"发相册图它却去开相机"的 bug）。
     * 现在只有一个 `send(Out)`：`images` 非空就等于"要真的发给模型看"。
     */
    static final class Out {
        final String text;
        /** 进 API 的图片字节；空 = 纯文字（相机那条路由模型自己调 look）。 */
        final List<byte[]> images;
        /** 落盘路径（气泡里显示的那几张）。 */
        final List<String> files;

        Out(String text, List<byte[]> images, List<String> files) {
            this.text = text == null ? "" : text;
            this.images = images == null ? new ArrayList<byte[]>() : images;
            this.files = files == null ? new ArrayList<String>() : files;
        }

        Out(String text) { this(text, null, null); }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TopBar topBar;
    private LinearLayout box;
    private ScrollView scroll;
    private View fab;

    private ToolChip toolChip;

    private ChatInputBar bar;
    private Bubbles bubbles;

    /** "正在想…"那条临时气泡（拿到回答就换掉）。 */
    private View thinkingRow;
    /** 流式输出那条**正在长**的气泡（状态机在 {@link Bubbles.Stream} 里）。 */
    private Bubbles.Stream stream;
    private boolean streaming;
    /** 失败气泡（重试时先把它摘掉，不然原地堆两条）。 */
    private View failedRow;

    /** 空态铺没铺在 box 里（第一次发消息要清掉）；这一屏有没有真铺过内容。 */
    private boolean emptyShown;
    private boolean loaded;
    /** 上一次发出去的东西（失败气泡的「重试」要它）。 */
    private Out lastOut;
    /** 这一轮已经在界面上显示过的图（避免用户气泡和助手气泡各显示一遍）。 */
    private final Set<String> shownShots = new HashSet<String>();

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        // 待发图和草稿各兜一层：图的主存放在 EyeService（前台服务活得比界面长），
        // 这里再从 bundle 补一次 —— 服务被系统杀掉重建的那一下，两者都能救回来。
        if (b != null) {
            String draft = b.getString("draft");
            if (draft != null && draft.length() > 0) bar.setText(draft);
            List<String> shots = b.getStringArrayList("shots");
            if (shots != null) for (String f : shots) bar.addImage(f);
        }
        if (!hasCamera()) {
            askCorePerms(REQ_PERM);
        } else {
            startEye();
            ensureStorage(REQ_STORAGE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        if (bar == null) return;
        b.putString("draft", bar.text());
        b.putStringArrayList("shots", new ArrayList<String>(bar.imagePaths()));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startEye();
            else toast("没有相机权限：它会看不见东西（其它功能照常）");
            for (int i = 0; i < perms.length; i += 1) {
                if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(perms[i])
                    && res[i] != PackageManager.PERMISSION_GRANTED) {
                    toast("存储权限没给：会话和图都存不下来");
                }
            }
        } else if (code == REQ_STORAGE) {
            if (res.length > 0 && res[0] != PackageManager.PERMISSION_GRANTED) {
                toast("存储权限没给：会话和图都存不下来");
            }
        }
    }

    private void startEye() {
        try {
            startService(new Intent(this, EyeService.class));
        } catch (Throwable e) {
            toast("启动失败：" + e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loaded = false;
        bind();
        // 服务是异步起的：onCreate 里 startService 之后 onResume 立刻就来，
        // 这时候 peek() 往往还是 null —— 800ms / 2000ms 后再挂两次。
        ui.postDelayed(new Runnable() { public void run() { bind(); } }, 800);
        ui.postDelayed(new Runnable() { public void run() { bind(); } }, 2000);
    }

    @Override
    protected void onPause() {
        EyeService s = EyeService.peek();
        if (s != null) s.setUi(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (bubbles != null) bubbles.shutdown();
        super.onDestroy();
    }

    private void bind() {
        EyeService s = EyeService.peek();
        if (s == null) return;
        s.setUi(this);
        onState();
        if (!loaded) { loaded = true; reload(); }
    }

    // ---------------------------------------------------------------- 界面骨架

    private void buildUi() {
        immersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new TopBar(this, new TopBar.Cb() {
            public void onMenu() { openConvList(); }
            public void onVideo() { openCall(); }
            public void onGear() { openSettings(); }
        });
        col.addView(topBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 消息区 + 回到底部 ----
        FrameLayout mid = new FrameLayout(this);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S3));
        scroll.addView(box, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mid.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fab = buildFab();
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(40), dp(40));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = dp(Theme.S4);
        flp.bottomMargin = dp(Theme.S3);
        mid.addView(fab, flp);
        col.addView(mid, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        toolChip = new ToolChip(this);
        col.addView(toolChip, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ChatInputBar(this);
        bar.setOnSend(new ChatInputBar.SendListener() {
            public boolean onSend(String text, List<ChatInputBar.Pick> picks) {
                return send(fromPicks(text, picks));
            }
        });
        bar.setOnStop(new Runnable() { public void run() { stopStreaming(); } });
        bar.setOnFocus(new Runnable() { public void run() { scrollBottom(); } });
        bar.setOnLook(new Runnable() { public void run() { sendLook(); } });
        col.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bubbles = new Bubbles(this, this);
        stream = bubbles.stream(box);

        scroll.getViewTreeObserver().addOnScrollChangedListener(
            new android.view.ViewTreeObserver.OnScrollChangedListener() {
                public void onScrollChanged() { updateFab(); }
            });

        root.setFocusableInTouchMode(true);
        root.requestFocus();
        setContentView(root);
        // 沉浸式会让 adjustResize 失效（见 AppActivity.keepAboveKeyboard 的注释）
        keepAboveKeyboard(root);
    }

    /** 「↓ 回到底部」：离开底部超过 240dp 才出现。 */
    private View buildFab() {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Theme.roundStroke(Theme.SURFACE, 512f, Theme.LINE, dp(1)));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(Glyph.of(this, "chevron", 18, Theme.TEXT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(18), dp(18));
        lp.gravity = Gravity.CENTER;
        f.addView(iv, lp);
        f.setVisibility(View.GONE);
        f.setAlpha(0f);
        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { scrollBottom(); }
        });
        return f;
    }

    private void updateFab() {
        if (fab == null || box == null || scroll == null) return;
        int dist = box.getBottom() - scroll.getHeight() - scroll.getScrollY();
        boolean far = dist > dp(FAB_THRESHOLD);
        if (far) {
            if (fab.getVisibility() == View.VISIBLE) return;
            fab.setVisibility(View.VISIBLE);
            fab.animate().alpha(1f).setDuration(160).start();
        } else {
            if (fab.getVisibility() != View.VISIBLE) return;
            fab.animate().alpha(0f).setDuration(140).withEndAction(new Runnable() {
                public void run() {
                    if (fab.getAlpha() < 0.05f) fab.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    // ---------------------------------------------------------------- 空态

    private void showEmpty() {
        emptyShown = true;
        box.addView(new EmptyView(this, new EmptyView.Cb() {
            public void onSuggest(String label) {
                bar.setText(label);
                bar.focus();
            }
        }), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void clearEmpty() {
        if (!emptyShown) return;
        emptyShown = false;
        box.removeAllViews();
    }

    // ---------------------------------------------------------------- 重铺

    /** 从磁盘重铺一遍（进页面、切会话、清空、重发之后都调）。 */
    private void reload() {
        if (box == null) return;
        box.removeAllViews();
        thinkingRow = null;
        failedRow = null;
        emptyShown = false;
        endStream();
        setStreaming(false);
        if (toolChip != null) toolChip.hide(null);
        shownShots.clear();
        loaded = true;

        EyeService s = EyeService.peek();
        if (s == null) {
            hint("服务还没起来…");
            loaded = false;
            return;
        }
        List<Conv.Msg> msgs = s.messages();
        if (msgs.isEmpty()) {
            showEmpty();
            return;
        }
        bubbles.render(box, msgs, MAX_RENDER);
        scrollBottom();
    }

    private void hint(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Theme.TEXT_DIM);
        v.setTextSize(14);
        v.setLineSpacing(dp(4), 1f);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(Theme.S5), dp(60), dp(Theme.S5), 0);
        box.addView(v);
    }

    private void scrollBottom() {
        ui.post(new Runnable() {
            public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    // ---------------------------------------------------------------- 发送

    private Out fromPicks(String text, List<ChatInputBar.Pick> picks) {
        List<byte[]> images = new ArrayList<byte[]>();
        List<String> files = new ArrayList<String>();
        if (picks != null) {
            for (ChatInputBar.Pick p : picks) {
                if (p == null) continue;
                if (p.bytes != null) images.add(p.bytes);
                if (p.path != null) files.add(p.path);
            }
        }
        return new Out(text, images, files);
    }

    /** **唯一的发送入口**。三个来源（打字 / 语音 / 相册）都收敛到这里。 */
    private boolean send(Out o) {
        if (o == null) return false;
        String t = o.text == null ? "" : o.text.trim();
        boolean has = o.images != null && !o.images.isEmpty();
        if (t.length() == 0 && !has) return false;

        EyeService s = EyeService.peek();
        // 服务没起来时**别清输入栏** —— 让他等两秒还能接着发
        if (s == null) { toast("服务还没起来，稍等一下"); return false; }

        EyeLog.w("send", "imgs=" + (has ? o.images.size() : 0) + " text=" + t.length());
        clearEmpty();
        endStream();
        setStreaming(true);
        lastOut = o;
        for (String f : o.files) shownShots.add(f);
        box.addView(bubbles.user(t, o.files, 0));
        showThinking("正在想…");
        if (has) s.sendWithImages(t, o.images);
        else s.send(t);
        scrollBottom();
        return true;
    }

    private void showThinking(String label) {
        removeThinking();
        thinkingRow = bubbles.thinking(label);
        box.addView(thinkingRow);
        scrollBottom();
    }

    private void removeThinking() {
        if (thinkingRow != null) {
            box.removeView(thinkingRow);
            thinkingRow = null;
        }
    }

    // ---------------------------------------------------------------- 流式

    private void setStreaming(boolean on) {
        streaming = on;
        if (bar != null) bar.setSending(on);
    }

    private void stopStreaming() {
        EyeService s = EyeService.peek();
        if (s != null) s.cancelTurn();
        // 正常情况：服务把"已收到的部分 +（已停止）"落盘后推 onReply，这里不用收尾。
        // 但万一那条回包没到（服务被打断在别的分支上），按钮不能永远停在 ■ —— 兜一个超时。
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!streaming) return;
                endStream();
                setStreaming(false);
                removeThinking();
            }
        }, 4000);
    }

    /** 第一条增量到了：把"正在想…"换成一条会长大的气泡。 */
    private void beginStream() {
        if (stream.active()) return;
        removeThinking();
        stream.begin();
        scrollBottom();
    }

    private void endStream() {
        stream.end();
    }

    /**
     * 「拍一张」：输入栏那个相机按钮。
     *
     * 和打字发送的区别：这里**先由界面铺一条用户气泡**（"这是什么？"），再让服务去抓帧 ——
     * `EyeService.lookNow()` 内部就是 `send("这是什么？", jpeg, frames)`，但它不经过
     * `MainActivity.send()`，所以少了这一条气泡；不补的话，抓完图界面上只有它的回答，
     * 用户看不到"我问了什么"。
     */
    private void sendLook() {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来，稍等一下"); return; }
        clearEmpty();
        endStream();
        setStreaming(true);
        box.addView(bubbles.user("这是什么？", null, 0));
        showThinking("正在看一眼…");
        lastOut = null;                 // 抓帧失败重试 = 再点一次相机，不是重发这句话
        s.lookNow();
        scrollBottom();
    }

    @Override
    public void onDelta(final String text) {
        ui.post(new Runnable() {
            public void run() {
                if (!streaming) setStreaming(true);
                if (!stream.active()) beginStream();
                stream.append(text);
                scrollBottom();
            }
        });
    }

    @Override
    public void onQuiet() {
        ui.post(new Runnable() {
            public void run() {
                endStream();
                setStreaming(false);
                removeThinking();
                toolChip.hide(null);
            }
        });
    }

    @Override
    public void onRoundReset() {
        ui.post(new Runnable() {
            public void run() {
                endStream();
                showThinking("正在想…");
            }
        });
    }

    // ---------------------------------------------------------------- EyeService.Ui

    @Override
    public void onTool(String name, String phase, String detail) {
        final String line = detail == null ? "" : detail;
        final boolean running = "running".equals(phase);
        ui.post(new Runnable() {
            public void run() {
                if (running) toolChip.show(line);
                else toolChip.hide(line);
            }
        });
    }

    @Override
    public void onReply(final String text, final String reasoning, final long thinkMs,
                        final List<String> imageFiles, final long at) {
        ui.post(new Runnable() {
            public void run() {
                /**
                 * ⚠️ 顺序在这里很要紧（P0：真机上丢过最后一段字）。
                 *
                 * `stream.finish(text)` 先用**落盘那份全文**同步覆盖正在显示的气泡，
                 * 然后才拆掉它、再按同一份全文建一条正式气泡。两条路用的都是 `text`，
                 * 所以"气泡里的字 == 磁盘里那行的字"是结构性的，不靠时序运气。
                 */
                // 对账：streamed 是流式累计的，authoritative 是落盘那份。两个数不一致
                // 就说明增量有丢失（这才是真 bug），日志里一眼能看出来。
                EyeLog.w("ui", "reply streamed=" + stream.chars()
                    + " authoritative=" + (text == null ? 0 : text.length()));
                stream.finish(text);
                endStream();
                setStreaming(false);
                removeThinking();
                toolChip.hide(null);
                EyeLog.w("ui", "reply chars=" + (text == null ? 0 : text.length())
                    + " thinkMs=" + thinkMs + " at=" + at);
                // 这一轮刚发出去时已经在用户气泡下面显示过的图，别在回答下面再来一遍
                List<String> fresh = new ArrayList<String>();
                if (imageFiles != null) {
                    for (String f : imageFiles) if (!shownShots.contains(f)) fresh.add(f);
                }
                shownShots.clear();
                box.addView(bubbles.ai(text, reasoning, thinkMs, fresh, at, false));
                scrollBottom();
            }
        });
    }

    @Override
    public void onError(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                endStream();
                setStreaming(false);
                removeThinking();
                toolChip.hide(null);
                failedRow = bubbles.ai("（没答上：" + msg + "）", null, 0, null, 0, true);
                box.addView(failedRow);
                scrollBottom();
            }
        });
    }

    @Override
    public void onState() {
        ui.post(new Runnable() {
            public void run() {
                EyeService s = EyeService.peek();
                if (s == null) return;
                topBar.setTitle(s.convTitle());
                topBar.setSubtitle(subLine());
            }
        });
    }

    /** 通话页的缓冲进度：会话页不显示，忽略。 */
    @Override
    public void onFrames(int have, int need) { }

    /** 顶栏第二行：`模型 · 思考档 · 上下文`。 */
    private String subLine() {
        try {
            Config c = new Config(this);
            return c.apiModel().replace("deepseek-", "")
                + " · 思考 " + c.thinkLabel() + " · " + Config.ctxLabel(c.ctxTokens());
        } catch (Throwable e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- Bubbles.Cb

    @Override
    public void onBubbleLong(boolean mine, final String text, final long at) {
        String[] items = mine
            ? new String[] { "复制", "重发", "删这段" }
            : new String[] { "复制", "朗读", "重发", "删这段" };
        Sheets.menu(this, items, "删这段", new Sheets.OnPick() {
            public void onPick(String pick) {
                if ("复制".equals(pick)) { copy(text); return; }
                if ("朗读".equals(pick)) {
                    EyeService svc = EyeService.peek();
                    if (svc != null) svc.say(text);
                    return;
                }
                if ("重发".equals(pick)) { retry(at); return; }
                if ("删这段".equals(pick)) { doDelete(at); }
            }
        });
    }

    @Override
    public void onImageTap(String file) {
        android.graphics.Bitmap b = bubbles.loadThumb(file);
        if (b == null) { toast("这张图读不出来了"); return; }
        Sheets.image(this, b);
    }

    @Override
    public void onImageLong(final String file, final String text) {
        String[] items = { "再问一句", "复制文字", "保存到相册" };
        Sheets.menu(this, items, null, new Sheets.OnPick() {
            public void onPick(String pick) {
                if ("再问一句".equals(pick)) {
                    bar.addImage(file);
                    bar.focus();
                    toast("图已放进输入框，说你想问的");
                } else if ("复制文字".equals(pick)) {
                    copy(text == null || text.length() == 0 ? "（这条只有图）" : text);
                } else {
                    Sheets.toGallery(MainActivity.this, file);
                }
            }
        });
    }

    @Override
    public void onReasoning(String reasoning) {
        Sheets.longText(this, "它当时在想什么", reasoning);
    }

    @Override
    public void onRetry(long at) {
        retry(at);
    }

    // ------------------------------------------- 重发 / 删除 / 复制

    /** 「重发」／失败气泡下面的「重试」。 */
    private void retry(long at) {
        if (at <= 0) {
            // 失败气泡：把它摘掉，原样再发一次（不新增用户气泡）
            if (failedRow != null) { box.removeView(failedRow); failedRow = null; }
            Out o = lastOut;
            if (o == null) { toast("再打一次吧"); return; }
            EyeService s = EyeService.peek();
            if (s == null) { toast("服务还没起来"); return; }
            setStreaming(true);
            showThinking("正在想…");
            if (o.images != null && !o.images.isEmpty()) s.sendWithImages(o.text, o.images);
            else s.send(o.text);
            return;
        }
        EyeService s = EyeService.peek();
        if (s == null) return;
        String text = s.prepareRetry(at);
        if (text == null) { toast("这条对不上了，重开一下再试"); return; }
        if (text.trim().length() == 0) {
            toast("这条只有图，重发不了；长按那张图选「再问一句」");
            return;
        }
        reload();
        send(new Out(text));
    }

    private void doDelete(long at) {
        EyeService s = EyeService.peek();
        if (s == null) return;
        if (s.deleteTurn(at)) { reload(); toast("删了"); }
        else toast("没删成（对不上了）");
    }

    private void copy(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("鱼眼", text == null ? "" : text));
            toast("复制了");
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------- 跳转 / 结果

    private void openConvList() {
        try {
            startActivity(new Intent(this, ConvActivity.class));
        } catch (Throwable e) {
            toast("打不开会话列表：" + e.getMessage());
        }
    }

    private void openCall() {
        try {
            startActivity(new Intent(this, CallActivity.class));
        } catch (Throwable e) {
            toast("打不开通话页：" + e.getMessage());
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Throwable e) {
            toast("打不开设置：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (bar != null) bar.onActivityResult(req, res, data);
    }
}
