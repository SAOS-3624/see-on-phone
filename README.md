# SeeNow Phone Version

> 让 DSH 用**手机摄像头**看见使用者前方，再用语音告诉他前面是什么。
>
> 这是给视障用户的导盲版 SeeNow：不能截屏（他看不见屏幕），只能看摄像头。

- npm：`dsh-see-now-phone`
- 工具名：`see_phone`
- 依赖：Node ≥ 20 + `ffmpeg`；手机上另需 Termux:API

---

## 1. 它和 dsh-see-now 是什么关系

**同一个引擎，两个产品。**

| | `dsh-see-now` | `dsh-see-now-phone` |
|---|---|---|
| 采集 | 屏幕（ddagrab / gdigrab / portal） | **摄像头**（termux 抓拍 / MJPEG / RTSP） |
| 读者 | 能看见画面的人（自己确认用） | **看不见的盲人**（只听模型说） |
| 输出形态 | 多帧宫格时间线（看"怎么变的"） | **单帧**（看"现在有什么"） |
| 节奏 | 15–40 秒一张 | 秒级 |
| 语音 | 可选 `say` | **强制走系统 TTS** |
| 浮窗 / 字幕 / region | 有 | 无（他看不见，也听不到视频台词） |

引擎部分（`src/engine/`）整块 vendor 自 `dsh-see-now` 0.1.5：打分、切点、装箱 DP、
拼图、PNG 编码、token 换算。**采集层整个重写**，因为 Android 上
`/dev/video*` 对普通进程不可见，PC 那套 ffmpeg 设备枚举一个都用不了。

### 为什么不是「see-now 加个 `--camera`」

三条硬理由，不是洁癖：

1. **默认值冲突**：see-now 默认多帧拼图（省 token、看变化），导盲要单帧（看此刻）。
   同一个 `scene` 开关两头都要改，最后必然分叉。
2. **失败语义不同**：see-now 采不到画面 = "稍后再试"；导盲采不到画面 = **必须明确说看不清**，
   绝不能沉默。这个差别渗到每一处返回值里。
3. **发版节奏**：导盲要按真机反馈狂调阈值，不该带着"看视频"的用户一起发版。

---

## 2. 安装

### 2.1 PC（验证用）

```bash
npm i -g dsh-see-now-phone
dsh plugin add dsh-see-now-phone
```

PC 上没有 `termux-camera-photo`，会自动落到本机摄像头（v4l2 / avfoundation / dshow）。

### 2.2 手机（DSH Go / Termux 系宿主）

```bash
# 1. Termux 里装 API 桥
pkg install termux-api
# 2. 装 Termux:API App（F-Droid），并在系统设置里给 Termux 相机权限
# 3. 装插件
npm i -g dsh-see-now-phone
dsh plugin add dsh-see-now-phone
```

验证采集是否通（**在装插件之前先跑这一步**，能省掉一半排错）：

```bash
termux-camera-photo -c 0 /sdcard/dcim/test.jpg && ls -l /sdcard/dcim/test.jpg
```

拍出来是黑的 → 权限没给；报 `Permission Denial` → Termux:API App 没装；
命令不存在 → `pkg install termux-api` 没装。

---

## 3. 配置

`~/.dsh/see-phone.json`（也可以 `DSH_SEE_PHONE_CONFIG='{"backend":"mjpeg"}'` 覆盖）：

```json
{
  "backend": "termux",
  "camera": "back",
  "width": 336,
  "height": 448,
  "fps": 8,
  "grabIntervalMs": 220
}
```

| 键 | 说明 |
|---|---|
| `backend` | `termux`（默认，无需额外 App）/ `mjpeg` / `rtsp` / `v4l2` / `avfoundation` / `dshow` / `file` / `testsrc` |
| `camera` | `back`（默认，看前方）/ `front` |
| `width` / `height` | 画布尺寸。336×448 单帧 ≈ 130 token |
| `grabIntervalMs` | 抓拍间隔。220ms ≈ 4.5fps 上限，Android camera2 open/close 的物理下限 |

### 后端怎么挑

| 后端 | 帧率 | 需要什么 | 适合 |
|---|---|---|---|
| `termux` | 2–4 fps | Termux + Termux:API | **默认**。判断台阶、门、人、车足够 |
| `mjpeg` | 15–30 fps | 另一个 App（IP Webcam）+ 同一 Wi-Fi | 走路快、要连贯画面 |
| `rtsp` | 15–30 fps | DroidCam 等 | 同上，延迟更低 |

`termux` 抓拍是**一次一张**，走路时画面会比人慢半拍。所以描述方位时要说"刚才"，
或者先再 `look` 一次。要真正连贯得上 `mjpeg`/`rtsp`。

---

## 4. 工具用法

```js
see_phone({ action: "look", note: "我在找 3 号楼" })   // 默认，秒级返回一张图
see_phone({ action: "watch", wait: 30 })             // 背景等画面大变（过马路、进楼）
see_phone({ action: "status" })                      // 后端、帧率、亮度、上次错误
see_phone({ action: "stop" })                        // 放掉摄像头，别让电池和相机一直热着
```

`say` 会真的走一次 `termux-tts-speak`。**上一句没念完就打断**——导盲最怕一句话排在
一句话后面（"前面有台阶"晚 3 秒到达，人已经迈出去了）。

`look` 的返回值里带一段给模型的硬约束：先风险、再通路、最后动作；方位用
"正前/左前/右前"；距离用"半步/一两步/五米外"；看不清就说看不清。
这段话在 `src/guide.js`，不是可选的文案。

---

## 5. 诚实的边界

这套东西能帮到什么程度，先说清楚，别让人对它有错误期待：

**能做**
- 正前方 5 米内的台阶、路沿、门、柱子、停着的车、站着的人
- 招牌门牌、公交车号、店名（静止时）
- 粗略方位与绕行方向

**做不好**
- **相对速度和动态障碍**：4fps 抓拍判断不了自行车多快过来。要这个必须上 `mjpeg`/`rtsp`
- **精确距离**：单目摄像头没有深度，只能说"一两步""五米外"，不能说"1.2 米"
- **细窄空间**：门缝、栏杆间距、地砖高差
- **延迟**：`look` 从调用到用户听见，端到端 2–6 秒（摄像头 0.2s + ffmpeg 0.1s +
  模型 TTFT 1–3s + TTS 0.3s）。**这个延迟不适合当作过马路的唯一依据**

**明确不做**
- 不替代盲杖和导盲犬。这是补充信息，不是碰撞检测
- 不做实时避障（那需要连续视频 + 边缘计算，不是这个架构）

> 一句话：它能回答"我前面有什么"，不能回答"我现在迈这一步安全吗"。后者要靠盲杖。

---

## 6. 开发

```bash
npm run check:syntax      # 全量语法体检
npm test                  # 宿主契约测试（假 ctx）
npm run smoke testsrc 6   # 合成源跑通整条管线，图落 .tmp/
npm run smoke file 12 -- <视频路径>   # 用真实视频当"摄像头"
```

手机上调试：

```bash
DSH_SEE_PHONE_CONFIG='{"backend":"testsrc"}' dsh   # 先证明管线通，再换 termux
```

---

## License

Apache-2.0。`src/engine/` 来自 [dsh-see-now](https://github.com/SAOS-3624/dsh-see-now)，
同一许可证。
