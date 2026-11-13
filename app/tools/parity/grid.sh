#!/bin/bash
# 通话宫格离线探针：N=1..8 各自排成什么样（箱数 / 格子数 / 每格像素 / 比例）。
#
# 为什么要在电脑上跑：真机一次"编译 → 装 → 点进通话页 → 等 20 秒"要 3 分钟，
# 而"帧到底有没有全进格子"是纯几何问题，不该靠肉眼在手机上看。
# 这里编的是 src 里那几份**原文件**（不复制副本），改完代码立刻能验。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work-grid"

find "$WORK" -type f -delete 2>/dev/null || true
mkdir -p "$WORK/com/dsh/eye"

# 纯 Java 的那几个（不许 import android.*），见各自文件头
for f in Packer Tiers Tokens Pipeline Cuts CallSheet CallStream CallCapture EyeLog; do
  cp "$ROOT/src/com/dsh/eye/$f.java" "$WORK/com/dsh/eye/"
done
cp "$HERE/GridProbe.java" "$WORK/com/dsh/eye/"

cd "$WORK"
javac -encoding UTF-8 -d out com/dsh/eye/*.java
java -Dfile.encoding=UTF-8 -cp out com.dsh.eye.GridProbe
