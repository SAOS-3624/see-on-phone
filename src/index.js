/**
 * See on Phone — 手机摄像头版的 SeeNow。
 *
 * ============================ 它和 see-now 是什么关系 ============================
 *
 * 同一套「帧 → 打分 → 拼图 → PNG」引擎（`src/engine/` 整块从 dsh-see-now vendor 过来，
 * 拼图/取帧算法跟着桌面版一起升级），**采集层整个换掉**：PC 版截屏幕
 * （ddagrab/gdigrab/portal），手机版开摄像头（MJPEG / 抓拍 / RTSP）。
 *
 * 真正要注意的是**读者**，而且有两位，由 `mode` 选（见 src/mode.js）：
 *   - `normal`（默认）：用户自己看得见画面。宫格按时间线摊开，回答按普通会话说。
 *   - `guide`：用户看不见（导盲）。单帧「此刻」、先风险后动作、可以直接念。
 *
 * 0.2.x 把导盲当成唯一定位写死在工具描述里，于是"看一眼"这种中性请求也会被接成
 * 导盲口吻 —— 0.3.0 起默认 normal，导盲是显式的第二档。
 * ============================================================================
 */
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createPipeline } from './engine/pipeline.js';
import { readTuning } from './engine/tuning.js';
import { PACES, SCENE_OPTS, DEFAULT_PACE } from './engine/pace.js';
import { openCamera } from './cam/camera.js';
import { seePhoneToolSpec, TOOL_NAME, DEFAULT_WAIT_SEC } from './tool-spec.js';
import { buildLookText, buildGuideText, buildStatusText, gradeView } from './guide.js';
import { resolveMode, readAppMode, sizesFor, describeMode } from './mode.js';
import { speak, ttsState, stopSpeaking } from './tts.js';
import { loadDefineTool } from './define-tool.js';
import { startInbox } from './inbox.js';

export const inject = ['tools', 'attachments'];

/**
 * 取帧间隔。
 * 抓拍后端：termux-camera-photo 单次 ~800ms，220ms 留余量；
 * http-raw（流模式）：本地回环单帧 ~25ms、源 15–20fps，必须压到 70ms 才跟得上。
 */
const DEFAULT_GRAB_MS = 70;

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

/**
 * 出图相关设置的签名。只看**会影响管线输出的那几个键** —— `listen` 段和
 * `imageTrigger` 这些手机上根本没接，它们变了不该白白重开一次摄像头。
 */
export function tuningSignature(t) {
  return JSON.stringify([
    t?.tierStyle, t?.tierMap, t?.tierMode, t?.tierCurve, t?.tierThresholds,
    t?.scoreNormalize, t?.changeLog, t?.defaultPace,
  ]);
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
 * 给 IP Camera App 下发的控制参数（`GET /?camera=back&rotate=90`）。
 *
 * `rotate` 默认 90 是**实测结论**，不是猜的：nova 11 的 sensor 成像相对手机
 * 自然方向转了 90°，不转的话源是 800×600 横图，`cover` 缩到 336×448 要横向
 * 裁掉 44% 画面，而且模型说的"左/右/前"全错。转 90° 后源是 600×800 = 3:4，
 * 和 336×448 同比例，零裁切。
 *
 * 换机型如果发现画面是歪的，把配置里的 `rotate` 改成 0/180/270 试。
 */
function buildPreset(cfg) {
  const preset = {};
  const cam = String(cfg.camera ?? 'back').toLowerCase();
  if (cam === 'front' || cam === 'back') preset.camera = cam;
  const rotate = cfg.rotate === undefined || cfg.rotate === null ? 90 : Number(cfg.rotate);
  if (Number.isFinite(rotate)) preset.rotate = rotate;
  if (cfg.resolution) preset.resolution = String(cfg.resolution);
  if (cfg.torch === true || cfg.torch === 'on') preset.torch = 'on';
  return Object.keys(preset).length ? preset : null;
}

/**
 * 建管线用的参数 —— **这一份就是 see-now 的口径，一个字都不许自己发挥**。
 *
 * 桌面版 `see-now/src/runner.js::start()` 建 `createPipeline` 时传的就是这几组：
 * `PACES[pace].pipeline` → `strategy`（=设置页的 tierStyle）→ `tierMap` / `tierMode` /
 * `tierCurve` / `tierThresholds` → `scoreNormalize` → `changeLog` → `cutAbs`。
 *
 * 0.3.0 之前这里只有 `{width, height, fps, queueDepth}`，等于把上面那一整套
 * **全部退回 `pipeline.js::DEFAULTS`**。后果不是"略差"，是三处会真出错：
 *
 * - `strategy` 落到 `native`（而 see-now 的默认是 `standard` = 1 档压成 2 档）：
 *   单帧图会占满整幅、多帧时张数爆炸 —— see-now 改成 standard 就是为了这个。
 * - `floorGapK` 落到 0：自适应兜底整个关掉，只剩固定的 `silentFloorSec: 20`，
 *   半动的画面（出帧间隔 2~10 秒）会长时间不给图。
 * - `tierThresholds` 落到 null：阈值表跟着 `tierCurve` 走，用户配的自定义七道不生效。
 *
 * @param {{size:{width:number,height:number}, fps:number, mode:string,
 *          tune:object, pace:string, audioStrip:boolean}} p
 */
export function pipelineOptions({ size, fps, mode, tune, pace, audioStrip = false }) {
  const paceName = PACES[pace] ? pace : DEFAULT_PACE;
  const p = PACES[paceName];

  /**
   * 导盲 = 桌面版的「逐帧模式」（`scene:true`）：一张图一个画面，格子必须撑满。
   *
   * 为什么非这样不可：`standard` 会把 1 档压成 2 档，而 `look` 的宫格里**只有一帧** ——
   * 不压的话这一帧只占 1/4 画布，导盲用户看到的就是一张"四分之一是画面、其余是灰"的图。
   * `SCENE_OPTS` 的 `strategy:'single'` 正是为这种情况准备的（见 pace.js 的注释），
   * 两个键**必须成对**出现。
   */
  if (mode === 'guide') {
    return {
      width: size.width, height: size.height, fps,
      // 导盲不排队：手上有一张够新就够，攒着没意义
      queueDepth: 1,
      ...SCENE_OPTS,
      // 逐帧模式的切点门槛跟 `dense` 走（同 see-now 的 `sceneMode ? PACES.dense : ...`）
      cutAbs: PACES.dense.cutAbs,
      // 档位映射由 SCENE_OPTS 钉成 single，但**怎么算档位**仍然听设置的
      tierMode: tune.tierMode,
      tierCurve: tune.tierCurve,
      tierThresholds: tune.tierThresholds,
      scoreNormalize: tune.scoreNormalize,
      changeLog: tune.changeLog,
      audioStrip,
      /**
       * 帧率归一化：手机的实际 fps 是 6~17 档（配置默认 8），和电脑版的 12fps 基准差得远 ——
       * 不折算的话 `smoothFrames` / `BAR_WINDOW` / `cutBgWindow` 这三处"按帧的窗口"
       * 在 8fps 下会变成 0.75s / 3s / 0.75s（12fps 是 0.5 / 2 / 0.5）。
       * 详见 `engine/pipeline.js` 的 `timeNormalizeFrames`。
       */
      timeNormalizeFrames: true,
    };
  }

  return {
    width: size.width, height: size.height, fps,
    queueDepth: 1,
    // 节奏档自带的管线覆盖（积分阈值、每张帧数、兜底间隔…）
    ...(p.pipeline ?? {}),
      /**
       * 帧率归一化：手机的实际 fps 是 6~17 档（配置默认 8），和电脑版的 12fps 基准差得远 ——
       * 不折算的话 `smoothFrames` / `BAR_WINDOW` / `cutBgWindow` 这三处"按帧的窗口"
       * 在 8fps 下会变成 0.75s / 3s / 0.75s（12fps 是 0.5 / 2 / 0.5）。
       * 详见 `engine/pipeline.js` 的 `timeNormalizeFrames`。
       */
      timeNormalizeFrames: true,
    strategy: tune.tierStyle,
    ...(tune.tierStyle === 'custom' ? { tierMap: tune.tierMap } : {}),
    tierMode: tune.tierMode,
    tierCurve: tune.tierCurve,
    tierThresholds: tune.tierThresholds,
    scoreNormalize: tune.scoreNormalize,
    changeLog: tune.changeLog,
    // 切点门槛跟当前节奏档走。（see-now 特有的一条：回放本地视频时**不传 pace 就用 dense**，
    // 因为 12fps 采样会吃掉快切；手机只有实时摄像头，没有这条分支。）
    cutAbs: p.cutAbs,
    audioStrip,
  };
}

/**
 * 摄像头会话：采集 + 管线 + 最近一帧。
 *
 * 单例。手机上只有一个摄像头，两个会话必然互相抢。
 * `mode` 只影响**画布尺寸**（见 mode.js 的 MODE_SIZES）与逐帧/攒图口径；
 * 打分、切点、拼图仍是 see-now 那一份。
 */
function createSession(cfg, mode, tune, pace) {
  const size = sizesFor(mode, cfg);
  const camBackend = cfg.backend ?? 'http-mjpeg';
  const camOpts = {
    backend: camBackend,
    // 手机上默认直连 IP Camera App 的 MJPEG 流，插件内置 jpeg-js 解码。
    // 不再经过 Termux / python / ffmpeg —— 采集链只剩一个外部 App，
    // 不会因为它俩被系统清后台而断掉。127.0.0.1 直连，无需端口映射。
    url: cfg.url ?? 'http://127.0.0.1:4444/video/mjpeg',
    width: size.width,
    height: size.height,
    fps: Number(cfg.fps) || 8,
    // 存量配置里可能还写着抓拍时代的 220ms（会把 20fps 的流压成 4fps）。
    // http-raw 走本地回环，钳到 80ms 是安全上限，不必让用户改配置文件。
    grabIntervalMs: camBackend === 'http-raw'
      ? Math.min(Number(cfg.grabIntervalMs) || DEFAULT_GRAB_MS, 80)
      : (Number(cfg.grabIntervalMs) || DEFAULT_GRAB_MS),
    cameraId: cfg.camera === 'front' ? 1 : (Number(cfg.cameraId) || 0),
    preset: camBackend === 'http-mjpeg' ? buildPreset(cfg) : null,
    ffmpegPath: cfg.ffmpegPath ?? 'ffmpeg',
  };

  /**
   * 音轨条默认**关**（`cfg.audioStrip === true` 才开）。
   *
   * 桌面版默认开，因为那条链手里真有 RMS 音量（`listen.levelsBetween`）。手机没有
   * 听声音那条链，`audio: null` 会让 `drawAudioStrip` 画一条**恒定静音条** ——
   * 白占 3% 高度（336 宽时 14px），还会让模型以为"这段时间没声音"。
   * see-now 自己在 region 模式（也是拿不到音量）就是这么关的，同一个判断。
   */
  const pipeline = createPipeline(pipelineOptions({
    size, fps: camOpts.fps, mode, tune, pace, audioStrip: cfg.audioStrip === true,
  }));

  const state = {
    size, camOpts, pipeline,
    mode,
    // 记下这条管线是「按哪一档节奏、哪一版设置」建的：ensure() 靠它发现该不该重建
    pace,
    tuneSig: tuningSignature(tune),
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
  let cfg = loadConfig();
  // 插件原来只在启动时读一次配置，改完 see-phone.json 必须重载插件才生效
  // （dsh-go 上真踩过）。改成每次调用比对，变了就重建会话。
  let cfgSnapshot = JSON.stringify(cfg);
  /**
   * 出图设置（`~/.dsh/see-phone.json` 的 `tierStyle` / `tierCurve` / `defaultPace` …）。
   * 键名与桌面版 `~/.dsh/see-now.json` **完全一样**，一份文档管两个插件。
   *
   * 每轮重读 + 签名比对：改完文件下一次工具调用就生效，不用重启插件
   * （`readTuning` 只读一个几百字节的文件，比维护 mtime 缓存更不容易出错）。
   */
  let tune = readTuning({ env: process.env });
  let tuneSnapshot = tuningSignature(tune);
  /**
   * 当前节奏档。和 see-now 一样是**粘性**的：`args.pace` 改一次就一直生效，
   * 直到设置文件里的 `defaultPace` 变了（那次以文件为准）。
   */
  let paceMode = PACES[tune.defaultPace] ? tune.defaultPace : DEFAULT_PACE;
  let session = null;
  // `args.camera` 覆盖配置里的 camera；null = 跟配置走。
  // 摄像头是单例资源，换摄像头只能整个会话重建。
  let activeCamera = null;
  let disposers = [];

  const log = (...a) => { try { ctx.logger?.info?.('[see-phone]', ...a); } catch { /* 无 logger */ } };

  /**
   * 拿会话；没有就建。任何 action（除 help/status 未启动）都要先过这里。
   *
   * 换模式 / 换节奏 / 改设置 = 换一套管线参数。管线在 `createPipeline()` 时就把
   * width/height/strategy/cutAbs 全定死了，所以只能整个重建（摄像头也是单例，
   * 本来就只能有一个）。
   */
  async function ensure(mode, pace) {
    const want = sizesFor(mode, cfg);
    const wantPace = PACES[pace] ? pace : DEFAULT_PACE;
    if (session?.cam) {
      const sameShape = session.size.width === want.width
        && session.size.height === want.height
        && session.mode === mode
        && session.pace === wantPace
        && session.tuneSig === tuneSnapshot;
      if (sameShape) return session;
      log('管线参数变了', `${session.size.width}×${session.size.height}/${session.mode}/${session.pace}`,
        '→', `${want.width}×${want.height}/${mode}/${wantPace}`);
      await session.stop();
      session = null;
    }
    if (!session) {
      const base = activeCamera ? { ...cfg, camera: activeCamera } : cfg;
      session = createSession(base, mode, tune, wantPace);
    }
    try {
      await session.start();
      log('摄像头已启动', session.camOpts.backend ?? 'auto', session.size, mode);
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

  async function doLook(args, mode) {
    const s = await ensure(mode, paceMode);
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
    const note = args.note ? String(args.note).slice(0, 300) : null;
    const backend = s.snapshot().backend;
    const text = mode === 'guide'
      ? buildGuideText({ note, ageSec, quality })
      : buildLookText({ note, ageSec, frames: sheet.frames.length, quality, backend });
    return attach(ctx, sheet, text, {
      ageSec: +ageSec.toFixed(2),
      quality,
      mode,
      luma: s.lastLuma == null ? null : Math.round(s.lastLuma),
      sheets: s.sheets,
      backend: s.snapshot().backend,
    });
  }

  async function doWatch(args, mode) {
    const s = await ensure(mode, paceMode);
    const waitSec = Number.isFinite(Number(args.wait)) && Number(args.wait) > 0
      ? Math.min(Number(args.wait), 120)
      : DEFAULT_WAIT_SEC * 4;
    const note = args.note ? String(args.note).slice(0, 300) : null;
    const backend = s.snapshot().backend;
    const t0 = Date.now();

    // pipeline 在 push 里按"变化量 / 张数 / 等待时间"自己 flush，
    // watch 只负责把攒出来的那张（可能是多帧宫格）领走。
    while (Date.now() - t0 < waitSec * 1000) {
      const sheet = s.pipeline.take();
      if (sheet) {
        s.sheets += 1;
        const ageSec = (Date.now() - s.lastWall) / 1000;
        // 画面质量要**拿到图之后**再判：进来时刚建会话、frames 还是 0，
        // 提前算会把一张 17 帧的宫格标成"没有输出任何帧"（实测踩到）。
        const quality = gradeView({ meanLuma: s.lastLuma, frames: s.frames });
        const text = mode === 'guide'
          ? buildGuideText({ note, ageSec, quality })
          : buildLookText({ note, ageSec, frames: sheet.frames.length, quality, backend });
        return attach(ctx, sheet, text, {
          ok: true, sheets: s.sheets, frames: sheet.frames.length,
          why: sheet.why, backend, mode, quality,
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

  /**
   * `peek`：不阻塞地领走管线里已经攒好的那张图。
   *
   * 和 `look` 的区别：`look` 会等一帧新的、再强制 flush 单帧；`peek` 只是
   * 把"已经攒出来的"取走，一帧都不等。插件叫醒模型时（"有新画面了"）用它，
   * 免得又一次 8 秒阻塞。
   */
  async function doPeek(args, mode) {
    if (!session?.cam) {
      return {
        text: '摄像头还没启动过，没有攒着的图。先 `see_phone({action:"look"})` 或 `watch` 一次。',
        meta: { ok: false, reason: 'not-started' },
      };
    }
    const sheet = session.pipeline.take();
    if (!sheet) {
      return {
        text: '没有攒好的新画面。静止场景不会攒图（这是省 token 的设计）；'
          + '要「此刻」就调 `look`。',
        meta: { ok: true, reason: 'no-sheet' },
      };
    }
    session.sheets += 1;
    const ageSec = (Date.now() - session.lastWall) / 1000;
    const quality = gradeView({ meanLuma: session.lastLuma, frames: session.frames });
    const note = args.note ? String(args.note).slice(0, 300) : null;
    const backend = session.snapshot().backend;
    const text = mode === 'guide'
      ? buildGuideText({ note, ageSec, quality })
      : buildLookText({ note, ageSec, frames: sheet.frames.length, quality, backend });
    return attach(ctx, sheet, text, {
      ageSec: +ageSec.toFixed(2), quality, frames: sheet.frames.length,
      why: sheet.why, backend, mode, sheets: session.sheets,
    });
  }

  async function doStatus(mode) {
    const tuneLine = `设置：档位 ${tune.tierStyle}　节奏 ${paceMode}（切点 ${PACES[paceMode].cutAbs}）`
      + `　曲线 ${tune.tierCurve}　归一 ${tune.scoreNormalize ? '开' : '关'}`
      + `　变化日志 ${tune.changeLog ? '开' : '关'}`
      + `${tune.path ? `\n设置文件：${tune.path}` : ''}`
      + `${tune.warnings?.length ? `\n⚠ ${tune.warnings.join('；')}` : ''}`;
    if (!session) {
      return {
        text: `还没启动过摄像头。配置：${JSON.stringify(cfg)}\n`
          + `模式：${describeMode(mode)}\n${tuneLine}\n`
          + '调一次 `see_phone({action:"look"})` 就会按配置文件启动。',
        meta: {
          ok: true, running: false, mode, pace: paceMode,
          tierStyle: tune.tierStyle, tierCurve: tune.tierCurve,
        },
      };
    }
    const snap = session.snapshot();
    const quality = gradeView({ meanLuma: session.lastLuma, frames: session.frames });
    const t = buildStatusText({ cam: snap, pipeline: session.pipeline, quality });
    const ib = inbox.state();
    // 会话的画布是建会话那一刻的模式定的：`status` 不重建采集，
    // 所以这里报会话的模式，两者不一致时把"下次会换"说清楚。
    const sessMode = session.mode ?? mode;
    const modeLine = `模式：${describeMode(sessMode)}　画布：${session.size.width}×${session.size.height}`
      + (sessMode === mode ? '' : `\n（本次调用解析为 ${mode}，下次取帧会按它重建画布）`);
    return {
      text: `${t}\n${modeLine}\n${tuneLine}\n`
        + `声音：${JSON.stringify(ttsState())}\n收件箱：${JSON.stringify(ib)}`,
      meta: {
        ok: true, running: snap.running, backend: snap.backend, mode,
        sessionMode: sessMode, pace: session.pace ?? paceMode,
        tierStyle: tune.tierStyle, tierCurve: tune.tierCurve,
        tts: ttsState(), inbox: ib,
      },
    };
  }

  /**
   * 两张配置都重读：`see-phone.json` 里既有采集项（backend/url/rotate…），
   * 也有出图设置（tierStyle/defaultPace…）。任一张变了就重建会话。
   *
   * @returns {boolean} 是否重建过（status 用它提示"下次取帧用新参数"）
   */
  async function refreshConfig() {
    const fresh = loadConfig();
    const snap = JSON.stringify(fresh);
    const freshTune = readTuning({ env: process.env });
    const tuneSnap = tuningSignature(freshTune);
    const cfgDirty = snap !== cfgSnapshot;
    const tuneDirty = tuneSnap !== tuneSnapshot;
    if (!cfgDirty && !tuneDirty) return false;
    cfg = fresh;
    cfgSnapshot = snap;
    tune = freshTune;
    tuneSnapshot = tuneSnap;
    if (cfgDirty) activeCamera = null;          // 文件改了，camera 覆盖作废，回到配置值
    // 设置文件改了就以它为准（同 see-now：启动时从 tuning 取默认档）
    if (tuneDirty) paceMode = PACES[tune.defaultPace] ? tune.defaultPace : DEFAULT_PACE;
    if (session) { await session.stop(); session = null; }
    return true;
  }

  async function route(args = {}) {
    const action = String(args.action ?? 'watch').toLowerCase();
    // help/status/stop 也要过这里：改了配置后第一次调用就该生效
    await refreshConfig();

    /**
     * 模式三级回退（见 src/mode.js）：参数 > App 开关文件 > 配置文件 > normal。
     * **每次调用都重算**：用户在 App 上点一下开关，下一次工具调用就该换口吻，
     * 不能等到重启插件。
     */
    const picked = resolveMode({ arg: args.mode, app: readAppMode(), cfg: cfg.mode });
    const mode = picked.mode;

    /**
     * 节奏档（同 see-now）：`args.pace` 传一次就**粘住**，之后每次调用都沿用，
     * 直到再传一个新值或设置文件的 `defaultPace` 变了。
     * 换档 = 换一套管线参数，`ensure()` 发现 `session.pace` 不一致会自己重建。
     */
    const wantPace = String(args.pace ?? '').trim().toLowerCase();
    if (PACES[wantPace]) paceMode = wantPace;

    // 换摄像头：args.camera 和当前不一致就重建（摄像头单例，只能整个换）
    const wantCam = String(args.camera ?? '').toLowerCase();
    if (wantCam === 'front' || wantCam === 'back') {
      const nowCam = activeCamera ?? String(cfg.camera ?? 'back').toLowerCase();
      if (wantCam !== nowCam) {
        activeCamera = wantCam;
        if (session) { await session.stop(); session = null; }
      }
    }

    // `say` 在任何动作之前处理：用户等着回话时，一张图晚两秒无所谓，一句话晚两秒就很难受
    const said = args.say ? String(args.say).trim().slice(0, 400) : null;
    let speakResult = null;
    if (said) speakResult = await speak(said);

    switch (action) {
      case 'help':
        return { text: HELP, meta: { action, mode } };
      case 'reload': {
        cfgSnapshot = '__force__';   // 强制走一次重建
        await refreshConfig();
        return { text: '配置已重载，下次取帧会用新参数重启采集。', meta: { ok: true, mode } };
      }
      case 'status':
        return doStatus(mode);
      case 'stop': {
        stopSpeaking();
        if (session) await session.stop();
        const had = Boolean(session);
        session = null;
        return { text: had ? '摄像头已关，不再耗电。' : '本来就没在跑。', meta: { ok: true, mode } };
      }
      case 'watch':
        return doWatch(args, mode);
      case 'peek':
        return doPeek(args, mode);
      case 'look':
      default: {
        const out = await doLook(args, mode);
        // 说了但没出声，等于没说 —— 必须把原因亮出来，让模型改用文字回复
        if (speakResult && !speakResult.ok && said) {
          const fallback = mode === 'guide'
            ? '把内容直接写进回复给用户，让他用系统读屏听；并提醒他检查「看见」App 的语音是否还活着。'
            : '把内容直接写进回复（用户看得见屏幕）。要念的话检查「看见」App 的语音是否还活着。';
          return {
            ...out,
            text: `${out.text}\n\n⚠ 这句话没说出口（${speakResult.reason ?? 'unknown'}）：${fallback}`,
          };
        }
        return out;
      }
    }
  }

  /**
   * 记住"上一次工具调用发生在哪个会话里"。
   *
   * 收件箱是后台轮询，没有 invocation，只能靠这里顺手抓一个 —— 宿主在
   * `execute(args, exec)` 里给的 `exec.agent.session.id` 就是它。
   * 计策：拿不到也不报错，等下一次调用再补（UI 上会显示 no-session-id）。
   */
  let lastSessionId = null;
  function rememberSession(exec) {
    try {
      const sid = exec?.agent?.session?.id
        ?? exec?.session?.id
        ?? exec?.sessionId
        ?? exec?.agent?.sessionId
        ?? null;
      if (sid) lastSessionId = String(sid);
    } catch { /* 宿主形状变了也不能炸 */ }
  }

  const tool = {
    ...seePhoneToolSpec(),
    async execute(args = {}, exec) {
      rememberSession(exec);
      const out = await route(args);
      return toLosslessJson(out);
    },
  };

  disposers.push(ctx.tools.register(defineTool(tool)));

  // 收件箱：手机「看见」App 里打的字 → steer 进这个会话
  const inbox = startInbox(ctx, { getSessionId: () => lastSessionId });
  disposers.push(() => { try { inbox.stop(); } catch { /* ignore */ } });
  log(`${TOOL_NAME} 已注册`);

  ctx.on?.('dispose', () => {
    try { inbox.stop(); } catch { /* ignore */ }
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
const HELP = `See on Phone · 手机摄像头

## 它在做什么
手机摄像头 → 缩放成 rgb24 → 打分/切点 → 装箱 DP 拼图 → 图片附件交给宿主。
与桌面 \`see_now\` 同一套引擎（拼图/取帧算法随桌面版一起升级），只是采集源换成摄像头。

## 两种模式（mode）
- \`normal\`（**默认**）：用户自己看得见画面。按普通会话回答，宫格按时间线摊开，
  不假设用户看不见、不强制念出来。
- \`guide\`：用户看不见（导盲）。单帧「此刻」、先风险后通路、一句到两句，可以直接念。
  出图走**逐帧模式**（一张图一个画面、格子撑满画布），不做多帧宫格。
旧值 \`general\` 等同 \`normal\`。优先顺序：调用参数 > 手机 App 的开关 > 配置文件 > normal。
「看见」App 顶栏一点就切，写在 /sdcard/Download/DSH-Go/mode.txt ，下一次工具调用即生效。

画布随模式变：normal 420×560、guide 336×448 —— 两者计费都是 197 token（小于 544²
会被地板放大到同一网格），所以正常模式白得 56% 像素。

## 节奏档（pace）与设置
出图频率由 \`pace\` 定，粘性：\`calm\` / \`normal\`（默认）/ \`dense\` / \`game-economic\`。
切点门槛分别是 20 / 12 / 7 / 80，兜底间隔（floorGapK）也跟着换一张。

设置写在 \`~/.dsh/see-phone.json\`，**键名与桌面版 \`~/.dsh/see-now.json\` 完全一样**
（一份文档管两个插件）。只列会影响出图的：
- \`tierStyle\`：\`standard\`（默认，1 档压成 2 档）/ \`native\`（1 档也占满整幅）/ \`full\` / \`compact\` / \`custom\`
- \`tierMap\`：\`tierStyle:"custom"\` 时的逐档映射（8 个数，1~8）
- \`tierMode\`：\`density\`（默认）/ \`weight\`
- \`tierCurve\`：\`docx\`（默认）/ \`perceptual\` / \`custom\`
- \`tierThresholds\`：7 个「帧/秒」阈值，必须严格递增
- \`defaultPace\`：\`calm\` / \`normal\` / \`dense\` / \`game-economic\`
- \`scoreNormalize\`：亮度归一 + 黑边补偿，默认 **开**
- \`changeLog\`：交付时附「这段时间画面怎么动」，默认关
- \`audioStrip\`：手机没有音频通道，默认 **关**（置 true 会画一条恒定静音条，占 3% 高度，不推荐）

改完下一次工具调用就生效（会重开一次摄像头，约 1 秒）；写错了会退回默认并在 \`status\` 里报原因。

## 采集后端（配置文件 ~/.dsh/see-phone.json 的 backend）
- \`http-mjpeg\`（**手机默认**）：插件直连「看见」App 的 MJPEG 流，内置 jpeg-js 解码。
  实测 nova 11 上 8.7fps、单帧解码 25~32ms。要求 App 在跑（它自己是前台服务）。
  配置项 \`url\`（默认 \`http://127.0.0.1:4444/video/mjpeg\`）、\`rotate\`（默认 90，
  画面歪了就改 0/180/270）、\`camera\`（back/front）、\`resolution\`（low/medium/high）。
- \`http-raw\`：Termux 侧 cam_server.py 出 rgb24（旧链路，留作回退）
- \`termux\`：Termux:API 抓拍，~1fps（camera2 每次 open/close 800ms，物理上限）
- \`mjpeg\` / \`rtsp\`：外部 App，需要 url
- \`v4l2\` / \`avfoundation\` / \`dshow\`：桌面调试用
- \`file\` / \`testsrc\`：回归测试用

## 声音
\`say\` 参数会念出来：插件发一条显式广播给「看见」App，由它调 Android 的 TextToSpeech。
桥不在时返回 \`no-sound\` —— 这时把话直接写进回复。
**normal 模式下 App 只显示字幕、不自动朗读**；guide 模式才默认念（省得吓到正常用户）。
\`status\` 里 \`tts.channel\` 会显示当前通道。

## 收件箱（手机端打字 → 直接进会话）
App 里打的字落到 /sdcard/Download/DSH-Go/inbox.txt ，插件每 400ms 轮询一次，
把**最后一条**用 agent.steer() 塞进当前会话（next-step，正在跑的轮次立刻看得见）。
⚠️ 需要先有过一次工具调用（这样插件才知道 sessionId）。没拿到时 status 的
inbox.lastError 会是 no-session-id，先 look 一次即可。

## 成本
一张图 ≈ 197 token，且**会留在会话历史里每轮重发**。连续看 10 分钟（每 3 秒一张）
= 200 张 ≈ 4 万 token 的常驻负担。所以：只在真的要"看"的时候 \`look\`/\`watch\`，别轮询。

## 已知限制
- 抓拍后端是"一次一张"，走路时画面会比人慢半拍；说方位时用"刚才"而不是"现在"
- 全黑/太暗时**不会**出图（gradeView 拦掉了），只会告诉你请用户调整
- 单摄像头：\`look\` 和 \`watch\` 不能真正并发`;
