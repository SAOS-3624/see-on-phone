/**
 * phone-pack.mjs —— **读手机导出的会话包**，两条导入线共用的唯一入口。
 *
 * 为什么单独拆出来：手机上「发到电脑」和全量导出有两种落盘形状，TSV / JSONL 两种行格式。
 * 这些解析以前在两条导入线里各写了一遍，而且**行为还不一样**
 * （一份带"图已省略"占位、一份不带；一份按 id 去重、一份不去重）。
 * 结果就是同一个包导进 WorkBuddy 和 DSH，正文可能不同、DSH 那边还会重复导两次。
 * 现在只留这一份，谁要导谁调它。
 *
 * 认三种输入形状（都在 `<src>` 或 `<src>/export/` 下）：
 *
 *   conv-<id>/            meta.json + conv.jsonl (+ summary.txt)   手机「发到电脑」的单包
 *   conv/<id>.jsonl       TSV 或 JSONL (+ <id>.sum)                 全量导出
 *   export/ 那层          `adb pull` 下来会多一层 export/           局域网解 zip 没有
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';

/** 正文里那句"图已省略"是给人看的；标题要剥掉它（截断时右括号可能没了，所以可选）。 */
export const IMG_NOTE = /（导出的图已省略[^）]*）?/g;

/* ---------------------------------------------------------------- 解析 */

/**
 * 解析一段会话正文。两种行格式混着也能吃：
 *   - JSONL：`{at, role, text}`（新版本）
 *   - TSV  ：`at \t role \t text \t image \t reasoning \t origin`（老版本）
 *
 * @returns {Array<{at:number, role:'user'|'assistant', text:string}>}
 */
export function parseConversation(text) {
  const out = [];
  for (const raw of text.split('\n')) {
    const line = raw.replace(/\r$/, '');
    if (!line.trim()) continue;
    const msg = line.startsWith('{') ? fromJsonLine(line) : fromTsvLine(line);
    if (msg) out.push(msg);
  }
  return out;
}

function fromJsonLine(line) {
  let o;
  try { o = JSON.parse(line); } catch { return null; }
  if (o.role !== 'user' && o.role !== 'assistant') return null;
  if (typeof o.text !== 'string' || !o.text) return null;
  return { at: Number(o.at) || Date.now(), role: o.role, text: o.text };
}

function fromTsvLine(line) {
  const f = line.split('\t');
  if (f.length < 3) return null;
  const role = (f[1] || '').trim();
  if (role !== 'user' && role !== 'assistant') return null;

  let text = f[2] || '';
  // 图片那列是 `路径|帧数|为什么|token`：正文里补一句，让他知道这轮看过图
  if (f[3] && f[3].includes('|')) {
    // 帧数 = 0 是**相册图**（1.3.18 起相册图不再冒充"1 帧"），别写成"约 ? 帧"
    const frames = Number(f[3].split('|')[1]) || 0;
    text += `${text ? '\n\n' : ''}（导出的图已省略：${frames > 0 ? `约 ${frames} 帧` : '相册图'}）`;
  }
  if (!text) return null;
  return { at: Number(f[0]) || Date.now(), role, text };
}

/** 从消息里取一个能当标题的句子：剥掉图片占位、压空白、截断。 */
export function titleOf(msgs, fallback = '手机导入') {
  const raw = msgs.find((m) => m.role === 'user')?.text || '';
  return raw.replace(IMG_NOTE, '').replace(/\s+/g, ' ').trim().slice(0, 40) || fallback;
}

/* ---------------------------------------------------------------- 收集 */

/**
 * 列出某个目录下所有可导的会话包。
 *
 * 同一个 convId 同时存在单包目录和全量文件时**只保留一个**（单包优先：
 * 它带 `meta.json` 的标题和 `summary.txt`）。这是之前 DSH 线重复导入的根因。
 *
 * @param {string} srcDir
 * @returns {Array<{id:string, meta:object, msgs:Array, summary:string, from:string}>}
 */
export function listPacks(srcDir) {
  const packs = [];
  const seen = new Set();

  const add = (pack) => {
    if (!pack.msgs.length || seen.has(pack.id)) return;
    seen.add(pack.id);
    packs.push(pack);
  };

  for (const base of packRoots(srcDir)) {
    // ① 单会话包：conv-<id>/{meta.json, conv.jsonl, summary.txt}
    for (const name of safeReaddir(base)) {
      if (!name.startsWith('conv-')) continue;
      const dir = path.join(base, name);
      if (!isDir(dir)) continue;
      const conv = path.join(dir, 'conv.jsonl');
      if (!existsSync(conv)) continue;

      let meta = {};
      try { meta = JSON.parse(readFileSync(path.join(dir, 'meta.json'), 'utf8')); } catch { /* 可选 */ }

      add({
        id: meta.convId || name.slice('conv-'.length),
        meta,
        msgs: parseConversation(readFileSync(conv, 'utf8')),
        summary: readIf(path.join(dir, 'summary.txt')).trim(),
        from: dir,
      });
    }

    // ② 全量导出：conv/<id>.jsonl（TSV）+ 同名 .sum（首行是水位）
    const bulk = path.join(base, 'conv');
    for (const file of safeReaddir(bulk)) {
      if (!file.endsWith('.jsonl')) continue;
      const full = path.join(bulk, file);
      const sum = readIf(full.replace(/\.jsonl$/, '.sum'));
      add({
        id: file.slice(0, -'.jsonl'.length),
        meta: {},
        msgs: parseConversation(readFileSync(full, 'utf8')),
        summary: sum ? sum.split('\n').slice(1).join('\n').trim() : '',
        from: full,
      });
    }
  }
  return packs;
}

/** `adb pull` 下来会多一层 `export/`，局域网解 zip 没有。两层都看。 */
function packRoots(srcDir) {
  const roots = [srcDir];
  const nested = path.join(srcDir, 'export');
  if (existsSync(nested)) roots.push(nested);
  return roots;
}

/* ---------------------------------------------------------------- 杂项 */

function safeReaddir(dir) {
  try { return readdirSync(dir); } catch { return []; }
}

function isDir(p) {
  try { return statSync(p).isDirectory(); } catch { return false; }
}

function readIf(file) {
  try { return readFileSync(file, 'utf8'); } catch { return ''; }
}
