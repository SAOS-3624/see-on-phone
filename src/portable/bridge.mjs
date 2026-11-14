#!/usr/bin/env node
/**
 * bridge.mjs —— 「鱼眼」App 的会话 / 记忆和电脑之间的搬运。
 *
 * ```
 * dsh-see-on-phone pull            手机 → 电脑（默认落到 ~/.dsh/phone-data/）
 * dsh-see-on-phone push            电脑 → 手机（默认从 ~/.dsh/phone-data/）
 * dsh-see-on-phone status          看一眼手机那头什么情况
 * dsh-see-on-phone export latest   把电脑上最近改过的那条 DSH 会话转成手机格式
 *
 * dsh-see-on-phone import          手机包 → **DSH**（默认）
 * dsh-see-on-phone import --wb     手机包 → **WorkBuddy 侧边栏**
 * dsh-see-on-phone import --both   两边都写
 * ```
 *
 * ## 两条导入线，别搞混
 *
 * | 目标 | 存储 | 谁看它 |
 * |---|---|---|
 * | DSH | `~/.dsh/sessions/` + `workspace.json` + `session_projcache` | `dsh web` / DSH 桌面版 |
 * | WorkBuddy 侧边栏 | `~/.workbuddy/workbuddy.db` + `projects/<slug>/*.jsonl` | WorkBuddy 桌面端 |
 *
 * 两边格式完全不同（一个是多帧 zstd，一个是普通 JSONL），所以各有一个导入器：
 * `to-dsh.mjs` 和 `to-workbuddy.mjs`。本文件只负责**拉包、推包、分发**。
 *
 * ## 为什么是"目录"不是"打包"
 *
 * 手机那头把数据**摊平**成一个目录（`Download/DSH-Go/export/`），这里 `adb pull` 一个目录
 * 就完事 —— 不用在电脑上装 zip 库、不用 base64、出问题拿记事本就能看。
 * 手机另外顺手打了一个 `phone-*.zip`，那是给你发微信用的，这条路不用它。
 *
 * ## 合并规则（在手机那侧执行，见 `Portable.java`）
 *
 * - 会话 jsonl：按时间戳**增量追加**（只补 local 没有的），所以来回搬不会重复也不会丢
 * - 会话图片：只补缺的
 * - 记忆：本地没有就写；内容不同**不覆盖**，另存 `import/skipped/` 并报出来
 *   （记忆每轮都进 prompt，悄悄覆盖最容易出事）
 *
 * 依赖：只要 `adb`（手机连上、USB 调试开着）。Node 18+ 自带 fetch。
 */

import { spawn } from 'node:child_process';
import { zstdDecompressSync, inflateRawSync } from 'node:zlib';
import { mkdirSync, existsSync, rmSync, readdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { importToDsh, loadMap } from './to-dsh.mjs';
import { adbBin, dataDir, projectDir, ADB_HINT } from './paths.mjs';

/* ------------------------------------------------------------ 环境 */

const HOST_PORT = 14444;
const REMOTE_BASE = '/sdcard/Download/DSH-Go';

/**
 * 本次调用的参数。
 *
 * ⚠️ **不在模块顶层解析 `process.argv`** —— 这个文件会被 `cli.mjs` import，
 * 顶层解析等于"一 import 就把命令行跑一遍"。
 */
let ARGS = [];

function valueOf(flag) {
  const i = ARGS.indexOf(flag);
  return i >= 0 ? ARGS[i + 1] : null;
}

/** 局域网地址：`--host 192.168.1.5:4444`。给了就不走 adb。 */
const hostArg = () => valueOf('--host');

/** 手机 HTTP 的根地址。 */
function phoneBase() {
  const h = hostArg();
  if (!h) return `http://127.0.0.1:${HOST_PORT}`;
  return h.startsWith('http') ? h : `http://${h}`;
}

/** 手机包的落地目录（`--dir` 可覆盖）。 */
const dirArg = () => path.resolve(valueOf('--dir') || dataDir());

/* ------------------------------------------------------------ 进程 / HTTP */

function run(bin, argv, timeoutMs = 90_000) {
  return new Promise((resolve, reject) => {
    const p = spawn(bin, argv, { stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    let err = '';
    const t = setTimeout(() => { p.kill(); reject(new Error(`${bin} 超时（${timeoutMs}ms）`)); }, timeoutMs);
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('error', (e) => {
      clearTimeout(t);
      // ENOENT 只会说 "spawn adb ENOENT"，看不出是"没装 adb" —— 换成能照做的话
      if (e?.code === 'ENOENT') reject(new Error(ADB_HINT));
      else reject(e);
    });
    p.on('close', (code) => {
      clearTimeout(t);
      if (code === 0) resolve(out.trim());
      else reject(new Error(`${bin} 退出码 ${code}\n${err || out}`));
    });
  });
}

/**
 * `-s <序列号>` / `--serial <序列号>`：指定连哪台手机。
 *
 * ⚠️ 插两台（或 USB + 无线同时在线）时，adb 不带 `-s` 一律报
 * `more than one device/emulator` —— 看上去像"手机没连"，其实是没指名道姓。
 * 序列号从 `adb devices` 第一列拿。
 */
const serialArg = () => valueOf('-s') || valueOf('--serial');
const adb = (...a) => {
  const s = serialArg();
  return run(adbBin(), s ? ['-s', s, ...a] : a);
};

async function get(pathname, timeoutMs = 120_000) {
  const r = await fetch(`${phoneBase()}${pathname}`, { signal: AbortSignal.timeout(timeoutMs) });
  return r.text();
}

/** 探活：局域网直接连；插线走 adb forward，然后等手机那边的服务起来。 */
async function ensurePhone() {
  if (hostArg()) {
    for (let i = 0; i < 3; i += 1) {
      try {
        const t = await get('/status', 5000);
        if (t.includes('"version"')) return JSON.parse(t);
      } catch { /* 再试 */ }
      await new Promise((r) => setTimeout(r, 1000));
    }
    throw new Error(`连不上 ${phoneBase()}。检查：手机开了「局域网」开关、同一个 WiFi、IP 没变`);
  }

  await adb('get-state');
  await adb('forward', `tcp:${HOST_PORT}`, 'tcp:4444');
  for (let i = 0; i < 10; i += 1) {
    try {
      const t = await get('/status', 4000);
      if (t.includes('"version"')) return JSON.parse(t);
    } catch { /* 还没起 */ }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error('手机上的服务没响应：确认「看见」App 正在运行');
}

/** 数一个目录里指定后缀的文件（不递归）。 */
function countFiles(dir, ext) {
  try {
    return readdirSync(dir).filter((n) => n.endsWith(ext)).length;
  } catch {
    return 0;
  }
}

/* ------------------------------------------------------------ 拉 / 推 */

async function pull() {
  // 局域网模式：不走 adb pull，改让手机把 zip 以 base64 吐出来，一条 HTTP 传完。
  // 前提是导出时图片已经全部 offload 成文字，包只有几十 KB。
  if (hostArg()) return pullOverHttp();

  const st = await ensurePhone();
  console.log(`手机：v${st.version} · ${st.convMsgs} 条消息`);
  const r = JSON.parse(await get('/export'));
  if (!r.ok) throw new Error(r.error || '导出失败');
  console.log(`导出：会话 ${r.convs} 条 / 记忆 ${r.memory} 个（${(r.bytes / 1024).toFixed(0)}KB）`);

  mkdirSync(dirArg(), { recursive: true });
  const landed = path.join(dirArg(), 'export');
  // ⚠️ **必须先把本地那份删掉**：`adb pull` 只覆盖同名文件、**不会删除**手机侧已经没有的。
  //    不清的话，你在手机上删掉的会话会一直堆在电脑上；下次 push 又会把它们"复活"回去。
  if (existsSync(landed)) rmSync(landed, { recursive: true, force: true });

  await adb('pull', `${REMOTE_BASE}/export`, dirArg());
  const here = existsSync(path.join(landed, 'conv')) ? landed : dirArg();
  console.log(`已拉到：${here}`);

  // 落地之后对账：数量对不上说明中间出了问题，别让他以为拉全了
  const got = countFiles(path.join(here, 'conv'), '.jsonl');
  if (typeof r.convs === 'number' && got !== r.convs) {
    console.log(`⚠️ 会话数对不上：手机说 ${r.convs}，落地 ${got}。再拉一次试试。`);
  }
  console.log(`（zip 也在手机上：${r.zip}，发给别人用那个）`);
  console.log('下一步：dsh-see-on-phone import   → 进 WorkBuddy 侧边栏');
  console.log('      或：dsh-see-on-phone import --dsh → 进 DSH');
}

async function push() {
  // 局域网模式：不走 adb push，直接 POST 给手机的 /import（手机按 at 增量合并）
  if (hostArg()) return pushOverHttp();

  await ensurePhone();
  if (!existsSync(dirArg())) throw new Error(`没有这个目录：${dirArg()}`);

  // 反向产物在 `push/conv/`，全量导出在 `export/conv/`，两个都认
  const src = existsSync(path.join(dirArg(), 'push', 'conv')) ? path.join(dirArg(), 'push')
    : existsSync(path.join(dirArg(), 'conv')) ? dirArg() : path.join(dirArg(), 'export');
  if (!existsSync(path.join(src, 'conv')) && !existsSync(path.join(src, 'memory'))) {
    throw new Error(`${src} 里既没有 conv/ 也没有 memory/，不像一份导出的数据`);
  }

  // 先清空远端 import（避免上一轮的旧文件被重复合并）
  await adb('shell', `rm -rf ${REMOTE_BASE}/import`);
  await adb('shell', `mkdir -p ${REMOTE_BASE}/import`);
  await adb('push', src, `${REMOTE_BASE}/import`);

  // adb push 目录会把目录名带进去，统一摊平成 import/conv、import/memory
  const leaf = path.basename(src);
  if (leaf && leaf !== 'import') {
    await adb('shell',
      `if [ -d ${REMOTE_BASE}/import/${leaf} ]; then `
      + `mv ${REMOTE_BASE}/import/${leaf}/* ${REMOTE_BASE}/import/ 2>/dev/null; `
      + `rmdir ${REMOTE_BASE}/import/${leaf} 2>/dev/null; fi`);
  }

  const r = JSON.parse(await get('/import', 180_000));
  if (!r.ok) throw new Error(r.error || '导入失败');
  console.log(`导入：新会话 ${r.convNew} · 追加 ${r.convAppend} 条 · `
    + `新记忆 ${r.memNew} · 相同 ${r.memSame} · 图片 ${r.images}`);
  if (r.memConflict > 0) {
    console.log(`⚠️ 有 ${r.memConflict} 个记忆同名但内容不同，**没覆盖手机上的**：`);
    for (const n of r.conflicts) console.log(`   - ${n}`);
    console.log(`   电脑这份放在手机 ${r.skippedDir}/，要哪个你自己定。`);
  }
}

async function pushOverHttp() {
  const st = await ensurePhone();
  const dir = path.join(dirArg(), 'push', 'conv');
  if (!existsSync(dir)) {
    throw new Error(`没有要推的内容。先导出：\n  dsh-see-on-phone export latest`);
  }
  const files = readdirSync(dir).filter((n) => n.endsWith('.jsonl'));
  if (!files.length) throw new Error(`${dir} 里没有 .jsonl，先 export 一次`);

  console.log(`手机：v${st.version}（局域网 ${phoneBase()}）`);
  let total = 0;
  for (const n of files) {
    const res = await fetch(`${phoneBase()}/import?name=${encodeURIComponent(n)}`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: readFileSync(path.join(dir, n)),
    });
    const txt = await res.text();
    let j;
    try { j = JSON.parse(txt); } catch { throw new Error(`手机回的不是 JSON：${txt.slice(0, 120)}`); }
    if (!j.ok) throw new Error(j.error || '手机说导入失败');
    total += j.added || 0;
    console.log(`✓ ${n}　追加 ${j.added} 条${j.added ? '' : '（手机上已有，没新增）'}`);
  }
  console.log(`\n共追加 ${total} 条。手机打开那段对话就能看到（不插线，走 HTTP）。`);
}

/* ------------------------------------------------------------ 导入分发 */

/**
 * `import` 的统一入口：默认进 WorkBuddy，`--dsh` 进 DSH，`--both` 两边都写。
 *
 * 两个导入器都依赖 `node:sqlite`（读 workbuddy.db）。用动态 import 是为了让
 * 老 Node 上 `pull`/`push` 这些跟它无关的命令还能用。
 */
async function importAll(argv) {
  const force = argv.includes('--force');
  const dry = argv.includes('--dry');
  /**
   * 导到哪。
   *
   * ⚠️ **默认是 DSH**（用户装的是 DSH 插件），不是 WorkBuddy。
   * 反向默认会让没装 WorkBuddy 的人一上来就 `找不到 workbuddy.db`。
   */
  const toWb = argv.includes('--wb') || argv.includes('--both');
  const toDsh = !argv.includes('--wb');

  // 项目根：`--cwd` 优先，否则"在哪个目录里跑就导进哪个项目"
  const cwd = (valueOf('--cwd') || projectDir()).replace(/\\/g, '/');

  if (toWb) {
    const { importPackages } = await loadImporter('./to-workbuddy.mjs', 'WorkBuddy');
    const r = importPackages({ src: dirArg(), cwd, force, dry });
    if (!r.added && !r.skipped && !dry) {
      console.log('没有可导的会话 —— 先 pull 一次，或在手机上长按会话点「发到电脑」。');
    }
  }

  if (toDsh) {
    if (toWb) console.log('\n--- 同时写 DSH ---');
    const dsh = await loadImporter('./to-dsh.mjs', 'DSH');
    // DSH 线的 cwd 用原始形态（反斜杠），它按这个拼项目目录名
    await dsh.importToDsh({ src: dirArg(), cwd: valueOf('--cwd') || projectDir(), force, dry });
  }
}

async function loadImporter(spec, label) {
  try {
    return await import(spec);
  } catch (e) {
    throw new Error(`${label} 导入器加载失败（需要 Node 22.5+，node:sqlite）：${e.message}`);
  }
}

/* ------------------------------------------------------------ 反向：DSH → 手机 */

/** 解一个 DSH 会话的全部记录（多帧 zstd 按魔数切片）。 */
function readDshSession(file) {
  const buf = readFileSync(file);
  const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
  const offsets = [];
  for (let i = 0; i + 4 <= buf.length; i += 1) if (buf.compare(MAGIC, 0, 4, i, i + 4) === 0) offsets.push(i);

  let text = '';
  for (let k = 0; k < offsets.length; k += 1) {
    const end = k + 1 < offsets.length ? offsets[k + 1] : buf.length;
    try { text += zstdDecompressSync(buf.subarray(offsets[k], end)).toString('utf8'); } catch { /* 坏帧跳过 */ }
  }
  return text.split('\n').filter(Boolean)
    .map((l) => { try { return JSON.parse(l); } catch { return null; } })
    .filter(Boolean);
}

/** 从记录里挑出"人说的话"和"它说的话"，丢掉工具/系统/请求头那些。 */
function toPhoneLines(recs) {
  const out = [];
  for (const r of recs) {
    if (r.type !== 'user/message' && r.type !== 'assistant/message') continue;
    const content = r.type === 'user/message' ? r.data?.content : r.data?.message?.content;
    if (!Array.isArray(content)) continue;
    const text = content
      .filter((c) => c?.type === 'text' && typeof c.text === 'string')
      .map((c) => c.text).join('');
    if (!text.trim()) continue;
    // 手机格式：at \t role \t text \t image \t reasoning \t origin
    // 正文里的换行/制表符必须转义，否则一条消息会被切成好几行
    out.push([String(Number(r.time) || Date.now()), r.type === 'user/message' ? 'user' : 'assistant',
      text.replace(/\t/g, ' ').replace(/\r?\n/g, '\\n'), '', '', 'pc'].join('\t'));
  }
  return out;
}

function findSessionFile(arg) {
  const root = path.join(os.homedir(), '.dsh', 'sessions');
  const projects = readdirSync(root).map((n) => path.join(root, n));

  if (arg && /^(session-)?[0-9a-f-]{8,}$/i.test(arg)) {
    for (const d of projects) {
      const hit = readdirSync(d).find((c) => c.startsWith(arg));
      if (hit) return path.join(d, hit, 'session.v4.jsonl.zstd');
    }
  }

  // 没指定 / latest：挑修改时间最新的
  let best = null;
  for (const d of projects) {
    let subs = [];
    try { subs = readdirSync(d); } catch { continue; }
    for (const c of subs) {
      const f = path.join(d, c, 'session.v4.jsonl.zstd');
      if (!existsSync(f)) continue;
      const st = statSync(f).mtimeMs;
      if (!best || st > best.mtime) best = { file: f, mtime: st };
    }
  }
  return best?.file ?? null;
}

async function exportToPhone(sessionArg) {
  const file = findSessionFile(sessionArg || 'latest');
  if (!file) throw new Error('没找到 DSH 会话。用法：export <sessionId|latest>');

  const recs = readDshSession(file);
  const dshId = recs.find((r) => r.type === 'session')?.id ?? path.basename(path.dirname(file));
  const convId = loadMap(dirArg())[dshId]?.convId || `pc-${Date.now()}`;
  const lines = toPhoneLines(recs);
  if (!lines.length) throw new Error('这条会话里没有可搬的对话（只有工具调用？）');

  const outDir = path.join(dirArg(), 'push', 'conv');
  mkdirSync(outDir, { recursive: true });
  const outFile = path.join(outDir, `${convId}.jsonl`);
  writeFileSync(outFile, lines.join('\n') + '\n');

  console.log(`DSH 会话 ${dshId.replace(/^session-/, '').slice(0, 8)} → 手机会话 ${convId}`);
  console.log(`  ${lines.length} 条消息（工具调用/系统提示已丢弃）`);
  console.log(`  写成：${outFile}`);
  // `push` 推的是**整个目录**，不挑文件。导出过几次就攒了几个，这里说清楚，免得推过去一堆旧的。
  const pending = readdirSync(outDir).filter((n) => n.endsWith('.jsonl')).length;
  console.log(`\n待推清单里现在有 ${pending} 个会话（push 会全部推过去）。`);
  console.log('下一步：dsh-see-on-phone push');
}

/* ------------------------------------------------------------ zip（局域网拉包） */

/**
 * 解 zip —— **自己解，不调外部命令**。
 *
 * 踩过：Git Bash 里 `tar` 是 GNU tar 1.35，它**不支持 zip**（只有 Windows 自带的
 * bsdtar 支持）。环境不同、工具不同，这类"看起来能跑"的依赖最容易在用户机器上翻车。
 */
function unzipTo(buf, dir) {
  // ① 先找 EOCD（文件尾的中央目录索引）。它有 22 字节固定头，后面可能跟注释。
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66560); i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('不是有效的 zip（找不到 EOCD）');

  const total = buf.readUInt16LE(eocd + 10);
  let cd = buf.readUInt32LE(eocd + 16);

  // ② 走中央目录：这里才有**准确的 csize / 本地头偏移**。
  //    不能只读 local file header —— Java 的 ZipOutputStream 会把尺寸留在
  //    data descriptor 里（头里是 0），照着读就会当场断在第一个文件之后。
  let n = 0;
  for (let k = 0; k < total; k += 1) {
    if (buf.readUInt32LE(cd) !== 0x02014b50) break;
    const method = buf.readUInt16LE(cd + 10);
    const csize = buf.readUInt32LE(cd + 20);
    const namelen = buf.readUInt16LE(cd + 28);
    const extralen = buf.readUInt16LE(cd + 30);
    const commentlen = buf.readUInt16LE(cd + 32);
    const lho = buf.readUInt32LE(cd + 42);
    const name = buf.subarray(cd + 46, cd + 46 + namelen).toString('utf8');

    // 数据起点 = 本地头 + 本地头长度 + 文件名 + 扩展区
    const dataAt = lho + 30 + buf.readUInt16LE(lho + 26) + buf.readUInt16LE(lho + 28);

    if (!name.endsWith('/')) {
      const raw = buf.subarray(dataAt, dataAt + csize);
      const dest = path.join(dir, name);
      mkdirSync(path.dirname(dest), { recursive: true });
      writeFileSync(dest, method === 8 ? inflateRawSync(raw) : raw);
      n += 1;
    }
    cd += 46 + namelen + extralen + commentlen;
  }
  return n;
}

async function pullOverHttp() {
  const st = await ensurePhone();
  console.log(`手机：v${st.version} · ${st.convMsgs} 条消息（局域网 ${phoneBase()}）`);

  const raw = await get('/export?b64=1', 180_000);
  let r;
  try { r = JSON.parse(raw); } catch { throw new Error('手机回的导出结果不是 JSON：' + raw.slice(0, 120)); }
  if (!r.ok) throw new Error(r.error || '导出失败');
  if (!r.zip64) throw new Error('手机没给 zip64（这版 App 还没有 /export?b64=1，先插线升级）');

  const landed = path.join(dirArg(), 'export');
  if (existsSync(landed)) rmSync(landed, { recursive: true, force: true });
  mkdirSync(dirArg(), { recursive: true });

  const zip = path.join(dirArg(), `pull-${Date.now()}.zip`);
  writeFileSync(zip, Buffer.from(r.zip64, 'base64'));
  console.log(`导出：会话 ${r.convs} / 记忆 ${r.memory} / ${(r.zipBytes / 1024).toFixed(0)}KB`);

  const files = unzipTo(readFileSync(zip), dirArg());
  rmSync(zip, { force: true });
  if (!files) throw new Error('解压出 0 个文件（zip 结构不对？）');
  console.log(`解开 ${files} 个文件`);

  const here = existsSync(path.join(dirArg(), 'export', 'conv')) ? path.join(dirArg(), 'export') : dirArg();
  const got = countFiles(path.join(here, 'conv'), '.jsonl');
  if (typeof r.convs === 'number' && got !== r.convs) {
    console.log(`⚠️ 会话数对不上：手机说 ${r.convs}，落地 ${got}`);
  }
  console.log(`已落到：${here}（不插线，走 HTTP）`);
}

/* ------------------------------------------------------------ 状态 / CLI */

async function status() {
  const st = await ensurePhone();
  console.log(JSON.stringify({
    version: st.version, convMsgs: st.convMsgs, ctxTokens: st.ctxTokens,
    cameraOpen: st.cameraOpen, think: st.think,
  }, null, 2));
}

export const COMMANDS = {
  pull,
  push,
  import: () => importAll(ARGS.slice(1)),
  export: () => exportToPhone(ARGS[1]),
  status,
};

/**
 * 跑一条子命令。
 *
 * @param {string[]} argv 不含命令名的那部分？**含**。`['pull', '--host', '...']`
 */
export async function runCommand(argv) {
  ARGS = argv.slice();
  const cmd = ARGS[0] || 'status';
  const handler = COMMANDS[cmd];
  if (!handler) {
    throw new Error(`不认识的子命令「${cmd}」。可用：${Object.keys(COMMANDS).join(' / ')}`);
  }
  await handler();
}
