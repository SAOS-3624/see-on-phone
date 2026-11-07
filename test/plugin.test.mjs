/**
 * 宿主契约测试：用假 ctx 跑一遍注册 + 调用。
 *
 * 这套测试守的是"**手机上装上去之后工具到底在不在**"这一类问题 ——
 * see-now 上踩过一次：`defineTool` 解析不到 → apply 抛 → 被 catch 吞掉 →
 * 整个插件静默不注册，用户只看到"没有这个工具"。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

process.env.DSH_SEE_PHONE_CONFIG = JSON.stringify({
  backend: 'testsrc', width: 336, height: 448, fps: 8,
});

const { apply } = await import('../src/index.js');

/** 最小可用宿主：只要 register / get / on。 */
function fakeHost() {
  const tools = [];
  const saved = [];
  const handlers = [];
  return {
    tools,
    saved,
    ctx: {
      tools: { register: (t) => { tools.push(t); return () => {}; } },
      get: (k) => (k === 'attachments'
        ? {
          async saveImage({ data, mediaType, name }) {
            saved.push({ bytes: data.length, mediaType, name });
            return { id: `att-${saved.length}`, mediaType, name };
          },
        }
        : undefined),
      on: (evt, fn) => { if (evt === 'dispose') handlers.push(fn); return () => {}; },
      logger: { info: () => {}, warn: () => {} },
    },
    /** 等价于宿主卸载插件：必须把摄像头放掉，否则下一个测试会抢同一个摄像头 */
    async dispose() {
      for (const fn of handlers) { try { fn(); } catch { /* ignore */ } }
      await new Promise((r) => setTimeout(r, 200));
    },
  };
}

/** 起一个已 apply 的宿主，并把清理挂到 t.after —— 忘了关会让整个测试进程不退出。 */
async function boot(t) {
  const host = fakeHost();
  await apply(host.ctx);
  t.after(async () => { await host.dispose(); });
  return host;
}

test('apply 注册了 see_phone', async (t) => {
  const host = await boot(t);
  assert.equal(host.tools.length, 1, '应当注册且只注册一个工具');
  assert.equal(host.tools[0].name, 'see_phone');
});

test('契约形状能被宿主接受（required 提升到父级、无 undefined）', async (t) => {
  const host = await boot(t);
  const spec = host.tools[0];
  assert.equal(spec.parameters.type, 'object');
  assert.ok(spec.parameters.properties.action, 'action 必须在 properties 里');
  assert.deepEqual(Object.keys(spec.parameters).sort(), ['properties', 'type']);
  // 序列化往返必须无损，否则宿主报 "value is not lossless JSON"
  assert.deepEqual(JSON.parse(JSON.stringify(spec.parameters)), spec.parameters);
});

test('status 在未启动时也能答', async (t) => {
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'status' });
  assert.equal(out.meta.ok, true);
  assert.match(out.text, /还没启动/);
});

test('look 能从合成源出图并交给 attachments', async (t) => {
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'look', wait: 6 });
  assert.equal(out.meta.ok, true, `look 失败：${out.text}`);
  assert.equal(host.saved.length, 1, '应当存了一张图');
  assert.equal(host.saved[0].mediaType, 'image/png');
  assert.ok(host.saved[0].bytes > 1000, 'PNG 太小说明出的是空图');
  assert.equal(out.images.length, 1);
  assert.match(out.text, /画面变化|帧/, '通用模式应当给画面变化说明，不是导盲话术');
});

test('mode:"guide" 仍保留导盲读图规范（后续定制用）', async (t) => {
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'look', mode: 'guide', wait: 6 });
  assert.equal(out.meta.ok, true, `look 失败：${out.text}`);
  assert.match(out.text, /正前|风险/, '导盲模式必须给出方位与风险约束');
});

test('stop 之后 status 说没在跑', async (t) => {
  const host = await boot(t);
  await host.tools[0].execute({ action: 'look', wait: 6 });
  const stopped = await host.tools[0].execute({ action: 'stop' });
  assert.match(stopped.text, /已关|没在跑/);
  const st = await host.tools[0].execute({ action: 'status' });
  assert.equal(st.meta.running, false);
});
