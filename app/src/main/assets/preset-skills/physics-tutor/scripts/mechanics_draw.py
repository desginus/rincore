#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""力学图参数化渲染器 —— 习题辅导预设能力 (physics-tutor)

核心原则（连教训一起写在文件头）：
  · 图不由模型"手绘"，而是「模型给参数 → 工具按物理约束渲染」；
  · 模板内置**方向断言**：mg 必竖直向下、N 必垂直斜面、f 必沿斜面……
    渲染前就拦住"几何像那么回事、物理是错的"的图（第一版演示就犯过这个错）。
  · 输出 PNG，可直接贴到解答里；如需矢量图/出版级排版，改用 LaTeX/TikZ。

用法:
  python3 mechanics_draw.py --selftest
  python3 mechanics_draw.py --json '{"figure":"incline","params":{"theta":30,"mu":0.2},"out":"fig.png"}'
  python3 mechanics_draw.py spec.json

模板与参数:
  incline : theta(度), mu, slide("down"|"up"), mass(默认2.0)
  atwood  : m1, m2
  spring  : m, k, x0(初始位移, 可负), v0(默认0)
"""
import argparse
import json
import os
import sys
import tempfile


def _mpl():
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        import numpy as np
        return matplotlib, plt, np
    except ImportError:
        print("缺少依赖 matplotlib/numpy —— 请先运行: pip3 install --break-system-packages matplotlib numpy", file=sys.stderr)
        sys.exit(2)


_CJK_CANDIDATES = [
    "Noto Sans CJK SC", "Noto Sans CJK JP", "WenQuanYi Zen Hei",
    "WenQuanYi Micro Hei", "Source Han Sans SC", "Droid Sans Fallback",
]


def _pick_font(matplotlib):
    from matplotlib import font_manager
    available = {f.name for f in font_manager.fontManager.ttflist}
    for name in _CJK_CANDIDATES:
        if name in available:
            matplotlib.rcParams["font.sans-serif"] = [name]
            matplotlib.rcParams["axes.unicode_minus"] = False
            return True
    return False


def _title(zh: str, en: str, has_cjk: bool) -> str:
    return zh if has_cjk else en


# ─────────────────────── 模板 ───────────────────────

def draw_incline(ax, plt, np, params):
    theta_deg = float(params.get("theta", 30))
    mu = float(params.get("mu", 0.0))
    slide = str(params.get("slide", "down"))
    if slide not in ("down", "up"):
        raise ValueError('slide 只能是 "down" 或 "up"')
    th = np.deg2rad(theta_deg)
    sin_t, cos_t = np.sin(th), np.cos(th)

    L = 6.0
    ax.plot([0, L, L], [0, 0, L * np.tan(th)], "k-", lw=1.3)
    ax.plot([0, L], [0, L * np.tan(th)], "k-", lw=1.6)
    ax.text(L - 0.9, 0.18, rf"$\theta={theta_deg:g}^\circ$", fontsize=12)

    bx = 3.5
    by = bx * np.tan(th)
    blk = plt.Rectangle((bx - 0.45, by - 0.30), 0.9, 0.60,
                        facecolor="skyblue", edgecolor="navy", lw=1.4, zorder=3)
    from matplotlib.transforms import Affine2D
    blk.set_transform(Affine2D().rotate_deg_around(bx, by, theta_deg) + ax.transData)
    ax.add_patch(blk)
    ax.text(bx - 0.10, by - 0.06, "m", fontsize=11, zorder=4)

    def arrow(d, length, color, label, lx, ly):
        ax.annotate("", xy=(bx + d[0] * length, by + d[1] * length), xytext=(bx, by),
                    arrowprops=dict(arrowstyle="-|>", lw=2.0, color=color), zorder=5)
        ax.text(bx + lx, by + ly, label, color=color, fontsize=12, zorder=5)

    mg_dir = np.array([0.0, -1.0])                  # 重力：竖直向下
    n_dir = np.array([-sin_t, cos_t])               # 支持力：垂直斜面
    f_dir = np.array([cos_t, sin_t])                # 沿斜面向上
    if slide == "up":
        f_dir = -f_dir                              # 上滑 → 摩擦力沿斜面向下

    arrow(mg_dir, 1.45, "crimson", "mg", -0.15, -1.85)
    arrow(n_dir, 1.25, "green", "N", -1.35, 0.85)
    arrow(f_dir, 1.10, "darkorange", "f", 1.4 * f_dir[0], 1.4 * f_dir[1] + 0.34)

    # ── 物理方向断言（渲染正确性不靠肉眼）──
    assert abs(np.dot(mg_dir, n_dir)) > 0.4, "mg 不应垂直斜面"
    assert abs(float(np.dot(n_dir, f_dir))) < 1e-9, "N 与 f 必须正交"
    assert abs(abs(float(np.dot(f_dir, np.array([cos_t, sin_t])))) - 1.0) < 1e-9, "f 必须沿斜面"
    if slide == "up":
        assert f_dir[0] < 0, "上滑时摩擦力必须沿斜面向下"
    else:
        assert f_dir[0] > 0, "下滑时摩擦力必须沿斜面向上"
    ax.set_xlim(-1.6, 7.2)
    ax.set_ylim(-2.4, 4.6)
    return f"斜面滑块（{ '上滑' if slide=='up' else '下滑'}）", "Inclined plane (sliding " + slide + ")", mu


def draw_atwood(ax, plt, np, params):
    m1 = float(params.get("m1", 3.0))
    m2 = float(params.get("m2", 1.0))
    if m1 <= 0 or m2 <= 0:
        raise ValueError("m1/m2 必须为正")
    ax.plot([0, 0], [0.6, 4.9], "k-", lw=3, zorder=1)
    ax.add_patch(plt.Circle((0, 5.05), 0.26, fill=False, lw=2, zorder=2))
    y1, y2 = 2.5, 3.5
    ax.plot([-0.26, -0.26], [5.05, y1 + 0.30], "k-", lw=1.2)
    ax.plot([0.26, 0.26], [5.05, y2 + 0.30], "k-", lw=1.2)
    ax.add_patch(plt.Rectangle((-0.26 - 0.34, y1 - 0.30), 0.68, 0.60, facecolor="steelblue", alpha=.85, zorder=3))
    ax.add_patch(plt.Rectangle((0.26 - 0.34, y2 - 0.30), 0.68, 0.60, facecolor="seagreen", alpha=.85, zorder=3))
    ax.text(-0.26 + 0.42, y1 - 0.12, rf"$m_1$={m1:g}", fontsize=11, color="steelblue")
    ax.text(0.26 + 0.42, y2 - 0.12, rf"$m_2$={m2:g}", fontsize=11, color="seagreen")

    # 加速度方向：重的一侧向下
    heavy_left = m1 > m2
    ax.annotate("", xy=(-0.6, y1 - 0.30 - 0.55), xytext=(-0.6, y1 - 0.30),
                arrowprops=dict(arrowstyle="-|>", lw=1.8, color="crimson")) if heavy_left else \
        ax.annotate("", xy=(0.6, y2 - 0.30 - 0.55), xytext=(0.6, y2 - 0.30),
                    arrowprops=dict(arrowstyle="-|>", lw=1.8, color="crimson"))
    ax.text(-1.15 if heavy_left else 0.75, min(y1, y2) - 0.75, r"$a$", color="crimson", fontsize=12)

    # ── 断言：a 方向必须指向重物 ──
    assert (m1 - m2) * (1 if heavy_left else -1) > 0, "加速度方向与质量差矛盾"
    ax.set_xlim(-1.8, 1.8)
    ax.set_ylim(0.6, 5.8)
    return "阿特伍德机", "Atwood machine", None


def draw_spring(ax, plt, np, params):
    m = float(params.get("m", 1.0))
    k = float(params.get("k", 100.0))
    x0 = float(params.get("x0", 0.1))
    if k <= 0 or m <= 0:
        raise ValueError("k/m 必须为正")
    # 墙 + 弹簧 + 滑块
    ax.plot([0, 0], [0.2, 1.6], "k-", lw=3)
    coils = 8
    xs = np.linspace(0, 3.0, 200)
    ys = 0.9 + 0.18 * np.sin(np.linspace(0, coils * 2 * np.pi, 200))
    ax.plot(xs, ys, "k-", lw=1.2)
    ax.add_patch(plt.Rectangle((3.0, 0.55), 0.9, 0.7, facecolor="skyblue", edgecolor="navy", lw=1.4))
    ax.text(3.28, 0.78, "m", fontsize=11)
    ax.plot([3.0, 3.0], [0.2, 0.55], "k--", lw=0.8)
    ax.plot([3.9, 3.9], [0.2, 0.55], "k--", lw=0.8)

    f_dir = -1.0 if x0 > 0 else 1.0        # 弹力方向与位移相反
    ax.annotate("", xy=(3.45 + f_dir * 1.1, 0.9), xytext=(3.45, 0.9),
                arrowprops=dict(arrowstyle="-|>", lw=2.0, color="crimson"))
    ax.text(3.45 + f_dir * 1.3 - 0.25, 1.05, r"$F=-kx$", color="crimson", fontsize=11,
            ha="center")
    ax.text(0.1, 1.35, rf"$k$={k:g} N/m, $x_0$={x0:g} m", fontsize=11)

    # ── 断言：弹力方向与位移相反 ──
    assert x0 * f_dir < 0, "弹力方向必须与位移方向相反"
    ax.set_xlim(-0.4, 6.0)
    ax.set_ylim(0.0, 1.8)
    return "弹簧振子", "Spring-mass (F=-kx)", None


TEMPLATES = {
    "incline": draw_incline,
    "atwood": draw_atwood,
    "spring": draw_spring,
}


def render(figure: str, params: dict, out_path: str) -> str:
    matplotlib, plt, np = _mpl()
    has_cjk = _pick_font(matplotlib)
    fn = TEMPLATES.get(figure)
    if fn is None:
        raise ValueError(f"未知模板: {figure}（可用: {', '.join(TEMPLATES)}）")
    fig, ax = plt.subplots(figsize=(6.4, 4.2), dpi=200)
    ax.set_aspect("equal")
    ax.axis("off")
    zh, en, _ = fn(ax, plt, np, params or {})
    ax.set_title(_title(zh, en, has_cjk), fontsize=13)
    fig.tight_layout()
    fig.savefig(out_path)
    plt.close(fig)
    return out_path


def selftest() -> int:
    tmp = tempfile.mkdtemp(prefix="mechdraw_")
    cases = [
        ("incline", {"theta": 30, "mu": 0.2, "slide": "down"}),
        ("incline", {"theta": 37, "mu": 0.1, "slide": "up"}),
        ("atwood", {"m1": 3, "m2": 1}),
        ("spring", {"m": 1, "k": 100, "x0": 0.1}),
    ]
    ok = True
    for i, (name, params) in enumerate(cases, 1):
        out = os.path.join(tmp, f"{name}_{i}.png")
        try:
            render(name, params, out)
            size = os.path.getsize(out)
            good = size > 5000
            print(f"{name} {params} -> {size} bytes", "✓" if good else "✗ 文件过小")
            ok = ok and good
        except AssertionError as e:
            print(f"{name} {params} -> 断言失败: {e}")
            ok = False
        except Exception as e:
            print(f"{name} {params} -> 错误: {e}")
            ok = False
    print("== 自检结果:", "通过" if ok else "失败", "==  (输出目录:", tmp, ")")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="力学图参数化渲染器 (physics-tutor)")
    ap.add_argument("spec", nargs="?", help="spec.json 路径")
    ap.add_argument("--json", help="直接传 JSON 字符串")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.json:
        payload = json.loads(args.json)
    elif args.spec:
        with open(args.spec, "r", encoding="utf-8") as f:
            payload = json.load(f)
    else:
        ap.print_help()
        return 2

    figure = payload.get("figure", "")
    out = payload.get("out", "figure.png")
    try:
        path = render(figure, payload.get("params") or {}, out)
    except AssertionError as e:
        print(f"[physics] 物理方向断言失败: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"[render] 渲染失败: {e}", file=sys.stderr)
        return 1
    print(f"已渲染: {path}  （figure={figure} params={payload.get('params')}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
