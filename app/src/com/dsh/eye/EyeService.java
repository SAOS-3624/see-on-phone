package com.dsh.eye;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Map;

/**
 * 唯一的常驻服务：相机 + HTTP + 语音 + 会话，一个进程全包。
 *
 * ## v4 里它变了什么
 *
 * 0.13.x 里这个类 1638 行，因为它同时是"相机看守"、"连续看调度"、"会话存储"和
 * "HTTP 后端"。v4 把这些**按职责划走**：
 *
 * | 原来在这儿的 | 现在在哪 |
 * |---|---|
 * | 直连 HTTP + 一轮回答 | {@link Brain}（只发字节，不认识会话） |
 * | 工具循环 / reasoning 回传 | {@link Asker} |
 * | 抓帧攒箱拼图 | {@link Looker} |
 * | 工具表与参数校验 | {@link Tools} |
 *
 * 留下来的只有"**谁在什么时候该发生什么**"：会话属于谁、相机借给谁、说完话念不念。
 *
 * ## 相机是借来的，不是开着的
 *
 * v4 的核心约束：**没人要就不开相机**（不耗电、不亮指示灯、不发热）。
 *
 * ```
 * look 工具 / /frame.jpg / /video/mjpeg  →  camAcquire()
 * 用完（或流断开）                        →  camRelease() → 3 秒后真关
 * 界面（会话页）                          →  从不申请相机
 * ```
 *
 * 计数而不是布尔：`/frame.jpg` 抓图的同时插件可能连着 MJPEG，谁先走都不该把另一个关掉。
 */
public class EyeService extends Service implements CameraEngine.Sink, HttpServer.Source, Tools.Env {

    private static final String TAG = "DshEye";
    private static final String CHANNEL_ID = "dsh-eye";
    private static final int NOTI_ID = 4711;
    private static final String WAKE_ACTION = "com.dsh.eye.WAKE";

    public static final String ACTION_STOP = "com.dsh.eye.STOP";
    public static final String ACTION_TORCH = "com.dsh.eye.TORCH";
    public static final String ACTION_FLIP = "com.dsh.eye.FLIP";
    public static final String ACTION_SAY = "com.dsh.eye.SAY";

    /** 老插件的落点，保留只为"已经在手机上的 0.1.x"不用改就能出声。 */
    private static final String SAY_FILE = "/sdcard/Download/DSH-Go/say.txt";
    private static final String STATUS_FILE = "/sdcard/Download/DSH-Go/tts-status.txt";
    /**
     * 「正常 / 导盲」开关的落点。插件每 400ms 轮询一次（`dsh-see-on-phone/src/mode.js`）。
     *
     * v4 删掉了两种模式，但**这个文件仍然恒写 `normal`** —— 桌面插件还在读它，
     * 停写会让它回落（不崩，但那是"我这边变了、你那边不知道"的错位）。
     * 等插件同版本删掉 `guide` 分支，这个文件才停写。
     */
    private static final String MODE_FILE = "/sdcard/Download/DSH-Go/mode.txt";

    /** 相机没人要之后多久真关（毫秒）。断开的插件可能马上重连，关太快等于反复冷启动。 */
    private static final long CAM_LINGER_MS = 3000;

    private static volatile EyeService instance;

    private Config cfg;
    private CameraEngine engine;
    private Speaker speaker;
    private HttpServer http;
    private Looker looker;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Object frameLock = new Object();
    private volatile HttpServer.Frame frame;
    private volatile long seq;
    private volatile boolean running;

    // ---------------------------------------------------------------- 相机借用

    private int camHolders;
    private long camReleaseAt;
    private long lastCamOpenAt;
    private final Object camLock = new Object();

    // ---------------------------------------------------------------- 会话

    /**
     * 当前会话的消息（内存镜像 = 磁盘上的真相）。
     *
     * 真正的存储是 `ConvStore`（`/sdcard/Download/DSH-Go/conv/<id>.jsonl`），
     * 这份 list 只是把它读进内存，避免每问一句都扫一遍文件。
     */
    private final java.util.List<Conv.Msg> history = new java.util.ArrayList<>();
    /** 这一轮用户说的话，回答回来时要配成一对存进 history。 */
    private volatile String pendingUser;
    /** 滚动的"前情摘要"。 */
    private volatile String convSummary = "";
    private volatile long convSummaryUpTo;
    private final java.util.concurrent.atomic.AtomicBoolean summarizing =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 摘要专用线程（排在对话线程里会拖慢下一句）。 */
    private final java.util.concurrent.ExecutorService sumQ =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    /** 逐轮状态（`look` 的幂等与"这一轮那张图"）。 */
    private int lookCount;
    private Tools.Look lastLook;
    /**
     * 这一轮**自带**的图（相册选的）**不放字段**，用 `send()` 里的局部变量传 ——
     * `Asker.ask()` 开头会调 `env.newTurn()`，字段会在那里被清掉。见 send() 的注释。
     */
    /** 上一次落盘的回答时间戳（界面拿它做"重说/删这段"的定位）。 */
    private volatile long lastAssistantAt;

    /**
     * 「待发送的相册图」——**存在服务里，不是存在 Activity 字段里**。
     *
     * 为什么：Activity 会因为旋屏、深色模式切换、被系统回收而重建，字段全丢；
     * 而用户刚选好 4 张图正准备发 —— 图丢了是最气人的那种 bug（上一版就是这样，
     * 交接文档第 6 节第 4 条点名了这个）。服务是前台服务，生命周期比界面长得多。
     * 界面重建后进来 `pendingPaths()` 一取就还原。
     */
    private final java.util.List<String> pendingPaths = new java.util.ArrayList<>();
    private final java.util.List<byte[]> pendingBytes = new java.util.ArrayList<>();

    /** 界面写：把"还没发出去的图"整批同步过来（传空 = 清空）。 */
    synchronized void stashPending(java.util.List<String> paths, java.util.List<byte[]> bytes) {
        pendingPaths.clear();
        pendingBytes.clear();
        if (paths != null) pendingPaths.addAll(paths);
        if (bytes != null) pendingBytes.addAll(bytes);
    }

    synchronized java.util.List<String> pendingPaths() {
        return new java.util.ArrayList<>(pendingPaths);
    }

    synchronized java.util.List<byte[]> pendingBytes() {
        return new java.util.ArrayList<>(pendingBytes);
    }

    /** 当前这一轮是谁在跑（打断用）。 */
    private volatile Asker current;

    /**
     * 对话线程。**单线程队列，不是"每句话一个 new Thread"**。
     *
     * 以前每发一句就起一个线程：连发五句 = 五个线程五个 socket（每个都卡在
     * `HttpURLConnection` 的读上，interrupt 对它无效），直到超时才一个个退。
     * 现在 `cancelTurn → Brain.abort()` 会直接把连接掐断，旧线程秒退，
     * 单线队列天然就是"一句接一句"。
     */
    private final java.util.concurrent.ExecutorService asks =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    // ---------------------------------------------------------------- 界面回调

    /**
     * 会话页要的三类事件。
     *
     * 为什么不做成"服务保存一个消息列表、界面来拉"：那个列表和磁盘上的会互相打架
     * （同一句话既在内存又在文件里，界面重进就重复）。所以约定是：
     * **落盘只有服务做，界面只显示事件**；重进界面时从磁盘重读。
     */
    /**
     * 通话页要的"实时画面"。**只有它在前台的时候才有接收方** ——
     * 没人接就把位图回收掉，不能指望 GC（15fps × 480×640 ≈ 每帧 600KB）。
     */
    interface PreviewSink {
        /** 已经转好向的位图，**所有权转移给接收方**（它负责 recycle 掉旧的）。 */
        void onPreview(android.graphics.Bitmap bmp);
    }

    interface Ui {
        /** 工具状态：`name` + running/ok/fail。 */
        void onTool(String name, String phase, String detail);
        /**
         * 回答落地（含思考过程，界面折叠显示）。
         *
         * @param imageFiles 这一轮那几张图的落盘路径（相册多选就有多条；空 list = 没图）
         * @param at         这条消息在会话里的时间戳（"重说/删这段"要用它定位）
         */
        void onReply(String text, String reasoning, long thinkMs,
                     java.util.List<String> imageFiles, long at);
        /** 流式增量：界面把它追加到"正在说"的气泡后面。 */
        void onDelta(String text);
        /** 这一轮的增量作废（模型改主意去调工具了），界面撤掉临时气泡。 */
        void onRoundReset();
        /**
         * 这一轮**什么都不用显示**（自动看之后判断"没变化"，整轮丢弃）。
         * 界面把临时状态收掉、回到"在看"就行。
         */
        void onQuiet();
        void onError(String msg);
        void onState();
        /**
         * **拼好的那张图**（实时预览）。和真发出去的那张走同一个函数、同一批帧，
         * 所以"预览长什么样 = 发出去什么样"。跑在后台线程，**每抓一帧重排一次**（5fps ⇒ 200ms）。
         *
         * @param cells   这张里有几格（就是图上画的格子数，不是"窗口里采了多少帧"）
         * @param spanSec 这张覆盖了多少秒
         *
         * 这两个数必须**跟着这张图一起来** —— 以前是界面自己去问"攒了几帧"，
         * 于是标签写"1 格"、图上画着 4 格，两边对不上（用户当场就问过）。
         */
        default void onSheetPreview(byte[] jpeg, int cells, double spanSec) { }
    }

    private volatile Ui uiSink;
    private volatile PreviewSink previewSink;
    /** 最近说出口的一句（通话页刚打开时先把它显示出来，别留一片空白）。 */
    private volatile String lastLine;

    void setUi(Ui u) { uiSink = u; }

    void setPreviewSink(PreviewSink p) { previewSink = p; }

    String lastLine() { return lastLine; }

    /**
     * 通话页的取帧：**一直采，只留最新 N 张**（实现在 {@link CallCapture}）。
     *
     * 这是"自动/手动"共用的底座 —— 不管自动开还是关，只要通话页开着，相机每出一帧就按
     * 采样间隔（发送窗口 ÷ 张数）抽一张进缓冲，队列满就顶掉最旧的。所以"最新 N 张"
     * 永远是**刚刚发生的这一段时间**，不是几分钟前的。
     *
     * 两种用法：
     *   - **自动 · 开**：攒满 N 张就拼成一张宫格发过去，它自己看一眼、有话说就说一句。
     *   - **自动 · 关**：一个字节都不发。等你开口（打字或话筒）时，
     *     把当前这几张拼成一张宫格**挂在你的话上**一起发出去 ——
     *     这样它回答的是"你现在看到的"，而不是历史里那张老图。
     *
     * ⚠️ 0.29.x 的病根：自动关着的时候**根本不采帧**，自动开着又要求 `frames >= 2`
     * （静止画面永远只有 1 帧）才发 —— 两头都堵死，表现就是"它一直看着同一张老图"。
     */
    private volatile boolean autoWatch;
    private volatile boolean autoBusy;
    /**
     * 有没有一轮正在跑。
     *
     * ⚠️ 不能用 `current != null` 来判断 —— `current` 是"最近一个 asker"，**跑完也不置空**
     * （过期回包靠"下一条把 current 换成新 asker"来表达）。0.29.x 就是用 `current != null`
     * 挡住自动看的，结果只要用户说过一句话，**自动看就永久失效**，用户看到的就是
     * "自动开着它也不动了"。这里单独记一个真正会复位的标记。
     */
    private volatile boolean busyRound;
    /** 这一轮是"自己看一眼"发起的（回来太短就当没说话）。 */
    private volatile boolean pendingAuto;
    private volatile long lastAutoAt;
    /** 看门狗用：这一轮是什么时候开的。 */
    private volatile long lastRoundAt;
    /** 上一次生效的灵敏度（变了就重置退避）。 */
    private String appliedPace;
    /** 发送间隔的硬上限（灵敏度那几档都在它之下，留着是防手改的配置把节奏拉到几分钟）。 */
    private static final long AUTO_MAX_GAP_MS = 60_000;

    /** 通话页开着没有（决定要不要一直采帧）。 */
    private volatile boolean callActive;
    /** 相机是这一秒才开的（预热期内收到的都是黑帧，见 {@link CallCapture#WARMUP_MS}）。 */
    private volatile long callOpenedAt;
    /**
     * 连续取帧窗口：最近"一个发送窗口"的连续帧（{@link CallCapture#FPS} 帧/秒）。
     *
     * ⚠️ 它是**连续**的，不是"隔几秒抽一张"。切点判据吃的是相邻帧差分，
     * 抽稀成快照之后判据全废 —— 用户看到的就是"每格一个不相干的画面"。
     */
    private final CallCapture capture = new CallCapture();

    /**
     * **一条通话会话 = 一条连续管线**（= 电脑版 `createPipeline` 只在开播时建一次）。
     *
     * 以前是"每次发图把最近 N 秒的帧重喂一遍新管线"，管线里 `gapEwma / Tiers / 去重 /
     * 积分累积` 全部每窗清零 —— 出来就只有切点、没有兜底和积分帧，一窗 2 帧。
     * 详见 {@link CallStream} 的类注释。
     */
    private final CallStream stream = new CallStream();

    /** 通话页开/关时借用相机（预览是要一直有帧的）。 */
    void callOpen() {
        callActive = true;                 // 先立标记：ensureEngine 建引擎时直接按它选分辨率
        camAcquire("call");
        // 通话页的帧要拼成宫格喂模型，640 长边太小（一格只剩两百多像素，模型会说"糊"）
        try { if (engine != null) engine.setResolution("medium"); } catch (Throwable ignored) { }
        autoWatch = cfg == null || cfg.autoWatch();   // 用记住的那个选择，别每次硬置 true
        lastAutoAt = System.currentTimeMillis();
        capture.reset();
        callOpenedAt = System.currentTimeMillis();
        previewBusy = false;
        lastPreviewAt = 0;
        // 建这条会话唯一的管线：窗口 = 发送间隔。**张数不设** —— 几帧由 see-now 的
        // `flushGain`（碎格）/ `maxWaitSec`（时间）/ `maxFrames:40`（保险）自己判。
        // 队列深度 = 攒批（`wakeBatch`，默认 1 = 原来的行为）。
        if (looker != null) {
            stream.open(looker.opts(), autoGapMs() / 1000.0,
                cfg == null ? 1 : cfg.wakeBatch());
        }
    }

    void callClose() {
        callActive = false;
        autoWatch = false;
        capture.reset();
        stream.close();
        // 回到会话页那种"偶尔看一眼"的用法，640 够用也省电
        try { if (engine != null) engine.setResolution("low"); } catch (Throwable ignored) { }
        camRelease();
    }

    /**
     * 通话页唯一的后台线程：**喂帧**（JPEG→rgb24+打分，~15ms）和**画预览**（~40ms）都在这。
     *
     * 为什么合成一条：两个单线程池（以前是 `scorePool` + `previewPool`）等于两个队列、
     * 两套"忙不忙"的判断，还要担心互相插队 —— 而这两件事加起来在 5fps（200ms 预算）里
     * 只占 55ms。一个线程 = 一个顺序 = 帧永远按时间进管线，不会因为预览插队乱序。
     */
    private final java.util.concurrent.ExecutorService workPool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean previewBusy;
    /** 左下角那张拼图预览显不显示（设置里记住；关掉连拼图都不算）。 */
    private volatile boolean previewOn = true;
    /**
     * 两次拼图之间的最小间隔 = **0，每抓一帧就重排一次**（5fps ⇒ 200ms 一张）。
     *
     * 原来是 3000ms。那对"看热闹"够用，但**调试时是瞎的**：管线 5fps 在抓帧，
     * 预览 3 秒才动一次，看不出哪一帧被留下、排成了什么档位。
     * 2026-11-14 用户原话"我要看到每抓一帧都会变化那种"。
     *
     * 靠 `previewBusy` 防重入：上一次拼图还没完就跳过这一帧，绝不排队
     * （排队会把 200ms 的采样节奏拖垮，得不偿失）。
     */
    private static final long PREVIEW_EVERY_MS = 0;

    /**
     * 安排一次"拼出来长什么样"的预览。
     *
     * 画的是**管线正在攒的那一箱**（`CallStream.preview()` → 同一个 `CallSheet.layout`），
     * 所以预览就是"此刻发出去会是什么样"，而且不用重算任何帧的打分（帧进来时已经算过）。
     */
    private void schedulePreview() {
        if (!previewOn) return;                     // 用户把预览关了：一次拼图都不做
        long now = System.currentTimeMillis();
        if (previewBusy || now - lastPreviewAt < PREVIEW_EVERY_MS || looker == null) return;
        if (!callActive || stream.draftFrames() == 0) return;
        previewBusy = true;
        lastPreviewAt = now;
        try {
            workPool.submit(new Runnable() {
                public void run() {
                    try {
                        Pipeline.Sheet s = stream.preview();
                        if (s == null) return;
                        /**
                         * **这一箱的最后一帧还是上一张用的那一帧** = 内容没变，别重拼。
                         *
                         * 静止画面时管线不新增帧（`dupMin` 去重 + 兜底 20 秒），
                         * 而 `schedulePreview` 是 5fps 进来的 —— 不判这一下就是每秒
                         * 白拼 5 张一模一样的图。有变化时每帧都会走到下面，预览跟着变。
                         */
                        Pipeline.Entry tail = s.frames.isEmpty() ? null : s.frames.get(s.frames.size() - 1);
                        if (tail != null && tail == lastPrevTail) return;
                        lastPrevTail = tail;

                        long t0 = System.currentTimeMillis();
                        byte[] jpg = looker.thumb(s);
                        prevCostMs += System.currentTimeMillis() - t0;
                        prevN += 1;
                        // 每 25 张报一次（5fps 下约 5 秒）：真机上核对"预览到底跟不跟得上"
                        if (prevN % 25 == 0) {
                            EyeLog.w("look", "preview " + prevN + " 张 · 平均 "
                                + (prevCostMs / prevN) + "ms · 上张 " + s.frames.size() + " 格");
                        }
                        Ui u = uiSink;
                        if (u != null && jpg != null) {
                            u.onSheetPreview(jpg, s.frames.size(), s.to - s.from);
                        }
                    } catch (Throwable e) {
                        EyeLog.w("look", "预览失败: " + e);
                    } finally {
                        previewBusy = false;
                    }
                }
            });
        } catch (Throwable e) {
            previewBusy = false;
        }
    }
    private volatile long lastPreviewAt;
    /** 上一张预览用的最后一帧 —— 引用相同就说明这一箱没长，不必重拼。 */
    private Pipeline.Entry lastPrevTail;
    private int prevN;
    private long prevCostMs;

    /** 界面上的"显示/隐藏预览"按钮走这里：落盘 + 立刻生效。 */
    void setPreviewOn(boolean on) {
        previewOn = on;
        lastPreviewAt = 0;                  // 重新打开：下一帧立刻重排
        if (cfg != null) cfg.setPreviewOn(on);
    }

    boolean previewOn() { return previewOn; }

    /**
     * 采集线程每出一帧都走这里：够密就收进窗口。
     *
     * 只做"塞进队列"这一件事，**不拼图不发请求** —— 拼图要几十毫秒（还要解一整窗 JPEG），
     * 放在采集线程上会把预览拖卡。发送由 1 秒一次的 tick 去判（见 {@link #maybeAutoSend()}）。
     */
    private void sampleCallFrame(byte[] jpg) {
        if (!callActive || jpg == null || jpg.length == 0) return;
        /**
         * 相机刚开（`callOpen` 里换到 medium 会 reopen）那 1 秒出的是**还没曝光好的黑帧**，
         * 收进来就会在第一格留一块黑的（真机实测：4 格宫格里第 1 格全黑，模型以为镜头被挡）。
         */
        if (System.currentTimeMillis() - callOpenedAt < CallCapture.WARMUP_MS) return;
        long at = System.currentTimeMillis();
        if (!capture.offer(at)) return;
        /**
         * 解码 + 打分（~5ms/帧）放**后台单线程**：采集线程是相机的回调线程，
         * 在那儿解 Bitmap 会把预览拖卡（真机实测掉帧）。
         */
        final byte[] frame = jpg;
        final long atMs = at;
        try {
            workPool.submit(new Runnable() {
                public void run() {
                    Looker lk = looker;
                    if (lk == null || !callActive) return;
                    byte[] rgb = lk.toRgb24(frame);
                    if (rgb == null) return;
                    stream.push(atMs, rgb, frame);
                }
            });
        } catch (Throwable ignored) { }
        schedulePreview();
    }

    void setAutoWatch(boolean on) {
        autoWatch = on;
        // 打开的那一刻就允许立刻发（否则要等一整个窗口，用户会以为开关没生效）
        lastAutoAt = on ? 0 : System.currentTimeMillis();
        if (cfg != null) cfg.setAutoWatch(on);        // 记住
    }

    boolean autoWatch() { return autoWatch; }

    /**
     * 两次自动看之间隔多久 —— **跟着设置里的「灵敏度」走**。
     *
     * 平静 25s / 正常 15s / 灵敏 8s / 游戏 20s（画面一直在动，别一句接一句）。
     *
     * 这个数同时是**采样窗口**：{@link CallCapture#interval} 拿它除以张数，
     * 所以 N 张正好铺满"上一次发完到现在"。改它 = 同时改采样密度和发送节奏，两者不会脱节。
     */
    private long autoGapMs() {
        String p = cfg == null ? "normal" : cfg.pace();
        long base;
        if ("calm".equals(p)) base = 25_000;
        else if ("dense".equals(p)) base = 8_000;
        else if ("game-economic".equals(p)) base = 20_000;
        else base = 15_000;
        return Math.min(base, AUTO_MAX_GAP_MS);
    }

    /** tick 每秒调一次：够不够"攒满就发"，够就发。 */
    private void maybeAutoSend() {
        if (!autoWatch || !callActive || !running || cfg == null || autoBusy) return;
        if (busyRound) {
            // 看门狗：只要有一次回调没来，busyRound 就永久 true，表现就是"卡住不动"
            if (System.currentTimeMillis() - lastRoundAt > 120_000) {
                EyeLog.w("auto", "看门狗：上一轮 120s 没回，放行");
                busyRound = false;
            } else {
                return;                                    // 正在回话，别插嘴
            }
        }
        if (!cfg.direct()) return;
        /**
         * **等管线自己封箱**。什么时候封是 `Pipeline` 判的（`flushGain` 碎格 /
         * `maxWaitSec` 时间 / `maxFrames:40` 保险 / 兜底 / 切点），App 不再拿
         * "攒够几帧""隔了多少秒"再判一遍 —— 两处都判就会把管线刚攒好的节奏打乱
         * （1.1.3 的 `have() < 3` 就是这么把图卡在 2 帧的）。
         */
        if (!stream.ready()) return;
        long now = System.currentTimeMillis();
        /**
         * 发送节奏由**灵敏度**定（平静 25s / 正常 15s / 灵敏 8s / 游戏 20s），
         * 不是"管线一封箱就发" —— 4 格在画面动的时候 2~3 秒就攒满，
         * 照着发就是每 3 秒找模型说一句（真机实测过：23:02:47、23:02:50 连着两张）。
         * 攒满的那张会待在队列里（`queueDepth = 1` 只留最新一张），到点了取最新的发。
         */
        if (now - lastAutoAt < autoGapMs()) return;
        lastAutoAt = now;
        autoBusy = true;
        Ui u = uiSink;
        if (u != null) u.onTool("look", "running", "自己看一眼…");
        try {
            asks.submit(new Runnable() {
                public void run() {
                    try { autoLookBuffered(); } catch (Throwable e) { EyeLog.w("auto", "" + e); }
                    finally {
                        autoBusy = false;
                        Ui v = uiSink;
                        if (v != null && !busyRound) v.onTool("look", "ok", "看完了");
                    }
                }
            });
        } catch (Throwable e) {
            autoBusy = false;
        }
    }

    /** 自动看：把管线封好的那一箱（攒批 > 1 时是那几箱）**连图带话**发过去。 */
    private void autoLookBuffered() {
        if (looker == null) return;
        /**
         * 一次取走**攒下的全部**（上限 = `wakeBatch`）。
         *
         * 只取一张的话队列压得住也没用 —— 模型还得为剩下两张各来一轮，
         * 每轮都要把整个上下文重过一遍，那就是没省（see-now `runner.js:409`）。
         */
        java.util.List<Pipeline.Sheet> sheets =
            stream.takeMany(cfg == null ? 1 : cfg.wakeBatch());
        if (sheets.isEmpty()) return;
        java.util.List<byte[]> jpegs = new java.util.ArrayList<>();
        int frames = 0, tokens = 0;
        for (Pipeline.Sheet s : sheets) {
            Tools.Look l = looker.lookOf(s);
            if (l == null || l.jpeg == null) continue;
            jpegs.add(l.jpeg);
            frames += l.frames;
            tokens += l.tokens;
        }
        if (jpegs.isEmpty()) return;
        // **不清窗口**：它是滑动的（永远"最近 window 秒"），清了就变成"只有发完这一秒"，
        // 紧接着用户开口时就会拿到 2 帧、跨 1.7 秒 —— 正是他觉得"又变成两帧"的那种图。
        // 「攒满就发」就是攒满就发：不再拿"跟上一次像不像"去拦。
        // 用户的原话是这个，而拦一次的表现就是"它又不动了"。真没内容时模型会回一句
        // 「没什么变化」—— 那一轮不朗读不落盘（looksLikeNoop），体验上分得开。
        // ⚠️ 攒批 = 1 时日志必须和改动前**逐字一致**（`看一张：…`），别为了统一格式改掉它。
        EyeLog.w("auto", jpegs.size() == 1
            ? "看一张：n=" + frames + " tokens=" + tokens
            : "看 " + jpegs.size() + " 张：n=" + frames + " tokens=" + tokens);
        // ⚠️ 顺序：先 send（它会把 pendingAuto 清成 false），再立标记。
        //    反过来的话，`send` 开头那句清标记会把这次自动看标记掉。
        sendWithSheets(jpegs, frames);
        pendingAuto = true;
    }

    /**
     * 用户开口时**当场截断**出一张宫格（插话那条路，只有一张）。
     *
     * 挑哪几帧、每帧多大**全交给管线**（`takeNow` → `flush`）—— 这一层只负责递过去。
     * 「自动看」不走这里：它要取走队列里攒下的**全部**（见 {@link #autoLookBuffered}）。
     */
    private Tools.Look callSheet(String why) {
        if (looker == null) return null;
        // 插话：立刻截断出图（管线攒到哪算哪），用户问的是"现在"
        return looker.lookOf(stream.takeNow(System.currentTimeMillis(), why));
    }


    /**
     * 用户开口（打字/话筒）时，把缓冲里的帧挂在这句话上。
     *
     * 自动开着也照样挂 —— 他既然开口问了，就该让他问的是"现在"。
     */
    private void sendWithBufferedFrames(final String text) {
        Tools.Look l = callSheet("user");
        if (l == null || l.jpeg == null) {
            send(text, (java.util.List<byte[]>) null, 0, false, null);
            return;
        }
        // 刚发过图：自动那条往后挪（窗口不动，见 autoLookBuffered 里的注释）
        // 而且以这张为"上一次看到的画面"
        lastAutoAt = System.currentTimeMillis();
        lastPreviewAt = 0;                    // 窗口已经交出去了：下一帧来了立刻重排预览
        EyeLog.w("send", "带通话帧 " + l.frames + " 张 tokens=" + l.tokens);
        send(text, java.util.Collections.singletonList(l.jpeg), l.frames, true,
            "这是手机摄像头**刚才这几秒**攒的 " + l.frames + " 帧拼成的一张宫格"
                + "（按时间先后，左上角是编号）。直接看这张图回答，不要调用 look 工具。");
    }

    /** 「没什么可说的」—— 只认明确的否定词，不用长度猜（"地上有水"只有 4 个字但必须说）。 */
    private static boolean looksLikeNoop(String s) {
        if (s == null) return true;
        String t = s.replaceAll("[\\s。，,.！!？?…~→]", "").trim();
        if (t.length() == 0) return true;
        return t.equals("嗯") || t.contains("没变") || t.contains("没有变化")
            || t.contains("没什么变化") || t.contains("没看出") || t.contains("没什么可")
            || t.contains("无变化");
    }

    public static EyeService peek() { return instance; }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        BuildConfig.attach(this);   // 给 Portable 那种没有 Context 的静态工具读版本号
        running = true;
        startForeground(NOTI_ID, buildNotification("就绪"));

        cfg = new Config(this);
        cfg.migrate();                        // 老版本存下的默认值升到 v4 口径（模型名 / 上下文轮数）
        previewOn = cfg.previewOn();
        speaker = new Speaker(this);
        looker = new Looker(cfg);
        http = new HttpServer(this);
        http.start();

        applySettings();
        loadHistory();
        writeModeFile();      // 插件还在读它（见 MODE_FILE 的注释）
        startFilePoll();
        scheduleWake(this, 10 * 60 * 1000);
        ui.post(tick);
        EyeLog.w("svc", "onCreate pid=" + android.os.Process.myPid()
            + " think=" + cfg.think() + " model=" + cfg.apiModel());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TORCH.equals(a)) {
            camAcquire("torch");
            if (engine != null) engine.setTorch(!engine.isTorch());
            camRelease();
        }
        if (ACTION_FLIP.equals(a)) {
            camAcquire("flip");
            if (engine != null) engine.flip();
            camRelease();
        }
        if (ACTION_SAY.equals(a) && intent != null && speaker != null) {
            speaker.say(intent.getStringExtra("text"));
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        EyeLog.w("svc", "onDestroy");
        running = false;
        instance = null;
        ui.removeCallbacksAndMessages(null);
        try { if (current != null) current.cancel(); } catch (Throwable ignored) { }
        try { asks.shutdownNow(); } catch (Throwable ignored) { }
        try { sumQ.shutdownNow(); } catch (Throwable ignored) { }
        try { filePoll.interrupt(); } catch (Throwable ignored) { }
        try { http.stop(); } catch (Throwable ignored) { }
        try { if (engine != null) engine.stop(); } catch (Throwable ignored) { }
        try { speaker.stop(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- 相机借用（核心约束）

    /**
     * 借相机。**幂等且计数**：`look`、`/frame.jpg`、`/video/mjpeg` 各自借各的还，
     * 谁先走都不影响别人。
     */
    void camAcquire(String why) {
        synchronized (camLock) {
            camHolders += 1;
            camReleaseAt = Long.MAX_VALUE;
        }
        ensureEngine(why);
    }

    void camRelease() {
        synchronized (camLock) {
            camHolders = Math.max(0, camHolders - 1);
            if (camHolders == 0) camReleaseAt = System.currentTimeMillis() + CAM_LINGER_MS;
        }
    }

    /**
     * 开相机（如果该开）。权限没给就什么都不做 —— 开机自启时用户不在屏幕前，
     * 请求权限的弹窗只能由界面发起。
     */
    private void ensureEngine(String why) {
        if (!running) return;
        if (engine != null) return;
        if (!hasCameraPermission()) {
            Log.w(TAG, "没有相机权限，等服务被授权后再起");
            return;
        }
        engine = new CameraEngine(this, this);
        // 通话页要拼宫格给模型看，用 medium；平时 640 够用也省电
        try { engine.setResolution(callActive ? "medium" : "low"); engine.setFps(12); }
        catch (Throwable ignored) { }
        engine.start();
        lastCamOpenAt = System.currentTimeMillis();
        EyeLog.w("cam", "开（" + why + "）");
    }

    /** 没人借了就把相机真关掉（每秒钟看一眼）。 */
    private void maybeCloseCamera() {
        CameraEngine e = engine;
        if (e == null) return;
        if (camHolders > 0) return;
        if (System.currentTimeMillis() < camReleaseAt) return;
        engine = null;
        try { e.stop(); } catch (Throwable ignored) { }
        EyeLog.w("cam", "关（"
            + (System.currentTimeMillis() - lastCamOpenAt) + "ms 后没人要了）");
    }

    private boolean hasCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- 会话数据

    private String convId() {
        String id = cfg == null ? "" : cfg.convId();
        if (id != null && id.length() > 0) return id;
        id = ConvStore.newId();
        if (cfg != null) cfg.setConvId(id);
        return id;
    }

    String convIdNow() { return convId(); }

    private void loadHistory() {
        String id = convId();
        java.util.List<Conv.Msg> msgs = ConvStore.load(id);
        synchronized (history) {
            history.clear();
            history.addAll(msgs);
        }
        convSummary = ConvStore.readSummary(id);
        convSummaryUpTo = ConvStore.summaryUpTo(id);
    }

    /** 界面重进 / 切会话时读一遍（界面只读，不改）。 */
    java.util.List<Conv.Msg> messages() {
        synchronized (history) {
            return new java.util.ArrayList<>(history);
        }
    }

    /**
     * 这一问带上的上下文：先按轮数、再按 token 预算裁，最后决定哪几张图真发。
     *
     * 打开滚动摘要时，被裁掉的那段不会凭空消失 —— 它已经被 {@link #maybeSummarize}
     * 压成一段"前情"放在最前面。
     */
    /**
     * 这一问带上的上下文：先按轮数、再按 token 预算裁，最后决定哪几张图真发。
     *
     * 打开滚动摘要时，被裁掉的那段不会凭空消失 —— 它已经被 {@link #maybeSummarize}
     * 压成一段"前情"放在最前面。
     *
     * ⚠️ 预算口径是**整个请求**，不是"光历史"：先扣掉工具定义、记忆、系统提示和这一次
     * 的回答额度，剩下的才给历史。不扣的话用户选 900k 就会真的发出 900k + 两千，
     * 撞上 1M 的窗口边界直接 400。
     */
    private java.util.List<Conv.Out> contextFor() {
        if (cfg == null) return null;
        java.util.List<Conv.Msg> all;
        synchronized (history) {
            all = new java.util.ArrayList<>(history);
        }
        int overhead = Conv.textTokens(Memory.block(cfg))
            + 1_500                       // 四个工具的定义（每轮都发）
            + cfg.maxTokens()             // 这一次的回答额度
            + 300;                        // system prompt
        int budget = Math.max(1_500, cfg.ctxTokens() - overhead);
        // 轮数闸已删：`Conv.trim` 的 maxTurns 恒传"不限"，真正管事的只有预算。
        java.util.List<Conv.Msg> kept = Conv.trim(all, Config.TURNS_UNLIMITED, budget);
        java.util.List<Conv.Out> out = Conv.render(kept, cfg.imgWindow(), new Conv.Reader() {
            public byte[] read(String file) { return ConvStore.readImage(file); }
        });
        String sum = convSummary;
        if (cfg.summary() && sum != null && sum.length() > 0) {
            out.add(0, new Conv.Out("user", "[前情摘要 · 更早的对话已压缩]\n" + sum));
        }
        return out;
    }

    /**
     * 当前会话大致占多少 token（估算）—— 只给界面显示"现在用了多少 / 上限多少"。
     *
     * 为什么值得显示：预算是个上限，用户看不到"离撞墙还有多远"就只能瞎猜。
     * 这也是唯一能让"300k 和 16k 到底差在哪"变得可感知的地方。
     *
     * ⚠️ **必须按 {@link #contextFor} 的口径算**：最老的那些图会被换成占位文字
     * （只有最近 `imgWindow` 张真发），这里少算一笔，用户就会看到"现 887"，
     * 而实际请求是 887 + 3 张图 ≈ 1.5k。数字说假话比不显示更糟。
     */
    int ctxEstimate() {
        java.util.List<Conv.Msg> all;
        synchronized (history) {
            all = new java.util.ArrayList<>(history);
        }
        int total = 0;
        for (Conv.Msg m : all) total += m.shots == null ? 0 : m.shots.size();
        int keepFrom = Math.max(0, total - (cfg == null ? 0 : cfg.imgWindow()));
        int idx = 0;
        int n = 0;
        for (Conv.Msg m : all) {
            n += Conv.textTokens(m.text);
            if (m.reasoning != null) n += Conv.textTokens(m.reasoning);
            if (m.shots == null) continue;
            for (Conv.Shot s : m.shots) {
                n += idx >= keepFrom ? Math.max(1, s.tokens) : Conv.textTokens(s.mark());
                idx += 1;
            }
        }
        return n;
    }

    /**
     * 记一轮：**先落盘再进内存**（盘上才是真相，内存只是缓存）。
     *
     * @return 这一轮几张图的落盘路径（界面拿去显示缩略图）；没图就是空 list
     */
    private java.util.List<String> rememberTurn(String you, String me, String reasoning,
                                                long thinkMs, java.util.List<byte[]> sheets,
                                                int frames) {
        java.util.List<String> out = new java.util.ArrayList<>();
        lastAssistantAt = 0;
        if (cfg == null) return out;
        // ⚠️ 纯图（相册里挑一张、一个字都没打）也必须落盘：以前这里要求 you 非空，
        //    于是"只发图"的那一轮重进会话就没了（气泡在屏幕上，但磁盘上没有）。
        String mineText = you == null ? "" : you;
        boolean noImg = sheets == null || sheets.isEmpty();
        if (mineText.length() == 0 && noImg) return out;
        String id = convId();
        long now = System.currentTimeMillis();
        Conv.Msg u = Conv.Msg.user(now, mineText);
        if (sheets != null) {
            for (byte[] sheet : sheets) {
                if (sheet == null || sheet.length == 0) continue;
                String file = ConvStore.saveImage(id, sheet);
                if (file != null) {
                    u.with(Conv.Shot.of(file, Math.max(1, frames), "看了", Tokens.SHEET_TOKENS));
                    out.add(file);
                }
            }
        }
        if (me != null && me.length() > 0) lastLine = me;
        Conv.Msg a = Conv.Msg.ai(now + 1, me == null ? "" : me);
        lastAssistantAt = a.at;
        a.withReasoning(reasoning);
        a.thinkMs = thinkMs;
        synchronized (history) {
            history.add(u);
            history.add(a);
            // 内存镜像的上限。以前是 400 条，那等于"上下文最多 200 轮" —— 预算调到 300k 以上时
            // 这条成了真瓶颈（用户看到的"怎么还是记不住"其实是这里在丢）。给到 2000 条。
            while (history.size() > 2000) history.remove(0);
        }
        ConvStore.append(id, u);
        ConvStore.append(id, a);
        maybeSummarize();
        return out;
    }

    /** 单张图的旧签名（`/ask` 那条 HTTP 路只有一张），转调多张版本。 */
    private String rememberTurn(String you, String me, String reasoning, long thinkMs,
                                byte[] sheet, int frames) {
        java.util.List<String> files = rememberTurn(you, me, reasoning, thinkMs,
            sheet == null ? null : java.util.Collections.singletonList(sheet), frames);
        return files.isEmpty() ? null : files.get(0);
    }

    /**
     * 该压了吗：把"这一问会被裁掉、但还没压过"的那段压成摘要。**在后台线程做**。
     *
     * 摘要要发一次模型请求（1~3 秒），放在提问前同步做，用户会明显感觉到"这次怎么这么慢"。
     * 所以这里在**上一轮回答落地之后**就开始压，等下一问真正需要时它已经好了。
     */
    private void maybeSummarize() {
        if (cfg == null || !cfg.summary()) return;
        if (!summarizing.compareAndSet(false, true)) return;
        final Config c = cfg;
        sumQ.submit(new Runnable() {
            public void run() {
                try {
                    java.util.List<Conv.Msg> all;
                    synchronized (history) {
                        all = new java.util.ArrayList<>(history);
                    }
                    java.util.List<Conv.Msg> kept = Conv.trim(all, Config.TURNS_UNLIMITED, c.ctxTokens());
                    java.util.List<Conv.Msg> dropped = Conv.toSummarize(all, kept, convSummaryUpTo);
                    int tokens = 0;
                    for (Conv.Msg m : dropped) tokens += Conv.tokensOf(m, false);
                    if (dropped.isEmpty()) return;
                    if (dropped.size() < 4 && tokens < 400) return;

                    String add = Brain.summarize(c, Conv.digest(dropped), c.summaryChars());
                    if (add == null) return;

                    String merged = convSummary == null || convSummary.length() == 0
                        ? add : (convSummary + "\n" + add);
                    if (merged.length() > 1200) merged = merged.substring(merged.length() - 1200);
                    long upTo = dropped.get(dropped.size() - 1).at;
                    ConvStore.writeSummary(convIdNow(), merged, upTo);
                    convSummary = merged;
                    convSummaryUpTo = upTo;
                    EyeLog.w("sum", "压了 " + dropped.size() + " 条（约 " + tokens + " token）→ "
                        + add.length() + " 字");
                } catch (Throwable e) {
                    EyeLog.w("sum", "摘要失败: " + e);
                } finally {
                    summarizing.set(false);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 会话管理（界面调这些）

    String convTitle() {
        String id = convId();
        String t = ConvStore.readTitle(id);
        if (t != null && t.length() > 0) return t;
        synchronized (history) {
            for (Conv.Msg m : history) {
                if ("user".equals(m.role) && m.text.length() > 0) {
                    return m.text.length() <= 16 ? m.text : m.text.substring(0, 16) + "…";
                }
            }
        }
        return "新对话";
    }

    void switchConv(String id) {
        if (cfg == null || id == null || id.length() == 0) return;
        cancelTurn();
        cfg.setConvId(id);
        loadHistory();
        pendingUser = null;
        notifyState();
        EyeLog.w("conv", "switch -> " + id + "（" + history.size() + " 条）");
    }

    void newConv() {
        if (cfg == null) return;
        cancelTurn();
        String id = ConvStore.newId();
        cfg.setConvId(id);
        // 先把空文件建出来：不建的话这条会话在列表里根本不出现（列表是扫 .jsonl 的）
        ConvStore.clear(id);
        synchronized (history) { history.clear(); }
        convSummary = "";
        convSummaryUpTo = 0;
        pendingUser = null;
        notifyState();
        EyeLog.w("conv", "new -> " + id);
    }

    void renameConv(String id, String title) { ConvStore.rename(id, title); }

    void deleteConv(String id) {
        if (id == null) return;
        ConvStore.delete(id);
        if (cfg != null && id.equals(cfg.convId())) newConv();
    }

    void clearConv() {
        cancelTurn();
        ConvStore.clear(convId());
        synchronized (history) { history.clear(); }
        convSummary = "";
        convSummaryUpTo = 0;
        pendingUser = null;
        notifyState();
    }

    // ---------------------------------------------------------------- 说话

    /**
     * 用户说了一句。**这是唯一的入口**（输入框、语音、`/ask` 都走它）。
     *
     * 先打断上一轮：用户又说话 = 他不想等那 8 秒思考了。
     */
    void send(String text) {
        // 通话页开着：这句话要带上"刚才这几秒"的画面，否则模型只能看历史里那张老图
        if (callActive) { sendWithBufferedFrames(text); return; }
        send(text, (java.util.List<byte[]>) null, 0, false, null);
    }

    /**
     * 自动看那条：图必须**真的进 API**（不然模型只能自己再拍一次，白等一轮）。
     *
     * `frames` 是这一批的**合计**（多张时每张各用这个数落盘，和 see-now `attachAndReturn`
     * 的口径一致：单张时它就是那张的帧数，多张时单张区间不进 `Shot`）。
     */
    private void sendWithSheets(java.util.List<byte[]> sheets, int frames) {
        String note = sheets.size() == 1
            ? "这是手机摄像头**刚才这一会儿**攒的 " + frames + " 帧拼成的一张宫格"
                + "（按时间先后，左上角是编号）。直接看它，不要调用 look 工具。"
            : "这是手机摄像头**刚才这几段时间**攒的 " + sheets.size() + " 张宫格、共 " + frames
                + " 帧（每张内部按时间先后，左上角是编号；多张之间也是先后顺序）。"
                + "直接看它们回答，不要调用 look 工具。";
        send("（你自己看了一眼画面）有值得说的就一句话说；没变化就只回「嗯」。",
            sheets, frames, true, note);
    }

    /**
     * @param image 这一轮自带的图（"这是什么？"用）；null = 纯文字
     *
     * `image` 只用于**落盘显示**，不进 API —— 相机是工具，模型通过调 `look` 拿图。
     */
    void send(String text, byte[] image, int frames) {
        send(text, image == null ? null : java.util.Collections.singletonList(image),
            frames, false, null);
    }

    /**
     * 相册选的图：**这张图必须真的进 API**，不能只落盘显示。
     *
     * 和相机那条的区别：相机是"模型想看才看"（工具），相册是"用户就是要你看这张"。
     * 走工具的话模型可能压根不调，用户会觉得图白发了。
     */
    void sendWithImage(String text, byte[] image) {
        // frames=0：相册不是帧序列，别让模型说"第 1 帧"
        send(text, image == null ? null : java.util.Collections.singletonList(image),
            0, true, null);
    }

    /**
     * 相册**多选**（最多 4 张）跟着一句话一起发。
     *
     * `Asker.compose` 本来就是按 `o.images` 逐个塞 `image_url`，所以多张只是把
     * 单张换成 list —— 协议侧一行没改。
     */
    void sendWithImages(String text, java.util.List<byte[]> images) {
        send(text, images, 0, true, null);
    }

    private void send(final String text, final java.util.List<byte[]> images,
                      final int frames, final boolean attach) {
        send(text, images, frames, attach, null);
    }

    private void send(final String text, final java.util.List<byte[]> images,
                      final int frames, final boolean attach, final String note) {
        final String t = text == null ? "" : text.trim();
        final boolean hasImg = images != null && !images.isEmpty();
        // 只发图不带字也要放行（相册里挑一张直接问"这是什么"是最自然的用法）
        if (t.length() == 0 && !hasImg) return;
        // 用户自己发的：不管上一轮是不是自动看，这个标记必须清掉，
        // 否则他这句话的回复会被当成"没什么可说的"直接丢掉。
        pendingAuto = false;
        if (cfg == null) return;
        if (!cfg.direct()) {
            notifyError("还没填 API key：设置 → API key");
            return;
        }
        cancelTurn();
        // 用户又开口了 = 上一句别念了（在线音色是排队异步的，不掐会一句句追着念完）
        if (speaker != null) speaker.shutUp();
        pendingUser = t;
        final java.util.List<Conv.Out> ctx = contextFor();
        final Asker asker = new Asker(cfg, this);
        current = asker;
        busyRound = true;
        lastRoundAt = System.currentTimeMillis();
        newTurn();
        /**
         * 这一轮自带的图**必须是局部变量**，不能是字段。
         *
         * `Asker.ask()` 开头会调 `env.newTurn()`（每轮清工具状态），字段版在那里就被
         * 清成 null 了 —— 于是相册图只显示在"刚发出去"那个气泡里，重进会话就没了。
         * 相机那条路不受影响（它走 `lastLook`），所以这个 bug 一直没被发现。
         */
        final java.util.List<byte[]> turnImgs =
            hasImg ? new java.util.ArrayList<>(images) : null;
        final int turnFrames0 = frames;

        try { asks.submit(new Runnable() {
            public void run() {
                /**
                 * 带图那一轮给模型的话术。
                 *
                 * 不说明的话它会把这当成"相机刚拍的画面"，甚至看到全黑/看不清就
                 * **自己调 look 工具去拍现实**（实测：发相册里一张黑底截图，
                 * 它回"镜头被挡住了"，然后开相机拍了一张天花板）。
                 *
                 * `pendingUser` 仍是用户原话/空串，落盘和气泡都不带这段说明。
                 */
                final boolean attached = attach && hasImg;
                final String what = images != null && images.size() > 1
                    ? "这几张图" : "这张图";
                // note = 通话页攒帧/自动看那条的说明（自己写好了"这是什么、别调 look"）
                final String askText;
                if (!attached) {
                    askText = t;
                } else if (note != null && note.length() > 0) {
                    askText = (t.length() == 0 ? "" : t + "\n\n") + "（" + note + "）";
                } else {
                    askText = (t.length() == 0
                        ? "（这是我刚从相册发给你" + (images.size() > 1 ? "的几张图" : "的一张图")
                            + "。直接看" + what + "回答，不要调用 look 工具）"
                        : t + "\n\n（" + what + "是我从相册选的，请直接看" + what
                            + "；不要调用 look 工具）");
                }
                asker.ask(askText, attach ? images : null, ctx, new Asker.Cb() {
                    public void onTool(String name, String phase, String detail) {
                        Ui u = uiSink;
                        if (u != null) u.onTool(name, phase, detail);
                    }

                    public void onDelta(String d) {
                        // 自动看那一轮：先不露字。它很可能回一句"嗯"然后被整轮丢掉，
                        // 提前显示就成了"闪一下就没了"。
                        if (pendingAuto) return;
                        Ui u = uiSink;
                        if (u != null) u.onDelta(d);
                    }

                    public void onRoundReset() {
                        Ui u = uiSink;
                        if (u != null) u.onRoundReset();
                    }

                    public void onStopped(String partial) {
                        pendingAuto = false;
                        if (current != asker) return;
                        busyRound = false;
                        String body = partial == null ? "" : partial.trim();
                        String out = body.length() == 0 ? "（已停止）" : body + "\n\n（已停止）";
                        java.util.List<byte[]> sheets = turnImgs != null ? turnImgs
                            : (lastLook == null ? null
                                : java.util.Collections.singletonList(lastLook.jpeg));
                        int frames = turnImgs != null ? turnFrames0
                            : (lastLook == null ? 0 : lastLook.frames);
                        java.util.List<String> files = rememberTurn(pendingUser, out, null, 0, sheets, frames);
                        lastLook = null;
                        Ui u = uiSink;
                        if (u != null) u.onReply(out, null, 0, files, lastAssistantAt);
                        EyeLog.w("ask", "停止后落盘 " + out.length() + " 字");
                    }

                    public void onText(String out, String reasoning, long thinkMs) {
                        if (current != asker) return;      // 被打断了：这条答案作废
                        busyRound = false;
                        if (pendingAuto) {
                            pendingAuto = false;
                            if (looksLikeNoop(out)) {
                                // 自己看了一眼，没什么可说的：**不落盘也不念**
                                EyeLog.w("auto", "没什么变化 → 这一轮丢弃（不念不落盘）");
                                Ui u = uiSink;
                                if (u != null) u.onQuiet();
                                return;
                            }
                        }
                        java.util.List<byte[]> sheets = turnImgs != null ? turnImgs
                            : (lastLook == null ? null
                                : java.util.Collections.singletonList(lastLook.jpeg));
                        int frames = turnImgs != null ? turnFrames0
                            : (lastLook == null ? 0 : lastLook.frames);
                        java.util.List<String> files =
                            rememberTurn(pendingUser, out, reasoning, thinkMs, sheets, frames);
                        lastLook = null;
                        Ui u = uiSink;
                        if (u != null) u.onReply(out, reasoning, thinkMs, files, lastAssistantAt);
                        answerLine(out);
                    }

                    public void onFail(String reason) {
                        pendingAuto = false;
                        if (current == asker) busyRound = false;
                        if (reason == null || reason.length() == 0) return;   // 被打断，静默
                        Ui u = uiSink;
                        if (u != null) u.onError(reason);
                    }
                });
            }
        }); } catch (Throwable e) {
            EyeLog.w("ask", "提交失败（服务在退出？）: " + e);
        }
    }

    /**
     * 「拍一张」：现场抓一张宫格，**当这一轮的配图**发出去。
     *
     * 和"让它自己 look"的区别：`look` 由模型判断要不要看；这个是你指定"就看这个"。
     * 抓帧要 3~5 秒（开相机 + 喂 2.5 秒），所以放在对话队列里跑，不占界面线程。
     */
    void lookNow() {
        if (cfg == null) return;
        if (!cfg.direct()) { notifyError("还没填 API key：设置 → API 接口"); return; }
        Ui u = uiSink;
        if (u != null) u.onTool("look", "running", "正在看一眼…");
        asks.submit(new Runnable() {
            public void run() {
                Tools.Look l = look(2.5);
                Ui v = uiSink;
                if (l == null || l.jpeg == null) {
                    if (v != null) {
                        v.onTool("look", "fail", "没看成");
                        v.onError("相机没抓到画面（被别的 App 占着？）");
                    }
                    return;
                }
                if (v != null) v.onTool("look", "ok", "看了 " + l.frames + " 帧");
                send("这是什么？", l.jpeg, l.frames);
            }
        });
    }

    /**
     * 找到"这条消息所属的那一轮的 user 时间戳"。重说/删除都以它为界。
     *
     * @return -1 = 没找到（界面上的消息和磁盘对不上了，别动）
     */
    private long pairedUserAt(long at) {
        java.util.List<Conv.Msg> all;
        synchronized (history) { all = new java.util.ArrayList<>(history); }
        int idx = -1;
        for (int i = 0; i < all.size(); i += 1) {
            if (all.get(i).at == at) { idx = i; break; }
        }
        if (idx < 0) return -1;
        while (idx >= 0 && !"user".equals(all.get(idx).role)) idx -= 1;
        return idx < 0 ? -1 : all.get(idx).at;
    }

    /**
     * 「重说」：把这条回答连同它的问题一起砍掉，**把问题原样交回界面**去重发。
     *
     * 为什么不在这里直接发：界面得先把气泡摆回去（用户要看见"我问了什么"），
     * 再触发发送。服务端只负责"砍干净 + 把问题还给你"。
     *
     * @return 那句 user 文本；null = 找不到/砍失败
     */
    String prepareRetry(long at) {
        if (cfg == null) return null;
        long from = pairedUserAt(at);
        if (from < 0) return null;
        String text = null;
        synchronized (history) {
            for (Conv.Msg m : history) {
                if (m.at == from && "user".equals(m.role)) { text = m.text; break; }
            }
        }
        if (text == null) return null;
        cancelTurn();
        if (speaker != null) speaker.shutUp();
        int cut = ConvStore.truncateFrom(convId(), from);
        if (cut < 0) return null;
        loadHistory();
        EyeLog.w("conv", "重说：砍掉 " + cut + " 条，从 " + from);
        return text;
    }

    /** 「删这段」：同上去掉"重发"。 */
    boolean deleteTurn(long at) {
        if (cfg == null) return false;
        long from = pairedUserAt(at);
        if (from < 0) return false;
        cancelTurn();
        int cut = ConvStore.truncateFrom(convId(), from);
        if (cut < 0) return false;
        loadHistory();
        EyeLog.w("conv", "删这段：砍掉 " + cut + " 条，从 " + from);
        notifyState();
        return true;
    }

    /**
     * 打断当前这一轮。
     *
     * ⚠️ **不要把 `current` 置空**：置空之后，这一轮的 `onStopped`（"已停止，这是说到一半的内容"）
     * 会被 `current != asker` 判成过期回包直接丢掉 —— 表现出来就是**按了停止什么也没发生，
     * 按钮一直停在 ■**。踩过一次。
     *
     * "这是一条过期回包"由下一条消息把 `current` 换成新 asker 来表达，不靠置空。
     */
    void cancelTurn() {
        Asker a = current;
        if (a != null) a.cancel();
        busyRound = false;      // 这一轮作废了：标记也要复位，否则自动看永远等着
    }

    // ---------------------------------------------------------------- Tools.Env

    @Override public Config cfg() { return cfg; }

    @Override public void newTurn() {
        lookCount = 0;
        lastLook = null;
    }

    @Override public int looksThisTurn() { return lookCount; }

    @Override public Tools.Look lastLook() { return lastLook; }

    /**
     * 抓一张宫格。**借相机 → 等帧 → 喂几秒 → 拼图 → 还相机**。
     *
     * 相机冷启动 0.5~1.5 秒，所以这一步整体 3~5 秒是正常的 —— 这也是为什么
     * `look` 工具在 UI 上要显示"正在看一眼…"，而不是让用户以为卡住了。
     */
    @Override public Tools.Look look(double seconds) {
        lookCount += 1;
        camAcquire("look");
        try {
            Tools.Look l = looker.grab(seconds);
            if (l != null) lastLook = l;
            return l;
        } finally {
            camRelease();
        }
    }

    /**
     * 工作区文件操作。**它自己动手的那块地**。
     *
     * 写完/删完要 `notifyState()`：设置页那行「工作区 N 个文件」得跟着变，
     * 否则用户不知道它到底存没存进去。
     */
    @Override public String files(String action, String path, String text) {
        String r;
        if ("list".equals(action)) r = Workspace.list(path);
        else if ("read".equals(action)) r = Workspace.read(path);
        else if ("write".equals(action)) r = Workspace.write(path, text, false);
        else if ("append".equals(action)) r = Workspace.write(path, text, true);
        else if ("delete".equals(action)) r = Workspace.delete(path);
        else r = "{\"error\":\"不认识的 action：" + action + "\"}";
        notifyState();
        return r;
    }

    int workspaceCount() { return Workspace.count(); }

    @Override public String memRead(String name) {
        String n = Tools.safeName(name);
        File f = new File(Memory.dir(), n);
        if (!f.exists()) return "没有这个文件：" + n + "\n现有：" + Memory.names();
        String body = Memory.read(f);
        return body == null ? "读不出来：" + n : body;
    }

    @Override public String memWrite(String name, String text, boolean append) {
        String r = Tools.writeFile(name, text, append);
        refreshMemory();
        notifyState();
        return r;
    }

    @Override public boolean lan() { return cfg != null && cfg.lanOn(); }

    /**
     * 开关切了立刻生效：先停掉现有监听，再起一个新的。
     * 不这么做的话要等进程重启 —— 用户点了开关没反应，会以为坏了。
     */
    @Override public void rebind() {
        try {
            if (http != null) { http.stop(); http.start(); }
        } catch (Throwable e) {
            EyeLog.w("http", "重绑失败: " + e);
        }
    }

    @Override public String exportData() { return Portable.export(); }

    @Override public String exportB64() { return Portable.exportBase64(); }

    @Override public String exportOne(String convId) { return Portable.exportOne(convId); }

    @Override public String importText(String name, String body) {
        String r = Portable.importText(name, body);
        try { loadHistory(); } catch (Throwable ignored) { }
        notifyState();
        return r;
    }

    @Override public String importData() {
        String r = Portable.importAll();
        // 合并完要让内存和界面都跟上：历史重读、记忆重算、通知刷新
        try { loadHistory(); } catch (Throwable ignored) { }
        try { refreshMemory(); } catch (Throwable ignored) { }
        notifyState();
        return r;
    }

    @Override public String memList() { return Memory.listText(); }

    @Override public String memPreview() { return Memory.preview(cfg); }

    /**
     * 模型自己调档位。**白名单，不是"什么都能改"**。
     *
     * 黑名单（永远不许碰）：`apiKey` / `apiBase` / `apiModel` / 采集分辨率 / 帧率。
     * 理由一句话：**调错了要能自己看出来**。档位错了是"图糊一点"，用户一眼能发现；
     * 换模型/端点错了是"账单炸了/连不上"，用户根本不知道是谁干的。
     */
    @Override public String tune(JSONObject a) {
        StringBuilder applied = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        try {
            if (a.has("tier")) {
                String v = a.getString("tier").trim().toLowerCase();
                if (v.equals("native") || v.equals("standard") || v.equals("compact")
                    || v.equals("full") || v.equals("single")) {
                    cfg.setTierStyle(v);
                    applied.append("档位=").append(tierLabel(v)).append(" ");
                } else bad.append("tier 只认 native/standard/compact/full/single；");
            }
            if (a.has("pace")) {
                String v = a.getString("pace").trim().toLowerCase();
                if (v.equals("calm") || v.equals("normal") || v.equals("dense")
                    || v.equals("game-economic")) {
                    cfg.setPace(v);
                    applied.append("节奏=").append(v).append(" ");
                } else bad.append("pace 只认 calm/normal/dense/game-economic；");
            }
            if (a.has("frames")) {
                int v = Math.max(2, Math.min(16, a.getInt("frames")));
                cfg.setMaxFrames(v);
                applied.append("每张最多 ").append(v).append(" 帧 ");
            }
            if (a.has("ctx")) {
                // 单位是 k token（和设置页一致）。"调大点/记性差" → 300 → 300k。
                int k = Math.max(2, Math.min(900, a.getInt("ctx")));
                cfg.setCtxTokens(k * 1000);
                applied.append("上下文 ").append(Config.ctxLabel(cfg.ctxTokens())).append(" ");
            }
            if (a.has("images")) {
                int v = Math.max(0, Math.min(3, a.getInt("images")));
                cfg.setImgWindow(v);
                applied.append("回看 ").append(v).append(" 张图 ");
            }
            if (a.has("summary")) {
                cfg.setSummary(a.getBoolean("summary"));
                applied.append("摘要 ").append(cfg.summary() ? "开" : "关").append(" ");
            }
            if (a.has("speak")) {
                cfg.setSpeak(a.getBoolean("speak"));
                applied.append("朗读 ").append(cfg.speak() ? "开" : "关").append(" ");
            }
            if (a.has("think")) {
                cfg.setThink(a.getString("think"));
                applied.append("思考=").append(cfg.thinkLabel()).append(" ");
            }
        } catch (Throwable e) {
            bad.append("参数读不懂：").append(e.getMessage()).append("；");
        }
        notifyState();
        try {
            JSONObject o = new JSONObject();
            if (bad.length() > 0) o.put("error", bad.toString());
            o.put("applied", applied.length() == 0 ? "（没有可应用的项）" : applied.toString().trim());
            o.put("tier", cfg.tierStyle());
            o.put("pace", cfg.pace());
            o.put("think", cfg.think());
            o.put("maxFrames", cfg.maxFrames());
            o.put("ctxTokens", cfg.ctxTokens());
            o.put("imgWindow", cfg.imgWindow());
            return o.toString();
        } catch (Throwable e) {
            return "{\"applied\":\"" + applied + "\"}";
        }
    }

    private static String tierLabel(String v) {
        if ("native".equals(v)) return "原生（一格一张，最清楚）";
        if ("compact".equals(v)) return "紧凑（格子小，省）";
        if ("full".equals(v)) return "铺满";
        if ("single".equals(v)) return "单帧整幅";
        return "标准";
    }

    // ---------------------------------------------------------------- 记忆

    /**
     * 记忆改了之后做一件事：**清掉可能还在飞的那一轮缓存**。
     *
     * 0.13.x 这里是把拼好的 block 塞进 `Brain` 的静态字段；v4 改成
     * `Asker` 每轮现读 —— 少一个跨轮次的隐式状态，也就少一个"改了不生效"的坑。
     * 这个方法保留，是因为调用点（设置页、memory_write）需要一个明确的"已刷新"动作。
     */
    void refreshMemory() {
        if (cfg == null) return;
        EyeLog.w("mem", Memory.status(cfg));
    }

    /** 设置页「忘掉这一条」。 */
    boolean forgetMem(String name) {
        boolean ok = Memory.remove(name);
        if (ok) { refreshMemory(); notifyState(); }
        return ok;
    }

    /** 设置页「全清」。 */
    void forgetAllMem() {
        Memory.clear();
        refreshMemory();
        notifyState();
    }

    // ---------------------------------------------------------------- 语音

    private void answerLine(String text) {
        if (text == null) return;
        lastLine = text;
        if (cfg == null || !cfg.speak()) return;
        String clean = Speaker.plain(text);
        String err = speaker == null ? "not-ready" : speaker.say(clean);
        if (err != null) EyeLog.w("tts", "这句没念出来: " + err);
    }

    /** `/say`：显式的朗读请求，**永远念**（那是有人明确要求出声）。 */
    public String say(String text) {
        if (text == null) return "empty";
        Ui u = uiSink;
        if (u != null) u.onReply(text, null, 0, null, 0);
        return speaker == null ? "not-ready" : speaker.say(text);
    }

    void testVoice() {
        if (speaker != null) speaker.say("我在。这是现在的声音。");
    }

    /** 清掉 MP3 缓存（换音色/换语速后旧缓存不再命中，但也不会自己消失）。 */
    void clearVoiceCache() {
        EdgeTts.clearCache();
        notifyState();
    }

    int voiceCacheCount() { return EdgeTts.cacheCount(); }

    /**
     * 可选的音色：**在线的那几个排在最前面**（小轩第一），后面才是系统的。
     *
     * 顺序是有意的：用户装完就想找"小轩"，翻到底下找它是不合理的；
     * 系统音色是兜底，排后面才对。
     */
    String[] voiceChoices() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("系统默认");
        for (String l : Config.EDGE_LABELS) out.add(l);
        if (speaker != null) out.addAll(speaker.voiceLabels());
        return out.toArray(new String[0]);
    }

    String voiceKeyAt(int i) {
        if (i <= 0) return "";
        if (i <= Config.EDGE_VOICES.length) return Config.EDGE_PREFIX + Config.EDGE_VOICES[i - 1];
        if (speaker == null) return "";
        int k = i - Config.EDGE_VOICES.length - 1;
        java.util.List<String> keys = speaker.voiceKeys();
        return k < keys.size() ? keys.get(k) : "";
    }

    @Override
    public String ttsVoice(java.util.Map<String, String> q) {
        if (speaker == null) return "语音还没起来";
        if (q.containsKey("name")) {
            String name = q.get("name");
            cfg.setTtsVoice(name);
            speaker.setVoice(name);
        }
        String real = speaker.voiceName().length() == 0 ? "系统默认" : speaker.voiceName();
        return "现在=" + real
            + "\n可选=" + java.util.Arrays.toString(voiceChoices())
            + "\n原始=" + speaker.describeVoices();
    }

    @Override public String ttsJson() {
        return speaker == null ? "{\"ready\":false}" : speaker.statusJson();
    }

    // ---------------------------------------------------------------- 设置

    /**
     * 设置页改完的那一刻调一次。
     *
     * v4 里它要管的东西少了很多（采集参数不再由用户配），剩下的每一条都**立刻生效**：
     * 语速、音色、超时、记忆注入。
     */
    void applySettings() {
        if (cfg == null) return;
        if (speaker != null) {
            speaker.setRate(cfg.rate());
            speaker.setEdgeRate(cfg.rate());
            speaker.setVoice(cfg.ttsVoice());
        }
        Brain.setReadTimeoutSec(cfg.timeoutSec());
        refreshMemory();
        // 设置里改了"自动看"，正在通话的页面要立刻跟着变（不然要点两次）
        if (callActive) autoWatch = cfg.autoWatch();
        // 灵敏度改了：立刻按新节奏来。退避计数也要清零，否则得等一分钟才生效。
        if (!cfg.pace().equals(appliedPace)) {
            appliedPace = cfg.pace();
            lastAutoAt = 0;                 // 立刻按新节奏来
            EyeLog.w("auto", "灵敏度 → " + cfg.paceLabel()
                + "（窗口 " + (autoGapMs() / 1000) + "s · " + CallCapture.FPS + "fps）");
        }
        // 攒批（wakeBatch）改了不会立刻生效：`queueDepth` 是 `CallStream.open()` 建管线时定的
        // （see-now 也一样 —— `queueDepth` 在 `createPipeline` 时定）。下一次进通话页才用新值。
        notifyState();
        EyeLog.w("svc", "apply think=" + cfg.think() + " speak=" + cfg.speak()
            + " ctx=" + Config.ctxLabel(cfg.ctxTokens())
            + " imgw=" + cfg.imgWindow() + " sum=" + cfg.summary()
            + " mem=" + cfg.memInject() + "(" + Memory.count() + "份)"
            + " tier=" + cfg.tierStyle() + " pace=" + cfg.pace()
            + " wake=" + cfg.wakeBatch());
    }

    /** `/mode`：两种模式都删了，**恒返回 normal** —— 老脚本不会崩。 */
    @Override
    public String modeSet(Map<String, String> q) {
        return "模式 = normal（v4 只有一种形态）";
    }

    /**
     * 写 `mode.txt`，**恒 normal**。见 {@link #MODE_FILE} 的注释。
     * 现在只在开机和设置变更时写一次 —— 不像 0.13.x 那样每次切模式都写。
     */
    void writeModeFile() {
        try {
            File f = new File(MODE_FILE);
            File d = f.getParentFile();
            if (d != null && !d.exists()) d.mkdirs();
            FileOutputStream o = new FileOutputStream(f, false);
            o.write("normal\n".getBytes("UTF-8"));
            o.close();
        } catch (Throwable e) {
            EyeLog.w("svc", "mode file 写不进去: " + e);
        }
    }

    // ---------------------------------------------------------------- CameraEngine.Sink

    @Override
    public void onJpeg(byte[] jpg, int w, int h, int rotate) {
        if (looker != null) looker.onFrame(jpg);
        try { sampleCallFrame(jpg); } catch (Throwable ignored) { }   // 通话页的滚动缓冲
        long s = seq + 1;
        seq = s;
        HttpServer.Frame f = new HttpServer.Frame(s, System.currentTimeMillis(), jpg);
        synchronized (frameLock) {
            frame = f;
            frameLock.notifyAll();
        }
        if (fpsAt == 0) { fpsAt = f.wall; fpsN = 0; }
        fpsN += 1;
        long span = f.wall - fpsAt;
        if (span >= 2000) {
            fpsVal = fpsN * 1000.0 / span;
            fpsAt = f.wall;
            fpsN = 0;
        }
    }

    /**
     * 引擎已经把 JPEG 解码 + 转好向的那张位图交过来了。
     *
     * 会话页没有取景器 → 没人接，直接回收；**通话页在前台时**交给它显示。
     * 注意：接过去之后这张位图就归界面所有了，这里不能回收（会和绘制撞车）。
     */
    @Override
    public void onPreviewBitmap(android.graphics.Bitmap bmp) {
        PreviewSink p = previewSink;
        if (p != null && bmp != null) {
            try { p.onPreview(bmp); return; } catch (Throwable ignored) { }
        }
        if (bmp != null) { try { bmp.recycle(); } catch (Throwable ignored) { } }
    }

    @Override
    public void onEngineState(String text) { Log.i(TAG, "cam: " + text); }

    private long fpsAt;
    private int fpsN;
    private volatile double fpsVal;

    /** 实测帧率（0 = 还没测出来）。`Pipeline` 的所有"秒 ↔ 帧"换算要它。 */
    double measuredFps() { return fpsVal; }

    // ---------------------------------------------------------------- HttpServer.Source

    @Override public HttpServer.Frame latestFrame() { return frame; }

    @Override
    public HttpServer.Frame waitFrame(long afterSeq, long timeoutMs) {
        synchronized (frameLock) {
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            while (running) {
                HttpServer.Frame f = frame;
                if (f != null && f.seq > afterSeq) return f;
                long wait = deadline - SystemClock.elapsedRealtime();
                if (wait <= 0) return null;
                try { frameLock.wait(wait); } catch (InterruptedException e) { return null; }
            }
            return null;
        }
    }

    /**
     * 「有画面正在流动」。
     *
     * v4 里它的含义变了：0.13.x 是"相机常驻、最近 8 秒内出过帧"，
     * 现在是"**有人借着相机**且刚出过帧"。用 8 秒太宽 —— 相机 3 秒就关了，
     * 光看帧龄会让 /status 在相机已经关了之后还报 streaming:true。
     */
    @Override public boolean streaming() {
        if (!running || engine == null) return false;
        HttpServer.Frame f = frame;
        return f != null && System.currentTimeMillis() - f.wall < 2000;
    }

    /** `/video/mjpeg` 有人连上/断开：借还相机。 */
    @Override public void streamOpened() { camAcquire("mjpeg"); }

    @Override public void streamClosed() { camRelease(); }

    /**
     * `/frame.jpg`：**按需抓一帧**（≤1.5 秒等不到就回 503）。
     *
     * v4 没有常驻采集了，所以这里要现场借相机。插件感知不到差别，只是首帧慢 0.5~1.5 秒 ——
     * 这正是规划里写明的代价（换来的是"不亮指示灯、不发热"）。
     */
    @Override
    public HttpServer.Frame grabFrame() {
        camAcquire("frame.jpg");
        try {
            long t0 = SystemClock.elapsedRealtime();
            HttpServer.Frame have = frame;
            // 缓存够新就直接给（连续抓图时不必每张都等）
            if (have != null && System.currentTimeMillis() - have.wall < 400) return have;
            long after = have == null ? 0 : have.seq;
            HttpServer.Frame f = waitFrame(after, 1500);
            EyeLog.w("cam", "frame.jpg 等了 " + (SystemClock.elapsedRealtime() - t0) + "ms");
            return f != null ? f : frame;
        } finally {
            camRelease();
        }
    }

    @Override public String statusJson() { return statusJsonImpl(); }

    /**
     * `/look`：现场抓一张拼好的宫格，**返回 JSON（图是 base64）**。
     *
     * 这个端点是 v4 里"电脑把手机当眼睛"那条线的下一个形态 —— 插件现在还用
     * `/frame.jpg` + `/video/mjpeg`，等它瘦身就换到这里。所以**返回结构要稳定**：
     * `{images:[b64], frames, tokens, why, canvas:{w,h}}`，少一个字段都会让将来那次改造
     * 变成"改两端"。
     */
    @Override
    public String lookSheet(double seconds) {
        newTurn();
        Tools.Look l = look(seconds);
        if (l == null || l.jpeg == null) return "{\"error\":\"no frame\"}";
        try {
            JSONObject o = new JSONObject();
            org.json.JSONArray arr = new org.json.JSONArray();
            arr.put(android.util.Base64.encodeToString(l.jpeg, android.util.Base64.NO_WRAP));
            o.put("images", arr);
            o.put("frames", l.frames);
            o.put("tokens", l.tokens);
            o.put("why", l.why == null ? "time" : l.why);
            o.put("canvas", new JSONObject()
                .put("w", Tokens.canvasW()).put("h", Tokens.canvasH()));
            return o.toString();
        } catch (Throwable e) {
            return "{\"error\":\"" + Json.esc(String.valueOf(e)) + "\"}";
        }
    }

    /** `/config`：写配置 + 返回脱敏状态。key 永远不完整回显。 */
    @Override
    public String setConfig(Map<String, String> q) {
        if (q.containsKey("clear")) cfg.setKey("");
        if (q.containsKey("key")) cfg.setKey(q.get("key"));
        if (q.containsKey("base")) cfg.setBase(q.get("base"));
        if (q.containsKey("model")) cfg.setModel(q.get("model"));
        if (q.containsKey("think")) cfg.setThink(q.get("think"));
        applySettings();
        return cfg.describe();
    }

    /**
     * 同步问一句（给电脑端 / 自测用）。
     *
     * 线程要注意：这里跑在 HttpServer 的连接线程上，而 Asker 是同步阻塞的，
     * 所以**直接在这里跑完**，不用再开线程 —— 本来就不在主线程。
     */
    @Override
    public String askOnce(String text, int timeoutSec) {
        final String t = text == null ? "" : text.trim();
        if (t.length() == 0) return "text 不能为空";
        if (!cfg.direct()) return "还没填 API key";
        final java.util.List<Conv.Out> ctx = contextFor();
        newTurn();
        final Asker asker = new Asker(cfg, this);
        final String[] out = new String[1];
        final String[] reason = new String[1];
        final long[] thinkMs = new long[1];
        asker.ask(t, (byte[]) null, ctx, new Asker.Cb() {
            public void onTool(String name, String phase, String detail) {
                Ui u = uiSink;
                if (u != null) u.onTool(name, phase, detail);
            }
            public void onDelta(String d) {
                Ui u = uiSink;
                if (u != null) u.onDelta(d);
            }
            public void onRoundReset() {
                Ui u = uiSink;
                if (u != null) u.onRoundReset();
            }
            public void onStopped(String partial) {
                // /ask 是同步接口，停止就是"返回已经收到的部分"
                out[0] = partial == null || partial.length() == 0 ? "（已停止）" : partial;
            }
            public void onText(String s, String r, long ms) {
                out[0] = s;
                reason[0] = r;
                thinkMs[0] = ms;
            }
            public void onFail(String why) { out[0] = "（没答上：" + why + "）"; }
        });
        if (out[0] == null) return "（没答上）";
        // 和界面那条路走**完全一样**的落盘：reasoning 跟消息走、图挂在 user 上。
        // 以前这里传的是 null —— 于是 PC 上问出来的那几轮，在手机上点开看不到"思考 N 秒"。
        byte[] sheet = lastLook == null ? null : lastLook.jpeg;
        int frames = lastLook == null ? 0 : lastLook.frames;
        rememberTurn(t, out[0], reason[0], thinkMs[0], sheet, frames);
        lastLook = null;
        return out[0];
    }

    String statusJsonImpl() {
        StringBuilder b = new StringBuilder(400);
        b.append("{\"app\":\"鱼眼\",\"version\":\"").append(BuildConfig.version(this)).append("\"")
         .append(",\"streaming\":").append(streaming())
         .append(",\"cameraOpen\":").append(engine != null)
         .append(",\"camera\":\"").append(engine != null && engine.isFront() ? "front" : "back").append("\"")
         .append(",\"width\":").append(engine == null ? 0 : engine.outWidth())
         .append(",\"height\":").append(engine == null ? 0 : engine.outHeight())
         .append(",\"clients\":").append(http == null ? 0 : http.clients())
         .append(",\"httpListening\":").append(http != null && http.listening())
         .append(",\"mode\":\"normal\"")
         .append(",\"think\":\"").append(cfg == null ? "low" : cfg.think()).append("\"")
         .append(",\"speak\":").append(cfg == null || cfg.speak())
         .append(",\"tools\":").append(cfg != null && cfg.tools())
         .append(",\"tierStyle\":\"").append(cfg == null ? "standard" : cfg.tierStyle()).append("\"")
         /**
          * 档位判据。**默认必须是 `density`**（see-now `pipeline.js:290` 的默认）。
          * 报出来是为了能一眼核对"手机端有没有偷偷跟电脑版分叉"——
          * 2026-11-14 就是把默认改成 weight 没被发现，用户当场指出来的。
          */
         .append(",\"tierMode\":\"").append(Tuning.load().str("tierMode", "density")).append("\"")
         .append(",\"pace\":\"").append(cfg == null ? "normal" : cfg.pace()).append("\"")
         .append(",\"maxFrames\":").append(cfg == null ? 0 : cfg.maxFrames())
         .append(",\"wakeBatch\":").append(cfg == null ? 1 : cfg.wakeBatch())
         .append(",\"conv\":\"").append(convId()).append("\"")
         .append(",\"convTitle\":\"").append(Json.esc(convTitle())).append("\"")
         .append(",\"convMsgs\":").append(history.size())
         // turns 这个字段留着（插件/脚本可能在读），但轮数闸已删，恒为"不限"
         .append(",\"turns\":").append(Config.TURNS_UNLIMITED)
         .append(",\"ctxTokens\":").append(cfg == null ? 0 : cfg.ctxTokens())
         .append(",\"ctxEst\":").append(ctxEstimate())
         .append(",\"imgWindow\":").append(cfg == null ? 0 : cfg.imgWindow())
         .append(",\"summary\":").append(cfg != null && cfg.summary())
         .append(",\"sumLen\":").append(convSummary == null ? 0 : convSummary.length())
         .append(",\"memInject\":").append(cfg != null && cfg.memInject())
         .append(",\"memFiles\":").append(Memory.count())
         .append(",\"memTokens\":").append(Conv.textTokens(Memory.block(cfg)));
        b.append(",\"tts\":").append(ttsJson());
        String e = engine == null ? null : engine.lastError();
        if (e == null && http != null) e = http.lastError();
        b.append(",\"error\":").append(e == null ? "null" : "\"" + Json.esc(e) + "\"");
        b.append("}");
        return b.toString();
    }

    @Override
    public String applyControl(Map<String, String> q) {
        StringBuilder done = new StringBuilder();
        // 排障口：把 SharedPreferences 原样吐出来（"这个开关到底存成什么了"）
        if (q.get("peek") != null) return cfg.dump();
        if (q.get("torch") != null) {
            boolean on = "on".equalsIgnoreCase(q.get("torch")) || "1".equals(q.get("torch"));
            camAcquire("torch");
            if (engine != null) engine.setTorch(on);
            camRelease();
            done.append("torch=").append(on ? "on" : "off").append(" ");
        }
        if (q.get("camera") != null) {
            camAcquire("flip");
            if (engine != null) {
                String v = q.get("camera").toLowerCase();
                if (v.equals("toggle")) engine.flip();
                else if (v.equals("front") && !engine.isFront()) engine.flip();
                else if (v.equals("back") && engine.isFront()) engine.flip();
            }
            camRelease();
            done.append("camera=").append(q.get("camera")).append(" ");
        }
        if (q.get("think") != null) {
            cfg.setThink(q.get("think"));
            done.append("think=").append(cfg.think()).append(" ");
        }
        if (q.get("speak") != null) {
            boolean on = "on".equalsIgnoreCase(q.get("speak")) || "1".equals(q.get("speak"));
            cfg.setSpeak(on);
            done.append("speak=").append(cfg.speak() ? "on" : "off").append(" ");
        }
        if (q.get("clear") != null) {
            clearConv();
            done.append("cleared ");
        }
        if (q.get("say") != null) {
            String err = speaker.say(q.get("say"));
            done.append(err == null ? "say=ok " : "say=" + err + " ");
        }
        notifyState();
        return done.length() == 0 ? "no-op" : done.toString().trim();
    }

    // ---------------------------------------------------------------- 事件/通知

    private void notifyState() {
        Ui u = uiSink;
        if (u != null) u.onState();
    }

    private void notifyError(String msg) {
        Ui u = uiSink;
        if (u != null) u.onError(msg);
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (!running) return;
            try { maybeCloseCamera(); } catch (Throwable ignored) { }
            try { maybeAutoSend(); } catch (Throwable ignored) { }
            try { refreshNotification(); } catch (Throwable ignored) { }
            try { dumpStatus(); } catch (Throwable ignored) { }
            ui.postDelayed(this, 1000);
        }
    };

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "鱼眼·常驻",
                NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return b.setContentTitle("鱼眼")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
    }

    private String lastNotiText;

    private void refreshNotification() {
        String text = (cfg == null ? "就绪" : "思考 " + cfg.thinkLabel())
            + (engine != null ? " · 相机开" : " · 相机待机");
        // 只在文字变了才 notify：这个 tick 是 1 秒一次，每秒推一条通知是白耗电
        if (text.equals(lastNotiText)) return;
        lastNotiText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTI_ID, buildNotification(text));
    }

    /** 10 分钟一次的"兜底网"：被系统清掉后自己回来。 */
    static void scheduleWake(Context ctx, long intervalMs) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(ctx, BootReceiver.class);
            i.setAction(WAKE_ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, i, 0);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + intervalMs, intervalMs, pi);
        } catch (Throwable e) {
            Log.w(TAG, "scheduleWake: " + e);
        }
    }

    // ---------------------------------------------------------------- 兼容：老插件的 say.txt

    private volatile long lastSeen;
    private Thread filePoll;
    private long lastStatusDump;

    /**
     * 老插件（0.1.x）只会往 `/sdcard/Download/DSH-Go/say.txt` 追加 `<ts>|<文本>`。
     * 新插件走 `/say`，但手机上装的可能还是老的 —— 两个口子都开着才叫"装上就能用"。
     */
    private void startFilePoll() {
        lastSeen = readSeen();
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { pollSayFile(); } catch (Throwable e) { /* 文件没了不是错误 */ }
                    try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                }
            }
        }, "dsh-eye-sayfile");
        t.setDaemon(true);
        filePoll = t;
        t.start();
    }

    private void pollSayFile() {
        File f = new File(SAY_FILE);
        if (!f.exists() || !f.canRead()) return;
        long maxSeen = lastSeen;
        String latest = null;
        try {
            // ⚠ 千万别用 RandomAccessFile.readLine()：它按 ISO-8859-1 一个字节一个字符地读，
            // UTF-8 的中文会变成乱码再喂给 TTS。
            byte[] raw = readAll(f);
            if (raw == null) return;
            String text = new String(raw, "UTF-8");
            for (String line : text.split("\n")) {
                int bar = line.indexOf('|');
                if (bar <= 0) continue;
                long ts;
                try { ts = Long.parseLong(line.substring(0, bar).trim()); } catch (Throwable e) { continue; }
                if (ts <= maxSeen) continue;
                maxSeen = ts;
                latest = line.substring(bar + 1);
            }
        } catch (Throwable e) {
            return;
        }
        if (latest == null) return;
        lastSeen = maxSeen;
        writeSeen(maxSeen);
        say(latest);
    }

    private static byte[] readAll(File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            if (f.length() > 256 * 1024) return null;   // 这是信箱，不是日志
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n <= 0) break;
                off += n;
            }
            return buf;
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private long readSeen() {
        try {
            File f = new File(getFilesDir(), "seen.txt");
            if (!f.exists()) return 0;
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            String s = raf.readLine();
            raf.close();
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    private void writeSeen(long v) {
        try {
            RandomAccessFile raf = new RandomAccessFile(new File(getFilesDir(), "seen.txt"), "rw");
            raf.setLength(0);
            raf.write((v + "\n").getBytes("UTF-8"));
            raf.close();
        } catch (Throwable ignored) { }
    }

    /** 顺手维护老诊断文件，`adb exec-out cat` 还能照旧看。 */
    private void dumpStatus() {
        long now = System.currentTimeMillis();
        if (now - lastStatusDump < 20_000) return;  // tick 是 1 秒一次，别每秒都写盘
        lastStatusDump = now;
        try {
            File f = new File(STATUS_FILE);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            String s = "ttsReady=" + (speaker != null && speaker.isReady())
                + "\nthink=" + (cfg == null ? "" : cfg.think())
                + "\ncameraOpen=" + (engine != null)
                + "\nconv=" + convId()
                + "\nmsgs=" + history.size()
                + "\nlastError=" + (speaker == null ? "" : speaker.lastError())
                + "\n";
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            raf.write(s.getBytes("UTF-8"));
            raf.close();
        } catch (Throwable ignored) { }
    }
}
