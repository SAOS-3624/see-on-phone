# App 构建说明

> 目标：**没有 Gradle、没有 Android Studio、没有任何三方库**，一条 `build.sh` 出可安装的 APK。
> 如果你只想要装好的包，去 [Releases](https://github.com/SAOS-3624/see-on-phone/releases) 下 APK，这一页不用看。

## 1. 你需要的

| 东西 | 说明 |
|---|---|
| JDK 17 | `javac` / `java` / `keytool` 在 PATH 里 |
| `android.jar` (API 33) | 随便哪份 Android SDK 里的 `platforms/android-33/android.jar` |
| `d8.jar` | `build-tools/<ver>/lib/d8.jar` |
| `aapt2` | `build-tools/<ver>/aapt2[.exe]` |
| `apksigner.jar` | `build-tools/<ver>/lib/apksigner.jar` |

```bash
export ANDROID_SDK=/path/to/sdk            # 认 ANDROID_SDK 或 ANDROID_HOME
bash build.sh
```

`build.sh` 找 SDK 的顺序：`$ANDROID_SDK` → `$ANDROID_HOME` → `../sdk/android-13` → `./sdk/android-13`。
后两个是给"SDK 就放在仓库旁边"的离线包场景留的，正常用环境变量就行。

产物：`see-on-phone.apk`（约 190 KB）。

## 2. 六步在干什么

```
[0] 图标      res/mipmap-*/ic*.png 已在仓库里；缺失时才用 tools/make-icon.py 生成占位
[1] javac     -encoding UTF-8 -source 8 -target 8，源码全在 src/
[2] d8        160+ 个 .class → 一个 classes.dex
[3] aapt2     compile --dir res → res.zip
[4] aapt2     link --manifest AndroidManifest.xml → unsigned.apk
[5] 塞 dex    用 Python zipfile 把 classes.dex 追加进去（aapt2 不管这一步）
[6] 签名      apksigner sign --ks dsh.keystore
```

## 3. 四个把人坑过的点

**① `javac` 默认用 GBK。** 源码里有中文注释，不加 `-encoding UTF-8` 直接编译失败，报的错还看不懂。

**② JDK 17 不认 `d8.bat` / `apksigner.bat` 里的 `-Djava.ext.dirs`。**
必须 `java -cp lib/d8.jar com.android.tools.r8.D8` 这样直接调 jar，别用 `.bat`。

**③ 构建目录必须清干净。**
删掉 `Foo.java` 之后，`build/classes/` 里残留的 `Foo.class` 还会被 d8 打进 APK ——
表现出来就是"代码删了，包里还能搜到"。
所以第 0 步会清 `build/`，而且用的是 `find -delete` 而不是 `rm -rf`：Windows 上某些安全软件
会对批量删除下手，`rm` 会被打断，`find -delete` 不会。

**④ 改版本号要改两处。**
`AndroidManifest.xml` 的 `versionCode`+`versionName`，和 `BuildConfig.java` 的 `VERSION`。
只改一处的话：`versionCode` 没升 → `adb install -r` 会以为没变化而跳过；
`BuildConfig.VERSION` 没改 → 设置页显示的还是老版本，排障时会被误导。

## 4. 签名

仓库里**不含**密钥。`build.sh` 第一次跑会自己生成一个 `dsh.keystore`（口令 `android`，仅本地开发用）。

⚠️ 因此**你自己编译的 APK 和 Release 里的签名不一样**，覆盖安装会报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。装之前先卸掉官方包：

```bash
adb uninstall com.dsh.eye
```

（卸载会丢掉本地会话与工作区文件 —— 先在设置里导出一次。）

## 5. 装到手机

```bash
adb install -r see-on-phone.apk
```

`sideload` 安装，系统会提示"未知来源/风险应用"，正常放行即可。

## 6. 调试

- **release 包的 logcat 里几乎没有东西**（日志被重定向了）。看
  `Download/DSH-Go/eye.log`，用文件管理器或：

  ```bash
  adb shell tail -40 /sdcard/Download/DSH-Go/eye.log
  ```

- **HTTP 端点**在 `127.0.0.1:4444`，从电脑上访问要先转发：

  ```bash
  adb forward tcp:14444 tcp:4444
  curl http://127.0.0.1:14444/status
  ```

  （`adb forward` 和用它的命令要写在同一条 shell 里 —— adb server 被回收后转发会静默消失。）
