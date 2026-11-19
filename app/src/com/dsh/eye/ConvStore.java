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
 * 图与 sidecar 单独放在 `conv/<id>/` 里。
 *
 * ```
 * /sdcard/Download/DSH-Go/conv/
 *   20261111-2233.jsonl     一行一条消息，最早的在最上面（v2：9 列）
 *   20261111-2233.title     会话名（用户改的那个；没有就用第一句用户的话）
 *   20261111-2233.sum       滚动摘要
 *   20261111-2233.pin       置顶标记
 *   20261111-2233.tlock     标题锁（用户手改过名；和 .pin 同款空文件）
 *   20261111-2233/          这个会话独占的目录
 *       1732000000123-1.jpg            图（现有命名）
 *       1732000000456-stc.json         server_tool_calls sidecar（原始 JSON，逐字节）
 * ```
 *
 * ## 为什么不用 JSON
 *
 * 工程里没有 JSON 库（零依赖是刻意的），而手写 JSON 解析器是这类小项目最容易出事的地方
 * （转义、代理对、数字精度）。这里的数据形状是**定死的列**，用 `\t` 分隔 + 转义
 * 就够：人眼能 `cat`，`adb pull` 出来能直接改，坏了一行也只丢一条消息，不会整个会话读不出来。
 *
 * ## 行格式（v2：7 列 → 9 列，**只在末尾加**）
 *
 * ```
 * at \t role \t esc(text) \t shots \t esc(reasoning) \t esc(origin) \t thinkMs \t esc(metaJson) \t stcRef
 * ```
 *
 * 文字与路径里的 `\`、`\t`、`\n` 都转义成 `\\` `\t` `\n` 两个字符，读的时候反过来。
 * **后两列（v2 新增）缺了是合法的** —— 老文件只有前 4~7 列，读回来就是
 * "没有模式 / 没有引用 / 没有附件 / 没有 stc"，不需要迁移脚本。
 *
 * ## 写的时候只追加
 *
 * 每轮结束 append 一行，不重写整个文件 —— 手机上重写 100KB 的文本要几十毫秒，
 * 而这一步在主线程（回答落地时）。追加是 O(1)，断电最多丢最后一行。
 * （`truncateFrom` 是唯一的例外，它必须重写，走"临时文件 + rename"。）
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
    private static final String BASE_DEFAULT = "/sdcard/Download/DSH-Go/conv";

    /**
     * 实际目录。**恒等于 {@link #BASE_DEFAULT}**，只有 {@link #baseForTest} 会改它 ——
     * 那是给电脑 JVM 上的 `T1Test` 用的（电脑上不可能建 `/sdcard/...`），App 里永不调用。
     */
    private static volatile String BASE_DIR = BASE_DEFAULT;

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
        File d = new File(BASE_DIR);
        if (!d.exists() && !d.mkdirs()) {
            // 建不出来（没给存储权限 / 被分区存储拦了）：如实记一笔，然后照常返回 ——
            // 写图/写会话各自会失败并各自记日志，不会把"回答"这件事本身拖挂。
            EyeLog.w("conv", "建不了 " + BASE_DIR + "，多半是没给存储权限");
        }
        return d;
    }

    /** 给电脑 JVM 上的离线测试用（`T1Test` 指到临时目录）。App 里不要调。 */
    static void baseForTest(String dirPath) {
        if (dirPath == null || dirPath.trim().length() == 0) return;
        BASE_DIR = dirPath.trim();
    }

    /** 当前落盘根目录（排障/日志用）。 */
    static String base() {
        return BASE_DIR;
    }

    private static File fileOf(String id) { return new File(dir(), id + ".jsonl"); }
    private static File titleOf(String id) { return new File(dir(), id + ".title"); }
    /** 标题锁：用户手改过名 = 有这个空文件（和 `.pin` 同款，会话删掉时跟着走）。 */
    private static File tlockOf(String id) { return new File(dir(), id + ".tlock"); }
    private static File imgDirOf(String id) {
        File d = new File(dir(), id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** `conv/<id>/<at>-stc.json`。 */
    private static File stcOf(String id, long at) {
        return new File(new File(dir(), id), at + "-stc.json");
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
        // 标题锁是**会话级**的，但渲染每条消息都要知道"能不能覆盖标题" ——
        // 这里一次 IO 镜像到每个 Msg 上，热路径零 IO（ARCH §3.1）。
        final boolean locked = titleLocked(id);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                Conv.Msg m = parse(line);
                if (m != null) {
                    m.titleLocked = locked;
                    out.add(m);
                }
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
            // 第 8 列：metaJson（v2）。老文件没有 → mode/effort/cites/atts 全是默认。
            // ⚠️ **meta 坏只丢 meta，正文照常返回** —— 引用丢了用户看不出来，
            //    正文丢了用户会骂。所以这一层 try 是单独的，绝不和上面合并。
            if (c.length > 7 && c[7].length() > 0) {
                try {
                    readMeta(m, unesc(c[7]));
                } catch (Throwable ignored) {
                    // 只丢 meta：m.text / m.shots / m.reasoning 已经读好了
                }
            }
            // 第 9 列：stcRef（sidecar 文件名；本轮没有 server_tool_calls 就是空）
            if (c.length > 8 && c[8].length() > 0) m.stcRef = unesc(c[8]);
            return m;
        } catch (Throwable e) {
            return null;
        }
    }

    private static long num(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable e) { return 0; }
    }

    /**
     * 解析第 8 列 `metaJson`（键很短，一行几十到几百字节）。
     *
     * ```
     * {"v":2,"m":"web","e":"auto","tg":1,
     *  "src":[{"u":"https://…","t":"来源名","s":"摘要","o":1}],
     *  "ann":[{"s":12,"e":40,"o":1}],
     *  "att":[{"id":"file-api-…","n":"合同.pdf","z":248213,"p":"user_data","st":2}],
     *  "st":"3 步 · 2.4s · 4 源"}
     * ```
     *
     * `src`（来源面板用）与 `ann`（角标用）**合成同一份 {@code Msg.cites}**：按 `o` 对齐。
     * `ann` 里有、`src` 里没有的序号 → 建一个只有位置的 Cite（面板少一条，但不影响渲染）。
     */
    private static void readMeta(Conv.Msg m, String json) {
        Object root = Jsn.parse(json);
        if (!(root instanceof java.util.Map)) return;
        java.util.Map<?, ?> map = (java.util.Map<?, ?>) root;

        Object mode = map.get("m");
        if (mode instanceof String) m.mode = Caps.Mode.of((String) mode);
        Object eff = map.get("e");
        if (eff instanceof String && ((String) eff).length() > 0) m.effort = (String) eff;
        m.titleGen = lon(map.get("tg")) != 0;
        String st = str(map.get("st"));
        if (st != null && st.length() > 0) m.steps = st;

        java.util.TreeMap<Integer, Cites.Cite> byOrd = new java.util.TreeMap<Integer, Cites.Cite>();
        List<Object> src = listOf(map.get("src"));
        for (Object x : src) {
            if (!(x instanceof java.util.Map)) continue;
            java.util.Map<?, ?> e = (java.util.Map<?, ?>) x;
            int ord = (int) lon(e.get("o"));
            if (ord <= 0) continue;
            Cites.Cite c = new Cites.Cite();
            c.ordinal = ord;
            c.url = str(e.get("u"));
            c.title = str(e.get("t"));
            c.snippet = str(e.get("s"));
            c.cpStart = -1;
            c.cpEnd = -1;
            byOrd.put(Integer.valueOf(ord), c);
        }
        List<Object> ann = listOf(map.get("ann"));
        for (Object x : ann) {
            if (!(x instanceof java.util.Map)) continue;
            java.util.Map<?, ?> e = (java.util.Map<?, ?>) x;
            int ord = (int) lon(e.get("o"));
            if (ord <= 0) continue;
            Integer k = Integer.valueOf(ord);
            Cites.Cite c = byOrd.get(k);
            if (c == null) {
                c = new Cites.Cite();
                c.ordinal = ord;
                byOrd.put(k, c);
            }
            c.cpStart = (int) lon(e.get("s"));
            c.cpEnd = (int) lon(e.get("e"));
        }
        if (!byOrd.isEmpty()) m.cites = new ArrayList<Cites.Cite>(byOrd.values());

        List<Object> att = listOf(map.get("att"));
        if (!att.isEmpty()) {
            List<Conv.Att> atts = new ArrayList<Conv.Att>(att.size());
            for (Object x : att) {
                if (!(x instanceof java.util.Map)) continue;
                java.util.Map<?, ?> e = (java.util.Map<?, ?>) x;
                Conv.Att a = new Conv.Att();
                a.fileId = str(e.get("id"));
                a.name = str(e.get("n"));
                a.size = lon(e.get("z"));
                a.purpose = str(e.get("p"));
                a.state = (int) lon(e.get("st"));
                atts.add(a);
            }
            if (!atts.isEmpty()) m.atts = atts;
        }
    }

    private static long lon(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof Boolean) return ((Boolean) o).booleanValue() ? 1 : 0;
        return 0;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object o) {
        if (o instanceof List) return (List<Object>) o;
        return new ArrayList<Object>();
    }

    // ---------------------------------------------------------------- 写

    /** 追加一条。同步锁：回答落地和 HTTP 侧可能同时写。 */
    static void append(String id, Conv.Msg m) {
        if (id == null || m == null) return;
        synchronized (LOCK) {
            try {
                FileOutputStream o = new FileOutputStream(fileOf(id), true);
                // 列序固定：at / role / text / shots / reasoning / origin / thinkMs / meta / stcRef
                // ⚠️ 只在**末尾**加列。桌面插件的解析器（`src/portable/pack.mjs`）按
                //    `line.split('\t')` 读前 4 列，多出来的列会被忽略；加在中间会把它读歪。
                //    老 App 读新文件同理：`c.length > 6` 读 thinkMs，多出的 2 列被忽略，
                //    正文/图/思考/时间全正常 —— 这也是"可回滚"的底气（ARCH §3.2）。
                o.write((m.at + "\t" + m.role + "\t" + Json.esc(m.text) + "\t" + shots(m)
                    + "\t" + Json.esc(m.reasoning)
                    + "\t" + Json.esc(m.origin == null ? "phone" : m.origin)
                    + "\t" + m.thinkMs
                    + "\t" + Json.esc(meta(m))
                    + "\t" + Json.esc(m.stcRef)
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

    /**
     * 造第 8 列。**没有 v5 信息时返回空串**（老样子的行更短，也更好 `cat`）。
     *
     * `v:2` 恒写：将来 v:3 时，本版本只读它认识的键（前向兼容）。
     * `titleLocked` **不进这一列** —— 它是会话级锁（`<id>.tlock`），每条消息带上只是镜像。
     */
    private static String meta(Conv.Msg m) {
        if (m == null) return "";
        boolean hasCites = m.cites != null && !m.cites.isEmpty();
        boolean hasAtts = m.atts != null && !m.atts.isEmpty();
        boolean hasSteps = m.steps != null && m.steps.length() > 0;
        if (m.mode == null && m.effort == null && !m.titleGen && !hasCites && !hasAtts && !hasSteps) {
            return "";
        }
        StringBuilder b = new StringBuilder(192);
        b.append("{\"v\":2");
        if (m.mode != null) b.append(",\"m\":").append(Json.quote(m.mode.key));
        if (m.effort != null) b.append(",\"e\":").append(Json.quote(m.effort));
        if (m.titleGen) b.append(",\"tg\":1");
        if (hasCites) {
            b.append(",\"src\":[");
            boolean first = true;
            for (Cites.Cite c : m.cites) {
                if (c == null) continue;
                if (!first) b.append(',');
                first = false;
                b.append("{\"u\":").append(Json.quote(c.url))
                 .append(",\"t\":").append(Json.quote(c.title))
                 .append(",\"s\":").append(Json.quote(clipSnippet(c.snippet)))
                 .append(",\"o\":").append(c.ordinal).append('}');
            }
            b.append("],\"ann\":[");
            first = true;
            for (Cites.Cite c : m.cites) {
                if (c == null || c.cpEnd < 0) continue;   // 只有来源、没有位置的 → 不进 ann
                if (!first) b.append(',');
                first = false;
                b.append("{\"s\":").append(c.cpStart)
                 .append(",\"e\":").append(c.cpEnd)
                 .append(",\"o\":").append(c.ordinal).append('}');
            }
            b.append(']');
        }
        if (hasAtts) {
            b.append(",\"att\":[");
            boolean first = true;
            for (Conv.Att a : m.atts) {
                if (a == null) continue;
                if (!first) b.append(',');
                first = false;
                b.append("{\"id\":").append(Json.quote(a.fileId))
                 .append(",\"n\":").append(Json.quote(a.name))
                 .append(",\"z\":").append(a.size)
                 .append(",\"p\":").append(Json.quote(a.purpose))
                 .append(",\"st\":").append(a.state).append('}');
            }
            b.append(']');
        }
        if (hasSteps) b.append(",\"st\":").append(Json.quote(m.steps));
        return b.append('}').toString();
    }

    /** 来源摘要最多存 160 字（一行别撑成几百字节，来源面板也不需要更多）。 */
    private static String clipSnippet(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\t', ' ');
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
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

    // ---------------------------------------------------------------- sidecar（server_tool_calls）

    /**
     * 读 `server_tool_calls` 的**原始 JSON 字符串**。
     *
     * 为什么进 sidecar 不进 TSV：`signature` 是加密页面正文，单个 call 可能几 KB 到几十 KB，
     * 塞进一行 TSV 会变成 100KB+（编辑器打不开、`truncateFrom` 重写变慢）。
     *
     * ⚠️ **逐字节保存、逐字节读回，绝不重新序列化** —— 重新序列化会把 `signature` 里的
     * 转义细节改掉，而官方要求"原样回传"。
     */
    static String readStc(String id, long at) {
        if (id == null) return null;
        try {
            File f = stcOf(id, at);
            if (!f.exists()) return null;
            String s = readAll(f);
            return s.length() == 0 ? null : s;
        } catch (Throwable e) {
            EyeLog.w("conv", "读 stc 失败: " + e);
            return null;
        }
    }

    /** 写 sidecar（`null` / 空串 = 什么都不做）。 */
    static void writeStc(String id, long at, String raw) {
        if (id == null || raw == null || raw.length() == 0) return;
        synchronized (LOCK) {
            try {
                File f = stcOf(id, at);
                File d = f.getParentFile();
                if (d != null && !d.exists()) d.mkdirs();
                FileOutputStream o = new FileOutputStream(f, false);
                o.write(raw.getBytes("UTF-8"));
                o.close();
            } catch (Throwable e) {
                EyeLog.w("conv", "写 stc 失败: " + e);
            }
        }
    }

    static void deleteStc(String id, long at) {
        if (id == null) return;
        try { stcOf(id, at).delete(); } catch (Throwable ignored) { }
    }

    /** 把某个会话的 `*-stc.json` 全删（清空对话时用；图保留）。 */
    private static void sweepStc(String id) {
        File d = new File(dir(), id);
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.endsWith("-stc.json")) { try { f.delete(); } catch (Throwable ignored) { } }
        }
    }

    // ---------------------------------------------------------------- 标题 / 标题锁

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

    /**
     * **用户**改标题。落地 `.tlock` —— 之后任何自动标题永不再覆盖（ARCH §3.1）。
     *
     * ⚠️ 自动标题请用 {@link #renameGenerated}：那条路**不落锁**，
     *    否则第一次自动生成之后就把"自动"永久关掉了。
     */
    static void rename(String id, String title) {
        renameManual(id, title);
    }

    /** **用户**改标题（= 上面 {@link #rename} 的正名）：写 `.title` + 落 `.tlock`。 */
    static void renameManual(String id, String title) {
        if (id == null) return;
        writeTitle(id, title);
        lockTitle(id);
    }

    /** 自动生成（轻请求 / 本地截断）的标题：只写 `.title`，**不落锁**。 */
    static void renameGenerated(String id, String title) {
        if (id == null) return;
        if (titleLocked(id)) return;   // 用户手改过名 → 永不覆盖
        writeTitle(id, title);
    }

    /** {@link #renameGenerated} 的别名（ARCH 里叫 `renameAuto`；两个名字都留着，语义完全相同）。 */
    static void renameAuto(String id, String title) {
        renameGenerated(id, title);
    }

    private static void writeTitle(String id, String title) {
        try {
            FileOutputStream o = new FileOutputStream(titleOf(id), false);
            o.write((title == null ? "" : title.trim()).getBytes("UTF-8"));
            o.close();
        } catch (Throwable e) {
            EyeLog.w("conv", "改名失败: " + e);
        }
    }

    static boolean titleLocked(String id) {
        return id != null && tlockOf(id).exists();
    }

    static void lockTitle(String id) {
        if (id == null) return;
        try {
            File f = tlockOf(id);
            if (!f.exists()) f.createNewFile();
        } catch (Throwable e) {
            EyeLog.w("conv", "标题锁失败: " + e);
        }
    }

    // ---------------------------------------------------------------- 置顶

    // ---------------------------------------------------------------- 未完成任务（跨消息续跑）

    /**
     * 未完成任务日志：`<id>.task`，一行一条「工具调用 + 结果」。
     *
     * ## 为什么需要它
     *
     * `Asker` 的 `turn`（assistant.tool_calls + tool 结果）是**内存里的临时数组**，
     * 一轮结束就没了。8/16 步跑不完时，模型下一句"继续"只能看到最终回答，
     * 看不到"刚才搜到了什么、写了哪个文件" —— 于是重复劳动。
     *
     * 这里把每一步的工具结果落盘；下一轮开头作为一条 system 注入。
     * **不存 assistant.tool_calls 协议对**（那要求字段逐字回放，坏了就 400），
     * 只存"做过什么 + 结果是什么"的人话摘要，模型据此接着做即可。
     *
     * ## 格式
     *
     * `tool \t args \t result`，字段内的 `\` `\t` `\n` `\r` 转义成两个字符。
     * 最多 20 条；每条 result 截 4000 字；注入时再截到 6000 字总量。
     */
    private static File taskFile(String id) { return new File(dir(), id + ".task"); }

    static void taskAppend(String id, String tool, String args, String result) {
        if (id == null || id.length() == 0) return;
        try {
            List<String> lines = new ArrayList<String>();
            File f = taskFile(id);
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), "UTF-8"));
                String ln;
                while ((ln = r.readLine()) != null) {
                    if (ln.length() > 0) lines.add(ln);
                }
                r.close();
            }
            lines.add(taskEsc(taskClip(tool, 40)) + "\t" + taskEsc(taskClip(args, 800))
                + "\t" + taskEsc(taskClip(result, 4000)));
            while (lines.size() > 20) lines.remove(0);

            StringBuilder b = new StringBuilder();
            for (String ln : lines) b.append(ln).append('\n');
            File tmp = new File(dir(), id + ".task.tmp");
            FileOutputStream o = new FileOutputStream(tmp, false);
            o.write(b.toString().getBytes("UTF-8"));
            o.close();
            if (!tmp.renameTo(f)) {
                FileOutputStream o2 = new FileOutputStream(f, false);
                o2.write(b.toString().getBytes("UTF-8"));
                o2.close();
                tmp.delete();
            }
        } catch (Throwable e) {
            EyeLog.w("task", "写任务状态失败：" + e);
        }
    }

    /** 下一轮开头注入的 system 文本；没有则返回 ""。 */
    static String taskLoad(String id) {
        if (id == null || id.length() == 0) return "";
        File f = taskFile(id);
        if (!f.exists()) return "";
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"));
            StringBuilder b = new StringBuilder();
            b.append("【上一轮未完成的任务】下面是刚才已经做过的事和结果。接着做，不要重复；")
                .append("如果用户换了话题，就忽略这段。\n");
            String ln;
            int n = 0;
            while ((ln = r.readLine()) != null && b.length() < 6000) {
                String[] p = ln.split("\t", -1);
                if (p.length < 3) continue;
                n += 1;
                b.append(n).append(". [").append(taskUnesc(p[0])).append("] ")
                    .append(taskUnesc(p[1])).append(" → ")
                    .append(taskClip(taskUnesc(p[2]), 1200)).append('\n');
            }
            r.close();
            return n == 0 ? "" : b.toString();
        } catch (Throwable e) {
            EyeLog.w("task", "读任务状态失败：" + e);
            return "";
        }
    }

    static void taskClear(String id) {
        if (id == null || id.length() == 0) return;
        try {
            File f = taskFile(id);
            if (f.exists()) f.delete();
            File tmp = new File(dir(), id + ".task.tmp");
            if (tmp.exists()) tmp.delete();
        } catch (Throwable ignored) { }
    }

    private static String taskClip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    private static String taskEsc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String taskUnesc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i += 1) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(i + 1);
                if (d == '\\') { b.append('\\'); i += 1; continue; }
                if (d == 't') { b.append('\t'); i += 1; continue; }
                if (d == 'n') { b.append('\n'); i += 1; continue; }
                if (d == 'r') { b.append('\r'); i += 1; continue; }
            }
            b.append(c);
        }
        return b.toString();
    }

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

    /**
     * 删会话：正文 + 标题 + 摘要 + 置顶 + **标题锁** + 它自己的目录（图与 sidecar 一起走）。
     * 图目录是独占的，直接整个删。
     */
    static void delete(String id) {
        if (id == null) return;
        synchronized (LOCK) {
            try { fileOf(id).delete(); } catch (Throwable ignored) { }
            try { titleOf(id).delete(); } catch (Throwable ignored) { }
            try { sumFile(id).delete(); } catch (Throwable ignored) { }
            try { pinFile(id).delete(); } catch (Throwable ignored) { }
            // `.tlock` 是目录的**兄弟**文件（和 `.pin` 一样），不删会留下孤儿
            try { tlockOf(id).delete(); } catch (Throwable ignored) { }
            File d = new File(dir(), id);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) { try { f.delete(); } catch (Throwable ignored) { } }
            try { d.delete(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 清空内容但保留会话（"忘掉这段对话，但别把这条记录也删了"）。
     *
     * 三条：
     *   1. `.jsonl` 清空；2. `.sum` 删（内容都没了，还带着一段"前情"就是假的）；
     *   3. `<id>/*-stc.json` 全删（引用的原始包没意义了）+ 图**保留**（用户可能只想清空对话）。
     *
     * ⚠️ `.tlock` **不清**：用户给这个会话起过名这件事，清空对话后仍然算数。
     */
    static void clear(String id) {
        if (id == null) return;
        synchronized (LOCK) {
            try { new FileOutputStream(fileOf(id), false).close(); } catch (Throwable ignored) { }
            // 摘要也一起清：内容都没了，还带着一段"前情"就是假的
            try { sumFile(id).delete(); } catch (Throwable ignored) { }
            sweepStc(id);
        }
    }

    /**
     * 砍掉「这条之后（含这条）」的全部消息。给"重说 / 删这段"用。
     *
     * 三条规矩：
     *   1. **先备份**成 `<id>.jsonl.bak` —— 一旦切错了，用户丢的是真对话，
     *      而这个 App 没有"撤销"。宁可多占一份磁盘。
     *   2. 写临时文件再 rename：中途被杀不会留下半个会话。
     *   3. 被砍掉的那些 assistant 消息对应的 **stc sidecar 一起删**，否则永远留下孤儿文件。
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
            java.util.List<Long> cutStc = new ArrayList<>();   // 被砍掉的 assistant 的 at
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
                    if (at >= 0 && at >= fromAt) {
                        cut += 1;
                        if (isAssistant(line, tab)) cutStc.add(Long.valueOf(at));
                        continue;
                    }
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
            // 重写成功之后才删 sidecar：先删后写如果写失败，引用就永久丢了
            for (Long at : cutStc) deleteStc(id, at.longValue());
            return cut;
        }
    }

    /** 这一行是 assistant 吗（`at \t role \t …`）。 */
    private static boolean isAssistant(String line, int tab) {
        if (tab < 0) return false;
        int st = tab + 1;
        int en = line.indexOf('\t', st);
        if (en < 0) return false;
        return "assistant".equals(line.substring(st, en));
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

    // ---------------------------------------------------------------- 迷你 JSON（只给 metaJson 用）

    /**
     * 极简 JSON 解析器：**只服务第 8 列**。
     *
     * 为什么自己写：工程零依赖（没有 `org.json` 以外的库，而 `org.json` 只在 Android 上
     * 存在，这个类必须能在电脑 JVM 上测）。这里只需要"读回自己刚写的东西"，
     * 所以只实现 RFC 8259 里出现得到的部分：
     *
     * ```
     * object / array / string（含 \" \\ \/ \b \f \n \r \t 与反斜杠-u 的四位十六进制转义）/ number / true / false / null
     * ```
     *
     * 结果类型：`Map<String,Object>` / `List<Object>` / `String` / `Double` / `Boolean` / `null`。
     * **任何畸形输入都不抛**：解析到一半停就返回已解析的部分（meta 坏只丢 meta，正文不动）。
     */
    private static final class Jsn {

        private final String src;
        private int i;

        private Jsn(String src) {
            this.src = src;
            this.i = 0;
        }

        static Object parse(String src) {
            if (src == null || src.length() == 0) return null;
            Jsn p = new Jsn(src);
            p.ws();
            return p.value();
        }

        private void ws() {
            while (i < src.length() && Character.isWhitespace(src.charAt(i))) i += 1;
        }

        private Object value() {
            if (i >= src.length()) return null;
            char c = src.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (src.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (src.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (src.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        private java.util.Map<String, Object> object() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            i += 1;                                   // 吃掉 '{'
            ws();
            if (i < src.length() && src.charAt(i) == '}') { i += 1; return m; }
            while (i < src.length()) {
                ws();
                if (i >= src.length() || src.charAt(i) != '"') break;   // 畸形：就地收手
                String k = string();
                ws();
                if (i < src.length() && src.charAt(i) == ':') i += 1;
                ws();
                Object v = value();
                m.put(k, v);
                ws();
                if (i < src.length() && src.charAt(i) == ',') { i += 1; continue; }
                if (i < src.length() && src.charAt(i) == '}') i += 1;
                break;
            }
            return m;
        }

        private java.util.List<Object> array() {
            java.util.List<Object> a = new ArrayList<Object>();
            i += 1;                                   // 吃掉 '['
            ws();
            if (i < src.length() && src.charAt(i) == ']') { i += 1; return a; }
            while (i < src.length()) {
                ws();
                a.add(value());
                ws();
                if (i < src.length() && src.charAt(i) == ',') { i += 1; continue; }
                if (i < src.length() && src.charAt(i) == ']') i += 1;
                break;
            }
            return a;
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i += 1;                                   // 吃掉开头的引号
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == '"') { i += 1; break; }
                if (c == '\\') {
                    i += 1;
                    if (i >= src.length()) break;
                    char e = src.charAt(i);
                    i += 1;
                    if (e == 'n') b.append('\n');
                    else if (e == 't') b.append('\t');
                    else if (e == 'r') b.append('\r');
                    else if (e == 'b') b.append('\b');
                    else if (e == 'f') b.append('\f');
                    else if (e == 'u') {
                        if (i + 4 <= src.length()) {
                            int v = 0;
                            for (int k = 0; k < 4; k += 1) v = v * 16 + hex(src.charAt(i + k));
                            b.append((char) v);
                            i += 4;
                        }
                    } else b.append(e);               // \" \\ \/ 以及任何未知转义
                } else {
                    b.append(c);
                    i += 1;
                }
            }
            return b.toString();
        }

        private int hex(char c) {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return 0;
        }

        private Object number() {
            int st = i;
            while (i < src.length()) {
                char c = src.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i += 1;
                else break;
            }
            if (st == i) { i += 1; return null; }     // 认不出来的字符：吞一个，绝不死循环
            try { return Double.valueOf(src.substring(st, i)); } catch (Throwable e) { return null; }
        }
    }
}
