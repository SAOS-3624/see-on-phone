#!/bin/bash
# 版面渲染探针：把"一窗帧 → 一张宫格"真画成 PNG（纯 Java + AWT），肉眼验收版面。
# 图落在 tools/parity/out/：静止/平移/硬切 × 自适应/等分 × 4/6/8 格。
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$HERE/.work-render"

find "$WORK" -type f -delete 2>/dev/null || true
mkdir -p "$WORK/com/dsh/eye" "$WORK/tools/parity/out"

for f in Packer Tiers Tokens Pipeline Cuts CallSheet CallStream CallCapture EyeLog; do
  cp "$ROOT/src/com/dsh/eye/$f.java" "$WORK/com/dsh/eye/"
done
cp "$HERE/RenderProbe.java" "$WORK/com/dsh/eye/"

cd "$WORK"
javac -encoding UTF-8 -d out com/dsh/eye/*.java
java -Dfile.encoding=UTF-8 -Dout="$HERE/out" -cp out com.dsh.eye.RenderProbe
