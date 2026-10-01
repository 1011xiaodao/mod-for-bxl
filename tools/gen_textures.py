#!/usr/bin/env python3
"""程序化占位贴图生成（16×16 PNG，纯 stdlib）。美术资产到位后可直接替换同名文件。

- block/colony_core.png        钢板+铆钉（逻辑核心）
- block/construction_barrier.png 黄黑警示条纹（施工围挡）
- item/foundation_*.png ×9     各建筑地基（灰底+彩色内芯）
"""
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                    "assets", "pioneer_colony", "textures")


def write_png(path, pixels):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    height = len(pixels)
    width = len(pixels[0])
    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(struct.pack("4B", *px) for px in row)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print("wrote", os.path.relpath(path, os.path.join(ROOT, "..", "..", "..", "..", "..")))


def base(color):
    return [[color for _ in range(16)] for _ in range(16)]


def px(img, x, y, color):
    img[y][x] = color


def border(img, color):
    for i in range(16):
        px(img, i, 0, color)
        px(img, i, 15, color)
        px(img, 0, i, color)
        px(img, 15, i, color)


STEEL = (74, 85, 104, 255)
STEEL_DARK = (45, 55, 72, 255)
STEEL_LIGHT = (113, 128, 150, 255)
RIVET = (203, 213, 224, 255)
YELLOW = (246, 224, 94, 255)
BLACK = (32, 32, 36, 255)

# —— 逻辑核心（钢板 + 铆钉 + 中央标记） ——
core = base(STEEL)
border(core, STEEL_DARK)
for i in range(2, 14):
    for j in range(2, 14):
        if i in (2, 13) or j in (2, 13):
            px(core, i, j, STEEL_LIGHT)
for (x, y) in [(3, 3), (12, 3), (3, 12), (12, 12)]:
    px(core, x, y, RIVET)
for i in range(6, 10):
    for j in range(6, 10):
        px(core, i, j, (159, 200, 255, 255))
write_png(os.path.join(ROOT, "block", "colony_core.png"), core)

# —— 施工围挡（黄黑斜纹） ——
bar = []
for y in range(16):
    row = []
    for x in range(16):
        row.append(YELLOW if (x + y) % 8 < 4 else BLACK)
    bar.append(row)
border(bar, STEEL_DARK)
write_png(os.path.join(ROOT, "block", "construction_barrier.png"), bar)

# —— 地基物品（灰底 + 建筑色内芯） ——
FOUNDATION_COLORS = {
    "warehouse": (102, 170, 255, 255),
    "residence": (190, 140, 90, 255),
    "canteen": (240, 160, 60, 255),
    "farm": (110, 200, 110, 255),
    "smelter": (140, 140, 150, 255),
    "guard_post": (200, 110, 110, 255),
    "trade_station": (240, 210, 90, 255),
    "research_institute": (110, 220, 220, 255),
    "boiler_room": (200, 90, 60, 255),
}
for name, color in FOUNDATION_COLORS.items():
    img = base(STEEL_DARK)
    for i in range(3, 13):
        for j in range(3, 13):
            px(img, i, j, color)
    for i in range(6, 10):
        for j in range(6, 10):
            px(img, i, j, (255, 255, 255, 255))
    border(img, BLACK)
    write_png(os.path.join(ROOT, "item", "foundation_%s.png" % name), img)
