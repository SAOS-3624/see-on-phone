/**
 * dsh-cache.mjs —— 往 DSH 的**会话列表缓存**里写一条。
 *
 * ## 为什么需要这个文件
 *
 * 我以为会话列表是"扫 `~/.dsh/sessions/` 目录"出来的，于是只写了
 * `sessions/<项目>/session-<uuid>/session.v4.jsonl.zstd`。
 *
 * 结果：**文件写对了、格式也对，DSH 里一条都不显示。**
 *
 * 真相是列表读的是另一份东西：
 *
 * ```
 * ~/.dsh/storages/session_projcache/sessions/session-<uuid>.json
 * ```
 *
 * 一个会话要"出现在列表里"，必须**两处都有**：
 *   - `sessions/.../session.v4.jsonl.zstd`   ← 内容（列表点开才读）
 *   - `storages/session_projcache/sessions/session-<uuid>.json`  ← 列表项（标题/时间/统计）
 *
 * 而且这份缓存有 **29 个字段**（带各自的 `ver`），少了会被当成脏数据跳过。
 * 这里的默认值都是"中性值"，不能带别人的统计 —— 否则列表里会显示错误的 token 数字。
 */
import { writeFileSync, mkdirSync, existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { dshHome } from './paths.mjs';
import os from 'node:os';

/** 缓存目录（DSH 的本地存储）。 */
export function cacheDir() {
  return path.join(dshHome(), 'storages', 'session_projcache', 'sessions');
}

/**
 * 默认的 29 个字段。`ver` 必须和 DSH 当前版本一致（从真实缓存里抄的），
 * 值一律取"没有发生过任何事"的状态。
 */
function defaultRows() {
  return {
    title: { ver: 1, seq: 0, val: null },
    titleInput: { ver: 3, seq: 0, val: null },
    llmRetry: { ver: 1, seq: 0, val: {} },
    sandboxMode: { ver: 1, seq: 0, val: 'workspace-write' },
    goal: { ver: 6, seq: 0, val: { current: null, seenGoalIds: [], failure: null } },
    tokenUsage: { ver: 2, seq: 0, val: null },
    contextPressure: { ver: 5, seq: 0, val: null },
    contextBreakdown: { ver: 6, seq: 0, val: null },
    turnBoundary: {
      ver: 2, seq: 0,
      val: { openTurnStartSeq: null, lastStepStartSeq: null, lastStepBoundary: null },
    },
    inbox: { ver: 1, seq: 0, val: { 'next-turn': [], 'next-step': [] } },
    sessionStats: { ver: 1, seq: 0, val: null },
    turnOutline: { ver: 2, seq: 0, val: { turns: [] } },
    agentPreset: { ver: 1, seq: 0, val: 'standard' },
    userQuestions: {
      ver: 2, seq: 0,
      val: { inheritedEventCount: 0, timed: false, questions: { active: [], settled: [] } },
    },
    permissions: {
      ver: 2, seq: 0,
      val: { preset: 'workspace-write', sandbox: 'workspace-write', approval: 'ask', seeded: true },
    },
    workingDirectory: { ver: 1, seq: 0, val: null },
    subagentCatalog: { ver: 6, seq: 0, val: { inheritedEventCount: 0 } },
    subagentTiming: { ver: 3, seq: 0, val: { descriptorSeen: false, settledMs: 0 } },
    subagent: { ver: 2, seq: 0, val: {} },
    costUsage: { ver: 12, seq: 0, val: null },
    timeContext: { ver: 2, seq: 0, val: null },
    todos: { ver: 2, seq: 0, val: null },
    plan: { ver: 3, seq: 0, val: { active: false, wanted: null, running: null, activeAtLastHeader: false } },
    subagentModelSelectionPolicy: { ver: 1, seq: 0, val: null },
    'dsh-memoir/activity': { ver: 3, seq: 0, val: null },
    harnessDevLegacyPrompt: { ver: 1, seq: 0, val: null },
    modelSelection: {
      ver: 2, seq: 0,
      val: {
        lastUsed: { provider: 'deepseek-official', model: 'deepseek-flash', reasoningEffort: 'low' },
        pending: null,
      },
    },
    sessionListMetadata: { ver: 1, seq: 0, val: { blank: false, lastPromptAt: 0 } },
    imageLimits: { ver: 1, seq: 0, val: null },
  };
}

/**
 * 从现成的真实缓存里抄一份"字段骨架"（字段名 + ver）。
 *
 * 为什么要抄：`projcache` 的每个字段都带版本号（`ver`），DSH 升级会改版本。
 * 手写死一份 29 字段的话，版本一对不上，后端就把这条记录当脏数据跳过 ——
 * 表现就是"文件都写好了、id 也发给前端了，列表里就是不显示"。
 * 抄骨架 = 版本永远跟当前 DSH 一致，加字段也不会漏。
 *
 * @returns {object|null} rows 骨架；没有可抄的返回 null
 */
let templateCache;

function templateRows() {
  // 一次导入要写 N 条，不缓存就得把 150+ 个 JSON 读 N 遍。进程内缓存足够
  // （命令跑完就没了，DSH 升级后重跑一次自然换新的）。
  if (templateCache !== undefined) return templateCache;

  const dir = cacheDir();
  if (!existsSync(dir)) { templateCache = null; return null; }
  let best = null;
  for (const f of readdirSync(dir)) {
    if (!f.endsWith('.json')) continue;
    try {
      const j = JSON.parse(readFileSync(path.join(dir, f), 'utf8'));
      const rows = j?.record?.rows;
      if (!rows || typeof rows !== 'object') continue;
      const t = statSync(path.join(dir, f)).mtimeMs;
      const sameCwd = String(j.record?.identity?.cwd || '').toLowerCase().includes('3-life');
      // 优先同项目、其次最新；同项目 +1 亿的权重足够压过时间差
      const score = t + (sameCwd ? 1e11 : 0);
      if (!best || score > best.score) best = { score, rows };
    } catch { /* 坏文件跳过 */ }
  }
  templateCache = best?.rows ?? null;
  return templateCache;
}

/**
 * 用骨架的字段集 + 版本号，配默认的中性值。
 * 骨架里有、默认值也有的字段 → 用默认值（不把别人的统计带进来）；
 * 骨架里有、默认值没有的 → 保留骨架的结构，但把 seq 归零。
 */
function rowsFromTemplate(tpl) {
  const def = defaultRows();
  const out = {};
  for (const [k, v] of Object.entries(tpl)) {
    out[k] = {
      ver: v?.ver ?? 1,
      seq: 0,
      val: def[k] !== undefined ? def[k].val : (v?.val ?? null),
    };
  }
  return out;
}

/**
 * 写一条会话列表缓存。
 *
 * @param {object} o
 * @param {string} o.dshId       形如 `session-<uuid>`（**必须带前缀**，缓存文件名就用它）
 * @param {string} o.cwd         项目目录（列表按它分组）
 * @param {string} o.title
 * @param {string} o.firstText   第一条用户消息（列表 hover 时显示）
 * @param {number} o.firstSeq    第一条用户消息的 seq
 * @param {number} o.turns       轮数
 * @param {number} o.lastAt      最后一条消息的时间戳
 * @param {number} o.maxSeq      会话里最大的 seq
 * @param {number} o.createdAt
 * @param {Array<{turn:number,seq:number,prompt:string,response:string}>} o.outline
 */
export function writeSessionCache(o) {
  const tpl = templateRows();
  const rows = tpl ? rowsFromTemplate(tpl) : defaultRows();
  // 骨架里可能没有这个字段（版本差异）—— 没有就跳过，别写进去
  const touch = (k, val) => { if (rows[k]) { rows[k].seq = o.maxSeq; rows[k].val = val; } };

  touch('title', o.title || (o.firstText || '').slice(0, 24) || '（手机导入）');
  touch('titleInput', {
    first: { seq: o.firstSeq || 1, text: o.firstText || '' },
    count: o.turns,
    lastSeq: o.maxSeq,
  });
  touch('sessionListMetadata', { blank: false, lastPromptAt: o.lastAt || Date.now() });
  touch('sessionStats', {
    turns: o.turns, steps: o.turns, llmMs: 0, toolMs: 0, ttftMs: 0, ttftSteps: 0,
    decodeMs: 0, decodeTokens: 0, lastTurn: o.turns, openStep: null, pendingCalls: {},
  });
  touch('turnOutline', { turns: o.outline || [] });
  touch('agentPreset', 'standard');
  touch('sandboxMode', 'workspace-write');
  touch('workingDirectory', o.cwd);

  const dir = cacheDir();
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${o.dshId}.json`);
  writeFileSync(file, JSON.stringify({
    version: 7,
    record: {
      identity: {
        formatVersion: 4,
        createdAt: o.createdAt || Date.now(),
        cwd: o.cwd,
        isSeeded: false,
        inheritedEventCount: 0,
      },
      rows,
    },
  }, null, 2));
  return file;
}

/**
 * 把会话**登记到项目里** —— 这一步不做，前面写的东西 DSH 一个都不看。
 *
 * `~/.dsh/storages/workspace.json` 的 `tables.workspaces[项目].sessionIds`
 * 才是会话列表的**真实来源**（它决定"这个项目下有哪些会话"）。
 * 只写 session 文件、只写 projcache 缓存，列表里都不会出现 —— 我在这上面绕了两轮。
 */
export function registerSession(dshId, cwd) {
  const P = path.join(dshHome(), 'storages', 'workspace.json');
  if (!existsSync(P)) return { ok: false, why: 'DSH 还没有任何项目登记（workspace.json 不存在）—— 先在 DSH 里打开一次这个目录' };
  let j;
  try { j = JSON.parse(readFileSync(P, 'utf8')); } catch (e) { return { ok: false, why: 'workspace.json 解析失败' }; }
  const ws = j.tables?.workspaces || {};
  const hit = Object.entries(ws).find(([, w]) => w.path === cwd);
  if (!hit) return { ok: false, why: `「${cwd}」还不是 DSH 里的项目 —— 先在 DSH 里打开一次这个目录，再重跑导入（文件已经写好了，只是列表里挂不上）` };
  const w = hit[1];
  if (!Array.isArray(w.sessionIds)) w.sessionIds = [];
  if (w.sessionIds.includes(dshId)) return { ok: true, already: true };
  w.sessionIds.unshift(dshId);       // 放最前面：刚导入的排最上
  writeFileSync(P, JSON.stringify(j, null, 2));
  return { ok: true, added: true, total: w.sessionIds.length };
}

/** 缓存有没有这条（用来判断"是不是已经写过了"）。 */
export function hasSessionCache(dshId) {
  return existsSync(path.join(cacheDir(), `${dshId}.json`));
}

/** 登记有没有这条（`workspace.json` 的 `sessionIds` 里在不在）。 */
export function isSessionRegistered(dshId, cwd) {
  const P = path.join(dshHome(), 'storages', 'workspace.json');
  try {
    const j = JSON.parse(readFileSync(P, 'utf8'));
    const hit = Object.values(j.tables?.workspaces || {}).find((w) => w.path === cwd);
    return !!(hit && Array.isArray(hit.sessionIds) && hit.sessionIds.includes(dshId));
  } catch {
    return false;
  }
}
