/**
 * 收件箱解析的契约：
 *  - 只认 `<毫秒时间戳>|<文本>`，坏的行走开
 *  - 只投**最后一条**（用户连打三句时，前两句往往已被后一句覆盖语义）
 *  - `after` 之前的当作已读，不重投
 *  - 中文原样出来（这条是踩过的坑：老桥 App 用 readLine() 解 UTF-8，中文全乱）
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseInbox } from '../src/inbox.js';

test('只取最后一条', () => {
  const raw = '100|第一句\n200|第二句\n300|第三句\n';
  const r = parseInbox(raw, 0);
  assert.equal(r.latest, '第三句');
  assert.equal(r.maxSeen, 300);
});

test('已读的不重投', () => {
  const raw = '100|旧\n200|新\n';
  const r = parseInbox(raw, 150);
  assert.equal(r.latest, '新');
  assert.equal(r.maxSeen, 200);

  const r2 = parseInbox(raw, 200);
  assert.equal(r2.latest, null);
  assert.equal(r2.maxSeen, 200);
});

test('坏行跳过，中文不乱码', () => {
  const raw = 'no-pipe-here\nx|没有时间戳\n400|台阶在右脚前面\n';
  const r = parseInbox(raw, 0);
  assert.equal(r.latest, '台阶在右脚前面');
  assert.equal(r.maxSeen, 400);
});

test('文本里带竖线也只切第一个', () => {
  const r = parseInbox('500|a|b|c\n', 0);
  assert.equal(r.latest, 'a|b|c');
});
