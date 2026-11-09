/**
 * guide.js — 出图时附在文本里的那段说明。**按模式两套。**
 *
 * 为什么单独一个文件：这段文字**每次出图都要重发一遍**，而不是只写在工具描述里。
 * 工具描述会被长会话滚远，模型看到图的那一刻手上只有这段。所以它必须短、必须是
 * 祈使句、必须直接约束输出格式。
 *
 * `buildLookText`  = normal（默认）：用户自己看得见，别替他下结论；
 * `buildGuideText` = guide：用户看不见，只说他需要的。
 * 两套都**不许**写"你看"以外的假设 —— 说错读者比说错画面更贵。
 */

/**
 * 画面质量分级。
 *
 * 导盲场景最危险的不是"没看见"，是**在烂画面上编**。所以先把画面判成
 * `good / dim / dark / blank`，再决定给模型什么话 —— `dark` 时输出里根本没有图，
 * 只有"请照亮"的指令，从源头上掐掉瞎猜。
 */
export function gradeView({ meanLuma, frames, firstFrameMs }) {
  if (!frames) return 'none';
  if (meanLuma == null) return 'unknown';
  if (meanLuma < 8) return 'blank';    // 镜头被手指/口袋挡住
  if (meanLuma < 26) return 'dark';
  if (meanLuma < 46) return 'dim';
  return 'good';
}

const QUALITY_LINE = {
  none: '⚠ 摄像头没有输出任何帧。',
  blank: '⚠ 画面几乎全黑：镜头可能被挡住（手指、口袋、贴桌面）。',
  dark: '⚠ 画面很暗，细节不可靠。',
  dim: '画面偏暗，远处细节可能看不清。',
  good: '',
  unknown: '',
};

/**
 * 出图时附在文本里的一段话。
 *
 * @param {object} p
 * @param {string|null} p.note        用户刚说的话
 * @param {number} p.ageSec           这一帧是几秒前抓的
 * @param {number} p.frames           这一批用了几帧
 * @param {string} p.quality          gradeView 的结果
 * @param {number} p.spokenPacingSec  上次说话到现在过了多久（怕把用户念烦）
 */
/**
 * 出图时附在文本里的一段话（normal 模式：和桌面 `see_now` 一样说话）。
 *
 * 这里刻意**不提"用户看不见"** —— 0.2.x 的版本写了，结果模型不管用户说什么都用
 * 导盲口吻回答。默认读者的画像必须写对：他能看见画面，图是给他自己确认/讨论用的。
 */
export function buildLookText({ note = null, ageSec = 0, frames = 1, quality = 'good', backend = 'http-raw' } = {}) {
  const parts = [];
  parts.push(`手机摄像头的画面，共 ${frames} 帧，按「画面变化」挑出来的 —— `
    + '静止时帧少、变化多时帧多。编号按时间先后。');
  parts.push('用户自己看得见这块画面；这是给他确认、或者跟你一起看的图，别替他描述他已经知道的东西。');
  if (note) parts.push(`用户刚说：「${note}」。按这句话决定重点。`);
  const q = QUALITY_LINE[quality] ?? '';
  if (q) parts.push(q);
  if (ageSec >= 3) parts.push(`最新一帧是 ${ageSec.toFixed(1)} 秒前的；要「此刻」就再调一次 \`look\`。`);
  if (backend === 'http-raw' || backend === 'termux') {
    parts.push('（手机抓拍后端约每秒 1 帧，比桌面录屏慢得多；判断快速运动时说「刚才」而不是「现在」。）');
  }
  return parts.join('\n');
}

/**
 * 导盲模式的读图规范。**只有 `mode:"guide"` 走这里。**
 * 这段每次出图都要重发，而不是只写在工具描述里 —— 工具描述会被长会话滚远，
 * 模型看到图的那一刻手上只有它。所以必须短、必须是祈使句、必须直接约束输出格式。
 */
export function buildGuideText({ note = null, ageSec = 0, quality = 'good' } = {}) {
  const parts = [
    '这是用户手机摄像头**此刻**拍到的画面。用户看不见它 —— 只有你能看见，'
      + '你说什么他就信什么，所以只说你真看见的东西。',
  ];
  if (note) parts.push(`用户刚说：「${note}」。按这句话决定重点，不要泛泛描述。`);
  const q = QUALITY_LINE[quality] ?? '';
  if (q) parts.push(q);
  if (ageSec >= 3) parts.push(`这一帧是 ${ageSec.toFixed(1)} 秒前抓的，人可能已经动了 —— 报方位时说"刚才"。`);
  parts.push(
    '回答格式（给语音念的）：**先风险，再通路，最后动作**。一句到两句，不超过 40 字。'
      + '方位用「正前 / 左前 / 右前 / 脚下」，距离用「半步 / 一两步 / 五米外」。'
      + '不要描述颜色、天气、画面构图；不要用"图片中""画面左侧"这类第三人称说法。'
      + '看不清就说看不清，并给出怎么调整手机（抬高/放低/转向/开灯）。',
  );
  return parts.join('\n');
}

/** 非出图结果（status / stop / 失败）的文案不需要上面那套规范，单独一版。 */
export function buildStatusText({ cam, pipeline, quality, loopNote = null }) {
  const lines = [];
  lines.push(cam.running
    ? `摄像头在跑：${cam.backend} @${cam.width}×${cam.height}，${cam.fps || '?'} fps，已收 ${cam.frames} 帧`
    : '摄像头没在跑。');
  if (cam.grab?.shots) lines.push(`抓拍：${cam.grab.shots} 张成功 / ${cam.grab.fails} 失败`);
  if (cam.firstFrameMs != null) lines.push(`首帧 ${cam.firstFrameMs}ms`);
  if (pipeline) {
    const s = pipeline.stats;
    lines.push(`管线：留帧 ${s.kept}，出图 ${s.sheets}，去重跳过 ${s.dupSkipped}`);
  }
  if (quality && quality !== 'good') lines.push(QUALITY_LINE[quality] ?? quality);
  if (loopNote) lines.push(loopNote);
  if (cam.stderrTail) lines.push(`ffmpeg stderr 尾部：${cam.stderrTail.slice(-300)}`);
  return lines.join('\n');
}
