package com.dsh.eye;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 花费估算。**只做估算，不声称账单** —— 官方牌价会变，改动只在本文件。
 *
 * ## 口径（2026-11-22 对着官方价目表核过一遍）
 *
 * 单位：**美元 / 百万 token**。
 *
 * | 模型 | | 缓存命中 | 缓存未命中 | 输出 |
 * |---|---|---|---|---|
 * | `deepseek-flash` | 空闲 | 0.003 | 0.15 | 0.60 |
 * | `deepseek-flash` | 高峰 | 0.006 | 0.30 | 1.20 |
 * | `deepseek-v4-pro` | 空闲 | 0.022 | 0.66 | 1.98 |
 * | `deepseek-v4-pro` | 高峰 | 0.044 | 1.32 | 3.96 |
 *
 * 高峰 = UTC **01:00-04:00** 与 **06:00-10:00**（周一至周五；中国法定节假日官方也算空闲，
 * 但本地没有节假日表，**节假日会按高峰算** —— 方向是"可能略微高估"，不会低报）。
 *
 * ## 旧版错在哪（2.5.8 及之前）
 *
 * 1. **只算最后一轮**：`Asker` 把 `promptTokens` 覆盖成"最后一次非空"，而一次 5 轮的工具
 *    调用是**真的付了 5 次输入** —— 显示值只有实际的一小半。
 * 2. **不认缓存命中**：`usage` 只读了 `prompt_tokens`，命中价只有 1/50，长期对话全按
 *    未命中算 → 明显高估。
 * 3. **没算峰谷**：同一份请求高峰是空闲的 2×。
 * 4. **搜索计费口径过时**：2026-11-18 12:00 起**服务端** `web_search`/`fetch` 不再按次收费
 *    （结果按 token 计）；但我们混合模式走的原子接口 `/v1/tools/search` **仍然**
 *    $0.75 / 千次。两者必须分开记。
 * 5. **漏了文件解析费**：官方 $0.225 / 千页。一本 300 页的书 = $0.0675，以前一分不记。
 *
 * ## 为什么仍然"只是估算"
 *
 * - 图像按 token 折算，官方的折算系数没公开，我们只信 `usage.prompt_tokens`；
 * - `usage` 拿不到时（流中断、兼容端点不给）按 0 算，**这一轮会少记**，日志里有 `usage=0` 可查；
 * - 完整对账以官方账单为准。
 */
final class Cost {

    private Cost() { }

    // ---- 每百万 token 的美元价：{ 缓存命中, 缓存未命中, 输出 } ----
    private static final double[] FLASH_OFF  = { 0.003, 0.15, 0.60 };
    private static final double[] FLASH_PEAK = { 0.006, 0.30, 1.20 };
    private static final double[] PRO_OFF    = { 0.022, 0.66, 1.98 };
    private static final double[] PRO_PEAK   = { 0.044, 1.32, 3.96 };

    /** 原子接口按次计费（美元/次）。服务端工具 **不** 走这里（见类注释第 4 条）。 */
    static final double SEARCH_CALL = 0.00075;   // POST /v1/tools/search
    static final double FETCH_CALL = 0.00075;    // POST /v1/tools/fetch
    /** 文件解析（含 OCR）：$0.225 / 千页。 */
    static final double PARSE_PAGE = 0.000225;

    /**
     * 一轮（可能含多次工具往返）的真实用量。
     *
     * 字段全 int，因为 `usage` 本来就是整数；求和也不会溢出（1M × 16 轮 < 2^31）。
     */
    static final class Use {
        /** 缓存命中的输入 token（便宜的那部分）。 */
        int hit;
        /** 缓存未命中的输入 token。 */
        int miss;
        /** 输出 token（含思考）。 */
        int out;
        /** 这一轮我们自己打的原子搜索/读网页次数（混合模式）。 */
        int search;
        int fetch;
        /** 这一轮新上传/首次引用的文档页数（文件解析费）。 */
        int pages;

        int prompt() { return hit + miss; }

        void add(Use o) {
            if (o == null) return;
            hit += o.hit;
            miss += o.miss;
            out += o.out;
            search += o.search;
            fetch += o.fetch;
            pages += o.pages;
        }
    }

    /**
     * 现在是不是高峰。
     *
     * 判据只有 UTC 小时 + 星期：04:00-06:00 这段"午休"和周末都算空闲。
     * 中国法定节假日不内置 —— 那几天会按高峰算（高估，不会低报）。
     */
    static boolean peak(long atMs) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(atMs);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
        int h = c.get(Calendar.HOUR_OF_DAY);
        return (h >= 1 && h < 4) || (h >= 6 && h < 10);
    }

    /** 当前这一轮该用哪一档价（`pro` 按名字里的 `-pro` 判，和 `Caps.isPro` 同口径）。 */
    private static double[] rates(String model, boolean peak) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT).trim();
        boolean pro = m.equals(Caps.MODEL_PRO) || m.endsWith("-pro");
        if (pro) return peak ? PRO_PEAK : PRO_OFF;
        return peak ? FLASH_PEAK : FLASH_OFF;
    }

    /** 这一轮的估算花费（美元）。 */
    static double of(String model, Use u, boolean peak) {
        if (u == null) return 0;
        double[] r = rates(model, peak);
        double usd = u.hit / 1_000_000.0 * r[0]
            + u.miss / 1_000_000.0 * r[1]
            + u.out / 1_000_000.0 * r[2];
        usd += u.search * SEARCH_CALL + u.fetch * FETCH_CALL;
        usd += u.pages * PARSE_PAGE;
        return usd <= 0 || Double.isNaN(usd) ? 0 : usd;
    }

    /** 只算 token 部分（不含按次计费），界面拆账用。 */
    static double tokensOnly(String model, Use u, boolean peak) {
        if (u == null) return 0;
        double[] r = rates(model, peak);
        return u.hit / 1_000_000.0 * r[0]
            + u.miss / 1_000_000.0 * r[1]
            + u.out / 1_000_000.0 * r[2];
    }

    /** 这一轮有几个计次的网络工具（**只看原子接口**；服务端工具已不按次收费）。 */
    static int countTools(java.util.List<Proto.Step> steps) {
        if (steps == null) return 0;
        int n = 0;
        for (Proto.Step s : steps) {
            if (s == null) continue;
            String t = s.callType == null ? "" : s.callType;
            if (t.contains("web_search") || t.contains("read_url")) n += 1;
        }
        return n;
    }

    /** 美元显示：小于 1 分用 6 位，往上用 4 位；0 显示 $0。 */
    static String fmt(double usd) {
        if (usd <= 0 || Double.isNaN(usd)) return "$0";
        if (usd < 0.01) return String.format(Locale.ROOT, "$%.6f", usd);
        return String.format(Locale.ROOT, "$%.4f", usd);
    }

    /** 顶栏副标题用的短格式（空间小，1 分以下保留 4 位）。 */
    static String fmtShort(double usd) {
        if (usd <= 0 || Double.isNaN(usd)) return "$0";
        if (usd < 0.01) return String.format(Locale.ROOT, "$%.4f", usd);
        if (usd < 1) return String.format(Locale.ROOT, "$%.3f", usd);
        return String.format(Locale.ROOT, "$%.2f", usd);
    }

    /** 缓存命中率（0~1）。没有输入就是 0。 */
    static double hitRate(Use u) {
        int p = u == null ? 0 : u.prompt();
        return p <= 0 ? 0 : (double) u.hit / p;
    }

    /**
     * 缓存**省下来**多少钱：命中部分按（未命中价 − 命中价）算。
     *
     * 显示这个数是因为它是"缓存有没有起作用"的唯一直观指标 ——
     * 命中价只有未命中的 1/50，一场长对话里它比"总花费"更能说明问题。
     */
    static double savedByCache(String model, int hitTokens, boolean peak) {
        if (hitTokens <= 0) return 0;
        double[] r = rates(model, peak);
        return hitTokens / 1_000_000.0 * Math.max(0, r[1] - r[0]);
    }
}
