package com.dsh.eye;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与记忆的**搬运**：手机 ↔ 电脑。
 *
 * ## 为什么是"摊平目录"，不是"打包成二进制"
 *
 * 两端的边界只放**最笨的东西**：一行一条的 jsonl、纯文本的记忆、一堆 jpg。
 * 这样：
 *   - PC 侧不需要任何依赖（`adb pull` 一个目录就够了，连 zip 都不用解）
 *   - 出问题时用记事本就能看一眼，不用先写个解包脚本
 *   - 合并规则可以写得极简单（见 {@link #mergeJsonl}）
 *
 * 另外**顺手**在 `export/` 里生成一个同名 zip（`phone-<时间>.zip`），纯粹为了"发微信传走"
 * 这种场景。PC 那条路不用它。
 *
 * ## 合并规则（这是这套东西的核心）
 *
 * | 内容 | 规则 |
 * |---|---|
 * | 会话 `conv/<id>.jsonl` | **按时间戳增量追加**：只补本地没有的（`at` 大于本地最大）。jsonl 本来就是追加式的，天然可合并 |
 * | 会话图片 `conv/<id>/*.jpg` | 只补缺的（同名就是同一张） |
 * | 记忆 `memory/*` | 本地没有 → 写入；内容一样 → 跳过；**内容不同 → 不覆盖**，另存到 `import/skipped/` 并回报 |
 *
 * 最后一条是刻意的：记忆是**人写的话**，它会**每轮都进 prompt**。自动覆盖很容易
 * 把手机上手改的那段冲掉，而用户根本不会发现。宁可他看到"有一条冲突"，也别悄悄丢。
 */
final class Portable {

    static final String BASE = "/sdcard/Download/DSH-Go";
    static final String EXPORT_DIR = BASE + "/export";
    static final String IMPORT_DIR = BASE + "/import";
    static final String SKIP_DIR = IMPORT_DIR + "/skipped";

    private Portable() { }

    // ---------------------------------------------------------------- 导出

    /**
     * 把当前数据摊平写到 `Download/DSH-Go/export/`，再打一个 zip 方便传走。
     *
     * @return 给人看的 JSON（路径、条数、字节数）
     */
    static String export() {
        File dir = new File(EXPORT_DIR);
        try {
            /**
             * ⚠️ 只清**上一次全量导出留下的东西**，不能碰 `conv-*` 子目录 ——
             * 那些是「发到电脑」单独打包的会话，全量导出顺手删了它们，
             * 用户刚点的"发到电脑"就白点了（实测踩到：导出完一看目录没了）。
             */
            cleanExceptConvs(dir);
            if (!dir.exists() && !dir.mkdirs()) return Json.err("建不了 export 目录（没给存储权限？）");

            int convs = 0;
            long bytes = 0;
            File convSrc = ConvStore.dir();
            File convOut = new File(dir, "conv");
            convOut.mkdirs();
            File[] fs = convSrc.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isDirectory()) {
                        // <id>/ 里面是这一轮用过的图
                        File sub = new File(convOut, f.getName());
                        sub.mkdirs();
                        File[] imgs = f.listFiles();
                        if (imgs != null) for (File img : imgs) {
                            bytes += Files.copy(img, new File(sub, img.getName()));
                        }
                        continue;
                    }
                    String n = f.getName();
                    if (!(n.endsWith(".jsonl") || n.endsWith(".sum") || n.endsWith(".pin"))) continue;
                    if (n.endsWith(".jsonl")) convs += 1;
                    bytes += Files.copy(f, new File(convOut, n));
                }
            }

            int mems = 0;
            File memSrc = Memory.dir();
            File memOut = new File(dir, "memory");
            memOut.mkdirs();
            File[] ms = memSrc.listFiles();
            if (ms != null) {
                for (File m : ms) {
                    if (m.isDirectory()) continue;
                    mems += 1;
                    bytes += Files.copy(m, new File(memOut, m.getName()));
                }
            }

            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT)
                .format(new java.util.Date());
            Files.writeText(new File(dir, "manifest.json"),
                "{\"app\":\"dsh-eye\",\"version\":\"" + BuildConfig.VERSION + "\","
                + "\"time\":" + System.currentTimeMillis() + ","
                + "\"convs\":" + convs + ",\"memory\":" + mems + "}\n", false);

            File zip = new File(dir, "phone-" + stamp + ".zip");
            long zipBytes = zip(dir, zip);

            String json = "{\"ok\":true,\"dir\":\"" + EXPORT_DIR + "\","
                + "\"zip\":\"" + zip.getAbsolutePath() + "\","
                + "\"zipBytes\":" + zipBytes + ","
                + "\"convs\":" + convs + ",\"memory\":" + mems + ",\"bytes\":" + bytes + "}";
            EyeLog.w("port", "导出完成：会话 " + convs + " 条 / 记忆 " + mems + " 个");
            return json;
        } catch (Throwable e) {
            EyeLog.w("port", "导出失败: " + e);
            return Json.err("导出失败：" + e);
        }
    }

    /**
     * 把**一段对话**连上下文一起导出，给电脑那边转成 DSH 会话。
     *
     * ## 为什么图片在这里全部 offload 掉
     *
     * 用户明确要求：**配图一律不带**，换成一行占位文字。两个好处：
     *   1. 导出快、包小（一段 30 轮的对话可能带 20 张图，一张 20~30KB）
     *   2. 电脑那边不用再管"图放哪、怎么引用"，转 DSH 会话时也不用碰附件服务
     * 代价是电脑上看不到当时那张图 —— 所以占位文字里把**帧数/口径/token**留下，
     * 至少知道"这里当时有张 4 帧的宫格"。
     *
     * ## 输出（`export/conv-<id>/`）
     *
     * ```
     * meta.json   { app, version, convId, title, at, turns, savedAt, settings:{...} }
     * conv.jsonl  一行一条 JSON：{at, role, text, reasoning, image:{frames,why,tokens}|null}
     * summary.txt 滚动摘要（有才带）
     * ```
     *
     * 格式选 JSONL 而不是手机内部那种 TSV：**正文里本来就有换行和制表符**，
     * TSV 一到导出就得转义，不如一开始就用 JSON。
     */
    static String exportOne(String convId) {
        if (convId == null || convId.length() == 0) return Json.err("没给会话 id");
        java.util.List<Conv.Msg> msgs = ConvStore.load(convId);
        if (msgs == null || msgs.isEmpty()) return Json.err("这段对话是空的");

        File dir = new File(new File(EXPORT_DIR), "conv-" + convId);
        try {
            clean(dir);
            if (!dir.exists() && !dir.mkdirs()) return Json.err("建不了目录（没给存储权限？）");

            StringBuilder jsonl = new StringBuilder(msgs.size() * 256);
            int withImg = 0;
            long firstAt = 0, lastAt = 0;
            for (Conv.Msg m : msgs) {
                if (firstAt == 0) firstAt = m.at;
                lastAt = m.at;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("at", m.at);
                o.put("role", m.role == null ? "user" : m.role);
                o.put("text", m.text == null ? "" : m.text);
                String reason = m.reasoning == null ? "" : m.reasoning;
                if (reason.length() > 0) o.put("reasoning", reason);
                if (m.shots != null && !m.shots.isEmpty()) {
                    Conv.Shot s0 = m.shots.get(0);
                    org.json.JSONObject im = new org.json.JSONObject();
                    im.put("frames", Math.max(1, s0.tokens > 0 ? m.shots.size() : 1));
                    im.put("why", s0.why == null ? "" : s0.why);
                    im.put("tokens", s0.tokens);
                    o.put("image", im);
                    // 正文里留一行占位：电脑上看到这段就知道"当时有图，被省了"
                    o.put("text", (m.text == null ? "" : m.text)
                        + "\n\n（导出的图已省略：约 " + Math.max(1, m.shots.size())
                        + " 帧 · " + s0.tokens + " token）");
                    withImg += 1;
                }
                jsonl.append(o.toString()).append('\n');
            }
            Files.writeText(new File(dir, "conv.jsonl"), jsonl.toString(), false);

            String sum = ConvStore.readSummary(convId);
            if (sum != null && sum.trim().length() > 0) {
                Files.writeText(new File(dir, "summary.txt"), sum, false);
            }

            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("app", "dsh-eye");
            meta.put("version", BuildConfig.VERSION);
            meta.put("convId", convId);
            meta.put("title", ConvStore.readTitle(convId));
            meta.put("at", lastAt);
            meta.put("createdAt", firstAt);
            meta.put("turns", msgs.size());
            meta.put("savedAt", System.currentTimeMillis());
            meta.put("hasSummary", sum != null && sum.trim().length() > 0);
            Files.writeText(new File(dir, "meta.json"), meta.toString(2), false);

            EyeLog.w("port", "导出单段 " + convId + "：" + msgs.size() + " 条（含图 " + withImg + " 条，已 offload）");
            return "{\"ok\":true,\"dir\":\"" + Json.esc(dir.getAbsolutePath()) + "\","
                + "\"convId\":\"" + Json.esc(convId) + "\","
                + "\"msgs\":" + msgs.size() + ",\"images\":" + withImg
                + ",\"title\":\"" + Json.esc(ConvStore.readTitle(convId)) + "\"}";
        } catch (Throwable e) {
            EyeLog.w("port", "导出单段失败: " + e);
            return Json.err("导出失败：" + e);
        }
    }

    /**
     * 给**局域网模式**用：导出并直接把 zip 的 base64 吐出来。
     *
     * 为什么可以这么干：**图片已经在导出时全部 offload 成文字了**，所以包很小
     * （40 条消息 ≈ 24KB，base64 之后 ≈ 32KB）。一条 HTTP 就传完，
     * 不用 `adb pull` 拉目录 —— 不插线也能搬。
     */
    static String exportBase64() {
        String r = export();
        if (!r.contains("\"ok\":true")) return r;
        try {
            org.json.JSONObject o = new org.json.JSONObject(r);
            String zipPath = o.optString("zip", "");
            File z = new File(zipPath);
            if (!z.exists()) return Json.err("zip 没生成出来：" + zipPath);
            if (z.length() > 40L * 1024 * 1024) return Json.err("包太大（" + z.length() + " 字节），走数据线吧");
            byte[] b = Files.read(z, 64 * 1024 * 1024);
            if (b == null) return Json.err("读不了 zip");
            o.put("zip64", android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP));
            o.put("zipBytes", b.length);
            return o.toString();
        } catch (Throwable e) {
            return Json.err("打包失败：" + e);
        }
    }

    // ---------------------------------------------------------------- 导入

    /**
     * 把 `Download/DSH-Go/import/` 里的东西合并进来（PC 用 `adb push` 放进去）。
     *
     * @return 给人看的 JSON（新增/跳过/冲突各几条）
     */
    static String importAll() {
        File dir = new File(IMPORT_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            return "{\"ok\":false,\"error\":\"没有 " + IMPORT_DIR + " 目录：先用 adb push 把导出的内容放进去\"}";
        }
        int convNew = 0, convAppend = 0, memNew = 0, memSame = 0, memConflict = 0, imgs = 0;
        List<String> conflicts = new ArrayList<>();

        try {
            File convIn = new File(dir, "conv");
            File[] fs = convIn.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isDirectory()) {
                        File out = new File(ConvStore.dir(), f.getName());
                        out.mkdirs();
                        File[] imgs2 = f.listFiles();
                        if (imgs2 != null) for (File img : imgs2) {
                            File t = new File(out, img.getName());
                            if (!t.exists()) { Files.copy(img, t); imgs += 1; }
                        }
                        continue;
                    }
                    String n = f.getName();
                    if (!n.endsWith(".jsonl")) continue;
                    int r = mergeJsonl(f, new File(ConvStore.dir(), n));
                    if (r == 0) convNew += 1;
                    else if (r > 0) convAppend += r;
                }
            }

            File memIn = new File(dir, "memory");
            File[] ms = memIn.listFiles();
            if (ms != null) {
                for (File m : ms) {
                    if (m.isDirectory()) continue;
                    File t = new File(Memory.dir(), m.getName());
                    if (!t.exists()) { Files.copy(m, t); memNew += 1; continue; }
                    if (Files.same(m, t)) { memSame += 1; continue; }
                    // 内容不同：**不覆盖**，另存一份等他处理
                    File skip = new File(SKIP_DIR);
                    skip.mkdirs();
                    Files.copy(m, new File(skip, m.getName()));
                    memConflict += 1;
                    conflicts.add(m.getName());
                }
            }

            String json = "{\"ok\":true,\"convNew\":" + convNew + ",\"convAppend\":" + convAppend
                + ",\"memNew\":" + memNew + ",\"memSame\":" + memSame
                + ",\"memConflict\":" + memConflict + ",\"images\":" + imgs
                + ",\"skippedDir\":\"" + SKIP_DIR + "\""
                + ",\"conflicts\":" + Json.arr(conflicts) + "}";
            EyeLog.w("port", "导入完成：" + json);
            return json;
        } catch (Throwable e) {
            EyeLog.w("port", "导入失败: " + e);
            return Json.err("导入失败：" + e);
        }
    }

    /**
     * 追加式合并：只把 `at` 大于本地最大值的那几行追加进去。
     *
     * @return 0 = 本地没有这个会话（整份复制）；>0 = 追加了几条；-1 = 出错/没变化
     */
    /**
     * 局域网模式用：电脑 POST 过来一段 jsonl 文本，直接并进 `<id>.jsonl`。
     *
     * 和目录导入走**同一套合并规则**（按 `at` 增量追加），只是入口不同 ——
     * 一份规则，两个入口，不会出现"插线导和无线导结果不一样"。
     */
    static String importText(String convId, String text) {
        String id = convId == null ? "" : convId.trim().replaceAll("[^0-9A-Za-z._-]", "");
        if (id.length() == 0) return Json.err("name 不合法");
        if (id.endsWith(".jsonl")) id = id.substring(0, id.length() - 6);
        if (text == null || text.trim().length() == 0) return Json.err("body 是空的");

        try {
            ConvStore.dir().mkdirs();
            File dst = new File(ConvStore.dir(), id + ".jsonl");
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String ln : text.split("\n")) if (ln.trim().length() > 0) lines.add(ln);
            int added = mergeLines(lines, dst);
            EyeLog.w("port", "POST 导入 " + id + "：" + added + " 条");
            return "{\"ok\":true,\"convId\":\"" + Json.esc(id) + "\",\"added\":" + added + "}";
        } catch (Throwable e) {
            EyeLog.w("port", "POST 导入失败: " + e);
            return Json.err("导入失败：" + e);
        }
    }

    private static int mergeJsonl(File src, File dst) {
        try {
            if (!dst.exists()) { Files.copy(src, dst); return 0; }
            long localMax = -1;
            BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(dst), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                long at = headAt(line);
                if (at > localMax) localMax = at;
            }
            r.close();

            List<String> add = new ArrayList<>();
            r = new BufferedReader(new InputStreamReader(new FileInputStream(src), "UTF-8"));
            while ((line = r.readLine()) != null) {
                if (line.length() == 0) continue;
                if (headAt(line) > localMax) add.add(line);
            }
            r.close();
            if (add.isEmpty()) return -1;

            Collections.sort(add, new java.util.Comparator<String>() {
                public int compare(String a, String b) { return Long.compare(headAt(a), headAt(b)); }
            });
            FileOutputStream o = new FileOutputStream(dst, true);
            StringBuilder sb = new StringBuilder();
            for (String s : add) sb.append(s).append('\n');
            o.write(sb.toString().getBytes("UTF-8"));
            o.close();
            return add.size();
        } catch (Throwable e) {
            EyeLog.w("port", "合并 " + dst.getName() + " 失败: " + e);
            return -1;
        }
    }

    /** 把若干行按 `at` 增量并进 dst（不存在就新建）。返回实际追加的条数。 */
    private static int mergeLines(java.util.List<String> lines, File dst) throws Exception {
        long localMax = -1;
        if (dst.exists()) {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(dst), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                long at = headAt(line);
                if (at > localMax) localMax = at;
            }
            r.close();
        }
        java.util.List<String> add = new java.util.ArrayList<>();
        for (String ln : lines) {
            if (headAt(ln) > localMax) add.add(ln);
        }
        if (add.isEmpty()) return 0;
        Collections.sort(add, new java.util.Comparator<String>() {
            public int compare(String a, String b) { return Long.compare(headAt(a), headAt(b)); }
        });
        StringBuilder sb = new StringBuilder();
        for (String x : add) sb.append(x).append('\n');
        FileOutputStream o = new FileOutputStream(dst, true);
        o.write(sb.toString().getBytes("UTF-8"));
        o.close();
        return add.size();
    }

    private static long headAt(String line) {
        int tab = line.indexOf('\t');
        try {
            return Long.parseLong(tab > 0 ? line.substring(0, tab) : line);
        } catch (Throwable e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 杂项



    /** 清空目录，但**保留 `conv-*` 子目录**（单会话导出包）。 */
    private static void cleanExceptConvs(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (f.getName().startsWith("conv-")) continue;   // 单会话包，留着
                clean(f);
                f.delete();
            } else {
                f.delete();
            }
        }
    }

    private static void clean(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { clean(f); f.delete(); }
            else f.delete();
        }
    }


    /** 把摊平的目录打成 zip（只为了"发走"方便；PC 那条路不用它）。 */
    private static long zip(File dir, File out) throws Exception {
        java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(
            new java.io.BufferedOutputStream(new FileOutputStream(out)));
        try {
            zdir(z, dir, dir.getAbsolutePath() + "/");
        } finally {
            z.close();
        }
        return out.length();
    }

    private static void zdir(java.util.zip.ZipOutputStream z, File dir, String base) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.equals(new File(EXPORT_DIR, f.getName())) && f.getName().endsWith(".zip")) continue;
            if (f.isDirectory()) { zdir(z, f, base); continue; }
            String full = f.getAbsolutePath();
            if (full.endsWith(".zip")) continue;
            String rel = full.substring(base.length());
            z.putNextEntry(new java.util.zip.ZipEntry(rel));
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) z.write(buf, 0, n);
            in.close();
            z.closeEntry();
        }
    }


}
