/**
 * portable.test.mjs —— 搬会话那条线的纯函数测试。
 *
 * 为什么只测这几块：`bridge` / `to-dsh` 要真手机、真 DSH 才能端到端跑，
 * 但**最容易悄悄改坏、而且修起来最疼**的是三件小事：
 *   ① 手机包的行格式（TSV / JSONL 混着来）
 *   ② 标题引用的合法性判定（2026-11 那次 19 条会话打不开就是这个）
 *   ③ 英文报错翻译成下一步动作
 * 这三块都是纯函数，拦住它们就等于拦住"导进去打不开"。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { explain } from '../src/portable/paths.mjs';
import { checkLightweight } from '../src/portable/doctor.mjs';
import { parseConversation, titleOf, IMG_NOTE } from '../src/portable/pack.mjs';

/* ---------------------------------------------------------------- 造数据 */

const header = { type: 'session', version: 4, id: 'session-x', createdAt: 1, isSeeded: false, delegationDepth: 0 };

/** 一条能通过检查的最小会话：seed → 一轮问答 → 标题（指向 seq 7 的 user/message）。 */
function healthy() {
  const events = [
    { type: 'permission/preset', seq: 0, data: {} },
    { type: 'sandbox/mode', seq: 1, data: {} },
    { type: 'approval/policy', seq: 2, data: {} },
    { type: 'session/end-seed', seq: 3, data: {} },
    { type: 'turn/start', seq: 4, data: { turn: 1 } },
    { type: 'step/start', seq: 5, data: { turn: 1, step: 1 } },
    { type: 'system/message', seq: 6, data: { turn: 1, step: 1, message: { id: 's1', role: 'system', content: [], source: { kind: 'system-prompt' } } }, surfaceOp: 'append' },
    { type: 'user/message', seq: 7, data: { role: 'user', id: 'u1', content: [{ type: 'text', text: '你好' }], source: { kind: 'user' } }, surfaceOp: 'append' },
    { type: 'assistant/message', seq: 8, data: { turn: 1, step: 1, stream: [], message: { id: 'a1', role: 'assistant', content: [], source: { kind: 'model', provider: 'p', model: 'm' } } }, surfaceOp: 'append' },
    { type: 'step/end', seq: 9, data: { turn: 1, step: 1 } },
    { type: 'turn/end', seq: 10, data: { turn: 1, reason: { kind: 'completed' } } },
    { type: 'session/title', seq: 11, data: { title: '你好', messageSeqs: [7], source: { kind: 'fallback' } } },
  ];
  return { header, events };
}

/* ---------------------------------------------------------------- 标题引用 */

test('健康会话通过', () => {
  const { header: h, events } = healthy();
  assert.deepEqual(checkLightweight(h, events), { ok: true });
});

test('标题指向 step/start：必须判出来（真实事故的形状）', () => {
  const { header: h, events } = healthy();
  events[11].data.messageSeqs = [5];            // 5 是 step/start
  const r = checkLightweight(h, events);
  assert.equal(r.ok, false);
  assert.match(r.why, /不是用户消息/);
});

test('标题指向更晚的记录：判出来', () => {
  const { header: h, events } = healthy();
  events[11].data.messageSeqs = [11];
  const r = checkLightweight(h, events);
  assert.equal(r.ok, false);
  assert.match(r.why, /不存在|更晚/);
});

test('标题重复引用同一条：判出来', () => {
  const { header: h, events } = healthy();
  events[11].data.messageSeqs = [7, 7];
  const r = checkLightweight(h, events);
  assert.equal(r.ok, false);
  assert.match(r.why, /重复/);
});

test('标题 source.kind=user 时 messageSeqs 必须为空', () => {
  const { header: h, events } = healthy();
  events[11].data.source = { kind: 'user' };
  const r = checkLightweight(h, events);
  assert.equal(r.ok, false);
});

test('回答缺 stream（收尾三件套）判出来', () => {
  const { header: h, events } = healthy();
  delete events[8].data.stream;
  const r = checkLightweight(h, events);
  assert.equal(r.ok, false);
  assert.match(r.why, /stream/);
});

test('seq 断档判出来', () => {
  const { header: h, events } = healthy();
  events[8].seq = 9;
  const r = checkLightweight(h, events);
  assert.equal(r.ok, false);
  assert.match(r.why, /断档/);
});

/* ---------------------------------------------------------------- 报错翻译 */

test('把"标题引用坏了"翻成人话，并指向 repair', () => {
  const { plain, action } = explain('session/title messageSeqs must cite distinct earlier human user/message events');
  assert.match(plain, /标题来源/);
  assert.match(action, /repair/);
});

test('认不出来的错误不乱解释（免得把 adb 问题说成会话坏了）', () => {
  const { plain } = explain('没找到 adb。两种办法任选一个');
  assert.equal(plain, '');
});

/* ---------------------------------------------------------------- 包格式 */

test('TSV 行：图片那列会补一句占位，帧数为 0 当成相册图', () => {
  const tsv = [
    '1700000000000\tuser\t拍一下\t/sdcard/a.jpg|3|到时间|197\t\tphone',
    '1700000000001\tassistant\t看到了\t\t\t',
    '1700000000002\tuser\t相册里的\t/x.jpg|0||0\t\tphone',
  ].join('\n');
  const msgs = parseConversation(tsv);
  assert.equal(msgs.length, 3);
  assert.match(msgs[0].text, /约 3 帧/);
  assert.match(msgs[2].text, /相册图/);
});

test('JSONL 行也吃', () => {
  const jsonl = [
    JSON.stringify({ at: 1, role: 'user', text: '你好' }),
    JSON.stringify({ at: 2, role: 'assistant', text: '在' }),
    '{坏行',
    JSON.stringify({ at: 3, role: 'system', text: '丢掉' }),
  ].join('\n');
  const msgs = parseConversation(jsonl);
  assert.equal(msgs.length, 2);
  assert.equal(msgs[1].role, 'assistant');
});

test('标题从第一句人话里取，并且剥掉"图已省略"占位', () => {
  const msgs = parseConversation('1700000000000\tuser\t这是什么\n\n（导出的图已省略：约 4 帧）\t/x.jpg|4||197\t\tphone');
  const title = titleOf(msgs);
  assert.doesNotMatch(title, /导出的图已省略/);
  assert.match(title, /这是什么/);
  assert.equal('这是什么\n\n（导出的图已省略：约 4 帧）'.replace(IMG_NOTE, '').trim().startsWith('这是什么'), true);
});
