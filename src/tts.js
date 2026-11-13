/**
 * 手机 TTS —— 走**共享文件 + 桥 App 轮询**。
 *
 * 为什么是这条最笨的路：插件跑在 PRoot 里，那是 Linux 用户态，碰不到 Android 的
 * TextToSpeech / MediaPlayer。逐条试过的死路：
 *
 *   `am start`      → SecurityException: package=com.android.shell does not belong to uid=<dsh_app>
 *                     （ActivityTaskManagerService.assertPackageMatchesCallingUid，Android 12 专门堵这条）
 *   `cmd texttospeech` → "No shell command implementation"（服务在，但没有 shell 命令）
 *   `am broadcast`  → 20:00 还能用（result=0 且真出声），21:40 起全部变成
 *                     "Failure calling service activity: Failed transaction"，连发给
 *                     termux.api 也不行了 —— 这条 IPC 通道在这个 ROM 上不可靠
 *   `termux-tts-speak` → 依赖 Termux 的 socket 服务，华为清后台后脚本挂 15 秒再失败
 *
 * 唯一从没失败过的是**文件**：PRoot 写 `/sdcard` 一直好用。所以：
 *
 *   插件追加一行 `<毫秒时间戳>|<要念的话>`
 *     → `DSH TTS Bridge`（`phone/dsh-tts-bridge`，17KB）常驻前台服务每 250ms 读一次
 *     → 发现更大的时间戳就调 Android TextToSpeech 念出来
 *
 * 用时间戳而不是"文件变了"：桥 App 重启后会记住上次念到哪儿，不会把历史重念一遍。
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { dirname } from 'node:path';
import { whichBin } from './bin.js';

/** 和桥 App 约定的落点（`SayService.SAY_FILE`）。 */
export const SAY_FILE = '/sdcard/Download/DSH-Go/say.txt';
/** 文件最多留多少行，超了从头截掉 —— 这是临时信箱，不是日志。 */
const SAY_KEEP = 20;

/** 兜底的广播地址（Rom 允许时的更省电路径）。 */
const BRIDGE_ACTION = 'com.dsh.tts.SAY';
const BRIDGE_COMP = 'com.dsh.ttsbridge/.SayReceiver';
const AM_CANDIDATES = ['/system/bin/am', '/system/xbin/am'];
const SYSTEM_PATH = '/system/bin:/system/xbin';

let amPath;          // undefined = 还没探测
let binCache;
let speech = null;
let lastSpokenAt = 0;
let lastChannel = null;
let lastError = null;

async function which(cmd) {
  return whichBin(cmd);
}

async function resolveAm() {
  if (amPath !== undefined) return amPath;
  amPath = AM_CANDIDATES.find((p) => {
    try { return existsSync(p); } catch { return false; }
  }) ?? null;
  return amPath;
}

export async function resolveTts(explicit = null) {
  if (binCache !== undefined) return binCache;
  const cand = explicit ?? process.env.DSH_TTS_BIN ?? 'termux-tts-speak';
  binCache = (await which(cand))
    ?? (await which('termux-tts-speak'))
    ?? null;
  return binCache;
}

/** 把文件切成"最后 SAY_KEEP 行"，避免一年后攒出几 MB。 */
function trimSayFile() {
  try {
    const txt = readFileSync(SAY_FILE, 'utf8');
    const lines = txt.split('\n').filter((l) => l.includes('|'));
    if (lines.length <= SAY_KEEP) return;
    writeFileSync(SAY_FILE, `${lines.slice(-SAY_KEEP).join('\n')}\n`);
  } catch { /* 截不动就算了，不影响说话 */ }
}

/** 通道 1（主用）：追加一行到共享文件。 */
function speakViaFile(text) {
  try {
    const dir = dirname(SAY_FILE);
    if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
    const clean = String(text).replace(/[\r\n]+/g, ' ').trim().slice(0, 500);
    if (!clean) return { ok: false, reason: 'empty' };
    appendFileSync(SAY_FILE, `${Date.now()}|${clean}\n`, 'utf8');
    trimSayFile();
    return { ok: true, channel: 'file' };
  } catch (e) {
    lastError = e.message;
    return { ok: false, reason: 'write-failed', detail: e.message };
  }
}

/** 通道 2（备用）：广播给桥 App。Rom 允许时更快，可惜这台机器上不可靠。 */
function speakViaBridge(am, text, rate) {
  return new Promise((resolve) => {
    const args = [
      'broadcast', '--user', '0', '-f', '0x20',
      '-a', BRIDGE_ACTION, '-n', BRIDGE_COMP,
      '--es', 'text', text, '--ef', 'rate', String(rate),
    ];
    let child;
    try {
      child = spawn(am, args, {
        windowsHide: true,
        env: { ...process.env, PATH: `${SYSTEM_PATH}:${process.env.PATH ?? ''}` },
      });
    } catch (e) {
      return resolve({ ok: false, reason: 'spawn-failed', detail: e.message });
    }
    speech = child;
    let out = '';
    const grab = (c) => { out += c; };
    child.stdout?.on('data', grab);
    child.stderr?.on('data', grab);
    child.on('error', (e) => {
      if (speech === child) speech = null;
      resolve({ ok: false, reason: 'am-error', detail: e.message });
    });
    child.on('close', () => {
      if (speech === child) speech = null;
      const ok = /Broadcast completed/.test(out) && !/Exception/.test(out);
      resolve({ ok, reason: ok ? undefined : 'broadcast-rejected', detail: out.slice(-240) });
    });
  });
}

/** 通道 3（最后兜底）：Termux:API，只在 Termux 活着时有用。 */
async function speakViaTermux(text, rate) {
  const bin = await resolveTts();
  if (!bin) return { ok: false, reason: 'no-tts' };
  const child = spawn(bin, ['-r', String(rate), text], { windowsHide: true });
  speech = child;
  child.on('error', () => { speech = null; });
  child.on('close', () => { if (speech === child) speech = null; });
  return { ok: true, channel: 'termux' };
}

/**
 * 朗读一句话。
 *
 * 打断优先：桥 App 收到新的一句会 `QUEUE_FLUSH`，旧的立刻停 ——
 * 导盲最怕"前面有台阶"排在上一句后面晚 3 秒到。
 *
 * @returns {Promise<{ok:boolean, channel?:string, reason?:string, detail?:string}>}
 */
export async function speak(text, { rate = 1.0, flush = true } = {}) {
  const s = String(text ?? '').trim();
  if (!s) return { ok: false, reason: 'empty' };

  if (flush && speech) {
    try { speech.kill('SIGKILL'); } catch { /* 已结束 */ }
    speech = null;
  }

  lastSpokenAt = Date.now();

  // 主路径：写文件。桥 App 会自己念。
  const f = speakViaFile(s);
  if (f.ok) {
    lastChannel = 'file';
    return f;
  }

  // 文件写不了（/sdcard 没挂上、权限变了）→ 试广播
  const am = await resolveAm();
  if (am) {
    const r = await speakViaBridge(am, s.slice(0, 500), rate);
    if (r.ok) { lastChannel = 'bridge'; return { ok: true, channel: 'bridge' }; }
    lastError = r.detail ?? r.reason;
  }

  const t = await speakViaTermux(s.slice(0, 500), rate);
  if (t.ok) { lastChannel = 'termux'; return t; }
  return { ok: false, reason: 'no-channel', detail: lastError };
}

export function ttsState() {
  return {
    available: Boolean(lastChannel),
    channel: lastChannel,
    file: SAY_FILE,
    lastError,
    lastSpokenAt,
  };
}

export function stopSpeaking() {
  if (speech) { try { speech.kill('SIGKILL'); } catch { /* ignore */ } speech = null; }
}
