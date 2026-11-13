#!/bin/bash
# 不同帧率下结果差多少 —— 用 App 的口径（判据全是秒 + 三处窗口按 fps 折算）。
#
# 判"区别不要太大"的三条：
#   ① 留帧理由的分布（cut/mark/floor）应该接近 —— 说明"什么算变化"没漂
#   ② 出图张数应该接近 —— 说明"多久说一句"没漂
#   ③ 每张覆盖的秒数应该接近
#
# 用法：bash multi-fps.sh   （素材由 .work 里的 kb-*.raw / pub-*.raw 提供）
cd "$(dirname "$0")" || exit 1
CP=.work/out

printf '%-6s %-4s %7s %7s %7s %7s %7s %8s\n' 素材 fps 帧数 留帧 切点 积分 兜底 出图
echo "---------------------------------------------------------------------------"

for label in kb pub; do
  for f in 8 12 15 24 30; do
    raw=".work/$label-$f.raw"
    [ -f "$raw" ] || continue
    err=$(java -cp "$CP" com.dsh.eye.PipelineCli "$raw" 240 320 "$f" default norm 2>&1 >/dev/null)
    sheets=$(java -cp "$CP" com.dsh.eye.PipelineCli "$raw" 240 320 "$f" default norm 2>/dev/null | wc -l)
    frames=$(echo "$err" | sed -n 's/.*frames=\([0-9]*\).*/\1/p')
    kept=$(echo "$err" | sed -n 's/.*kept=\([0-9]*\).*/\1/p')
    cuts=$(echo "$err" | sed -n 's/.*cuts=\([0-9]*\).*/\1/p')
    marks=$(echo "$err" | sed -n 's/.*marks=\([0-9]*\).*/\1/p')
    floors=$(echo "$err" | sed -n 's/.*floors=\([0-9]*\).*/\1/p')
    printf '%-6s %-4s %7s %7s %7s %7s %7s %8s\n' "$label" "$f" "$frames" "$kept" "$cuts" "$marks" "$floors" "$sheets"
  done
  echo "---------------------------------------------------------------------------"
done
