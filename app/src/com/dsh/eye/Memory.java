package com.dsh.eye;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「长期设定 / 记忆」：一段用户自己写的文字 + 一堆可导入的文档，拼成 system prompt 的尾巴。
 *
 * ## 为什么是"文档注入"而不是把养鱼那套搬回来
 *
 * 0.11.0 删掉的 `Pet` 是**代码生成的人格**（五层记忆、情绪、主动心跳，1,749 行），
 * 它替用户决定了"该记什么"。这里反过来了：**用户写什么就记什么** ——
 * 一段人设、一份说明书、几条家规，放进 `memory/` 就生效。
 *
 * 对"想养鱼"的人来说够用：写一段「你是缸里的一条鱼，说话要带气泡音」就是养鱼；
 * 不想养了把开关一关，人格就不进 prompt。**代码里不预设任何人格。**
 *
 * ## 目录
 *
 * ```
 * /sdcard/Download/DSH-Go/memory/
 *   01-人设.txt      按文件名排序拼起来，所以可以用数字前缀控制先后
 *   02-家规.md
 * ```
 *
 * ## 上限
 *
 * 注入的文字按 {@link #MAX_TOKENS} 截断 —— 这段是**每一问都要重发**的，
 * 一份 5 万字的说明书塞进去，等于每问多花几毛钱。超了会在末尾写明还漏了几个文件，
 * 不静默截断（用户得知道自己的东西没全进去）。
 */
final class Memory {

    /** 记忆目录。和 eye.log / say.txt 一样写死绝对路径（别用 Environment 拼，会少 Download/ 一层）。 */
    static final String DIR = "/sdcard/Download/DSH-Go/memory";

    /**
     * 注入上限（估算 token）。1500 ≈ 2200 个汉字，够放一份人设 + 一页设定；
     * 再多就属于"每问都在给模型念书"，钱会花在没人看的地方。
     */
    static final int MAX_TOKENS = 1500;

    private Memory() { }

    static File dir() {
        File d = new File(DIR);
        if (!d.exists() && !d.mkdirs()) {
            EyeLog.w("mem", "建不了 " + DIR + "，多半是没给存储权限");
        }
        return d;
    }

    /** 目录里的文档，按文件名排序（数字前缀控制先后）。只认 txt/md。 */
    static List<File> files() {
        List<File> out = new ArrayList<>();
        File[] fs = dir().listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase();
            if (n.endsWith(".txt") || n.endsWith(".md")) out.add(f);
        }
        Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    static int count() { return files().size(); }

    static String names() {
        StringBuilder sb = new StringBuilder();
        for (File f : files()) {
            if (sb.length() > 0) sb.append("、");
            sb.append(f.getName());
        }
        return sb.toString();
    }

    /**
     * 拼出要注入的那一段。**空串 = 什么都不注入**（没开开关、没写人设、目录也空）。
     *
     * 顺序：人设（用户手写的那段）在前，文档在后 —— 人设是"我是谁"，文档是"我知道什么"。
     */
    static String block(Config cfg) {
        if (cfg == null || !cfg.memInject()) return "";
        StringBuilder sb = new StringBuilder(2048);
        String persona = cfg.persona();
        if (persona != null && persona.trim().length() > 0) {
            sb.append("【人设】\n").append(persona.trim()).append('\n');
        }
        List<File> fs = files();
        int skipped = 0;
        for (File f : fs) {
            String body = read(f);
            if (body == null || body.trim().length() == 0) continue;
            String piece = "\n【" + f.getName() + "】\n" + body.trim() + "\n";
            if (Conv.textTokens(sb.toString() + piece) > MAX_TOKENS) { skipped += 1; continue; }
            sb.append(piece);
        }
        if (skipped > 0) {
            sb.append("\n（还有 ").append(skipped).append(" 个文件因为超出注入上限没读，")
              .append("想让它进来就删掉前面的或精简一下）\n");
        }
        if (sb.length() == 0) return "";
        return "以下是用户的长期设定与资料，当作你已经知道的背景，不要复述、不要问它从哪来：\n"
            + sb.toString();
    }

    /**
     * 目录里的**全部正文**（不含人设、不含包装话术）。
     *
     * 为什么单独有一个口子：`memory_write` 要判断"总量有没有超"，
     * 而 {@link #block} 会受开关影响、还会加上人设 —— 那是"注入什么"，
     * 这里是"磁盘上有多少"，两件事。
     */
    static String raw() {
        StringBuilder sb = new StringBuilder(2048);
        for (File f : files()) {
            String body = read(f);
            if (body == null) continue;
            sb.append("\n【").append(f.getName()).append("】\n").append(body.trim()).append('\n');
        }
        return sb.toString();
    }

    /** 记忆目录里**单条**删掉一个文件（设置页"忘掉这一条"）。 */
    static boolean remove(String name) {
        if (name == null) return false;
        for (File f : files()) {
            if (f.getName().equals(name)) return f.delete();
        }
        return false;
    }

    static String read(File f) {
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
            EyeLog.w("mem", "读 " + f.getName() + " 失败: " + e);
            return null;
        }
    }

    /** 文件名消毒：去掉路径分隔符和会让人排序错乱的前导点。 */
    private static String safeName(String n) {
        String s = n == null ? "" : n.trim();
        s = s.replace('/', '_').replace('\\', '_').replace('\u0000', '_');
        if (s.length() == 0) s = "doc.txt";
        if (s.startsWith(".")) s = "a" + s;
        if (s.length() > 60) s = s.substring(0, 56) + ".txt";
        String low = s.toLowerCase();
        if (!low.endsWith(".txt") && !low.endsWith(".md")) s = s + ".txt";
        return s;
    }

    /** 清空记忆：删目录里所有文档（不删人设，人设在设置里手改）。 */
    static int clear() {
        int n = 0;
        for (File f : files()) {
            if (f.delete()) n += 1;
        }
        return n;
    }

    /** 排障用：把当前会注入的内容原样吐出来（设置页「看一眼」用）。 */
    static String preview(Config cfg) {
        String b = block(cfg);
        if (b.length() == 0) return "（现在没有注入任何东西）";
        int t = Conv.textTokens(b);
        String head = b.length() > 600 ? b.substring(0, 600) + "\n…（后面还有 " + (b.length() - 600) + " 字）" : b;
        return head + "\n\n—— 约 " + t + " token（上限 " + MAX_TOKENS + "）";
    }

    /** 给设置页显示的一句话摘要。 */
    static String status(Config cfg) {
        int n = count();
        int t = Conv.textTokens(block(cfg));
        StringBuilder sb = new StringBuilder();
        sb.append(n).append(" 个文件");
        if (cfg != null && cfg.persona().trim().length() > 0) sb.append(" + 人设");
        sb.append("，约 ").append(t).append(" token");
        if (cfg == null || !cfg.memInject()) sb.append("（开关关着，不会注入）");
        return sb.toString();
    }

    /** 目录里的文件名清单（排障 / 设置页展示）。 */
    static String listText() {
        List<File> fs = files();
        if (fs.isEmpty()) return "目录是空的：" + DIR;
        StringBuilder sb = new StringBuilder();
        for (File f : fs) sb.append(f.getName()).append("（").append(f.length()).append(" 字节）\n");
        sb.append("\n目录：").append(DIR);
        return sb.toString();
    }
}
