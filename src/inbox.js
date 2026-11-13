/**
 * 收件箱：把"人说的话"从手机端塞回会话。
 *
 * ============================ 为什么是文件 ============================
 *
 * 手机上的「鱼眼」App 和本插件不在同一个进程、也不在同一个 uid：
 *   - App 是原生 Android（`com.dsh.eye`），跑在系统里；
 *   - 插件跑在 dsh-go 的 PRoot 里（uid 是 dsh_app）。
 * 它们之间**唯一从没失败过的通道是 /sdcard 上的文件**（say.txt 已经证明了半年）。
 * 广播、am start、socket 都试过：广播会随机 Failed transaction，am start 被
 * assertPackageMatchesCallingUid 拒，socket 要处理端口/权限/生命周期。
 *
 * 所以方向反过来：**App 写 `/sdcard/Download/DSH-Go/inbox.txt`，插件轮询**。
 * 一行一条：`<毫秒时间戳>|<文本>`，和 say.txt 同一个格式。
 *
 * ============================ 为什么要 steer ============================
 *
 * 模型在阻塞等待时收不到任何指令 —— 它在等"任务完成时的 return zero"。
 * 把人的输入挂上同一个信号（`agent.steer()` = next-step），就能在它等着的时候
 * 把话塞进去，而且正在跑的轮次立刻看得见。这是桌面版 watch 那条回环的核心，
 * 这里原样搬过来。
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

/** 和「鱼眼」App 约定的落点（`EyeService.INBOX_FILE`）。 */
export const INBOX_FILE = '/sdcard/Download/DSH-Go/inbox.txt';
/** 顺手把诊断写进 App 的那份日志里，PC 侧一条命令就能看全链路。 */
const LOG_FILE = '/sdcard/Download/DSH-Go/eye.log';
/** 轮询间隔。400ms 对"说完就等回答"已经完全够快，又不至于烧 CPU。 */
const POLL_MS = 400;
/** 这是信箱不是日志：超过这个大小直接不读。 */
const MAX_BYTES = 256 * 1024;

function logLine(tag, msg) {
  try {
    const line = `${new Date().toISOString().slice(5, 23).replace('T', ' ')} ${tag} ${msg}\n`;
    writeFileSync(LOG_FILE, line, { flag: 'a' });
  } catch { /* 日志写不进去不影响主链路 */ }
}

/**
 * cordis 的 ctx 是 Proxy：服务没在 `inject` 里声明时 `get` 陷阱**直接抛**，
 * `?.` 挡不住异常。所以一律 `ctx.get()` + try（和 src/index.js 里的 serviceOf 同源）。
 */
function serviceOf(ctx, name) {
  if (!ctx) return null;
  try {
    if (typeof ctx.get === 'function') {
      const v = ctx.get(name);
      if (v) return v;
    }
  } catch { /* 没声明 inject */ }
  try { return ctx[name] ?? null; } catch { return null; }
}

/** DSH 的 followup/steer 要的都是 user 角色的消息。 */
function userMessage(text) {
  return {
    id: randomUUID(),
    role: 'user',
    content: [{ type: 'text', text: String(text ?? '') }],
    source: { kind: 'see-phone-inbox' },
  };
}

/**
 * 把一条消息投给会话。
 *
 * `steer` 优先（下一个 step 就交给模型，正在跑的轮次也看得见）；
 * 抛了就退回 `followup`（排队，至少不丢话）。
 */
async function deliver(ctx, sessionId, text) {
  const sc = serviceOf(ctx, 'sessionController');
  if (!sc?.resolveAgent) return { ok: false, reason: 'no-session-controller' };
  if (!sessionId) return { ok: false, reason: 'no-session-id' };

  const resolved = await sc.resolveAgent(sessionId);
  if (!resolved || resolved.error || !resolved.agent) {
    const reason = resolved?.error?.message ?? resolved?.error ?? 'resolve-failed';
    return { ok: false, reason: String(reason).slice(0, 160) };
  }
  const agent = resolved.agent;
  try {
    if (typeof agent.steer === 'function') {
      agent.steer(userMessage(text));
    } else {
      agent.followup(userMessage(text));
    }
  } catch (e) {
    try { agent.followup(userMessage(text)); } catch (e2) { return { ok: false, reason: String(e2).slice(0, 160) }; }
  }
  try {
    const sessions = serviceOf(ctx, 'sessions');
    if (sessions?.flush) await sessions.flush(agent.session);
  } catch { /* flush 失败不影响投递 */ }
  return { ok: true };
}

/** 上次读到哪一条：存私有目录，插件重启不会把历史重投一遍。 */
function seenPath() {
  return join(homedir(), '.dsh', 'phone-inbox-seen');
}

function readSeen() {
  try {
    const s = readFileSync(seenPath(), 'utf8').trim();
    const n = Number(s);
    return Number.isFinite(n) ? n : 0;
  } catch {
    return 0;   // 0 = 还没读过，第一次会把现有内容全部跳过
  }
}

function writeSeen(v) {
  try { writeFileSync(seenPath(), String(v)); } catch { /* 写不进去就每次多投一遍，能忍 */ }
}

/**
 * 解析整个文件，返回 `{ latest, maxSeen }`。
 *
 * 只投**最后一条**：用户连打三句时，前两句往往已经被后一句覆盖了语义
 * （"往前走" → "不对，往左"）。模型要的是最新意图，投三条反而让它答错。
 */
export function parseInbox(raw, after) {
  let latest = null;
  let maxSeen = after;
  for (const line of String(raw).split('\n')) {
    const bar = line.indexOf('|');
    if (bar <= 0) continue;
    const ts = Number(line.slice(0, bar).trim());
    if (!Number.isFinite(ts) || ts <= maxSeen) continue;
    maxSeen = ts;
    latest = line.slice(bar + 1);
  }
  return { latest, maxSeen };
}

/**
 * 起轮询。
 *
 * @param {object} ctx 插件上下文
 * @param {{getSessionId: () => string|null, enabled?: () => boolean}} o
 *   `getSessionId` 由 index.js 提供 —— 它从每次工具调用的 `exec.agent.session.id` 抓。
 * @returns {{stop: () => void, state: () => object}}
 */
export function startInbox(ctx, { getSessionId } = {}) {
  let lastSeen = readSeen();
  let hinted = false;
  let timer = null;
  let stopped = false;
  const stats = { read: 0, delivered: 0, failed: 0, lastError: null, lastText: null };

  // 第一次运行：把文件里已有的内容当作"已读"，别一装上就把历史投一遍
  if (lastSeen === 0) {
    try {
      if (existsSync(INBOX_FILE)) {
        const buf = readFileSync(INBOX_FILE, 'utf8');
        const { maxSeen } = parseInbox(buf, 0);
        if (maxSeen > 0) { lastSeen = maxSeen; writeSeen(lastSeen); }
      }
    } catch { /* 读不到就算了 */ }
  }

  /**
   * 拿不到会话 id 时，往 say.txt 放一句 —— 那会立刻出现在手机悬浮窗里并被念出来。
   * 只在第一次提醒，别变成刷屏。
   */
  function hintOnce(text) {
    if (hinted) return;
    hinted = true;
    try {
      writeFileSync('/sdcard/Download/DSH-Go/say.txt', `${Date.now()}|${text}\n`, { flag: 'a' });
    } catch { /* 写不进去就只留日志 */ }
  }

  async function tick() {
    if (stopped) return;
    try {
      if (!existsSync(INBOX_FILE)) return;
      const raw = readFileSync(INBOX_FILE, 'utf8');
      if (raw.length > MAX_BYTES) return;
      const { latest, maxSeen } = parseInbox(raw, lastSeen);
      if (!latest) return;
      lastSeen = maxSeen;
      writeSeen(maxSeen);
      stats.read += 1;

      const text = latest.trim();
      if (!text) return;
      const sid = getSessionId?.() ?? null;
      if (!sid) {
        stats.failed += 1;
        stats.lastError = 'no-session-id';
        logLine('inbox', `skip "${text.slice(0, 40)}"（还没有会话 id：先让它 look 一次）`);
        hintOnce('先让它在聊天里看一眼，之后你打的字就能直接进来了。');
        return;
      }
      const r = await deliver(ctx, sid, text);
      if (r.ok) {
        stats.delivered += 1;
        stats.lastText = text.slice(0, 80);
        stats.lastError = null;
        logLine('inbox', `steer ok: ${text.slice(0, 60)}`);
      } else {
        stats.failed += 1;
        stats.lastError = r.reason;
        logLine('inbox', `steer failed: ${r.reason}`);
      }
    } catch (e) {
      stats.failed += 1;
      stats.lastError = String(e?.message ?? e).slice(0, 160);
    }
  }

  timer = setInterval(tick, POLL_MS);
  if (timer.unref) timer.unref();

  return {
    stop() {
      stopped = true;
      if (timer) { clearInterval(timer); timer = null; }
    },
    state() { return { ...stats, lastSeen, file: INBOX_FILE }; },
    /** 测试用：手动触发一次。 */
    tick,
  };
}
