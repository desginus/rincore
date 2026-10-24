#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""preprocess.py —— 通用「看清题图」工具箱（不限于力学，任何学科的照片/截图都能用）。

用途：学生用手机拍/截的题图常见毛病——EXIF 方向不对、整页倾斜、偏暗发灰、分辨率
太小、局部小字看不清、一页多题、纸张被拍成梯形。本脚本在送给视觉模型（或自己看
图）之前做**不裁掉题面**的增益，并给出「这张图该做哪些预处理」的确定性依据（probe）。

CLI（stdout 只能是 JSON）：
    <python> preprocess.py --spec <spec.json>
    <python> preprocess.py --spec-json '<inline json>'

spec：
    {
      "src": "raw.jpg",
      "out": "proc.png",
      "ops": [
        {"op": "probe"},                                    # 只读探测，不改图
        {"op": "auto_orient"},                              # 按 EXIF 方向转正
        {"op": "rotate", "angle": 2.5, "expand": true},     # 正角度=逆时针
        {"op": "deskew", "max_angle": 10},                  # 自动估文本行倾角并转正
        {"op": "perspective", "corners": [[x,y],[x,y],[x,y],[x,y]]},
        {"op": "perspective", "auto_detect": true},
        {"op": "crop_box", "box": [0.1, 0.1, 0.9, 0.5]},    # 归一化或像素坐标
        {"op": "zoom", "box": [0.3,0.4,0.6,0.5], "factor": 3, "out": "small-text.png"},
        {"op": "binarize", "method": "otsu"},               # otsu | adaptive
        {"op": "denoise", "method": "median", "size": 3},   # median | bilateral
        {"op": "enhance_text"},                             # 灰化+局部对比+轻锐化
        {"op": "ink"},                                      # enhance_text 的别名
        {"op": "grayscale"}, {"op": "autocontrast", "cutoff": 1.0},
        {"op": "sharpen", "factor": 1.5},
        {"op": "crop", "box": [0.0, 0.0, 1.0, 0.5]},        # 语义与旧版完全一致
        {"op": "upscale", "factor": 2.0},
        {"op": "split", "mode": "auto", "axis": "y"}        # 等价于顶层 split 字段
      ],
      "split": {"mode": "auto"},          # 或 {"count": 3, "axis": "y", "overlap": 0.05}
      "out_dir": "pieces",
      "max_side": 2000
    }

probe（只读探测）返回的字段，都是"实测"而不是猜的：
    width / height / mode / exif_orientation / aspect / mean_luma / contrast /
    contrast_range / is_color / colorfulness / blur_estimate / blur_label /
    skew_estimate（正=需要逆时针转这个角度才摆平）/ dark_fraction /
    suggested_ops:[{op,...,why}]   ← why 写明"为什么建议它"

成功：{"ok":true,"outputs":[{path,width,height,bytes,index?,box?}],"main":...,"notes":[...],
      "probe":{...}|null,"probes":[...],"suggested_ops":[...],"deskew":{...}|null,"limits":{...}}
失败：{"ok":false,"error":"中文原因"}，退出码 1。
"""

import io
import json
import math
import os
import sys

try:  # stdout 必须是 UTF-8，避免中文在 GBK 控制台下被打碎
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps, ImageStat
except Exception as exc:  # pragma: no cover - 环境缺 Pillow
    sys.stdout.write(json.dumps({
        "ok": False,
        "error": "本机 Python 缺少 Pillow，无法预处理图片：%s（请用内置 Python 运行）" % exc,
    }, ensure_ascii=False) + "\n")
    raise SystemExit(1)

try:  # numpy 只被少数操作需要（透视矫正 / 双边滤波）；缺了也能跑，只是如实降级
    import numpy as np
    NUMPY_ERROR = None
except Exception as exc:  # pragma: no cover
    np = None
    NUMPY_ERROR = str(exc)

# 允许的操作（split 是"延后执行"的：它等价于顶层 split 字段）
OPS = ("auto_orient", "crop", "crop_box", "upscale", "grayscale", "autocontrast",
       "sharpen", "probe", "rotate", "deskew", "perspective", "zoom", "binarize",
       "denoise", "ink", "enhance_text", "split")

# PNG 体积控制：超过该字节数才考虑调色板压缩（"quantize": "auto" 时）
QUANTIZE_THRESHOLD = 1_500_000

# 默认最长边上限（顶层 max_side 缺省时用它；index.js 显式传 2000）
DEFAULT_MAX_SIDE = 2000
# 单张图允许的最大像素总量：超过就等比缩回，宁可如实提示也不让内存爆掉
MAX_PIXELS = 30_000_000
# 放大类操作的硬上限
UPSCALE_MAX_SIDE = 20000
UPSCALE_MAX_PIXELS = 200_000_000
ZOOM_MAX_SIDE = 6000
# 对比度归一后的拉普拉斯方差低于这个值就认为画面偏软
# （实测：清晰截图 500~1140；高斯模糊 1.5px 后 138~228；3px 后 61~106）
BLUR_THRESHOLD = 280.0


# --------------------------------------------------------------------------- #
# 输出工具
# --------------------------------------------------------------------------- #
def emit(obj, code=0):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()
    return code


def fail(message):
    return emit({"ok": False, "error": str(message)}, 1)


def log(message):
    """诊断信息只走 stderr，绝不污染 stdout 的 JSON。"""
    try:
        sys.stderr.write("[preprocess] %s\n" % message)
    except Exception:
        pass


def require_numpy(what):
    if np is None:
        raise ValueError("本机 Python 缺少 numpy，无法执行 %s：%s（请改用带 numpy 的内置 Python）"
                         % (what, NUMPY_ERROR))


# --------------------------------------------------------------------------- #
# 参数
# --------------------------------------------------------------------------- #
def load_spec(argv):
    spec_path = None
    spec_json = None
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--spec":
            i += 1
            if i >= len(argv):
                raise ValueError("--spec 后面缺少文件路径")
            spec_path = argv[i]
        elif arg.startswith("--spec="):
            spec_path = arg.split("=", 1)[1]
        elif arg == "--spec-json":
            i += 1
            if i >= len(argv):
                raise ValueError("--spec-json 后面缺少 JSON 内容")
            spec_json = argv[i]
        elif arg.startswith("--spec-json="):
            spec_json = arg.split("=", 1)[1]
        elif arg in ("-h", "--help"):
            raise ValueError("用法: preprocess.py --spec <spec.json> | --spec-json '<json>'")
        else:
            raise ValueError("未知参数 %r；只支持 --spec / --spec-json" % arg)
        i += 1

    if spec_json is not None:
        try:
            spec = json.loads(spec_json)
        except Exception as exc:
            raise ValueError("--spec-json 不是合法 JSON：%s" % exc)
    elif spec_path is not None:
        if not os.path.isfile(spec_path):
            raise ValueError("spec 文件不存在：%s" % spec_path)
        try:
            with open(spec_path, "r", encoding="utf-8-sig") as handle:
                spec = json.load(handle)
        except Exception as exc:
            raise ValueError("spec 文件解析失败：%s" % exc)
    else:
        raise ValueError("缺少 --spec 或 --spec-json")

    if not isinstance(spec, dict):
        raise ValueError("spec 必须是一个 JSON 对象")
    return spec


def as_number(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        try:
            value = float(value)
        except Exception:
            raise ValueError("%s 必须是数字，收到 %r" % (name, value))
    return float(value)


def as_int(value, name):
    number = as_number(value, name)
    if abs(number - round(number)) > 1e-6:
        raise ValueError("%s 必须是整数，收到 %r" % (name, value))
    return int(round(number))


def as_flag(value, default=True):
    """宽松布尔：true/false、1/0、"true"/"yes"/"是" 等都能认。"""
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    text = str(value).strip().lower()
    if text in ("1", "true", "yes", "y", "on", "\u662f", "\u771f"):
        return True
    if text in ("0", "false", "no", "n", "off", "\u5426", "\u5047", ""):
        return False
    return default


def parse_box(raw, width, height, name="box", min_size=8):
    """[l,t,r,b]：任一分量绝对值 > 1 视为像素，否则视为相对宽高的归一化坐标。"""
    if not isinstance(raw, (list, tuple)) or len(raw) != 4:
        raise ValueError("%s 必须是 [左, 上, 右, 下] 四个数字" % name)
    vals = [as_number(v, "%s[%d]" % (name, idx)) for idx, v in enumerate(raw)]
    if any(abs(v) > 1.0 for v in vals):
        left, top, right, bottom = [int(round(v)) for v in vals]
    else:
        left = int(round(vals[0] * width))
        top = int(round(vals[1] * height))
        right = int(round(vals[2] * width))
        bottom = int(round(vals[3] * height))
    left = max(0, min(left, width))
    top = max(0, min(top, height))
    right = max(0, min(right, width))
    bottom = max(0, min(bottom, height))
    if right - left < min_size or bottom - top < min_size:
        raise ValueError("%s 裁剪结果为空或过小（%s，至少需要 %d px）"
                         % (name, (left, top, right, bottom), min_size))
    return (left, top, right, bottom)


def parse_quad(raw, width, height, name="corners", normalized=False):
    """四点四边形：[[x,y]×4]，默认像素坐标；四点都在 0–1 之间时按归一化处理。"""
    if not isinstance(raw, (list, tuple)) or len(raw) != 4:
        raise ValueError("%s 必须是 4 个 [x, y] 点（左上、右上、右下、左下）" % name)
    values = []
    for index, item in enumerate(raw):
        if not isinstance(item, (list, tuple)) or len(item) != 2:
            raise ValueError("%s[%d] 必须是 [x, y] 两个数字" % (name, index))
        values.append((as_number(item[0], "%s[%d][0]" % (name, index)),
                       as_number(item[1], "%s[%d][1]" % (name, index))))
    if not normalized:
        normalized = all(0.0 <= x <= 1.0 and 0.0 <= y <= 1.0 for x, y in values)
    points = []
    for index, (x, y) in enumerate(values):
        if normalized:
            x, y = x * width, y * height
        if x < -2 or y < -2 or x > width + 2 or y > height + 2:
            raise ValueError("%s[%d]=(%g,%g) 落在图片外（图幅 %dx%d）"
                             % (name, index, x, y, width, height))
        points.append((float(x), float(y)))
    return points


# --------------------------------------------------------------------------- #
# 只读探测：一切建议都必须由实测指标推出
# --------------------------------------------------------------------------- #
def otsu_threshold(gray):
    """Otsu 全局阈值（只用直方图，不需要 numpy）。"""
    hist = gray.histogram()
    total = sum(hist)
    if total <= 0:
        return 128
    sum_all = sum(index * count for index, count in enumerate(hist))
    sum_back = 0.0
    weight_back = 0
    best_threshold = 128
    best_variance = -1.0
    for threshold in range(256):
        weight_back += hist[threshold]
        if weight_back == 0:
            continue
        weight_fore = total - weight_back
        if weight_fore == 0:
            break
        sum_back += threshold * hist[threshold]
        mean_back = sum_back / weight_back
        mean_fore = (sum_all - sum_back) / weight_fore
        variance = weight_back * weight_fore * (mean_back - mean_fore) ** 2
        if variance > best_variance:
            best_variance = variance
            best_threshold = threshold
    return best_threshold


def laplacian_variance(gray):
    """拉普拉斯方差：越小越模糊（Pillow 的 3x3 卷积核，无 numpy 也能算）。"""
    laplacian = gray.filter(ImageFilter.Kernel(
        (3, 3), [0, 1, 0, 1, -4, 1, 0, 1, 0], scale=1, offset=128))
    hist = laplacian.histogram()
    total = float(sum(hist)) or 1.0
    mean = sum(index * count for index, count in enumerate(hist)) / total
    return sum(count * (index - mean) ** 2 for index, count in enumerate(hist)) / total


def sharpness_estimate(gray):
    """清晰度：先在灰度上做 0.5% 截断的对比度拉伸，再算拉普拉斯方差。

    先拉伸是为了把"暗/发灰"和"模糊"分开——实测暗图直接算会被误判成模糊。
    """
    return laplacian_variance(ImageOps.autocontrast(gray, cutoff=0.5))


def estimate_skew(gray, max_angle=10.0, coarse=1.0, fine=0.1, work_side=560):
    """投影法估计文本行倾角，返回 (校正角, 得分, 说明)。

    校正角为**正**表示需要逆时针旋转该角度才能把文本行摆平（与 rotate.angle 同向）。
    做法：二值化取文本像素 → 在候选角度上旋转 → 按行投影，行投影的相邻差平方和
    最大时文本行最"成行"。纯 Pillow/直方图实现，不需要 OpenCV/霍夫变换。
    """
    small = gray
    longest = max(gray.size)
    if longest > work_side:
        ratio = work_side / float(longest)
        small = gray.resize((max(1, int(round(gray.width * ratio))),
                             max(1, int(round(gray.height * ratio)))), Image.BILINEAR)

    hist = small.histogram()
    total = float(sum(hist)) or 1.0
    mean = sum(index * count for index, count in enumerate(hist)) / total
    if mean < 128:  # 暗底亮字：先反相，让"文字=亮"
        small = ImageOps.invert(small)
    threshold = otsu_threshold(small)
    mask = small.point(lambda value: 255 if value <= threshold else 0, mode="L")
    if mask.getbbox() is None:
        return 0.0, 0.0, "未找到可辨认的文本像素，倾角按 0 处理"

    def score(angle):
        rotated = mask.rotate(angle, resample=Image.BILINEAR, expand=False, fillcolor=0)
        profile = rotated.resize((1, rotated.height), Image.BOX)
        values = list(profile.tobytes())
        return sum((values[i + 1] - values[i]) ** 2 for i in range(len(values) - 1))

    best_angle = 0.0
    best_score = -1.0
    steps = int(round(2 * max_angle / coarse)) + 1
    for index in range(steps):
        angle = -max_angle + index * coarse
        value = score(angle)
        if value > best_score:
            best_score, best_angle = value, angle
    start = best_angle - coarse
    steps = int(round(2 * coarse / fine)) + 1
    for index in range(steps):
        angle = start + index * fine
        value = score(angle)
        if value > best_score:
            best_score, best_angle = value, angle

    detail = "投影法（工作分辨率 %dx%d，搜索 ±%g°，步长 %g°）" % (
        small.width, small.height, max_angle, fine)
    return round(best_angle, 2), best_score, detail


def blur_label(value):
    if value < BLUR_THRESHOLD * 0.4:
        return "明显偏软（可能失焦或压缩过度）"
    if value < BLUR_THRESHOLD:
        return "偏软（建议轻锐化）"
    return "清晰"


def probe_image(image):
    """只读探测：返回一组实测指标 + 由指标推出的 suggested_ops。"""
    width, height = image.size

    exif_orientation = 1
    try:
        exif = image.getexif()
        if exif:
            exif_orientation = int(exif.get(0x0112, 1) or 1)
    except Exception:
        exif_orientation = 1

    gray = image.convert("L")
    hist = gray.histogram()
    total = float(sum(hist)) or 1.0
    mean = sum(index * count for index, count in enumerate(hist)) / total
    variance = sum(count * (index - mean) ** 2 for index, count in enumerate(hist)) / total
    std = variance ** 0.5

    low = high = None
    accumulated = 0
    for index, count in enumerate(hist):
        accumulated += count
        if low is None and accumulated >= total * 0.05:
            low = index
        if accumulated >= total * 0.95:
            high = index
            break
    low = 0 if low is None else low
    high = 255 if high is None else high
    dark_fraction = sum(hist[:110]) / total

    colorfulness = 0.0
    try:
        channel_means = ImageStat.Stat(image.convert("RGB")).mean
        colorfulness = max(channel_means) - min(channel_means)
    except Exception:
        colorfulness = 0.0

    blur = sharpness_estimate(gray)
    skew, _skew_score, skew_detail = estimate_skew(gray)

    suggestions = []

    def suggest(name, why, **extra):
        item = {"op": name}
        item.update(extra)
        item["why"] = why
        suggestions.append(item)

    if exif_orientation != 1:
        suggest("auto_orient", "EXIF 方向 = %d，不是正向" % exif_orientation)

    short_side = min(width, height)
    if short_side < 1200:
        factor = min(3.0, max(1.2, round(1200.0 / short_side, 2)))
        suggest("upscale", "短边只有 %d px（< 1200），放大后小字更可读" % short_side,
                factor=factor)

    if (high - low) < 140 or std < 30:
        if high - low <= 4:
            reason = ("5%%~95%% 分位都落在灰度 %d（画面绝大部分是同一种底色，标准差 %.1f）"
                      % (high, std))
        else:
            reason = "动态范围窄（5%%~95%% 灰度 %d~%d，标准差 %.1f）" % (low, high, std)
        suggest("autocontrast", reason + "，对比度偏低", cutoff=1.0)
    elif mean < 95:
        suggest("autocontrast", "整体偏暗（均值灰度 %.1f）" % mean, cutoff=0.5)
    elif mean > 205:
        suggest("autocontrast", "整体偏灰/过亮（均值灰度 %.1f）" % mean, cutoff=0.5)

    if blur < BLUR_THRESHOLD:
        suggest("sharpen", "对比归一后的清晰度 %.1f 低于阈值 %.0f，画面%s"
                % (blur, BLUR_THRESHOLD, blur_label(blur)), factor=1.6)

    if abs(skew) >= 0.5:
        suggest("deskew", "%s估计文本行倾斜 %+.2f°" % (skew_detail, skew))

    if colorfulness > 25:
        suggest("grayscale", "三通道均值差 %.1f（明显偏色），灰化后文字对比更稳" % colorfulness)

    if height >= width * 2.0:
        suggest("split", "高宽比 %.2f（%dx%d），很可能一页多题，可按空白投影自动切分"
                % (height / float(width), width, height), mode="auto", axis="y")
    elif width >= height * 2.0:
        suggest("split", "宽高比 %.2f（%dx%d），可能是横向双栏，可按空白投影自动切分"
                % (width / float(height), width, height), mode="auto", axis="x")

    return {
        "width": width,
        "height": height,
        "mode": image.mode,
        "exif_orientation": exif_orientation,
        "aspect": round(width / float(height), 4) if height else 0.0,
        "mean_luma": round(mean, 2),
        "contrast": round(std, 2),
        "contrast_range": [int(low), int(high)],
        "is_color": bool(colorfulness > 8),
        "colorfulness": round(colorfulness, 2),
        "blur_estimate": round(blur, 2),
        "blur_label": blur_label(blur),
        "skew_estimate": round(skew, 2),
        "skew_detail": skew_detail,
        "dark_fraction": round(dark_fraction, 4),
        "suggested_ops": suggestions,
    }


# --------------------------------------------------------------------------- #
# 内存护栏 / 通用小工具
# --------------------------------------------------------------------------- #
def guard_pixels(image, ctx, label):
    """像素总量超过 MAX_PIXELS 时等比缩回，并如实记一条 notes（绝不悄悄爆内存）。

    30M 像素的 RGB 图约 90 MB，重采样过程峰值再翻一倍——这是在"办公机 + DSH 沙箱"里
    能稳住的量级；超过就缩回去并把"缩过"写进 notes，而不是硬撑到被系统杀掉。
    """
    pixels = image.width * image.height
    if pixels <= MAX_PIXELS:
        return image
    ratio = (MAX_PIXELS / float(pixels)) ** 0.5
    new_w = max(1, int(round(image.width * ratio)))
    new_h = max(1, int(round(image.height * ratio)))
    out = image.resize((new_w, new_h), Image.LANCZOS)
    ctx["notes"].append("%s：像素量 %d 超过内存上限 %d（约 %d MB RGB），已等比缩到 %dx%d"
                        % (label, pixels, MAX_PIXELS, MAX_PIXELS * 3 // 1048576,
                           out.width, out.height))
    ctx["facts"]["memory_clamped"] = True
    return out


def fill_color(image):
    """旋转/透视留白的填充色：按模式取"纸面"色。"""
    mode = image.mode
    if mode == "L":
        return 255
    if mode == "1":
        return 1
    if mode == "RGBA":
        return (255, 255, 255, 255)
    if mode == "LA":
        return (255, 255)
    return (255, 255, 255)


def rotate_image(image, angle, expand=True):
    return image.rotate(angle, resample=Image.BICUBIC, expand=bool(expand),
                        fillcolor=fill_color(image))


# --------------------------------------------------------------------------- #
# 操作
# --------------------------------------------------------------------------- #
def op_probe(image, params, ctx):
    info = probe_image(image)
    ctx["probes"].append(info)
    ctx["notes"].append("probe：%dx%d mode=%s EXIF 方向=%d 灰度均值=%.1f 对比度=%.1f "
                        "清晰度=%.1f（%s）倾角=%+.2f° 建议 %d 项预处理"
                        % (info["width"], info["height"], info["mode"],
                           info["exif_orientation"], info["mean_luma"], info["contrast"],
                           info["blur_estimate"], info["blur_label"],
                           info["skew_estimate"], len(info["suggested_ops"])))
    for item in info["suggested_ops"]:
        ctx["notes"].append("probe 建议 %s：%s" % (item["op"], item.get("why", "")))
    return image


# --------------------------------------------------------------------------- #
# 内容法判定方向（不依赖 EXIF）
#
# 背景：DSH 的附件归一化会重新编码图片并**剥掉 EXIF**（实测：从附件库拿到的
# 那张手机照片 EXIF 条目数 = 0、Orientation 标签 = None）。所以只靠
# ImageOps.exif_transpose 的转正在这条链路上**必然失效**，必须从像素判断
# 文字行是横排还是竖排。
#
# 判据：墨量沿「行」方向的带纹强度 std/mean 与沿「列」方向的比值。
# 文字横排 → 行剖面有文行与行间空白（带纹强），列剖面平缓。
# 实测（实验脚本见工作区 .tmp/orient_probe*.py，真值已知）：
#   合成文字页     2.64 / 0.38（比值 6.8 倍）
#   真实手机照片   1.85 / 0.54（竖排原图；区分度 3.4 倍）
#   同一张图换另一种判墨方式（背景归一化）仍给出同向结论。
#
# **明确不做的两件事**（都是实测出来的负面结论，不是偷懒）：
#   1) 正/倒（180°）：std/mean 对 180° 旋转是**严格不变量**——同一页转 180°
#      前后数值完全相同（实测 1.273/0.686 一模一样）。另一类「基线陡峭度」
#      统计量在两种判墨方式下符号相反（合成 0.853/1.171 vs 1.051/0.952），
#      不可用。
#   2) 竖排该转 +90 还是 -90：两个候选的统计量只差 9%（0.9577 vs 1.0442），
#      真实照片上甚至切不出足够的文行带（只 1 个带，算不出值）。
# 所以这两处**一律不猜**：横排/竖排自动判，方向候选拼成对照图一起返回，
# 由主模型或用户看一眼确认。宁可多给一张图，也不要静默转错。
# --------------------------------------------------------------------------- #
ORIENT_SIDE_MIN_RATIO = 1.5    # 行/列 ≥ 此值 → 判定文字横排
ORIENT_SIDE_MAX_RATIO = 0.67   # 行/列 ≤ 此值 → 判定文字竖排
ORIENT_NO_INK = 1e-9           # 两个方向带纹都 ≤ 此值 = 图内没有可用墨量结构
ORIENT_DETECT_MAX_SIDE = 900


def _orient_gray(image, max_side=ORIENT_DETECT_MAX_SIDE):
    gray = image.convert("L")
    if max(gray.size) > max_side:
        scale = max_side / float(max(gray.size))
        gray = gray.resize((max(1, int(gray.width * scale)),
                            max(1, int(gray.height * scale))), Image.LANCZOS)
    return gray


def _orient_profiles(gray):
    """返回 (行剖面, 列剖面)：每个值是该行/列的平均墨量。纯 PIL，不依赖 numpy。"""
    hist = gray.histogram()
    total = sum(hist) or 1

    def percentile(fraction):
        seen = 0
        for value, count in enumerate(hist):
            seen += count
            if seen >= total * fraction:
                return value
        return 255

    threshold = percentile(0.02) + 0.45 * (percentile(0.98) - percentile(0.02))
    ink = gray.point(lambda v: 255 if v < threshold else 0, mode="L")
    width, height = ink.size
    rows = list(ink.resize((1, height), Image.BOX).tobytes())
    cols = list(ink.resize((width, 1), Image.BOX).tobytes())
    return rows, cols


def _band_strength(profile):
    """墨剖面的带纹强度 std/mean：文行与行间空白交替时大，平缓时小。"""
    if not profile:
        return 0.0
    values = [float(value) for value in profile]
    mean = sum(values) / len(values)
    if mean <= 1e-9:
        return 0.0
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    return (variance ** 0.5) / mean


def detect_orientation(image):
    """只判「文字横排还是竖排」。返回带实测分数的 dict，便于事后审计。

    **证据不足时必须回 unknown，不许硬判**（两类情形都要走这条路，都有用例守着）：
      1) 图内没有可用墨量结构（纯白 / 纯色 / 整幅实心）——两个分数都是 0，
         「比值」没有定义。原先会算成 inf 并判成"横排、方向正确"，那是对空白图
         下了没有依据的结论。
      2) 两个分数接近（比值落在两个阈值之间）。
    """
    gray = _orient_gray(image)
    rows, cols = _orient_profiles(gray)
    row_score = _band_strength(rows)
    col_score = _band_strength(cols)
    if row_score <= ORIENT_NO_INK and col_score <= ORIENT_NO_INK:
        side, ratio = "unknown", None
    elif col_score <= ORIENT_NO_INK:
        # 只有整行墨带（如一条横线）：列剖面完全平，比值发散，仍算强横排证据
        side, ratio = "horizontal", float("inf")
    else:
        ratio = row_score / col_score
        if ratio >= ORIENT_SIDE_MIN_RATIO:
            side = "horizontal"
        elif ratio <= ORIENT_SIDE_MAX_RATIO:
            side = "vertical"
        else:
            side = "unknown"
    return {"side": side, "row_score": row_score, "col_score": col_score, "ratio": ratio}


def orient_measured(info):
    """把实测分数拼成给人看的一句话（ratio 可能是 None 或 inf，都要能读）。"""
    ratio = info.get("ratio")
    if ratio is None:
        shown = "不可判（图内没有可用墨量结构）"
    elif math.isinf(ratio):
        shown = "发散（列向带纹=0）"
    else:
        shown = "%.2f" % ratio
    return "行向带纹=%.2f 列向带纹=%.2f 比值=%s" % (info["row_score"], info["col_score"], shown)


def _orient_sheet_path(ctx):
    out = ctx.get("out")
    if out:
        return os.path.splitext(out)[0] + "-orient.png"
    out_dir = ctx.get("out_dir")
    if out_dir:
        return os.path.join(out_dir, "orient-sheet.png")
    return None


def _orient_sheet(ctx, panels, out_path):
    """把候选方向并排拼成一张对照图，附在输出里让人一眼确认。

    标签只用 ASCII（避免字体缺字形把标签变成空框）。
    """
    label_height = 30
    target_height = 700
    gap = 10
    scaled = []
    for text, item in panels:
        panel = item.convert("RGB")
        if panel.height > target_height:
            scale = target_height / float(panel.height)
            panel = panel.resize((max(1, int(panel.width * scale)), target_height),
                                 Image.LANCZOS)
        scaled.append((text, panel))
    width = sum(panel.width for _t, panel in scaled) + gap * (len(scaled) + 1)
    height = max(panel.height for _t, panel in scaled) + label_height + gap * 2
    sheet = Image.new("RGB", (width, height), (255, 255, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 18)
    except Exception:
        font = ImageFont.load_default()
    x = gap
    for text, panel in scaled:
        draw.text((x + 4, gap + 6), text, fill=(180, 0, 0), font=font)
        sheet.paste(panel, (x, gap + label_height))
        x += panel.width + gap
    info = save_png(sheet, out_path, ctx.get("quantize", "auto"))
    info["kind"] = "orient-sheet"
    info["candidates"] = [text for text, _panel in panels]
    ctx["extra_outputs"].append(info)
    return info


def op_auto_orient(image, params, ctx):
    orientation = 1
    try:
        exif = image.getexif()
        if exif:
            orientation = int(exif.get(0x0112, 1) or 1)
    except Exception:
        orientation = 1
    out = ImageOps.exif_transpose(image)
    if out is None:
        out = image
    if out.size != image.size or orientation != 1:
        ctx["notes"].append("auto_orient：按 EXIF 方向 %s 转正（%dx%d → %dx%d）"
                            % (orientation, image.width, image.height, out.width, out.height))
        ctx["facts"]["orient"] = {"method": "exif", "exif_orientation": orientation}
        return out

    # EXIF 没有可用信息（DSH 附件归一化会剥掉 EXIF）→ 改用像素内容判方向
    info = detect_orientation(out)
    info["method"] = "content"
    info["exif_orientation"] = orientation
    measured = orient_measured(info)

    if info["side"] == "horizontal":
        info["rotated"] = 0
        info["flip_uncertain"] = True
        ctx["notes"].append(
            "auto_orient：EXIF 无方向信息（DSH 附件归一化会剥掉 EXIF，实测拿到手时 EXIF 条目数=0），"
            "改用像素判方向：%s → 文字【横排】，方向正确，未旋转。注意像素统计量对 180° 旋转是不变量，"
            "所以这不排除整页颠倒；若发现倒置，加 ops:[{\"op\":\"rotate\",\"degrees\":180}]。"
            % measured)
        ctx["facts"]["orient"] = info
        return out

    if info["side"] == "unknown":
        info["rotated"] = 0
        no_ink = info["ratio"] is None
        ctx["notes"].append(
            "auto_orient：EXIF 无方向信息，改用像素判方向：%s。%s"
            "→ 方向【存疑，未旋转】。若确为竖排或倒置，请显式指定 "
            "ops:[{\"op\":\"rotate\",\"degrees\":90}]（或 180 / 270）。"
            % (measured,
               "图内没有可用的文字墨量结构（空白页 / 纯色 / 已被裁空），无从判断方向，"
               if no_ink else
               "既未达横排阈值 %.2f 也未达竖排阈值 %.2f，"
               % (ORIENT_SIDE_MIN_RATIO, ORIENT_SIDE_MAX_RATIO)))
        ctx["facts"]["orient"] = info
        return out

    # 竖排：必须转 ±90；但哪个方向像素统计量判不出来 → 采用固定默认 + 附候选对照图
    counter = out.rotate(90, expand=True)
    clockwise = out.rotate(-90, expand=True)
    info["rotated"] = 90
    info["flip_uncertain"] = True
    info["default_direction"] = "counterclockwise(+90)"
    sheet_path = _orient_sheet_path(ctx)
    sheet = None
    if sheet_path:
        try:
            sheet = _orient_sheet(ctx, [("A = rotate +90 (this is what was used)", counter),
                                        ("B = rotate -90", clockwise)], sheet_path)
        except Exception as exc:                                   # 写不出对照图不影响主流程
            ctx["notes"].append("auto_orient：候选对照图写出失败（%s），请用 rotate 显式指定方向" % exc)
    info["sheet"] = sheet["path"] if sheet else None
    ctx["notes"].append(
        "auto_orient：EXIF 无方向信息，改用像素判方向：%s → 文字【竖排】，已按固定默认逆时针转 90°（对照图 A 格）。"
        "转 +90 还是 -90 像素统计量判不出来（实测两候选统计量只差 9%%，你这张真实照片甚至切不出足够的文行带），"
        "**所以不猜**：%s。正确的是哪一格请看那张对照图；若 B 格才对，再加 ops:[{\"op\":\"rotate\",\"degrees\":180}]。"
        % (measured,
           ("已附候选对照图 %s（A=+90 已采用，B=-90）" % sheet["path"]) if sheet
           else "未能写出对照图，请直接用 rotate 指定方向"))
    ctx["facts"]["orient"] = info
    return counter


def op_crop(image, params, ctx):
    box = parse_box(params.get("box"), image.width, image.height, "crop.box", min_size=8)
    out = image.crop(box)
    ctx["notes"].append("crop：裁切到 %s（%dx%d → %dx%d）"
                        % (box, image.width, image.height, out.width, out.height))
    return out


def op_crop_box(image, params, ctx):
    """crop_box：[l,t,r,b]，归一化（0–1 小数）或像素（绝对值 >1）自动判别。

    与 crop 的区别只有一个：允许裁到 4 px 的小区域（配合 zoom 抠小字用）。
    """
    box = parse_box(params.get("box"), image.width, image.height, "crop_box.box", min_size=4)
    out = image.crop(box)
    scale = "归一化" if all(abs(as_number(v, "crop_box.box")) <= 1.0
                           for v in params.get("box")) else "像素"
    ctx["notes"].append("crop_box：按 %s坐标裁切到 %s（%dx%d → %dx%d）"
                        % (scale, box, image.width, image.height, out.width, out.height))
    return out


def op_upscale(image, params, ctx):
    factor = as_number(params.get("factor", 2.0), "upscale.factor")
    if factor <= 0:
        raise ValueError("upscale.factor 必须大于 0")
    if factor == 1:
        ctx["notes"].append("upscale：factor=1，未改动")
        return image
    new_w = int(round(image.width * factor))
    new_h = int(round(image.height * factor))
    if max(new_w, new_h) > UPSCALE_MAX_SIDE or new_w * new_h > UPSCALE_MAX_PIXELS:
        raise ValueError("upscale 放大后尺寸过大（%dx%d，上限 %d px 长边 / %.1f 亿像素），"
                         "请降低 factor"
                         % (new_w, new_h, UPSCALE_MAX_SIDE, UPSCALE_MAX_PIXELS / 1e8))
    out = image.resize((new_w, new_h), Image.LANCZOS)
    ctx["notes"].append("upscale：LANCZOS ×%g（%dx%d → %dx%d）"
                        % (factor, image.width, image.height, out.width, out.height))
    return out


def op_rotate(image, params, ctx):
    if "angle" not in params:
        raise ValueError("rotate 需要 angle（度，正=逆时针）")
    angle = as_number(params.get("angle"), "rotate.angle")
    if abs(angle) > 360:
        raise ValueError("rotate.angle 超出 ±360°（收到 %g）" % angle)
    expand = as_flag(params.get("expand"), True)
    if abs(angle) < 1e-9:
        ctx["notes"].append("rotate：angle=0，未改动")
        return image
    out = rotate_image(image, angle, expand)
    ctx["notes"].append("rotate：逆时针 %g°（expand=%s，留白填 %s）（%dx%d → %dx%d）"
                        % (angle, "true" if expand else "false", fill_color(image),
                           image.width, image.height, out.width, out.height))
    return out


def op_deskew(image, params, ctx):
    max_angle = as_number(params.get("max_angle", 10.0), "deskew.max_angle")
    max_angle = max(1.0, min(max_angle, 30.0))
    min_angle = as_number(params.get("min_angle", 0.5), "deskew.min_angle")
    angle, score, detail = estimate_skew(image.convert("L"), max_angle=max_angle)
    ctx["facts"]["deskew"] = {"angle": angle, "score": round(score, 2),
                              "detail": detail, "applied": False}
    if abs(angle) < min_angle:
        ctx["notes"].append("deskew：%s未检测到明显倾斜（估计 %+.2f°，小于 min_angle=%g），未改动"
                            % (detail, angle, min_angle))
        return image
    expand = as_flag(params.get("expand"), True)
    out = rotate_image(image, angle, expand)
    ctx["facts"]["deskew"]["applied"] = True
    ctx["facts"]["deskew"]["expand"] = bool(expand)
    ctx["notes"].append("deskew：检测到 %+.2f° 倾斜，已逆时针转 %.2f° 摆平"
                        "（%s，expand=%s；%dx%d → %dx%d）"
                        % (angle, angle, detail, "true" if expand else "false",
                           image.width, image.height, out.width, out.height))
    return out


def order_quad(points):
    """把四点整理成 左上、右上、右下、左下（按 x 分左右，再按 y 分上下）。"""
    ordered = sorted(points, key=lambda point: point[0])
    left = sorted(ordered[:2], key=lambda point: point[1])
    right = sorted(ordered[2:], key=lambda point: point[1])
    return [left[0], right[0], right[1], left[1]]


def quad_area(points):
    area = 0.0
    for index in range(4):
        x0, y0 = points[index]
        x1, y1 = points[(index + 1) % 4]
        area += x0 * y1 - x1 * y0
    return abs(area) / 2.0


def check_convex(points):
    signs = []
    for index in range(4):
        x0, y0 = points[index]
        x1, y1 = points[(index + 1) % 4]
        x2, y2 = points[(index + 2) % 4]
        cross = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
        if abs(cross) > 1e-6:
            signs.append(cross > 0)
    return len(signs) >= 3 and (all(signs) or not any(signs))


def distance(a, b):
    return math.hypot(a[0] - b[0], a[1] - b[1])


def solve_homography(src_points, dst_points):
    """求把 src 四点映射到 dst 四点的 3x3 单应矩阵（DLT，numpy 解 8 元线性方程组）。"""
    require_numpy("透视矫正")
    matrix = []
    target = []
    for (x, y), (u, v) in zip(src_points, dst_points):
        # 每对点贡献两行：u 与 v 各一个方程（h8 固定为 1）
        matrix.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        target.append(u)
        matrix.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        target.append(v)
    try:
        solution = np.linalg.solve(np.asarray(matrix, dtype=np.float64),
                                   np.asarray(target, dtype=np.float64))
    except Exception as exc:
        raise ValueError("透视矫正的四点退化（无法求单应矩阵）：%s；请检查 corners 是否共线或写反" % exc)
    return np.asarray([[solution[0], solution[1], solution[2]],
                       [solution[3], solution[4], solution[5]],
                       [solution[6], solution[7], 1.0]], dtype=np.float64)


def warp_quad(image, quad, ctx, label="perspective"):
    """把源四点四边形矫正成矩形（双三次会被内存吃爆，这里用双线性 + 分带采样）。"""
    require_numpy("透视矫正")
    tl, tr, br, bl = order_quad(quad)
    if not check_convex([tl, tr, br, bl]):
        raise ValueError("%s：四点不是凸四边形（可能顺序写反或共线）：%s"
                         % (label, [[round(x, 1), round(y, 1)] for x, y in (tl, tr, br, bl)]))
    raw_w = max(distance(tl, tr), distance(bl, br))
    raw_h = max(distance(tl, bl), distance(tr, br))
    out_w = int(round(raw_w))
    out_h = int(round(raw_h))
    if out_w < 8 or out_h < 8 or quad_area([tl, tr, br, bl]) < 64:
        raise ValueError("%s：矫正后尺寸过小（%dx%d），请检查 corners 是否写反" % (label, out_w, out_h))

    target = [(0.0, 0.0), (float(out_w - 1), 0.0),
              (float(out_w - 1), float(out_h - 1)), (0.0, float(out_h - 1))]
    homography = solve_homography(target, [tl, tr, br, bl])  # 目标 → 源

    mode = image.mode if image.mode in ("L", "RGB", "RGBA") else "RGB"
    source = image if image.mode == mode else image.convert(mode)
    if image.mode != mode:
        ctx["notes"].append("%s：源图模式 %s 不支持透视采样，已转为 %s 处理"
                            % (label, image.mode, mode))
    array = np.asarray(source, dtype=np.float32)
    height, width = array.shape[:2]
    channels = 1 if mode == "L" else array.shape[2]

    canvas = Image.new(mode, (out_w, out_h), fill_color(source))
    result = np.empty((out_h, out_w, channels), dtype=np.uint8) if channels > 1 \
        else np.empty((out_h, out_w), dtype=np.uint8)

    band = 256  # 分带采样：把中间数组的内存钉死在几十 MB
    for top in range(0, out_h, band):
        bottom = min(out_h, top + band)
        rows, cols = np.mgrid[top:bottom, 0:out_w]
        denominator = homography[2, 0] * cols + homography[2, 1] * rows + homography[2, 2]
        denominator[denominator == 0] = 1e-9
        src_x = (homography[0, 0] * cols + homography[0, 1] * rows + homography[0, 2]) / denominator
        src_y = (homography[1, 0] * cols + homography[1, 1] * rows + homography[1, 2]) / denominator
        src_x = np.clip(src_x, 0, width - 1)
        src_y = np.clip(src_y, 0, height - 1)
        x0 = np.floor(src_x).astype(np.int32)
        y0 = np.floor(src_y).astype(np.int32)
        x1 = np.minimum(x0 + 1, width - 1)
        y1 = np.minimum(y0 + 1, height - 1)
        wx = (src_x - x0).astype(np.float32)
        wy = (src_y - y0).astype(np.float32)
        if channels > 1:
            wx = wx[..., None]
            wy = wy[..., None]
        value = (array[y0, x0] * (1 - wx) * (1 - wy) + array[y0, x1] * wx * (1 - wy)
                 + array[y1, x0] * (1 - wx) * wy + array[y1, x1] * wx * wy)
        result[top:bottom] = np.clip(value, 0, 255).astype(np.uint8)
        del rows, cols, src_x, src_y, x0, y0, x1, y1, wx, wy, value

    canvas.paste(Image.fromarray(result, mode), (0, 0))
    ctx["notes"].append("%s：四点透视矫正为 %dx%d（源四点 %s）"
                        % (label, out_w, out_h,
                           [[float(round(x1, 1)), float(round(y1, 1))]
                            for x1, y1 in [tl, tr, br, bl]]))
    return canvas


def detect_paper_quad(image):
    """自动寻找纸张四边形：边框背景估计 → 与背景不同的区域 → 四边直线拟合 → 交成四点。

    找不到就抛可读错误（**不瞎裁**）；如果纸张几乎铺满整幅且四边接近正交，返回 None
    表示"无需透视矫正"。
    """
    require_numpy("perspective.auto_detect")
    gray = image.convert("L")
    longest = max(gray.size)
    if longest > 360:
        ratio = 360.0 / longest
        gray = gray.resize((max(8, int(round(gray.width * ratio))),
                            max(8, int(round(gray.height * ratio)))), Image.BILINEAR)
    array = np.asarray(gray, dtype=np.float32)
    height, width = array.shape

    border = np.concatenate([array[0, :], array[-1, :], array[:, 0], array[:, -1]])
    background = float(np.median(border))
    noise = float(np.median(np.abs(border - background)))
    tolerance = max(12.0, 3.0 * noise + 8.0)
    mask = np.abs(array - background) > tolerance

    row_coverage = mask.mean(axis=1)
    col_coverage = mask.mean(axis=0)
    rows = np.flatnonzero(row_coverage > 0.25)
    cols = np.flatnonzero(col_coverage > 0.25)
    if rows.size < 0.15 * height or cols.size < 0.15 * width:
        raise ValueError(
            "perspective.auto_detect：没有找到明显的纸张边界（与背景差异 >%g 的像素太少）；"
            "请直接给出 corners=[[x,y]×4] 或先用 crop/zoom 指定区域" % tolerance)
    top, bottom = int(rows[0]), int(rows[-1])
    left, right = int(cols[0]), int(cols[-1])
    if bottom - top < 16 or right - left < 16:
        raise ValueError("perspective.auto_detect：找出的纸张区域太小（%dx%d），不可靠"
                         % (right - left, bottom - top))

    inside = float(np.median(array[top:bottom + 1, left:right + 1]))
    if abs(inside - background) < max(18.0, tolerance):
        return None, ("边框底色（灰度 %.0f）与区域内底色（灰度 %.0f）很接近，"
                      "判定整幅就是题图、没有可矫正的纸张边缘" % (background, inside))

    def fit_line(xs, ys, vertical):
        """最小二乘拟合 x = a*y + b（vertical=True）或 y = a*x + b。"""
        if len(xs) < 8:
            return None
        xs = np.asarray(xs, dtype=np.float64)
        ys = np.asarray(ys, dtype=np.float64)
        if vertical:
            a, b = np.polyfit(ys, xs, 1)
            return (1.0, -a, -b)  # x - a*y - b = 0 → (A,B,C)
        a, b = np.polyfit(xs, ys, 1)
        return (-a, 1.0, -b)      # y - a*x - b = 0

    margin = max(2, int(0.05 * (bottom - top)))
    left_x, left_y, right_x, right_y = [], [], [], []
    for y in range(top + margin, max(top + margin + 1, bottom - margin)):
        row = np.flatnonzero(mask[y])
        if row.size < 0.3 * (right - left):
            continue
        left_x.append(int(row[0]))
        left_y.append(y)
        right_x.append(int(row[-1]))
        right_y.append(y)

    margin = max(2, int(0.05 * (right - left)))
    top_x, top_y, bottom_x, bottom_y = [], [], [], []
    for x in range(left + margin, max(left + margin + 1, right - margin)):
        column = np.flatnonzero(mask[:, x])
        if column.size < 0.3 * (bottom - top):
            continue
        top_x.append(x)
        top_y.append(int(column[0]))
        bottom_x.append(x)
        bottom_y.append(int(column[-1]))

    sides = {
        "left": fit_line(left_x, left_y, True),
        "right": fit_line(right_x, right_y, True),
        "top": fit_line(top_x, top_y, False),
        "bottom": fit_line(bottom_x, bottom_y, False),
    }
    missing = [name for name, line in sides.items() if line is None]
    if missing:
        raise ValueError("perspective.auto_detect：%s 边上的纸张边界点不足，无法拟合直线；"
                         "请直接给出 corners=[[x,y]×4]" % "、".join(missing))

    def intersect(first, second):
        a1, b1, c1 = first
        a2, b2, c2 = second
        determinant = a1 * b2 - a2 * b1
        if abs(determinant) < 1e-9:
            return None
        x = (b1 * c2 - b2 * c1) / determinant
        y = (c1 * a2 - c2 * a1) / determinant
        return (x, y)

    corners = []
    for pair in (("left", "top"), ("top", "right"), ("right", "bottom"), ("bottom", "left")):
        point = intersect(sides[pair[0]], sides[pair[1]])
        if point is None:
            raise ValueError("perspective.auto_detect：%s 与 %s 两条边近乎平行，交点不可靠"
                             % pair)
        corners.append(point)

    scale = image.width / float(width)
    corners = [(x * scale, y * scale) for x, y in corners]
    ordered = order_quad(corners)
    if not check_convex(ordered):
        raise ValueError("perspective.auto_detect：拟合出的四点不构成凸四边形，判定不可靠，"
                         "请直接给出 corners=[[x,y]×4]")
    area = quad_area(ordered)
    frame = float(image.width * image.height)
    angles = []
    for index in range(4):
        x0, y0 = ordered[index]
        x1, y1 = ordered[(index + 1) % 4]
        angles.append(abs(math.degrees(math.atan2(y1 - y0, x1 - x0)) % 90))
    max_tilt = max(min(angle, 90 - angle) for angle in angles)
    if area > 0.97 * frame and max_tilt < 0.8:
        return None, "纸张几乎铺满整幅且四边接近正交（最大倾角 %.2f°），无需透视矫正" % max_tilt
    if area < 0.2 * frame:
        raise ValueError("perspective.auto_detect：找出的四边形只占图幅 %.0f%%，更像误检；"
                         "请直接给出 corners=[[x,y]×4]" % (100.0 * area / frame))
    readable = [[float(round(x, 1)), float(round(y, 1))] for x, y in ordered]
    return ordered, ("自动检测到纸张四边形 %s，占比 %.0f%%，最大倾角 %.2f°"
                     % (readable, 100.0 * area / frame, max_tilt))


def op_perspective(image, params, ctx):
    corners = params.get("corners")
    normalized = as_flag(params.get("normalized"), False)
    if corners is None:
        if not as_flag(params.get("auto_detect"), False):
            raise ValueError("perspective 需要 corners=[[x,y]×4]（源图四点像素坐标），"
                             "或给 auto_detect=true 让脚本自己找纸张四边形")
        quad, detail = detect_paper_quad(image)
        if quad is None:
            ctx["notes"].append("perspective：%s" % detail)
            return image
        ctx["notes"].append("perspective：%s" % detail)
        return warp_quad(image, quad, ctx, "perspective(auto_detect)")
    quad = parse_quad(corners, image.width, image.height, "perspective.corners", normalized)
    return warp_quad(image, quad, ctx, "perspective")


def op_zoom(image, params, ctx):
    """把小字区域抠出来放大成一张新图（读不清的数值就放大再看）。"""
    box = parse_box(params.get("box"), image.width, image.height, "zoom.box", min_size=4)
    factor = as_number(params.get("factor", 2.5), "zoom.factor")
    if factor <= 0:
        raise ValueError("zoom.factor 必须大于 0")
    resample_name = str(params.get("resample", "lanczos")).lower()
    if resample_name in ("bicubic", "bicubic\u53cc\u4e09\u6b21"):
        resample = Image.BICUBIC
    elif resample_name in ("lanczos", "bicubic\u62c9\u4e09\u65af", "antialias"):
        resample = Image.LANCZOS
    else:
        raise ValueError("zoom.resample 只能是 \"lanczos\" 或 \"bicubic\"（收到 %r）"
                         % params.get("resample"))

    region = image.crop(box)
    target_w = int(round(region.width * factor))
    target_h = int(round(region.height * factor))
    capped = False
    longest = max(target_w, target_h)
    if longest > ZOOM_MAX_SIDE:
        shrink = ZOOM_MAX_SIDE / float(longest)
        target_w = max(1, int(round(target_w * shrink)))
        target_h = max(1, int(round(target_h * shrink)))
        capped = True
    zoomed = region.resize((target_w, target_h), resample)
    ctx["facts"]["zoom"] = {"box": list(box), "factor": factor,
                            "width": zoomed.width, "height": zoomed.height}
    ctx["notes"].append("zoom：把 %s 区域（%dx%d）放大 ×%g 到 %dx%d（%s）%s"
                        % (box, region.width, region.height, factor, zoomed.width,
                           zoomed.height, resample_name,
                           "；已达长边上限 %d px，实际倍数被压小" % ZOOM_MAX_SIDE
                           if capped else ""))

    extra_out = params.get("out")
    if extra_out:
        info = save_png(zoomed, extra_out, ctx.get("quantize", "auto"))
        info["kind"] = "zoom"
        info["box"] = list(box)
        ctx["extra_outputs"].append(info)
        ctx["notes"].append("zoom：另存一份放大图到 %s（同时在管线里继续后续操作）" % extra_out)
    return zoomed


def op_binarize(image, params, ctx):
    method = str(params.get("method", "otsu")).lower()
    gray = image.convert("L")
    if method == "otsu":
        threshold = otsu_threshold(gray)
        out = gray.point(lambda value: 255 if value > threshold else 0, mode="L")
        dark = sum(gray.histogram()[:threshold + 1]) / float(gray.width * gray.height)
        ctx["notes"].append("binarize：Otsu 全局阈值 %d（约 %.1f%% 像素判为前景/文字）"
                            % (threshold, dark * 100))
    elif method == "adaptive":
        block = as_int(params.get("block", 31), "binarize.block")
        if block < 3:
            raise ValueError("binarize.block 至少为 3（收到 %d）" % block)
        if block % 2 == 0:
            block += 1
        offset = as_number(params.get("c", 8), "binarize.c")
        blurred = gray.filter(ImageFilter.BoxBlur(max(1, (block - 1) // 2)))
        # (原图 - 局部均值) + 128，再以 128+c 为界二值化：局部光照不均也能救
        high_pass = ImageChops.subtract(gray, blurred, scale=1.0, offset=128)
        limit = 128 + offset
        out = high_pass.point(lambda value: 255 if value > limit else 0, mode="L")
        ctx["notes"].append("binarize：自适应阈值（BlockBlur 窗口 %d，c=%g，判据 > %g）"
                            % (block, offset, limit))
    else:
        raise ValueError("binarize.method 只能是 \"otsu\" 或 \"adaptive\"（收到 %r）"
                         % params.get("method"))
    return out


def bilateral_filter(image, radius, sigma_space, sigma_range):
    """简易双边滤波（分带 + numpy 向量化，边界用带 halo 的块避免接缝）。"""
    require_numpy("denoise 的双边滤波")
    mode = image.mode if image.mode in ("L", "RGB") else "RGB"
    source = image if image.mode == mode else image.convert(mode)
    array = np.asarray(source, dtype=np.float32)
    height, width = array.shape[:2]
    offsets = [(dy, dx) for dy in range(-radius, radius + 1)
               for dx in range(-radius, radius + 1)]
    spatial = {offset: math.exp(-(offset[0] ** 2 + offset[1] ** 2) /
                                (2.0 * sigma_space ** 2)) for offset in offsets}

    result = np.empty_like(array)
    band = 256
    for top in range(0, height, band):
        bottom = min(height, top + band)
        y0 = max(0, top - radius)
        y1 = min(height, bottom + radius)
        block = array[y0:y1]
        block_h = block.shape[0]
        weight_sum = np.zeros((block_h, width, 1), dtype=np.float32) if array.ndim == 3 \
            else np.zeros((block_h, width), dtype=np.float32)
        total = np.zeros_like(block)
        for dy, dx in offsets:
            # 用夹紧索引代替 np.roll：roll 会跨行/跨列回绕，边界会串味
            row_index = np.clip(np.arange(block_h) + dy, 0, block_h - 1)
            col_index = np.clip(np.arange(width) + dx, 0, width - 1)
            shifted = block[row_index][:, col_index]
            difference = np.abs(block - shifted)
            if array.ndim == 3:
                difference = difference.max(axis=2, keepdims=True)
            weight = spatial[(dy, dx)] * np.exp(-(difference ** 2) /
                                                (2.0 * sigma_range ** 2))
            total += shifted * weight
            weight_sum += weight
        weight_sum[weight_sum == 0] = 1.0
        filtered = total / weight_sum
        result[top:bottom] = filtered[top - y0:bottom - y0]
        del block, total, weight_sum, filtered
    return Image.fromarray(np.clip(result, 0, 255).astype(np.uint8), mode)


def op_denoise(image, params, ctx):
    method = str(params.get("method", "median")).lower()
    if method == "median":
        size = as_int(params.get("size", 3), "denoise.size")
        if size not in (3, 5, 7, 9):
            raise ValueError("denoise.size 只能是 3 / 5 / 7 / 9（收到 %s）" % size)
        out = image.filter(ImageFilter.MedianFilter(size=size))
        ctx["notes"].append("denoise：中值滤波 %dx%d（去椒盐/压缩噪点，笔画不易被抹平）" % (size, size))
        return out
    if method == "bilateral":
        radius = as_int(params.get("radius", 2), "denoise.radius")
        if radius < 1 or radius > 5:
            raise ValueError("denoise.radius 必须在 1–5 之间（收到 %d）" % radius)
        sigma_space = as_number(params.get("sigma_space", 2.0), "denoise.sigma_space")
        sigma_range = as_number(params.get("sigma_range", 25.0), "denoise.sigma_range")
        if sigma_space <= 0 or sigma_range <= 0:
            raise ValueError("denoise.sigma_space / sigma_range 必须大于 0")
        if np is None:
            out = image.filter(ImageFilter.GaussianBlur(radius=radius * 0.6))
            ctx["notes"].append("denoise：本机缺少 numpy，双边滤波降级为高斯模糊"
                                "（radius=%g，会轻微糊掉笔画）" % (radius * 0.6))
            return out
        if image.width * image.height > 12_000_000 and radius > 1:
            radius = 1
            ctx["notes"].append("denoise：图幅较大，双边滤波半径自动降到 1 以控制耗时/内存")
        out = bilateral_filter(image, radius, sigma_space, sigma_range)
        ctx["notes"].append("denoise：双边滤波（radius=%d，sigma_space=%g，sigma_range=%g）"
                            "——保边去噪，文字边缘比中值更清楚"
                            % (radius, sigma_space, sigma_range))
        return out
    raise ValueError("denoise.method 只能是 \"median\" 或 \"bilateral\"（收到 %r）"
                     % params.get("method"))


def op_enhance_text(image, params, ctx):
    """灰化 + 局部对比增强 + 轻微锐化：一步让手写/印刷字更清楚。"""
    radius = as_number(params.get("radius", 12.0), "enhance_text.radius")
    if radius <= 0:
        raise ValueError("enhance_text.radius 必须大于 0")
    strength = as_number(params.get("strength", 0.35), "enhance_text.strength")
    sharpen = as_number(params.get("sharpen", 1.1), "enhance_text.sharpen")
    cutoff = as_number(params.get("cutoff", 0.5), "enhance_text.cutoff")

    gray = image.convert("L")
    stretched = ImageOps.autocontrast(gray, cutoff=max(0.0, min(cutoff, 49.0)))
    blurred = stretched.filter(ImageFilter.GaussianBlur(radius=max(1.0, radius)))
    alpha = max(0.0, min(strength, 0.8))
    # 局部对比 = 原图 + α(原图 − 大半径模糊)，把光照不均压平、笔画更实
    high_pass = ImageChops.subtract(stretched, blurred, scale=1.0, offset=128)
    boosted = Image.blend(stretched, high_pass, alpha)
    out = boosted
    if sharpen > 0:
        out = boosted.filter(ImageFilter.UnsharpMask(
            radius=1.2, percent=int(round(sharpen * 100)), threshold=2))
    ctx["notes"].append("enhance_text：灰化 + 局部对比（半径 %g，强度 %g）+ 轻微锐化"
                        "（UnsharpMask 1.2/%d）；字迹更实，已二值化的图不必重复增强"
                        % (radius, alpha, int(round(sharpen * 100))))
    return out


def op_grayscale(image, params, ctx):
    if image.mode == "L":
        ctx["notes"].append("grayscale：已是灰度图，未改动")
        return image
    out = image.convert("L")
    ctx["notes"].append("grayscale：转为 L 灰度图")
    return out


def op_autocontrast(image, params, ctx):
    cutoff = as_number(params.get("cutoff", 0.0), "autocontrast.cutoff")
    cutoff = max(0.0, min(cutoff, 49.0))
    out = ImageOps.autocontrast(image, cutoff=cutoff)
    ctx["notes"].append("autocontrast：拉伸对比度（cutoff=%g）" % cutoff)
    return out


def op_sharpen(image, params, ctx):
    factor = as_number(params.get("factor", 1.5), "sharpen.factor")
    radius = as_number(params.get("radius", 2.0), "sharpen.radius")
    threshold = int(as_number(params.get("threshold", 3), "sharpen.threshold"))
    percent = int(round(factor * 100))
    if percent <= 0:
        ctx["notes"].append("sharpen：factor<=0，未改动")
        return image
    out = image.filter(ImageFilter.UnsharpMask(radius=radius, percent=percent,
                                              threshold=max(0, threshold)))
    ctx["notes"].append("sharpen：UnsharpMask(radius=%g, percent=%d, threshold=%d)"
                        % (radius, percent, max(0, threshold)))
    return out


OP_FUNCS = {
    "auto_orient": op_auto_orient,
    "crop": op_crop,
    "crop_box": op_crop_box,
    "upscale": op_upscale,
    "grayscale": op_grayscale,
    "autocontrast": op_autocontrast,
    "sharpen": op_sharpen,
    "probe": op_probe,
    "rotate": op_rotate,
    "deskew": op_deskew,
    "perspective": op_perspective,
    "zoom": op_zoom,
    "binarize": op_binarize,
    "denoise": op_denoise,
    "ink": op_enhance_text,
    "enhance_text": op_enhance_text,
}


def normalize_ops(ops):
    """把 ops 统一成 [{op:...}]，split 交给顶层（延后执行）。"""
    if ops is None:
        ops = []
    if not isinstance(ops, list):
        raise ValueError("ops 必须是数组")
    items = []
    for index, item in enumerate(ops):
        if isinstance(item, str):
            item = {"op": item}
        if not isinstance(item, dict):
            raise ValueError("ops[%d] 必须是对象，例如 {\"op\": \"grayscale\"}" % index)
        name = item.get("op")
        if not name:
            raise ValueError("ops[%d] 缺少 op 字段" % index)
        if str(name) not in OPS:
            raise ValueError("未知操作 op=%r；可用：%s" % (name, "、".join(OPS)))
        items.append(dict(item))
    return items


def apply_ops(image, items, ctx):
    for index, item in enumerate(items):
        name = str(item["op"])
        func = OP_FUNCS.get(name)
        if func is None:
            raise ValueError("未知操作 op=%r；可用：%s" % (name, "、".join(OPS)))
        image = func(image, item, ctx)
        image = guard_pixels(image, ctx, "ops[%d] %s" % (index, name))
    return image


# --------------------------------------------------------------------------- #
# 缩放 / 保存 / 切分
# --------------------------------------------------------------------------- #
def limit_max_side(image, max_side, ctx, label, note_list):
    if not max_side:
        return image
    longest = max(image.width, image.height)
    if longest <= max_side:
        return image
    ratio = max_side / float(longest)
    new_w = max(1, int(round(image.width * ratio)))
    new_h = max(1, int(round(image.height * ratio)))
    out = image.resize((new_w, new_h), Image.LANCZOS)
    note_list.append("%s：最长边 %d > max_side=%d，等比缩到 %dx%d"
                     % (label, longest, max_side, out.width, out.height))
    return out


def save_png(image, path, quantize_mode):
    """写 PNG；auto 模式下体积过大时用 256 色调色板兜底（取更小者）。"""
    parent = os.path.dirname(os.path.abspath(path))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)

    buffer = io.BytesIO()
    image.save(buffer, "PNG", optimize=True, compress_level=9)
    payload = buffer.getvalue()
    mode_used = image.mode

    if quantize_mode is not False and image.mode in ("RGB", "RGBA"):
        too_big = len(payload) > QUANTIZE_THRESHOLD
        if quantize_mode is True or too_big:
            try:
                source = image
                if image.mode == "RGBA":
                    alpha = image.getchannel("A")
                    if alpha.getextrema()[0] < 255:
                        source = None  # 有透明像素，不做调色板压缩
                if source is not None:
                    quantized = source.convert("RGB").quantize(
                        colors=256, method=Image.MEDIANCUT, dither=Image.FLOYDSTEINBERG)
                    alt = io.BytesIO()
                    quantized.save(alt, "PNG", optimize=True, compress_level=9)
                    alt_payload = alt.getvalue()
                    if len(alt_payload) < len(payload):
                        payload = alt_payload
                        mode_used = "P(256)"
            except Exception as exc:  # 压缩失败不影响主流程
                log("调色板压缩跳过：%s" % exc)

    with open(path, "wb") as handle:
        handle.write(payload)
    return {"path": path, "width": image.width, "height": image.height,
            "bytes": len(payload), "mode": mode_used}


def blank_cuts(image, axis, count, ctx, min_gap_ratio=0.012, blank_threshold=0.012):
    """按空白投影找切分位置：返回 (切点列表(原图坐标), 说明) 或 (None, 原因)。"""
    gray = image.convert("L")
    longest = max(gray.size)
    scale = 1.0
    if longest > 900:
        scale = 900.0 / longest
        gray = gray.resize((max(8, int(round(gray.width * scale))),
                            max(8, int(round(gray.height * scale)))), Image.BILINEAR)
    hist = gray.histogram()
    background = max(range(256), key=lambda value: hist[value])
    tolerance = 28.0
    if np is not None:
        array = np.asarray(gray, dtype=np.float32)
        ink = np.abs(array - background) > tolerance
        profile = ink.mean(axis=1 if axis == "y" else 0)
    else:
        # 没有 numpy：用 1 像素宽的 BOX 缩放拿到行/列均值
        binary = gray.point(lambda v: 255 if abs(v - background) > tolerance else 0)
        if axis == "y":
            profile = [value / 255.0 for value in
                       binary.resize((1, gray.height), Image.BOX).tobytes()]
        else:
            profile = [value / 255.0 for value in
                       binary.resize((gray.width, 1), Image.BOX).tobytes()]
    dimension = len(profile)
    blank = [value < blank_threshold for value in profile]

    min_gap = max(6, int(round(min_gap_ratio * dimension)))
    gaps = []
    index = 0
    while index < dimension:
        if blank[index]:
            start = index
            while index < dimension and blank[index]:
                index += 1
            if index - start >= min_gap:
                gaps.append((start, index))
        else:
            index += 1
    if not gaps:
        return None, ("在 %s 轴上没找到宽度 ≥ %d px（占 %.1f%%）的空白带"
                      % (axis, min_gap, min_gap_ratio * 100))

    # 贴着图边的空白带是"页边距"，不是分题间隙：切在那里只会切出一片空白
    usable = [gap for gap in gaps if gap[0] > 0 and gap[1] < dimension]
    if not usable:
        return None, ("%s 轴上的空白带都贴在图的边缘（页边距），没有可用于分题的内部空白"
                      % axis)

    if count:
        need = count - 1
        if len(usable) < need:
            return None, ("只有 %d 条可用的内部空白带（另有 %d 条页边距），不足以按 count=%d 切分"
                          % (len(usable), len(gaps) - len(usable), count))
        chosen = sorted(sorted(usable, key=lambda gap: gap[1] - gap[0], reverse=True)[:need])
    else:
        big = [gap for gap in usable if (gap[1] - gap[0]) >= max(min_gap, 0.02 * dimension)]
        if not big:
            return None, ("内部空白带都太窄（最宽 %d px < 图幅的 2%%），不敢自动切"
                          % max(gap[1] - gap[0] for gap in usable))
        chosen = sorted(big)[:19]

    cuts = [int(round((start + end) / 2.0 / scale)) for start, end in chosen]
    detail = ("按空白投影在 %s 轴 %s 处切分（空白带宽度 %s px，原图坐标）"
              % (axis, cuts, [int(round((end - start) / scale)) for start, end in chosen]))
    return cuts, detail


def split_pieces(image, split, ctx):
    """按 split 配置切块；支持 mode=auto（空白投影）与等分两种。

    返回 [{"index","box","image"}] 与一条人话说明。
    """
    if not isinstance(split, dict):
        raise ValueError("split 必须是对象，例如 {\"count\": 3, \"axis\": \"y\"}")
    axis = str(split.get("axis", "y")).lower()
    if axis in ("x", "w", "horizontal", "横"):
        axis = "x"
    elif axis in ("y", "h", "vertical", "竖"):
        axis = "y"
    else:
        raise ValueError("split.axis 只能是 \"x\" 或 \"y\"（收到 %r）" % split.get("axis"))
    overlap = as_number(split.get("overlap", 0.0), "split.overlap")
    if overlap < 0 or overlap >= 0.5:
        raise ValueError("split.overlap 必须在 [0, 0.5) 之间（收到 %s）" % overlap)
    mode = str(split.get("mode", "even")).lower()
    if mode in ("auto", "auto_count", "blank"):
        mode = "auto"
    elif mode in ("even", "equal", "fixed", ""):
        mode = "even"
    else:
        raise ValueError("split.mode 只能是 \"even\"（等分）或 \"auto\"（按空白投影）；"
                         "收到 %r" % split.get("mode"))

    count = None
    if split.get("count") is not None:
        try:
            count = int(split.get("count"))
        except Exception:
            raise ValueError("split.count 必须是整数")
        if count < 2:
            raise ValueError("split.count 至少为 2（当前 %s）" % count)
        if count > 40:
            raise ValueError("split.count 过大（%s），最多 40" % count)

    dim = image.height if axis == "y" else image.width
    detail = ""
    if mode == "auto":
        cuts, why = blank_cuts(image, axis, count, ctx)
        if cuts is None:
            if count is None:
                raise ValueError("split(mode=auto)：%s；可改用手动 {\"count\": N, \"axis\": \"%s\"}"
                                 % (why, axis))
            detail = "split(auto)：%s，已回退为等分 %d 块。" % (why, count)
            cuts = []
        else:
            detail = "split(auto)：%s，切 %d 块。" % (why, len(cuts) + 1)
    else:
        if count is None:
            raise ValueError("split 缺少 count（等分模式必须给出 count，或用 mode=\"auto\"）")
        cuts = []

    if cuts:
        boundaries = [0] + sorted(cuts) + [dim]
    else:
        step = dim / float(count)
        boundaries = [int(round(index * step)) for index in range(count + 1)]
        boundaries[0], boundaries[-1] = 0, dim

    entries = []
    for index in range(len(boundaries) - 1):
        start, end = boundaries[index], boundaries[index + 1]
        span = end - start
        if span < 8:
            end = min(dim, start + 8)
        margin = int(round(span * overlap / 2.0))
        box_start = 0 if index == 0 else max(0, start - margin)
        box_end = dim if index == len(boundaries) - 2 else min(dim, end + margin)
        if box_end - box_start < 8:
            box_end = min(dim, box_start + 8)
        if axis == "y":
            box = (0, box_start, image.width, box_end)
        else:
            box = (box_start, 0, box_end, image.height)
        entries.append({"index": index + 1, "box": box, "image": image.crop(box)})

    if not detail:
        detail = ("沿 %s 轴等分 %d 块，overlap=%g（块间重叠约 %d px）"
                  % (axis, len(entries), overlap, int(round(dim / float(len(entries)) * overlap))))
    elif overlap:
        detail += "块间重叠 overlap=%g。" % overlap
    return entries, detail


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def run(spec):
    ctx = {"notes": [], "probes": [], "facts": {}, "extra_outputs": [],
           "quantize": "auto"}

    src = spec.get("src") or spec.get("image") or spec.get("path")
    if not src:
        raise ValueError("spec 缺少 src（源图片路径）")
    if not os.path.isfile(src):
        raise ValueError("源图片不存在：%s" % src)

    out = spec.get("out")
    out_dir = spec.get("out_dir")

    quantize_mode = spec.get("quantize", "auto")
    if quantize_mode not in (True, False, "auto", None):
        raise ValueError("quantize 只能是 true / false / \"auto\"")
    ctx["quantize"] = quantize_mode

    max_side = spec.get("max_side")
    defaulted_max_side = max_side is None
    if max_side is None:
        max_side = DEFAULT_MAX_SIDE
    else:
        max_side = int(as_number(max_side, "max_side"))
        if max_side < 64:
            raise ValueError("max_side 太小（%s），至少 64" % max_side)
    ctx["facts"]["max_side"] = max_side

    items = normalize_ops(spec.get("ops"))
    if as_flag(spec.get("probe"), False) and not any(
            str(item["op"]) == "probe" for item in items):
        items.insert(0, {"op": "probe"})
    probe_requested = any(str(item["op"]) == "probe" for item in items)

    split = spec.get("split")
    pipeline = []
    for item in items:
        if str(item["op"]) == "split":
            if split is None:
                split = {key: value for key, value in item.items() if key != "op"}
                ctx["notes"].append("split：以 ops 里的 split 操作生效（等价于顶层 split 字段）")
            else:
                ctx["notes"].append("split：ops 里的 split 被忽略（已给出顶层 split）")
            continue
        pipeline.append(item)

    if not out and not (out_dir and split) and not probe_requested:
        raise ValueError("spec 至少需要 out（主输出），或同时给出 out_dir 与 split")

    try:
        image = Image.open(src)
        image.load()
    except Exception as exc:
        raise ValueError("无法读取图片 %s：%s" % (src, exc))

    ctx["notes"].append("读入 %dx%d（mode=%s）" % (image.width, image.height, image.mode))

    # 供 auto_orient 之类需要在管线中途另存一张辅助图的操作用（如方向候选对照图）
    ctx["out"] = out
    ctx["out_dir"] = out_dir

    image = apply_ops(image, pipeline, ctx)

    if image.width < 8 or image.height < 8:
        raise ValueError("处理后的图片尺寸异常：%dx%d" % (image.width, image.height))

    outputs = []
    main_path = None

    # 先按 max_side 限制主图，再写文件（没有 out 时不必缩放，避免误导性记录）
    if out:
        main_image = limit_max_side(image, max_side, ctx, "max_side", ctx["notes"])
        info = save_png(main_image, out, quantize_mode)
        outputs.append(info)
        main_path = out
        if info["mode"] not in ("RGB", "L", "RGBA"):
            ctx["notes"].append("主图体积偏大，已用 256 色调色板 PNG 压缩以控制体积")

    outputs.extend(ctx["extra_outputs"])

    # 切块从「未缩小的高分辨率图」出发，保证每块尽量清晰，再各自限制 max_side
    if split:
        if not out_dir:
            raise ValueError("指定了 split 就必须提供 out_dir（切块输出目录）")
        entries, detail = split_pieces(image, split, ctx)
        ctx["notes"].append(detail)
        for entry in entries:
            piece = limit_max_side(entry["image"], max_side, ctx, "max_side(piece)", ctx["notes"])
            path = os.path.join(out_dir, "piece%d.png" % entry["index"])
            info = save_png(piece, path, quantize_mode)
            info["index"] = entry["index"]
            info["box"] = list(entry["box"])
            info["source_box"] = list(entry["box"])
            outputs.append(info)
        ctx["notes"].append("切块输出目录：%s" % out_dir)

    probes = ctx["probes"]
    probe = probes[-1] if probes else None
    return {
        "ok": True,
        "outputs": outputs,
        "main": main_path or (outputs[0]["path"] if outputs else None),
        "notes": ctx["notes"],
        "probe": probe,
        "probes": probes,
        "suggested_ops": probe["suggested_ops"] if probe else [],
        "deskew": ctx["facts"].get("deskew"),
        "limits": {
            "max_side": max_side,
            "max_side_defaulted": bool(defaulted_max_side),
            "max_pixels": MAX_PIXELS,
            "zoom_max_side": ZOOM_MAX_SIDE,
        },
    }


def main(argv):
    try:
        spec = load_spec(argv)
    except Exception as exc:
        return fail(exc)
    try:
        result = run(spec)
    except Exception as exc:
        log("失败：%s" % exc)
        return fail(exc)
    return emit(result, 0)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))