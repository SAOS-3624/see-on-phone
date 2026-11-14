package com.dsh.eye;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;

import java.io.File;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 设置：**分组卡片、一页到底**。
 *
 * ```
 * 对话                        ← 11sp 灰字，字距 0.08
 * ┌──────────────────────────┐
 * │ ⚙ 思考强度         低  › │  ← 图标 + 标题 15sp + 副标题 12sp + 右值 13sp + chevron
 * ├──────────────────────────┤
 * │ ☰ 上下文       64k（现 3k）›│
 * └──────────────────────────┘
 * ```
 *
 * ## 这一版只换了皮，**逻辑一行没删**
 *
 * 上一版是一长串平铺的行（每行一个圆角灰块），14 行看着像一根面条，找不到东西。
 * 现在按组收进卡片、每行加图标和副标题。**但每一行背后的动作完全没变** ——
 * 设置页是这个 App 全部的配置入口，删一行就是删一个功能。
 *
 * ## v4 删掉了什么（这是最容易被"顺手加回来"的地方）
 *
 * 模式（正常/导盲）、档位、节奏、采集分辨率、帧率、朝向、攒批、空闲自动停、
 * 轮数上限、排障数字 —— 全删。理由：**这些不是用户该操心的**，档位由模型用
 * `tune` 自己调，采集是 `look` 的内部实现。
 */
public class SettingsActivity extends AppActivity {

    private Config cfg;
    private LinearLayout box;
    /** 当前正在填的那张卡片（`head()` 开一张，下一组时关掉）。 */
    private LinearLayout card;
    /** 卡片里已经有几行（用来决定要不要加分隔线）。 */
    private int cardRows;
    /** 正开着的"上下文"弹窗。改一项就把旧关掉再开一个，否则会一层层堆上去。 */
    private AlertDialog ctxDlg;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Config(this);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);
        root.addView(header(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(Theme.BG);
        sc.setVerticalScrollBarEnabled(false);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S6));
        sc.addView(box, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 沉浸式下 adjustResize 不生效，键盘弹起时靠它把底部让出来
        keepAboveKeyboard(sc);
        // 滚动区吃掉剩余高度：不这么写，卡片少的时候整页会缩在上半截
        root.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * 56dp 顶栏：`← 设置`。
     *
     * 原来这一页**没有顶栏也没有返回键** —— 全屏一列卡片，退出只能靠系统返回手势/按键。
     * 设置里有 17 行、14 个弹窗，滑到"关于"那一组时人是没有方向感的。
     */
    private View header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(56));
        bar.setPadding(dp(Theme.S2), 0, dp(Theme.S2), 0);

        ImageView back = new ImageView(this);
        back.setImageDrawable(Glyph.of(this, "back", 20, Theme.TEXT));
        back.setBackground(Theme.rippleCircle(0x22FFFFFF));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView t = new TextView(this);
        t.setText("设置");
        t.setTextColor(Theme.TEXT);
        t.setTextSize(Theme.T_ROW);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(Theme.S2);
        bar.addView(t, lp);
        return bar;
    }

    // ---------------------------------------------------------------- 铺界面

    private void rebuild() {
        if (box == null) return;
        box.removeAllViews();
        card = null;
        cardRows = 0;

        head("对话");
        row("gear", "思考强度", "想多久", cfg.thinkLabel(), new Runnable() { public void run() {
            cfg.setThink(cfg.nextThink());
            apply();
        } });
        row("copy", "上下文", "单次请求最多带多少", ctxBrief(),
            new Runnable() { public void run() { ctxDialog(); } });
        row("image", "回看图片", "历史里带几张图", cfg.imgWindow() + " 张", new Runnable() {
            public void run() {
                cfg.setImgWindow((cfg.imgWindow() + 1) % 4);
                apply();
            } });
        row("menu", "滚动摘要", "聊太长时把前情压成一段", cfg.summary() ? "开" : "关",
            new Runnable() { public void run() {
                cfg.setSummary(!cfg.summary());
                apply();
            } });
        row("edit", "工具", "关掉就只聊天，不看也不记", cfg.tools() ? "开" : "关（纯聊天）",
            new Runnable() { public void run() {
                cfg.setTools(!cfg.tools());
                apply();
            } });

        head("语音");
        row("sound", "朗读", "回答念出来", cfg.speak() ? "开" : "关", new Runnable() {
            public void run() {
                cfg.setSpeak(!cfg.speak());
                apply();
            } });
        row("mic", "音色", "系统的；装引擎就多几个", voiceLabel(),
            new Runnable() { public void run() { pickVoice(); } });
        row("speed", "语速", null, String.format(java.util.Locale.ROOT, "%.1f×", cfg.rate()),
            new Runnable() { public void run() {
                float[] all = { 0.8f, 1.0f, 1.2f, 1.5f };
                float cur = cfg.rate();
                int i = 0;
                for (int k = 0; k < all.length; k += 1) if (Math.abs(all[k] - cur) < 0.01f) i = k;
                cfg.setRate(all[(i + 1) % all.length]);
                apply();
            } });

        head("通话");
        row("video", "自动看", "开着它自己开口；关着只在你说话时发", cfg.autoWatch() ? "开" : "关",
            new Runnable() { public void run() {
                cfg.setAutoWatch(!cfg.autoWatch());
                apply();
            } });
        /**
         * 攒批 = see-now 的 `wakeBatch`（`tuning.js:56`）。**不是手机端新发明的旋钮**：
         * 省的不是图的钱，是"每一轮都要把整个上下文重过一遍"那笔。
         *
         * 改了要**重开通话页**才生效（`queueDepth` 在建管线时定），所以文案只描述它是什么，
         * 不加"立刻生效"这种它会做不到的承诺。
         */
        row("fisheye", "攒批", "攒够几张图才一起交给模型", cfg.wakeBatch() + " 张",
            new Runnable() { public void run() {
                cfg.setWakeBatch(cfg.nextWakeBatch());
                apply();
            } });

        /**
         * 采集两项：**和电脑版 see-now 的「灵敏度 / 档位映射」是同一个东西、同一个取值**
         * （`pace` / `tierStyle`），所以电脑上调好的口径能原样搬过来。
         *
         * 之前这两项只活在 `tune` 工具里（模型自己调），设置页看不到 —— 用户的原话是
         * "档位映射呢？灵敏度呢？怎么都没了"。**模型能调不等于用户能调**，这两件事要分开。
         */
        head("采集");
        row("speed", "灵敏度", "画面变化多快算变化", cfg.paceLabel(),
            new Runnable() { public void run() {
                cfg.setPace(cfg.nextPace());
                apply();
            } });
        row("image", "档位映射", cfg.tierHint(), cfg.tierLabel(),
            new Runnable() { public void run() {
                cfg.setTierStyle(cfg.nextTier());
                apply();
            } });

        head("能力");
        row("eye", "记忆", "它每轮都会看到的东西", memoryBrief(),
            new Runnable() { public void run() { memDialog(); } });
        row("edit", "人设", "它是谁、怎么说话",
            cfg.persona().length() == 0 ? "（没写）" : clip(cfg.persona(), 14),
            new Runnable() { public void run() { personaPick(); } });

        head("数据");
        row("copy", "会话", "新建 / 切换 / 置顶", convBrief(),
            new Runnable() { public void run() {
                startActivity(new Intent(SettingsActivity.this, ConvActivity.class));
            } });
        row("trash", "清空当前会话", "只清内容，图还在", null,
            new Runnable() { public void run() { confirmClear(); } });
        row("wifi", "局域网", "关了只能插数据线连",
            cfg.lanOn() ? "开 · " + lanIp() + ":4444" : "关",
            new Runnable() { public void run() { lanDialog(); } });
        /**
         * 「电脑地址」—— 手机上的「发到电脑」往哪儿发。
         *
         * 一直写死 `127.0.0.1:7788`，只有 `adb reverse` 隧道通的时候才指得到电脑，
         * 所以**不插线就永远发不出去**。这里放出来让用户能填电脑的局域网地址。
         */
        row("wifi", "电脑地址", "「发到电脑」往哪儿发", cfg.syncHost(),
            new Runnable() { public void run() { syncHostDialog(); } });
        row("folder", "工作区", "它能自己往里写文件", wsBrief(),
            new Runnable() { public void run() { workspace(); } });
        row("refresh", "备份与迁移", "手机 ⇄ 电脑", null,
            new Runnable() { public void run() { portable(); } });

        head("关于");
        row("gear", "接口", null, shortModel() + " · " + (cfg.direct() ? "key 已填" : "还没填 key"),
            new Runnable() { public void run() { apiDialog(); } });
        row("check", "版本", "端口 4444", BuildConfig.version(this), null);
        row("copy", "许可", "Apache-2.0 · 第三方署名", "查看",
            new Runnable() { public void run() { licenseDialog(); } });
        row("folder", "目录", "DSH-Go/conv · memory · eye.log", null, new Runnable() {
            public void run() {
                toast("/sdcard/Download/DSH-Go/\n"
                    + "  conv/   会话（一行一条）+ 图\n"
                    + "  memory/ 记忆\n"
                    + "  eye.log 日志");
            }
        });
        endGroups();
        footer();
    }

    private String voiceLabel() { return Config.voiceDisplay(cfg.ttsVoice()); }

    /** 跳系统的「文字转语音」设置：装引擎、下语音包、挑默认嗓子都在那儿。 */
    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Throwable e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                toast("进「系统 → 语言与输入 → 文字转语音」装音色");
            } catch (Throwable e2) {
                toast("这台机器没这个设置页，去系统设置里找「文字转语音」");
            }
        }
    }

    /**
     * 许可。
     *
     * 为什么要在 App 里做这一页：**APK 是独立分发的二进制** —— npm 包里带了
     * NOTICE/CREDITS，不代表拿到 APK 的人也拿到了。Apache-2.0 §4(a) 要求随分发
     * 附一份许可，所以全文直接塞 `res/raw/`，跟着 APK 走。
     */
    private void licenseDialog() {
        /**
         * ⚠️ 这里**不能**同时用 `setMessage` + `setItems`：实测（华为，2.1.0）列表项会整个不见，
         * 只剩 message 和一个「关了」——点了没反应，看着像"许可页坏了"。
         * 三个按钮 + message 才是能同时显示的组合。
         */
        new AlertDialog.Builder(this)
            .setTitle("许可")
            .setMessage("代码：Apache-2.0。\n"
                + "本 APK 内附许可全文与第三方署名。\n\n"
                + "github.com/SAOS-3624/see-on-phone")
            .setPositiveButton("许可全文", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { showRaw("license"); }
            })
            .setNeutralButton("第三方署名", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { showRaw("notice"); }
            })
            .setNegativeButton("关了", null)
            .show();
    }

    /** 读 `res/raw/<name>.txt` 显示。用 getIdentifier 而不是 R —— build.sh 不编 R.java。 */
    private void showRaw(String name) {
        int id = getResources().getIdentifier(name, "raw", getPackageName());
        String body;
        if (id == 0) {
            body = "这个包里没找到 " + name + ".txt（构建时漏了 res/raw/）。";
        } else {
            try {
                java.io.InputStream in = getResources().openRawResource(id);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                body = bo.toString("UTF-8");
            } catch (Throwable e) {
                body = "读不出来：" + e.getMessage();
            }
        }
        ScrollView sv = new ScrollView(this);
        TextView t = new TextView(this);
        /**
         * 许可全文是纯文本（不要过 Markdown —— Apache 正文里全是 `*` 和编号，
         * 会被当列表/强调吃掉）；NOTICE 是 Markdown，走项目自带的渲染器，
         * 否则屏幕上会原样显示 `# NOTICE` 和一堆 `**`。
         */
        t.setText("license".equals(name) ? body : Markdown.render(body));
        t.setTextSize(Theme.T_TINY);
        t.setTextColor(Theme.TEXT_DIM);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        sv.addView(t);
        new AlertDialog.Builder(this)
            .setTitle("license".equals(name) ? "Apache-2.0" : "第三方署名")
            .setView(sv)
            .setPositiveButton("关了", null)
            .show();
    }

    /**
     * 上下文那一行：`64k（现 3.2k）`。
     *
     * 后面那个"现"是当前这条会话实际占的量（估算）—— 没有它，用户看到 64k 也不知道
     * 自己离撞墙还有多远，只能瞎猜。
     */
    private String ctxBrief() {
        StringBuilder b = new StringBuilder(Config.ctxLabel(cfg.ctxTokens()));
        EyeService s = EyeService.peek();
        if (s != null) {
            int est = s.ctxEstimate();
            b.append("（现 ").append(est >= 1000 ? (est / 1000) + "k" : String.valueOf(est)).append("）");
        }
        return b.toString();
    }

    /** 页面最底下那行：品牌 + 版本。**用户能看到的必须写「鱼眼」**。 */
    private void footer() {
        TextView t = new TextView(this);
        t.setText("鱼眼 " + BuildConfig.version(this) + " · 端口 4444");
        t.setTextColor(Theme.TEXT_DIM);
        t.setTextSize(Theme.T_TINY);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(Theme.S6), 0, 0);
        box.addView(t);
    }

    // ---------------------------------------------------------------- 分组 / 行

    /** 开一组：组标题在上，卡片在下面一张张填。 */
    private void head(String t) {
        endGroups();
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(Theme.TEXT_DIM);
        v.setTextSize(Theme.T_TINY);
        if (Build.VERSION.SDK_INT >= 21) v.setLetterSpacing(0.08f);
        v.setPadding(dp(Theme.S1), dp(Theme.S5), dp(Theme.S1), dp(Theme.S2));
        box.addView(v);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.roundStroke(Theme.SURFACE, dp(Theme.R_CARD), Theme.LINE, dp(1)));
        cardRows = 0;
    }

    /** 关掉当前这张卡（下一组开始 / 到底了）。 */
    private void endGroups() {
        if (card == null) return;
        if (cardRows > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(Theme.S1);
            box.addView(card, lp);
        }
        card = null;
        cardRows = 0;
    }

    /**
     * 一行：`图标 + 标题 + 副标题 + 右值 + chevron`。
     *
     * @param sub     副标题（null = 不要第二行）
     * @param value   右侧的值（null = 不显示）
     * @param onClick null = 不可点（"版本"那种只用来读的）
     */
    private void row(String glyph, String label, String sub, String value, final Runnable onClick) {
        if (card == null) head("");

        if (cardRows > 0) {
            View line = new View(this);
            line.setBackgroundColor(Theme.LINE);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            llp.leftMargin = dp(Theme.S4 + 20);
            line.setLayoutParams(llp);
            card.addView(line);
        }
        cardRows += 1;

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(Theme.S4), dp(13), dp(Theme.S4 - 2), dp(13));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(Glyph.of(this, glyph, 20, Theme.TEXT_DIM));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.rightMargin = dp(Theme.S3 + 1);
        r.addView(icon, ilp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Theme.TEXT);
        l.setTextSize(Theme.T_ROW);
        l.setSingleLine(true);
        col.addView(l);

        if (sub != null && sub.length() > 0) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextColor(Theme.TEXT_DIM);
            s2.setTextSize(Theme.T_SUB);
            s2.setSingleLine(true);
            s2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            s2.setPadding(0, dp(2), 0, 0);
            col.addView(s2);
        }
        r.addView(col, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null && value.length() > 0) {
            TextView v = new TextView(this);
            v.setText(value);
            v.setTextColor(Theme.TEXT_DIM);
            v.setTextSize(13);
            v.setGravity(Gravity.END);
            v.setMaxWidth(dp(150));
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(v);
        }

        if (onClick != null) {
            ImageView chev = new ImageView(this);
            chev.setImageDrawable(Glyph.of(this, "chevronRight", 15, Theme.TEXT_DIM));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(15), dp(15));
            clp.leftMargin = dp(Theme.S1);
            r.addView(chev, clp);
            r.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) { onClick.run(); }
            });
        }

        card.addView(r, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---------------------------------------------------------------- 上下文

    /**
     * 上下文设置：**只有一个数** —— 单次请求最多带多少 token，外加"自定义"。
     *
     * 以前这里还有"带几轮"，删了：它和预算是双闸，两个都满足才发，于是预算调到 300k
     * 也没用。**留一个数就够了**：想少带就把预算调小。
     */
    private void ctxDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(4), dp(18), dp(4));

        TextView t2 = new TextView(this);
        t2.setText("单次请求最多带多少（含工具与记忆）");
        t2.setTextColor(Theme.TEXT);
        t2.setTextSize(14);
        t2.setPadding(0, dp(6), 0, dp(6));
        col.addView(t2);

        LinearLayout line2 = chipLine();
        for (final int v : Config.CTX_CHOICES) {
            if (v > 128_000) continue;                  // 前四个一行，剩下的一行
            line2.addView(chip(Config.ctxLabel(v), cfg.ctxTokens() == v, new Runnable() {
                public void run() { cfg.setCtxTokens(v); apply(); reopenCtx(); }
            }));
        }
        col.addView(line2);

        LinearLayout line3 = chipLine();
        for (final int v : Config.CTX_CHOICES) {
            if (v <= 128_000) continue;
            line3.addView(chip(Config.ctxLabel(v), cfg.ctxTokens() == v, new Runnable() {
                public void run() { cfg.setCtxTokens(v); apply(); reopenCtx(); }
            }));
        }
        line3.addView(chip("自定义", false, new Runnable() {
            public void run() { ctxCustom(); }
        }));
        col.addView(line3);

        TextView tip = new TextView(this);
        tip.setText("现在：" + Config.ctxLabel(cfg.ctxTokens()) + " 上限 · 这条会话约占 "
            + fmtTok(eyeEstimate()) + "\n\n"
            + "预算只是上限，不是每问都花这么多 —— 聊得短就按实际长度算。\n"
            + "服务端窗口 1M，所以这里封顶 900k（留余量给工具定义和这一次的回答）。\n"
            + "开太大会变慢；命中前缀缓存时输入会便宜很多。");
        tip.setTextColor(Theme.TEXT_DIM);
        tip.setTextSize(Theme.T_TINY);
        tip.setLineSpacing(dp(2), 1f);
        tip.setPadding(0, dp(14), 0, 0);
        col.addView(tip);

        ScrollView sc = new ScrollView(this);
        sc.addView(col);
        ctxDlg = new AlertDialog.Builder(this)
            .setTitle("上下文")
            .setView(sc)
            .setNegativeButton("关了", null)
            .show();
    }

    /** 改完一项：关掉当前这个再开一个新的（就地改样式更麻烦，弹窗数量也不是性能问题）。 */
    private void reopenCtx() {
        if (ctxDlg != null) {
            try { ctxDlg.dismiss(); } catch (Throwable ignored) { }
            ctxDlg = null;
        }
        ctxDialog();
    }

    /** 「自定义…」：直接填数字（单位 k，1M 就填 1000）。 */
    private void ctxCustom() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("填数字，单位 k（例如 300 = 300k）");
        et.setText(String.valueOf(cfg.ctxTokens() / 1000));
        et.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
            .setTitle("自定义预算（k）")
            .setMessage("范围 " + (Config.CTX_MIN / 1000) + " ~ " + (Config.CTX_MAX / 1000)
                + "k。服务端窗口是 1M，这里封顶 900k —— 剩下的留给工具定义和这一次的回答，"
                + "不封顶的话刚好顶到 1M 会直接 400。")
            .setView(et)
            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    int k;
                    try { k = Integer.parseInt(et.getText().toString().trim()); }
                    catch (Throwable e) { toast("填个数字"); return; }
                    cfg.setCtxTokens(k * 1000);
                    apply();
                    toast("预算 " + Config.ctxLabel(cfg.ctxTokens()));
                    reopenCtx();
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    private int eyeEstimate() {
        EyeService s = EyeService.peek();
        return s == null ? 0 : s.ctxEstimate();
    }

    private static String fmtTok(int t) {
        if (t >= 1000) return String.format(java.util.Locale.ROOT, "%.1fk", t / 1000.0);
        return String.valueOf(t);
    }

    // ---------------------------------------------------------------- 小部件

    private LinearLayout chipLine() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setPadding(0, 0, 0, dp(2));
        return l;
    }

    /** 一个可选中的小方块（没有 FlowLayout 就自己拼行）。 */
    private TextView chip(String label, boolean on, final Runnable click) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(13);
        v.setTextColor(on ? Theme.BG : Theme.TEXT_SOFT);
        v.setSingleLine(true);          // 「自定义…」被折成两行过，别再来一次
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setBackground(Theme.roundStroke(on ? Theme.ACCENT : Theme.SURFACE_2,
            dp(9), on ? Theme.ACCENT : Theme.LINE, dp(1)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(4);
        v.setLayoutParams(lp);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { click.run(); }
        });
        return v;
    }

    private String memoryBrief() {
        try {
            return Memory.count() + " 个文件 · 约 " + Conv.textTokens(Memory.block(cfg)) + " token";
        } catch (Throwable e) {
            return "";
        }
    }

    private String convBrief() {
        try {
            return ConvStore.list().size() + " 条";
        } catch (Throwable e) {
            return "";
        }
    }

    private String shortModel() { return cfg.apiModel().replace("deepseek-", ""); }

    // ---------------------------------------------------------------- 各对话框

    /** 音色：系统的中文语音列表（第 0 个是「系统默认」）。 */
    private void pickVoice() {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来，等两秒再点"); return; }
        // 全是系统里装了的音色（第 0 个是「系统默认」）—— 见 EyeService.voiceChoices
        final String[] labels = s.voiceChoices();
        new AlertDialog.Builder(this)
            .setTitle("发音")
            .setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    EyeService svc = EyeService.peek();
                    if (svc == null) return;
                    String key = which == 0 ? "" : svc.voiceKeyAt(which);
                    cfg.setTtsVoice(key);
                    svc.applySettings();
                    svc.testVoice();
                    rebuild();
                }
            })
            .setNeutralButton("更多音色", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { openTtsSettings(); }
            })
            .show();
    }

    /**
     * 记忆：看清单、单条删、清空。
     *
     * 「单条删」是 v4 补的 —— 0.13.x 只有"清空"，而记忆是**每轮都注入**的东西，
     * 有一条写坏了却不能单独拿掉，用户就只能全清。
     */
    private void memDialog() {
        final java.util.List<java.io.File> fs = Memory.files();
        if (fs.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("记忆")
                .setMessage("目录是空的：" + Memory.DIR
                    + "\n\n它自己用 memory_write 记的东西会出现在这里；"
                    + "你也可以直接往这个目录丢 txt/md。")
                .setPositiveButton("好", null)
                .show();
            return;
        }
        // 列表最后接两个动作项 —— 以前"清空全部"挂在 neutral 上，
        // 后来加"新建一条"时把那个按钮顶掉了，功能就这么静悄悄地没了。
        final String[] names = new String[fs.size() + 2];
        for (int i = 0; i < fs.size(); i += 1) {
            names[i] = fs.get(i).getName() + "（" + fs.get(i).length() + "B）";
        }
        names[fs.size()] = "＋ 新建一条";
        names[fs.size() + 1] = "清空全部（" + fs.size() + " 个）";
        new AlertDialog.Builder(this)
            .setTitle("记忆 · " + Memory.count() + " 个文件")
            .setItems(names, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which < fs.size()) { showMem(names[which]); return; }
                    if (which == fs.size()) { memNew(); return; }
                    new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("清空全部记忆？")
                        .setMessage("这些是它以后每轮都会看到的东西，清掉就再也想不起来了。")
                        .setPositiveButton("清", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w2) {
                                EyeService svc = EyeService.peek();
                                if (svc != null) svc.forgetAllMem(); else Memory.clear();
                                rebuild();
                            }
                        })
                        .setNegativeButton("算了", null)
                        .show();
                }
            })
            .setNegativeButton("关了", null)
            .show();
    }

    /** 手写一条记忆（和它自己 `memory_write` 写进去的是同一种文件）。 */
    private void memNew() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(4), dp(18), dp(4));

        final EditText name = new EditText(this);
        name.setHint("文件名，如 03-家规.txt");
        name.setSingleLine(true);
        col.addView(name);

        final EditText text = new EditText(this);
        text.setHint("内容（它会每轮都看到，别太长）");
        text.setMinLines(4);
        col.addView(text);

        TextView tip = new TextView(this);
        tip.setText("上限：单文件 2KB、目录合计约 1500 token。满了会从最旧的文件开始丢。");
        tip.setTextColor(Theme.TEXT_DIM);
        tip.setTextSize(Theme.T_TINY);
        tip.setPadding(0, dp(8), 0, 0);
        col.addView(tip);

        new AlertDialog.Builder(this)
            .setTitle("记一条")
            .setView(col)
            .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    String n = name.getText().toString().trim();
                    String t = text.getText().toString();
                    if (n.length() == 0) n = "note.txt";
                    if (t.trim().length() == 0) { toast("内容空着"); return; }
                    String r = Tools.writeFile(n, t, false);
                    if (r.contains("\"error\"")) toast(r);
                    else toast("写好了");
                    EyeService s = EyeService.peek();
                    if (s != null) s.applySettings();
                    rebuild();
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    private void showMem(final String label) {
        int cut = label.lastIndexOf("（");
        final String name = cut > 0 ? label.substring(0, cut) : label;
        java.io.File f = new java.io.File(Memory.dir(), name);
        String body = Memory.read(f);
        if (body == null) body = "（读不出来）";
        new AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(body.length() > 2000 ? body.substring(0, 2000) + "…" : body)
            .setPositiveButton("忘掉这条", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    EyeService s = EyeService.peek();
                    boolean ok = s != null ? s.forgetMem(name) : Memory.remove(name);
                    toast(ok ? "删了" : "删不掉");
                    rebuild();
                }
            })
            .setNegativeButton("留着", null)
            .show();
    }

    /**
     * 人设：先给几个**能用的模板**，再给"自定义"。
     *
     * 为什么不再直接弹一个大输入框：空白框面前没人知道该写什么，最后都空着。
     */
    private void personaPick() {
        final String[] names = {
            "默认（不给身份）", "随身眼睛", "学习搭子",
            "看片搭子", "自定义…",
        };
        new AlertDialog.Builder(this)
            .setTitle("它是什么身份")
            .setItems(names, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which == names.length - 1) { personaDialog(); return; }
                    cfg.setPersona(PERSONA[which]);
                    apply();
                    toast(which == 0 ? "清掉了" : "设成「" + names[which] + "」");
                }
            })
            .show();
    }

    private static final String[] PERSONA = {
        "",
        "你在给我当眼睛。说话短、直接，先给结论再给细节。看到什么说什么；看不清就说看不清，不许猜。",
        "你是我的学习搭子。讲东西要打比方，讲完追问一句确认我听懂了，别一次倒一大段。",
        "你陪我一起看片。只说值得说的地方，别逐帧解说，别剧透后面的剧情。",
    };

    private void personaDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(5);
        et.setText(cfg.persona());
        new AlertDialog.Builder(this)
            .setTitle("人设（每轮都注入，别太长）")
            .setView(et)
            .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    cfg.setPersona(et.getText().toString());
                    apply();
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    /** 手机在 WiFi 里的地址（拿不到就给个提示）。 */
    private String lanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "没连上 WiFi";
    }

    /**
     * 局域网开关。
     *
     * 默认**关**：关了只监听 127.0.0.1，必须插数据线（adb forward）才连得上。
     * 开了绑 0.0.0.0，同一个 WiFi 下电脑直接连 —— 方便，但**同网任何设备**都能调这些接口。
     */
    private void lanDialog() {
        final boolean on = cfg.lanOn();
        new AlertDialog.Builder(this)
            .setTitle("局域网（不插线）")
            .setMessage((on ? "现在是**开**：" + lanIp() + ":4444\n同 WiFi 的电脑可以直接连。"
                : "现在是**关**：只监听本机，必须用数据线 + adb forward。")
                + "\n\n开了之后，同一个 WiFi 下的任何设备只要知道这个地址，"
                + "就能看图、让它说话、改档位。**在公共 WiFi 上别开。**"
                + "\n\n电脑那边：\ndsh-see-on-phone status --host " + lanIp() + ":4444")
            .setPositiveButton(on ? "关掉" : "打开", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    cfg.setLanOn(!on);
                    EyeService s = EyeService.peek();
                    if (s != null) s.rebind();
                    toast(!on ? "已开：" + lanIp() + ":4444" : "已关，只能插线连");
                    rebuild();
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    /**
     * 电脑地址。
     *
     * 插数据线时走 `127.0.0.1:7788`：电脑端的同步面板会 `adb reverse` 把这个端口
     * 接到自己身上，所以手机这边不用知道电脑在哪。
     *
     * 不插线时那个地址没人接 —— 改成电脑的局域网地址，手机直接往那边发。
     * 电脑端同步面板对外监听时会打印这个地址。
     */
    private void syncHostDialog() {
        // 示例用**手机自己**的网段推算，不把任何一台具体电脑的 IP 写进代码
        final String myIp = lanIp();
        final String example = myIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
            ? myIp.substring(0, myIp.lastIndexOf('.')) + ".100:7788"
            : "192.168.0.100:7788";

        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(cfg.syncHost());
        et.setSelection(et.getText().length());
        et.setHint(example);
        new AlertDialog.Builder(this)
            .setTitle("电脑地址")
            .setMessage("「发到电脑」往这个地址发。\n\n"
                + "· 插数据线：保持 127.0.0.1:7788（电脑端会自动接上）\n"
                + "· 不插线：填电脑的局域网地址，形如 " + example + "\n"
                + "  电脑和手机要在同一个 WiFi 下")
            .setView(et)
            .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    String v = et.getText().toString().trim();
                    cfg.setSyncHost(v.length() == 0 ? "127.0.0.1:7788" : v);
                    toast("电脑地址：" + cfg.syncHost());
                    rebuild();
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    private String wsBrief() {
        EyeService s = EyeService.peek();
        int n = s == null ? Workspace.count() : s.workspaceCount();
        return n == 0 ? "空" : n + " 个文件";
    }

    /**
     * 工作区：看它到底存了什么，能读、能删。
     *
     * 这块地的意义是"**用户能验证**"：模型说"存好了"，你得能当场看到那个文件。
     */
    private void workspace() {
        final java.util.List<File> files = new java.util.ArrayList<>();
        final java.util.List<String> rels = new java.util.ArrayList<>();
        walk(Workspace.dir(), "", files, rels, 0);
        if (files.isEmpty()) {
            new AlertDialog.Builder(this)
                .setTitle("工作区")
                .setMessage("还空着。\n\n让它「帮我记下来」「存成文件」，它就会往这里写：\n"
                    + Workspace.ROOT + "\n\n这个目录在文件管理器里能直接看到、能拷走。")
                .setPositiveButton("知道了", null)
                .show();
            return;
        }
        final String[] labels = new String[rels.size()];
        for (int i = 0; i < rels.size(); i += 1) {
            long n = files.get(i).length();
            labels[i] = rels.get(i) + "  ·  " + (n >= 1024 ? (n / 1024) + "KB" : n + "B");
        }
        new AlertDialog.Builder(this)
            .setTitle("工作区 · " + rels.size() + " 个文件")
            .setItems(labels, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) { showWsFile(rels.get(which)); }
            })
            .setNeutralButton("清空", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("清空工作区？")
                        .setMessage("里面的文件会全删掉，不能撤销。")
                        .setPositiveButton("清", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d2, int w2) {
                                for (String r : rels) Workspace.delete(r);
                                rebuild();
                            }
                        })
                        .setNegativeButton("算了", null)
                        .show();
                }
            })
            .show();
    }

    private void walk(File d, String prefix, java.util.List<File> out,
                      java.util.List<String> rels, int depth) {
        if (depth > 4) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;
            String r = prefix.length() == 0 ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) walk(f, r, out, rels, depth + 1);
            else { out.add(f); rels.add(r); }
        }
    }

    private void showWsFile(String rel) {
        String r = Workspace.read(rel);
        String body = r;
        try {
            org.json.JSONObject o = new org.json.JSONObject(r);
            body = Brain.str(o, "text", r);
            if (body.length() > 8000) body = body.substring(0, 8000) + "\n\n…（太长了，只显示前 8000 字）";
        } catch (Throwable ignored) { }
        new AlertDialog.Builder(this)
            .setTitle(rel)
            .setMessage(body)
            .setPositiveButton("关了", null)
            .setNegativeButton("删掉", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    Workspace.delete(rel);
                    rebuild();
                }
            })
            .show();
    }

    /**
     * 手机 ⇄ 电脑：会话和记忆搬来搬去。
     *
     * 界面上只给两个动作（导出 / 导入），细节交给命令行 —— 因为真正搬的时候
     * 人是坐在电脑前的，手机上点两下反而更麻烦。
     */
    private void portable() {
        new AlertDialog.Builder(this)
            .setTitle("备份与迁移")
            .setMessage("导出：把会话 + 记忆摊平到 Download/DSH-Go/export/，\n"
                + "并在那儿生成一个 phone-时间.zip（可以直接发走）。\n\n"
                + "导入：读 Download/DSH-Go/import/ 里的内容合并进来。\n"
                + "会话按时间戳增量追加，记忆同名不同内容不覆盖（另存到 import/skipped/）。\n\n"
                + "电脑那头一条命令：\ndsh-see-on-phone sync")
            .setPositiveButton("导出", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    EyeService s = EyeService.peek();
                    if (s == null) { toast("服务还没起来"); return; }
                    String r = s.exportData();
                    toast(r.contains("\"ok\":true") ? "导出好了：Download/DSH-Go/export/" : r);
                }
            })
            .setNegativeButton("导入", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    EyeService s = EyeService.peek();
                    if (s == null) { toast("服务还没起来"); return; }
                    String r = s.importData();
                    toast(r.contains("\"ok\":true") ? "导入完成" : r);
                    rebuild();
                }
            })
            .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("清空当前会话？")
            .setMessage("正文和前情摘要都会没，图还留在 conv/ 里。这一步不能撤销。")
            .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    EyeService s = EyeService.peek();
                    if (s != null) s.clearConv(); else ConvStore.clear(cfg.convId());
                    toast("清空了");
                    rebuild();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    /**
     * 接口：key / 端点 / 模型。
     *
     * 手机键盘打 key 很痛苦，所以除了这里，还有 `GET /config?key=...` 那条路
     * （adb forward 之后一条 curl 就设好）。
     */
    private void apiDialog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText key = new EditText(this);
        key.setHint("API key（sk-…）");
        key.setText(cfg.apiKey());
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(key);

        final EditText base = new EditText(this);
        base.setHint("端点");
        base.setText(cfg.apiBase());
        base.setSingleLine(true);
        col.addView(base);

        final EditText model = new EditText(this);
        model.setHint("模型");
        model.setText(cfg.apiModel());
        model.setSingleLine(true);
        col.addView(model);

        TextView tip = new TextView(this);
        tip.setText("填了 key 才能用。模型默认 deepseek-v4-flash"
            + "（旧名 deepseek-chat 已弃用，会自动映射到非思考模式）。");
        tip.setTextColor(Theme.TEXT_DIM);
        tip.setTextSize(Theme.T_TINY);
        tip.setPadding(0, dp(8), 0, 0);
        col.addView(tip);

        new AlertDialog.Builder(this)
            .setTitle("接口")
            .setView(col)
            .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    cfg.setKey(key.getText().toString());
                    cfg.setBase(base.getText().toString());
                    cfg.setModel(model.getText().toString());
                    apply();
                    toast(cfg.direct() ? "存了" : "key 是空的，还不能聊");
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    // ---------------------------------------------------------------- 收尾

    /** 改完立刻通知服务（语速/音色/超时/记忆注入都是即时生效的）。 */
    private void apply() {
        EyeService s = EyeService.peek();
        if (s != null) s.applySettings();
        rebuild();
    }

    private static String clip(String s, int n) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
