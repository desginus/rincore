#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""原图标注与局部放大（学科无关）：在学生的题目图上做**讲解式标注**。

为什么需要它
------------
读题时经常遇到两种情况：
1. 尺寸/数值字太小 → 需要**把那一小块放大**再贴回图上，让人一眼看清；
2. 题目给了一张图（受力图、电路图、几何图、统计图、流程图）→ 讲解时
   要在**原图上**圈出关键部位、画箭头、标注意义，而不是另画一张。

本工具只做「画」这一件事，不做任何物理判断：所有坐标由调用方给出。
坐标默认是**归一化 0–1**（相对当前底图，左上角为原点），也可用 `px: true` 给像素。

`grid` 操作会铺一张带刻度的 0–1 坐标网格——看不到图的调用方可以先用它出图、
看清目标所在的格子，再用精确坐标来标注。这是"盲标注"变"准标注"的关键一步。

spec 结构
---------
{
  "src": "题目.png",                 # 必填
  "out": "标注.png",                 # 可选
  "ops": [ {...}, ... ],            # 按顺序作用在当前底图上
  "max_side": 2400,                 # 可选：底图长边上限（超过则等比缩小）
  "note": ["底部说明"]
}

支持的 op
--------
crop       box:[l,t,r,b]                   裁掉四周，之后所有坐标相对新底图
box        box, label, color, width        矩形框（圈重点）
highlight  box, color, alpha               半透明色块（高亮区域）
circle     center:[x,y], r, label, color   圆（圈住某个点）
ellipse    center, rx, ry, label, color    椭圆
arrow      from, to, label, color          箭头（力的方向、流程走向、视线引导）
line       from, to, color, width          直线（连线、辅助线）
dimension  from, to, text, offset          尺寸线：两端界线 + 双箭头 + 数值
text       at, text, size, color, box      文字（可带白底）
zoom       box, factor, at, label          把 box 区域放大 factor 倍，贴到 at 位置并引线相连
grid       cols, rows, labels, color       铺 0–1 坐标网格（用于定位）
invert     -                               反色（深色底图看不清时）
grayscale  -                               灰度

所有 op 都支持 `px: true` 表示坐标是像素而不是 0–1 归一化。
失败一律返回可读中文错误，不静默产出错图。
"""

import json
import math
import os
import sys

try:  # stdout 必须是 UTF-8，避免中文在 GBK 控制台下被打碎
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

from PIL import Image, ImageDraw, ImageFont, ImageOps

FONT_DIR = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
REGULAR_FONTS = ["msyh.ttc", "simhei.ttf", "simsun.ttc", "Deng.ttf", "msyhl.ttc", "arial.ttf"]
BOLD_FONTS = ["msyhbd.ttc", "simhei.ttf", "msyh.ttc", "arialbd.ttf"]

INK = (32, 41, 55, 255)
ACCENT = (198, 40, 40, 255)
BLUE = (28, 96, 190, 255)
GREEN = (33, 140, 74, 255)
ORANGE = (200, 120, 10, 255)
WHITE = (255, 255, 255, 255)
GRID = (120, 140, 165, 90)

NAMED_COLORS = {
    "red": ACCENT, "blue": BLUE, "green": GREEN, "orange": ORANGE,
    "black": INK, "white": WHITE, "gray": (120, 130, 145, 255),
    "yellow": (230, 190, 20, 255), "purple": (140, 80, 180, 255),
}


class SpecError(Exception):
    """spec 非法（可读中文原因）。"""


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------
def _load(src, max_side=None):
    if not isinstance(src, str) or not src.strip():
        raise SpecError("src 必须是图片路径")
    path = os.path.abspath(src)
    if not os.path.exists(path):
        raise SpecError("图片不存在：%s" % path)
    try:
        image = Image.open(path)
        image = ImageOps.exif_transpose(image)   # 手机照片的旋转信息要落到实处
        image = image.convert("RGBA")
    except Exception as exc:
        raise SpecError("无法读取图片 %s：%s" % (path, exc))
    if max_side:
        limit = int(max_side)
        long_side = max(image.size)
        if long_side > limit > 0:
            ratio = limit / float(long_side)
            image = image.resize(
                (max(1, int(image.width * ratio)), max(1, int(image.height * ratio))),
                Image.LANCZOS,
            )
    return image


def _color(value, default):
    if value is None:
        return default
    if isinstance(value, (list, tuple)):
        parts = list(value) + [255] * (4 - len(value))
        return tuple(int(max(0, min(255, v))) for v in parts[:4])
    text = str(value).strip().lower()
    if text in NAMED_COLORS:
        return NAMED_COLORS[text]
    if text.startswith("#"):
        body = text[1:]
        if len(body) == 3:
            body = "".join(ch * 2 for ch in body)
        if len(body) == 6:
            return (int(body[0:2], 16), int(body[2:4], 16), int(body[4:6], 16), 255)
        if len(body) == 8:
            return (int(body[0:2], 16), int(body[2:4], 16), int(body[4:6], 16), int(body[6:8], 16))
    raise SpecError("无法识别的颜色 %r（用 #rrggbb 或 red/blue/green/orange/gray/black）" % value)


def _font(size, bold=False):
    names = BOLD_FONTS if bold else REGULAR_FONTS
    for name in names:
        path = os.path.join(FONT_DIR, name)
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, int(size))
            except Exception:
                continue
    return ImageFont.load_default()


def _text_size(draw, text, font):
    try:
        box = draw.textbbox((0, 0), text, font=font)
        return box[2] - box[0], box[3] - box[1]
    except Exception:
        return len(str(text)) * 7, 14


def _to_px_box(box, size, px=False):
    """把 box 归一化成像素 [l,t,r,b]，并做越界收敛。"""
    width, height = size
    if not isinstance(box, (list, tuple)) or len(box) != 4:
        raise SpecError("box 必须是 [l,t,r,b] 四个数")
    try:
        values = [float(v) for v in box]
    except Exception:
        raise SpecError("box 必须是数值：[l,t,r,b]")
    if not px:
        values = [values[0] * width, values[1] * height, values[2] * width, values[3] * height]
    left, top, right, bottom = values
    if right < left:
        left, right = right, left
    if bottom < top:
        top, bottom = bottom, top
    left, right = max(0, min(width, left)), max(0, min(width, right))
    top, bottom = max(0, min(height, top)), max(0, min(height, bottom))
    if right - left < 2 or bottom - top < 2:
        raise SpecError("box 太小或完全越界（像素范围内不足 2px）：%s" % (box,))
    return (int(round(left)), int(round(top)), int(round(right)), int(round(bottom)))


def _to_px_point(point, size, px=False):
    width, height = size
    if isinstance(point, dict):
        point = [point.get("x", 0.0), point.get("y", 0.0)]
    if not isinstance(point, (list, tuple)) or len(point) != 2:
        raise SpecError("坐标必须是 [x,y] 或 {x,y}")
    try:
        x, y = float(point[0]), float(point[1])
    except Exception:
        raise SpecError("坐标必须是数值：[x,y]")
    if not px:
        x, y = x * width, y * height
    return (int(round(max(0, min(width, x)))), int(round(max(0, min(height, y)))))


def _draw_label(draw, xy, text, font, fill, anchor="la", bg=True, pad=4):
    """带白底的文字，避免压在图上读不出来。"""
    if not text:
        return
    x, y = xy
    if bg:
        w, h = _text_size(draw, str(text), font)
        # anchor 决定文字相对 (x,y) 的哪个角；这里只按常用几种做白底
        offsets = {
            "la": (0, 0), "lm": (0, -h / 2.0), "lb": (0, -h),
            "ma": (-w / 2.0, 0), "mm": (-w / 2.0, -h / 2.0), "mb": (-w / 2.0, -h),
            "ra": (-w, 0), "rm": (-w, -h / 2.0), "rb": (-w, -h),
        }
        dx, dy = offsets.get(anchor, (0, 0))
        box = [x + dx - pad, y + dy - pad, x + dx + w + pad, y + dy + h + pad]
        draw.rounded_rectangle(box, radius=max(2, pad // 2), fill=(255, 255, 255, 215))
    draw.text((x, y), str(text), font=font, fill=fill, anchor=anchor)


def _arrow(draw, start, end, color, width, head=None):
    """画一条带箭头的线（箭头用实心三角形，方向自动）。"""
    x0, y0 = start
    x1, y1 = end
    length = math.hypot(x1 - x0, y1 - y0)
    if length < 1:
        return
    size = float(head or max(10.0, width * 5.0))
    angle = math.atan2(y1 - y0, x1 - x0)
    # 线画到箭尾，避免线头穿透箭头
    bx, by = x1 - size * 0.82 * math.cos(angle), y1 - size * 0.82 * math.sin(angle)
    draw.line([x0, y0, bx, by], fill=color, width=max(1, int(width)))
    left = (x1 - size * math.cos(angle - math.pi / 7.0), y1 - size * math.sin(angle - math.pi / 7.0))
    right = (x1 - size * math.cos(angle + math.pi / 7.0), y1 - size * math.sin(angle + math.pi / 7.0))
    draw.polygon([(x1, y1), left, right], fill=color)


def _with_alpha(image, alpha):
    """把整张图按 alpha 变淡，用于做放大镜垫底。"""
    layer = image.copy()
    if layer.mode != "RGBA":
        layer = layer.convert("RGBA")
    channel = layer.split()[3].point(lambda value: int(value * float(alpha)))
    layer.putalpha(channel)
    return layer


# --------------------------------------------------------------------------
# 各个 op
# --------------------------------------------------------------------------
def op_crop(image, op, state):
    box = _to_px_box(op.get("box"), image.size, bool(op.get("px")))
    state["notes"].append("crop 到 %s（之后的坐标相对新底图）" % (box,))
    return image.crop(box)


def op_box(image, op, state):
    draw = ImageDraw.Draw(image, "RGBA")
    box = _to_px_box(op.get("box"), image.size, bool(op.get("px")))
    color = _color(op.get("color"), ACCENT)
    width = int(op.get("width") or max(2, round(min(image.size) / 320.0)))
    draw.rectangle(box, outline=color, width=width)
    if op.get("label"):
        font = _font(int(op.get("size") or max(15, min(image.size) / 42.0)), bold=True)
        _draw_label(draw, (box[0] + 2, box[1] - 3), op["label"], font, color, anchor="lb")
    state["kinds"].append("box")
    return image


def op_highlight(image, op, state):
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    box = _to_px_box(op.get("box"), image.size, bool(op.get("px")))
    color = _color(op.get("color"), (255, 214, 10, 255))
    alpha = float(op.get("alpha", 0.3))
    draw.rectangle(box, fill=(color[0], color[1], color[2], int(255 * max(0.0, min(1.0, alpha)))))
    state["kinds"].append("highlight")
    return Image.alpha_composite(image, layer)


def op_circle(image, op, state):
    draw = ImageDraw.Draw(image, "RGBA")
    center = _to_px_point(op.get("center"), image.size, bool(op.get("px")))
    if op.get("r") is None:
        raise SpecError("circle 需要 r（归一化时是相对图片宽度的比例）")
    radius = float(op["r"]) * (1.0 if op.get("px") else image.size[0])
    color = _color(op.get("color"), ACCENT)
    width = int(op.get("width") or max(2, round(min(image.size) / 320.0)))
    draw.ellipse([center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius],
                 outline=color, width=width)
    if op.get("label"):
        font = _font(int(op.get("size") or max(15, min(image.size) / 42.0)), bold=True)
        _draw_label(draw, (center[0] + radius + 4, center[1] - radius), op["label"], font, color, anchor="lb")
    state["kinds"].append("circle")
    return image


def op_ellipse(image, op, state):
    draw = ImageDraw.Draw(image, "RGBA")
    center = _to_px_point(op.get("center"), image.size, bool(op.get("px")))
    scale = 1.0 if op.get("px") else image.size[0]
    rx = float(op.get("rx") or op.get("r") or 0.1) * scale
    ry = float(op.get("ry") or op.get("r") or 0.1) * scale
    color = _color(op.get("color"), ACCENT)
    width = int(op.get("width") or max(2, round(min(image.size) / 320.0)))
    draw.ellipse([center[0] - rx, center[1] - ry, center[0] + rx, center[1] + ry], outline=color, width=width)
    state["kinds"].append("ellipse")
    return image


def op_arrow(image, op, state):
    draw = ImageDraw.Draw(image, "RGBA")
    px = bool(op.get("px"))
    start = _to_px_point(op.get("from") or op.get("xytext"), image.size, px)
    end = _to_px_point(op.get("to") or op.get("xy"), image.size, px)
    color = _color(op.get("color"), BLUE)
    width = int(op.get("width") or max(2, round(min(image.size) / 380.0)))
    _arrow(draw, start, end, color, width, op.get("head"))
    if op.get("label"):
        font = _font(int(op.get("size") or max(15, min(image.size) / 44.0)), bold=True)
        mid = ((start[0] + end[0]) / 2.0, (start[1] + end[1]) / 2.0)
        _draw_label(draw, (mid[0] + 6, mid[1] - 6), op["label"], font, color, anchor="lb")
    state["kinds"].append("arrow")
    return image


def op_line(image, op, state):
    draw = ImageDraw.Draw(image, "RGBA")
    px = bool(op.get("px"))
    start = _to_px_point(op.get("from"), image.size, px)
    end = _to_px_point(op.get("to"), image.size, px)
    color = _color(op.get("color"), INK)
    width = int(op.get("width") or max(1, round(min(image.size) / 500.0)))
    draw.line([start, end], fill=color, width=width)
    state["kinds"].append("line")
    return image


def op_dimension(image, op, state):
    """尺寸线：两端界线 + 双向箭头 + 数值（工程图讲解常用）。"""
    draw = ImageDraw.Draw(image, "RGBA")
    px = bool(op.get("px"))
    start = _to_px_point(op.get("from"), image.size, px)
    end = _to_px_point(op.get("to"), image.size, px)
    color = _color(op.get("color"), GREEN)
    width = int(op.get("width") or max(2, round(min(image.size) / 420.0)))
    offset = op.get("offset")
    if isinstance(offset, (list, tuple)) and len(offset) == 2:
        off = (float(offset[0]) * (1.0 if px else image.size[0]),
               float(offset[1]) * (1.0 if px else image.size[1]))
    else:
        off = (0.0, 0.0)
    a = (start[0] + off[0], start[1] + off[1])
    b = (end[0] + off[0], end[1] + off[1])
    tick = max(6.0, min(image.size) / 90.0)
    dx, dy = b[0] - a[0], b[1] - a[1]
    length = math.hypot(dx, dy) or 1.0
    nx, ny = -dy / length * tick, dx / length * tick
    for point in (a, b):
        draw.line([point[0] - nx, point[1] - ny, point[0] + nx, point[1] + ny], fill=color, width=width)
    # 双箭头 + 中间留白给数字
    text = str(op.get("text") or "")
    font = _font(int(op.get("size") or max(15, min(image.size) / 44.0)), bold=True)
    gap = 0.0
    if text:
        text_w, _ = _text_size(draw, text, font)
        gap = text_w + 14
    ux, uy = dx / length, dy / length
    mid = ((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0)
    end_a = (mid[0] - ux * gap / 2.0, mid[1] - uy * gap / 2.0)
    start_b = (mid[0] + ux * gap / 2.0, mid[1] + uy * gap / 2.0)
    _arrow(draw, a, end_a, color, width)
    _arrow(draw, b, start_b, color, width)
    if text:
        _draw_label(draw, (mid[0], mid[1]), text, font, color, anchor="mm")
    state["kinds"].append("dimension")
    return image


def op_text(image, op, state):
    draw = ImageDraw.Draw(image, "RGBA")
    # 允许写 at:[x,y]，也允许直接把 x / y 写在 op 上（模型很容易这么写，不该因此报错）
    at_raw = op.get("at")
    if at_raw is None and "x" in op and "y" in op:
        at_raw = {"x": op.get("x"), "y": op.get("y")}
    at = _to_px_point(at_raw, image.size, bool(op.get("px")))
    font = _font(int(op.get("size") or max(16, min(image.size) / 40.0)), bold=bool(op.get("bold", True)))
    color = _color(op.get("color"), INK)
    text = op.get("text")
    if text is None:
        raise SpecError("text 需要 text 字段")
    _draw_label(draw, at, text, font, color, anchor=op.get("anchor", "la"), bg=bool(op.get("box", True)))
    state["kinds"].append("text")
    return image


def op_zoom(image, op, state):
    """把 box 区域放大 factor 倍，贴到 at 位置，并用引线连回原位。"""
    box = _to_px_box(op.get("box"), image.size, bool(op.get("px")))
    factor = float(op.get("factor") or 2.5)
    if factor <= 1.0:
        raise SpecError("zoom 的 factor 必须大于 1（当前 %s）" % factor)
    if factor > 16:
        raise SpecError("zoom 的 factor 太大（%s），请分次放大" % factor)
    region = image.crop(box)
    target = (max(1, int(region.width * factor)), max(1, int(region.height * factor)))
    # 放大后不能超过底图短边的 0.75，避免整张图被放大镜撑爆
    cap = max(80, int(min(image.size) * 0.75))
    if max(target) > cap:
        ratio = cap / float(max(target))
        target = (max(1, int(target[0] * ratio)), max(1, int(target[1] * ratio)))
        state["warnings"].append(
            "zoom 目标尺寸受限于底图短边（上限 %d px），实际放大倍数约为 %.2f"
            % (cap, target[0] / float(max(1, region.width)))
        )
    magnified = region.resize(target, Image.LANCZOS)

    at = op.get("at")
    if at is None:
        at = "tr"
    if isinstance(at, str):
        margin = int(min(image.size) * 0.02)
        corners = {
            "tl": (margin, margin), "tr": (image.size[0] - margin - target[0], margin),
            "bl": (margin, image.size[1] - margin - target[1]),
            "br": (image.size[0] - margin - target[0], image.size[1] - margin - target[1]),
        }
        if at not in corners:
            raise SpecError("zoom 的 at 只能是 tl/tr/bl/br 或 [x,y]（收到 %r）" % at)
        pos = corners[at]
    else:
        pos = _to_px_point(at, image.size, bool(op.get("px")))
    pos = (int(max(0, min(image.size[0] - target[0], pos[0]))),
           int(max(0, min(image.size[1] - target[1], pos[1]))))

    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    border = _color(op.get("color"), ORANGE)
    # 引线：从原区域右下角连到放大框左上角
    start = (box[2], box[3])
    end = (pos[0], pos[1] + target[1] * 0.0)
    draw.line([start, (start[0], end[1]), end], fill=border, width=max(2, round(min(image.size) / 450.0)))
    draw.rectangle([box[0], box[1], box[2], box[3]], outline=border, width=max(2, round(min(image.size) / 400.0)))
    layer.paste(magnified, pos)
    draw.rectangle([pos[0] - 2, pos[1] - 2, pos[0] + target[0] + 2, pos[1] + target[1] + 2],
                   outline=border, width=max(2, round(min(image.size) / 350.0)))
    if op.get("label"):
        font = _font(int(op.get("size") or max(15, min(image.size) / 44.0)), bold=True)
        _draw_label(draw, (pos[0], pos[1] - 4), op["label"], font, border, anchor="lb")
    state["kinds"].append("zoom")
    state["notes"].append(
        "zoom：%s → 放大 %.2f×（%dx%d），贴在 %s" % (box, target[0] / float(max(1, region.width)),
                                                target[0], target[1], pos)
    )
    return Image.alpha_composite(image, layer)


def op_grid(image, op, state):
    """铺 0–1 坐标网格并标刻度：看不到图的调用方靠它定位目标区域。"""
    layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")
    cols = int(op.get("cols") or 10)
    rows = int(op.get("rows") or 10)
    cols, rows = max(2, min(40, cols)), max(2, min(40, rows))
    color = _color(op.get("color"), GRID)
    width = max(1, round(min(image.size) / 900.0))
    font = _font(int(max(12, min(image.size) / 55.0)))
    for i in range(cols + 1):
        x = image.size[0] * i / float(cols)
        strong = i in (0, cols) or i * 2 == cols
        draw.line([x, 0, x, image.size[1]], fill=color if not strong else (90, 110, 140, 190),
                  width=width + (1 if strong else 0))
        if op.get("labels", True) and i < cols:
            draw.text((x + 3, 3), "%.2f" % (i / float(cols)), font=font, fill=(70, 85, 110, 230))
    for j in range(rows + 1):
        y = image.size[1] * j / float(rows)
        strong = j in (0, rows) or j * 2 == rows
        draw.line([0, y, image.size[0], y], fill=color if not strong else (90, 110, 140, 190),
                  width=width + (1 if strong else 0))
        if op.get("labels", True) and j < rows:
            draw.text((3, y + 3), "%.2f" % (j / float(rows)), font=font, fill=(70, 85, 110, 230))
    state["kinds"].append("grid")
    state["notes"].append("grid：%d×%d 的 0–1 坐标网格（左上角为原点，x 向右、y 向下）" % (cols, rows))
    return Image.alpha_composite(image, layer)


def op_invert(image, op, state):
    from PIL import ImageChops
    state["kinds"].append("invert")
    return ImageChops.invert(image.convert("RGB")).convert("RGBA")


def op_grayscale(image, op, state):
    state["kinds"].append("grayscale")
    return ImageOps.grayscale(image).convert("RGBA")


OPS = {
    "crop": op_crop,
    "box": op_box,
    "highlight": op_highlight,
    "circle": op_circle,
    "ellipse": op_ellipse,
    "arrow": op_arrow,
    "line": op_line,
    "dimension": op_dimension,
    "text": op_text,
    "zoom": op_zoom,
    "magnifier": op_zoom,
    "grid": op_grid,
    "invert": op_invert,
    "grayscale": op_grayscale,
}


def annotate(spec):
    if not isinstance(spec, dict):
        raise SpecError("spec 必须是 JSON 对象")
    source = spec.get("src")
    image = _load(source, spec.get("max_side"))
    state = {"notes": [], "warnings": [], "kinds": []}

    ops = spec.get("ops") or []
    if not isinstance(ops, list):
        raise SpecError("ops 必须是数组")
    for index, op in enumerate(ops):
        if not isinstance(op, dict):
            raise SpecError("ops[%d] 必须是对象" % index)
        name = op.get("op")
        if name not in OPS:
            raise SpecError("ops[%d] 的 op=%r 不支持；可用：%s" % (index, name, "/".join(sorted(OPS))))
        try:
            image = OPS[name](image, op, state)
        except SpecError:
            raise
        except Exception as exc:
            raise SpecError("ops[%d]（%s）执行失败：%s: %s" % (index, name, type(exc).__name__, exc))
        if image is None:
            raise SpecError("ops[%d]（%s）返回了空图" % (index, name))

    notes = spec.get("note") or []
    if notes:
        draw = ImageDraw.Draw(image, "RGBA")
        font = _font(int(max(14, min(image.size) / 52.0)))
        y = image.size[1] - 8
        for line in list(notes)[:4][::-1]:
            text = "\u00b7 %s" % line
            width, height = _text_size(draw, text, font)
            draw.rounded_rectangle([8, y - height - 6, 8 + width + 12, y + 4], radius=4,
                                   fill=(255, 255, 255, 220))
            draw.text((14, y - height - 2), text, font=font, fill=(90, 105, 125, 255))
            y -= height + 10

    out = spec.get("out")
    if not out:
        raise SpecError("spec 缺少 out（输出 PNG 路径）")
    out = os.path.abspath(out)
    directory = os.path.dirname(out)
    if directory and not os.path.isdir(directory):
        os.makedirs(directory, exist_ok=True)
    flat = image.convert("RGB")
    flat.save(out, "PNG")
    return {
        "ok": True,
        "out": out,
        "width": flat.width,
        "height": flat.height,
        "bytes": os.path.getsize(out),
        "kinds": sorted(set(state["kinds"])),
        "notes": state["notes"],
        "warnings": state["warnings"],
        "ops_applied": len(ops),
    }


def main(argv):
    spec = None
    index = 0
    try:
        while index < len(argv):
            if argv[index] == "--spec" and index + 1 < len(argv):
                # utf-8-sig：调用方写 spec 时可能带 BOM（Windows 记事本/PowerShell 常见），
                # 带 BOM 的 json.load 会直接抛 JSONDecodeError，这里统一吃掉。
                with open(argv[index + 1], "r", encoding="utf-8-sig") as handle:
                    spec = json.load(handle)
                index += 2
            elif argv[index] == "--spec-json" and index + 1 < len(argv):
                spec = json.loads(argv[index + 1].lstrip("\ufeff"))
                index += 2
            else:
                index += 1
    except Exception as exc:
        sys.stdout.write(json.dumps({"ok": False, "error": "读取 spec 失败：%s" % exc}, ensure_ascii=False))
        return 1
    if spec is None:
        sys.stdout.write(json.dumps({"ok": False, "error": "缺少 --spec 或 --spec-json"}, ensure_ascii=False))
        return 1
    try:
        result = annotate(spec)
    except SpecError as exc:
        sys.stdout.write(json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False))
        return 1
    except Exception as exc:
        sys.stdout.write(json.dumps({"ok": False, "error": "%s: %s" % (type(exc).__name__, exc)},
                                    ensure_ascii=False))
        return 1
    sys.stdout.write(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))