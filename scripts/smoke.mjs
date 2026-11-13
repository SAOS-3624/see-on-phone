/**
 * smoke.mjs —— 端到端冒烟：**用真手机**拍一张，把图存下来让你自己看。
 *
 * ```
 * node scripts/smoke.mjs          # 采集 2 秒
 * node scripts/smoke.mjs 6        # 采集 6 秒（场景里有动作的时候用这个）
 * ```
 *
 * 0.4.0 起它不再跑本地管线（引擎删了），就走手机那条路：
 * `/status` 探活 → `/look` 拿一张 → 落盘成 jpg。
 *
 * 它测的是**这条链路的真东西**：手机连着吗、App 在跑吗、图出来是什么样。
 * 假服务器能测契约，测不了"这张图糊不糊"。
 */
import { writeFileSync } from 'node:fs';
import { baseOf, loadPhoneConfig, lookSheet, status } from '../src/phone.js';

const seconds = Number(process.argv[2]) > 0 ? Number(process.argv[2]) : 2;
const base = baseOf(loadPhoneConfig().url);
console.log('手机接口：', base);

try {
  const st = await status(base);
  console.log(`✓ /status  ${st.app} ${st.version}　相机 ${st.cameraOpen ? '开' : '关'}　`
    + `上下文 ${st.convMsgs} 条`);
} catch (err) {
  console.error('✗ /status 失败：', err.message);
  console.error('  查三样：手机连着、App 在跑、adb forward tcp:4444 tcp:4444');
  process.exit(1);
}

try {
  const t0 = Date.now();
  const shot = await lookSheet(base, seconds);
  const out = `smoke-sheet-${Date.now()}.jpg`;
  writeFileSync(out, shot.bytes);
  console.log(`✓ /look    ${shot.frames} 帧 / ${shot.tokens} token / `
    + `${shot.canvas ? `${shot.canvas.w}×${shot.canvas.h}` : '?'} / ${shot.bytes.length} 字节 `
    + `(${Date.now() - t0}ms)`);
  console.log(`  已存 ${out} —— 打开看一眼。糊就调手机上的档位（tier），插件这边调不了。`);
} catch (err) {
  console.error('✗ /look 失败：', err.message);
  process.exit(1);
}
