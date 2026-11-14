package com.dsh.eye;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 会话列表：**搜索 + 分组 + 置顶**。
 *
 * ```
 * ‹  对话
 * ┌──────────────────────────┐
 * │ ⌕ 搜会话…                 │
 * └──────────────────────────┘
 * 今天
 * ┃ ┌────────────────────────┐        ← 左边那条 3dp 竖线 = 当前会话
 * ┃ │ 桌上有什么              │
 * ┃ │ 14:32 · 6 轮            │
 * ┃ └────────────────────────┘
 *                        (＋)         ← 右下 56dp 渐变 FAB
 * ```
 *
 * 三条设计要点（都是为了"找到上次聊的那条"）：
 * 1. **分组**：置顶 / 今天 / 昨天 / 更早。位置信息比时间戳好认。
 * 2. **搜索**：边打边筛标题，不区分大小写。
 * 3. **置顶**：钉住的不会随时间沉底（`conv/<id>.pin` 空文件当标记）。
 *
 * 长按一行 → 置顶 / 改名 / 发到电脑 / 删除。
 */
public class ConvActivity extends AppActivity {

    private LinearLayout box;
    private EditText search;
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private final SimpleDateFormat md = new SimpleDateFormat("MM-dd", Locale.ROOT);
    /** 当前筛选词（空 = 不筛）。 */
    private String keyword = "";

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        setContentView(build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(Theme.S2), dp(Theme.S2), dp(Theme.S4), dp(Theme.S1));

        FrameLayout back = new FrameLayout(this);
        back.setBackground(Theme.rippleCircle(Theme.RIPPLE));
        ImageView bi = new ImageView(this);
        bi.setImageDrawable(Glyph.of(this, "back", 20, Theme.TEXT_DIM));
        FrameLayout.LayoutParams bilp = new FrameLayout.LayoutParams(dp(20), dp(20));
        bilp.gravity = Gravity.CENTER;
        back.addView(bi, bilp);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText("对话");
        title.setTextColor(Theme.TEXT);
        title.setTextSize(Theme.T_TITLE);
        title.setPadding(dp(Theme.S2), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(top, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 搜索 ----
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setBackground(Theme.pillStroke(Theme.SURFACE_2, Theme.LINE, dp(1)));
        searchBox.setPadding(dp(Theme.S3 + 2), 0, dp(Theme.S3), 0);

        ImageView si = new ImageView(this);
        si.setImageDrawable(Glyph.of(this, "search", 16, Theme.TEXT_DIM));
        LinearLayout.LayoutParams silp = new LinearLayout.LayoutParams(dp(16), dp(16));
        silp.rightMargin = dp(Theme.S2);
        searchBox.addView(si, silp);

        search = new EditText(this);
        search.setHint("搜会话…");
        search.setTextSize(14);
        search.setTextColor(Theme.TEXT);
        search.setHintTextColor(Theme.TEXT_DIM);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(0, dp(10), 0, dp(10));
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                keyword = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                refresh();
            }
            public void afterTextChanged(android.text.Editable s) { }
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sblp.leftMargin = dp(Theme.S4);
        sblp.rightMargin = dp(Theme.S4);
        sblp.topMargin = dp(Theme.S1);
        sblp.bottomMargin = dp(Theme.S2);
        col.addView(searchBox, sblp);

        // ---- 列表 ----
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(Theme.S4), 0, dp(Theme.S4), dp(96));
        sc.addView(box, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(sc, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 新建 FAB ----
        FrameLayout fab = new FrameLayout(this);
        fab.setBackground(Theme.gradient(512f, Theme.GRAD[0], Theme.GRAD[1]));
        fab.setElevation(dp(6));
        ImageView pi = new ImageView(this);
        pi.setImageDrawable(Glyph.of(this, "plus", 22, Theme.WHITE));
        FrameLayout.LayoutParams pilp = new FrameLayout.LayoutParams(dp(22), dp(22));
        pilp.gravity = Gravity.CENTER;
        fab.addView(pi, pilp);
        fab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { newConv(); }
        });
        Anim.press(fab);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56));
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        flp.rightMargin = dp(Theme.S5);
        flp.bottomMargin = dp(Theme.S6);
        root.addView(fab, flp);

        // 沉浸式下 adjustResize 不生效（搜索框/改名弹窗都会被键盘挡），靠它让位
        keepAboveKeyboard(root);
        return root;
    }

    // ---------------------------------------------------------------- 列表

    private void refresh() {
        if (box == null) return;
        box.removeAllViews();

        String cur = currentId();
        List<ConvStore.Meta> all = ConvStore.list();
        List<ConvStore.Meta> shown = new ArrayList<>();
        for (ConvStore.Meta m : all) {
            if (keyword.length() > 0 && !m.title.toLowerCase(Locale.ROOT).contains(keyword)) continue;
            shown.add(m);
        }

        if (shown.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(all.isEmpty()
                ? "还没有对话。点右下角那个 + 开一条，或者回主界面问一句 —— 第一句会自动开一条。"
                : "没有匹配「" + keyword + "」的会话。");
            t.setTextColor(Theme.TEXT_DIM);
            t.setTextSize(13);
            t.setLineSpacing(dp(3), 1f);
            t.setPadding(dp(Theme.S1), dp(Theme.S6), dp(Theme.S1), 0);
            box.addView(t);
            return;
        }

        long todayStart = startOfToday();
        long yesterdayStart = todayStart - 86400_000L;
        int lastGroup = -999;
        for (ConvStore.Meta m : shown) {
            int g = groupOf(m, todayStart, yesterdayStart);
            if (g != lastGroup) {
                head(groupName(g));
                lastGroup = g;
            }
            row(m, m.id.equals(cur));
        }
    }

    private static final int G_PIN = 0;
    private static final int G_TODAY = 1;
    private static final int G_YESTERDAY = 2;
    private static final int G_OLDER = 3;

    private int groupOf(ConvStore.Meta m, long todayStart, long yesterdayStart) {
        if (m.pinned) return G_PIN;
        if (m.at >= todayStart) return G_TODAY;
        if (m.at >= yesterdayStart) return G_YESTERDAY;
        return G_OLDER;
    }

    private String groupName(int g) {
        if (g == G_PIN) return "置顶";
        if (g == G_TODAY) return "今天";
        if (g == G_YESTERDAY) return "昨天";
        return "更早";
    }

    private static long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void head(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Theme.TEXT_DIM);
        v.setTextSize(Theme.T_TINY);
        v.setLetterSpacing(0.08f);
        v.setPadding(dp(Theme.S1), dp(Theme.S4), dp(Theme.S1), dp(Theme.S1));
        box.addView(v);
    }

    /** 一行卡片。当前会话左边多一条 3dp 的竖线。 */
    private void row(final ConvStore.Meta m, boolean isCur) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setBackground(Theme.roundStroke(Theme.SURFACE, dp(Theme.R_CARD), Theme.LINE, dp(1)));

        // 当前会话的指示条（3dp 竖线，贴着卡片左边）
        View bar = new View(this);
        bar.setBackground(Theme.round(isCur ? Theme.ACCENT : Theme.NONE, dp(2)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(3),
            ViewGroup.LayoutParams.MATCH_PARENT);
        blp.leftMargin = dp(1);
        blp.topMargin = dp(10);
        blp.bottomMargin = dp(10);
        wrap.addView(bar, blp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(Theme.S3 + 1), dp(13), dp(Theme.S4), dp(13));

        TextView t = new TextView(this);
        t.setText(m.title);
        t.setTextColor(Theme.TEXT);
        t.setTextSize(Theme.T_ROW);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);

        TextView sub = new TextView(this);
        sub.setText(relTime(m) + " · " + m.turns + " 轮"
            + (m.pinned ? " · 置顶" : "") + (isCur ? " · 当前" : ""));
        sub.setTextColor(isCur ? Theme.ACCENT : Theme.TEXT_DIM);
        sub.setTextSize(Theme.T_SUB);
        sub.setPadding(0, dp(3), 0, 0);
        col.addView(sub);

        wrap.addView(col, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wrap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { open(m); }
        });
        wrap.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                menu(m);
                return true;
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(Theme.S2);
        wrap.setLayoutParams(lp);
        box.addView(wrap);
    }

    /** 今天只显示时间，昨天/更早显示日期（越近越精确）。 */
    private String relTime(ConvStore.Meta m) {
        long todayStart = startOfToday();
        if (m.at >= todayStart) return hhmm.format(new Date(m.at));
        if (m.at >= todayStart - 86400_000L) return "昨天 " + hhmm.format(new Date(m.at));
        return md.format(new Date(m.at));
    }

    // ---------------------------------------------------------------- 操作

    private void newConv() {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务没在跑，先回主界面让它醒过来"); return; }
        s.newConv();
        finish();
    }

    private void open(ConvStore.Meta m) {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务没在跑，切不过去"); return; }
        s.switchConv(m.id);
        finish();
    }

    private void menu(final ConvStore.Meta m) {
        final boolean pin = ConvStore.isPinned(m.id);
        final String[] items = { pin ? "取消置顶" : "置顶", "改名", "发到电脑", "删除" };
        new AlertDialog.Builder(this)
            .setTitle(m.title)
            .setItems(items, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which == 0) {
                        ConvStore.setPinned(m.id, !pin);
                        refresh();
                    } else if (which == 1) {
                        rename(m);
                    } else if (which == 2) {
                        sendToPc(m);
                    } else {
                        confirmDelete(m);
                    }
                }
            })
            .show();
    }

    /**
     * 「发到电脑」：只把**这一段**连上下文导出去（图片全部 offload 成占位文字）。
     *
     * 导完不让用户猜下一步 —— 直接告诉他去电脑上敲哪条命令。
     */
    private void sendToPc(final ConvStore.Meta m) {
        EyeService s = EyeService.peek();
        if (s == null) { toast("服务还没起来"); return; }
        String r = s.exportOne(m.id);
        try {
            org.json.JSONObject o = new org.json.JSONObject(r);
            if (!o.optBoolean("ok")) { toast(Brain.str(o, "error", "导出失败")); return; }
            final String head = "「" + m.title + "」\n" + o.optInt("msgs") + " 条消息"
                + "，其中 " + o.optInt("images") + " 张图已省略";
            final String host = new Config(this).syncHost();

            // ⚠️ 网络请求**必须在后台线程**：Android 会直接抛 NetworkOnMainThreadException
            //    （这一版第一次跑就撞上了，弹窗里显示"连不上 127.0.0.1:7788"其实是这个异常）。
            new Thread(new Runnable() {
                public void run() {
                    final String err = Sync.trigger(host);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            new AlertDialog.Builder(ConvActivity.this)
                                .setTitle(err == null ? "已发到电脑" : "已打包（电脑没连上）")
                                .setMessage(head + "\n\n" + (err == null
                                    ? "电脑正在收，几秒后 DSH 的会话列表里就有这段。"
                                    : "原因：" + err + "\n\n"
                                        + "先在电脑上装好插件：\nnpm i -g dsh-see-on-phone\n\n"
                                        + "包已经存在手机里了，回头在电脑上跑一次 dsh-see-on-phone sync 也一样。"))
                                .setPositiveButton("知道了", null)
                                .show();
                        }
                    });
                }
            }, "dsh-sync").start();
        } catch (Throwable e) {
            toast(r);
        }
    }

    private void rename(final ConvStore.Meta m) {
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(m.title);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
            .setTitle("改个名字")
            .setView(et)
            .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    String t = et.getText().toString().trim();
                    if (t.length() == 0) return;
                    EyeService s = EyeService.peek();
                    if (s != null) s.renameConv(m.id, t);
                    else ConvStore.rename(m.id, t);
                    refresh();
                }
            })
            .setNegativeButton("算了", null)
            .show();
    }

    private void confirmDelete(final ConvStore.Meta m) {
        new AlertDialog.Builder(this)
            .setTitle("删掉「" + m.title + "」？")
            .setMessage("正文和它用过的图都会一起删掉，不能撤销。")
            .setPositiveButton("删", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    EyeService s = EyeService.peek();
                    if (s != null) s.deleteConv(m.id);
                    else ConvStore.delete(m.id);
                    refresh();
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String currentId() {
        EyeService svc = EyeService.peek();
        if (svc != null) return svc.convIdNow();
        String id = new Config(this).convId();
        return id == null ? "" : id;
    }
}
