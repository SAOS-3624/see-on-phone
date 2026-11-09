/**
 * mode.js 的单元测试：模式归一 + 三级回退 + 画布尺寸。
 *
 * 守的是"用户点『看一眼』不该被当盲人"这条产品规则：默认必须是 normal，
 * 而 `general` 这种存量写法不能把默认值带偏。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeMode, resolveMode, sizesFor, MODE_SIZES } from '../src/mode.js';

test('normalizeMode：两个正名 + 旧名 + 认不出', () => {
  assert.equal(normalizeMode('normal'), 'normal');
  assert.equal(normalizeMode('Guide'), 'guide');
  assert.equal(normalizeMode('general'), 'normal', '0.2.x 的旧名字必须继续认');
  assert.equal(normalizeMode('blind'), 'guide');
  assert.equal(normalizeMode(''), null);
  assert.equal(normalizeMode('whatever'), null, '认不出要给 null，让调用方继续回退');
  assert.equal(normalizeMode(undefined), null);
});

test('resolveMode：参数 > App 开关 > 配置 > normal', () => {
  assert.deepEqual(resolveMode({}), { mode: 'normal', source: 'default' });
  assert.deepEqual(resolveMode({ cfg: 'guide' }), { mode: 'guide', source: 'config' });
  assert.deepEqual(resolveMode({ cfg: 'guide', app: 'normal' }), { mode: 'normal', source: 'app' });
  assert.deepEqual(
    resolveMode({ cfg: 'guide', app: 'guide', arg: 'normal' }),
    { mode: 'normal', source: 'arg' },
  );
  // 认不出的值要跳过，不能把整条链打断
  assert.deepEqual(resolveMode({ cfg: 'guide', app: '???' }), { mode: 'guide', source: 'config' });
});

test('sizesFor：按模式给画布，配置里写死的尺寸优先', () => {
  assert.deepEqual(sizesFor('normal', {}), MODE_SIZES.normal);
  assert.deepEqual(sizesFor('guide', {}), MODE_SIZES.guide);
  assert.deepEqual(sizesFor('normal', { width: 300, height: 400 }), { width: 300, height: 400 });
  // 只写一半不算：宁可回默认值，也别拿着半套尺寸去建管线
  assert.deepEqual(sizesFor('guide', { width: 300 }), MODE_SIZES.guide);
});
