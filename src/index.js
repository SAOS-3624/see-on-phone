/**
 * See on Phone —— **电脑借手机的眼睛**。
 *
 * ============================ 0.4.0 变了什么 ============================
 *
 * 0.3.x 是"同一套引擎在电脑上再跑一遍"：拉手机原始 MJPEG 流 → 本地 ffmpeg 抽帧 →
 * 跑 4137 行 vendor 引擎挑帧拼图 → 交给模型。**现在全删了。**
 *
 * 手机 App 里已经有同一套引擎（Java 版，逐位对拍过 0 差异），而且图就在手机里：
 * 让它拼完，一次 `/look` 拿回拼好的宫格更省 —— 省的是**你的电脑**（不用装 ffmpeg）
 * 和**带宽**（不用拉原始流）。
 *
 * 所以这个插件现在只剩三件事：**发请求、收图、交给宿主**（见 `src/phone.js`）。
 * 档位、节奏、前后摄像头、帧数 —— 全部归手机 App 管，插件不再有一份自己的配置去打架。
 *
 * 读者画像仍然是两档（见 src/mode.js）：
 *   - `normal`（默认）：用户自己看得见画面，按普通会话说。
 *   - `guide`：用户看不见（导盲），先风险后动作、可以直接念。
 * ============================================================================
 */
import { baseOf, lookSheet, status as phoneStatus, loadPhoneConfig } from './phone.js';
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

const loadConfig = loadPhoneConfig;

export async function apply(ctx) {
  const defineTool = await loadDefineTool();
  let cfg = loadConfig();
  // 配置原来只在启动时读一次，改完 see-phone.json 必须重载插件才生效（dsh-go 上真踩过）。
  // 现在每次调用比对一次 —— 0.4.0 之后配置里只剩「手机地址」一项，比对很便宜。
  let cfgSnapshot = JSON.stringify(cfg);
  let disposers = [];

  const log = (...a) => { try { ctx.logger?.info?.('[see-phone]', ...a); } catch { /* 无 logger */ } };

    /**
     * 把一张图交给宿主。
   *
   * **0.4.0 起不再自己拼 PNG**：手机返回的就是拼好的 JPEG，这里只负责转交。
   * 宿主没有 attachments 服务时返回文字提示（同旧版行为）。
   */
  async function attachImage(ctx2, bytes, mediaType, text, meta) {
    const svc = serviceOf(ctx2, 'attachments');
    if (!svc?.saveImage) {
      return {
        text: `${text}

  （宿主没有 attachments 服务，图交不上去 —— 只能靠上面这段文字。）`,
        meta: { ...meta, ok: false },
      };
    }
    const ref = await svc.saveImage.call(svc, {
      data: bytes,
      mediaType,
      name: `see-phone-${Date.now()}.${mediaType === 'image/png' ? 'png' : 'jpg'}`,
    });
    return { images: [ref], text, meta: { ...meta, ok: true } };
  }

  /** 手机那头的一段人话摘要，几处都要用。 */
  function shotLine(shot, mode, note, ageSec) {
    const parts = [`${shot.frames} 帧`, `${shot.tokens} token`];
    if (shot.canvas) parts.push(`${shot.canvas.w}×${shot.canvas.h}`);
    if (shot.why) parts.push(`口径 ${shot.why}`);
    const head = `手机拍的宫格：${parts.join(' · ')}`;
    const tail = note ? `\n\n（当前关注：${note}）` : '';
    return `${head}${tail}\n\n${mode === 'guide'
      ? '用户在等着你描述画面。先说风险再说怎么走，一句话一件事。'
      : '图按时间先后摊开，左上角是编号。看清楚再回答。'}`;
  }

  async function doLook(args, mode) {
    const base = baseOf(cfg.url);
    const wait = Number(args.wait);
    const sec = Number.isFinite(wait) && wait > 0 ? Math.min(wait, 8) : 1.5;
    const shot = await lookSheet(base, sec);
    const note = args.note ? String(args.note).slice(0, 300) : null;
    return attachImage(ctx, shot.bytes, shot.mediaType, shotLine(shot, mode, note, sec), {
      ok: true, engine: 'phone', frames: shot.frames, tokens: shot.tokens, why: shot.why, mode,
    });
  }

  async function doWatch(args, mode) {
    const base = baseOf(cfg.url);
    const wait = Number(args.wait);
    // watch 是"看一会儿"：给手机更长的采集窗口（默认 8 秒，上限 8）
    const sec = Number.isFinite(wait) && wait > 0 ? Math.min(wait, 8) : 8;
    const shot = await lookSheet(base, sec);
    const note = args.note ? String(args.note).slice(0, 300) : null;
    return attachImage(ctx, shot.bytes, shot.mediaType, shotLine(shot, mode, note, sec), {
      ok: true, engine: 'phone', frames: shot.frames, tokens: shot.tokens, why: shot.why, mode,
    });
  }

  /**
   * `peek`：快速瞄一眼。
   *
   * ⚠️ 语义变了：以前是"领走管线里已经攒好的那张，一帧都不等"；现在手机那边没有队列
   * 可以领，只能现抓一个短窗口（1 秒）。**它仍然是最快的那条路**，只是不再是 0 等待。
   */
  async function doPeek(args, mode) {
    const base = baseOf(cfg.url);
    const shot = await lookSheet(base, 1);
    const note = args.note ? String(args.note).slice(0, 300) : null;
    return attachImage(ctx, shot.bytes, shot.mediaType, shotLine(shot, mode, note, 1), {
      ok: true, engine: 'phone', frames: shot.frames, tokens: shot.tokens, why: shot.why, mode,
    });
  }

  async function doStatus(mode) {
    const base = baseOf(cfg.url);
    try {
      const st = await phoneStatus(base);
      const lines = [
        `手机：${st.app ?? '鱼眼'} ${st.version ?? ''}`.trim(),
        `摄像头：${st.cameraOpen ? '开着' : '关着'}（${st.camera ?? '?'} ${st.width}×${st.height}）`,
        `上下文：${st.convMsgs ?? '?'} 条消息，预算 ${st.ctxTokens ?? '?'} token`,
        `思考：${st.think ?? '?'}　朗读：${st.speak ? '开' : '关'}　工具：${st.tools ? '开' : '关'}`,
        '',
        `这个插件现在**只借手机的眼睛**（engine=phone）：一次 /look 拿回拼好的宫格，`,
        `电脑上不再抽帧、也不需要 ffmpeg。模式：${describeMode(mode)}`,
        `接口：${base}`,
      ];
      return { text: lines.join('\n'), meta: { ok: true, engine: 'phone', mode, ...st } };
    } catch (err) {
      return {
        text: `连不上手机：${err?.message ?? err}\n\n`
          + '检查：手机连着、App 在跑、`adb forward tcp:4444 tcp:4444` 没断。',
        meta: { ok: false, engine: 'phone', mode, error: String(err?.message ?? err) },
      };
    }
  }

  /**
   * 配置文件改了要不要重建。
   *
   * 0.4.0 之后只剩一件事要重载：**手机地址**（其余全在手机那边管）。
   */
  async function refreshConfig() {
    const fresh = loadConfig();
    const snap = JSON.stringify(fresh);
    if (snap === cfgSnapshot) return false;
    cfg = fresh;
    cfgSnapshot = snap;
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
     * `pace` / `camera` / 档位这些**现在归手机管了**：插件不再有自己的采集管线，
     * 传进来也不该假装自己生效 —— 直接告诉模型去哪儿改。
     */
    const steering = [];
    if (args.pace) steering.push('pace');
    if (args.camera) steering.push('camera');
    if (args.tier) steering.push('tier');

    // `say` 在任何动作之前处理：用户等着回话时，一张图晚两秒无所谓，一句话晚两秒就很难受
    const said = args.say ? String(args.say).trim().slice(0, 400) : null;
    let speakResult = null;
    if (said) speakResult = await speak(said);

    switch (action) {
      case 'help':
        return { text: HELP, meta: { action, mode } };
      case 'reload': {
        cfgSnapshot = '__force__';
        await refreshConfig();
        return { text: `配置已重载。手机地址：${baseOf(cfg.url)}`, meta: { ok: true, mode } };
      }
      case 'status':
        return doStatus(mode);
      case 'stop': {
        stopSpeaking();
        // 手机那边的相机是它自己管的：抓完一张、没人再看就 3 秒后自动关。
        // 插件这边没有常驻会话，也就没有"关掉"这个动作。
        return {
          text: '语音停了。手机端的相机不用管 —— 它抓完图就自己关（约 3 秒）。',
          meta: { ok: true, mode },
        };
      }
      case 'watch':
        return doWatch(args, mode);
      case 'peek':
        return doPeek(args, mode);
      case 'look':
      default: {
        if (steering.length) {
          const out0 = await doLook(args, mode);
          return {
            ...out0,
            text: `${out0.text}\n\n⚠ ${steering.join(' / ')} 现在不由插件管 —— `
              + '档位、节奏、前后摄像头都在手机 App 的设置里。',
          };
        }
        const out = await doLook(args, mode);
        // 说了但没出声，等于没说 —— 必须把原因亮出来，让模型改用文字回复
        if (speakResult && !speakResult.ok && said) {
          const fallback = mode === 'guide'
            ? '把内容直接写进回复给用户，让他用系统读屏听；并提醒他检查「鱼眼」App 的语音是否还活着。'
            : '把内容直接写进回复（用户看得见屏幕）。要念的话检查「鱼眼」App 的语音是否还活着。';
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

  // 收件箱：手机「鱼眼」App 里打的字 → steer 进这个会话
  const inbox = startInbox(ctx, { getSessionId: () => lastSessionId });
  disposers.push(() => { try { inbox.stop(); } catch { /* ignore */ } });
  log(`${TOOL_NAME} 已注册`);

  ctx.on?.('dispose', () => {
    try { inbox.stop(); } catch { /* ignore */ }
    try { stopSpeaking(); } catch { /* ignore */ }
    for (const d of disposers) { try { d?.(); } catch { /* ignore */ } }
    disposers = [];
  });
}

/**
 * `action:"help"` —— 契约里塞不下的东西放这里。
 *
 * 判据同 see-now：**删掉这句，模型会不会用错？** 会 → 进契约；不会 → 进这里。
 */
const HELP = `鱼眼 · See on Phone — 电脑借手机的眼睛

## 它在做什么
调手机 App 的 \`/look\` —— 手机自己采集、挑帧、拼宫格（它的引擎和桌面 \`see_now\` 同源），
**一张拼好的图**回来，直接交给宿主。电脑这边不抽帧、不需要 ffmpeg、也不占带宽。

## 动作（action）
- \`watch\`（**默认**）：采集 8 秒，然后回一张拼好的宫格。
- \`look\`：同 \`watch\`，但默认只采集 1.5 秒（用户刚问了一句就用它）。
- \`peek\`：最快的一张，采集 1 秒。**不再是「0 等待」** —— 手机那边没有队列可以领。
- \`status\`：手机的状态（摄像头开没开、上下文多少条、思考/朗读/工具开关）。
- \`stop\`：停语音。**手机相机会自己关**（抓完图约 3 秒），插件这边没有常驻会话。
- \`reload\`：重读 \`~/.dsh/see-phone.json\`（现在里面只剩 \`url\` 有用）。

## 两种模式（mode）
- \`normal\`（**默认**）：用户自己看得见画面，按普通会话说。
- \`guide\`：用户看不见（导盲），先风险后通路、可以直接念。
旧值 \`general\` 等同 \`normal\`。优先顺序：调用参数 > 手机 App 的开关 > 配置文件 > normal。

## 归手机管的事（插件不再有自己的旋钮）
档位（tier）、节奏（pace）、前后摄像头、帧数、出图大小 —— **全部在手机 App 里**。
传 \`pace\` / \`tier\` / \`camera\` 给这个工具不会有任何效果，返回里会提醒一句。

## 配置（~/.dsh/see-phone.json）
- \`url\`：手机接口，默认 \`http://127.0.0.1:4444/video/mjpeg\`（只取 \`//host:port\` 那半截）
- \`mode\`：见上

连不上时的返回里会写清楚查哪三样：手机连着、App 在跑、\`adb forward tcp:4444 tcp:4444\`。

## 声音
\`say\` 参数会让手机念出来（它那边有音色）。桥不在时返回 \`no-sound\`，这时把话直接写进回复。

## 收件箱（手机端打字 → 直接进会话）
App 里打的字落到 /sdcard/Download/DSH-Go/inbox.txt，插件每 400ms 轮询一次，
把**最后一条**用 agent.steer() 塞进当前会话。
⚠️ 需要先有过一次工具调用（这样插件才知道 sessionId）。

## 成本
一张宫格 ≈ 197 token，且**会留在会话历史里每轮重发**。所以只在真要「看」的时候 \`look\`/\`watch\`，别轮询。

## 已知限制
- **必须连着手机**，且「鱼眼」App 在运行（它就是这条链路的服务端）。
- 电脑上**不用装 ffmpeg 了**（0.4.0 起）。语音播放仍要 ffplay/mpv，那是另一回事。
- 档位/节奏在手机上调：插件传 \`pace\`/\`tier\`/\`camera\` 无效（会在返回里提醒）。
`;
