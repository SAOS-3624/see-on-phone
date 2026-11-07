# SeeNow Phone Version

> **手机版 see-now**：保留桌面版原有的「打分 → 挑帧 → 多帧宫格 → 时间线」能力，
> 只把采集源从电脑屏幕换成手机摄像头。
>
> 导盲是**可选模式**（`mode:"guide"`），后续再定制；默认 `mode:"general"`，
> 行为和桌面 `see_now` 一致 —— 其他用户装上去就是原来那套东西。

- npm：`dsh-see-now-phone`
- 工具名：`see_phone`
- 依赖：Node ≥ 20；手机场景另需 Termux + Termux:API

---

## 1. 和 dsh-see-now 的关系

**同一套引擎，两个采集源。**

| | `dsh-see-now`（桌面） | `dsh-see-now-phone` |
|---|---|---|
| 采集 | 屏幕：ddagrab / gdigrab / portal / PipeWire | **摄像头**：Termux 抓拍 / MJPEG / RTSP |
| 引擎 | 打分、切点、装箱 DP、拼图、PNG、token 换算 | **整块 vendor 过来，一行没改** |
| 输出 | 多帧宫格（按画面变化挑帧） | **一样** |
| 动作 | watch / peek / status / stop | **一样**（另有 `look` 取单帧） |
| 语音 | 可选 `say` | 可选 `say`（有 TTS 就念） |

引擎 `src/engine/` 的 9 个文件来自 `dsh-see-now` 0.1.5，零宿主依赖（只用 `node:zlib`）。
**采集层整个重写**：Android 上普通进程看不到 `/dev/video*`，桌面那套 ffmpeg 设备枚举一个都用不了。

### 为什么独立成包而不是给 see-now 加 `--camera`

采集配置、默认值、发版节奏都不同；导盲那套后续还要按真机反馈反复调阈值，
不该带着"看视频"的用户一起发版。

---

## 2. 手机上的架构（真机实测通过）

```
Termux:API (camera2)
   │  termux-camera-photo  ~800ms/张（open→capture→close，物理下限）
   ▼
cam_grab.sh   shell 循环抓拍 → 原子替换 .cam-frame.jpg + 毫秒时间戳
   │
cam_server.py  PIL 解码（draft 降采样）→ 等比 cover → rgb24
   │  HTTP 127.0.0.1:8123/frame?w=&h=   ≈ 80ms/次
   ▼
dsh-go (PRoot Ubuntu) 里的 dsh-see-now-phone
   │  http-raw 后端：收现成 rgb24 → pipeline.push({t,buf})
   ▼
打分 → 切点 → 装箱 DP → 拼图 → PNG → 宿主附件
```

**两个关键决策**（都是实测踩出来的）：

1. **抓拍和发包拆成两个进程**。同一条 `termux-camera-photo`，
   shell 直接调 ~800ms，python 的 `subprocess` 调 **~3500ms**（差 4 倍，与环境变量无关）。
   shell 循环抓、python 只读文件，各自都在最优点。
2. **解码放在手机侧**，插件只收 rgb24。dsh-go 的 PRoot 里大概率没有 ffmpeg，
   而且 `image2pipe` 喂多张 JPEG 时，ffmpeg 会把"数据停顿"判成 EOF 提前退出（实测只走 1 帧）。
   插件侧因此**零 ffmpeg 依赖**。

`PIL.Image.draft()` 让 JPEG 解码器直接按 1/4 降采样解码：
4096×3072 全图 open 208ms → 52ms，resize 61ms → 14ms。

两个 Android App 共享同一个网络栈，所以 PRoot 里直连 `127.0.0.1:8123` 即可，**不需要端口转发**。

---

## 3. 后端

| 后端 | 帧率 | 需要 | 说明 |
|---|---|---|---|
| **`http-raw`** | ~1 fps | Termux + Termux:API + Pillow | **手机默认**，零插件侧依赖 |
| `termux` | ~1 fps | Termux + Termux:API | 插件自己抓拍（只在插件直接跑在 Termux 里时用） |
| `mjpeg` / `rtsp` | 15–30 fps | IP Webcam / DroidCam | **要流畅就用这个**，见 §5 |
| `v4l2` / `avfoundation` / `dshow` | 原生 | 桌面摄像头 | PC 调试 |
| `file` / `testsrc` | — | — | 回归测试 |

配置 `~/.dsh/see-phone.json`（或 `DSH_SEE_PHONE_CONFIG='{"backend":"testsrc"}'`）：

```json
{
  "backend": "http-raw",
  "url": "http://127.0.0.1:8123/frame",
  "width": 336,
  "height": 448,
  "fps": 8,
  "grabIntervalMs": 220,
  "mode": "general"
}
```

---

## 4. 工具用法

```js
see_phone({ action: "watch" })                    // 默认：等多帧宫格（和桌面 see_now 一样）
see_phone({ action: "peek" })                     // 不等待，领走已攒好的图
see_phone({ action: "look" })                     // 单帧「此刻」，用户插话时用
see_phone({ action: "status" })                   // 后端、帧率、抓拍成功/失败数、上次错误
see_phone({ action: "stop" })                     // 放掉摄像头，别一直耗电
see_phone({ action: "look", mode: "guide" })      // 导盲话术（后续定制，默认不用）
```

`watch` 的阻塞就是"暂停"，回完话再调一次；静止画面会一直攒着不打扰你（省 token 的设计）。

---

## 5. 实测性能与诚实的边界

真机：HUAWEI nova 11（FOA-AL00），HarmonyOS 4.2 / Android 12，arm64。

| 环节 | 实测 |
|---|---|
| `termux-camera-photo` 抓一张 | **~800ms**（camera2 open/close） |
| PIL 解码 4096×3072 → 336×448 | 60–80ms（加 `draft` 后） |
| HTTP `/frame` 端到端 | **~80ms** |
| 插件 10 秒收到 | **25 帧（2.5 fps）**，成功出图 |

⚠️ **抓拍路径的物理上限就是 ~1 fps 的真实画面更新**。
`termux-camera-photo` 每次都要完整 open/close 摄像头，且没有分辨率参数，改不动。
手机抓拍的间隔抖动也大，所以帧时间戳用**真实墙钟**而不是 `frameIndex/fps`。

这个帧率对「看眼前静态画面」够用，但**做不了桌面版那种 12fps 的连续运动分析**。
要流畅（15–30fps）只能换流：装 IP Webcam / DroidCam，配置
`{"backend":"mjpeg","url":"http://127.0.0.1:8080/video"}`，插件侧已经有这个后端。

其他边界：
- 全黑/太暗时 `gradeView` 会拦掉出图，只提示调整，不让模型在烂画面上编
- 单帧 336×448 ≈ 130 token，**图会留在会话历史里每轮重发**；长时间看要留意
- 手机抓拍持续耗电发热，用完记得 `stop`

---

## 6. 当前装机进度

- [x] dsh-go APK 装上（本机模式）
- [x] Termux + Termux:API 装上、`termux-api` / Pillow 装好、相机权限已授
- [x] `cam_grab.sh` + `cam_server.py` 部署并跑起来（Termux 前台服务 + wake-lock）
- [x] `http-raw` 端到端打通：手机摄像头 → rgb24 → 插件 → PNG
- [x] 插件改回通用模式（保留原功能），导盲留作 `mode:"guide"`
- [ ] **把插件装进 dsh-go 的 PRoot**（还差这一步：需要在 dsh-go 里开一个终端执行 `npm i`）

---

## 7. 开发

```bash
npm run check:syntax      # 全量语法体检
npm test                  # 宿主契约测试（假 ctx，6 条）
npm run smoke testsrc 6   # 合成源跑通整条管线
DSH_SEE_PHONE_CONFIG='{"backend":"http-raw","url":"http://127.0.0.1:8123/frame"}' npm run smoke http-raw 10
```

## License

Apache-2.0。`src/engine/` 来自 [dsh-see-now](https://github.com/SAOS-3624/dsh-see-now)，同一许可证。
