/**
 * 出图设置的移植契约（**这个文件是"别乱改"的护栏**）。
 *
 * 手机版和桌面版是同一套引擎，但参数是在调用侧拼出来的 —— 一旦这里少传一个键，
 * 它会静默退回 `pipeline.js::DEFAULTS`，表现是"图看着不对"，极难查（0.3.0 之前
 * 就是这个状态：只传了 width/height/fps/queueDepth）。
 *
 * 所以这里把 see-now `runner.js::start()` 传给 `createPipeline` 的那一组键
 * 逐条钉住。**改这里的期望值 = 改 see-now 的口径，先回去改桌面版。**
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { pipelineOptions, tuningSignature } from '../src/index.js';
import { PACES, SCENE_OPTS } from '../src/engine/pace.js';
import { readTuning, TUNING_DEFAULTS } from '../src/engine/tuning.js';
import { LEVEL_THRESHOLDS, PERCEPTUAL_THRESHOLDS } from '../src/engine/tiers.js';

const SIZE = { width: 336, height: 448 };
const FPS = 8;

/** 没写配置文件时的默认设置（与 see-now `readTuning()` 的返回值同形）。 */
const tune = readTuning({ env: { DSH_HOME: '/nonexistent-dsh-home-for-test' } });

const normal = (pace = 'normal', t = tune) =>
  pipelineOptions({ size: SIZE, fps: FPS, mode: 'normal', tune: t, pace });

test('默认设置与桌面版一致（readTuning 的默认值不能被改过）', () => {
  assert.equal(tune.tierStyle, 'standard', 'see-now 默认 standard（1 档压成 2 档）');
  assert.equal(tune.tierMode, 'density');
  assert.equal(tune.tierCurve, 'docx');
  assert.deepEqual(tune.tierThresholds, [...LEVEL_THRESHOLDS]);
  assert.equal(tune.scoreNormalize, true);
  assert.equal(tune.changeLog, false);
  assert.equal(tune.defaultPace, 'normal');
});

test('normal 模式把整套 see-now 设置都传给管线', () => {
  const o = normal();
  assert.equal(o.strategy, 'standard');
  assert.equal(o.tierMode, 'density');
  assert.equal(o.tierCurve, 'docx');
  assert.deepEqual(o.tierThresholds, [...LEVEL_THRESHOLDS]);
  assert.equal(o.scoreNormalize, true);
  assert.equal(o.changeLog, false);
  assert.equal(o.width, 336);
  assert.equal(o.height, 448);
  assert.equal(o.fps, FPS);
  assert.equal(o.queueDepth, 1);
  // custom 之外不塞 tierMap（see-now 也是只在 custom 时带）
  assert.equal('tierMap' in o, false);
});

test('节奏档的 pipeline 覆盖与 cutAbs 逐档对上 PACES（含自适应兜底）', () => {
  for (const name of Object.keys(PACES)) {
    const o = normal(name);
    assert.equal(o.cutAbs, PACES[name].cutAbs, `pace:${name} 的 cutAbs`);
    for (const [k, v] of Object.entries(PACES[name].pipeline ?? {})) {
      assert.deepEqual(o[k], v, `pace:${name} 的 ${k}`);
    }
  }
  // 默认档必须真的带自适应兜底 —— 落到 DEFAULTS 的 floorGapK:0 等于关掉它
  assert.equal(normal().floorGapK, 2);
  assert.equal(normal().floorMin, 2);
  assert.equal(normal().floorMax, 20);
});

test('guide 模式 = 桌面版的逐帧模式（单帧必须铺满画布）', () => {
  const o = pipelineOptions({ size: SIZE, fps: FPS, mode: 'guide', tune, pace: 'normal' });
  assert.equal(o.strategy, SCENE_OPTS.strategy);
  assert.equal(o.strategy, 'single');
  assert.equal(o.maxFrames, 1);
  // 逐帧模式的切点门槛跟 dense 走
  assert.equal(o.cutAbs, PACES.dense.cutAbs);
  // 设置项仍然生效
  assert.deepEqual(o.tierThresholds, [...LEVEL_THRESHOLDS]);
  assert.equal(o.scoreNormalize, true);
});

test('设置文件能改到管线上：tierStyle / tierCurve / 自定义阈值 / 节奏', () => {
  const custom = {
    ...tune,
    tierStyle: 'custom',
    tierMap: [2, 2, 3, 4, 5, 6, 7, 8],
    tierCurve: 'perceptual',
    tierThresholds: [...PERCEPTUAL_THRESHOLDS],
    scoreNormalize: false,
    changeLog: true,
    defaultPace: 'calm',
  };
  const o = normal(custom.defaultPace, custom);
  assert.equal(o.strategy, 'custom');
  assert.deepEqual(o.tierMap, custom.tierMap);
  assert.equal(o.tierCurve, 'perceptual');
  assert.deepEqual(o.tierThresholds, [...PERCEPTUAL_THRESHOLDS]);
  assert.equal(o.scoreNormalize, false);
  assert.equal(o.changeLog, true);
  assert.equal(o.cutAbs, PACES.calm.cutAbs);
  assert.equal(o.floorGapK, 3);
});

test('音轨条跟随"有没有音频源"：手机默认关，配置能开', () => {
  assert.equal(normal().audioStrip, false);
  const on = pipelineOptions({
    size: SIZE, fps: FPS, mode: 'normal', tune, pace: 'normal', audioStrip: true,
  });
  assert.equal(on.audioStrip, true);
});

test('设置签名只看出图相关键（改 listen/imageTrigger 不该重开摄像头）', () => {
  const a = tuningSignature(tune);
  assert.equal(tuningSignature({ ...tune, listen: { silence: 0.4 } }), a);
  assert.equal(tuningSignature({ ...tune, imageTrigger: 500 }), a);
  assert.notEqual(tuningSignature({ ...tune, tierStyle: 'native' }), a);
  assert.notEqual(tuningSignature({ ...tune, defaultPace: 'dense' }), a);
});

test('TUNING_DEFAULTS 的 tier 默认值与 tiers.js 同源（不许手抄）', () => {
  assert.deepEqual(TUNING_DEFAULTS.tierThresholds, [...LEVEL_THRESHOLDS]);
  assert.deepEqual(TUNING_DEFAULTS.tierMap, [1, 2, 3, 4, 5, 6, 7, 8]);
});
