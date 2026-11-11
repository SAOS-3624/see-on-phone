/**
 * 画布尺寸 = token 票价的护栏。
 *
 * 手机的画布落在 544² 地板以下，计费被放大到同一个网格 → **197 token 是固定票价**，
 * 跟画布多大无关。所以画布只该由两件事决定：
 *   1. 别大到超出票价（超出就是真加钱）；
 *   2. 别小到白扔钱（缩了不便宜，只是把像素扔掉）。
 *
 * 这四条一起把 471×628 钉死了。改 `MODE_SIZES` 必须同时满足它们，否则这里红。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { MODE_SIZES, MODES } from '../src/mode.js';
import { imageTokens, IMAGE_UPSCALE_AREA } from '../src/engine/tokens.js';

/** 摄像头源：`resolution=low` 选 4:3 的 640×480，App 转 90° 后是 480×640。 */
const SOURCE = { width: 480, height: 640 };
/** 旧画布（420×560 / 336×448）计费的口径，新尺寸不许超过它。 */
const BUDGET = 197;

test('两种模式的画布都在 token 票价之内，且基本吃满地板面积', () => {
  for (const mode of MODES) {
    const { width, height } = MODE_SIZES[mode];
    const tokens = imageTokens(width, height);
    // 地板放大倍数：>1 就是"付了钱没拿到像素"。容差 0.1% ── 471×628 差 148px，
    // 放大 1.0002 倍，网格一格都不变，可以接受。再小就是在扔钱。
    const upscale = Math.max(1, Math.sqrt(IMAGE_UPSCALE_AREA / (width * height)));
    assert.ok(
      upscale <= 1.001,
      `${mode} ${width}×${height} 面积 ${width * height} 比地板 ${IMAGE_UPSCALE_AREA} 小 ` +
      `${(upscale * 100 - 100).toFixed(1)}%：这块面积是已经付过钱的，不用白不用`,
    );
    assert.equal(tokens, BUDGET, `${mode} ${width}×${height} = ${tokens} token，票价必须是 ${BUDGET}`);
  }
});

test('画布与摄像头源同比例（3:4），不做 cover 裁切', () => {
  for (const mode of MODES) {
    const { width, height } = MODE_SIZES[mode];
    assert.ok(
      Math.abs(width / height - SOURCE.width / SOURCE.height) < 1e-9,
      `${mode} ${width}×${height} 不是 3:4 → 每格都要裁掉一条画面`,
    );
  }
});

test('画布不超过源宽：采集侧只缩小不放大', () => {
  for (const mode of MODES) {
    const { width } = MODE_SIZES[mode];
    assert.ok(
      width <= SOURCE.width,
      `${mode} 宽 ${width} > 源宽 ${SOURCE.width} → 最近邻放大会把画面糊掉，` +
      '而且换不来任何 token',
    );
  }
});

test('旧尺寸确实也是 197：缩画布一分钱都省不下来', () => {
  // 这就是"白扔像素"的证据：三个尺寸同价，所以只能选最大的那个。
  assert.equal(imageTokens(336, 448), BUDGET);
  assert.equal(imageTokens(420, 560), BUDGET);
  assert.equal(imageTokens(471, 628), BUDGET);
});
