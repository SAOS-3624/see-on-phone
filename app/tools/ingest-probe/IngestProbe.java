package com.dsh.eye;

import java.nio.file.*;
import java.util.*;

/**
 * 切片器的命令行自测：`javac` 单文件就能跑，不依赖 Android。
 * 这是把 DocIngest 从 Pet 里拆出来的收益之一 —— 纯函数可以脱离手机验。
 */
public class IngestProbe {
    public static void main(String[] a) throws Exception {
        String doc = new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8");
        List<String> segs = DocIngest.split(doc);
        int hi = 0;
        System.out.println("段数 = " + segs.size());
        for (int i = 0; i < segs.size(); i++) {
            double w = DocIngest.weight(segs.get(i));
            if (w > 0.8) hi++;
            System.out.println(String.format("%2d [%.2f] %s", i + 1, w, segs.get(i)));
        }
        System.out.println("其中高权重 " + hi + " 条");
        for (String s : segs) {
            if (s.startsWith("#") || s.length() < 4) throw new AssertionError("切片有脏数据: " + s);
        }
        System.out.println("断言通过：没有标题行、没有过短的碎片");
    }
}
