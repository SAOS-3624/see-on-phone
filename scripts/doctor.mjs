/**
 * doctor.mjs —— 装机体检。**装插件之前先跑这个**。
 *
 *   node scripts/doctor.mjs            # 只看状态
 *   node scripts/doctor.mjs --shoot    # 顺便真拍一张，验证能出画面
 *
 * 0.4.0 起这个插件不碰摄像头、不要 ffmpeg 了，所以体检项也换了：
 * 以前查"ffmpeg 在不在、termux 有没有"，现在查**手机那头通不通**。
 * 唯一还留在电脑上的外部依赖是**语音播放**（ffplay/mpv），单独列一条。
 */
import { baseOf, loadPhoneConfig, lookSheet, status } from '../src/phone.js';
import { whichBin } from '../src/bin.js';

const shoot = process.argv.includes('--shoot');
const rows = [];
let fatal = 0;

function row(name, ok, detail, { blocking = false } = {}) {
  rows.push({ name, ok, detail });
  if (!ok && blocking) fatal += 1;
}

const base = baseOf(loadPhoneConfig().url);
row('手机地址', true, base);

let st = null;
try {
  st = await status(base);
  row('手机在线', true, `${st.app} ${st.version}`);
} catch (err) {
  row('手机在线', false, `${err.message}\n    查三样：手机连着 / App 在跑 / adb forward tcp:4444 tcp:4444`, { blocking: true });
}

if (st) {
  row('摄像头', true, st.cameraOpen ? '当前开着（有别的进程在用）' : '当前关着（用的时候会自己开）');
  row('上下文', true, `${st.convMsgs ?? '?'} 条 · 预算 ${st.ctxTokens ?? '?'} token`);
}

// 语音播放：插件自己念要 ffplay/mpv；没有就会退回"只出文字"
const playBin = (await whichBin('ffplay')) ?? (await whichBin('mpv'));
row('语音播放', Boolean(playBin), playBin ?? '没找到 ffplay / mpv —— say 会退回只出文字（不影响看图）');

if (shoot) {
  try {
    const shot = await lookSheet(base, 2);
    row('实拍一张', true, `${shot.frames} 帧 / ${shot.tokens} token / ${shot.bytes.length} 字节`);
  } catch (err) {
    row('实拍一张', false, err.message, { blocking: true });
  }
}

const pad = (s, n) => s + ' '.repeat(Math.max(0, n - [...s].length * 2));
for (const r of rows) {
  console.log(`${r.ok ? '✓' : '✗'} ${pad(r.name, 10)} ${r.detail}`);
}
console.log(fatal === 0 ? '\n体检通过。' : `\n有 ${fatal} 项阻塞，先把上面 ✗ 的处理掉。`);
process.exit(fatal === 0 ? 0 : 1);
