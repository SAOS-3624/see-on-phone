/**
 * 修 DSH 会话文件（`~/.dsh/sessions/<项目>/<会话>/session.v4.jsonl.zstd`）。
 *
 * 为什么会有这个：DSH ≥0.1.7 的 V4 准入要求每条消息带 "producer-owned source"，
 * 而老版 `dsh-import.mjs` 写的 `user/message` / `assistant/message` 缺这些字段 ——
 * 表现就是列表里能看到、点开却「历史加载失败：… is corrupt」。DSH 自己升级后才发现。
 *
 * 补什么（对照 2 万条健康消息统计出来的实际形状）：
 *   · `user/message`      → `data.source{kind:"user"}` + `data.role` + `data.id` + `surfaceOp:"append"`
 *   · `assistant/message` → `message.source{kind:"model", provider, model}` + `message.id` + `surfaceOp`
 *     注意**光有 kind 不够**，会报 `message must have model source`；`replayState` / `usage` 可选。
 *   · `assistant/message` 的 `data` 还必须是 **`turn` + `step` + `stream`** 三件套（settlement fields），
 *     `stream` 必须是数组 —— 缺了报 `seed assistant/message at index N has invalid settlement fields`。
 *     ⚠️ 这条是 2026-11-14 另一个排查轮次补的：我当时统计"stream 可选"，样本里混进了**我们自己
 *     写坏的会话**（51 条全是它们），所以判错了。修正后全库 20563 条健康消息 **0 条缺 stream**。
 *
 * 用法：
 *   dsh-see-on-phone repair           # dry-run，只列出哪些会话要修
 *   dsh-see-on-phone repair --write   # 真写盘（每个文件先备份 .bak-<时间戳>）
 *
 * ⚠️ 写完必须**完全退出并重开 DSH**：损坏状态是进程级缓存，切会话不刷新。
 * ✅ 写完用 `dsh-see-on-phone doctor --all` 验（那支用 DSH 自己的 `Session.fromRestore`，
 *    和 DSH 打开会话是同一份代码）；这一支只负责"改"，不做判定。
 */
import { readFileSync, writeFileSync, readdirSync, existsSync, copyFileSync } from 'node:fs';
import { zstdCompressSync, zstdDecompressSync, constants as zc } from 'node:zlib';
import { randomUUID } from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import { dshHome } from './paths.mjs';

const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
const SEED_EVENTS = 4;

/** 会话根目录（`DSH_HOME` 可覆盖）。 */
const rootDir = () => path.join(dshHome(), 'sessions');

function readAll(file) {
  const buf = readFileSync(file);
  let offs = 0; const out = [];
  while (offs < buf.length) {
    const next = buf.indexOf(MAGIC, offs + 4);
    const end = next > 0 ? next : buf.length;
    try { out.push(zstdDecompressSync(buf.subarray(offs, end)).toString('utf8')); } catch { break; }
    offs = end;
  }
  return out.join('');
}
function encodeFrames(recs) {
  const opts = { params: { [zc.ZSTD_c_checksumFlag]: 1 } };
  const frame = (rows) => zstdCompressSync(
    Buffer.from(rows.map((r) => JSON.stringify(r)).join('\n') + '\n', 'utf8'), opts);
  const head = recs.filter((r) => r.type === 'session');
  const body = recs.filter((r) => r.type !== 'session');
  const frames = [frame(head), frame(body.slice(0, SEED_EVENTS))];
  for (let i = SEED_EVENTS; i < body.length; i += 20) frames.push(frame(body.slice(i, i + 20)));
  return Buffer.concat(frames);
}

/**
 * 扫全库、补字段。
 *
 * @param {string[]} argv `['--write']` 才真写盘；不带就是干跑
 * @returns {{scanned:number, broken:number, fixed:number}}
 */
export async function runCli(argv = []) {
const DRY = !argv.includes('--write');
const ROOT = rootDir();
let scanned = 0, broken = 0, fixed = 0;
for (const proj of readdirSync(ROOT)) {
  const pd = path.join(ROOT, proj);
  let subs; try { subs = readdirSync(pd); } catch { continue; }
  for (const sub of subs) {
    const file = path.join(pd, sub, 'session.v4.jsonl.zstd');
    if (!existsSync(file)) continue;
    scanned += 1;
    const text = readAll(file);
    if (!text) continue;
    const lines = text.split('\n').filter(Boolean);
    let recs = [], dirty = false;
    for (const l of lines) { try { recs.push(JSON.parse(l)); } catch { /* 坏行留着 */ } }
    for (const r of recs) {
      if (r.type === 'user/message') {
        r.data = r.data || {};
        if (!r.data.source?.kind) { r.data.source = { ...(r.data.source||{}), kind: 'user' }; dirty = true; }
        if (!r.data.role) { r.data.role = 'user'; dirty = true; }
        if (!r.data.id) { r.data.id = randomUUID(); dirty = true; }
        if (!r.surfaceOp) { r.surfaceOp = 'append'; dirty = true; }
      } else if (r.type === 'assistant/message') {
        r.data = r.data || {};
        // settlement 三件套：空数组是官方认可值（v0→v4 迁移对没有 chunk 的 attempt 就填 []）
        if (!Array.isArray(r.data.stream)) { r.data.stream = []; dirty = true; }
        const m = r.data.message;
        if (m) {
          // ⚠️ 光有 kind:'model' 不够 —— 校验要的是**完整的 model source**（provider + model），
          //    报错原文：`Session event at seq N message must have model source`
          const s0 = m.source || {};
          if (s0.kind !== 'model' || !s0.provider || !s0.model) {
            m.source = { kind: 'model', provider: 'deepseek-official', model: 'deepseek-flash', ...s0, kind: 'model' };
            if (!m.source.provider) m.source.provider = 'deepseek-official';
            if (!m.source.model) m.source.model = 'deepseek-flash';
            dirty = true;
          }
          if (!m.id) { m.id = randomUUID(); dirty = true; }
        }
        if (!r.surfaceOp) { r.surfaceOp = 'append'; dirty = true; }
      }
    }

    /**
     * `session/title` 的 `messageSeqs` 必须指向**更早的、`source.kind === 'user'` 的 `user/message`**。
     *
     * DSH 的 `titleSources()`（`dsh-session-format-v3-to-v4/src/relationships.ts`）逐条查：
     * 引用要早于 title 自己、类型是 user/message、来源是真人、且不重复。不满足就整条会话
     * 报 `stored log is corrupt: SessionFormatError: … must cite distinct earlier human user/message events`。
     *
     * 2026-11-14 那次 19 条全挂，根因是旧版 `dsh-import` 把 `firstUserSeq` 取在 `step/start` 之前，
     * 记成了 `[5]`（`step/start` 的 seq），而真正的第一条 user/message 在 7。
     * 这里按同样规则重映射 —— **只改这一个字段**，不动 seq、不动事件顺序。
     *
     * ⚠️ 必须放在上面那个循环**之后**：它要先给老 `user/message` 补上 `source.kind`，
     * 这里的 `citable()` 才判得出来。
     */
    const body = recs.filter((r) => r.type !== 'session');
    const bySeq = new Map(body.map((e) => [e.seq, e]));
    const citable = (e) => e && e.type === 'user/message' && e.data?.source?.kind === 'user';
    for (const ev of body) {
      if (ev.type !== 'session/title' && ev.type !== 'session/title-llm-request') continue;
      const refs = Array.isArray(ev.data?.messageSeqs) ? ev.data.messageSeqs : [];
      const humans = body.filter((e) => Number.isSafeInteger(e.seq) && e.seq < ev.seq && citable(e)).map((e) => e.seq);
      const out = [];
      for (const v of refs) {
        if (Number.isSafeInteger(v) && v < ev.seq && citable(bySeq.get(v)) && !out.includes(v)) { out.push(v); continue; }
        const fix = humans.find((h) => !out.includes(h));
        if (fix !== undefined) out.push(fix);
      }
      if (out.length === 0 && ev.data?.source?.kind !== 'user' && humans.length) out.push(humans[humans.length - 1]);
      if (JSON.stringify(out) !== JSON.stringify(refs)) {
        console.log(`      title#${ev.seq} messageSeqs ${JSON.stringify(refs)} → ${JSON.stringify(out)}`);
        ev.data.messageSeqs = out;
        dirty = true;
      }
    }
    if (!dirty) continue;
    broken += 1;
    console.log((DRY ? '[dry] ' : '[fix] ') + proj + '/' + sub.slice(0, 16) + ' 记录 ' + recs.length);
    if (!DRY) {
      copyFileSync(file, file + '.bak-' + Date.now());
      writeFileSync(file, encodeFrames(recs));
      fixed += 1;
    }
  }
}
console.log(`扫描 ${scanned} 个会话，需要修的 ${broken} 个` + (DRY ? '（dry-run，未写盘；加 --write 才改）' : `，已修 ${fixed}`));
if (!DRY && fixed) console.log('改完了：完全退出并重开 DSH 才能看到（列表和校验结果是进程级缓存）。');
return { scanned, broken, fixed };
}
