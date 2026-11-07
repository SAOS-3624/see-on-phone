/**
 * 装机体检。**装插件之前先跑这个** —— 手机上 90% 的"没反应"是权限/依赖，
 * 而不是插件坏了，但那些原因在宿主的日志里长得一模一样。
 *
 *   node scripts/doctor.mjs            # 体检当前环境
 *   node scripts/doctor.mjs --shoot    # 顺便真拍一张，验证摄像头能出画面
 */
import { spawn } from 'node:child_process';
import { existsSync, statSync, unlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { probeFfmpeg } from '../src/cam/ffmpeg.js';
import { whichBin } from '../src/cam/camera.js';

const shoot = process.argv.includes('--shoot');
const rows = [];
let fatal = 0;

function row(name, ok, detail, { blocking = false } = {}) {
  rows.push({ name, ok, detail });
  if (!ok && blocking) fatal += 1;
}

function run(cmd, args, { timeoutMs = 8000 } = {}) {
  return new Promise((resolve) => {
    let out = '';
    let child;
    try { child = spawn(cmd, args, { windowsHide: true }); } catch (e) {
      return resolve({ code: -1, out: '', err: e.message });
    }
    const timer = setTimeout(() => { try { child.kill('SIGKILL'); } catch { /* ignore */ } }, timeoutMs);
    child.stdout.on('data', (s) => { out += s; });
    child.stderr.on('data', (s) => { out += s; });
    child.on('error', (e) => { clearTimeout(timer); resolve({ code: -1, out: '', err: e.message }); });
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, out: out.trim() }); });
  });
}

// 1. Node
const major = Number(process.versions.node.split('.')[0]);
row('Node.js', major >= 20, `v${process.versions.node}${major >= 20 ? '' : '（需要 ≥ 20）'}`, { blocking: true });

// 2. ffmpeg
const ff = await probeFfmpeg(process.env.DSH_FFMPEG_PATH ?? 'ffmpeg');
row('ffmpeg', ff.ok, ff.ok ? `${ff.version ?? '?'}，${ff.devices?.length ?? 0} 个设备` : String(ff.error), { blocking: true });

// 3. 摄像头采集通道
const camBin = await whichBin('termux-camera-photo');
row('termux-camera-photo', Boolean(camBin), camBin ?? '没找到（Termux 里 `pkg install termux-api`）');

// 4. TTS
const ttsBin = await whichBin('termux-tts-speak');
row('termux-tts-speak', Boolean(ttsBin), ttsBin ?? '没找到 —— 模型说的话不会自动念出来');

// 5. Termux:API App 真装了没
const pm = await run('sh', ['-c', 'pm list packages 2>/dev/null | grep -c com.termux.api']);
const apiInstalled = Number(pm.out) > 0;
row('Termux:API App', apiInstalled, apiInstalled ? '已安装' : '没装（F-Droid 搜 Termux:API）');

// 6. 真拍一张
if (shoot) {
  if (!camBin) {
    row('实拍测试', false, '跳过：没有 termux-camera-photo');
  } else {
    const out = join(tmpdir(), `see-phone-doctor-${Date.now()}.jpg`);
    const r = await run(camBin, ['-c', '0', out], { timeoutMs: 15000 });
    const ok = r.code === 0 && existsSync(out) && statSync(out).size > 1024;
    row('实拍测试', ok, ok ? `${(statSync(out).size / 1024).toFixed(0)}KB` : (r.out || r.err || '拍不出画面'));
    if (existsSync(out)) { try { unlinkSync(out); } catch { /* ignore */ } }
  }
}

// 输出
const w = Math.max(...rows.map((r) => r.name.length));
console.log('\nSeeNow Phone · 装机体检\n');
for (const r of rows) {
  console.log(`  ${r.ok ? '✓' : '✗'} ${r.name.padEnd(w)}  ${r.detail}`);
}
console.log('');
if (fatal) {
  console.log(`✗ ${fatal} 项阻塞：上面标 ✗ 且属于必需项，插件起不来。`);
  process.exitCode = 1;
} else {
  const warn = rows.filter((r) => !r.ok).length;
  console.log(warn
    ? `△ 可以启动，但有 ${warn} 项缺失（say 不会发声 / 采集通道走不了）。`
    : '✓ 全部通过，可以装插件了。');
}
