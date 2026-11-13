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

import { createServer } from 'node:http';

const { apply } = await import('../src/index.js');

/** 1×1 的真 JPEG —— 只要能被 base64 解出来、长度大于 0 就行。 */
const TINY_JPEG = '/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRof'
  + 'Hh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPDs0NDP/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAA'
  + 'AAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==';

/**
 * 起一个"假手机"：只实现插件真正用到的那三个端点。
 *
 * 0.4.0 起插件不再自己抽帧，所以测试也从"合成视频源"换成了"假 HTTP 服务" ——
 * 这样测的正好是**它和手机之间那层契约**（`/look` 的返回形状、超时、错误）。
 */
async function fakePhone(t, overrides = {}) {
  const server = createServer((req, res) => {
    const u = new URL(req.url, 'http://127.0.0.1');
    res.setHeader('content-type', 'application/json');
    if (u.pathname === '/look') {
      res.end(JSON.stringify({
        images: [TINY_JPEG], frames: 3, tokens: 197, why: 'time', canvas: { w: 471, h: 628 },
        ...overrides.look,
      }));
      return;
    }
    if (u.pathname === '/status') {
      res.end(JSON.stringify({
        app: '鱼眼', version: '9.9.9', cameraOpen: false, camera: 'back',
        width: 480, height: 640, convMsgs: 7, ctxTokens: 64000, think: 'low', speak: true, tools: true,
        ...overrides.status,
      }));
      return;
    }
    if (u.pathname === '/say') { res.end('{"ok":true}'); return; }
    res.statusCode = 404;
    res.end('{}');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const { port } = server.address();
  t.after(() => new Promise((r) => server.close(r)));
  process.env.DSH_SEE_PHONE_CONFIG = JSON.stringify({ url: `http://127.0.0.1:${port}/video/mjpeg` });
  return `http://127.0.0.1:${port}`;
}

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

test('连不上手机时说人话（不是抛异常）', async (t) => {
  // 故意不给假手机：端口指到一个没人听的地方
  process.env.DSH_SEE_PHONE_CONFIG = JSON.stringify({ url: 'http://127.0.0.1:1/video/mjpeg' });
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'status' });
  assert.equal(out.meta.ok, false);
  assert.match(out.text, /连不上手机/);
  assert.match(out.text, /App 在跑/, '必须告诉人去查什么');
});

test('look 从手机拿到图并交给 attachments', async (t) => {
  await fakePhone(t);
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'look' });
  assert.equal(out.meta.ok, true, `look 失败：${out.text}`);
  assert.equal(host.saved.length, 1, '应当存了一张图');
  assert.equal(host.saved[0].mediaType, 'image/jpeg', '手机给的是 JPEG，不该再转 PNG');
  assert.equal(out.images.length, 1);
  assert.equal(out.meta.engine, 'phone');
  assert.equal(out.meta.frames, 3);
  assert.match(out.text, /3 帧/);
  assert.equal(out.meta.mode, 'normal', '默认模式必须是 normal');
  assert.doesNotMatch(out.text, /用户看不见/, 'normal 模式不许说用户看不见');
});

test('mode:"guide" 走导盲口径', async (t) => {
  await fakePhone(t);
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'look', mode: 'guide' });
  assert.equal(out.meta.mode, 'guide');
  assert.match(out.text, /先.*风险/, '导盲模式必须给出风险优先的约束');
});

test('旧值 mode:"general" 仍然当作 normal（存量配置不能炸）', async (t) => {
  await fakePhone(t);
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'look', mode: 'general' });
  assert.equal(out.meta.mode, 'normal');
  assert.doesNotMatch(out.text, /用户看不见/);
});

test('stop 不再假装能关手机相机', async (t) => {
  await fakePhone(t);
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'stop' });
  assert.match(out.text, /自己关/, '要说清相机归手机管');
});

test('status 报的是手机的状态', async (t) => {
  await fakePhone(t);
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'status' });
  assert.equal(out.meta.ok, true);
  assert.match(out.text, /9\.9\.9/, '版本要来自手机');
  assert.match(out.text, /7 条消息/);
  assert.equal(out.meta.engine, 'phone');
});

test('pace/tier/camera 不再由插件管，要说出来', async (t) => {
  await fakePhone(t);
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'look', pace: 'dense', tier: 'native' });
  assert.match(out.text, /手机 App 的设置里/, '传了要解释去哪儿改，不能装作生效');
});

/**
 * `HELP` 是模板字符串 —— 正文里塞一个**裸反引号**就会把整个字符串截断，
 * 表现是 `help` 只回半句话（或者直接语法错）。这条测试钉住"它还是完整的"。
 */
test('help 是完整的（模板字符串没被反引号截断）', async (t) => {
  const host = await boot(t);
  const out = await host.tools[0].execute({ action: 'help' });
  assert.match(out.text, /^鱼眼 · See on Phone/);
  assert.ok(out.text.length > 600, `help 只有 ${out.text.length} 字，多半被截断了`);
  assert.match(out.text, /电脑借手机的眼睛/);
  assert.match(out.text, /归手机管的事/);
  assert.match(out.text, /已知限制/);
});
