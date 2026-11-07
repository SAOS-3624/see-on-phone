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

**默认走连续流，12–20fps。** 采集端用开源 App
[DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera)
（**MIT**，可商用；v0.16.1 官方 GitHub Release，arm64，SHA-256 与公布值核对一致）。

```
IP Camera App  Camera2 长连接 → HTTP :4444/video/mjpeg（免认证、免 TLS，约 17fps）
   │
Termux ffmpeg（常驻一次启动）→ scale/cover → rgb24
   │
cam_server.py  维护最新帧；HTTP :8123/frame（约 25ms/次）、/jpg、/stream、/view
   │
dsh-go (PRoot Ubuntu) 里的 dsh-see-now-phone
   │  http-raw 后端：收现成 rgb24 → pipeline.push({t,buf})
   ▼
打分 → 切点 → 装箱 DP → 多帧宫格 → PNG → 宿主附件
```

两个 Android App 共享同一个网络栈，PRoot 直连 `127.0.0.1` 即可，**不需要端口转发**。
插件侧**零 ffmpeg 依赖**（PRoot 里没有 ffmpeg，解码全在 Termux 侧做完）。

**为什么必须换流**：`termux-camera-photo` 每次 camera2 open/close ~800ms（上限 ~1.2fps），
Termux 的 ffmpeg 没有 `android_camera`/`v4l2` demuxer，`/dev/video*` 属 `system:camera`、
普通进程 `Permission denied`。抓拍路径调不出 12fps，是权限模型决定的。

**保留的回退**：`CAM_MODE=grab` 时不依赖任何额外 App，用
`cam_grab.sh` + `termux-camera-photo` + PIL `draft()` 走 ~1fps 抓拍路径
（IP Camera 没装/没开时自动可用）。帧标记用**递增序号**，不用时间戳。

## 3. 后端

| 后端 | 帧率 | 需要 | 说明 |
|---|---|---|---|
| **`http-raw`** | **12–20 fps** | Termux + ffmpeg + IP Camera App | **手机默认**，零插件侧依赖 |
| `termux` | ~1 fps | Termux + Termux:API | 插件自己抓拍（只在插件直接跑在 Termux 里时用） |
| `mjpeg` / `rtsp` | 15–30 fps | 任意流源 | 插件侧需 ffmpeg（PRoot 里没有，慎用） |
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
  "grabIntervalMs": 70,
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
配置改了不用再重载插件：每次调用会比对 `see-phone.json`，变了就自动重建采集会话；
也可以显式 `see_phone({action:"reload"})`。（0.1.x 早期版本只在启动时读一次配置，已修。）

---

## 5. 实测性能

真机：HUAWEI nova 11（FOA-AL00），HarmonyOS 4.2 / Android 12，arm64。

| 环节 | 实测 |
|---|---|
| IP Camera MJPEG 源 | **~17 fps**（3 秒 49 帧） |
| Termux ffmpeg → rgb24 | 336×448×3 = **451584 B**，精确对上 |
| `/frame` 端到端 | **~25 ms** |
| 帧服务内部计数 | **19.8 fps**（`ok=382 fail=0 age=0.03`） |
| **插件端到端 8 秒** | **104 帧 = 12.95 fps**，出 6 帧宫格 |

⚠️ 插件的轮询间隔必须跟上：`grabIntervalMs` 默认 **70ms**（`http-raw` 钳到 80ms）。
抓拍时代的 220ms 会把 20fps 的源压成 4.4fps —— 这个坑实测踩过。

**悬浮预览**：`http://127.0.0.1:8123/view` 是实时画面页，`/stream` 是 MJPEG 流。
手机浏览器打开 `/view`，再从最近任务把浏览器切成小窗/悬浮窗，
就是「后台采集 + 悬浮预览」，不用编译原生 overlay App。

其他边界：
- 单帧 336×448 ≈ 130 token，**图会留在会话历史里每轮重发**；长时间看要留意
- 保持 IP Camera App 在后台（别从最近任务划掉），断流时帧服务会自动重连
- 用完记得 `stop`；持续采集会耗电发热

## 6. 当前装机进度

- [x] dsh-go APK 装上（本机模式），引导走完、引擎启动
- [x] Termux + Termux:API + Pillow + ffmpeg（`libc++` 27c→30 后能装）
- [x] **开源 MIT 的 IP Camera App 装上**，关掉认证/TLS → 纯 HTTP MJPEG
- [x] `cam_server.py` 流模式跑起来：`/frame`、`/jpg`、`/stream`、`/view`
- [x] **端到端 12.95fps** 验证通过，多帧宫格出图
- [x] 插件 `see_phone` 已装进 dsh-go（`plugin_manager install_bundle`）
- [ ] 把**更新后的**插件包/配置刷进 dsh-go：在它终端里跑一次
      `sh /sdcard/Download/install-on-phone.sh`（改了轮询间隔，装一次即可）

## 开源与商用

摄像头流 App：MIT License，允许商用、修改、再分发，无 copyleft 义务。
从[官方 GitHub Releases](https://github.com/DigitallyRefined/android-ip-camera/releases) 下载，
校验 SHA-256 `0e9d5fb859b9dc2d77f401f31964c412b22d6d787b88556a3001ea3b79f099fc`。
插件本体 Apache-2.0，引擎同 `dsh-see-now`。

## 7. 开发

```bash
npm run check:syntax      # 全量语法体检
npm test                  # 宿主契约测试（假 ctx，6 条）
npm run smoke testsrc 6   # 合成源跑通整条管线
DSH_SEE_PHONE_CONFIG='{"backend":"http-raw","url":"http://127.0.0.1:8123/frame"}' npm run smoke http-raw 10
```

## License

Apache-2.0。`src/engine/` 来自 [dsh-see-now](https://github.com/SAOS-3624/dsh-see-now)，同一许可证。
