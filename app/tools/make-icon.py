#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成「鱼眼」的**占位**图标：深底 + 一只发亮的眼睛。纯标准库，不依赖 PIL。

⚠️ 正式图标是 `art/icon-source.jpg`（霓虹鱼眼标），已经生成好 `res/mipmap-*/ic.webp`
和 `res/mipmap-anydpi-v26/ic.xml` 一起入库了。**只有图标文件缺失时** build.sh 才会调这个脚本
补一个占位，免得新克隆的仓库编不出来。

为什么手写 PNG：这个工程刻意不带任何三方依赖（APK 才会只有 100KB 级），
图标是唯一需要绘图的地方，不值得为它装 Pillow。
抗锯齿用解析式覆盖度（不用超采样），144x144 一秒出图。

用法：python tools/make-icon.py
输出：res/mipmap-xxhdpi/ic.png （144x144）
"""
import math
import os
import struct
import zlib

S = 144
BG = (0x10, 0x18, 0x20)
RING = (0x62, 0xB0, 0xFF)
PUPIL = (0xF2, 0xF8, 0xFF)
GLOW = (0x4C, 0x9A, 0xFF)


def smoothstep(e0, e1, x):
    if e1 == e0:
        return 0.0 if x < e0 else 1.0
    t = (x - e0) / (e1 - e0)
    t = 0.0 if t < 0 else (1.0 if t > 1 else t)
    return t * t * (3 - 2 * t)


def mix(a, b, t):
    return (int(a[0] + (b[0] - a[0]) * t),
            int(a[1] + (b[1] - a[1]) * t),
            int(a[2] + (b[2] - a[2]) * t))


def sd_round_rect(x, y, w, h, r):
    qx = abs(x - w / 2.0) - (w / 2.0 - r)
    qy = abs(y - h / 2.0) - (h / 2.0 - r)
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def sample(x, y):
    sd = sd_round_rect(x, y, S, S, 30)
    a_shape = 1.0 - smoothstep(-0.6, 0.6, sd)
    if a_shape <= 0.002:
        return (0, 0, 0, 0)

    cx = cy = S / 2.0
    dx = x - cx
    dy = y - cy

    e_out = (dx / 54.0) ** 2 + (dy / 33.0) ** 2
    e_in = (dx / 44.0) ** 2 + (dy / 23.0) ** 2
    a_ring = min(smoothstep(0.0, 0.05, 1.0 - e_out), smoothstep(0.0, 0.06, e_in - 1.0))
    a_ring = max(0.0, min(1.0, a_ring))

    r = math.hypot(dx, dy)
    a_glow = (1.0 - smoothstep(15.0, 27.0, r)) * 0.55
    a_pupil = 1.0 - smoothstep(13.8, 15.2, r)

    col = BG
    col = mix(col, GLOW, a_glow)
    col = mix(col, PUPIL, a_pupil)
    col = mix(col, RING, a_ring)

    # 高光：瞳孔左上角
    hr = math.hypot(dx + 5.2, dy + 5.2)
    a_hi = (1.0 - smoothstep(3.4, 4.6, hr)) * a_pupil
    col = mix(col, (255, 255, 255), a_hi)

    return (col[0], col[1], col[2], int(round(a_shape * 255)))


def render():
    out = bytearray()
    for py in range(S):
        out.append(0)  # PNG filter type 0
        for px in range(S):
            r, g, b, a = sample(px + 0.5, py + 0.5)
            out += bytes([r, g, b, a])
    return bytes(out)


def write_png(path, w, h, raw):
    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
           + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.abspath(os.path.join(here, "..", "res", "mipmap-xxhdpi", "ic.png"))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    write_png(out, S, S, render())
    print("icon ->", out, os.path.getsize(out), "bytes")
