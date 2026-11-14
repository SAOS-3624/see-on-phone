/**
 * to-dsh.mjs —— 手机包 → **DSH**（`~/.dsh`）会话。
 *
 * 一条会话要出现在 DSH 的列表里，**四样缺一不可**：
 *
 * | # | 东西 | 位置 | 缺了会怎样 |
 * |---|---|---|---|
 * | ① | 正文 | `sessions/--D-<项目>--/session-<uuid>/session.v4.jsonl.zstd` | 列表有、点开空白 |
 * | ② | 列表缓存 | `storages/session_projcache/sessions/session-<uuid>.json` | 拿不到标题，前端直接跳过 |
 * | ③ | 项目登记 | `storages/workspace.json` → `workspaces[项目].sessionIds` | 不属于任何工作区 |
 * | ④ | 置顶（可选） | 同文件 `global.pinnedSessionIds` | 只是排不到最上面 |
 *
 * ① 的格式最坑，见 `encodeFrames()`。
 *
 * 一般不直接调，走 `cli.mjs` 的 `import` 子命令（它会把 `pull` 和 `import` 串起来）。
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { zstdCompressSync, zstdDecompressSync, constants as zlibConstants } from 'node:zlib';
import path from 'node:path';
import crypto from 'node:crypto';
import { listPacks, titleOf } from './pack.mjs';
import { writeSessionCache, registerSession, hasSessionCache, isSessionRegistered } from './cache.mjs';
import { validateSessionFile } from './doctor.mjs';
import { dataDir, dshHome } from './paths.mjs';

const ZSTD_MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

/** `~/.dsh/sessions`（或 `DSH_HOME` 指向的位置）。 */
const sessionsRoot = () => path.join(dshHome(), 'sessions');

/**
 * 会话初始化时固定写的那几条事件。
 * `encodeFrames()` 要靠它决定"第二帧放几条"，所以**必须和 `buildSession()` 里的写入顺序一致**。
 */
const SEED_EVENTS = 4; // permission/preset · sandbox/mode · approval/policy · session/end-seed

/* ------------------------------------------------------------ 生成正文 */

/**
 * 把手机消息编成 DSH 的会话记录（返回数组，**不返回字符串** ——
 * 调用方要直接拿去压帧，中间转一趟字符串纯浪费）。
 *
 * 记录顺序照抄真实会话：
 *   session 头 → 4 条 seed 事件 → 每轮 [turn/start, user/message, step/start,
 *   assistant/message, step/end, turn/end] → session/title
 *
 * @returns {{id:string, recs:Array, meta:{title:string, firstSeq:number, maxSeq:number, turns:number, outline:Array}}}
 */
function buildSession({ id, meta, msgs, summary }, cwd) {
  const createdAt = Number(meta.createdAt) || Number(msgs[0]?.at) || Date.now();
  const recs = [];
  let seq = 0;
  const push = (type, time, data, surfaceOp) => {
    const rec = { type, seq: seq++, time, data };
    if (surfaceOp) rec.surfaceOp = surfaceOp;
    recs.push(rec);
    return rec;
  };

  recs.push({
    type: 'session', version: 4, id, createdAt, cwd,
    isSeeded: false, delegationDepth: 0, agentPreset: 'standard',
  });

  // seed 事件：权限基线 + 初始化结束标记（少一条 DSH 就当会话还在初始化）
  push('permission/preset', createdAt, { preset: 'workspace-write' });
  push('sandbox/mode', createdAt, { mode: 'workspace-write' });
  push('approval/policy', createdAt, { policy: 'ask' });
  push('session/end-seed', createdAt, {});

  // 摘要拼进第一条 user 消息 —— 它是压缩过的旧上下文，丢了就等于失忆
  let summaryPending = (summary || '').trim();
  let firstUserSeq = null;
  let turn = 0;
  const outline = [];
  let openTurn = null;

  for (const m of msgs) {
    if (m.role === 'assistant') {
      if (!turn) continue; // 没有对应提问的答复，丢掉
      /**
       * ⚠️ `message.source.kind` 和 `message.id` **不能省**。
       *
       * DSH 0.1.7+ 的 V4 准入要求每条消息带"producer-owned source kind"，
       * 缺了整条会话都读不出来（报 `format v4 message requires a producer-owned
       * source kind`，界面上就是"历史加载失败"）。同理 user/message 要 `data.source`。
       *
       * ⚠️ `stream: []` **更不许省**（DSH 0.2.2-rc.1 起）。
       *
       * 新版在 seed/load 边界新增 `assertAssistantSettlementShape()`：assistant/message
       * 与 assistant/attempt 的 `data` 必须同时具备 `turn` / `step` / `stream` 三件套，
       * 且 `stream` 必须是**数组**，否则整条会话直接拒绝加载 ——
       * 报 `seed assistant/message at index N has invalid settlement fields`。
       * 这个报错看不出跟导入有关，2026-11-14 就为它排查过一轮。
       *
       * 空数组是**官方认可值**，不是糊弄：v0→v4 迁移里 `messageEvent()` 给没有
       * `assistant/chunk` 的 attempt 填的就是 `stream: []`；而且持久化层的
       * `assertCurrentAssistantStreams()` 对 `timed.length === 0` 直接 `continue`，
       * 会跳过"content / usage / replayState 与内嵌流是否一致"的比对。
       * 代价只是历史消息没有打字机动画，正文从 `message.content` 渲染，不受影响。
       */
      push('assistant/message', m.at, {
        turn, step: 1,
        stream: [],
        message: {
          role: 'assistant',
          content: [{ type: 'text', text: m.text }],
          // 必须是**完整的 model source**：只有 kind 会被拒（"message must have model source"）
          source: { kind: 'model', provider: 'deepseek-official', model: 'deepseek-flash' },
          id: crypto.randomUUID(),
        },
      }, 'append');
      push('step/end', m.at, { turn, step: 1 });
      push('turn/end', m.at, { turn, reason: { kind: 'completed' } });
      if (openTurn && !openTurn.response) {
        openTurn.response = m.text.slice(0, 200);
        openTurn = null;
      }
      continue;
    }

    // user
    turn += 1;
    push('turn/start', m.at, { turn });
    /**
     * ⚠️ 顺序是有讲究的：`turn/start → step/start → system/message → user/message`。
     *
     * DSH 的 v3 兼容路径（`dsh-plugin-manager/lib/types/operations.js`）对
     * `system/message` 有两条硬约束：**必须在打开的 step 里**、**必须是整个会话的第一个
     * surface 节点**。以前这里把 `step/start` 写在 `user/message` 后面，两条同时满足不了 ——
     * 结果是：导入的会话能打开，但用户在里头再聊一句，DSH 补一条 system/message
     * 就整条判损坏（`system/message requires a protected first surface head`）。
     *
     * 只写第 1 轮：后面的轮次 DSH 自己会补，我们不抢。
     */
    push('step/start', m.at, { turn, step: 1 });
    if (turn === 1) {
      push('system/message', m.at, {
        turn,
        step: 1,
        message: {
          role: 'system',
          content: [{ type: 'text', text: 'You are an AI agent powered by DeepSeek Harness.' }],
          source: { kind: 'system-prompt' },
          id: crypto.randomUUID(),
        },
      }, 'append');
    }
    let text = m.text;
    if (summaryPending) {
      text = `（以下这段是之前对话的摘要，原图已省略）\n${summaryPending}\n\n——\n\n${text}`;
      summaryPending = '';
    }
    if (firstUserSeq === null) firstUserSeq = seq;
    push('user/message', m.at, {
      content: [{ type: 'text', text }],
      source: { kind: 'user', clientTimeZone: 'Asia/Shanghai' },
      role: 'user',
      id: crypto.randomUUID(),
    }, 'append');

    // 大纲按"提问 → 紧随其后的第一条答复"配对，不假设严格交替（连续两条提问很常见）
    if (openTurn) outline.push(openTurn);
    openTurn = { turn, seq: 0, prompt: m.text.slice(0, 200), response: '' };
  }
  if (openTurn) outline.push(openTurn);

  // 只有提问、没有回答时才补 turn/end，否则会多出一个没有 turn/start 的孤儿 turn
  const last = recs[recs.length - 1];
  if (turn && last && last.type !== 'turn/end') {
    push('turn/end', Date.now(), { turn, reason: { kind: 'completed' } });
  }

  const title = (meta.title && String(meta.title).trim()) || titleOf(msgs);
  push('session/title', Date.now(), {
    title,
    messageSeqs: firstUserSeq === null ? [] : [firstUserSeq],
    source: { kind: 'fallback' },
  });

  assertSeedShape(recs);

  return {
    id,
    recs,
    meta: { title, firstSeq: firstUserSeq ?? 1, maxSeq: seq - 1, turns: turn, outline },
  };
}

/**
 * 写盘前的结构自检 —— 复刻 DSH 的 seed/load 准入，别等用户点开会话才发现。
 *
 * 起因：DSH 0.2.2-rc.1 加了 `assertAssistantSettlementShape()`，导入的会话缺 `stream`
 * 就整条读不出来，界面上只有一行"历史加载失败"，跟导入这件事看不出半点关系。
 * 这里把"我们能控制、DSH 会拒绝"的硬约束提前查一遍：
 *
 *   · seq 从 0 连续（DSH 全系统依赖 `seq = log.length`）
 *   · assistant/* 三件套 turn / step / stream（stream 必须是数组）
 *   · 每条消息的 id / role / source.kind / content，assistant 还要完整的 provider/model
 *
 * @param {Array<object>} recs 生成好的记录（含 session 头）
 * @throws 有任何一条不满足就抛，宁可不写盘
 */
function assertSeedShape(recs) {
  const problems = [];
  const events = recs.filter((r) => r.type !== 'session');
  events.forEach((e, i) => {
    if (e.seq !== i) problems.push(`seq 不连续：第 ${i} 条事件的 seq 是 ${e.seq}`);
  });
  for (const e of events) {
    const d = e.data || {};
    if (e.type === 'assistant/message' || e.type === 'assistant/attempt') {
      if (!Number.isSafeInteger(d.turn) || d.turn < 0) problems.push(`${e.type}#${e.seq} 缺 turn`);
      if (!Number.isSafeInteger(d.step) || d.step < 0) problems.push(`${e.type}#${e.seq} 缺 step`);
      if (!Array.isArray(d.stream)) problems.push(`${e.type}#${e.seq} 缺 stream 数组`);
    }
    if (e.type === 'assistant/message' || e.type === 'user/message') {
      const msg = e.type === 'user/message' ? d : d.message;
      if (typeof msg?.id !== 'string' || !msg.id) problems.push(`${e.type}#${e.seq} 缺 message.id`);
      if (!Array.isArray(msg?.content)) problems.push(`${e.type}#${e.seq} 缺 message.content 数组`);
      if (typeof msg?.source?.kind !== 'string' || !msg.source.kind) problems.push(`${e.type}#${e.seq} 缺 source.kind`);
      if (e.type === 'assistant/message') {
        const s = msg?.source || {};
        if (s.kind !== 'model' || !s.provider || !s.model) problems.push(`${e.type}#${e.seq} 的 source 不是完整 model source`);
      }
    }
  }
  // system/message 的 head 规则（DSH 的 v3 兼容路径会查，Session.fromRestore 不查）
  let step;
  let head;
  let hasSurface = false;
  const SURFACE = new Set(['system/message', 'user/message', 'assistant/message', 'tool/result']);
  for (const e of events) {
    if (e.type === 'step/start') step = { turn: e.data?.turn, step: e.data?.step };
    else if (e.type === 'step/end' || e.type === 'turn/end') step = undefined;
    if (e.type === 'system/message') {
      if (!step || step.turn !== e.data?.turn || step.step !== e.data?.step) {
        problems.push(`system/message#${e.seq} 不在打开的 step 里`);
      } else if (hasSurface && head === undefined) {
        problems.push(`system/message#${e.seq} 不是第一个 surface（缺 protected head）`);
      } else if (e.surfaceOp === 'append' && !hasSurface) {
        head = e.seq;
      }
    }
    if (SURFACE.has(e.type)) hasSurface = true;
  }
  if (problems.length) {
    throw new Error(`生成的会话过不了 DSH 准入，拒绝写盘：\n  - ${problems.join('\n  - ')}`);
  }
}

/**
 * 把记录压成 **DSH 期望的多帧 zstd**。
 *
 * ⚠️ 这是整条线上最隐蔽的坑：
 *
 * 引擎读会话头用 `readFirstZstdLine()` —— **只解第一帧、只取第一行**。
 * 整条会话压成一帧时，它拿不到有效的头，`listArtifacts()` 会在
 * `readGenerationHeader()` 里 `continue` 掉 —— 文件存在、id 也发给了前端，
 * **列表里就是不出现**。这个坑查了整整一轮。
 *
 * 引擎自己的形状：第一帧只有 session 头，第二帧放那 4 条 seed 事件，之后每 20 条一帧。
 * 另外它开 zstd checksum，一并照抄。
 */
function encodeFrames(recs) {
  const opts = { params: { [zlibConstants.ZSTD_c_checksumFlag]: 1 } };
  const frame = (rows) => zstdCompressSync(
    Buffer.from(rows.map((r) => JSON.stringify(r)).join('\n') + '\n', 'utf8'),
    opts,
  );

  const head = recs.filter((r) => r.type === 'session');
  const body = recs.filter((r) => r.type !== 'session');
  const frames = [frame(head), frame(body.slice(0, SEED_EVENTS))];
  for (let i = SEED_EVENTS; i < body.length; i += 20) frames.push(frame(body.slice(i, i + 20)));
  return Buffer.concat(frames);
}

/* ------------------------------------------------------------ 定位项目 */

/**
 * 找 `~/.dsh/sessions/` 里 cwd 对应的项目目录。
 *
 * 先按命名规则直接拼一个（`D:\a\B` → `--D-a-B--`）—— 命中率接近 100%，一步到位。
 * 拼不出来（历史目录名有大小写/斜杠差异）才扫目录、读每个项目的第一个会话头比对。
 */
function projectDirFor(cwd) {
  const guess = path.join(sessionsRoot(), `--${cwd.replace(/[:\\/]+/g, '-').replace(/^-+|-+$/g, '')}--`);
  if (existsSync(guess)) return guess;

  for (const name of safeReaddir(sessionsRoot())) {
    const dir = path.join(sessionsRoot(), name);
    const header = firstSessionHeader(dir);
    if (header?.cwd === cwd) return dir;
  }
  return guess;
}

function firstSessionHeader(projectDir) {
  for (const sub of safeReaddir(projectDir)) {
    const file = path.join(projectDir, sub, 'session.v4.jsonl.zstd');
    if (!existsSync(file)) continue;
    const text = readFirstFrame(file);
    if (!text) continue;
    try {
      const rec = JSON.parse(text.split('\n')[0]);
      if (rec.type === 'session') return rec;
    } catch { /* 坏文件，看下一个 */ }
  }
  return null;
}

/** 只解第一帧（读头部足够）。 */
function readFirstFrame(file) {
  try {
    const buf = readFileSync(file);
    const next = buf.indexOf(ZSTD_MAGIC, 4);
    return zstdDecompressSync(next > 0 ? buf.subarray(0, next) : buf).toString('utf8');
  } catch {
    return null;
  }
}

/* ------------------------------------------------------------ 导入账本 */

/** 账本：`convId → { convId, title, at }`，键是 DSH 的 session id。 */
const mapFile = (src) => path.join(src, 'map.json');

export function loadMap(src) {
  try {
    const m = JSON.parse(readFileSync(mapFile(src), 'utf8'));
    return m && typeof m === 'object' ? m : {};
  } catch {
    return {};
  }
}

function remember(src, dshId, convId, title) {
  const map = loadMap(src);
  // 同一个手机包只留一条：顺手清掉历史脏记录（裸 uuid / 换过 id 的旧记录）
  for (const [k, v] of Object.entries(map)) {
    if (v?.convId === convId && k !== dshId) delete map[k];
  }
  map[dshId] = { convId, title, at: Date.now() };
  mkdirSync(src, { recursive: true });
  writeFileSync(mapFile(src), JSON.stringify(map, null, 2));
}

/* ------------------------------------------------------------ 主流程 */

/**
 * 把手机包导进 DSH。
 *
 * @param {object}  [opts]
 * @param {string}  [opts.src]   手机包目录
 * @param {string}  [opts.cwd]   落到哪个项目（默认当前目录）
 * @param {boolean} [opts.dry]   只看不做
 * @param {boolean} [opts.force] 原地重写（复用同一个 session id）
 * @param {Function}[opts.log]
 * @returns {{added:number, skipped:number, ids:string[]}}
 */
export async function importToDsh(opts = {}) {
  const log = opts.log || console.log;
  const src = path.resolve(opts.src || dataDir());
  const cwd = opts.cwd || process.cwd();
  const dry = !!opts.dry;
  const force = !!opts.force;

  const packs = listPacks(src);
  if (!packs.length) {
    throw new Error(`没找到要导入的会话。先 pull 一次，或在手机上长按会话点「发到电脑」。\n  找的是：${src}/conv-*/ 或 ${src}/conv/*.jsonl`);
  }

  const target = projectDirFor(cwd);
  const map = loadMap(src); // 读一次，循环里不再碰磁盘

  log(`源：${src}`);
  log(`目标项目：${target}`);

  let added = 0; let skipped = 0;
  const ids = [];

  for (const pack of packs) {
    const prev = Object.keys(map).find((k) => map[k]?.convId === pack.id && k.startsWith('session-'));
    const state = prev ? completeness(prev, target, cwd) : null;

    if (prev && state.complete && !force) {
      log(`· ${pack.meta.title || pack.id}　已经导过了（${short(prev)}），跳过`);
      skipped += 1;
      continue;
    }
    if (prev && !state.complete) {
      log(`· ${pack.meta.title || pack.id}　不完整（${state.detail}），用同一个 id 补齐`);
    }

    const id = prev || `session-${crypto.randomUUID()}`;
    const built = buildSession({ id, meta: pack.meta, msgs: pack.msgs, summary: pack.summary }, cwd);

    log(`✓ ${built.meta.title}　${pack.msgs.length} 条 / 摘要 ${pack.summary ? '有' : '无'}　→ ${short(id)}`);

    if (dry) { added += 1; ids.push(id); continue; }

    // ① 正文
    const dir = path.join(target, id);
    mkdirSync(dir, { recursive: true });
    const body = path.join(dir, 'session.v4.jsonl.zstd');
    writeFileSync(body, encodeFrames(built.recs));

    /**
     * **写完立刻用 DSH 自己的 `Session.fromRestore` 验一遍**（和 DSH 打开会话同一份代码）。
     *
     * DSH 每次升级都会在加载边界加新校验（0.1.7 加 source kind、0.2.2 加 settlement 三件套…），
     * 自己复刻规则永远落后一步；等用户点开才发现，界面上只有一行"历史加载失败"。
     * 不过就当场回滚 + 报错，别把坏文件留在磁盘上。
     */
    const verdict = await validateSessionFile(body);
    if (!verdict.ok) {
      try { rmSync(dir, { recursive: true, force: true }); } catch { /* 删不掉也别盖住真正的错 */ }
      const plain = verdict.plain ? `\n  说人话：${verdict.plain}` : '';
      throw new Error(
        `「${built.meta.title}」这条没能写进去（DSH 不认，已删掉，不留坏文件）：${plain}\n`
        + `  原始报错：${verdict.why}\n`
        + '  → 多半是这版插件比你的 DSH 旧。先跑 `dsh-see-on-phone doctor --all` 看全貌，'
        + '再 `npm i -g dsh-see-on-phone@latest` 升级插件。',
      );
    }

    // ② 列表缓存
    writeSessionCache({
      dshId: id,
      cwd,
      title: built.meta.title,
      firstText: pack.msgs.find((m) => m.role === 'user')?.text?.replace(/\s+/g, ' ').trim().slice(0, 200) ?? '',
      firstSeq: built.meta.firstSeq,
      turns: built.meta.turns,
      lastAt: Number(pack.msgs.at(-1)?.at) || Date.now(),
      maxSeq: built.meta.maxSeq,
      createdAt: Number(pack.meta.createdAt) || Number(pack.msgs[0]?.at) || Date.now(),
      outline: built.meta.outline,
    });

    // ③ 项目登记
    const reg = registerSession(id, cwd);
    if (!reg.ok) log(`  ⚠ 没能登记进项目：${reg.why}`);

    remember(src, id, pack.id, pack.meta.title || built.meta.title);
    added += 1;
    ids.push(id);
  }

  log(`\n${dry ? '（dry run）将要导入' : '导入完成'}：新增 ${added}，跳过 ${skipped}`);
  if (!dry && added) {
    log('DSH（dsh web / 桌面版）刷新一下就能在左侧「工作区」里看到。');
    log('想确认 DSH 真能读：dsh-see-on-phone doctor（用 DSH 自己的 Session 类校验）');
  }
  return { added, skipped, ids };
}

/** ①正文 ②缓存 ③登记 三样齐不齐。 */
function completeness(id, target, cwd) {
  const body = existsSync(path.join(target, id, 'session.v4.jsonl.zstd'));
  const cache = hasSessionCache(id);
  const registered = isSessionRegistered(id, cwd);
  const missing = [
    body ? null : '正文',
    cache ? null : '缓存',
    registered ? null : '登记',
  ].filter(Boolean);
  return { complete: missing.length === 0, detail: missing.length ? `缺${missing.join('/')}` : '' };
}

const short = (id) => id.replace(/^session-/, '').slice(0, 8);

function safeReaddir(dir) {
  try { return readdirSync(dir); } catch { return []; }
}

/* ------------------------------------------------------------ CLI */

/** 单独跑时用（`node to-dsh.mjs --src ...`）；`cli.mjs` 直接调 `importToDsh()`。 */
export async function runCli(argv) {
  const flag = (n) => argv.includes(`--${n}`);
  const opt = (n) => { const i = argv.indexOf(`--${n}`); return i >= 0 ? argv[i + 1] : undefined; };
  await importToDsh({
    src: opt('src'),
    cwd: opt('cwd'),
    dry: flag('dry'),
    force: flag('force'),
  });
}

const isDirect = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isDirect) {
  try { await runCli(process.argv.slice(2)); }
  catch (e) { console.error(`✗ ${e.message}`); process.exit(1); }
}
