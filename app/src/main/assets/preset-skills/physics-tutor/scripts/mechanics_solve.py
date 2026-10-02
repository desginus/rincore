#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""力学符号求解器 —— 习题辅导预设能力 (physics-tutor)

设计原则：把"模型口算"换成「模型写方程 → 工具求解 → 工具自校验」。
所有解都给出：符号解(LaTeX) + 数值代入 + 回代校验（残差必须为 0）。

用法:
  python3 mechanics_solve.py --selftest
  python3 mechanics_solve.py problem.json
  python3 mechanics_solve.py --json '{"equations":["m*g*sin(theta)-mu*m*g*cos(theta)-m*a=0"],
                                     "unknowns":["a"],
                                     "values":{"m":2.0,"theta":"pi/6","mu":0.2,"g":9.8}}'

problem.json 结构:
  {
    "equations": ["...=0", ...],     # 必填；等号两侧均可，缺省右侧为 0
    "unknowns":  ["a", "T"],         # 必填；要解的符号
    "values":    {"m": 2.0, "theta": "pi/6", "g": 9.8},   # 选填；数值代入
    "positive":  ["m", "g"]          # 选填；声明为正实数的符号（默认从方程自动推断）
  }

退出码: 0=成功, 1=求解失败, 2=用法/环境错误
"""
import argparse
import json
import sys


def _die(msg: str, code: int = 2):
    print(f"[mechanics_solve] {msg}", file=sys.stderr)
    sys.exit(code)


def _parse_eq(sympy, text: str):
    if "=" in text:
        lhs, rhs = text.split("=", 1)
        return sympy.Eq(sympy.sympify(lhs), sympy.sympify(rhs))
    return sympy.Eq(sympy.sympify(text), 0)


def solve(equations, unknowns, values, positive=None):
    import sympy as sp

    eqs = [_parse_eq(sp, e) for e in equations]
    unknown_syms = [sp.Symbol(u) for u in unknowns]
    free = set()
    for eq in eqs:
        free |= {s for s in eq.free_symbols if not s.is_number}
    value_syms = set()
    for k, v in (values or {}).items():
        value_syms.add(sp.Symbol(k))
        _ = v
    all_syms = free | set(unknown_syms) | value_syms

    pos = set(positive or [])
    pos |= {s.name for s in unknown_syms if s.name in ("m", "M", "g", "k", "R", "L")}
    subs_pos = {s: sp.Symbol(s.name, positive=True) for s in all_syms if s.name in pos}
    if subs_pos:
        eqs = [eq.subs(subs_pos, simultaneous=True) for eq in eqs]
        unknown_syms = [subs_pos.get(u, u) for u in unknown_syms]

    sol = sp.solve(eqs, unknown_syms, dict=True)
    if not sol:
        return None, None, "求解失败：方程无解或信息不足（检查方程数是否 ≥ 未知量数）"
    sol0 = sol[0]

    out_lines = ["## 符号解"]
    for u in unknown_syms:
        if u in sol0:
            out_lines.append(f"- {sp.sstr(u)} = {sp.sstr(sp.simplify(sol0[u]))}")
            out_lines.append(f"  · LaTeX: `{sp.latex(sp.simplify(sol0[u]))}`")

    # 回代校验（残差必须为 0）
    residual_report = []
    ok = True
    for idx, eq in enumerate(eqs, 1):
        r = sp.simplify(eq.lhs.subs(sol0) - eq.rhs.subs(sol0))
        residual_report.append(f"  方程{idx} 残差 = {sp.sstr(r)}")
        if r != 0:
            ok = False
    out_lines.append("## 回代校验")
    out_lines += residual_report
    out_lines.append(f"结论: {'全部为零 ✓' if ok else '存在非零残差 ✗（请复核方程）'}")

    if values:
        try:
            vsubs = {sp.Symbol(k): sp.sympify(v) for k, v in values.items()}
            vsubs = {subs_pos.get(k, k): v for k, v in vsubs.items()}
            out_lines.append("## 数值代入")
            for u in unknown_syms:
                if u in sol0:
                    num = sp.N(sol0[u].subs(vsubs))
                    out_lines.append(f"- {sp.sstr(u)} = {sp.sstr(sp.nsimplify(num))} ≈ {float(num):.6g}")
        except Exception as e:  # 数值代入失败不影响符号解
            out_lines.append(f"（数值代入失败：{e}）")

    return sol0, out_lines, None


def _numeric(expr, mapping):
    """按符号名代入数值（避免 positive=True 造成的符号对象不一致）。"""
    import sympy as sp
    subs = {s: sp.sympify(mapping[s.name]) for s in expr.free_symbols if s.name in mapping}
    return float(sp.N(expr.subs(subs)))


def selftest() -> int:
    print("== mechanics_solve 自检 ==")
    try:
        import sympy  # noqa: F401
    except ImportError:
        print("缺少依赖 sympy —— 请先运行: pip3 install --break-system-packages sympy")
        return 2

    ok = True
    sol, _, err = solve(
        ["m*g*sin(theta)-mu*m*g*cos(theta)-m*a=0"], ["a"], None
    )
    if err or sol is None:
        ok = False
        print("斜面用例失败:", err)
    else:
        key = [s for s in sol if s.name == "a"][0]
        val = _numeric(sol[key], {"g": 9.8, "theta": "pi/6", "mu": 0.2})
        good = abs(val - 3.20259) < 1e-4
        print(f"斜面用例: a = {val:.5f} m/s²", "✓" if good else "✗ 期望 ≈3.20259")
        ok = ok and good

    sol2, _, err2 = solve(
        ["m1*g-T=m1*a", "T-m2*g=m2*a"], ["a", "T"],
        {"m1": 3, "m2": 1, "g": 9.8},
    )
    if err2 or sol2 is None:
        ok = False
        print("阿特伍德用例失败:", err2)
    else:
        ka = [s for s in sol2 if s.name == "a"][0]
        v = _numeric(sol2[ka], {"m1": 3, "m2": 1, "g": 9.8})
        good = abs(v - 4.9) < 1e-6
        print(f"阿特伍德用例: a = {v:.5f} m/s²", "✓" if good else "✗ 期望 4.9")
        ok = ok and good
    print("== 自检结果:", "通过" if ok else "失败", "==")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="力学符号求解器 (physics-tutor)")
    ap.add_argument("problem", nargs="?", help="problem.json 路径")
    ap.add_argument("--json", help="直接传 JSON 字符串")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.json:
        payload = json.loads(args.json)
    elif args.problem:
        with open(args.problem, "r", encoding="utf-8") as f:
            payload = json.load(f)
    else:
        ap.print_help()
        return 2

    equations = payload.get("equations") or []
    unknowns = payload.get("unknowns") or []
    if not equations or not unknowns:
        _die("必须提供 equations 与 unknowns")

    _, lines, err = solve(equations, unknowns, payload.get("values"), payload.get("positive"))
    if err:
        print(err)
        return 1
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
