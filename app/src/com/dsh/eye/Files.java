package com.dsh.eye;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 文件小工具。以前 `ConvStore.copy` 和 `Portable.copyFile` 是同一段代码的两份拷贝
 * （连 8KB/16KB 的缓冲区都不一样大），合成一处。
 */
final class Files {

    private Files() { }

    /** 整份复制，返回字节数。父目录会自动建。 */
    static long copy(File from, File to) throws Exception {
        if (from == null || !from.exists()) return 0;
        File parent = to.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to, false);
        byte[] buf = new byte[16 * 1024];
        long n = 0;
        try {
            int k;
            while ((k = in.read(buf)) > 0) { out.write(buf, 0, k); n += k; }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { out.close(); } catch (Throwable ignored) { }
        }
        return n;
    }

    /** 读整个文件（超过 limit 就截断），失败返回 null。 */
    static byte[] read(File f, int limit) {
        if (f == null || !f.exists()) return null;
        try {
            int want = (int) Math.min(f.length(), limit);
            byte[] b = new byte[want];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            try {
                while (off < want) {
                    int k = in.read(b, off, want - off);
                    if (k <= 0) break;
                    off += k;
                }
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            return off == want ? b : java.util.Arrays.copyOf(b, off);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 读成文本，失败返回 null。 */
    static String readText(File f, int limit) {
        byte[] b = read(f, limit);
        if (b == null) return null;
        try {
            return new String(b, "UTF-8");
        } catch (Throwable e) {
            return null;
        }
    }

    static boolean write(File f, byte[] data, boolean append) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            FileOutputStream o = new FileOutputStream(f, append);
            try {
                o.write(data);
            } finally {
                o.close();
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    static boolean writeText(File f, String s, boolean append) {
        try {
            return write(f, s.getBytes("UTF-8"), append);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 两个文件内容是否完全一样（比较前先比长度，省一次读）。 */
    static boolean same(File a, File b) {
        if (a == null || b == null || !a.exists() || !b.exists()) return false;
        if (a.length() != b.length()) return false;
        byte[] x = read(a, Integer.MAX_VALUE);
        byte[] y = read(b, Integer.MAX_VALUE);
        return x != null && y != null && java.util.Arrays.equals(x, y);
    }

    static ByteArrayOutputStream emptyOut(int hint) {
        return new ByteArrayOutputStream(hint);
    }
}
