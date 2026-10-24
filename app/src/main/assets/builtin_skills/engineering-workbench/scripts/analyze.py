#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
dsh-mech-workbench —— 数值分析内核 (Phase 1：静定问题)

职责
----
读取一份题目 spec(JSON)，求解静定杆/梁/轴的支座反力、内力图(N/Q/M/T)、
极值位置、截面几何与应力，并给出可核验的 checks；输出严格为单个 JSON 对象。

命令行
------
    <python> analyze.py --spec <spec.json>
    <python> analyze.py --spec-json '<inline json>'

成功 -> stdout 恰好一个 {"ok": true, ...}，退出码 0。
失败 -> stdout 恰好一个 {"ok": false, "error": "...", "detail": "..."}，退出码 1。
任何情况下 stdout 不出现日志/警告/traceback（异常一律转为上述 JSON）。

依赖：标准库 + numpy（禁止 sympy / scipy / matplotlib / pandas）。

=====================================================================
符号约定（与输出 conventions 字段一致）
=====================================================================
坐标：x 自构件左端量起，单位 = units.length。
外载荷：
  point_force.value 为力沿其方向的分量；dir="transverse" 时**向上为正**，
                    dir="axial" 时**向右(+x)为正**。
  distributed.q0/q1 为分布载荷集度(力/长度)，**向上为正**，沿 x 线性变化。
  point_moment.value **逆时针(CCW)为正**。
  point_torque.value 用**右手螺旋沿 +x 为正**。
支座反力：Rx 沿 +x，Ry 沿 +y(向上)，Mz 逆时针为正，Mx 右手螺旋 +x。
内力（截面内力，取左段为研究对象时的内力正方向）：
  N 轴力 **拉为正**；
  Q 剪力 **使微段顺时针转为正**（左段向上/右段向下）；
  M 弯矩 **使下侧受拉为正**（下凹/正弯矩，即左段截面内力矩为逆时针）；
  T 扭矩 **沿 +x 右手螺旋为正**。

由左段平衡可得闭式表达式（本文件全部按此实现）：
  N(x) = -Σ_{a<x} Fx
  Q(x) = +Σ_{a<x} Fy
  M(x) = +Σ_{a<x} [ (x-a)·Fy - Mz ]
  T(x) = -Σ_{a<x} Mx
分布载荷按 [x0, min(x1,x)] 区间解析积分后并入上述求和。
由此自动满足 dM/dx = Q、dQ/dx = q(x)。

=====================================================================
建模与已知限制
=====================================================================
* 平面问题，平衡方程 3 个（ΣFx、ΣFy、ΣM(左端)）；轴(kind="shaft") 追加
  ΣMx=0 共 4 个方程，固定端提供 4 个未知量(Rx,Ry,Mz,Mx)，铰 2、辊轴 1。
* 未知反力数 > 方程数 => determinate=false（Phase 1 不做变形协调/刚度法）。
* 约束不足或机构（秩亏、或载荷无法平衡）=> ok=false。
* 不处理：静不定、塑性、稳定(压杆失稳)、大变形、剪切变形、内力铰(hinge)、
  变截面梁的分段刚度（几何为常量截面；分段结果请拆成多个 spec）。
"""

import sys
import os
import json
import math
import sys

import numpy as np

try:  # stdout 必须是 UTF-8，避免中文在 GBK 控制台下被打碎
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ---------------------------------------------------------------------
# 基础工具
# ---------------------------------------------------------------------

TINY = 1e-300
POS_TOL_REL = 1e-9          # 位置比较用的相对容差


class SpecError(Exception):
    """模型/参数非法（可读中文原因）。"""

    def __init__(self, msg, detail=""):
        super().__init__(msg)
        self.msg = msg
        self.detail = detail


def _clean(v):
    """把 -0.0 / 极小数清成规整的 0.0，避免输出里出现 -0.0。"""
    if isinstance(v, float):
        if v == 0.0:
            return 0.0
        if not math.isfinite(v):
            raise SpecError("数值溢出：出现非有限值")
    return v


def _clean_obj(o):
    if isinstance(o, dict):
        return {k: _clean_obj(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_clean_obj(v) for v in o]
    if isinstance(o, np.generic):
        o = o.item()
    return _clean(o)


def _rerr(actual, expected, scale):
    """相对误差（分母加保护防 0 除）。"""
    den = max(abs(expected), abs(scale), 1e-12)
    return abs(actual - expected) / den


# ---------------------------------------------------------------------
# 单位（仅用于把结果换算到 units.stress 等声明单位；内部计算自洽）
# ---------------------------------------------------------------------

_LEN_TO_M = {"m": 1.0, "mm": 1e-3, "cm": 1e-2, "dm": 1e-1, "km": 1e3, "in": 0.0254}
_FORCE_TO_N = {"N": 1.0, "kN": 1e3, "MN": 1e6, "kgf": 9.80665, "tf": 9806.65, "lbf": 4.4482216}
_STRESS_TO_PA = {"Pa": 1.0, "kPa": 1e3, "MPa": 1e6, "GPa": 1e9, "N/mm^2": 1e6, "N/mm2": 1e6}

_DEFAULT_UNITS = {"length": "m", "force": "kN", "moment": "kN*m", "stress": "MPa"}


def _norm_unit(u):
    return str(u).strip().replace("·", "*").replace(".", "*").replace(" ", "").replace("²", "^2")


def resolve_units(units):
    """把 units 声明折算为到 SI 的比例因子；未知单位给出警告而非报错。"""
    u = dict(_DEFAULT_UNITS)
    warn = []
    if isinstance(units, dict):
        for k, v in units.items():
            if v is not None:
                u[k] = v
    raw_l, raw_f, raw_s = u["length"], u["force"], u["stress"]
    nl, nf, ns = _norm_unit(raw_l), _norm_unit(raw_f), _norm_unit(raw_s)
    len_to_m = _LEN_TO_M.get(nl)
    if len_to_m is None:
        warn.append("未识别的长度单位 %r，按 m 处理" % raw_l)
        len_to_m = 1.0
    force_to_n = _FORCE_TO_N.get(nf)
    if force_to_n is None:
        warn.append("未识别的力单位 %r，按 N 处理" % raw_f)
        force_to_n = 1.0
    stress_to_pa = _STRESS_TO_PA.get(ns)
    if stress_to_pa is None:
        warn.append("未识别的应力单位 %r，按 Pa 处理" % raw_s)
        stress_to_pa = 1.0
    # 力矩单位：由 力单位×长度单位 推导；仅做一致性提醒
    nm = _norm_unit(u["moment"])
    base_stress_to_pa = force_to_n / (len_to_m ** 2)   # 1 个 base 应力 = ? Pa
    return {
        "declared": {"length": raw_l, "force": raw_f, "moment": u["moment"], "stress": raw_s},
        "len_to_m": len_to_m,
        "force_to_n": force_to_n,
        "stress_to_pa": stress_to_pa,
        "base_stress_to_pa": base_stress_to_pa,
        "moment_norm": nm,
        "warnings": warn,
    }


# ---------------------------------------------------------------------
# 截面几何
# ---------------------------------------------------------------------

_SHAPES = ("rect", "circle", "tube", "ishape")


def _num(params, key, shape, positive=True):
    if key not in params:
        raise SpecError("截面 %s 缺少参数 %r" % (shape, key),
                        "params 应包含 " + str(sorted(params.keys())))
    v = params[key]
    if not isinstance(v, (int, float)) or isinstance(v, bool):
        raise SpecError("截面参数 %s.%s 必须为数值" % (shape, key))
    v = float(v)
    if not math.isfinite(v):
        raise SpecError("截面参数 %s.%s 非有限值" % (shape, key))
    if positive and v <= 0:
        raise SpecError("截面参数 %s.%s 必须 > 0（实际 %g）" % (shape, key, v))
    return v


def build_section(sec):
    """计算截面几何量（解析式）+ 用于数值积分的宽度函数。

    返回 dict：A, Iy, W, Sy, ymax, Ip, Wp, tau_max_coeff, t_na, integrate(f)
    """
    if not isinstance(sec, dict):
        raise SpecError("section 必须为对象")
    shape = sec.get("shape")
    if shape not in _SHAPES:
        raise SpecError("未知截面形状 %r" % (shape,),
                        "支持的形状：" + ", ".join(_SHAPES))
    params = sec.get("params") or {}
    if not isinstance(params, dict):
        raise SpecError("section.params 必须为对象")
    # E 只影响变形/挠度，静定反力、内力与应力都不需要它。
    # 原先缺 E 直接报错，会让「只求最大弯矩」这类题被无谓拦住，故改为可选。
    E = sec.get("E", None)
    if E is not None:
        if not isinstance(E, (int, float)) or isinstance(E, bool):
            raise SpecError("section.E 必须为数值")
        E = float(E)
        if E <= 0 or not math.isfinite(E):
            raise SpecError("section.E 必须 > 0（实际 %g）" % E)
    material = sec.get("material", None)

    ip = wp = None
    pieces = []      # 用于陀螺积分：[(y0,y1,width 常量)] 或 圆/管专用

    if shape == "rect":
        b = _num(params, "b", shape)
        h = _num(params, "h", shape)
        A = b * h
        Iy = b * h ** 3 / 12.0
        ymax = h / 2.0
        Sy = b * h ** 2 / 8.0
        t_na = b
        def integrate(f):
            return _simpson(lambda y: f(y) * b, -h / 2.0, h / 2.0)
        kind_geo = "rect"

    elif shape == "circle":
        d = _num(params, "d", shape)
        R = d / 2.0
        A = math.pi * d ** 2 / 4.0
        Iy = math.pi * d ** 4 / 64.0
        ymax = d / 2.0
        Sy = d ** 3 / 12.0
        t_na = d
        ip = math.pi * d ** 4 / 32.0
        wp = math.pi * d ** 3 / 16.0
        def integrate(f):
            # y = R sinθ, w·dy = 2R²cos²θ dθ（光滑化处理圆边界）
            g = lambda th: f(R * math.sin(th)) * 2.0 * R * R * math.cos(th) ** 2
            return _simpson(g, -math.pi / 2.0, math.pi / 2.0)
        kind_geo = "circle"

    elif shape == "tube":
        D = _num(params, "D", shape)
        d = _num(params, "d", shape)
        if d >= D:
            raise SpecError("管截面应满足 D > d（实际 D=%g, d=%g）" % (D, d))
        R, r = D / 2.0, d / 2.0
        A = math.pi * (D ** 2 - d ** 2) / 4.0
        Iy = math.pi * (D ** 4 - d ** 4) / 64.0
        ymax = D / 2.0
        Sy = (D ** 3 - d ** 3) / 12.0
        t_na = D - d
        ip = math.pi * (D ** 4 - d ** 4) / 32.0
        wp = math.pi * (D ** 4 - d ** 4) / (16.0 * D)
        def integrate(f):
            g_out = lambda th: f(R * math.sin(th)) * 2.0 * R * R * math.cos(th) ** 2
            g_in = lambda th: f(r * math.sin(th)) * 2.0 * r * r * math.cos(th) ** 2
            return (_simpson(g_out, -math.pi / 2.0, math.pi / 2.0)
                    - _simpson(g_in, -math.pi / 2.0, math.pi / 2.0))
        kind_geo = "tube"

    else:  # ishape
        bf = _num(params, "bf", shape)
        tf = _num(params, "tf", shape)
        hw = _num(params, "hw", shape)
        tw = _num(params, "tw", shape)
        H = 2.0 * tf + hw
        A = 2.0 * bf * tf + hw * tw
        yf = hw / 2.0 + tf / 2.0                      # 翼缘形心到中性轴
        Iy = 2.0 * (bf * tf ** 3 / 12.0 + bf * tf * yf ** 2) + tw * hw ** 3 / 12.0
        ymax = H / 2.0
        Sy = bf * tf * yf + tw * hw ** 2 / 8.0        # 中性轴以上面积对中性轴的静矩
        t_na = tw
        def integrate(f):
            tot = _simpson(lambda y: f(y) * tw, -hw / 2.0, hw / 2.0)
            tot += _simpson(lambda y: f(y) * bf, hw / 2.0, hw / 2.0 + tf)
            tot += _simpson(lambda y: f(y) * bf, -hw / 2.0 - tf, -hw / 2.0)
            return tot
        kind_geo = "ishape"

    W = Iy / ymax
    # τmax = tau_max_coeff · |Q|max / A（由 τ = Q·Sy/(Iy·t_na) 换算）
    tau_max_coeff = A * Sy / (Iy * t_na)

    return {
        "shape": shape,
        "params": params,
        "E": E,
        "material": material,
        "A": A, "Iy": Iy, "W": W, "Sy": Sy, "ymax": ymax,
        "Ip": ip, "Wp": wp,
        "tau_max_coeff": tau_max_coeff,
        "t_na": t_na,
        "geometric_kind": kind_geo,
        "integrate": integrate,
    }


# ---------------------------------------------------------------------
# 数值积分（复合 Simpson）
# ---------------------------------------------------------------------

def _simpson(f, a, b, n=400):
    if b == a:
        return 0.0
    if n % 2:
        n += 1
    h = (b - a) / float(n)
    s = f(a) + f(b)
    for i in range(1, n):
        s += (4.0 if i % 2 else 2.0) * f(a + i * h)
    return s * h / 3.0


# ---------------------------------------------------------------------
# 模型装配
# ---------------------------------------------------------------------

_SUPPORT_UNKNOWN = {
    # (平面内力未知量个数, 是否提供扭转约束)
    "fixed": (3, True),
    "pin": (2, False),
    "roller": (1, False),
}
_KINDS = ("bar", "beam", "shaft")


def _fnum(obj, key, where, required=True, default=None):
    if key not in obj or obj[key] is None:
        if required:
            raise SpecError("%s 缺少字段 %r" % (where, key))
        return default
    v = obj[key]
    if not isinstance(v, (int, float)) or isinstance(v, bool):
        raise SpecError("%s 的 %s 必须为数值" % (where, key))
    v = float(v)
    if not math.isfinite(v):
        raise SpecError("%s 的 %s 非有限值" % (where, key))
    return v


def build_model(spec):
    """解析 spec -> 支持集、事件集、分布载荷集（含反力占位）。"""
    if not isinstance(spec, dict):
        raise SpecError("spec 必须是 JSON 对象")

    kind = spec.get("kind")
    if kind not in _KINDS:
        raise SpecError("未知构件类型 kind=%r" % (kind,),
                        "支持：" + ", ".join(_KINDS))

    L = _fnum(spec, "length", "spec")
    if L <= 0:
        raise SpecError("构件长度 length 必须 > 0（实际 %g）" % L)

    units = resolve_units(spec.get("units"))

    supports_raw = spec.get("supports")
    if supports_raw is None:
        raise SpecError("缺少 supports 字段")
    if not isinstance(supports_raw, list) or len(supports_raw) == 0:
        raise SpecError("约束不足/机构", "未定义任何支座（supports 为空）")

    tol = POS_TOL_REL * max(L, 1.0)

    supports = []
    for i, s in enumerate(supports_raw):
        if not isinstance(s, dict):
            raise SpecError("supports[%d] 必须为对象" % i)
        st = s.get("type")
        if st not in _SUPPORT_UNKNOWN:
            raise SpecError("未知支座类型 %r（supports[%d]）" % (st, i),
                            "支持：fixed, pin, roller")
        sx = _fnum(s, "x", "supports[%d]" % i)
        if sx < -tol or sx > L + tol:
            raise SpecError("支座位置 x=%g 超出构件区间 [0, %g]" % (sx, L))
        sx = min(max(sx, 0.0), L)
        supports.append({"x": sx, "type": st, "index": i})

    loads_raw = spec.get("loads") or []
    if not isinstance(loads_raw, list):
        raise SpecError("loads 必须为数组")

    events = []       # 集中事件：force / moment / torque
    dists = []        # 分布载荷
    for i, ld in enumerate(loads_raw):
        if not isinstance(ld, dict):
            raise SpecError("loads[%d] 必须为对象" % i)
        t = ld.get("type")
        where = "loads[%d]" % i
        if t == "point_force":
            lx = _fnum(ld, "x", where)
            if lx < -tol or lx > L + tol:
                raise SpecError("%s 位置 x=%g 超出构件区间 [0, %g]" % (where, lx, L))
            d = ld.get("dir", "transverse")
            if d not in ("axial", "transverse"):
                raise SpecError("%s 的 dir=%r 非法" % (where, d), "支持：axial, transverse")
            v = _fnum(ld, "value", where)
            ev = {"x": min(max(lx, 0.0), L), "Fx": 0.0, "Fy": 0.0, "Mz": 0.0, "Mx": 0.0,
                  "tag": where, "jump": True}
            if d == "axial":
                ev["Fx"] = v
            else:
                ev["Fy"] = v
            events.append(ev)
        elif t == "distributed":
            x0 = _fnum(ld, "x0", where)
            x1 = _fnum(ld, "x1", where)
            if x0 > x1:
                x0, x1 = x1, x0
            if x0 < -tol or x1 > L + tol:
                raise SpecError("%s 区间 [%g, %g] 超出构件区间 [0, %g]" % (where, x0, x1, L))
            if x1 - x0 <= 0:
                raise SpecError("%s 的分布区间长度必须 > 0" % where)
            d = ld.get("dir", "transverse")
            if d not in ("axial", "transverse"):
                raise SpecError("%s 的 dir=%r 非法" % (where, d), "支持：axial, transverse")
            q0 = _fnum(ld, "q0", where)
            q1 = _fnum(ld, "q1", where, required=False, default=q0)
            dists.append({"x0": min(max(x0, 0.0), L), "x1": min(max(x1, 0.0), L),
                          "q0": q0, "q1": q1, "dir": d, "tag": where})
        elif t == "point_moment":
            lx = _fnum(ld, "x", where)
            if lx < -tol or lx > L + tol:
                raise SpecError("%s 位置 x=%g 超出构件区间 [0, %g]" % (where, lx, L))
            v = _fnum(ld, "value", where)
            events.append({"x": min(max(lx, 0.0), L), "Fx": 0.0, "Fy": 0.0, "Mz": v, "Mx": 0.0,
                           "tag": where, "jump": v != 0.0})
        elif t == "point_torque":
            lx = _fnum(ld, "x", where)
            if lx < -tol or lx > L + tol:
                raise SpecError("%s 位置 x=%g 超出构件区间 [0, %g]" % (where, lx, L))
            v = _fnum(ld, "value", where)
            events.append({"x": min(max(lx, 0.0), L), "Fx": 0.0, "Fy": 0.0, "Mz": 0.0, "Mx": v,
                           "tag": where, "jump": v != 0.0})
        else:
            raise SpecError("%s 的 type=%r 非法" % (where, t),
                            "支持：point_force, distributed, point_moment, point_torque")

    stations = spec.get("stations") or []
    if not isinstance(stations, list):
        raise SpecError("stations 必须为数组")
    st_list = []
    for i, s in enumerate(stations):
        if not isinstance(s, (int, float)) or isinstance(s, bool):
            raise SpecError("stations[%d] 必须为数值" % i)
        sx = float(s)
        if not math.isfinite(sx):
            raise SpecError("stations[%d] 非有限值" % i)
        if sx < -tol or sx > L + tol:
            raise SpecError("stations[%d]=%g 超出构件区间 [0, %g]" % (i, sx, L))
        st_list.append(min(max(sx, 0.0), L))

    return {
        "kind": kind, "L": L, "units": units, "supports": supports,
        "events": events, "dists": dists, "stations": st_list, "tol": tol,
    }


# ---------------------------------------------------------------------
# 平衡方程与反力求解
# ---------------------------------------------------------------------

def _dist_resultant(d):
    """整段分布载荷的合力与对 x=0 的力矩（∫t·q dt），向上/正向为正。"""
    s = d["x1"] - d["x0"]
    k = (d["q1"] - d["q0"]) / s
    F = d["q0"] * s + k * s * s / 2.0
    tq = d["x0"] * (d["q0"] * s + k * s * s / 2.0) + d["q0"] * s * s / 2.0 + k * s ** 3 / 3.0
    return F, tq


def solve_reactions(model, geo):
    """装配平衡方程，用 numpy 最小二乘求解，并做秩/残差检查。"""
    L, kind = model["L"], model["kind"]
    shaft_mode = (kind == "shaft")
    n_eq = 4 if shaft_mode else 3

    # 未知量布局
    layout = []
    n_unk = 0
    for s in model["supports"]:
        n_fixed = _SUPPORT_UNKNOWN[s["type"]][0]
        cols = {"Rx": None, "Ry": None, "Mz": None, "Mx": None}
        if s["type"] == "fixed":
            cols["Rx"], cols["Ry"], cols["Mz"] = n_unk, n_unk + 1, n_unk + 2
            n_unk += 3
            if shaft_mode:
                cols["Mx"] = n_unk
                n_unk += 1
        elif s["type"] == "pin":
            cols["Rx"], cols["Ry"] = n_unk, n_unk + 1
            n_unk += 2
        else:
            cols["Ry"] = n_unk
            n_unk += 1
        layout.append(cols)

    total_unknowns = n_unk
    torsion_active = shaft_mode or any(abs(e["Mx"]) > 0.0 for e in model["events"])

    # 载荷合力（不含反力）
    Fx_l = sum(e["Fx"] for e in model["events"])
    Fy_l = sum(e["Fy"] for e in model["events"])
    M0_l = sum(e["Mz"] + e["x"] * e["Fy"] for e in model["events"])
    Mx_l = sum(e["Mx"] for e in model["events"])
    for d in model["dists"]:
        F, tq = _dist_resultant(d)
        if d["dir"] == "axial":
            Fx_l += F
        else:
            Fy_l += F
            M0_l += tq

    # 载荷量级（用于容差）
    load_scale = 0.0
    for e in model["events"]:
        load_scale += abs(e["Fx"]) + abs(e["Fy"]) + abs(e["Mz"]) / max(L, 1.0) + abs(e["Mx"]) / max(L, 1.0)
    for d in model["dists"]:
        F, tq = _dist_resultant(d)
        load_scale += abs(F) + abs(tq) / max(L, 1.0)
    load_scale = max(load_scale, 1e-12)

    # 非轴类模型：外扭矩之和必须自平衡
    if (not shaft_mode) and torsion_active:
        if abs(Mx_l) > 1e-8 * max(load_scale, abs(Mx_l)):
            raise SpecError(
                "扭矩不平衡：非轴类模型（kind=%s）没有扭转约束" % kind,
                "外扭矩之和 ΣMx = %g，无法平衡；请改用 kind=\"shaft\" 并设置固定端" % Mx_l)

    # 静定判定：未知反力数 > 平衡方程数
    if total_unknowns > n_eq:
        return {
            "determinate": False,
            "unsupported": ["静不定：本工具 Phase 1 覆盖静定问题，需变形协调或刚度法"],
            "total_unknowns": total_unknowns,
            "n_eq": n_eq,
        }

    A = np.zeros((n_eq, max(total_unknowns, 1)))
    b = np.zeros(n_eq)
    for si, s in enumerate(model["supports"]):
        cols = layout[si]
        x = s["x"]
        if cols["Rx"] is not None:
            A[0, cols["Rx"]] = 1.0
        if cols["Ry"] is not None:
            A[1, cols["Ry"]] = 1.0
            A[2, cols["Ry"]] = x
        if cols["Mz"] is not None:
            A[2, cols["Mz"]] = 1.0
        if shaft_mode and cols["Mx"] is not None:
            A[3, cols["Mx"]] = 1.0
    b[0] = -Fx_l
    b[1] = -Fy_l
    b[2] = -M0_l
    if shaft_mode:
        b[3] = -Mx_l

    A_use = A[:, :total_unknowns] if total_unknowns > 0 else A[:, :0]
    if total_unknowns == 0:
        raise SpecError("约束不足/机构", "未定义任何支座")

    # 支座位置重复 -> 约束重复（建模错误，直接判机构）
    sp = model["supports"]
    for i in range(len(sp)):
        for j in range(i + 1, len(sp)):
            if abs(sp[i]["x"] - sp[j]["x"]) <= model["tol"]:
                raise SpecError("约束不足/机构",
                                "支座约束重复：supports[%d] 与 supports[%d] 位于同一位置 x=%g"
                                % (i, j, sp[i]["x"]))

    rank = int(np.linalg.matrix_rank(A_use))
    sol, _res, _rk, _sv = np.linalg.lstsq(A_use, b, rcond=None)
    resid = float(np.linalg.norm(A_use @ sol - b))
    denom = max(float(np.linalg.norm(b)), 1e-12)
    # 先看载荷能否平衡：不能平衡 => 缺少必要约束方向（机构/约束不足）
    if resid > 1e-7 * denom:
        raise SpecError(
            "约束不足/机构",
            "平衡方程无法满足（残差 %.3e，载荷范数 %.3e）：支座方向无法承担该载荷组合" % (resid, denom))
    # 载荷可平衡但约束方向线性相关 => 多余约束（静不定），不编造结果
    if rank < total_unknowns:
        return {
            "determinate": False,
            "unsupported": [
                "静不定：本工具 Phase 1 覆盖静定问题，需变形协调或刚度法",
                "支承约束方向线性相关（秩 %d < 未知反力数 %d）：存在多余约束或缺失约束方向，"
                "反力不能由平衡方程唯一确定" % (rank, total_unknowns),
            ],
            "total_unknowns": total_unknowns,
            "n_eq": n_eq,
        }

    reactions = []
    for si, s in enumerate(model["supports"]):
        cols = layout[si]
        rec = {"x": s["x"], "type": s["type"], "Rx": 0.0, "Ry": 0.0, "Mz": 0.0}
        if shaft_mode:
            rec["Mx"] = 0.0
        if cols["Rx"] is not None:
            rec["Rx"] = float(sol[cols["Rx"]])
        if cols["Ry"] is not None:
            rec["Ry"] = float(sol[cols["Ry"]])
        if cols["Mz"] is not None:
            rec["Mz"] = float(sol[cols["Mz"]])
        if shaft_mode and cols["Mx"] is not None:
            rec["Mx"] = float(sol[cols["Mx"]])
        reactions.append(rec)

    return {
        "determinate": True,
        "reactions": reactions,
        "total_unknowns": total_unknowns,
        "n_eq": n_eq,
        "torsion_active": torsion_active,
        "load_scale": load_scale,
        "resid_rel": resid / denom,
        "rank": rank,
        "sums": {"Fx_l": Fx_l, "Fy_l": Fy_l, "M0_l": M0_l, "Mx_l": Mx_l},
    }


# ---------------------------------------------------------------------
# 内力计算
# ---------------------------------------------------------------------

def make_event_list(model, reactions):
    """把反力并入事件集合（求解内力用）。"""
    evs = [dict(e) for e in model["events"]]
    for s, r in zip(model["supports"], reactions):
        if r["Rx"] or r["Ry"] or r["Mz"] or r.get("Mx"):
            evs.append({"x": s["x"], "Fx": r["Rx"], "Fy": r["Ry"], "Mz": r["Mz"],
                        "Mx": r.get("Mx", 0.0), "tag": "reaction@%g" % s["x"],
                        "jump": True})
    return evs


def internal_at(x, include_at, evs, dists, tol):
    """按符号约定计算某截面内力。include_at=True 表示位于该 x 的集中事件计入左段(x+侧)。"""
    N = Q = M = T = 0.0
    for e in evs:
        if e["Fx"] == 0.0 and e["Fy"] == 0.0 and e["Mz"] == 0.0 and e["Mx"] == 0.0:
            continue
        left = (e["x"] <= x + tol) if include_at else (e["x"] < x - tol)
        if not left:
            continue
        N += -e["Fx"]
        Q += e["Fy"]
        M += (x - e["x"]) * e["Fy"] - e["Mz"]
        T += -e["Mx"]
    for d in dists:
        xa = min(d["x1"], x)
        s = xa - d["x0"]
        if s <= 0.0:
            continue
        span = d["x1"] - d["x0"]
        k = (d["q1"] - d["q0"]) / span
        F = d["q0"] * s + k * s * s / 2.0
        if d["dir"] == "axial":
            N += -F
        else:
            tq = d["x0"] * (d["q0"] * s + k * s * s / 2.0) + d["q0"] * s * s / 2.0 + k * s ** 3 / 3.0
            Q += F
            M += x * F - tq
    return N, Q, M, T


def critical_xs(model):
    xs = {0.0, model["L"]}
    for s in model["supports"]:
        xs.add(s["x"])
    for e in model["events"]:
        xs.add(e["x"])
    for d in model["dists"]:
        xs.add(d["x0"])
        xs.add(d["x1"])
    for s in model["stations"]:
        xs.add(s)
    return sorted(xs)


def has_jump_at(x, evs, tol):
    for e in evs:
        if abs(e["x"] - x) <= tol and e["jump"] and (e["Fx"] or e["Fy"] or e["Mz"] or e["Mx"]):
            return True
    return False


def build_diagrams(model, evs):
    """分段 + 间断点重复点，按 x 升序。返回 {channel: [{'x':..,'value':..}, ...]}"""
    L = model["L"]
    tol = model["tol"]
    xs = critical_xs(model)
    dists = model["dists"]

    samples = []   # (x, include_at)
    for x in xs:
        is_left_end = x <= tol
        is_right_end = abs(x - L) <= tol
        if is_left_end and not is_right_end:
            samples.append((x, True))
        elif is_right_end and not is_left_end:
            samples.append((x, False))
        elif is_left_end and is_right_end:
            samples.append((x, True))
        else:
            if has_jump_at(x, evs, tol):
                samples.append((x, False))
                samples.append((x, True))
            else:
                samples.append((x, False))

    # 区间内加密：与分布载荷重叠的区间 >= 20 段
    fine = []
    for i in range(len(xs) - 1):
        a, b = xs[i], xs[i + 1]
        if b - a <= 1e-15:
            continue
        n = 20 if any(min(d["x1"], b) > max(d["x0"], a) for d in dists) else 3
        for k in range(1, n):
            fine.append((a + (b - a) * k / float(n), False))
    samples.extend(fine)

    samples.sort(key=lambda p: (p[0], 0 if not p[1] else 1))
    # 去重
    uniq = []
    for x, inc in samples:
        if uniq and abs(uniq[-1][0] - x) <= 1e-15 and uniq[-1][1] == inc:
            continue
        uniq.append((x, inc))

    diagrams = {"N": [], "Q": [], "M": [], "T": []}
    for x, inc in uniq:
        N, Q, M, T = internal_at(x, inc, evs, dists, tol)
        diagrams["N"].append({"x": x, "value": N})
        diagrams["Q"].append({"x": x, "value": Q})
        diagrams["M"].append({"x": x, "value": M})
        diagrams["T"].append({"x": x, "value": T})
    return diagrams


# ---------------------------------------------------------------------
# 极值
# ---------------------------------------------------------------------

def _ternary(f, a, b, want_max, iters=200):
    lo, hi = a, b
    for _ in range(iters):
        if hi - lo <= 1e-14 * max(abs(lo), abs(hi), 1.0):
            break
        m1 = lo + (hi - lo) / 3.0
        m2 = hi - (hi - lo) / 3.0
        f1, f2 = f(m1), f(m2)
        if want_max:
            if f1 < f2:
                lo = m1
            else:
                hi = m2
        else:
            if f1 > f2:
                lo = m1
            else:
                hi = m2
    xm = 0.5 * (lo + hi)
    return xm, f(xm)


def compute_extrema(model, evs, diagrams):
    """极值：含跨中/支座/Q=0 处 M 极值，位置用三分搜索精确化。"""
    L, tol = model["L"], model["tol"]
    dists = model["dists"]
    xs = critical_xs(model)
    if xs[0] > 0.0:
        xs = [0.0] + xs
    if xs[-1] < L:
        xs = xs + [L]

    def f(ch, x):
        return internal_at(x, False, evs, dists, tol)[{"N": 0, "Q": 1, "M": 2, "T": 3}[ch]]

    cands = {ch: [] for ch in ("N", "Q", "M", "T")}
    qzero = []
    # 候选点带来源优先级 prio：解析求根(3) > 图上的临界点(2) > 网格点(1) > 三分搜索(0)
    # 数值并列时优先采用解析位置，避免三分搜索在浮点“平顶”上位置漂移。
    for ch in cands:
        for pt in diagrams[ch]:
            cands[ch].append((pt["x"], pt["value"], 2))

    NGRID = 60
    for i in range(len(xs) - 1):
        c0, c1 = xs[i], xs[i + 1]
        if c1 - c0 <= 1e-15:
            continue

        # 子区间内取值的“侧”规则：区间内部（含左端）集中事件都属于左段 => include_at=True；
        # 只有恰好落在右端点时取 x- 侧（该点右侧的间断值由下一个子区间的左端覆盖）。
        def fsub(ch, x, _c1=c1):
            inc = abs(x - _c1) > 1e-15 * max(abs(_c1), 1.0)
            return internal_at(x, inc, evs, dists, tol)[{"N": 0, "Q": 1, "M": 2, "T": 3}[ch]]

        grid = [c0 + (c1 - c0) * k / float(NGRID) for k in range(NGRID + 1)]
        for ch in cands:
            vals = [fsub(ch, x) for x in grid]
            for k, v in enumerate(vals):
                cands[ch].append((grid[k], v, 1))
            for k in range(1, NGRID):
                if vals[k] >= vals[k - 1] and vals[k] >= vals[k + 1] and \
                        (vals[k] > vals[k - 1] or vals[k] > vals[k + 1]):
                    xm, vm = _ternary(lambda t: fsub(ch, t), grid[k - 1], grid[k + 1], True)
                    cands[ch].append((xm, vm, 0))
                if vals[k] <= vals[k - 1] and vals[k] <= vals[k + 1] and \
                        (vals[k] < vals[k - 1] or vals[k] < vals[k + 1]):
                    xm, vm = _ternary(lambda t: fsub(ch, t), grid[k - 1], grid[k + 1], False)
                    cands[ch].append((xm, vm, 0))

        # M 的极值补充：显式求 Q 变号点（Q=0 处 M 取极值）
        NG = 200
        gx = [c0 + (c1 - c0) * k / float(NG) for k in range(NG + 1)]
        gq = [fsub("Q", x) for x in gx]
        for k in range(NG):
            if gq[k] * gq[k + 1] < 0.0:
                lo, hi = gx[k], gx[k + 1]
                flo = gq[k]
                for _ in range(200):
                    mid = 0.5 * (lo + hi)
                    fm = fsub("Q", mid)
                    if flo * fm <= 0.0:
                        hi = mid
                    else:
                        lo, flo = mid, fm
                xz = 0.5 * (lo + hi)
                qzero.append(xz)
                cands["M"].append((xz, fsub("M", xz), 3))

    # Q/N 的极值补充：分布载荷集度 q(x)=0 处 Q（或 N）取极值，直接解析求根
    for d in dists:
        if d["q0"] * d["q1"] < 0.0:
            span = d["x1"] - d["x0"]
            t = d["x0"] + span * (-d["q0"]) / (d["q1"] - d["q0"])
            if d["x0"] < t < d["x1"]:
                ch_q = "N" if d["dir"] == "axial" else "Q"
                cands[ch_q].append((t, f(ch_q, t), 3))

    out = {}
    scale = {ch: max([abs(v) for _, v, _p in cands[ch]] + [0.0]) for ch in cands}
    for ch in cands:
        pts = cands[ch]
        if not pts:
            continue
        tol_v = 1e-12 * max(scale[ch], 1.0)

        def _pick(want_max):
            key = (max if want_max else min)(v for _, v, _p in pts)
            band = [(x, v, p) for x, v, p in pts
                    if ((v >= key - tol_v) if want_max else (v <= key + tol_v))]
            if want_max:
                band.sort(key=lambda t: (-t[1], -t[2], t[0]))
            else:
                band.sort(key=lambda t: (t[1], -t[2], t[0]))
            return band[0][0], band[0][1]

        xmax, vmax = _pick(True)
        xmin, vmin = _pick(False)
        rec = {
            "max": vmax, "max_x": xmax,
            "min": vmin, "min_x": xmin,
        }
        if ch == "M":
            absmax = max(abs(v) for _, v, _p in pts)
            band = [(x, v, p) for x, v, p in pts if abs(abs(v) - absmax) <= tol_v]
            band.sort(key=lambda t: (-abs(t[1]), -t[2], t[0]))
            rec["abs_max"] = abs(band[0][1])
            rec["abs_max_x"] = band[0][0]
        out[ch] = rec
    out["_q_zero_x"] = qzero
    return out


# ---------------------------------------------------------------------
# 应力
# ---------------------------------------------------------------------

def compute_stresses(geo, diagrams, extrema, units):
    A, Iy, W, ymax = geo["A"], geo["Iy"], geo["W"], geo["ymax"]
    base_to_stress = units["base_stress_to_pa"] / units["stress_to_pa"]

    # 沿 x 扫描 N 与 M 的组合，取极值纤维应力最大处
    # σ(x,y) = N/A + M·y/Iy ；|σ| 的最大值 = |M|/W + |N|/A（出现在 y = ±ymax 之一）
    best = None
    pts = diagrams["M"]
    n_by_x = {round(p["x"], 12): p["value"] for p in diagrams["N"]}
    for p in pts:
        x = p["x"]
        Mv = p["value"]
        Nv = n_by_x.get(round(p["x"], 12), 0.0)
        s_bend = abs(Mv) * ymax / Iy          # base 单位（力/长度²）
        s_axial = abs(Nv) / A
        tot = s_bend + s_axial
        if best is None or tot > best[0]:
            # 取 |σ| 较大的那根纤维
            s_pos = Mv * ymax / Iy + Nv / A
            s_neg = -Mv * ymax / Iy + Nv / A
            y = ymax if abs(s_pos) >= abs(s_neg) else -ymax
            best = (tot, x, y, s_bend, s_axial, Nv, Mv)
    if best is None:
        return {}

    tot, x_star, y_star, s_bend, s_axial, Nv, Mv = best
    sigma_max = tot * base_to_stress
    sigma_bending = s_bend * base_to_stress
    sigma_axial = s_axial * base_to_stress

    Qabs = max([abs(p["value"]) for p in diagrams["Q"]] + [0.0])
    qx = 0.0
    for p in diagrams["Q"]:
        if abs(p["value"]) >= Qabs - 1e-12 * max(Qabs, 1.0):
            qx = p["x"]
            break
    tau_max = geo["tau_max_coeff"] * Qabs / A * base_to_stress

    res = {
        "sigma_max": sigma_max,
        "sigma_max_x": x_star,
        "sigma_max_y": y_star,
        "sigma_bending": sigma_bending,
        "sigma_axial": sigma_axial,
        "sigma_N": Nv,
        "sigma_M": Mv,
        "tau_max": tau_max,
        "tau_max_x": qx,
        "tau_max_Q": Qabs,
        "tau_max_coeff": geo["tau_max_coeff"],
    }
    if geo["Wp"]:
        Tabs = max([abs(p["value"]) for p in diagrams["T"]] + [0.0])
        res["tau_torsion_max"] = Tabs / geo["Wp"] * base_to_stress
    return res


# ---------------------------------------------------------------------
# checks
# ---------------------------------------------------------------------

def geometric_checks(geo):
    """仅依赖截面几何的 checks（静不定分支也给得出）。"""
    A_n, ydA, y2dA = section_integrals(geo)
    checks = []
    checks.append({
        "name": "截面面积一致性 ∫dA=A",
        "expected": geo["A"], "actual": A_n,
        "rel_error": _rerr(A_n, geo["A"], geo["A"]),
        "pass": _rerr(A_n, geo["A"], geo["A"]) < 1e-8,
        "note": "数值积分核对解析面积",
    })
    checks.append({
        "name": "中性轴过形心 ∫y dA=0",
        "expected": 0.0, "actual": ydA,
        "rel_error": abs(ydA) / max(geo["ymax"] * geo["A"], 1e-12),
        "pass": abs(ydA) / max(geo["ymax"] * geo["A"], 1e-12) < 1e-8,
        "note": "",
    })
    checks.append({
        "name": "惯性矩一致性 ∫y²dA=Iy",
        "expected": geo["Iy"], "actual": y2dA,
        "rel_error": _rerr(y2dA, geo["Iy"], geo["Iy"]),
        "pass": _rerr(y2dA, geo["Iy"], geo["Iy"]) < 1e-8,
        "note": "",
    })
    return checks


def section_integrals(geo):
    """对截面做数值条带积分（圆/管用 θ 代换光滑化），返回 ∫dA, ∫y dA, ∫y²dA。"""
    integ = geo["integrate"]
    A_n = integ(lambda y: 1.0)
    ydA = integ(lambda y: y)
    y2dA = integ(lambda y: y * y)
    return A_n, ydA, y2dA


def build_checks(model, geo, solve, evs, diagrams, extrema):
    checks = []
    L = model["L"]
    tol = model["tol"]
    reactions = solve["reactions"]
    sums = solve["sums"]
    load_scale = solve["load_scale"]

    # 1) ΣFx = 0 / 2) ΣFy = 0 / 3) ΣM(左端) = 0
    act_fx = sum(r["Rx"] for r in reactions) + sums["Fx_l"]
    act_fy = sum(r["Ry"] for r in reactions) + sums["Fy_l"]
    act_m0 = sum(r["Mz"] + r["x"] * r["Ry"] for r in reactions) + sums["M0_l"]
    sc_f = max(load_scale, abs(sums["Fx_l"]), 1e-12)
    sc_m = max(load_scale * max(L, 1e-9), abs(sums["M0_l"]), 1e-12)
    for name, act, sc in (("ΣFx=0", act_fx, sc_f), ("ΣFy=0", act_fy, sc_f),
                          ("ΣM(对左端)=0", act_m0, sc_m)):
        e = _rerr(act, 0.0, sc)
        checks.append({"name": name, "expected": 0.0, "actual": act, "rel_error": e,
                       "pass": e < 1e-8, "note": ""})

    if solve.get("torsion_active"):
        act_mx = sum(r.get("Mx", 0.0) for r in reactions) + sums["Mx_l"]
        e = _rerr(act_mx, 0.0, max(load_scale, abs(sums["Mx_l"]), 1e-12))
        checks.append({"name": "ΣMx=0(扭转)", "expected": 0.0, "actual": act_mx,
                       "rel_error": e, "pass": e < 1e-8, "note": "轴类模型"})

    # 4) dM/dx = Q（数值差分抽查）
    xs = critical_xs(model)
    samples = []
    for i in range(len(xs) - 1):
        c0, c1 = xs[i], xs[i + 1]
        if c1 - c0 <= 1e-9 * max(L, 1.0):
            continue
        samples.append(0.5 * (c0 + c1))
    if not samples:
        samples = [0.5 * L]
    qscale = max([abs(p["value"]) for p in diagrams["Q"]] + [1e-12])
    h = max(1e-6 * L, 1e-12 * L)
    worst = 0.0
    worst_x = samples[0]
    worst_num = 0.0
    worst_q = 0.0
    for x in samples:
        M1 = internal_at(x + h, False, evs, model["dists"], tol)[2]
        M2 = internal_at(x - h, False, evs, model["dists"], tol)[2]
        num = (M1 - M2) / (2.0 * h)
        qx = internal_at(x, False, evs, model["dists"], tol)[1]
        e = _rerr(num, qx, qscale)
        if e > worst:
            worst, worst_x, worst_num, worst_q = e, x, num, qx
    checks.append({"name": "dM/dx=Q（数值差分抽查 %d 点）" % len(samples),
                   "expected": worst_q, "actual": worst_num,
                   "rel_error": worst, "pass": worst < 1e-5,
                   "note": "最差样本 x=%g（中心差分 h=%g）" % (worst_x, h)})

    # 5) 简支端 M=0（铰/辊轴支座处）
    mscale = max([abs(p["value"]) for p in diagrams["M"]] + [1e-12])
    hinge_xs = sorted({s["x"] for s in model["supports"] if s["type"] in ("pin", "roller")})
    if hinge_xs:
        worst = 0.0
        worst_x = hinge_xs[0]
        for hx in hinge_xs:
            inc = hx <= tol            # 左端取 x+ 侧，其余取 x- 侧（支座是集中力，M 连续）
            Mv = internal_at(hx, inc, evs, model["dists"], tol)[2]
            e = abs(Mv) / max(mscale, 1e-12)
            if e > worst:
                worst, worst_x = e, hx
        checks.append({"name": "简支端 M=0",
                       "expected": 0.0,
                       "actual": internal_at(worst_x, worst_x <= tol, evs, model["dists"], tol)[2],
                       "rel_error": worst, "pass": worst < 1e-8,
                       "note": "铰/辊轴支座 x=%s" % ", ".join("%g" % v for v in hinge_xs)})

    # 6) 截面应力分布核对 ∫σdA=N 与 ∫yσdA=M
    A, Iy = geo["A"], geo["Iy"]
    integ = geo["integrate"]
    A_n, ydA, y2dA = section_integrals(geo)

    mx = (extrema.get("M") or {}).get("abs_max_x", 0.0)
    Nv, _Qv, Mv, _Tv = internal_at(mx, abs(mx - L) > tol, evs, model["dists"], tol)
    int_sigma = integ(lambda y: Nv / A + Mv * y / Iy)
    int_ysigma = integ(lambda y: y * (Nv / A + Mv * y / Iy))
    # 相对误差的分母取“量级最大的一项”，避免 N 或 M 为 0 时被噪声放大
    sc_n = max(abs(Nv), abs(Mv) * geo["ymax"] * A_n / Iy, 1e-12)
    sc_m = max(abs(Mv), abs(Nv) * geo["ymax"], 1e-12)
    e1 = _rerr(int_sigma, Nv, sc_n)
    e2 = _rerr(int_ysigma, Mv, sc_m)
    checks.append({"name": "∫σdA=N（线性正应力 σ=N/A+M·y/Iy）",
                   "expected": Nv, "actual": int_sigma, "rel_error": e1,
                   "pass": e1 < 1e-8, "note": "截面 x=%g（数值条带积分）" % mx})
    checks.append({"name": "∫yσdA=M（同上）",
                   "expected": Mv, "actual": int_ysigma, "rel_error": e2,
                   "pass": e2 < 1e-8, "note": "截面 x=%g（数值条带积分）" % mx})

    # 7) 力平衡残差（求解质量）
    checks.append({"name": "平衡方程求解残差 ||A·r-b||/||b||",
                   "expected": 0.0, "actual": solve.get("resid_rel", 0.0),
                   "rel_error": solve.get("resid_rel", 0.0),
                   "pass": solve.get("resid_rel", 0.0) < 1e-9,
                   "note": "numpy lstsq，未知量 %d / 方程 %d" % (solve["total_unknowns"], solve["n_eq"])})
    return checks


# ---------------------------------------------------------------------
# warnings
# ---------------------------------------------------------------------

_YIELD = {"Q235": 235.0, "Q355": 355.0, "Q345": 345.0, "Q390": 390.0, "Q420": 420.0}


def build_warnings(model, geo, stresses, units, extra):
    warns = list(extra)
    warns.extend(units["warnings"])
    # 本工具**不做任何变形计算**（挠度/转角/变形协调一律没有），E 只被记录、不参与运算。
    # 原先只在"未给 E"时警告"挠度无法计算"，读起来像"给了 E 就能算挠度"——
    # 那是误导（给了 E 也只是少一条警告，什么都没算）。所以两种情形都要说清。
    if geo.get("E") is None:
        warns.append(
            "本工具不计算变形（挠度/转角/变形协调均无）：静定反力、内力与应力不需要 section.E。"
            "要挠度请用 solve_math 积分 EI·v''=M 并代入边界条件")
    else:
        warns.append(
            "已记录 section.E，但本工具不计算变形：它只出反力/内力/应力。"
            "挠度/转角请用 solve_math 积分 EI·v''=M 并代入边界条件")
    if geo["Ip"] is None:
        warns.append("非圆截面：Ip/Wp 不适用，已返回 null（扭转切应力需查表或数值方法）")
    if geo["shape"] == "tube":
        warns.append("管截面 τmax 采用 τ=Q·Sy/(Iy·t) 近似（未考虑壁厚剪切修正）")

    smax = stresses.get("sigma_max")
    if smax is not None:
        if 0.001 <= smax <= 1.0 or smax > 5000.0:
            warns.append("量级可疑：|σmax|=%.4g，请确认单位是否为 N·mm/MPa 一致体系" % smax)
        mat = (geo.get("material") or "").upper().replace(" ", "")
        for name, fy in _YIELD.items():
            if name in mat and smax > fy:
                warns.append("材料 %s 屈服强度约 %g MPa，|σmax|=%.3g 已超屈服，"
                             "弹性解不再适用（需弹塑性分析）" % (name, fy, smax))
                break
    return warns


# ---------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------

def analyze(spec):
    model = build_model(spec)
    geo = build_section(spec.get("section"))
    units = model["units"]

    warnings = []

    # 输出 channels：按 kind 的主通道 + 任何非零通道
    solve = solve_reactions(model, geo)

    conventions = {
        "N": "拉为正",
        "Q": "顺时针为正",
        "M": "下侧受拉为正",
        "T": "右手螺旋+x为正",
        "x": "自构件左端量起，单位同 units.length",
        "point_force": "沿 dir 的分量：transverse 向上为正，axial 沿 +x 为正",
        "distributed": "q 为集度(力/长度)，向上为正，沿 x 线性",
        "point_moment": "逆时针为正",
        "point_torque": "右手螺旋沿 +x 为正",
        "support": "Rx 沿+x，Ry 向上，Mz 逆时针，Mx 右手螺旋+x",
        "sigma": "σ=N/A+M·y/Iy，y 自中性轴向上为正，拉应力为正",
        "M_definition": "M=Σ_{a<x}[(x-a)·Fy-Mz]，满足 dM/dx=Q",
    }

    sec_out = {
        "shape": geo["shape"], "params": geo["params"],
        "A": geo["A"], "Iy": geo["Iy"], "W": geo["W"], "Sy": geo["Sy"],
        "Ip": geo["Ip"], "Wp": geo["Wp"],
        "ymax": geo["ymax"], "tau_max_coeff": geo["tau_max_coeff"],
        "E": geo["E"], "material": geo["material"],
        "units": units["declared"],
    }

    if not solve["determinate"]:
        checks = geometric_checks(geo)
        n_u, n_e = solve["total_unknowns"], solve["n_eq"]
        checks.append({
            "name": "静定判定（未知反力数 ≤ 平衡方程数）",
            "expected": float(n_e), "actual": float(n_u),
            "rel_error": (n_u - n_e) / float(n_e), "pass": False,
            "note": "未知反力 %d > 方程 %d，Phase 1 不支持静不定" % (n_u, n_e),
        })
        warnings.append("静不定：未求解反力与内力，仅给出截面几何与可核验的几何 checks")
        if geo["Ip"] is None:
            warnings.append("非圆截面：Ip/Wp 不适用，已返回 null（扭转切应力需查表或数值方法）")
        return {
            "ok": True,
            "kind": model["kind"],
            "determinate": False,
            "unsupported": solve["unsupported"],
            "conventions": conventions,
            "units": units["declared"],
            "reactions": [],
            "section": sec_out,
            "diagrams": {},
            "extrema": {},
            "stresses": {},
            "checks": checks,
            "warnings": warnings,
        }

    evs = make_event_list(model, solve["reactions"])
    diagrams_all = build_diagrams(model, evs)

    primary = {"bar": ["N"], "beam": ["N", "Q", "M"], "shaft": ["T"]}[model["kind"]]
    scale = max([abs(p["value"]) for ch in diagrams_all for p in diagrams_all[ch]] + [1e-12])
    channels = list(primary)
    for ch in ("N", "Q", "M", "T"):
        if ch not in channels:
            if any(abs(p["value"]) > 1e-12 * scale for p in diagrams_all[ch]):
                channels.append(ch)
    channels = [c for c in ("N", "Q", "M", "T") if c in channels]

    diagrams = {ch: diagrams_all[ch] for ch in channels}

    extrema_all = compute_extrema(model, evs, diagrams_all)
    qzero = extrema_all.pop("_q_zero_x", [])
    extrema = {ch: extrema_all[ch] for ch in channels if ch in extrema_all}

    stresses = compute_stresses(geo, diagrams_all, extrema_all, units)
    checks = build_checks(model, geo, solve, evs, diagrams_all, extrema_all)

    extra_warn = []
    if qzero:
        extra_warn.append("M 极值已按 Q 变号位置（Q=0）求解：x=%s"
                          % ", ".join("%.6g" % v for v in qzero[:8]))
    warnings = build_warnings(model, geo, stresses, units, warnings + extra_warn)

    return {
        "ok": True,
        "kind": model["kind"],
        "determinate": True,
        "conventions": conventions,
        "units": units["declared"],
        "reactions": solve["reactions"],
        "section": sec_out,
        "diagrams": diagrams,
        "extrema": extrema,
        "stresses": stresses,
        "checks": checks,
        "warnings": warnings,
    }


# ---------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------

def parse_args(argv):
    spec_file = None
    spec_json = None
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--spec":
            if i + 1 >= len(argv):
                raise SpecError("--spec 缺少文件路径")
            spec_file = argv[i + 1]
            i += 2
        elif a == "--spec-json":
            if i + 1 >= len(argv):
                raise SpecError("--spec-json 缺少 JSON 参数")
            spec_json = argv[i + 1]
            i += 2
        elif a in ("-h", "--help"):
            raise SpecError("用法：analyze.py --spec <spec.json> | --spec-json '<json>'")
        else:
            raise SpecError("未知命令行参数 %r" % a,
                            "用法：analyze.py --spec <spec.json> | --spec-json '<json>'")
    if spec_file is None and spec_json is None:
        raise SpecError("必须提供 --spec 或 --spec-json")
    if spec_file is not None and spec_json is not None:
        raise SpecError("--spec 与 --spec-json 只能二选一")
    return spec_file, spec_json


def load_spec(spec_file, spec_json):
    if spec_json is not None:
        txt = spec_json
    else:
        if not os.path.isfile(spec_file):
            raise SpecError("找不到 spec 文件：%s" % spec_file)
        try:
            with open(spec_file, "r", encoding="utf-8-sig") as fh:
                txt = fh.read()
        except OSError as exc:
            raise SpecError("无法读取 spec 文件：%s" % spec_file, str(exc))
    try:
        return json.loads(txt)
    except Exception as exc:
        raise SpecError("spec JSON 解析失败", str(exc))


def main(argv):
    try:
        spec_file, spec_json = parse_args(argv)
        spec = load_spec(spec_file, spec_json)
        result = analyze(spec)
        sys.stdout.write(json.dumps(_clean_obj(result), ensure_ascii=False) + "\n")
        return 0
    except SpecError as exc:
        out = {"ok": False, "error": exc.msg}
        if exc.detail:
            out["detail"] = exc.detail
        sys.stdout.write(json.dumps(_clean_obj(out), ensure_ascii=False) + "\n")
        return 1
    except Exception as exc:  # 兜底：绝不泄漏 traceback 到 stdout
        import traceback
        sys.stderr.write(traceback.format_exc())
        out = {"ok": False, "error": "内部错误：%s" % type(exc).__name__, "detail": str(exc)}
        sys.stdout.write(json.dumps(_clean_obj(out), ensure_ascii=False) + "\n")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))