#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学科预设（preset）：把「学科习惯的数据形状」展开成**通用图元系列**。

架构约定（这是本文件存在的唯一理由）
-----------------------------------
引擎 `figure.py` 只认「坐标系 + 通用系列（line/step/area/scatter/polygon/circle/text/annotate…）」，
**不认识任何学科**。学科的"样子"——工程力学的阶梯内力图、应力分布图、等比例莫尔圆——
全部落在这里：每个预设只是一个**纯函数**，输入该学科的紧凑数据（segments / dist / sigma_x…），
输出一个标准 figure spec（axes + series + 标注）。

因此：
* 想加一种**图型**（新系列种类）→ 改 `figure.py` 的 `SERIES_KINDS`，不要动本文件；
* 想加一种**学科外观**（新预设）→ 在 `PRESETS` 里注册一个展开函数，不要写新的渲染脚本。

本文件不 import 渲染层，也不画图，只做数据 → spec 的展开，因此可以单独测试。
所有预设都会避免两类视觉缺陷：**标注压住图形元素**、**同类信息重复标注**。
"""

import math

# 正负配色与既有工程图习惯一致（正蓝负橙），颜色不是唯一信息通道：数值同时标注在图上
COL_POS = "#1c60be"
COL_NEG = "#c4710c"
COL_CURVE = "#1c60be"
COL_AXIS = "#2b3440"
COL_MUTED = "#6c7888"
COL_MARK = "#c62828"

KIND_TITLES = {
    "axial": "轴力图 N",
    "shear": "剪力图 Q",
    "moment": "弯矩图 M",
    "torque": "扭矩图 T",
    "stress": "横截面正应力分布 σ",
    "mohr": "莫尔应力圆",
    "deflection": "挠曲线 v(x)",
}

KIND_SYMBOLS = {
    "axial": ("N", "kN"),
    "shear": ("Q", "kN"),
    "moment": ("M", "kN·m"),
    "torque": ("T", "kN·m"),
    "deflection": ("v", "mm"),
}

KIND_CONVENTIONS = {
    "axial": "轴力：拉为正",
    "shear": "剪力：使微段顺时针转动为正",
    "moment": "弯矩：下侧受拉为正",
    "torque": "扭矩：右手螺旋沿 +x 为正",
    "stress": "正应力：拉为正",
    "mohr": "σ 拉为正；τ 使单元顺时针转为正",
    "deflection": "挠度：按题设正方向",
}


def fmt(value, digits=4):
    """工程记法：太多小数在图上就是噪音。"""
    if value is None:
        return ""
    try:
        number = float(value)
    except Exception:
        return str(value)
    if number != number or number in (float("inf"), float("-inf")):
        return str(number)
    if abs(number) < 1e-12:
        return "0"
    text = "%.*g" % (int(digits), number)
    return text


def _f(value, default=0.0):
    try:
        return float(value)
    except Exception:
        return default


def _title_of(panel, default_kind):
    return panel.get("title") or KIND_TITLES.get(panel.get("preset") or default_kind, default_kind)


def _unit_note(symbol, unit):
    return "%s (%s)" % (symbol, unit) if unit else symbol


def _convention_box(panel, kind, extra=None):
    """右上角说明：符号约定（+ 可能的关键极值）。"""
    box = {}
    convention = panel.get("conventions") or KIND_CONVENTIONS.get(kind)
    if convention:
        box["正方向"] = convention
    if extra:
        box.update(extra)
    return box


def _notes_for(panel, kind):
    notes = []
    for item in panel.get("footnotes") or []:
        notes.append(str(item))
    return notes


def _callout_offset(panel, value, distance=26.0):
    """标注偏移方向：默认把文字推到图形外侧。

    多图纵向排列（Q/M 共享 x 轴）时，图的上方紧邻上一个子图的标题，
    往上标注**推太远**会撞标题；但反过来朝零线一侧推，又会把文字和引线推进
    阴影填充区里（独立评审实测："引线与曲线及填充区域重叠"）。
    所以这里改成"仍然朝外侧，但贴紧曲线"：既离开填充区，也不会顶到子图标题。
    """
    if panel.get("annotate_inside"):
        return [30.0, 12.0] if value >= 0 else [30.0, -12.0]
    return [16.0, distance] if value >= 0 else [16.0, -distance]


# --------------------------------------------------------------------------
# 阶梯型内力图（轴力 / 剪力 / 扭矩）：一次展开，三种学科共用
# --------------------------------------------------------------------------
def _stepped_runs(segs):
    """按正负号把连续的分段分成若干"同号段"，以便分色填充。"""
    runs = []
    for seg in segs:
        value = _f(seg.get("value"))
        sign = 1 if value > 1e-12 else (-1 if value < -1e-12 else 0)
        if runs and runs[-1]["sign"] == sign:
            runs[-1]["segs"].append(seg)
        else:
            runs.append({"sign": sign, "segs": [seg]})
    return runs


def _preset_stepped(panel, kind):
    preset = panel.get("preset") or kind
    segs_in = panel.get("segments")
    if not isinstance(segs_in, list) or not segs_in:
        # 分布载荷下的剪力/轴力是**线性变化**的，用 points 描述才自然。
        # 只认阶梯段会把这一类最常见的图挡在门外（实测踩到过），所以给 points 就转曲线画法。
        if isinstance(panel.get("points"), list) and len(panel["points"]) >= 2:
            return _preset_curve(panel, kind)
        raise ValueError("%s 预设需要非空的 segments:[{x0,x1,value}]，"
                         "或改用 points:[{x,value}]（线性变化时）" % preset)
    segments = sorted(segs_in, key=lambda item: _f(item.get("x0")))
    unit = panel.get("unit") or KIND_SYMBOLS.get(kind, ("", ""))[1]
    symbol = panel.get("symbol") or KIND_SYMBOLS.get(kind, ("N", ""))[0]
    abs_labels = bool(panel.get("abs_labels"))

    xs_all, vs_all = [], []
    for seg in segments:
        x0, x1 = _f(seg.get("x0")), _f(seg.get("x1"), _f(seg.get("x0")))
        xs_all += [x0, x1]
        vs_all.append(_f(seg.get("value")))

    series = []
    # 同号段分组 → 每组一个填充 + 一条阶梯折线（包含竖跳线与回到零线的收口）
    for run in _stepped_runs(segments):
        color = COL_POS if run["sign"] >= 0 else COL_NEG
        points_x, points_y = [], []
        for seg in run["segs"]:
            x0, x1 = _f(seg.get("x0")), _f(seg.get("x1"), _f(seg.get("x0")))
            if x1 < x0:
                x0, x1 = x1, x0
            value = _f(seg.get("value"))
            points_x += [x0, x0, x1]
            points_y += [0.0, value, value]
        points_x.append(points_x[-1])
        points_y.append(0.0)
        series.append({"kind": "area", "x": list(points_x), "y": list(points_y), "baseline": 0.0,
                       "color": color, "alpha": 0.16})
        series.append({"kind": "line", "x": list(points_x), "y": list(points_y),
                       "color": color, "width": 2.4})

    # 分段数值直接标在图上（放在零线的另一侧，避免压住图形）
    for seg in segments:
        x0, x1 = _f(seg.get("x0")), _f(seg.get("x1"), _f(seg.get("x0")))
        if x1 < x0:
            x0, x1 = x1, x0
        value = _f(seg.get("value"))
        if abs(value) < 1e-12:
            continue
        label = fmt(abs(value) if abs_labels else value)
        if unit:
            label = "%s %s" % (label, unit)
        if seg.get("label"):
            label = "%s（%s）" % (label, seg["label"])
        mid = (x0 + x1) / 2.0
        series.append({
            "kind": "text", "xy": [mid, value],
            "text": label, "size": 9,
            "color": COL_POS if value > 0 else COL_NEG,
            "ha": "center", "va": "bottom" if value > 0 else "top",
        })

    # 间断跳变：只在分段之间真的不连续时标一次，且用引线引到图上（引线不会穿过文字）
    extras = {}
    jump_index = 0
    for previous, current in zip(segments, segments[1:]):
        before, after = _f(previous.get("value")), _f(current.get("value"))
        if abs(after - before) < 1e-12:
            continue
        x = _f(current.get("x0"))
        target = max(before, after, key=abs)
        text = "%s → %s" % (fmt(before), fmt(after))
        if unit:
            text = "%s %s" % (text, unit)
        series.append({
            "kind": "annotate", "xy": [x, target], "text": text,
            "xytext": _callout_offset(panel, target, 28.0),
            "size": 8.5, "color": COL_MARK,
        })
        jump_index += 1

    # 显式极值标记（marks）：值只在图上标一次，不再另起一行汇总
    for mark in panel.get("marks") or []:
        value = _f(mark.get("value"))
        x = _f(mark.get("x"))
        text = mark.get("text")
        if not text:
            text = fmt(value)
            if unit:
                text = "%s %s" % (text, unit)
        series.append({
            "kind": "scatter", "x": [x], "y": [value], "size": 34, "color": COL_MARK,
        })
        series.append({
            "kind": "annotate", "xy": [x, value], "text": text,
            "xytext": _callout_offset(panel, value, 28.0),
            "size": 9, "color": COL_MARK,
        })

    xlim = panel.get("xlim") or [min(xs_all), max(xs_all)]
    if xlim[0] == xlim[1]:
        xlim = [xlim[0], xlim[1] + 1.0]
    lo, hi = min(vs_all + [0.0]), max(vs_all + [0.0])
    if hi - lo < 1e-12:
        hi, lo = hi + 1.0, lo - 1.0
    pad = (hi - lo) * 0.26
    ylim = panel.get("ylim") or [lo - pad, hi + pad]

    axes = [{
        "type": "cartesian",
        "title": _title_of(panel, kind),
        "xlim": xlim,
        "ylim": ylim,
        "xlabel": panel.get("x_label") or "x",
        "ylabel": _unit_note(symbol, unit) if panel.get("ylabel") is None else panel.get("ylabel"),
        "unit": _convention_box(panel, kind),
        "grid": True,
        "legend": False,
        "series": series,
    }]
    return {"axes": axes, "title": panel.get("title_override"), "note": _notes_for(panel, kind)}


# --------------------------------------------------------------------------
# 曲线型（弯矩图 / 挠曲线）
# --------------------------------------------------------------------------
def _preset_curve(panel, kind):
    preset = panel.get("preset") or kind
    if panel.get("segments") and not panel.get("points"):
        return _preset_stepped(panel, kind)
    points_in = panel.get("points")
    if not isinstance(points_in, list) or len(points_in) < 2:
        raise ValueError("%s 预设需要 points:[{x,value}]（至少 2 点），或给 segments 走阶梯图" % preset)
    points = sorted(((_f(p.get("x")), _f(p.get("value"))) for p in points_in), key=lambda item: item[0])
    unit = panel.get("unit") or KIND_SYMBOLS.get(kind, ("", ""))[1]
    symbol = panel.get("symbol") or KIND_SYMBOLS.get(kind, ("M", ""))[0]
    xs = [item[0] for item in points]
    ys = [item[1] for item in points]

    series = []
    if points[0][1] * points[-1][1] >= 0 and panel.get("fill", True):
        series.append({"kind": "area", "x": xs, "y": ys, "baseline": 0.0, "color": COL_CURVE, "alpha": 0.14})
    series.append({"kind": "line", "x": xs, "y": ys, "color": COL_CURVE, "width": 2.6})

    # 端值标注：两端都是 0（简支梁两端、铰支端的常态）时一个都不标——零线本来就在图上，
    # 两端各写一个一模一样的 "0 kN·m" 是典型的"同类信息重复标注"；两端同值也只标一次。
    ends = [(points[0], 1.0), (points[-1], -1.0)]
    if abs(points[0][1]) < 1e-12 and abs(points[-1][1]) < 1e-12:
        ends = []
    elif abs(points[0][1] - points[-1][1]) < 1e-12:
        ends = [(points[0], 1.0)]
    for (x, value), direction in ends:
        label = fmt(value) + ((" " + unit) if unit else "")
        series.append({"kind": "annotate", "xy": [x, value], "text": label, "size": 9,
                       "color": COL_CURVE, "arrow": False,
                       "xytext": [direction * 6.0, 8.0 if value >= 0 else -14.0]})
    extremum = max(points, key=lambda item: abs(item[1]))
    if extremum not in (points[0], points[-1]) and abs(extremum[1]) > 1e-12:
        label = fmt(extremum[1]) + ((" " + unit) if unit else "")
        series.append({"kind": "scatter", "x": [extremum[0]], "y": [extremum[1]],
                       "size": 34, "color": COL_MARK})
        series.append({"kind": "annotate", "xy": [extremum[0], extremum[1]], "text": label,
                       "xytext": _callout_offset(panel, extremum[1], 28.0),
                       "size": 9.5, "color": COL_MARK})

    for mark in panel.get("marks") or []:
        value, x = _f(mark.get("value")), _f(mark.get("x"))
        text = mark.get("text") or (fmt(value) + ((" " + unit) if unit else ""))
        series.append({"kind": "scatter", "x": [x], "y": [value], "size": 30, "color": COL_MARK})
        series.append({"kind": "annotate", "xy": [x, value], "text": text,
                       "xytext": _callout_offset(panel, value, 28.0), "size": 9, "color": COL_MARK})

    lo, hi = min(ys + [0.0]), max(ys + [0.0])
    if hi - lo < 1e-12:
        hi, lo = hi + 1.0, lo - 1.0
    pad = (hi - lo) * 0.26
    unit_box = _convention_box(panel, kind)
    if panel.get("show_extremum", False):
        unit_box = _convention_box(panel, kind, {
            "极值": "%s%s @x=%s" % (fmt(extremum[1]), (" " + unit) if unit else "", fmt(extremum[0])),
        })
    axes = [{
        "type": "cartesian",
        "title": _title_of(panel, kind),
        "xlim": panel.get("xlim") or [xs[0], xs[-1]],
        "ylim": panel.get("ylim") or [lo - pad, hi + pad],
        "xlabel": panel.get("x_label") or "x",
        "ylabel": _unit_note(symbol, unit) if panel.get("ylabel") is None else panel.get("ylabel"),
        "unit": unit_box,
        "grid": True,
        "legend": False,
        "series": series,
    }]
    return {"axes": axes, "title": panel.get("title_override"), "note": _notes_for(panel, kind)}


# --------------------------------------------------------------------------
# 应力分布（横轴 σ，纵轴截面高度 y）
# --------------------------------------------------------------------------
def _preset_stress(panel, kind="stress"):
    dist_in = panel.get("dist")
    if not isinstance(dist_in, list) or len(dist_in) < 2:
        raise ValueError("stress 预设需要 dist:[{y,sigma}]（至少 2 点）")
    dist = sorted(((_f(p.get("y")), _f(p.get("sigma"))) for p in dist_in), key=lambda item: item[0])
    unit = panel.get("unit") or "MPa"
    ys = [item[0] for item in dist]
    sigmas = [item[1] for item in dist]

    lo, hi = min(sigmas + [0.0]), max(sigmas + [0.0])
    span = hi - lo or 1.0
    # 左侧留出画截面示意的余量；右侧留出标注余量
    xlim = panel.get("xlim") or [lo - span * 0.62, hi + span * 0.30]
    ylo, yhi = min(ys), max(ys)
    yspan = (yhi - ylo) or 1.0
    ylim = panel.get("ylim") or [ylo - yspan * 0.16, yhi + yspan * 0.16]

    shape = str(panel.get("type") or "linear")
    curve = []
    if shape == "parabolic":
        steps = 60
        s0, s1 = dist[0][1], dist[-1][1]
        for index in range(steps + 1):
            t = index / float(steps)
            y = ylo + (yhi - ylo) * t
            sigma = s0 + (s1 - s0) * (1.0 - (2.0 * t - 1.0) ** 2)
            curve.append((y, sigma))
    else:
        curve = list(dist)

    fill_points = [[0.0, curve[0][0]]] + [[sigma, y] for y, sigma in curve] + [[0.0, curve[-1][0]]]
    face = COL_POS if curve[-1][1] >= 0 else COL_NEG
    series = [
        {"kind": "polygon", "xy": fill_points, "facecolor": face, "color": face,
         "alpha": 0.20, "closed": True},
        {"kind": "line", "x": [sigma for _, sigma in curve], "y": [y for y, _ in curve],
         "color": COL_CURVE, "width": 2.6},
        # σ = 0 的轴线必须真的画在 σ = 0
        {"kind": "vline", "x": 0.0, "color": COL_AXIS, "width": 1.8},
    ]

    neutral = panel.get("neutral_axis")
    if neutral is None and ylo <= 0.0 <= yhi:
        neutral = 0.0
    if neutral is not None:
        series.append({"kind": "hline", "y": _f(neutral), "color": COL_AXIS, "width": 1.2,
                       "style": (0, (6, 4))})
        series.append({"kind": "text", "xy": [xlim[1], _f(neutral)], "text": "中性轴 σ=0",
                       "size": 8.5, "color": COL_MUTED, "ha": "right",
                       "va": "bottom" if _f(neutral) >= 0 else "top"})

    # 截面示意（放在左侧留白里，不与曲线重叠）
    section_x0 = xlim[0] + span * 0.10
    section_x1 = xlim[0] + span * 0.34
    series.append({"kind": "polygon",
                   "xy": [[section_x0, ylo], [section_x1, ylo], [section_x1, yhi], [section_x0, yhi]],
                   "facecolor": "#e8ecf1", "color": COL_MUTED, "alpha": 0.9, "closed": True})
    series.append({"kind": "text", "xy": [(section_x0 + section_x1) / 2.0, yhi + yspan * 0.06],
                   "text": "截面", "size": 8.5, "color": COL_MUTED, "ha": "center", "va": "bottom"})

    # 上下边缘应力：各自朝远离 σ=0 轴的方向标注，不会压住曲线
    for index in (0, len(dist) - 1):
        y, sigma = dist[index]
        label = "%s %s" % (fmt(sigma), unit)
        outward = 1 if sigma >= 0 else -1
        series.append({"kind": "scatter", "x": [sigma], "y": [y], "size": 26,
                       "color": COL_POS if sigma >= 0 else COL_NEG})
        series.append({"kind": "annotate", "xy": [sigma, y], "text": label,
                       "xytext": [outward * 22, 4 if y >= 0 else -14], "size": 9,
                       "color": COL_POS if sigma >= 0 else COL_NEG})

    axes = [{
        "type": "cartesian",
        "title": _title_of(panel, kind),
        "xlim": xlim,
        "ylim": ylim,
        "xlabel": panel.get("x_label") or ("σ (%s)" % unit),
        "ylabel": panel.get("y_label") or "y（截面高度）",
        "unit": _convention_box(panel, kind),
        "grid": True,
        "legend": False,
        "series": series,
    }]
    return {"axes": axes, "title": panel.get("title_override"), "note": _notes_for(panel, kind)}


# --------------------------------------------------------------------------
# 莫尔应力圆：等比例是硬要求
# --------------------------------------------------------------------------
def _preset_mohr(panel, kind="mohr"):
    sx = _f(panel.get("sigma_x"))
    sy = _f(panel.get("sigma_y"))
    txy = _f(panel.get("tau_xy"))
    unit = panel.get("unit") or "MPa"
    cx = (sx + sy) / 2.0
    radius = math.hypot((sx - sy) / 2.0, txy)
    s1, s2 = cx + radius, cx - radius
    lim = max(radius * 1.28, abs(cx) + radius * 1.12, 1e-9)
    # 等比例 + x/y 跨度相同 ⇒ 圆一定是正圆；画框自动居中，不会偏上或留空
    xlim = [cx - lim, cx + lim]
    ylim = [-lim, lim]

    series = [
        # τ 轴（σ = 0 的竖脊）必须落在 σ = 0，而不是画框边缘
        {"kind": "vline", "x": 0.0, "color": COL_AXIS, "width": 1.6},
        {"kind": "hline", "y": 0.0, "color": COL_AXIS, "width": 1.6},
        {"kind": "circle", "center": [cx, 0.0], "r": radius, "color": COL_CURVE, "width": 2.6},
    ]

    def outward(point_x, point_y, distance=30.0):
        """把标注推到圆的**外侧**，保证不压圆弧。"""
        dx, dy = point_x - cx, point_y - 0.0
        norm = math.hypot(dx, dy) or 1.0
        return [dx / norm * distance, dy / norm * distance]

    # 圆心：标注放到左下（远离直径线 AB），并带引线
    series.append({"kind": "scatter", "x": [cx], "y": [0.0], "size": 28, "color": COL_AXIS})
    series.append({"kind": "annotate", "xy": [cx, 0.0], "text": "O′(%s, 0)" % fmt(cx),
                   "xytext": [-14, -30], "size": 8.5, "color": COL_MUTED})

    # 代表点 A(σx, τxy)、B(σy, −τxy)：直径线 + 外推标注
    for point_x, point_y, name in ((sx, txy, "A"), (sy, -txy, "B")):
        series.append({"kind": "line", "x": [cx, point_x], "y": [0.0, point_y],
                       "color": "#b98a8a", "width": 1.2, "style": (0, (5, 4))})
        series.append({"kind": "scatter", "x": [point_x], "y": [point_y], "size": 30, "color": COL_MARK})
        series.append({"kind": "annotate", "xy": [point_x, point_y],
                       "text": "%s(%s, %s)" % (name, fmt(point_x), fmt(point_y)),
                       "xytext": outward(point_x, point_y), "size": 8.5, "color": COL_MARK})

    # 主应力点：位置用实心点标出，数值统一放在图上方说明带（不重复标注）
    for value in (s1, s2):
        series.append({"kind": "scatter", "x": [value], "y": [0.0], "size": 26, "color": COL_AXIS})
    series.append({"kind": "scatter", "x": [cx], "y": [radius], "size": 24, "color": COL_MUTED})
    # "τmax" 抬到圆弧上方一点：正好写在圆顶点上时，文字会与圆弧笔画叠在一起糊成一片（独立评审实测）
    series.append({"kind": "text", "xy": [cx, radius * 1.08 + 0.6], "text": "τmax", "size": 8.5,
                   "color": COL_MUTED, "ha": "center", "va": "bottom"})
    # σ₁ / σ₂ 正好落在圆与 σ 轴的交点上，所以必须**沿 σ 轴向外**推出去，否则会压住圆弧
    # 注意用 ASCII 的 σ1 / σ2：U+2081/U+2082 下标字符在两套字体里都会渲染成空心方框（独立评审实测）
    series.append({"kind": "annotate", "xy": [s1, 0.0], "text": "σ1", "size": 9,
                   "color": COL_AXIS, "xytext": [10.0, -20.0], "arrow": False})
    series.append({"kind": "annotate", "xy": [s2, 0.0], "text": "σ2", "size": 9,
                   "color": COL_AXIS, "xytext": [-10.0, -20.0], "arrow": False})

    axes = [{
        "type": "cartesian",
        "title": _title_of(panel, kind),
        "xlim": xlim,
        "ylim": ylim,
        "aspect": "equal",
        "xlabel": "σ (%s)" % unit,
        "ylabel": "τ (%s)" % unit,
        "unit": {
            "σ1": "%s %s" % (fmt(s1), unit),
            "σ2": "%s %s" % (fmt(s2), unit),
            "τmax": "%s %s" % (fmt(radius), unit),
        },
        "legend": False,
        "series": series,
    }]
    return {"axes": axes, "title": panel.get("title_override"), "note": _notes_for(panel, kind)}


PRESETS = {
    "axial": lambda panel: _preset_stepped(panel, "axial"),
    "shear": lambda panel: _preset_stepped(panel, "shear"),
    "torque": lambda panel: _preset_stepped(panel, "torque"),
    "moment": lambda panel: _preset_curve(panel, "moment"),
    "deflection": lambda panel: _preset_curve(panel, "deflection"),
    "stress": lambda panel: _preset_stress(panel, "stress"),
    "mohr": lambda panel: _preset_mohr(panel, "mohr"),
}

PRESET_NAMES = sorted(PRESETS)

# 预设自己管理轴名与配色时，这些键不必再传给渲染层
_CONSUMED_KEYS = (
    "preset", "segments", "points", "dist", "marks", "unit", "symbol", "abs_labels",
    "conventions", "footnotes", "sigma_x", "sigma_y", "tau_xy", "neutral_axis",
    "show_extremum", "fill", "x_label", "y_label", "title_override",
)


def expand(panel):
    """把一个带 preset 的 panel 展开成标准 axes 列表 + 附加信息。

    @param panel - 含 `preset` 的字典（顶层 spec 或 `panels` 里的一项）。
    @returns `{"axes": [...], "title": ..., "note": [...], "preset": name}`。
    @throws ValueError 预设名未知或必需数据缺失时给出可读原因。
    """
    preset = panel.get("preset")
    if preset not in PRESETS:
        raise ValueError("未知 preset=%r（可用：%s）" % (preset, "/".join(PRESET_NAMES)))
    built = PRESETS[preset](panel)
    axes = built.get("axes") or []
    # 调用方显式给的通用字段（slot / xlim / ylim / hide_xticklabels / xlabel…）优先级最高
    passthrough = {key: panel[key] for key in ("slot", "hide_xticklabels", "sharex") if key in panel}
    for index, axis in enumerate(axes):
        if index == 0:
            axis.update(passthrough)
        if panel.get("xlim") and panel.get("xlim") != axis.get("xlim"):
            axis["xlim"] = panel["xlim"]
        if panel.get("ylim"):
            axis["ylim"] = panel["ylim"]
        if panel.get("title") and index == 0:
            axis["title"] = panel["title"]
    return {
        "axes": axes,
        # 调用方显式写 main_title 就该赢过预设自带的默认标题，
        # 否则"我明明指定了标题，出来的却是别的话"（实测踩到过）
        "title": panel.get("main_title") or built.get("title"),
        "note": built.get("note") or [],
        "preset": preset,
    }


def consumed_keys():
    """预设已经处理掉的字段（避免渲染层再当成未知字段）。"""
    return _CONSUMED_KEYS