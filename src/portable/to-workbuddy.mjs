#!/usr/bin/env node
/**
 * wb-import.mjs —— 把手机会话导进 **WorkBuddy 桌面端真正读的地方**。
 *
 * ## 为什么不是 `~/.dsh/sessions/`（重要，别再走错）
 *
 * WorkBuddy 侧边栏的会话列表 = `~/.workbuddy/workbuddy.db` 的 `sessions` 表。
 * 每个会话的正文 = `~/.workbuddy/projects/<项目slug>/<uuid>.jsonl`（普通 JSONL，不是 zstd）。
 *
 * 而 `~/.dsh/sessions/*.zstd` + `session_projcache` + `workspace.json` 那套是
 * **DSH 引擎/CLI 自己的存储**，跟桌面端侧边栏不是一条线 —— 往那里写，桌面上永远看不见。
 * （2026-11-13 绕了三轮才确认，见 `看见-会话互导-交接-20261113.md`。）
 *
 * ## 侧边栏的取数条件（2026-11-13 从 `app.asar` → `main/node.js` 反解确认）
 *
 * ```sql
 * SELECT ... FROM sessions
 *  WHERE transport = 'local'
 *    AND deleted_at IS NULL
 *    AND (user_id = ? OR user_id = '')
 *  ORDER BY COALESCE(last_activity_at, created_at) DESC, id ASC
 * ```
 *
 * 也就是说：**`conversation_origin` 不参与过滤**（可以放心用它做来源标记），
 * 但 `transport` / `deleted_at` / `user_id` 三个字段错一个就永远不显示。
 * `user_id` 不是写死的，本工具从库里最近一条真实会话上抄。
 *
 * ## 用法
 *
 * 一般不直接调，走 CLI：
 *
 * ```bash
 * dsh-see-on-phone import --wb            # 只导 WorkBuddy 侧边栏
 * dsh-see-on-phone import --both          # DSH + WorkBuddy 都导
 * dsh-see-on-phone import --wb --dry      # 只看要导什么
 * ```
 *
 * ⚠️ 这条线要 **Node 22.5+**（用内置 `node:sqlite` 读 `workbuddy.db`）。
 * 低版本不会崩，只是这条线不可用、`import --dsh` 照常能用。
 *
 * ## 安全
 *
 * - 只**新增**，从不改已有行；`sessions.id` 是主键，重复导会直接跳过。
 * - 去重账本 `.workbuddy/phone-data/wb-map.json`（convId → wbSessionId）。
 * - 写库前自动备份 `workbuddy.db` 到 `~/.workbuddy/wb-backup/`。
 * - `status` 用 `completed`（正常空闲会话就这个值），侧边栏才当它是正常会话；
 *   `archived` 会被折叠进"归档"，`working` 会一直转圈。
 * - 想把库指到别处（测试用）：设 `DSH_WB_HOME`。
 */
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, copyFileSync, rmSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { listPacks, titleOf, IMG_NOTE } from './pack.mjs';
import { dataDir, projectDir } from './paths.mjs';

/* --------------------------------------------------------------- 常量 */

/** WorkBuddy 的家目录。测试时用 `DSH_WB_HOME` 指到临时目录，绝不碰真库。 */
const WB = process.env.DSH_WB_HOME || path.join(os.homedir(), '.workbuddy');
const DB_FILE = path.join(WB, 'workbuddy.db');
const PROJECTS = path.join(WB, 'projects');
const BACKUP_DIR = path.join(WB, 'wb-backup');

const DEFAULT_SRC = () => dataDir();
/** 导进哪个项目：默认就是"你现在站的这个目录"。 */
const DEFAULT_CWD = () => projectDir().replace(/\\/g, '/');

/**
 * `node:sqlite` 是 **Node 22.5+** 才有的内置模块。
 *
 * 这里用顶层 `await import()` 而不是顶部静态 import —— 静态 import 在 Node 20 上
 * 会让**整个文件加载失败**，连带 `import --dsh`（只导 DSH 那条线）也一起崩。
 * 现在只是这一条线不可用，会给出人话提示。
 */
let DatabaseSync = null;
let sqliteWhy = null;
try {
  ({ DatabaseSync } = await import('node:sqlite'));
} catch (e) {
  sqliteWhy = `这条线要 Node 22.5 以上（内置 node:sqlite），你现在是 ${process.version}。\n`
    + '  改用 `dsh-see-on-phone import --dsh` 只导进 DSH；或者装个新版 Node 再来。';
}

/* ------------------------------------------------------------------ 小工具 */

const now = () => Date.now();

/**
 * uuid v7：48 位毫秒时间戳 + 随机。
 *
 * 为什么要 v7 而不是 `randomUUID()`：WorkBuddy 自己写盘用的是 v7（id 前缀就是时间），
 * 正文里 `session-meta` / message / `ai-title` 的 id 全是这个形状。既然要"同构"，
 * 就一步到位 —— 免得以后某个按 id 排序/去重的地方因为 v4 乱序出怪事。
 */
function uuidv7() {
  const b = Buffer.alloc(16);
  b.writeUIntBE(Date.now(), 0, 6);
  crypto.randomFillSync(b, 6);
  b[6] = (b[6] & 0x0f) | 0x70; // version 7
  b[8] = (b[8] & 0x3f) | 0x80; // variant 10xx
  const h = b.toString('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** DB 里的 cwd 形状（正斜杠）→ 正文 jsonl 里的形状（反斜杠 + 盘符小写）。 */
function cwdForFile(cwd) {
  const s = String(cwd).replace(/\//g, '\\');
  return /^[A-Z]:/.test(s) ? s.charAt(0).toLowerCase() + s.slice(1) : s;
}

/**
 * 从已有的 projects 目录里认出这个 cwd 对应的 slug（别自己猜大小写）。
 * WorkBuddy 的规则是 `D:\a\B-C` → `d-a-B-C`，大小写保留原样。
 */
function slugFor(cwd) {
  const norm = (s) => s.replace(/-/g, '').toLowerCase();
  const want = norm(String(cwd).replace(/[:\\/]+/g, '-').replace(/^-|-$/g, ''));
  const dirs = existsSync(PROJECTS) ? readdirSync(PROJECTS) : [];
  const hit = dirs.find((d) => norm(d) === want);
  if (hit) return hit;
  const g = String(cwd).replace(/[:\\/]+/g, '-').replace(/^-|-$/g, '');
  return g.charAt(0).toLowerCase() + g.slice(1);
}

/**
 * 把 cwd 对齐成 `workspaces` 表里那一份的**原样字符串**。
 *
 * 这一条是踩出来的：侧边栏按工作区（`workspaces.path`）分组挂会话，
 * 而工作区表里是 `C:\Users\me\3-Life`（反斜杠），我们写 `C:/Users/me/3-Life` 时，
 * 库里 HTTP 层面一切正常、快照里也有这条 —— **但界面上它挂不到任何工作区**，
 * 用户翻遍每个工作区都找不到。
 *
 * 所以：只要表里有语义相同的路径（大小写/斜杠不同也算），就用它那一份。
 * 这样即使前端是逐字符比较，也能命中。
 */
function canonicalCwd(db, cwd) {
  const norm = (s) => String(s).replace(/[\\/]+/g, '/').replace(/\/+$/, '').toLowerCase();
  let rows = [];
  try { rows = db.prepare('SELECT path FROM workspaces').all(); } catch { /* 老库没这张表 */ }
  const hit = rows.find((r) => typeof r?.path === 'string' && norm(r.path) === norm(cwd));
  return hit ? hit.path : cwd;
}

function openDb(readOnly = false) {
  if (!DatabaseSync) throw new Error(sqliteWhy);
  if (!existsSync(DB_FILE)) {
    throw new Error(`找不到 ${DB_FILE}\n  这条线是往 WorkBuddy 桌面端的侧边栏里导；没装 WorkBuddy 就用 \`import --dsh\`。`);
  }
  const db = new DatabaseSync(DB_FILE, { readOnly });
  // 桌面端正开着，可能正好在写；等一会儿比直接 SQLITE_BUSY 好
  if (!readOnly) db.exec('PRAGMA busy_timeout = 5000');
  return db;
}

/** 侧边栏是拿 `accountProvider().uid` 去查的；这里从库里最近一条真实会话上抄。 */
function currentUserId(db) {
  const r = db.prepare(`
    SELECT user_id FROM sessions
     WHERE user_id IS NOT NULL AND user_id != ''
     ORDER BY updated_at DESC LIMIT 1
  `).get();
  return r?.user_id;
}

/**
 * 验/修的时候用"自己导进去那批"的 user_id 更准。
 *
 * 否则切过账号的话，`currentUserId` 可能拿到新账号的 uid，
 * 一查"可见 0 条"就以为全丢了 —— 其实只是换了个人。
 */
function importedUserId(db) {
  const r = db.prepare(`
    SELECT user_id FROM sessions
     WHERE conversation_origin = 'import' AND user_id IS NOT NULL AND user_id != ''
     ORDER BY updated_at DESC LIMIT 1
  `).get();
  return r?.user_id || currentUserId(db);
}

/**
 * 抄一条现成会话的整行当骨架。
 *
 * 这张表有 40 列，手工填容易漏；抄骨架 = 版本升级加列也不会掉。
 * 优先抄同项目、同用户的（这样 model / expert / context_window 这些字段语义也对）。
 */
function templateRow(db, cwd, userId) {
  const pick = db.prepare(`
    SELECT * FROM sessions
     WHERE cwd = ? AND deleted_at IS NULL AND (user_id = ? OR user_id = '')
     ORDER BY updated_at DESC LIMIT 1
  `).get(cwd, userId);
  if (pick) return pick;
  const any = db.prepare(`
    SELECT * FROM sessions WHERE deleted_at IS NULL AND (user_id = ? OR user_id = '')
    ORDER BY updated_at DESC LIMIT 1
  `).get(userId);
  if (any) return any;
  return db.prepare('SELECT * FROM sessions ORDER BY updated_at DESC LIMIT 1').get();
}

/* ------------------------------------------------------------- 写 WB 存储 */

/**
 * 生成 WorkBuddy 的会话正文 JSONL。
 *
 * **必须跟引擎自己写的完全同构**，否则点进会话可能是一片空白：
 *   - 两条 `session-meta`（引擎初始化时会写两条，id 形状是 uuid v7）
 *   - 每条 message 带 `sessionId` / `cwd` / `parentId`（引擎靠 parentId 链找"这一轮问的是啥"）
 *   - assistant 的 `content[].type` 是 `output_text`，user 是 `input_text`
 *   - 结尾一条 `ai-title`（列表 hover / 顶栏标题都读它）
 *
 * 刻意**不伪造** `providerData`（traceId / requestId 那套）：
 * 那是真实请求的遥测，编一个假的对谁都没好处。缺了它，解析器按
 * `providerData?.startsNewUserRequest !== false` 判定，仍然认这条是用户消息。
 */
function buildJsonl(sessionId, cwd, msgs) {
  const fileCwd = cwdForFile(cwd);
  const first = msgs[0]?.at || now();
  const last = msgs.at(-1)?.at || now();
  const lines = [];

  for (let i = 0; i < 2; i += 1) {
    lines.push(JSON.stringify({
      type: 'session-meta', id: uuidv7(), sessionId, timestamp: first,
      meta: { 'codebuddy.ai/hostKind': 'unopted' },
    }));
  }

  let prev = null;
  for (const m of msgs) {
    const id = uuidv7();
    const rec = {
      id,
      ...(prev ? { parentId: prev } : {}),
      timestamp: m.at,
      type: 'message',
      role: m.role,
      content: [{ type: m.role === 'user' ? 'input_text' : 'output_text', text: m.text }],
    };
    if (m.role === 'assistant') rec.status = 'completed';
    rec.sessionId = sessionId;
    rec.cwd = fileCwd;
    lines.push(JSON.stringify(rec));
    prev = id;
  }

  lines.push(JSON.stringify({
    id: uuidv7(), timestamp: last, type: 'ai-title',
    aiTitle: titleOf(msgs), sessionId, cwd: fileCwd,
  }));

  return lines.join('\n') + '\n';
}

/* -------------------------------------------------------------- 导入账本 */

const mapPath = (src) => path.join(src, 'wb-map.json');

function loadMap(src) {
  try {
    const m = JSON.parse(readFileSync(mapPath(src), 'utf8'));
    return m && typeof m === 'object' ? m : {};
  } catch {
    return {};
  }
}

function saveMap(src, map) {
  mkdirSync(src, { recursive: true });
  writeFileSync(mapPath(src), JSON.stringify(map, null, 2));
}

/* ------------------------------------------------------------------ 主流程 */

/**
 * 把手机会话包导进 WorkBuddy。
 *
 * @param {object}  [opts]
 * @param {string}  [opts.src]   手机包目录
 * @param {string}  [opts.cwd]   落到哪个项目
 * @param {boolean} [opts.dry]   只看不做
 * @param {boolean} [opts.force] 同一手机包重导（先删旧行和旧文件）
 * @param {Function}[opts.log]
 * @returns {{added:number, skipped:number, ids:string[]}}
 */
export function importPackages(opts = {}) {
  const log = opts.log || console.log;
  const src = path.resolve(opts.src || DEFAULT_SRC());
  const dry = !!opts.dry;
  const force = !!opts.force;

  if (!existsSync(src)) throw new Error(`没有这个目录：${src}`);
  const packs = listPacks(src);
  if (!packs.length) {
    log(`没找到手机会话包。找的是：${src}/conv-*/ 或 ${src}/conv/*.jsonl（也认 export/ 那层）`);
    return { added: 0, skipped: 0, ids: [] };
  }

  const requested = String(opts.cwd || DEFAULT_CWD()).replace(/\\/g, '/');
  const db = openDb(false);
  // 对齐工作区表里的写法（反斜杠 / 大小写）—— 否则界面上挂不到工作区，详见 canonicalCwd 注释
  const cwd = canonicalCwd(db, requested);
  const slug = slugFor(cwd);
  const outDir = path.join(PROJECTS, slug);
  const userId = currentUserId(db);
  const tpl = templateRow(db, cwd, userId);
  if (!tpl) throw new Error('sessions 表是空的，没法抄骨架');

  const map = loadMap(src);

  log(`源：${src}`);
  log(`目标项目：${cwd}  →  projects/${slug}/`);
  log(`骨架抄自：${String(tpl.id).slice(0, 8)}（${tpl.title || '无标题'}）`);

  if (!dry) {
    mkdirSync(outDir, { recursive: true });
    mkdirSync(BACKUP_DIR, { recursive: true });
    const f = path.join(BACKUP_DIR, `workbuddy-${new Date().toISOString().replace(/[:.]/g, '-')}.db`);
    copyFileSync(DB_FILE, f);
    log(`已备份数据库 → ${f}`);
  }

  let added = 0; let skipped = 0;
  const ids = [];
  const written = []; // 本次已落盘的正文：插库失败时要把它们删掉，别留孤儿

  db.exec('BEGIN');
  try {
    for (const u of packs) {
      const known = map[u.id];
      const alive = known && db.prepare('SELECT 1 FROM sessions WHERE id = ? AND deleted_at IS NULL').get(known);

      if (known && alive && !force) {
        log(`  - ${u.id}：导过了（${known.slice(0, 8)}）`);
        skipped += 1;
        continue;
      }
      if (known && alive && force) {
        // 重导：先把旧行和旧文件清掉，否则会留一条孤儿会话
        log(`  ! ${u.id}：--force，删掉旧的 ${known.slice(0, 8)} 重导`);
        if (!dry) {
          db.prepare('DELETE FROM sessions WHERE id = ?').run(known);
          rmSync(path.join(outDir, `${known}.jsonl`), { force: true });
        }
        delete map[u.id];
      }

      const msgs = u.msgs;
      if (!msgs.length) { log(`  - ${u.id}：没有可导的消息，跳过`); skipped += 1; continue; }

      const id = uuidv7();
      const at = msgs[0].at;
      const last = msgs.at(-1).at;
      const title = (u.meta.title && String(u.meta.title).trim()) || titleOf(msgs);

      log(`  + ${u.id}：${msgs.length} 条消息 → ${id.slice(0, 8)}（${title}）`);
      if (dry) { added += 1; ids.push(id); continue; }

      writeFileSync(path.join(outDir, `${id}.jsonl`), buildJsonl(id, cwd, msgs));
      written.push(path.join(outDir, `${id}.jsonl`));

      // 抄骨架，只改这几个字段
      const row = { ...tpl };
      Object.assign(row, {
        id,
        cwd,
        user_id: userId || tpl.user_id,
        title,
        custom_title: title,
        status: 'completed',
        created_at: at,
        updated_at: last,
        last_activity_at: last,
        deleted_at: null,          // ← 列表的硬条件之一
        is_playground: 0,
        unread: 0,
        transport: 'local',        // ← 列表的硬条件之二
        conversation_origin: 'import', // 只做来源标记，不参与过滤（已核对源码）
      });
      const cols = Object.keys(row);
      db.prepare(`INSERT INTO sessions (${cols.map((c) => `"${c}"`).join(',')})
                  VALUES (${cols.map(() => '?').join(',')})`
      ).run(...cols.map((c) => (row[c] === undefined ? null : row[c])));

      map[u.id] = id;
      ids.push(id);
      added += 1;
    }
    db.exec('COMMIT');
  } catch (e) {
    try { db.exec('ROLLBACK'); } catch { /* 已经回滚了 */ }
    // 正文是在事务里写的，但文件系统不认事务 —— 手动清掉
    for (const f of written) rmSync(f, { force: true });
    throw e;
  } finally {
    db.close();
  }

  if (!dry) saveMap(src, map);

  log(`\n${dry ? '（dry run）将要导入' : '导入完成'}：新增 ${added}，跳过 ${skipped}`);
  if (!dry && added) {
    log('侧边栏如果没立刻刷新：按 Ctrl+R 重载窗口（列表是渲染进程缓存的）。');
    log('要自己验一遍（不用点界面）：dsh-see-on-phone import --wb --verify');
  }
  return { added, skipped, ids };
}

/* ------------------------------------------------------------------ 自检 */

/**
 * 按侧边栏**同一条 SQL** 验一遍，再校验正文文件。
 *
 * 这一步的意义：不用让用户"按 Ctrl+R 看看有没有"。条件是死的，能算就能验。
 *
 * @returns {{visible:number, ok:number, bad:Array}}
 */
export function verifyImports(opts = {}) {
  const log = opts.log || console.log;
  const cwd = String(opts.cwd || DEFAULT_CWD()).replace(/\\/g, '/');
  const slug = slugFor(cwd);
  const dir = path.join(PROJECTS, slug);

  const db = openDb(true);
  const userId = importedUserId(db);

  // ↓ 与 app.asar 里 visibleRows 的 WHERE 逐字对齐（只多了 conversation_origin 用来筛导入项）
  const rows = db.prepare(`
    SELECT id, cwd, title, status, created_at, last_activity_at
      FROM sessions
     WHERE transport = 'local'
       AND deleted_at IS NULL
       AND (user_id = ? OR user_id = '')
       AND conversation_origin = 'import'
     ORDER BY COALESCE(last_activity_at, created_at) DESC, id ASC
  `).all(userId);
  db.close();

  log(`侧边栏可见的导入会话：${rows.length} 条（user_id=${String(userId).slice(0, 8)}…）`);

  const bad = [];
  let ok = 0;
  for (const r of rows) {
    const file = path.join(dir, `${r.id}.jsonl`);
    const problems = [];

    if (!existsSync(file)) {
      problems.push('正文文件不存在');
    } else {
      let msgs = 0; let hasTitle = false; let wrongSession = 0;
      for (const line of readFileSync(file, 'utf8').split('\n')) {
        if (!line.trim()) continue;
        let o;
        try { o = JSON.parse(line); } catch { problems.push('有坏行（JSON 解析失败）'); break; }
        if (o.type === 'message') {
          msgs += 1;
          if (o.sessionId !== r.id) wrongSession += 1;
        }
        if (o.type === 'ai-title' && o.aiTitle) hasTitle = true;
      }
      if (!msgs) problems.push('一条消息都没有');
      if (!hasTitle) problems.push('缺 ai-title');
      if (wrongSession) problems.push(`${wrongSession} 条消息的 sessionId 对不上`);
    }

    if (problems.length) {
      bad.push({ id: r.id, title: r.title, problems });
      log(`  ✗ ${r.id.slice(0, 8)}  ${problems.join('；')}`);
    } else {
      ok += 1;
      const ts = new Date(r.last_activity_at || r.created_at).toISOString().slice(0, 16).replace('T', ' ');
      log(`  ✓ ${r.id.slice(0, 8)}  ${ts}  ${String(r.title).slice(0, 36)}`);
    }
  }

  log(`\n${bad.length ? '有问题' : '全部通过'}：${ok}/${rows.length}`);
  if (bad.length) log('修法：dsh-see-on-phone import --wb --repair（id 和 DB 行都不动，只重写正文）');
  return { visible: rows.length, ok, bad };
}

/* ------------------------------------------------------------------ 修复 */

/**
 * 把已导入会话的 `cwd` 对齐成 `workspaces` 表里的写法（斜杠 / 大小写）。
 *
 * 只改自己导进去的行，id、正文、时间都不动。为什么需要它见 `canonicalCwd()`。
 */
export function fixImportCwd(opts = {}) {
  const log = opts.log || console.log;
  const dry = !!opts.dry;
  const db = openDb(false);

  const rows = db.prepare(`
    SELECT id, cwd, title FROM sessions
     WHERE conversation_origin = 'import' AND transport = 'local' AND deleted_at IS NULL
  `).all();

  const plan = rows
    .map((r) => ({ id: r.id, from: r.cwd, to: canonicalCwd(db, r.cwd), title: r.title }))
    .filter((p) => p.to !== p.from);

  if (!plan.length) {
    db.close();
    log(`导入的 ${rows.length} 条会话，cwd 已经都是工作区表里的写法，不用改。`);
    return { fixed: 0 };
  }

  if (!dry) {
    mkdirSync(BACKUP_DIR, { recursive: true });
    const f = path.join(BACKUP_DIR, `workbuddy-${new Date().toISOString().replace(/[:.]/g, '-')}.db`);
    copyFileSync(DB_FILE, f);
    log(`已备份数据库 → ${f}`);
  }

  const upd = db.prepare('UPDATE sessions SET cwd = ? WHERE id = ?');
  for (const p of plan) {
    log(`  ${dry ? '~' : '✓'} ${p.id.slice(0, 8)}  ${p.from}  →  ${p.to}`);
    if (!dry) upd.run(p.to, p.id);
  }
  db.close();

  log(`\n${dry ? '（dry run）' : ''}对齐 ${plan.length} 条`);
  return { fixed: plan.length };
}

/**
 * 已导会话的正文按当前格式重写 —— **id、时间戳、会话归属都不动**。
 *
 * 用途：这次格式升级（补 sessionId / cwd / parentId 链）之前导进去的那批，
 * 列表能看见但正文可能是"半同构"的。跑一次就行，不会多出会话。
 *
 * 顺手会把标题里那句 `（导出的图已省略…）` 洗掉 —— 那是给正文看的提示，
 * 不该挂在侧边栏标题上。`cleanTitles: false` 可以关掉。
 */
export function repairImports(opts = {}) {
  const log = opts.log || console.log;
  const src = path.resolve(opts.src || DEFAULT_SRC());
  const cwd = String(opts.cwd || DEFAULT_CWD()).replace(/\\/g, '/');
  const dry = !!opts.dry;
  const cleanTitles = opts.cleanTitles !== false;

  const slug = slugFor(cwd);
  const dir = path.join(PROJECTS, slug);
  const map = loadMap(src);
  const byWb = new Map(Object.entries(map).map(([conv, wb]) => [wb, conv]));

  const db = openDb(false);
  const rows = db.prepare(`
    SELECT id, title FROM sessions
     WHERE transport = 'local' AND deleted_at IS NULL AND conversation_origin = 'import'
  `).all();

  if (!rows.length) { db.close(); log('没有导入的会话，不用修。'); return { fixed: 0 }; }

  const byConvId = new Map(listPacks(src).map((p) => [p.id, p]));
  let fixed = 0; let missing = 0; let retitled = 0;

  // 要改数据就先把库备一份（和导入同一个备份目录）
  if (!dry) {
    mkdirSync(BACKUP_DIR, { recursive: true });
    const f = path.join(BACKUP_DIR, `workbuddy-${new Date().toISOString().replace(/[:.]/g, '-')}.db`);
    copyFileSync(DB_FILE, f);
    log(`已备份数据库 → ${f}`);
  }

  try {
    for (const r of rows) {
      const conv = byWb.get(r.id);
      if (!conv) { log(`  ? ${r.id.slice(0, 8)} 账本里没有来源，跳过`); missing += 1; continue; }
      const u = byConvId.get(conv);
      if (!u) { log(`  ? ${r.id.slice(0, 8)} 源包 ${conv} 不在 ${src}，跳过`); missing += 1; continue; }

      const msgs = u.msgs;
      if (!msgs.length) { log(`  ? ${r.id.slice(0, 8)} 源包没消息，跳过`); missing += 1; continue; }

      const clean = titleOf(msgs);
      const oldTitle = String(r.title || '');
      const dirty = oldTitle.replace(IMG_NOTE, '') !== oldTitle;
      const wantTitle = cleanTitles && dirty ? clean : null;

      if (dry) {
        log(`  ~ ${r.id.slice(0, 8)} 会重写（${msgs.length} 条）${wantTitle ? `，标题改成「${wantTitle}」` : ''}`);
        fixed += 1;
        continue;
      }

      mkdirSync(dir, { recursive: true });
      writeFileSync(path.join(dir, `${r.id}.jsonl`), buildJsonl(r.id, cwd, msgs));

      if (wantTitle) {
        db.prepare('UPDATE sessions SET title = ?, custom_title = ? WHERE id = ?').run(wantTitle, wantTitle, r.id);
        retitled += 1;
      }
      log(`  ✓ ${r.id.slice(0, 8)} 已重写（${msgs.length} 条）${wantTitle ? `　标题 → ${wantTitle}` : ''}`);
      fixed += 1;
    }
  } finally {
    db.close();
  }

  log(`\n${dry ? '（dry run）' : ''}重写 ${fixed} 条${retitled ? `，洗掉标题占位 ${retitled} 条` : ''}${missing ? `，跳过 ${missing} 条` : ''}`);
  return { fixed, missing, retitled };
}

/* ------------------------------------------------------------------ 列表 */

export function listImports(opts = {}) {
  const log = opts.log || console.log;
  const src = path.resolve(opts.src || DEFAULT_SRC());
  const map = loadMap(src);
  const keys = Object.keys(map);
  log(`已导入 ${keys.length} 个手机包（账本：${mapPath(src)}）`);
  for (const [k, v] of Object.entries(map)) log(`  ${k} → ${v}`);
  return map;
}

/* ------------------------------------------------------------------ CLI */

function parseArgs(argv) {
  const flag = (n) => argv.includes('--' + n);
  const opt = (n) => { const i = argv.indexOf('--' + n); return i >= 0 ? argv[i + 1] : undefined; };
  return {
    dry: flag('dry'), list: flag('list'), force: flag('force'),
    verify: flag('verify'), repair: flag('repair'), fixCwd: flag('fix-cwd'),
    src: opt('src'), cwd: opt('cwd'),
  };
}

function main(argv) {
  const a = parseArgs(argv);
  const opts = { src: a.src, cwd: a.cwd, dry: a.dry, force: a.force };
  if (a.verify) return verifyImports(opts);
  if (a.repair) return repairImports(opts);
  if (a.fixCwd) return fixImportCwd(opts);
  if (a.list) return listImports(opts);
  return importPackages(opts);
}

// 只有"直接跑"才执行 CLI；被 phone-bridge.mjs import 时不动
const isDirect = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isDirect) {
  try {
    main(process.argv.slice(2));
  } catch (e) {
    console.error('出错：', e.message);
    process.exit(1);
  }
}
