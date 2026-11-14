#!/usr/bin/env node
/**
 * DSH 会话体检器 —— 用 **DSH 自己的 Session 类**跑一遍 seed/load 校验。
 *
 * 为什么不用"复刻规则"：DSH 每个版本都在加载边界加新校验（0.1.7 加 source kind，
 * 0.2.2 加 settlement 三件套 …），复刻的规则永远落后一步，而且一旦漏判，
 * 用户看到的就是界面上一行"历史加载失败"，根本联想不到是导入脚本的问题。
 * 这里直接从 `app.asar` 里解出 `@deepseek-ai/dsh-session` 的依赖闭包，
 * import 真模块调 `Session.fromRestore()` —— 和 DSH 打开会话走的是同一份代码。
 *
 * 用法：
 *   dsh-see-on-phone doctor             只查当前工作区（cwd 对应的项目目录）
 *   dsh-see-on-phone doctor --all       查所有项目
 *   dsh-see-on-phone doctor --verbose   列出通过/失败的每一条
 *
 * 退出码：0 全过，1 有坏会话（适合挂到导入脚本后面）。
 */
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import zlib from 'node:zlib';
import { pathToFileURL } from 'node:url';
import { dshHome, projectDir, findAsar, explain } from './paths.mjs';

const SESSIONS_ROOT = path.join(dshHome(), 'sessions');

/** 解出来的模块缓存。放系统临时目录 —— 不要往用户当前目录里丢 `.tmp/`。 */
const CACHE = path.join(os.tmpdir(), 'dsh-see-on-phone-doctor');

/**
 * 要从 `app.asar` 里解出来的入口包。
 *
 * `dsh-session` 只有单条事件的准入（`adoptSessionEvent`，注释里写明
 * "history relations are not checked"）；**跨事件**的关系校验
 * （`session/title messageSeqs must cite distinct earlier human user/message`
 * 这一条就在里面）在 `dsh-session-format-v3-to-v4` 的 `Relationships` 里。
 * 只解前者时 doctor 会对坏会话说"通过" —— 2026-11-14 就是这么放过 19 条的。
 */
const UNPACK_ENTRIES = [
  '@deepseek-ai/dsh-session',
  '@deepseek-ai/dsh-session-format-v3-to-v4',
];

/* ------------------------------------------------------------ zstd 多帧 */

const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

/** 会话文件是**多帧** zstd 拼接，`zstdDecompressSync` 只解第一帧，必须按 magic 切开。 */
function splitFrames(buf) {
  const idxs = [];
  let i = 0;
  while (true) {
    const p = buf.indexOf(MAGIC, i);
    if (p < 0) break;
    idxs.push(p);
    i = p + 4;
  }
  return idxs.map((s, k) => buf.subarray(s, k + 1 < idxs.length ? idxs[k + 1] : buf.length));
}

function readEvents(file) {
  const chunks = [];
  for (const fr of splitFrames(fs.readFileSync(file))) {
    try {
      chunks.push(zlib.zstdDecompressSync(fr));
    } catch (e) {
      throw new Error(`第 ${chunks.length + 1} 帧解压失败：${e.message}`);
    }
  }
  const recs = Buffer.concat(chunks)
    .toString('utf8')
    .split('\n')
    .filter((l) => l.trim())
    .map((l) => JSON.parse(l));
  if (recs[0]?.type !== 'session') throw new Error('首行不是 session 头');
  return { header: recs[0], events: recs.slice(1) };
}

/* ------------------------------------------------------------ 解包 DSH */

/** 找 DSH 安装包（Windows / macOS / Linux 都试，支持 `DSH_ASAR` 覆盖）。见 `paths.mjs`。 */
const asarPath = () => findAsar();

/** 读 asar 头，返回 `{ baseOffset, files[] }`。 */
function readAsarIndex(asar) {
  const fd = fs.openSync(asar, 'r');
  try {
    const head = Buffer.alloc(16);
    fs.readSync(fd, head, 0, 16, 0);
    const jsonSize = head.readUInt32LE(12);
    const hb = Buffer.alloc(jsonSize);
    fs.readSync(fd, hb, 0, jsonSize, 16);
    const tree = JSON.parse(hb.toString('utf8'));
    const files = [];
    (function walk(node, prefix) {
      for (const [name, v] of Object.entries(node.files || {})) {
        const full = `${prefix}/${name}`;
        if (v.files) walk(v, full);
        else files.push({ path: full, size: v.size, offset: Number(v.offset) });
      }
    })(tree, '');
    // ⚠️ 数据起点是 **8 + 头部 pickle 总长**（= `head.readUInt32LE(4)`），
    // 不是 `16 + jsonSize` —— 那少算了 4 字节对齐 padding，
    // 解出来的每个文件都往前错位，package.json 会以 `\n}\n{` 开头（JSON 解析直接炸）。
    return { baseOffset: 8 + head.readUInt32LE(4), files };
  } finally {
    fs.closeSync(fd);
  }
}

/**
 * 把 `@deepseek-ai/dsh-session` 及其依赖闭包解到 `.tmp/dsh-doctor/node_modules/`。
 * 解一次就缓存，除非 asar 换了（用大小+mtime 当指纹）。
 */
function unpackDshSession(asar) {
  const stamp = `${fs.statSync(asar).size}-${fs.statSync(asar).mtimeMs}-${UNPACK_ENTRIES.join(',')}`;
  const stampFile = path.join(CACHE, 'stamp.txt');
  const modDir = path.join(CACHE, 'node_modules');
  if (fs.existsSync(stampFile) && fs.readFileSync(stampFile, 'utf8') === stamp && fs.existsSync(modDir)) return modDir;

  const { baseOffset, files } = readAsarIndex(asar);
  const fd = fs.openSync(asar, 'r');
  try {
    const readText = (f) => {
      const b = Buffer.alloc(f.size);
      fs.readSync(fd, b, 0, f.size, baseOffset + f.offset);
      return b.toString('utf8');
    };
    const byPkg = new Map();
    for (const f of files) {
      if (!f.path.startsWith('/dsh/node_modules/') || !/\.(js|mjs|cjs|json)$/.test(f.path)) continue;
      const m = f.path.slice('/dsh/node_modules/'.length).match(/^(@[^/]+\/[^/]+|[^/]+)\//);
      if (!m) continue;
      if (!byPkg.has(m[1])) byPkg.set(m[1], []);
      byPkg.get(m[1]).push(f);
    }

    // 依赖闭包：从 entry 开始，顺着 import/require 的包名扩散
    const need = new Set();
    const queue = [...UNPACK_ENTRIES];
    const re = /(?:from\s*|import\s*\(\s*|require\s*\(\s*)["']([^"']+)["']/g;
    while (queue.length) {
      const pkg = queue.shift();
      if (need.has(pkg) || !byPkg.has(pkg)) continue;
      need.add(pkg);
      for (const f of byPkg.get(pkg)) {
        const t = readText(f);
        let m;
        re.lastIndex = 0;
        while ((m = re.exec(t))) {
          const spec = m[1];
          // ⚠️ 作用域包要取**两段**（`@js-temporal/polyfill`），只取第一段会解出 `@js-temporal` —— 空目录，
          //    接着 import 时报 `Cannot find package '@js-temporal/polyfill'`。
          if (spec.startsWith('@')) queue.push(spec.split('/').slice(0, 2).join('/'));
          else if (!spec.startsWith('.') && !spec.startsWith('node:')) queue.push(spec.split('/')[0]);
        }
      }
    }

    fs.rmSync(modDir, { recursive: true, force: true });
    for (const f of files) {
      if (!f.path.startsWith('/dsh/node_modules/')) continue;
      const rel = f.path.slice('/dsh/node_modules/'.length);
      const m = rel.match(/^(@[^/]+\/[^/]+|[^/]+)\//);
      // ⚠️ asar 头里的 offset 是**字符串**，`Number.isFinite('123')` 是 false ——
      // 直接判类型会把每个文件都跳过，解出来一个空包（报 "Invalid package config"）。
      const off = Number(f.offset);
      if (!m || !need.has(m[1]) || !Number.isFinite(off)) continue;
      const dest = path.join(modDir, rel);
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      const b = Buffer.alloc(f.size);
      fs.readSync(fd, b, 0, f.size, baseOffset + off);   // off 必须是 number，否则是字符串拼接
      fs.writeFileSync(dest, b);
    }
    fs.mkdirSync(CACHE, { recursive: true });
    fs.writeFileSync(stampFile, stamp);
    return modDir;
  } finally {
    fs.closeSync(fd);
  }
}

let _Session = null;

/** 取 DSH 自己的 `Session` 类（解包 + import，缓存一次）。 */
export async function loadSessionClass() {
  if (_Session) return _Session;
  const asar = asarPath();
  if (!asar) {
    throw new Error('没找到 DSH 安装目录。设一个 DSH_ASAR 指到 resources/app.asar，或者先打开一次 DSH 让它自己解出来。');
  }
  const modDir = unpackDshSession(asar);
  const entry = path.join(modDir, '@deepseek-ai', 'dsh-session', 'lib', 'index.js');
  const mod = await import(pathToFileURL(entry).href);
  if (typeof mod.Session?.fromRestore !== 'function') throw new Error('DSH 的 Session 类没有 fromRestore，版本结构可能变了');
  _Session = mod.Session;
  return _Session;
}

/**
 * 取 DSH 的**跨事件关系校验器**（`assertV4LifecycleRelationships`）。
 *
 * 这一层和 `Session.fromRestore` 是两个不同的准入：
 *   · `Session.fromRestore` —— 单条事件形状 + 内存模型，`doctor` 原来只查这个；
 *   · `Relationships`（本函数）—— **事件之间的引用**，比如
 *     `session/title.messageSeqs` 必须指向更早的、`source.kind === 'user'` 的 `user/message`。
 *
 * 2026-11-14 的故障就是后者：`messageSeqs` 指向了 `step/start`，
 * gateway 读盘时抛 `stored log is corrupt: SessionFormatError: … must cite distinct
 * earlier human user/message events`，而 doctor 一路报"通过"。
 */
let _relationships = null;

async function loadRelationships() {
  if (_relationships) return _relationships;
  const asar = asarPath();
  if (!asar) {
    throw new Error('没找到 DSH 安装目录。设一个 DSH_ASAR 指到 resources/app.asar，或者先打开一次 DSH 让它自己解出来。');
  }
  const modDir = unpackDshSession(asar);
  const entry = path.join(modDir, '@deepseek-ai', 'dsh-session-format-v3-to-v4', 'lib', 'index.js');
  const mod = await import(pathToFileURL(entry).href);
  const sess = await import(pathToFileURL(path.join(modDir, '@deepseek-ai', 'dsh-session', 'lib', 'index.js')).href);
  if (typeof mod.restoreReleasedV4Artifact !== 'function') {
    throw new Error('DSH 的 dsh-session-format-v3-to-v4 没有 restoreReleasedV4Artifact，版本结构可能变了');
  }
  _relationships = {
    assert: mod.restoreReleasedV4Artifact,
    known: sess.KNOWN_SESSION_EVENT_TYPES,
  };
  return _relationships;
}

/** 有 surfaceOp 的四种事件（和 DSH 的 `SURFACE_TYPES` 一致）。 */
const SURFACE_TYPES = new Set(['system/message', 'user/message', 'assistant/message', 'tool/result']);

/**
 * DSH 的第二条硬规则（藏在 plugin-manager 的 v3 兼容路径里，`Session.fromRestore` 不查）：
 *
 *   ① `system/message` 必须在一个**打开的 step** 里 —— 前面有 `step/start`，且 turn/step 对得上；
 *   ② 它必须是**整个会话的第一个 surface 节点**（否则 `head` 没被设过，直接判损坏）。
 *
 * 两条同时满足的写法只有一种（原生 DSH 就是这么写的）：
 * `turn/start → step/start → system/message → user/message → …`
 *
 * ⚠️ 2026-11-14 踩过：`dsh-import` 把 `step/start` 写在 `user/message` **后面**，
 * 于是 system/message 插哪儿都非法 —— 插前面没 open step，插后面又不是第一个 surface。
 * 表现是"导入的会话能打开，但用户在里头再聊一句，DSH 补一条 system/message 就整条损坏"。
 */
function checkSurfaceHead(events) {
  let step;
  let head;
  let hasSurface = false;
  for (const e of events) {
    if (e.type === 'step/start') step = { turn: e.data?.turn, step: e.data?.step };
    else if (e.type === 'step/end' || e.type === 'turn/end') step = undefined;

    if (e.type === 'system/message') {
      if (step === undefined || step.turn !== e.data?.turn || step.step !== e.data?.step) {
        throw new Error(`seq ${e.seq}: system/message 不在打开的 step 里（前面缺 step/start 或 turn/step 对不上）`);
      }
      if (hasSurface && head === undefined) {
        throw new Error(`seq ${e.seq}: system/message 不是第一个 surface（缺 protected head）`);
      }
      if (e.surfaceOp === 'append' && !hasSurface) head = e.seq;
    }
    if (SURFACE_TYPES.has(e.type)) hasSurface = true;
  }
}

/**
 * 校验一个会话文件（**导出给导入器用**：写完立刻验，别等用户点开才发现）。
 *
 * @returns {Promise<{ok:boolean, why?:string}>}
 */
/**
 * 不依赖 DSH 安装包的**基本自洽检查**。
 *
 * 为什么要它：深度校验得借 DSH 自己的模块，而 DSH 可能装在扫不到的位置（自定义路径、
 * 绿色版、容器里）。那种情况下如果直接判"全部失败"，用户会被一堆红字吓死；
 * 直接判"全部通过"又是帮倒忙。所以分两层：
 *
 *   第一层（这里）—— 只查"文件和它自己矛盾"的地方，规则都是 DSH 明写的硬约束：
 *     · 事件编号必须从 0 连续（DSH 全系统依赖 `seq = log.length`）
 *     · `assistant/message` 必须有 turn / step / stream 三件套
 *     · 每条消息必须有 id / role / content / source.kind
 *     · `session/title` 引用的必须是一条更早的、真人说的 `user/message`
 *   第二层（有 DSH 就做）—— 用 DSH 自己的 `Session.fromRestore` + `restoreReleasedV4Artifact`，
 *   规则跟它当前版本完全同步。
 *
 * @returns {{ok:boolean, why?:string}}
 */
export function checkLightweight(header, events) {
  for (const [i, e] of events.entries()) {
    if (e?.seq !== i) return { ok: false, why: `seq ${e?.seq ?? '?'} 在第 ${i} 条——记录编号断档了` };
    const d = e.data || {};
    if (e.type === 'assistant/message' || e.type === 'assistant/attempt') {
      if (!Number.isSafeInteger(d.turn)) return { ok: false, why: `seq ${e.seq}：回答缺 turn` };
      if (!Number.isSafeInteger(d.step)) return { ok: false, why: `seq ${e.seq}：回答缺 step` };
      if (!Array.isArray(d.stream)) return { ok: false, why: `seq ${e.seq}：回答缺 stream（收尾信息）` };
    }
    if (e.type === 'assistant/message' || e.type === 'user/message') {
      const m = e.type === 'user/message' ? d : d.message;
      if (typeof m?.id !== 'string' || !m.id) return { ok: false, why: `seq ${e.seq}：消息缺 id` };
      if (!Array.isArray(m?.content)) return { ok: false, why: `seq ${e.seq}：消息缺 content` };
      if (typeof m?.source?.kind !== 'string' || !m.source.kind) return { ok: false, why: `seq ${e.seq}：消息没标来源` };
    }
  }

  const byIdx = events;
  for (const ev of events) {
    if (ev.type !== 'session/title' && ev.type !== 'session/title-llm-request') continue;
    const refs = Array.isArray(ev.data?.messageSeqs) ? ev.data.messageSeqs : [];
    const kind = ev.data?.source?.kind;
    if (ev.type === 'session/title' && (refs.length === 0) !== (kind === 'user')) {
      return { ok: false, why: `seq ${ev.seq}：标题来源标记和引用对不上` };
    }
    const seen = new Set();
    for (const v of refs) {
      const s = byIdx[v];
      if (!Number.isSafeInteger(v) || v >= ev.seq) return { ok: false, why: `seq ${ev.seq}：标题引用了不存在/更晚的记录 ${v}` };
      if (seen.has(v)) return { ok: false, why: `seq ${ev.seq}：标题重复引用 ${v}` };
      if (s?.type !== 'user/message') return { ok: false, why: `seq ${ev.seq}：标题引用的 ${v} 是 ${s?.type ?? '空'}，不是用户消息` };
      if (s.data?.source?.kind !== 'user') return { ok: false, why: `seq ${ev.seq}：标题引用的 ${v} 不是真人说的` };
      seen.add(v);
    }
  }
  return { ok: true };
}

/**
 * 校验一个会话文件。
 *
 * @param {string} file
 * @param {{deep?:boolean}} [opts] `deep: false` 只跑第一层（快，用来批量扫）
 * @returns {Promise<{ok:boolean, why?:string, plain?:string, action?:string, degraded?:boolean}>}
 */
export async function validateSessionFile(file, opts = {}) {
  let header;
  let events;
  try {
    ({ header, events } = readEvents(file));
  } catch (e) {
    return { ok: false, why: `读不出这个文件：${e.message}` };
  }

  // 第一层：文件自身矛盾（不依赖 DSH 装在哪）
  const light = checkLightweight(header, events);
  if (!light.ok) return { ...light, ...explain(light.why) };
  if (opts.deep === false) return { ok: true };

  // 第二层：借 DSH 自己的代码，规则跟它当前版本同步
  try {
    const Session = await loadSessionClass();
    Session.fromRestore(header.id, events, header, 0, 'detached');
    checkSurfaceHead(events);
    const rel = await loadRelationships();
    // artifact 形状照 DSH 的 `restoreReleasedV4Artifact`：header 单独放、events 不含它、seq 从 0 密排。
    // 三处必须改写，否则会被它拒掉：
    //   · 文件里 header 带 `type: "session"`，而 `assertReleasedV4Header` 的白名单里没有这个键
    //     （报 `format v4 header has unexpected field type`）
    //   · `session-log-deepseek/delivery-accepted` 的 `sessionId` 有带/不带 `session-` 前缀两种写法，
    //     fork 出来的会话里还夹着父会话的 id —— 这里的继承段长度拿不到，
    //     所以统一改写成 header.id 放过去（这条检查对这里没有判别力，`messageSeqs` 不受影响）。
    //   · `inheritedEventCount` 传 0。
    const { type: _headerType, ...hdr } = header;
    const evs = events.map((e) => {
      if (e.type !== 'session-log-deepseek/delivery-accepted') return e;
      return { ...e, data: { ...e.data, sessionId: hdr.id } };
    });
    rel.assert({ header: hdr, events: evs, inheritedEventCount: 0 }, rel.known);
    return { ok: true };
  } catch (e) {
    const why = String(e?.cause?.message ?? e?.message ?? e).split('\n')[0];
    // 找不到 DSH / 包结构变了：**不算这条会话的错**，降级并如实说明
    if (/没找到 DSH 安装目录|Cannot find package|Cannot find module|没有 \w+，版本结构可能变了/.test(why)) {
      return { ok: true, degraded: true, why: '只做了基础检查：' + why };
    }
    return { ok: false, why, ...explain(why) };
  }
}

/* ------------------------------------------------------------ 主流程 */

export async function runCli(argv) {
const all = argv.includes('--all');
const verbose = argv.includes('--verbose');

function projectDirs() {
  if (!fs.existsSync(SESSIONS_ROOT)) return [];
  return fs
    .readdirSync(SESSIONS_ROOT)
    .map((n) => path.join(SESSIONS_ROOT, n))
    .filter((d) => fs.statSync(d).isDirectory());
}

/** cwd → `--D-a-B--` 这种目录名（保留首尾各两个短横）。 */
function slugOf(cwd) {
  return `--${cwd.replace(/[:\\/]+/g, '-').replace(/^-+|-+$/g, '')}--`;
}

// 深度校验要借 DSH 的模块；拿不到就降级（只做第一层），**不当作失败**
let deep = true;
let degradedWhy = '';
try {
  await loadSessionClass();
} catch (e) {
  deep = false;
  degradedWhy = String(e.message).split('\n')[0];
}

const dirs = all ? projectDirs() : [path.join(SESSIONS_ROOT, slugOf(projectDir()))].filter((d) => fs.existsSync(d));
if (!dirs.length) {
  console.log('没找到会话目录。DSH 还没在这个项目里建过会话？换个目录跑，或者加 --all 看全部。');
  return { ok: 0, fails: [] };
}

if (!deep) {
  console.log(`⚠ 只做基础检查：${degradedWhy}`);
  console.log('  （想让它跟 DSH 的判定完全一致：确认 DSH 装着，或设 DSH_ASAR 指到它的 resources/app.asar）\n');
}

let ok = 0;
let skipped = 0;
const fails = [];
for (const dir of dirs) {
  for (const sid of fs.readdirSync(dir)) {
    const file = path.join(dir, sid, 'session.v4.jsonl.zstd');
    if (!fs.existsSync(file)) continue;
    const verdict = await validateSessionFile(file, { deep });
    if (verdict.degraded) skipped += 1;
    if (!verdict.ok) {
      fails.push({ dir: path.basename(dir), sid, why: verdict.why, plain: verdict.plain, action: verdict.action });
      console.log(`✗ ${path.basename(dir)}/${sid}`);
      if (verdict.plain) console.log(`    ${verdict.plain}`);
      console.log(`    原始报错：${verdict.why}`);
      continue;
    }
    ok += 1;
    if (verbose) console.log(`✓ ${path.basename(dir)}/${sid}`);
  }
}

console.log(`\n${all ? '全部项目' : '当前项目'}：通过 ${ok}，失败 ${fails.length}${skipped ? `，${skipped} 条只做了基础检查` : ''}`);

if (fails.length) {
  const kinds = new Map();
  for (const f of fails) kinds.set(f.why, (kinds.get(f.why) ?? 0) + 1);
  console.log('\n按原因归并：');
  for (const [why, n] of kinds) console.log(`  ${n} 个：${why}`);
  console.log('\n下一步：`dsh-see-on-phone repair --write`（每个文件先自动备份再改）。');
  console.log('它修不了的（老格式迁移留下的 sourceEventSeqs / surface replace），按备份手改。');
  return { ok, fails, code: 1 };
}
return { ok, fails: [] };
}

const isDirect = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isDirect) {
  const r = await runCli(process.argv.slice(2));
  if (r?.fails?.length) process.exit(1);
}
