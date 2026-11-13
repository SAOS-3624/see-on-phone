package com.dsh.eye;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 工作区 —— 它**能自己读写文件**的那块地。
 *
 * ## 为什么只给一个目录，不给"整个手机"
 *
 * Android 11+ 的分区存储下，普通应用想全盘写只有两条路：`MANAGE_EXTERNAL_STORAGE`
 * （应用商店会拒）或者 SAF 让用户一个个目录授权（体验很重）。而 99% 的真实需求是
 * "**把我说的东西存下来**"，不是"随便改系统文件"。
 *
 * 所以这里开一块固定的地：`Download/DSH-Go/workspace/`。
 * 用户在文件管理器里能直接看到、能拷走、能删 —— **不需要任何额外权限**，
 * 因为它就在 Download 下面。
 *
 * ## 这个类是唯一的安全边界
 *
 * 模型给的是**相对路径字符串**，它可能编出 `../../memory/00.txt` 这种。所以：
 *   - 拒绝绝对路径
 *   - 拒绝任何 `..` 段
 *   - 解析完再确认 `canonicalPath` 真的还在根目录里（防符号链接 / 大小写 / 反斜杠花样）
 *
 * 三道里最后一道才是真正管用的，前两道只是让报错更好懂。
 */
final class Workspace {

    static final String ROOT = "/sdcard/Download/DSH-Go/workspace";

    /** 一次写的上限。手机上写超过这个数，基本是模型把整段代码当文件灌进来了。 */
    static final int MAX_WRITE = 512 * 1024;
    /** 一次读的上限：超了就截断并告诉它"后面还有多少没显示"。 */
    static final int MAX_READ = 64 * 1024;
    static final int MAX_LIST = 200;

    private Workspace() { }

    /**
     * 测试用：把根目录指到别处（JVM 上没有 /sdcard）。
     * 生产路径永远是 {@link #ROOT} —— 只有 {@code WsTest} 会调它。
     */
    private static String rootOverride;

    static void setRootForTest(String p) { rootOverride = p; }

    static String root() { return rootOverride != null ? rootOverride : ROOT; }

    static File dir() {
        File d = new File(root());
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /**
     * 相对路径 → 真实 File，**并且保证在根目录里**。
     *
     * @throws IllegalArgumentException 越界或路径非法
     */
    static File resolve(String rel) {
        String p = rel == null ? "" : rel.trim().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        if (p.length() == 0) throw new IllegalArgumentException("path 是空的");
        if (p.startsWith("/")) throw new IllegalArgumentException("只收相对路径，不要以 / 开头");
        for (String seg : p.split("/")) {
            if (seg.equals("..")) throw new IllegalArgumentException("不许用 .. 跳出去");
        }
        if (p.length() > 200) throw new IllegalArgumentException("路径太长了");

        File root = dir();
        File f = new File(root, p);
        try {
            String rp = root.getCanonicalPath();
            String fp = f.getCanonicalPath();
            if (!fp.equals(rp) && !fp.startsWith(rp + File.separator)) {
                throw new IllegalArgumentException("跑到工作区外面去了：" + p);
            }
            return f;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("路径不合法：" + p);
        }
    }

    // ---------------------------------------------------------------- 操作

    /** 列目录（给模型看的清单）。 */
    static String list(String rel) {
        File d;
        try {
            d = (rel == null || rel.trim().length() == 0) ? dir() : resolve(rel);
        } catch (Throwable e) {
            return Json.err(e.getMessage());
        }
        if (!d.exists()) return "{\"note\":\"这个目录还没有东西\",\"path\":\"" + Json.esc(rel) + "\"}";
        if (!d.isDirectory()) return read(rel);

        File[] fs = d.listFiles();
        if (fs == null) return Json.err("读不了这个目录");
        java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareToIgnoreCase(b.getName()); }
        });

        StringBuilder b = new StringBuilder();
        b.append("{\"root\":\"").append(root()).append("\",\"path\":\"").append(Json.esc(rel)).append("\"");
        int n = 0;
        StringBuilder items = new StringBuilder();
        for (File f : fs) {
            if (f.getName().startsWith(".")) continue;          // 隐藏文件不展示
            if (n >= MAX_LIST) break;
            if (items.length() > 0) items.append(',');
            items.append("{\"name\":\"").append(Json.esc(f.getName())).append('"')
                 .append(",\"dir\":").append(f.isDirectory())
                 .append(",\"bytes\":").append(f.isDirectory() ? 0 : f.length())
                 .append(",\"at\":").append(f.lastModified()).append('}');
            n += 1;
        }
        b.append(",\"count\":").append(n).append(",\"items\":[").append(items).append("]}");
        return b.toString();
    }

    static String read(String rel) {
        File f;
        try {
            f = resolve(rel);
        } catch (Throwable e) {
            return Json.err(e.getMessage());
        }
        if (!f.exists()) return Json.err("没有这个文件：" + rel);
        if (f.isDirectory()) return list(rel);
        if (f.length() > MAX_WRITE) return Json.err("太大了（" + f.length() + " 字节），不是文本文件吧");

        try {
            long len = f.length();
            int want = (int) Math.min(len, MAX_READ);
            byte[] buf = new byte[want];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < want) {
                int k = in.read(buf, off, want - off);
                if (k <= 0) break;
                off += k;
            }
            in.close();
            String text = new String(buf, 0, off, "UTF-8");
            String tail = len > MAX_READ ? "\n\n…（后面还有 " + (len - MAX_READ) + " 字节没显示）" : "";
            return "{\"path\":\"" + Json.esc(rel) + "\",\"bytes\":" + len
                + ",\"text\":" + Json.quote(text + tail) + "}";
        } catch (Throwable e) {
            return Json.err("读失败：" + e);
        }
    }

    static String write(String rel, String text, boolean append) {
        if (text == null) text = "";
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Throwable e) {
            return Json.err("编码失败");
        }
        if (data.length > MAX_WRITE) {
            return Json.err("一次写太大了（" + data.length + " 字节，上限 " + MAX_WRITE + "）。分成几次写。");
        }
        File f;
        try {
            f = resolve(rel);
        } catch (Throwable e) {
            return Json.err(e.getMessage());
        }
        if (f.isDirectory()) return Json.err("这是个目录，不是文件");
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return Json.err("建不了上级目录");

        try {
            FileOutputStream o = new FileOutputStream(f, append);
            o.write(data);
            o.close();
            EyeLog.w("ws", (append ? "追加" : "写入") + " " + rel + " " + data.length + "B");
            return "{\"ok\":true,\"path\":\"" + Json.esc(rel) + "\",\"bytes\":" + f.length()
                + ",\"mode\":\"" + (append ? "append" : "replace")
                + "\",\"abs\":\"" + f.getAbsolutePath() + "\"}";
        } catch (Throwable e) {
            return Json.err("写失败：" + e);
        }
    }

    static String delete(String rel) {
        File f;
        try {
            f = resolve(rel);
        } catch (Throwable e) {
            return Json.err(e.getMessage());
        }
        if (!f.exists()) return Json.err("本来就没有：" + rel);
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null && fs.length > 0) return Json.err("目录里还有东西，先删里面的");
        }
        boolean ok = f.delete();
        return ok ? "{\"ok\":true,\"deleted\":\"" + Json.esc(rel) + "\"}" : Json.err("删不掉（权限？）");
    }

    /** 设置页显示用。 */
    static int count() {
        return countIn(dir(), 0);
    }

    private static int countIn(File d, int depth) {
        if (depth > 4) return 0;
        File[] fs = d.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isDirectory()) n += countIn(f, depth + 1);
            else n += 1;
        }
        return n;
    }



}
