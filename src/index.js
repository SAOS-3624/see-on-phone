/**
 * SeeNow Phone Version — 手机摄像头版的 SeeNow。
 *
 * ============================ 它和 see-now 是什么关系 ============================
 *
 * 同一套「帧 → 打分 → 拼图 → PNG」引擎（`src/engine/` 整块从 dsh-see-now vendor 过来），
 * **采集层整个换掉**：PC 版截屏幕（ddagrab/gdigrab/portal），手机版开摄像头
 * （termux-camera-photo 抓拍 / MJPEG / RTSP）。
 *
 * 但真正变的不只是采集 —— 是**读者**：
 *   - `see_now` 的读者是"能看见屏幕的人"，图是给他自己确认用的辅助；
 *   - `see_phone` 的读者是**看不见的盲人**，图只给模型，模型说什么他就信什么。
 *
 * 所以这里刻意不做 see-now 的那些东西：没有宫格时间线（要的是"此刻"）、
 * 没有浮窗（他看不见）、没有字幕/ASR（说的是他身边，不是视频里）、没有 region。
 * 反过来多做了 see-now 不会做的一件事：**每次出图都强制走一次系统 TTS**。
 *
 * 设计取舍记在 README 的「为什么不是 see-now 加个 --camera」。
 * ============================================================================
 */
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createPipeline } from './engine/pipeline.js';
import { openCamera } from './cam/camera.js';
import { seePhoneToolSpec, TOOL_NAME, DEFAULT_WAIT_SEC } from './tool-spec.js';
import { buildLookText, buildGuideText, buildStatusText, gradeView } from './guide.js';
import { speak, ttsState, stopSpeaking } from './tts.js';
import { loadDefineTool } from './define-tool.js';

export const inject = ['tools', 'attachments'];

/** 竖持手机，4:3 竖构图。够看清两三米内的台阶和门框，单张 ≈ 130 token。 */
const DEFAULT_SIZE = Object.freeze({ width: 336, height: 448 });

/** 抓拍后端的真实节奏：termux-camera-photo 单次 150~500ms，取 220ms 留余量。 */
const DEFAULT_GRAB_MS = 220;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** cordis 的 without-inject 会让 `ctx.attachments` 直接 throw，`?.` 挡不住。 */
function serviceOf(ctx, key) {
  try {
    if (typeof ctx?.get === 'function') {
      const v = ctx.get(key);
      if (v) return v;
    }
  } catch { /* 没声明 inject 时 get 陷阱会 throw */ }
  try { return ctx?.[key] ?? null; } catch { return null; }
}

/** 宿主校验 `JSON.parse(JSON.stringify(v))` 等价，`undefined` 会让整条调用作废。 */
function toLosslessJson(value) {
  if (value === undefined) return null;
  return JSON.parse(JSON.stringify(value));
}

function loadConfig() {
  // 测试与实机调参都走这一个入口：`DSH_SEE_PHONE_CONFIG='{"backend":"testsrc"}'` 优先
  const inline = process.env.DSH_SEE_PHONE_CONFIG;
  if (inline) {
    try { return JSON.parse(inline) ?? {}; } catch { /* 写坏了就当没写 */ }
  }
  try {
    return JSON.parse(readFileSync(join(homedir(), '.dsh', 'see-phone.json'), 'utf8')) ?? {};
  } catch {
    return {};
  }
}

/**
 * 平均亮度。抽样而不是全量：336×448×3 全扫一遍 0.5ms，但 8fps 下就是白烧。
 * 台阶/门框这类东西在 Luma 上分得开，不需要精确。
 */
function meanLuma(buf) {
  let sum = 0;
  let n = 0;
  // 每 61 个像素取一个（与 3 互质，避免正好踩在同一列上）
  for (let i = 0; i + 2 < buf.length; i += 3 * 61) {
    sum += 0.299 * buf[i] + 0.587 * buf[i + 1] + 0.114 * buf[i + 2];
    n += 1;
  }
  return n ? sum / n : 0;
}

/**
 * 摄像头会话：采集 + 管线 + 最近一帧。
 *
 * 单例。手机上只有一个摄像头，两个会话必然互相抢。
 */
function createSession(cfg) {
  const size = {
    width: Number(cfg.width) || DEFAULT_SIZE.width,
    height: Number(cfg.height) || DEFAULT_SIZE.height,
  };
  const camOpts = {
    backend: cfg.backend ?? 'http-raw',
    // 手机上默认连 Termux 那个帧服务（cam_server.py）：抓拍 + 缩放 + rgb24 都在
    // 手机侧做完，插件侧零依赖（PRoot 里没有 ffmpeg 也能跑）。
    // 两个 App 共享网络栈，127.0.0.1 直连，不需要端口映射。
    url: cfg.url ?? 'http://127.0.0.1:8123/frame',
    width: size.width,
    height: size.height,
    fps: Number(cfg.fps) || 8,
    grabIntervalMs: Number(cfg.grabIntervalMs) || DEFAULT_GRAB_MS,
    cameraId: cfg.camera === 'front' ? 1 : (Number(cfg.cameraId) || 0),
    ffmpegPath: cfg.ffmpegPath ?? 'ffmpeg',
  };

  const pipeline = createPipeline({
    width: size.width,
    height: size.height,
    fps: camOpts.fps,
    // 导盲不排队：手上有一张够新就够，攒着没意义
    queueDepth: 1,
  });

  const state = {
    size, camOpts, pipeline,
    cam: null,
    startedAt: 0,
    lastWall: 0,
    lastT: 0,
    lastLuma: null,
    lastError: null,
    frames: 0,
    sheets: 0,
    stopReason: null,
  };

  state.start = async () => {
    if (state.cam) return state;
    state.cam = openCamera({
      ...camOpts,
      onFrame: (f) => {
        state.lastWall = f.wall;
        state.lastT = f.t;
        state.frames += 1;
        state.lastLuma = meanLuma(f.buf);
        try { pipeline.push({ t: f.t, buf: f.buf }); } catch (err) { state.lastError = err.message; }
      },
      onError: (err) => { state.lastError = err.message; },
      onExit: (info) => {
        // 采集自己退了（摄像头被别的 App 抢走、ffmpeg 崩了）—— 记下来，别让上层以为还在看
        if (state.cam) state.stopReason = `采集退出 code=${info.code}`;
        if (info.stderrTail) state.lastError = info.stderrTail.slice(-300);
      },
    });
    await state.cam.start();
    state.startedAt = Date.now();
    return state;
  };

  state.stop = async () => {
    await state.cam?.stop();
    state.cam = null;
    state.stopReason = 'stopped';
  };

  state.snapshot = () => {
    const cam = state.cam?.snapshot() ?? {
      backend: camOpts.backend ?? '?', width: size.width, height: size.height,
      running: false, frames: 0, fps: 0,
    };
    return { ...cam, grab: cam.grab ?? { shots: 0, fails: 0 } };
  };

  /** 等到一帧足够新的画面。抓拍后端 3~4fps，250ms 内基本一定有。 */
  state.waitFresh = async (maxMs) => {
    const t0 = Date.now();
    while (Date.now() - t0 < maxMs) {
      if (state.lastWall && Date.now() - state.lastWall <= 400) return true;
      await sleep(60);
    }
    return Boolean(state.lastWall);
  };

  return state;
}

export async function apply(ctx) {
  const defineTool = await loadDefineTool();
  const cfg = loadConfig();
  let session = null;
  let disposers = [];

  const log = (...a) => { try { ctx.logger?.info?.('[see-phone]', ...a); } catch { /* 无 logger */ } };

  /** 拿会话；没有就建。任何 action（除 help/status 未启动）都要先过这里。 */
  async function ensure() {
    if (session?.cam) return session;
    if (!session) session = createSession(cfg);
    try {
      await session.start();
      log('摄像头已启动', session.camOpts.backend ?? 'auto', session.size);
    } catch (err) {
      // 起不来不是致命错：把原因留在 session 上，工具返回里说清楚
      session.lastError = err.message;
      session.cam = null;
      throw err;
    }
    return session;
  }

  async function attach(ctx2, sheet, text, meta) {
    const svc = serviceOf(ctx2, 'attachments');
    if (!svc?.saveImage) {
      return { text: `${text}\n\n（宿主没有 attachments 服务，图交不上去 —— 只能靠上面这段文字。）`, meta: { ...meta, ok: false } };
    }
    const ref = await svc.saveImage.call(svc, {
      data: sheet.png,
      mediaType: 'image/png',
      name: `see-phone-${Date.now()}.png`,
    });
    return { images: [ref], text, meta: { ...meta, ok: true } };
  }

  async function doLook(args) {
    const s = await ensure();
    const waitSec = Number.isFinite(Number(args.wait)) && Number(args.wait) > 0
      ? Math.min(Number(args.wait), 30)
      : DEFAULT_WAIT_SEC;

    const fresh = await s.waitFresh(waitSec * 1000);
    if (!fresh) {
      const snap = s.snapshot();
      return {
        text: `摄像头没有出画面（${waitSec}s 内 0 帧）。\n${buildStatusText({ cam: snap, pipeline: null, quality: 'none' })}`
          + `\n\n${s.lastError ? `原因：${s.lastError}` : '可能是权限没给，或者摄像头被别的 App 占着。'}`,
        meta: { ok: false, reason: 'no-frame', backend: snap.backend },
      };
    }

    const sheet = s.pipeline.flushNow(s.lastT, 'look');
    if (!sheet) {
      return {
        text: '拿到帧了，但没能拼出图（管线里没有可用帧）。这通常意味着采集刚开始，再调一次 `look`。',
        meta: { ok: false, reason: 'no-sheet' },
      };
    }

    s.sheets += 1;
    const ageSec = (Date.now() - s.lastWall) / 1000;
    const quality = gradeView({ meanLuma: s.lastLuma, frames: s.frames, firstFrameMs: null });
    const mode = String(args.mode ?? cfg.mode ?? 'general').toLowerCase();
    const note = args.note ? String(args.note).slice(0, 300) : null;
    const backend = s.snapshot().backend;
    const text = mode === 'guide'
      ? buildGuideText({ note, ageSec, quality })
      : buildLookText({ note, ageSec, frames: sheet.frames.length, quality, backend });
    return attach(ctx, sheet, text, {
      ageSec: +ageSec.toFixed(2),
      quality,
      luma: s.lastLuma == null ? null : Math.round(s.lastLuma),
      sheets: s.sheets,
      backend: s.snapshot().backend,
    });
  }

  async function doWatch(args) {
    const s = await ensure();
    const waitSec = Number.isFinite(Number(args.wait)) && Number(args.wait) > 0
      ? Math.min(Number(args.wait), 120)
      : DEFAULT_WAIT_SEC * 4;
    const note = args.note ? String(args.note).slice(0, 300) : null;
    const mode = String(args.mode ?? cfg.mode ?? 'general').toLowerCase();
    const quality = gradeView({ meanLuma: s.lastLuma, frames: s.frames });
    const backend = s.snapshot().backend;
    const t0 = Date.now();

    // pipeline 在 push 里按"变化量 / 张数 / 等待时间"自己 flush，
    // watch 只负责把攒出来的那张（可能是多帧宫格）领走。
    while (Date.now() - t0 < waitSec * 1000) {
      const sheet = s.pipeline.take();
      if (sheet) {
        s.sheets += 1;
        const ageSec = (Date.now() - s.lastWall) / 1000;
        const text = mode === 'guide'
          ? buildGuideText({ note, ageSec, quality })
          : buildLookText({ note, ageSec, frames: sheet.frames.length, quality, backend });
        return attach(ctx, sheet, text, {
          ok: true, sheets: s.sheets, frames: sheet.frames.length,
          why: sheet.why, backend,
        });
      }
      await sleep(150);
    }
    return {
      text: `${waitSec} 秒内没有出图。静止画面会一直攒着不叫你（这是省 token 的设计）；`
        + '要「此刻」单帧就调 `look`，或直接 `status` 看采集是不是停了。',
      meta: { ok: false, reason: 'no-sheet' },
    };
  }

  async function doStatus() {
    if (!session) {
      return {
        text: `还没启动过摄像头。配置：${JSON.stringify(cfg)}\n`
          + '调一次 `see_phone({action:"look"})` 就会按配置文件启动。',
        meta: { ok: true, running: false },
      };
    }
    const snap = session.snapshot();
    const quality = gradeView({ meanLuma: session.lastLuma, frames: session.frames });
    const t = buildStatusText({ cam: snap, pipeline: session.pipeline, quality });
    return { text: t, meta: { ok: true, running: snap.running, backend: snap.backend } };
  }

  async function route(args = {}) {
    const action = String(args.action ?? 'watch').toLowerCase();

    // `say` 在任何动作之前处理：用户等着回话时，一张图晚两秒无所谓，一句话晚两秒就很难受
    const said = args.say ? String(args.say).trim().slice(0, 400) : null;
    let speakResult = null;
    if (said) speakResult = await speak(said);

    switch (action) {
      case 'help':
        return { text: HELP, meta: { action } };
      case 'status':
        return doStatus();
      case 'stop': {
        stopSpeaking();
        if (session) await session.stop();
        const had = Boolean(session);
        session = null;
        return { text: had ? '摄像头已关，不再耗电。' : '本来就没在跑。', meta: { ok: true } };
      }
      case 'watch':
        return doWatch(args);
      case 'look':
      default: {
        const out = await doLook(args);
        if (speakResult && !speakResult.ok && said
            && String(args.mode ?? cfg.mode ?? 'general').toLowerCase() === 'guide') {
          return { ...out, text: `${out.text}\n\n⚠ 没找到 termux-tts-speak：把要说的话写进回复里，让用户用系统读屏听。` };
        }
        return out;
      }
    }
  }

  const tool = {
    ...seePhoneToolSpec(),
    async execute(args = {}) {
      const out = await route(args);
      return toLosslessJson(out);
    },
  };

  disposers.push(ctx.tools.register(defineTool(tool)));
  log(`${TOOL_NAME} 已注册`);

  ctx.on?.('dispose', () => {
    try { stopSpeaking(); } catch { /* ignore */ }
    session?.stop?.().catch?.(() => {});
    for (const d of disposers) { try { d?.(); } catch { /* ignore */ } }
    disposers = [];
  });
}

/**
 * `action:"help"` —— 契约里塞不下的东西放这里。
 *
 * 判据同 see-now：**删掉这句，模型会不会用错？** 会 → 进契约；不会 → 进这里。
 */
const HELP = `SeeNow Phone Version · 手机摄像头导盲

## 它在做什么
手机摄像头 → 手机侧缩放成 rgb24 → 打分/切点 → 装箱 DP 拼图 → 图片附件交给宿主。\n与桌面 \`see_now\` 同一套引擎，只是采集源从屏幕换成摄像头。

## 采集后端（配置文件 ~/.dsh/see-phone.json 的 backend）
- \`termux\`（默认）：Termux:API 抓拍，2~4 fps，零额外 App，**够用来判断台阶/门/人**
- \`mjpeg\`：外部 App 开 MJPEG（IP Webcam 等），需要 url，接近原生帧率
- \`rtsp\`：DroidCam 等，需要 url
- \`v4l2\` / \`avfoundation\` / \`dshow\`：桌面调试用（PC 上的摄像头）
- \`file\` / \`testsrc\`：回归测试用

## 成本
一张 ${DEFAULT_SIZE.width}×${DEFAULT_SIZE.height} 单帧图 ≈ 130 token，且**会留在会话历史里每轮重发**。
连续导盲 10 分钟（每 3 秒一次）= 200 张 ≈ 2.6 万 token 的常驻负担。
所以：只在用户真的要"看"的时候 \`look\`，别拿它当轮询。

## 已知限制
- termux 抓拍是"一次一张"，走路时画面会比人慢半拍；说方位时用"刚才"而不是"现在"
- 全黑/太暗时**不会**出图（gradeView 拦掉了），只会告诉你请用户调整
- 单摄像头：\`look\` 和 \`watch\` 不能真正并发`;
