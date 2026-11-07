/**
 * guide.js — 交给模型的「这是给盲人看的画面」说明。
 *
 * 为什么单独一个文件：这段文字**每次出图都要重发一遍**，而不是只写在工具描述里。
 * 工具描述会被长会话滚远，模型看到图的那一刻手上只有这段。所以它必须短、必须是
 * 祈使句、必须直接约束输出格式。
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
export function buildLookText({ note = null, ageSec = 0, frames = 1, quality = 'good', backend = 'termux' } = {}) {
  const parts = [];

  parts.push(
    '这是用户手机摄像头**此刻**拍到的画面。用户看不见它 —— 只有你能看见，'
    + '你说什么他就信什么，所以只说你真看见的东西。',
  );

  if (note) parts.push(`用户刚说：「${note}」。按这句话决定重点，不要泛泛描述。`);

  const q = QUALITY_LINE[quality] ?? '';
  if (q) parts.push(q);

  if (ageSec >= 3) {
    parts.push(`这一帧是 ${ageSec.toFixed(1)} 秒前抓的，人可能已经动了 —— 报方位时说"刚才"，`
      + '或者先 `look` 一次再回答。');
  }

  parts.push(
    '回答格式（给语音念的）：**先风险，再通路，最后动作**。'
    + '一句到两句，不超过 40 字。方位用「正前 / 左前 / 右前 / 脚下」，距离用「半步 / 一两步 / 五米外」。'
    + '不要描述颜色、天气、画面构图；不要用"图片中""画面左侧"这类第三人称说法。'
    + '看不清就说看不清，并给出怎么调整手机（抬高/放低/转向/开灯）。',
  );

  if (backend === 'termux') {
    parts.push('（后置摄像头是抓拍模式，最多每秒 3~4 张；走快了画面会跟不上，'
      + '要确认"现在"就再调一次 `look`。）');
  }

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
