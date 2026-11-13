#!/bin/bash
# install.sh —— 一条命令装完「鱼眼」：**全程自己点弹窗，绝不留下等人点的框**。
#
# 目标：从敲下命令到出结果 **≤ 40 秒**。
#
# 踩过的四个坑（都写进代码了）：
#   1. 华为装包有两道弹窗（风险提示 → 纯净模式），只点第一道会退回纯净模式，
#      表现像"装不上"（`dumpsys` 的 versionCode 不变）。**两道都要点**。
#   2. `pm install` 在本机沙箱里会被 SIGTERM，但**设备上的安装会话还在** ——
#      所以不能"装完再看"，只能**边等边点**。
#   3. 应用市场开着的时候，"纯净模式"那道是它**伪造的**：按钮 `enabled="false"`，
#      点了不装（看着像装上了，`lastUpdateTime` 不动）→ 先 `pm disable-user`。
#   4. ⚠️ **`uiautomator dump` 在界面动画期间会卡 8~25 秒**。之前脚本"每 2 秒 dump 一次
#      直到成功"，跑成十分钟，被外层超时 SIGTERM —— 弹窗正好留在屏幕上，
#      用户看到的就是"又要我点"。所以：**全程最多 dump 两次**，其余靠 `dumpsys` 轮询。
#
# 用法：
#   bash tools/install.sh                 # 装 ../see-on-phone.apk
#   bash tools/install.sh path/to/x.apk
#
# 退出码：0 = 装上了且版本对得上；1 = 没装上（已把弹窗关掉并打印原因）。
set -u

# Git Bash 会把 `/data/local/tmp/x.apk` 这种"看起来像路径"的参数改写成 Windows 路径
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

HERE="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
# PATH 上没有 adb 就按 SDK 环境变量找（不写死任何本机路径）
if ! command -v "$ADB" >/dev/null 2>&1; then
  for c in "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK:-}/platform-tools/adb"; do
    [ -x "$c" ] && ADB="$c" && break
  done
fi
command -v "$ADB" >/dev/null 2>&1 || { echo "找不到 adb：装 platform-tools 或设 ADB=<路径>"; exit 1; }
PKG=com.dsh.eye
APK="${1:-$HERE/see-on-phone.apk}"
MANIFEST="$HERE/AndroidManifest.xml"

WANT_CODE=$(grep -o 'android:versionCode="[0-9]*"' "$MANIFEST" | head -1 | grep -o '[0-9]*')
WANT_NAME=$(grep -o 'android:versionName="[^"]*"' "$MANIFEST" | head -1 | sed 's/.*="//;s/"//')

WORK="$HERE/.tmp/install-work"
mkdir -p "$WORK"

say() { echo "[install] $*"; }
AS()  { timeout 15 "$ADB" shell "$@" 2>/dev/null; }

cur_code() { AS "dumpsys package $PKG | grep -m1 versionCode" | grep -o '[0-9]*' | head -1; }

# 抓一次 UI，返回「继续安装」按钮中心坐标（没有就空）。**硬超 6 秒**，卡住就算了。
button_center() {
  # 这台机器 dump 一次要 20 秒以上（界面在动画时会卡更久），20 秒是**必然失败**的值。
  timeout 30 "$ADB" shell "uiautomator dump /sdcard/_inst.xml" >/dev/null 2>&1
  timeout 10 "$ADB" exec-out cat /sdcard/_inst.xml > "$WORK/ui.xml" 2>/dev/null
  [ -s "$WORK/ui.xml" ] || return 0
  python - "$WORK/ui.xml" <<'PY'
import re, sys
try:
    x = open(sys.argv[1], encoding='utf-8', errors='ignore').read()
except Exception:
    sys.exit(0)
for m in re.finditer(r'text="继续安装"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
}

# 点一次「继续安装」。**只有第 1 次允许盲点**（弹窗刚出来时 dump 常常来不及）。
#
# ⚠️ 后面的轮次必须 dump 到按钮才点：弹窗一消失，同一个坐标就落到别的按钮上 ——
#    实测盲点第二次直接点到「取消」，adb 报 `INSTALL_FAILED_ABORTED: User rejected permissions`。
tap_continue() {
  local c
  c=$(button_center)
  if [ -z "$c" ]; then
    # dump 不出来才走这里。**这台机器有两道形状完全不同的弹窗，按钮位置差得很远**：
    #
    #   弹窗                         「继续安装」bounds        中心          出现时机
    #   风险提示（标题+说明+取消/继续） [555,2128][1000,2186]   (777,2157)   正常情况
    #   纯净模式（图标+三行大按钮）      [72,1936][1012,2056]    (542,1996)   市场被启用时，它伪造的
    #
    # 所以第 1 次猜风险提示，第 2 次猜纯净模式 —— 而不是两次都点同一个地方
    # （以前两次都点 (777,2157)，在纯净模式页面上会落到「取消」，
    #   报 INSTALL_FAILED_ABORTED: User rejected permissions）。
    case "${1:-0}" in
      1) c="777 2157" ;;
      2) c="542 1996" ;;
      *) return 1 ;;
    esac
  fi
  set -- $c
  say "点「继续安装」($1,$2)"
  AS "input tap $1 $2"
  return 0
}

cleanup_dialog() {
  if [ -n "$(button_center)" ]; then
    say "收尾：把残留的安装弹窗关掉"
    AS "input keyevent 4"
    sleep 1
    AS "input keyevent 4"
  fi
}

# ---------------------------------------------------------------- 开始

[ -f "$APK" ] || { echo "[install] ✗ 找不到 APK：$APK" >&2; exit 1; }
timeout 10 "$ADB" get-state >/dev/null 2>&1 || { echo "[install] ✗ 设备没连上" >&2; exit 1; }
say "目标 $PKG $WANT_NAME (versionCode $WANT_CODE)"

if [ "$(cur_code)" = "$WANT_CODE" ]; then
  say "设备上已经是这个版本，跳过"
  cleanup_dialog
  exit 0
fi

# 市场开着就关掉（它会伪造一道点了没用的弹窗）
#
# ⚠️ 还要**先把市场进程干掉**：实测它前台的验证码界面（CaptchaActivity）会一直压在上面，
#    光 `disable-user` 只是禁止下次启动，眼前那个界面还在 —— 表现就是 pm install 返回空、
#    版本永远不变（我在 0.24.0 上卡了三轮才发现是验证码界面）。
AS "am force-stop com.huawei.appmarket" >/dev/null
AS "pm disable-user com.huawei.appmarket" >/dev/null

APK_WIN="$(cygpath -w "$APK" 2>/dev/null || echo "$APK")"
say "推送…"
timeout 45 "$ADB" push "$APK_WIN" /data/local/tmp/_dsh.apk >/dev/null 2>&1 \
  || { echo "[install] ✗ 推送失败" >&2; exit 1; }
AS "am force-stop $PKG" >/dev/null

say "安装…"
# ⚠️ 用 `adb install` 而不是 `pm install`：
#   `pm install` 在设备上自建 session 后 shell 立刻返回，adb 断开 —— 华为的
#   "风险提示"一拦，session 就成了没人接收的空壳，界面永远停在"正在准备安装应用"，
#   版本号一直不变（点对按钮也没用，因为背后没有活着的 session 了）。
#   `adb install` 会一直守着这条 session 等确认，点完就能落地。
( timeout 150 "$ADB" install -r -d "$APK_WIN" >"$WORK/pm.log" 2>&1 & )

sleep 4
tap_continue 1
sleep 4
# 第二次**带 dump 保护**：dump 到按钮就点它的真实坐标（纯净模式弹窗靠这一步），
# dump 不到才退回猜坐标；弹窗已经不在了就什么都不做（不会误点「取消」）。
tap_continue 2

ok=0
for i in $(seq 1 14); do
  if [ "$(cur_code)" = "$WANT_CODE" ]; then ok=1; break; fi
  sleep 2
  # 第 6、9、12 轮再试：tap_continue 会先 dump，**只有按钮真的还在**才会点
  if [ "$i" = "6" ] || [ "$i" = "9" ] || [ "$i" = "12" ]; then tap_continue "$i" >/dev/null 2>&1; fi
done

if [ "$ok" = "1" ]; then
  name=$(AS "dumpsys package $PKG | grep -m1 versionName" | sed 's/.*=//')
  # 装完可能停在「安装完成」页（一屏应用推荐 + 完成/探索更多）：点「完成」(300,2161) 离开，
  # 点不中也无所谓，后面再按一次返回
  AS "input tap 300 2161"
  sleep 1
  AS "input keyevent 4"
  cleanup_dialog
  # ⚠️ 刻意**不**把应用市场 enable 回来：启用状态下那道"纯净模式"弹窗是它伪造的
  #    （按钮 enabled=false，点了不装）。装一次禁一次，别来回折腾。
  #    想恢复自己敲：adb shell pm enable com.huawei.appmarket
  say "✓ 装上了：versionCode=$WANT_CODE versionName=$name"
  exit 0
fi

echo "--- pm install 输出 ---" >&2
tail -5 "$WORK/pm.log" >&2 2>/dev/null
say "✗ 没装上（设备上还是 $(cur_code)）"
cleanup_dialog
# ⚠️ 失败时**也不要** enable 应用市场。以前这里是 `pm enable`，结果下一次安装
#    又会多出一道"纯净模式"（市场伪造的），按钮位置还不一样 —— 越失败越难装。
#    想恢复自己敲：adb shell pm enable com.huawei.appmarket
exit 1
