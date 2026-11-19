package com.dsh.eye;

/**
 * 错误的**唯一分类出口**。
 *
 * ## 为什么要有这个类
 *
 * 以前每个失败点各自拼一句中文往界面上抛：`Brain` 抛 `HttpError`（带 HTTP 码、带官方 body）、
 * `Asker` 包一层、`EyeService` 再传到 `Ui.onError(msg)`。结果是用户看到
 * `HTTP 400: {"error":{"message":"...cannot be used together..."}}` —— 既看不懂，
 * 又没法知道"该怎么办"。
 *
 * 这里把三件事拆开：
 *   1. **分类**（{@link #of(int, String)}）：HTTP 码 + body 关键词 → 一个枚举；
 *   2. **文案**（{@link #cta(Code)}）：只给人看的中文，**技术细节一个字都不许出现**；
 *   3. **出路**（{@link #retryable(Code)} / {@link #keepDraft(Code)}）：能不能自动重试、
 *      要不要把用户打的字留在输入框里。
 *
 * ## 三条硬纪律（照 ARCH §7.1）
 *
 * 1. 文案里不许出现 HTTP / token / API / Exception 这类技术词 —— UI 只见 {@link #cta(Code)}。
 * 2. `Brain.HttpError.getMessage()` 只进 `EyeLog`，永远不进 `Ui.onError`。
 * 3. 错误**绝不进上下文**：`onFail` 不落盘（这条由 `EyeService` 保证，不是这里的职责）。
 *
 * ## 纯 Java
 *
 * 这个类**不 import 任何 android.* / org.json** —— 它要在电脑 JVM 上被 `T1Test` 直接测。
 */
final class Err {

    private Err() { }

    /** 一次失败的全部语义。界面上要显示的、要不要留现场，都在这一个枚举里。 */
    enum Code {
        /** 没网 / 域名解析不了。**由调用方判**（`IOException` / `UnknownHostException`）。 */
        OFFLINE,
        /** 连上了但迟迟没有回应（`SocketTimeoutException`，或墙钟超过预算）。 */
        TIMEOUT,
        /** 官方接口限流。可退避重试 1 次。 */
        RATE_LIMIT,
        /** key 失效 / 无权限。 */
        AUTH,
        /** 余额不足。 */
        BILLING,
        /** 被官方内容审核挡了（400 + `Content Exists Risk`，或 `finish_reason=content_filter`）。 */
        CONTENT_RISK,
        /** 自定义 function 工具与服务端工具被混在同一个请求里（这是 App 的 bug）。 */
        MIXED_TOOLS,
        /** 联网模式下带了文档附件（先拦住，不发请求）。 */
        DOC_IN_WEB,
        /** 官方 5xx。 */
        SERVER,
        /** 响应读不懂（不是 JSON / 没有 choices / 其余 4xx）。 */
        PARSE,
        /** 文件超过上限（选择期校验，还没上传）。 */
        UPLOAD_TOO_BIG,
        /** 文件传上去了但解析不出来。 */
        UPLOAD_PARSE_FAIL,
        /** 用户自己按了停止。**不显示成错误**。 */
        ABORTED
    }

    /**
     * 一个"完整的错误"：分类 + 文案 + 两个判定。
     *
     * 界面侧只要拿一个 {@code E} 就够画出错误条（文案 + 按钮），不用再回头问分类。
     */
    static final class E {
        Code code;
        String cta;
        boolean retry;
        boolean keepDraft;

        E(Code c) {
            code = c == null ? Code.SERVER : c;
            cta = text(code);
            retry = retryable(code);
            keepDraft = keepDraft(code);
        }

        /** 日志用的一句话（技术细节只在这里出现，界面上不显示）。 */
        String logLine() {
            return "err=" + key(code) + " retry=" + retry;
        }

        public String toString() { return logLine() + " cta=" + cta; }
    }

    /** 从分类造一个 {@code E}。 */
    static E at(Code c) {
        return new E(c);
    }

    /** HTTP 码 + body 一步到位。 */
    static E from(int http, String body) {
        return new E(of(http, body));
    }

    /**
     * **纯分类**：HTTP 码 + 官方 body → 枚举。不抛、不联网、不看界面状态。
     *
     * 判定顺序有讲究：
     *   - 先看 HTTP 码里**没有歧义**的那几个（401/402/403/429/5xx）；
     *   - 再看 400 的 body 关键词（400 是唯一"同一码多种原因"的分支）；
     *   - 其余 4xx 一律 {@code PARSE}（"读不懂"比"猜错原因"诚实）。
     *
     * @param http 0 或负数代表"没拿到响应"（连接就断了）→ {@code OFFLINE}
     */
    static Code of(int http, String body) {
        if (http <= 0) return Code.OFFLINE;
        if (http == 429) return Code.RATE_LIMIT;
        if (http == 401 || http == 403) return Code.AUTH;
        if (http == 402) return Code.BILLING;
        if (http >= 500) return Code.SERVER;
        if (http == 400) {
            String low = body == null ? "" : body.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("content exists risk")
                || low.contains("content_filter")
                || low.contains("sensitive")) return Code.CONTENT_RISK;
            if (low.contains("cannot be used together")) return Code.MIXED_TOOLS;
            // 400 但看不出原因：诚实地当"读不懂"，不猜成限流/欠费
            return Code.PARSE;
        }
        // 2xx 不该走到这里（只在失败路径调用），其余 4xx 一律 PARSE
        return Code.PARSE;
    }

    /**
     * 中文文案。**唯一**给用户看的错误文字。
     *
     * 规范（照 PRD §7）：句号结尾、不用感叹号、不出现技术词、说清是谁的问题。
     */
    static String cta(Code c) {
        return text(c == null ? Code.SERVER : c);
    }

    static String text(Code c) {
        switch (c) {
            case OFFLINE:          return "网络不通。要我等你联网后重试吗？";
            case TIMEOUT:          return "响应超时了，可能是问题太长或者在排队。";
            case RATE_LIMIT:       return "请求太密集了，等一会儿再试。";
            case AUTH:             return "这个 key 好像失效了，去设置里换一个。";
            case BILLING:          return "账户余额不足，去充一下。";
            case CONTENT_RISK:     return "这条被内容审核挡了，换个说法试试。";
            case MIXED_TOOLS:      return "这是 App 的问题（模式配置错），已经把详情记进日志。";
            case DOC_IN_WEB:       return "联网模式下不能读文件，要读文件请切到聊天。";
            case SERVER:           return "服务端出问题了（不是你的锅）。";
            case PARSE:            return "返回的内容看不懂，我记下来了。";
            case UPLOAD_TOO_BIG:   return "这个文件太大了，超过上限，换个小一点的。";
            case UPLOAD_PARSE_FAIL:return "这个文件解析不出来，试试另一份或者截图给我看。";
            case ABORTED:          return "已停止。";
            default:               return "出了点问题，重试一下。";
        }
    }

    /** 能不能**同参数**自动重试 1 次（ARCH §7.1 的"自动重试"列）。 */
    static boolean retryable(Code c) {
        if (c == null) return false;
        switch (c) {
            case TIMEOUT:
            case RATE_LIMIT:
            case SERVER:
            case PARSE:
                return true;
            default:
                return false;
        }
    }

    /**
     * 要不要把用户打的字 / 附件 / 档位留在界面上。
     *
     * 目前**所有分类都留**（连 {@code ABORTED} 也留 —— 用户按了停止就是想改口重说）。
     * 之所以还留这个方法：将来若有"请求已被服务端接受、草稿不再有效"的情况，只改这里。
     */
    static boolean keepDraft(Code c) {
        return true;
    }

    /** 日志/统计用的稳定小写键（不要拿来当界面文案）。 */
    static String key(Code c) {
        if (c == null) return "server";
        switch (c) {
            case OFFLINE:           return "offline";
            case TIMEOUT:           return "timeout";
            case RATE_LIMIT:        return "rate_limit";
            case AUTH:              return "auth";
            case BILLING:           return "billing";
            case CONTENT_RISK:      return "content_risk";
            case MIXED_TOOLS:       return "mixed_tools";
            case DOC_IN_WEB:        return "doc_in_web";
            case SERVER:            return "server";
            case PARSE:             return "parse";
            case UPLOAD_TOO_BIG:    return "upload_too_big";
            case UPLOAD_PARSE_FAIL: return "upload_parse_fail";
            case ABORTED:           return "aborted";
            default:                return "server";
        }
    }
}
