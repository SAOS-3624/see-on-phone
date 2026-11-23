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
     * 服务端那侧也收成一个壳了（`EyeService.Turn`），`attach` 才决定图进不进 API。
     */
    static final class Out {
        final String text;
        /** 进 API 的图片字节；空 = 纯文字（相机那条路由模型自己调 look）。 */
        final List<byte[]> images;
        /** 落盘路径（气泡里显示的那几张）。 */
        final List<String> files;
        /**
         * 这一轮带的**文档附件**（已上传成功、有 `file_id` 的那些）。
         *
         * 和 `images` 分开放的：图走 base64 内联，文档走 `{"type":"file","file_id":…}`
         * 引用，两条完全不同的请求形状 —— 混在一个 list 里只会让下游到处判类型。
         */
        List<Conv.Att> atts;

        Out(String text, List<byte[]> images, List<String> files) {
            this.text = text == null ? "" : text;
            this.images = images == null ? new ArrayList<byte[]>() : images;
            this.files = files == null ? new ArrayList<String>() : files;
        }

        Out(String text) { this(text, null, null); }

        /** 有没有真的带内容（只发一个 PDF 也必须算"有内容"）。 */
        boolean hasBody() {
            return !images.isEmpty() || (atts != null && !atts.isEmpty());
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TopBar topBar;
    private LinearLayout box;
    private ScrollView scroll;
    private View fab;

    private ToolChip toolChip;

    /**
     * 输入框上方那一排能力胶囊（🛰 联网 / 🧠 自动 / ⚙ Agent）。
     *
     * <p>**三态互斥由它保证**（`Caps.Mode` 是同一个枚举），落盘在 `Config`，
     * 界面只做"读出来 → 显示 → 用户点了 → 存回去"。
     */
    private CapRow caps;

    private ChatInputBar bar;
    private Bubbles bubbles;

    /** 这一轮的过程卡（联网 → 搜索卡；Agent → 步骤条）。同一时刻最多一张。 */
    private Cards.SearchCard searchCard;
    private Cards.Steps stepsCard;
    /** 这一轮发出去时的模式与档位（**发送那一刻**的快照，中途改胶囊不影响本轮）。 */
    private Caps.Mode sendingMode = Caps.Mode.CHAT;
    private String sendingEffort = Caps.EFF_AUTO;
    /**
     * 这一轮发出去时**联网开着吗**。
     *
     * <p>必须单独记：`sendingMode == AGENT` 时它可能是"纯 Agent"也可能是"混合"，
     * 一个枚举分不出来。混合模式 = `sendingMode == AGENT && sendingWeb`。
     */
    private boolean sendingWeb;
    /**
     * 这一轮的过程卡已经收尾了。
     *
     * <p>收尾之后再来的步骤事件**不许再建卡** —— 否则一条迟到的 `call_completed`
     * 会在答案下面凭空长出一张新的"进行中"卡片，永远不结束。
     */
    private boolean roundClosed = true;

    /**
     * 刚收到一条"拒答"回答，正在等紧随其后的 `onError`（它带官方文案）。
     *
     * <p>用来保证**同一个拒答只出现一条提示**：`onReply` 先留白，`onError` 到了就由它说，
     * 600ms 没到才自己补。
     */
    private boolean pendingRefuse;

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
    /** 最近一轮的完整结果（用量弹窗用）。 */
    private Proto.Reply lastReply;
    /**
     * 最后一条**用户气泡**。
     *
     * 图属于发出去的那一条（磁盘上 `Msg.shots` 也只挂在 user 上），而通话页那张宫格是
     * 服务端现拼的 —— 得等它落盘之后补进这个气泡。以前是反过来挂在回答下面
     * （`shownShots` 那套"没显示过才挂"的补丁），结果同一张图界面/重进各出现一次。
     */
    private Bubbles.UserBubble lastUser;

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
        // 从设置页换了模型/开关回来：文件入口的死活要跟着变（pro 不能读文件）
        refreshDocGate();
        refreshUsage(null);
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
        syncCaps();
        onState();
        if (!loaded) { loaded = true; reload(); }
    }

    // ---------------------------------------------------------------- 能力胶囊

    /**
     * 把 `Config` 里记的两个开关与档位推进胶囊（进页面、切会话、服务起来之后都要同步一次）。
     *
     * <p>**两个开关都要读**：只读 `modeOf` 会把混合模式读成"纯 Agent"（联网那个开丢掉了），
     * 于是重进会话时界面上少亮一个胶囊、请求也不带搜索，用户会以为设置丢了。
     */
    private void syncCaps() {
        if (caps == null) return;
        try {
            Config c = new Config(this);
            String id = c.convId();
            caps.setCaps(c.webOf(id), c.modeOf(id) == Caps.Mode.AGENT);
            caps.setEffort(c.think());
        } catch (Throwable e) {
            EyeLog.w("ui", "读模式失败 " + e);
        }
    }

    /**
     * 用户改了联网 / Agent 的开关。
     *
     * <p>**只做三件事**：按会话存进 `Config`（两个键：`mode` + `web`）、刷新顶栏副标题、
     * 重算文件入口的死活。真正的请求形状由 `Caps.plan(...)` 在发送时决定 ——
     * 界面绝不在这里写"联网就带什么工具"这种判断（ARCH §9 第 2 条）。
     *
     * <p>为什么 mode 与 web 要**同时**写：`mode` 只有三档（聊/联/Agent），表达不了混合；
     * `web` 是那个多出来的布尔。两者不一起写就会出现"mode=AGENT 但 web 丢了"的半截状态。
     */
    private void applyCaps(boolean web, boolean agent) {
        try {
            Config c = new Config(this);
            String id = c.convId();
            c.setMode(id, agent ? Caps.Mode.AGENT : (web ? Caps.Mode.WEB : Caps.Mode.CHAT));
            c.setWeb(id, web);
        } catch (Throwable e) {
            EyeLog.w("ui", "存模式失败 " + e);
        }
        if (topBar != null) topBar.setSubtitle(subLine());
        // 切到"不能读文件"的模式/模型 → 文件入口立刻置灰（别让用户白传 100MB 再被拒）
        refreshDocGate();
    }

    /** 用户切了档位（全局偏好，不按会话记）。 */
    private void applyEffort(String e) {
        try {
            Config c = new Config(this);
            c.setThink(e);
        } catch (Throwable ex) {
            EyeLog.w("ui", "存档位失败 " + ex);
        }
        if (topBar != null) topBar.setSubtitle(subLine());
    }

    /** 当前会话 id（胶囊/卡片的偏好都按它记）。 */
    private String convId() {
        try {
            return new Config(this).convId();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 给 T4（附件入口）用的只读把手。
     *
     * <p>联网模式下**文档入口要置灰**、并且拦在"上传之前"（别让用户白传 100MB
     * 再被 `Caps.plan` 拒掉）。`ChatInputBar` 需要知道当前模式，通过这里读。
     */
    CapRow capsRow() {
        return caps;
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
            public void onSpeak() { toggleSpeak(); }
            public void onUsage() { openUsage(); }
        });
        topBar.setSpeak(new Config(this).speak());
        refreshUsage(null);
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

        /**
         * 能力胶囊行：插在 `ToolChip` 之下、输入栏之上（PRD §6.1 的线框）。
         *
         * 为什么不放在 `ToolChip` 上面：那条是"正在干活"的状态条，离输入栏越近越容易
         * 被当成按钮；反过来，胶囊是**这一轮怎么发**的开关，紧贴输入栏才符合手指的预期。
         */
        caps = new CapRow(this, new CapRow.Cb() {
            public void onCaps(boolean web, boolean agent) { applyCaps(web, agent); }
            public void onEffort(String e) { applyEffort(e); }
        });
        col.addView(caps, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar = new ChatInputBar(this);
        bar.setOnSend(new ChatInputBar.SendListener() {
            public boolean onSend(String text, List<ChatInputBar.Pick> picks) {
                return send(fromPicks(text, picks));
            }
        });
        bar.setOnStop(new Runnable() { public void run() { stopStreaming(); } });
        bar.setOnFocus(new Runnable() { public void run() { scrollBottom(); } });
        bar.setOnLook(new Runnable() { public void run() { openCamera(); } });
        col.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshDocGate();          // 第一次：按当前模式/模型把文件入口定好死活

        bubbles = new Bubbles(this, this);
        /**
         * 角标的去处：点一下 → 底部来源面板；长按 → 直接把 URL 放进剪贴板。
         *
         * 回调里拿到的是**那条消息的完整引用列表**（Span 自己抱着它），所以历史消息
         * 重新铺出来之后照样能点开「来源 2 / 4」—— 界面不需要维护"哪条消息配哪份引用"。
         */
        bubbles.citeCb(new CiteSpans.Cb() {
            public void onCite(Cites.Cite c, List<Cites.Cite> all) {
                if (c == null) return;
                Sheets.sources(MainActivity.this, all, c.ordinal);
            }

            public void onCiteLong(Cites.Cite c) {
                if (c == null || c.url == null || c.url.trim().length() == 0) return;
                copy(c.url.trim());
            }
        });
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
        // 卡片是"这一轮的过程"，重铺之后它们由磁盘里的 `Msg.steps` 重建
        searchCard = null;
        stepsCard = null;
        if (caps != null) caps.setChipEnabled(true);
        syncCaps();
        lastUser = null;                 // 这一屏重铺了：老把手已经不在视图树里
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
        /**
         * 历史卡片**只读重建**：`Msg.steps` 那行摘要 + `Msg.mode` 决定画 🔍 还是 ⚙。
         * 重新算一遍耗时/步数是不允许的 —— 那会和当时屏幕上看到的不一样。
         */
        bubbles.render(box, msgs, MAX_RENDER, new Bubbles.CardFor() {
            public View card(Conv.Msg m) {
                return Cards.history(MainActivity.this, m.mode, m.steps);
            }
        });
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
        List<Conv.Att> atts = new ArrayList<Conv.Att>();
        if (picks != null) {
            for (ChatInputBar.Pick p : picks) {
                if (p == null) continue;
                // 文档的 bytes/path 都是 null，两个判断天然互斥：
                // 图进 images，文档进 atts，**绝不会混**
                if (p.bytes != null) images.add(p.bytes);
                if (p.path != null) files.add(p.path);
                if (p.att != null) atts.add(p.att);
            }
        }
        Out o = new Out(text, images, files);
        o.atts = atts;
        return o;
    }

    /** **唯一的发送入口**。三个来源（打字 / 语音 / 相册）都收敛到这里。 */
    private boolean send(Out o) {
        if (o == null) return false;
        String t = o.text == null ? "" : o.text.trim();
        // ⚠️ 只发一个 PDF、不打字，也必须能发出去 —— 否则输入栏不清空、什么也不发生
        boolean has = o.hasBody();
        if (t.length() == 0 && !has) return false;

        EyeService s = EyeService.peek();
        // 服务没起来时**别清输入栏** —— 让他等两秒还能接着发
        if (s == null) { toast("服务还没起来，稍等一下"); return false; }

        int docs = o.atts == null ? 0 : o.atts.size();
        EyeLog.w("send", "imgs=" + o.images.size() + " docs=" + docs + " text=" + t.length());
        clearEmpty();
        endStream();
        setStreaming(true);
        beginRound();
        lastOut = o;
        lastUser = bubbles.userBubble(t, o.files, 0);
        box.addView(lastUser.view);
        showThinking(thinkingLabel());
        if (has) s.send(t, o.images, o.atts);
        else s.send(t);
        scrollBottom();
        return true;
    }

    /**
     * 「正在…」那一条的文案：**四态**（混合 / 联网 / Agent / 聊天）。
     *
     * <p>混合模式必须单独说一句"查 + 动手"：只说"正在动手"用户不知道它在搜，
     * 只说"正在查"又解释不了为什么后面会连着好几个步骤。
     */
    private String thinkingLabel() {
        if (sendingMode == Caps.Mode.AGENT) {
            return sendingWeb ? "正在查 + 动手…" : "正在动手…";
        }
        if (sendingMode == Caps.Mode.WEB) return "正在查…";
        return "正在想…";
    }

    /**
     * 附件入口的闸：**当前模式/模型能不能带文档**，灌给 `ChatInputBar`。
     *
     * 三处调用（`bar` 建好、切模式后、`onResume` —— 从设置页改完模型回来要刷新）。
     * 判断本身在 `Caps.plan`，这里只是把结论搬过去：界面绝不自己写
     * "联网就不能带文件"这种规则（ARCH §9 第 2 条）。
     *
     * <p>注意用的是**胶囊当前的模式**（`caps.mode()`）而不是 `sendingMode` ——
     * 后者是"正在发的那一轮"的快照，用它会让闸门慢一轮（切到联网后还是允许带文件）。
     */
    private void refreshDocGate() {
        if (bar == null) return;
        try {
            Config c = new Config(this);
            Caps.Mode m = caps == null ? Caps.Mode.CHAT : caps.mode();
            boolean web = caps != null && caps.isWeb();
            /**
             * ⚠️ 走**8 参**重载，把 `web` 传进去。
             *
             * 老 7 参版本只看 `mode`，而混合模式的 `mode` 也是 AGENT —— 传老版本
             * 会把 hybrid 当成纯 Agent 算，附件闸门就和真实请求不一致了。
             */
            Caps.Plan p = Caps.plan(m, c.think(), c.apiModel(),
                bar.docCount() > 0, false, c.titleAuto(), c.allowDocsInWeb(), web);
            bar.setDocGate(p.allowDocs, Caps.degradeHint(Caps.CAP_FILES));
        } catch (Throwable e) {
            EyeLog.w("ui", "刷新附件闸失败 " + e);
        }
    }

    /**
     * 一轮的开始：把"这一轮怎么发"拍成快照，并锁住胶囊。
     *
     * <p>快照是必须的：请求发出之后用户还能点胶囊，而 PRD 的验收是
     * "本轮行为不因中途改胶囊而变化"。所以这一轮用的模式和档位在**这里**定死，
     * 界面上那些控件只是为下一轮服务。
     */
    private void beginRound() {
        searchCard = null;
        stepsCard = null;
        roundClosed = false;
        pendingRefuse = false;
        sendingMode = caps == null ? Caps.Mode.CHAT : caps.mode();
        sendingEffort = caps == null ? Caps.EFF_AUTO : caps.effort();
        sendingWeb = caps != null && caps.isWeb();
        if (caps != null) caps.setChipEnabled(false);
        if (toolChip != null) toolChip.hide(null);
    }

    /** 一轮的结束：把胶囊还回来（下一轮可以改）。 */
    private void endRound() {
        if (caps != null) caps.setChipEnabled(true);
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
        // 立刻给一个"停了"的信号：思考条先标「已停止」，不等服务那条回包
        stream.thinkStopped();
        // 正常情况：服务把"已收到的部分 +（已停止）"落盘后推 onReply，这里不用收尾。
        // 但万一那条回包没到（服务被打断在别的分支上），按钮不能永远停在 ■ —— 兜一个超时。
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!streaming) return;
                endStream();
                setStreaming(false);
                removeThinking();
                settleCards(true);
                endRound();
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

    /** 左下角相机：先开预览弹层，按快门才真的抓图。 */
    private void openCamera() {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来，稍等一下"); return; }
        CameraSheet d = new CameraSheet(this, s, new CameraSheet.Cb() {
            public void onShot(byte[] jpeg) {
                if (jpeg == null || jpeg.length == 0) { sendLook(); return; }
                capture(jpeg);
            }
        });
        d.show();
    }

    /**
     * 快门拍到的那一张：存盘 → 作为**用户图片**发出去（不是 look 工具图）。
     *
     * 存在工作区旁边的 conv 目录里，气泡能直接显示；`send()` 走
     * `s.send(t, images, atts)`，`attach=true` 会让这张图真的进 API。
     */
    private void capture(byte[] jpeg) {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来，稍等一下"); return; }
        String path = ConvStore.saveImage(convId(), jpeg);
        ChatInputBar.Pick p = new ChatInputBar.Pick(path, jpeg);
        send(fromPicks("这是什么？", java.util.Collections.singletonList(p)));
    }

    /** 顶栏喇叭：开/关朗读（关的时候正在说的那句也立刻停）。 */
    private void toggleSpeak() {
        Config c = new Config(this);
        boolean on = !c.speak();
        c.setSpeak(on);
        if (topBar != null) topBar.setSpeak(on);
        EyeService s = EyeService.peek();
        if (s != null) s.setSpeak(on);
        toast(on ? "朗读已开启" : "朗读已关闭");
    }

    /**
     * 「拍一张」：输入栏那个相机按钮。
     *
     * 和打字发送的区别：这里**先由界面铺一条用户气泡**（"这是什么？"），再让服务去抓帧 ——
     * `EyeService.lookNow()` 内部就是 `send("这是什么？", shot)`，但它不经过
     * `MainActivity.send()`，所以少了这一条气泡；不补的话，抓完图界面上只有它的回答，
     * 用户看不到"我问了什么"。
     */
    private void sendLook() {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来，稍等一下"); return; }
        clearEmpty();
        endStream();
        setStreaming(true);
        beginRound();
        lastUser = bubbles.userBubble("这是什么？", null, 0);
        box.addView(lastUser.view);
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
                settleCards(true);
                endRound();
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
                /*
                 * 这里**只负责那一行即时状态**。
                 *
                 * 步骤条的「▸/✓/✕ + 搜索词 + 来源数」统一由 `Asker` 的
                 * `cb.onStep(...)` 发过来（含混合模式的 web_search）；如果这里再
                 * 自己合成一条 Proto.Step，同一次工具调用会在卡片上长出两行，
                 * 第二行永远停在"进行中"。
                 */
                if (running) toolChip.show(line);
                else toolChip.hide(line);
            }
        });
    }

    /**
     * 老签名（`CallActivity` / 老的调用点还在用）。**行为不变**：包一个只有正文的
     * `Proto.Reply` 转给新签名，于是"没有引用 / 没有卡片"的那条路一个字节都没改。
     */
    @Override
    public void onReply(final String text, final String reasoning, final long thinkMs,
                        final List<String> imageFiles, final long at) {
        Proto.Reply r = new Proto.Reply();
        r.text = text;
        r.reasoning = reasoning;
        r.thinkMs = thinkMs;
        // 老签名没有档位字段：用**这一轮真正发出去的那个**（`sendingEffort`），
        // 而不是"现在胶囊上那个"—— 用户可能已经在等答案时把档位拨走了。
        r.effort = sendingEffort;
        r.mode = sendingMode;
        r.cites = null;
        r.steps = null;
        onReply(r, imageFiles, at);
    }

    /**
     * 一轮回答的完整元数据（联网/Agent 那一轮走这条）。
     *
     * <p>顺序仍然照老规矩：**先用权威全文覆盖正在显示的气泡，再拆它、再建正式气泡**。
     * 多了两件事：`cites` 要跟着全文一起渲染（角标只在最后一帧才到），
     * 卡片要在这里收尾（`settle` / `finish`）。
     */
    @Override
    public void onReply(final Proto.Reply r, final List<String> imageFiles, final long at) {
        final Proto.Reply reply = r == null ? new Proto.Reply() : r;
        ui.post(new Runnable() {
            public void run() { showReply(reply, imageFiles, at); }
        });
    }

    private void showReply(Proto.Reply r, List<String> imageFiles, long at) {
        // 对账：streamed 是流式累计的，authoritative 是落盘那份。两个数不一致
        // 就说明增量有丢失（这才是真 bug），日志里一眼能看出来。
        EyeLog.w("ui", "reply streamed=" + stream.chars()
            + " authoritative=" + (r.text == null ? 0 : r.text.length())
            + " cites=" + (r.cites == null ? 0 : r.cites.size())
            + " steps=" + (r.steps == null ? 0 : r.steps.size())
            + " mode=" + (r.mode == null ? "-" : r.mode.key)
            + " stopped=" + r.stopped + " refused=" + r.refused);

        // ① 思考条先定态（「已停止」不能被随后的收尾覆盖成「· 自动」）
        if (r.stopped) stream.thinkStopped();
        // ② 权威全文 + 角标，同步覆盖正在显示的那条
        stream.finish(r.text, r.cites);
        endStream();
        setStreaming(false);
        removeThinking();
        toolChip.hide(null);
        endRound();

        // 顶栏圆环/花费：先按这一轮的 reply 更新，再等 900ms 让落盘后的上下文算准
        lastReply = r;
        refreshUsage(r);
        ui.postDelayed(new Runnable() {
            public void run() { refreshUsage(null); }
        }, 900);

        // ③ 卡片收尾
        //    先兜一遍"流式事件一条都没到"的情况（T2 还没接 onStep 时就是这条路）：
        //    用 `Reply.steps` 整批回放，卡片照样出现，只是没有逐行动画。
        if (searchCard == null && stepsCard == null
            && r.steps != null && !r.steps.isEmpty()) {
            for (int i = 0; i < r.steps.size(); i += 1) showStep(r.steps.get(i));
        }
        settleCards(r.stopped);

        // ④ 图只挂在"发出去"那一条上（磁盘也是这么存的）
        if (lastUser != null && lastUser.shots == 0) {
            bubbles.addShots(lastUser, imageFiles);
        }
        lastUser = null;

        // ⑤ 服务端给的会话标题（联网模式随本轮带；没有就保持原样，绝不拿来源标题顶上）
        if (r.title != null && r.title.trim().length() > 0) {
            topBar.setTitle(r.title.trim());
        }

        String body = r.text == null ? "" : r.text;
        if (r.refused && body.trim().length() == 0) {
            /**
             * 拒答。T2 的约定是**先 `onReply(rep)`、再 `onError(CONTENT_RISK)`**，
             * 而且不落盘正文 —— 两处都画就会出两条"没答上"气泡。
             *
             * 所以这里先留白，600ms 内等到 `onError` 就交给它（文案统一走 `Err.text`）；
             * 等不到才自己补一句，避免"界面上什么都不发生"。
             */
            pendingRefuse = true;
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (!pendingRefuse) return;
                    pendingRefuse = false;
                    failedRow = bubbles.ai(Err.text(Err.Code.CONTENT_RISK),
                        null, 0, null, 0, true);
                    box.addView(failedRow);
                    scrollBottom();
                }
            }, 600);
        } else if (body.trim().length() == 0) {
            // 只有思考没有正文：不留一条空气泡（那种"什么都没发生"最让人困惑）
            failedRow = bubbles.ai("（这条没答出内容，重试一次看看。）", null, 0, null, 0, true);
            box.addView(failedRow);
        } else {
            box.addView(bubbles.ai(body, r.reasoning, r.thinkMs, r.effort, r.stopped,
                null, at, false, r.cites));
        }
        scrollBottom();
    }

    // ---------------------------------------------------------------- 过程卡（搜索 / 步骤）

    /**
     * 服务端/工具的一步（流式）。
     *
     * <p>T2 已在 `EyeService.Ui` 上给了 `default` 实现，所以这里就是正常覆盖；
     * 没这条事件也不影响最终结果 —— `onReply` 里会用 `Reply.steps` 整批回放补一遍。
     */
    @Override
    public void onStep(final Proto.Step step) {
        if (step == null) return;
        ui.post(new Runnable() {
            public void run() { showStep(step); }
        });
    }

    /**
     * 思考增量（`reasoning_content`）。
     *
     * <p>`Ui` 上暂时没有这个方法，所以**不加 `@Override`**：T2 什么时候加了
     * `default void onThink(String)`，这里就自动成为实现，一行都不用改；
     * 没加的话它只是个没人调的方法，编译照过（思考条由 `Reply.reasoning` 兜底显示）。
     */
    public void onThink(final String delta) {
        ui.post(new Runnable() {
            public void run() {
                if (!stream.active()) beginStream();
                stream.think(delta);
                scrollBottom();
            }
        });
    }

    /** 思考结束（耗时定下来）。 */
    public void onThinkDone(final long ms) {
        ui.post(new Runnable() {
            public void run() { stream.thinkDone(ms, sendingEffort); }
        });
    }

    /**
     * 建卡 / 追加一行。
     *
     * <p>**联网只出搜索卡，Agent 只出步骤条**（PRD P0-8 验收第 5 条：不许两张卡叠在一起）：
     * 判据是这条事件来自服务端工具还是客户端工具 —— 这比"当前模式"更准，
     * 因为用户在等答案期间可能已经把胶囊切走了。
     */
    private void showStep(Proto.Step s) {
        if (roundClosed || s == null) return;
        if (isServerStep(s)) {
            if (searchCard == null) {
                removeThinking();          // 卡片替掉"正在想…"，别两行抢注意力
                searchCard = new Cards.SearchCard(this, convId());
                box.addView(searchCard);
            }
            String line = searchLine(s);
            if (line != null) searchCard.row(line);
        } else {
            // 聊天模式的工具调用**静默**（现有 ToolChip 一行就够，PRD §2.1）
            if (sendingMode != Caps.Mode.AGENT) return;
            if (stepsCard == null) {
                removeThinking();
                stepsCard = new Cards.Steps(this, 16, convId(), new Cards.Cb() {
                    public void onStop() { stopStreaming(); }
                });
                box.addView(stepsCard);
            }
            stepsCard.step(s);
        }
        scrollBottom();
    }

    /** 这条事件是服务端工具（搜索/读链接）还是自定义工具。 */
    private static boolean isServerStep(Proto.Step s) {
        String t = s.callType == null ? "" : s.callType.trim();
        return t.startsWith("deepseek_");
    }

    /**
     * 搜索卡的**一行人话**。
     *
     * <p>一次搜索通常是"开始搜 → 搜完"，读页面是"开始读 → 读完"，所以同一 `action`
     * 按 `type` 给两句话。**不写"已读 N 个页面"** —— 那个 N 服务端没给，
     * 编一个数字比不写更糟（用户会拿它当事实）。
     */
    private String searchLine(Proto.Step s) {
        boolean done = "call_completed".equals(s.type);
        boolean failed = "failed".equals(s.status);
        String act = s.action == null ? "" : s.action.trim();
        String q = s.query == null ? "" : s.query.trim();

        if ("search".equals(act) || "deep_search".equals(act)) {
            if (done) {
                if (failed) return "这一步没搜成";
                return s.sources >= 0 ? "找到 " + s.sources + " 个来源" : "搜完了";
            }
            return q.length() == 0 ? "开始搜索" : "已搜「" + q + "」";
        }
        if ("read_link".equals(act) || "open_page".equals(act)) {
            String d = Cards.domain(s.url);
            if (done) return d.length() == 0 ? "读完了一个页面" : "读完了 " + d;
            return d.length() == 0 ? "正在读一个页面" : "正在读 " + d;
        }
        if ("find_in_page".equals(act)) {
            return done ? "在页面里找到了" : "正在页面里找";
        }
        return done ? "这一步做完了" : "正在" + Cards.describe(s);
    }

    /**
     * 卡片收尾。**中断态也必须收**：卡在"进行中"比没有卡片更糟。
     *
     * @param stopped 用户按了停止（步骤条写「已停止」；达到上限写「已停止（达最大步数 8）」）
     */
    private void settleCards(boolean stopped) {
        roundClosed = true;                 // 之后再有迟到的步骤事件，不再往这一轮下面加卡
        if (searchCard != null) searchCard.settle(stopped);
        if (stepsCard != null) {
            if (stopped) {
                stepsCard.finish(stepsCard.count() >= stepsCard.max()
                    ? "已停止（达最大步数 " + stepsCard.max() + "）" : "已停止");
            } else {
                stepsCard.finish(null);
            }
        }
    }

    @Override
    public void onError(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                // 拒答的那条 onReply 已经先到了：这里由 onError 统一说一句，别再来一条
                pendingRefuse = false;
                stream.thinkStopped();
                endStream();
                setStreaming(false);
                removeThinking();
                toolChip.hide(null);
                // 卡片不能停在"进行中"：断了就写"中断"，并且把胶囊还回去（现场保留）
                settleCards(true);
                endRound();
                lastUser = null;
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

    /** 顶栏第二行：`模型 · 思考档 · 上下文`。 */
    private String subLine() {
        try {
            Config c = new Config(this);
            EyeService s = EyeService.peek();
            int used = s != null ? s.contextTokens() : 0;
            int budget = c.ctxTokens();
            int pct = budget > 0 ? Math.round(used * 100f / budget) : 0;
            return c.apiModel().replace("deepseek-", "")
                + " · " + pct + "% · " + Cost.fmtShort(c.costTotalUsd());
        } catch (Throwable e) {
            return "";
        }
    }

    /** 刷新顶栏圆环、副标题（副标题带上下文百分比和累计花费）。 */
    private void refreshUsage(Proto.Reply r) {
        if (r != null) lastReply = r;
        if (topBar == null) return;
        try {
            EyeService s = EyeService.peek();
            int used = s != null ? s.contextTokens() : 0;
            int budget = new Config(this).ctxTokens();
            topBar.setUsage(used, budget);
            topBar.setSubtitle(subLine());
        } catch (Throwable e) {
            EyeLog.w("ui", "刷新用量失败 " + e);
        }
    }

    /** 点顶栏圆环：上下文 + 本轮/累计花费。 */
    private void openUsage() {
        EyeService s = EyeService.peek();
        int used = s != null ? s.contextTokens() : 0;
        Config c = new Config(this);
        int budget = c.ctxTokens();
        int pct = budget > 0 ? Math.round(used * 100f / budget) : 0;

        Proto.Reply r = lastReply;
        int in = r == null ? c.lastIn() : r.promptTokens;
        int out = r == null ? c.lastOut() : r.completionTokens;
        int hit = r == null ? c.lastHit() : r.cachedTokens;
        int miss = Math.max(0, in - hit);
        int tools = r == null ? c.lastTools() : Cost.countTools(r.steps);
        double turn = r == null ? c.lastCostUsd() : r.costUsd;
        boolean peak = r != null ? r.peak : Cost.peak(System.currentTimeMillis());
        boolean hasTurn = in > 0 || out > 0 || turn > 0;
        double saved = Cost.savedByCache(c.apiModel(), hit, peak);
        String rate = in <= 0 ? "—" : Math.round(hit * 100f / in) + "%";

        String msg = "上下文：" + fmtNum(used) + " / " + fmtNum(budget)
            + "（" + pct + "%）\n"
            + (!hasTurn ? "本轮：还没发过请求\n"
                : "本轮输入 " + fmtNum(in) + "\n"
                  + "　· 缓存命中 " + fmtNum(hit) + "　· 未命中 " + fmtNum(miss)
                  + "　· 命中率 " + rate + "\n"
                  + "本轮输出 " + fmtNum(out) + " · 工具 " + tools + " 次\n"
                  + "本轮花费 " + Cost.fmt(turn)
                  + (saved > 0 ? "（缓存省下 " + Cost.fmt(saved) + "）" : "") + "\n")
            + "累计：输入 " + fmtNum(c.sumIn()) + "（命中 " + fmtNum(c.sumHit()) + "）"
            + " · 输出 " + fmtNum(c.sumOut()) + "\n"
            + "累计花费：" + Cost.fmt(c.costTotalUsd()) + "\n\n"
            + "按官方牌价（" + (peak ? "高峰价" : "空闲价") + "）：\n"
            + "flash 命中 $0.003 / 未命中 $0.15 / 输出 $0.60（每百万）\n"
            + "pro 命中 $0.022 / 未命中 $0.66 / 输出 $1.98\n"
            + "搜索/读网页 $0.00075/次 · 文件解析 $0.225/千页\n"
            + "高峰 = 北京时间 9-12 点、14-18 点（工作日），其余半价。\n"
            + "一轮里多次工具往返是逐轮累加的（以前只算最后一轮，会低报）。";

        new android.app.AlertDialog.Builder(this)
            .setTitle("上下文与花费")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show();
    }

    private static String fmtNum(int n) {
        return String.format(java.util.Locale.CHINA, "%,d", n);
    }

    // ---------------------------------------------------------------- Bubbles.Cb

    @Override
    public void onBubbleLong(boolean mine, final String text, final long at) {
        String[] items = mine
            ? new String[] { "复制", "重发", "删这段" }
            : new String[] { "复制", "朗读", "重发", "删这段" };
        Sheets.menu(this, items, "删这段", new Sheets.OnPick() {
            public void onPick(String pick) {
                if ("复制".equals(pick)) { copy(copyTextFor(at, text)); return; }
                if ("朗读".equals(pick)) {
                    EyeService svc = EyeService.peek();
                    if (svc != null) svc.say(copyTextFor(at, text));
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
            beginRound();
            showThinking("正在想…");
            // 重发要**连附件一起重发**（文件 ID 还在，不用再传一遍）
            if (o.hasBody()) s.send(o.text, o.images, o.atts);
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

    /**
     * 复制时把**角标降级成 `[1]` 纯文本**（PRD P0-3 验收最后一条）。
     *
     * <p>磁盘上的正文永远是干净的（没有 `[1]`、没有哨兵），所以"复制"不是读磁盘原文，
     * 而是**读磁盘 + 现算角标**（`Cites.plainWithMarkers`）。这样两个方向都对：
     * 界面上看得到可点的角标，粘到别处是一段带编号的普通文字。
     *
     * @param at   这条消息的时间戳（0 = 还没落盘，比如"正在想"那种，没有引用可算）
     * @param text 气泡里那份干净正文（找不到磁盘那条时的兜底）
     */
    private String copyTextFor(long at, String text) {
        String fallback = text == null ? "" : text;
        if (at <= 0) return fallback;
        EyeService s = EyeService.peek();
        if (s == null) return fallback;
        try {
            List<Conv.Msg> msgs = s.messages();
            for (int i = 0; i < msgs.size(); i += 1) {
                Conv.Msg m = msgs.get(i);
                if (m == null || m.at != at) continue;
                String body = m.text == null ? fallback : m.text;
                if (m.cites == null || m.cites.isEmpty()) return body;
                return Cites.plainWithMarkers(body, m.cites);
            }
        } catch (Throwable e) {
            EyeLog.w("ui", "算角标失败 " + e);
        }
        return fallback;
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
