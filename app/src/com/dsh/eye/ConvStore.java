package com.dsh.eye;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 对话落盘。**一个会话 = 一个 `.jsonl` 文件**（其实是"一行一条消息"的朴素文本），
 * 图单独放在 `conv/<id>/` 里。
 *
 * ```
 * /sdcard/Download/DSH-Go/conv/
 *   20261111-2233.jsonl     一行一条消息，最早的在最上面
 *   20261111-2233.title     会话名（用户改的那个；没有就用第一句用户的话）
 *   20261111-2233/          这个会话用过的图，命名 <时间戳>-<序号>.jpg
 * ```
 *
 * ## 为什么不用 JSON
 *
 * 工程里没有 JSON 库（零依赖是刻意的），而手写 JSON 解析器是这类小项目最容易出事的地方
 * （转义、代理对、数字精度）。这里的数据形状是**定死的四列**，用 `\t` 分隔 + 转义
 * 就够：人眼能 `cat`，`adb pull` 出来能直接改，坏了一行也只丢一条消息，不会整个会话读不出来。
 *
 * ## 行格式
 *
 * ```
 * <毫秒时间戳> \t <user|assistant> \t <文字> \t <图> \t <思考过程> \t <来源>
 * ```
 * 文字与路径里的 `\`、`\t`、`\n` 都转义成 `\\` `\t` `\n` 两个字符，读的时候反过来。
 * **后两个字段（v4 新增）缺了是合法的** —— 0.13.x 写的老文件只有前四列，
 * 读回来就是"没有思考过程、来源未知"，不需要迁移脚本。
 *
 * ## 写的时候只追加
 *
 * 每轮结束 append 一行，不重写整个文件 —— 手机上重写 100KB 的文本要几十毫秒，
 * 而这一步在主线程（回答落地时）。追加是 O(1)，断电最多丢最后一行。
 */
final class ConvStore {

    /** 同一毫秒里存多张图时的区分序号（见 {@link #saveImage}）。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
        new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 目录**写死绝对路径**，和工程里其它文件一致（`EyeLog` / `say.txt` / `inbox.txt` 都是
     * `/sdcard/Download/DSH-Go/`）。
     *
     * ⚠️ 不要用 `Environment.getExternalStorageDirectory()` 再拼 "DSH-Go" —— 那是
     * `/sdcard/DSH-Go`，少了 `Download/` 这一层，文件会落在别处（踩过：装完一切正常、
     * 只是 `ls /sdcard/Download/DSH-Go/conv` 是空的，因为东西写去了 `/sdcard/DSH-Go`）。
     */
    private static final String BASE = "/sdcard/Download/DSH-Go/conv";

    /** 追加/删除/清空之间互斥：回答落地在主线程、`/ask` 在 HTTP 线程，可能同时写。 */
    private static final Object LOCK = new Object();

    private ConvStore() { }

    /** 一个会话的元信息（列表页只读这个，不读内容）。 */
    static final class Meta {
        String id;
        String title;
        long at;
        int turns;
        /** 置顶的排最前面（和豆包一样：钉住的会话不按时间沉下去）。 */
        boolean pinned;
    }

    // ---------------------------------------------------------------- 路径

    static File dir() {
        File d = new File(BASE);
        if (!d.exists() && !d.mkdirs()) {
            // 建不出来（没给存储权限 / 被分区存储拦了）：如实记一笔，然后照常返回 ——
            // 写图/写会话各自会失败并各自记日志，不会把"回答"这件事本身拖挂。
            EyeLog.w("conv", "建不了 " + BASE + "，多半是没给存储权限");
        }
        return d;
    }

    private static File fileOf(String id) { return new File(dir(), id + ".jsonl"); }
    private static File titleOf(String id) { return new File(dir(), id + ".title"); }
    private static File imgDirOf(String id) {
        File d = new File(dir(), id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 新会话 id：时间序，天然按创建时间排。 */
    static String newId() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    // ---------------------------------------------------------------- 读

    /**
     * 会话列表，**最近用过的在前**。
     *
     * 标题优先级：`.title` 文件 > 第一条用户消息（截 18 字）> "新对话"。
     * `at` 取最后一条消息的时间（不是文件 mtime）—— adb push 过来会改 mtime。
     */
    static List<Meta> list() {
        List<Meta> out = new ArrayList<>();
        File[] files = dir().listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".jsonl")) continue;
            String id = n.substring(0, n.length() - ".jsonl".length());
            Meta m = new Meta();
            m.id = id;
            List<Conv.Msg> msgs = load(id);
            m.turns = 0;
            for (Conv.Msg x : msgs) if ("user".equals(x.role)) m.turns += 1;
            m.at = msgs.isEmpty() ? f.lastModified() : msgs.get(msgs.size() - 1).at;
            m.pinned = isPinned(id);
            m.title = readTitle(id);
            if (m.title == null) {
                for (Conv.Msg x : msgs) {
                    if ("user".equals(x.role) && x.text.length() > 0) { m.title = clip(x.text, 18); break; }
                }
            }
            if (m.title == null || m.title.length() == 0) m.title = "新对话";
            out.add(m);
        }
        Collections.sort(out, new Comparator<Meta>() {
            public int compare(Meta a, Meta b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;   // 置顶永远在上面
                return Long.compare(b.at, a.at);
            }
        });
        return out;
    }

    /** 读一个会话的全部消息（旧 → 新）。坏行跳过，不抛。 */
    static List<Conv.Msg> load(String id) {
        List<Conv.Msg> out = new ArrayList<>();
        File f = fileOf(id);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                Conv.Msg m = parse(line);
                if (m != null) out.add(m);
            }
        } catch (Throwable e) {
            EyeLog.w("conv", "读 " + id + " 失败: " + e);
        } finally {
            if (r != null) { try { r.close(); } catch (Throwable ignored) { } }
        }
        return out;
    }

    private static Conv.Msg parse(String line) {
        if (line == null || line.length() == 0) return null;
        String[] c = line.split("\t", -1);
        if (c.length < 3) return null;
        try {
            Conv.Msg m = new Conv.Msg();
            m.at = Long.parseLong(c[0].trim());
            m.role = c[1];
            m.text = unesc(c[2]);
            if (c.length > 3 && c[3].length() > 0) {
                for (String s : c[3].split(";")) {
                    if (s.length() == 0) continue;
                    String[] p = s.split("\\|", -1);
                    if (p.length < 4) continue;
                    Conv.Shot shot = new Conv.Shot();
                    shot.file = unesc(p[0]);
                    shot.frames = (int) num(p[1]);
                    shot.why = unesc(p[2]);
                    shot.tokens = (int) num(p[3]);
                    m.shots.add(shot);
                }
            }
            // 第 5 列：思考过程。老文件没有这一列 → 空串
            if (c.length > 4 && c[4].length() > 0) m.reasoning = unesc(c[4]);
            // 第 6 列：来源。老文件没有 → 当成 phone
            m.origin = c.length > 5 && c[5].length() > 0 ? unesc(c[5]) : "phone";
            // 第 7 列：这一轮想了多久（毫秒）。0.4.x 及以前没有这一列 → 0
            // （界面因此只显示「思考过程」不显示秒数，见 Bubbles.thinkChip）
            m.thinkMs = c.length > 6 ? num(c[6]) : 0;
            return m;
        } catch (Throwable e) {
            return null;
        }
    }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable e) { return 0; }
    }

    // ---------------------------------------------------------------- 写

    /** 追加一条。同步锁：回答落地和 HTTP 侧可能同时写。 */
    static void append(String id, Conv.Msg m) {
        if (id == null || m == null) return;
        synchronized (LOCK) {
            try {
                FileOutputStream o = new FileOutputStream(fileOf(id), true);
                // 列序固定：at / role / text / shots / reasoning / origin / thinkMs
                // ⚠️ 只在**末尾**加列。桌面插件的解析器（`src/portable/pack.mjs`）按 `line.split('\t')` 读前 4 列，
                //    多出来的列会被忽略；加在中间会把它读歪。
                o.write((m.at + "\t" + m.role + "\t" + Json.esc(m.text) + "\t" + shots(m)
                    + "\t" + Json.esc(m.reasoning)
                    + "\t" + Json.esc(m.origin == null ? "phone" : m.origin)
                    + "\t" + m.thinkMs
                    + "\n").getBytes("UTF-8"));
                o.close();
            } catch (Throwable e) {
                EyeLog.w("conv", "写 " + id + " 失败: " + e);
            }
        }
    }

    private static String shots(Conv.Msg m) {
        StringBuilder sb = new StringBuilder();
        if (m.shots != null) {
            for (Conv.Shot s : m.shots) {
                if (sb.length() > 0) sb.append(';');
                sb.append(Json.esc(s.file)).append('|').append(s.frames).append('|')
                  .append(Json.esc(s.why)).append('|').append(s.tokens);
            }
        }
        return sb.toString();
    }

    /** 图落盘，返回绝对路径；失败返回 null（这一轮就退化成"没图可回看"）。 */
    static String saveImage(String id, byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return null;
        try {
            /**
             * 文件名 = `毫秒-序号`。
             *
             * ⚠️ 只用毫秒会撞名：一批 3 张宫格是连着存的，`currentTimeMillis()` 很可能
             * 三张都一样 —— 后一张把前一张覆盖掉，三条消息指向同一张图（少两张、还看不出来）。
             */
            File f = new File(imgDirOf(id), System.currentTimeMillis() + "-" + SEQ.incrementAndGet() + ".jpg");
            FileOutputStream o = new FileOutputStream(f);
            o.write(jpeg);
            o.close();
            return f.getAbsolutePath();
        } catch (Throwable e) {
            EyeLog.w("conv", "存图失败: " + e);
            return null;
        }
    }

    /** 给 {@link Conv.Reader} 用：读不回来就返回 null（渲染时退化成占位）。 */
    static byte[] readImage(String file) {
        if (file == null || file.length() == 0) return null;
        try {
            File f = new File(file);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    static String readTitle(String id) {
        try {
            File f = titleOf(id);
            if (!f.exists()) return null;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            return new String(b, 0, Math.max(0, n), "UTF-8").trim();
        } catch (Throwable e) {
            return null;
        }
    }

    static void rename(String id, String title) {
        try {
            FileOutputStream o = new FileOutputStream(titleOf(id), false);
            o.write((title == null ? "" : title.trim()).getBytes("UTF-8"));
            o.close();
        } catch (Throwable e) {
            EyeLog.w("conv", "改名失败: " + e);
        }
    }

    // ---------------------------------------------------------------- 置顶

    private static File pinFile(String id) { return new File(dir(), id + ".pin"); }

    static boolean isPinned(String id) {
        return id != null && pinFile(id).exists();
    }

    /**
     * 置顶/取消。
     *
     * 用一个空文件当标记，不用 SharedPreferences：**会话删掉的时候标记跟着一起走**
     * （在同一个目录里），不会留下"这个 id 还钉着"的孤儿条目。
     */
    static void setPinned(String id, boolean on) {
        if (id == null) return;
        try {
            File f = pinFile(id);
            if (on) { if (!f.exists()) f.createNewFile(); }
            else { f.delete(); }
        } catch (Throwable e) {
            EyeLog.w("conv", "置顶失败: " + e);
        }
    }

    /** 删会话：正文 + 标题 + 它自己的图目录。图目录是独占的，直接整个删。 */
    static void delete(String id) {
        if (id == null) return;
        synchronized (LOCK) {
            try { fileOf(id).delete(); } catch (Throwable ignored) { }
            try { titleOf(id).delete(); } catch (Throwable ignored) { }
            try { sumFile(id).delete(); } catch (Throwable ignored) { }
            try { pinFile(id).delete(); } catch (Throwable ignored) { }
            File d = new File(dir(), id);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) { try { f.delete(); } catch (Throwable ignored) { } }
            try { d.delete(); } catch (Throwable ignored) { }
        }
    }

    /** 清空内容但保留会话（"忘掉这段对话，但别把这条记录也删了"）。 */
    static void clear(String id) {
        synchronized (LOCK) {
            try { new FileOutputStream(fileOf(id), false).close(); } catch (Throwable ignored) { }
            // 摘要也一起清：内容都没了，还带着一段"前情"就是假的
            try { sumFile(id).delete(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 砍掉「这条之后（含这条）」的全部消息。给"重说 / 删这段"用。
     *
     * 两条规矩：
     *   1. **先备份**成 `<id>.jsonl.bak` —— 一旦切错了，用户丢的是真对话，
     *      而这个 App 没有"撤销"。宁可多占一份磁盘。
     *   2. 写临时文件再 rename：中途被杀不会留下半个会话。
     *
     * @param fromAt 从哪个时间戳开始砍（含）
     * @return 砍掉了几条；-1 = 失败
     */
    static int truncateFrom(String id, long fromAt) {
        if (id == null) return -1;
        synchronized (LOCK) {
            File f = fileOf(id);
            if (!f.exists()) return 0;
            java.io.BufferedReader r = null;
            java.util.List<String> keep = new ArrayList<>();
            int cut = 0;
            try {
                r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() == 0) continue;
                    int tab = line.indexOf('\t');
                    long at = -1;
                    try { at = Long.parseLong(tab > 0 ? line.substring(0, tab) : line.trim()); }
                    catch (Throwable ignored) { }
                    if (at >= 0 && at >= fromAt) { cut += 1; continue; }
                    keep.add(line);
                }
            } catch (Throwable e) {
                EyeLog.w("conv", "读 " + id + " 失败: " + e);
                return -1;
            } finally {
                try { if (r != null) r.close(); } catch (Throwable ignored) { }
            }
            if (cut == 0) return 0;
            try {
                Files.copy(f, new File(dir(), id + ".jsonl.bak"));
                File tmp = new File(dir(), id + ".jsonl.tmp");
                FileOutputStream o = new FileOutputStream(tmp, false);
                StringBuilder sb = new StringBuilder();
                for (String line : keep) sb.append(line).append('\n');
                o.write(sb.toString().getBytes("UTF-8"));
                o.close();
                if (!tmp.renameTo(f)) {
                    // 有些实现 rename 到已存在的文件会失败：先删再 rename
                    f.delete();
                    if (!tmp.renameTo(f)) { EyeLog.w("conv", "改名失败，truncate 没生效"); return -1; }
                }
            } catch (Throwable e) {
                EyeLog.w("conv", "写 " + id + " 失败: " + e);
                return -1;
            }
            return cut;
        }
    }


    // ---------------------------------------------------------------- 滚动摘要

    private static File sumFile(String id) { return new File(dir(), id + ".sum"); }

    /**
     * 前情摘要：第一行是"已经压到哪条消息"（毫秒时间戳），后面全是摘要正文。
     *
     * 为什么把水位写在同一行：摘要和"压到哪"必须**同生同死**。分成两个文件，写入过程中
     * 被杀就会留下"摘要更新了、水位没动"或者反过来的错位，下一轮要么重复花钱压一遍，
     * 要么漏掉一段对话 —— 一行同时写、只换文件，至少不会自相矛盾。
     */
    static String readSummary(String id) {
        File f = sumFile(id);
        if (!f.exists()) return "";
        String all = readAll(f);
        int nl = all.indexOf('\n');
        return nl < 0 ? "" : all.substring(nl + 1).trim();
    }

    /** 摘要覆盖到的最后一条消息时间（0 = 还没压过）。 */
    static long summaryUpTo(String id) {
        File f = sumFile(id);
        if (!f.exists()) return 0;
        String all = readAll(f);
        int nl = all.indexOf('\n');
        String head = nl < 0 ? all : all.substring(0, nl);
        try { return Long.parseLong(head.trim()); } catch (Throwable e) { return 0; }
    }

    static void writeSummary(String id, String text, long upTo) {
        synchronized (LOCK) {
            try {
                FileOutputStream o = new FileOutputStream(sumFile(id), false);
                o.write((upTo + "\n" + (text == null ? "" : text)).getBytes("UTF-8"));
                o.close();
            } catch (Throwable e) {
                EyeLog.w("conv", "写摘要失败: " + e);
            }
        }
    }

    private static String readAll(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- 转义


    private static String unesc(String s) {
        if (s == null || s.indexOf('\\') < 0) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 't') sb.append('\t');
                else if (n == 'n') sb.append('\n');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String clip(String s, int n) {
        String t = s.replace('\n', ' ').replace('\t', ' ');
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
