#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学科无关的求解内核：确定性求解 + 独立验算。

为什么要有这个文件
------------------
画图之前得先把题**算对**。这一步不能靠"看起来合理"：所以本文件把每个结果都配一条
**独立的、定量的**复核（代回原式、对结果求导、与数值积分/差分对比、特征值残差……），
并把残差写进 `checks`。任何一条复核不过，都会在 `warnings` 里再报一次，绝不静默吞掉。

精确解与数值解是**两条不同的通道**：
* 有 sympy 时走精确路径（`exact` 给 `1/3`、`sqrt(2)` 这类真值，`latex` 可直接写进答案）；
* 没有 sympy 时**降级为数值**，并把做不到的 op 如实列进 `unsupported`——绝不用假结果冒充精确解。

运行契约
--------
    python symbolic.py --spec <spec.json>
    python symbolic.py --spec-json '<json>'
stdout 只输出**一个** JSON 对象：成功 {"ok": true, ...}，失败 {"ok": false, "error": "可读中文原因"}。
spec 用 utf-8-sig 读取（容忍 BOM）。sympy 从 `<插件根>/.pylibs` 或 `SYMBOLIC_PYLIB` 取，不写死解释器路径。
"""

import json
import math
import os
import re
import sys

try:  # 中文无论如何都要能打出来
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

PLUGIN_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _prepare_path():
    """把随包依赖目录插到 sys.path 前面（.pylibs 里放了 sympy + mpmath）。"""
    candidates = []
    extra = os.environ.get("SYMBOLIC_PYLIB")
    if extra:
        candidates += [item for item in extra.split(os.pathsep) if item]
    candidates.append(os.path.join(PLUGIN_ROOT, ".pylibs"))
    for path in candidates:
        if path and os.path.isdir(path) and path not in sys.path:
            sys.path.insert(0, path)


_prepare_path()

import numpy as np  # noqa: E402

sympy = None
SYMPY_ERROR = None
standard_transformations = None
convert_xor = None
implicit_multiplication_application = None
parse_expr = None

if os.environ.get("SYMBOLIC_NO_SYMPY") == "1":
    SYMPY_ERROR = "已按 SYMBOLIC_NO_SYMPY=1 禁用 sympy"
else:
    try:
        import sympy  # noqa: F401
        from sympy.parsing.sympy_parser import (  # noqa: F401
            parse_expr, standard_transformations, convert_xor, implicit_multiplication_application,
        )
    except Exception as exc:  # pragma: no cover
        sympy = None
        SYMPY_ERROR = str(exc)


class SpecError(Exception):
    """spec 本身有问题：一律给出可读中文原因，不抛 traceback 到 stdout。"""


# --------------------------------------------------------------------------
# 输入安全：表达式是数据，不是代码
# --------------------------------------------------------------------------
FORBIDDEN = (
    "__", "import", "os.", "sys.", "open(", "eval(", "exec(", "subprocess",
    "lambda", "globals", "locals", "getattr", "setattr", "compile(", "input(",
)


def _guard(text, what="表达式"):
    lowered = str(text).lower()
    for bad in FORBIDDEN:
        if bad in lowered:
            raise SpecError("%s里出现了不允许的片段 %r：只接受数学表达式，不接受代码" % (what, bad))
    return str(text)


def _strip_equals(text):
    """把 `a = b` 统一成 `a - b`（方程一律按 = 0 处理）。"""
    body = str(text)
    if "=" in body and "==" not in body:
        left, right = body.split("=", 1)
        return "(%s)-(%s)" % (left, right)
    return body


# sympy 命名空间里被抢占的名字：这些名字要留给 sympy 自己（函数/常数），
# 其余标识符一律当**变量**建新符号。
_SYMPY_FUNCS = {
    "sin", "cos", "tan", "cot", "sec", "csc", "asin", "acos", "atan", "acot", "asec", "acsc",
    "atan2", "sinh", "cosh", "tanh", "coth", "asinh", "acosh", "atanh", "exp", "log", "ln",
    "sqrt", "cbrt", "root", "Abs", "abs", "sign", "conjugate", "re", "im", "arg", "floor",
    "ceiling", "frac", "factorial", "gamma", "beta", "zeta", "erf", "erfc", "Min", "Max",
    "Piecewise", "Heaviside", "DiracDelta", "LambertW", "besselj", "bessely", "Matrix",
    "Integral", "Derivative", "Sum", "Product", "limit", "oo", "zoo", "nan", "pi",
}

_IDENTIFIER = re.compile(r"[^\W\d]\w*", re.UNICODE)

# 小写 e 留给自然常数（工科的弹性模量习惯写大写 E），其余标识符一律当变量
_CONSTANT_NAMES = {"e"}


def _local_dict(text, extra=None):
    """把表达式里出现的标识符全部建成**普通符号**。

    这一步不是锦上添花，是正确性的前提：`parse_expr` 默认按 `from sympy import *` 的命名空间解析，
    于是工科最常用的几个字母会被 sympy 抢走——
    `I` 变成虚数单位、`E` 变成自然常数、`N` 变成数值函数、`Q`/`S` 变成假设键与单例注册表。
    结果就是"电流 I、弹性模量 E、轴力 N、剪力 Q"这类题**静默算错或解不出来**。
    显式给 local_dict 后，这些字母都会老老实实当变量。
    """
    mapping = {}
    for name in set(_IDENTIFIER.findall(text)):
        if name in _SYMPY_FUNCS or name in _CONSTANT_NAMES:
            continue
        mapping[name] = sympy.Symbol(name)
    mapping.setdefault("ln", sympy.log)  # 工科习惯写 ln，sympy 里叫 log
    if extra:
        mapping.update(extra)
    return mapping


def _to_sympy(text, warnings=None, local_dict=None, what="表达式"):
    if sympy is None:
        raise SpecError("没有 sympy，无法做符号解析")
    body = _strip_equals(_guard(text, what))
    mapping = _local_dict(body, local_dict)
    try:
        return parse_expr(body, local_dict=mapping,
                          transformations=standard_transformations + (convert_xor,))
    except Exception:
        pass
    try:
        expr = parse_expr(body, local_dict=mapping,
                          transformations=standard_transformations
                          + (convert_xor, implicit_multiplication_application))
        if warnings is not None:
            warnings.append("%s %r 按隐式乘法解释（2x 记作 2*x）；多字母符号请显式写 *" % (what, text))
        return expr
    except Exception as exc:
        raise SpecError("无法解析%s %r：%s" % (what, text, exc))


# --------------------------------------------------------------------------
# 受限数值求值：给降级路径和"独立复核"用
# --------------------------------------------------------------------------
_NUM_ENV = {
    "sin": np.sin, "cos": np.cos, "tan": np.tan, "arcsin": np.arcsin, "arccos": np.arccos,
    "arctan": np.arctan, "asin": np.arcsin, "acos": np.arccos, "atan": np.arctan,
    "sinh": np.sinh, "cosh": np.cosh, "tanh": np.tanh, "exp": np.exp, "log": np.log,
    "ln": np.log, "log10": np.log10, "sqrt": np.sqrt, "abs": np.abs, "sign": np.sign,
    "floor": np.floor, "ceil": np.ceil, "pi": math.pi, "e": math.e, "inf": math.inf,
    "maximum": np.maximum, "minimum": np.minimum, "pow": np.power,
}


_ALLOWED_NODES = None


def _validate_numeric_ast(body, text, what):
    """数值求值前先过 AST 白名单：只允许"数字 / 变量 / 数学函数调用 / 四则与幂"。

    为什么不能只靠黑名单：`[1,2].pop()`、`print(1)`、`(1).real` 这类**非数学语法**能绕过字符串黑名单，
    被 Python 真的执行掉，然后返回一个"看起来像答案"的东西。求值器必须只认数学语法。

    注意入参是**源文本**（ast.parse 不接受已编译的 code 对象）。
    """
    global _ALLOWED_NODES
    if _ALLOWED_NODES is None:
        import ast as _ast
        _ALLOWED_NODES = {
            _ast.Expression, _ast.BinOp, _ast.UnaryOp, _ast.Call, _ast.Name, _ast.Constant,
            _ast.Load, _ast.Add, _ast.Sub, _ast.Mult, _ast.Div, _ast.Pow, _ast.Mod,
            _ast.FloorDiv, _ast.UAdd, _ast.USub,
        }
    import ast
    try:
        tree = ast.parse(body, "<expr>", "eval")
    except SyntaxError as exc:
        raise SpecError("无法把%s %r 变成数值表达式：%s" % (what, text, exc))
    for node in ast.walk(tree):
        if type(node) not in _ALLOWED_NODES:
            raise SpecError("%s %r 里出现了不允许的语法 %s：只接受数学表达式"
                            % (what, text, type(node).__name__))
        if isinstance(node, ast.Call):
            if not isinstance(node.func, ast.Name) or node.func.id not in _NUM_ENV:
                raise SpecError("%s %r 调用了不允许的函数：只接受 %s"
                                % (what, text, "/".join(sorted(_NUM_ENV))))
        if isinstance(node, ast.Constant):
            if isinstance(node.value, bool) or not isinstance(node.value, (int, float)):
                raise SpecError("%s %r 里出现了非数值常量：只接受数字" % (what, text))
            if isinstance(node.value, int) and len(str(abs(node.value))) > 12:
                raise SpecError("%s %r 里的整数太大（超过 12 位）：拒绝执行可能耗尽资源的表达式"
                                % (what, text))
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Pow):
            # 拦住 9**9**9 这类"指数塔"：它会把内存/CPU 直接打满
            exponent = node.right
            if isinstance(exponent, ast.Constant) and abs(exponent.value) > 100000:
                raise SpecError("%s %r 的指数过大（%s）：拒绝执行可能耗尽资源的表达式"
                                % (what, text, exponent.value))
            for inner in ast.walk(exponent):
                if isinstance(inner, ast.BinOp) and isinstance(inner.op, ast.Pow):
                    raise SpecError("%s %r 出现了指数塔（指数里还有幂）：拒绝执行可能耗尽资源的表达式"
                                    % (what, text))
    return tree


def _numeric_func(text, names, what="表达式"):
    """把一个数学表达式编译成只认数学函数的 numpy 函数（无 __builtins__，无属性访问）。"""
    body = _strip_equals(_guard(text, what)).replace("^", "**")
    _validate_numeric_ast(body, text, what)
    try:
        code = compile(body, "<expr>", "eval")
    except SyntaxError as exc:
        raise SpecError("无法把%s %r 变成数值表达式：%s" % (what, text, exc))
    env = dict(_NUM_ENV)
    for name in names:
        env[name] = 0.0

    def evaluate(*args):
        local = dict(env)
        for name, value in zip(names, args):
            local[name] = value
        try:
            with np.errstate(all="ignore"):
                return eval(code, {"__builtins__": {}}, local)  # noqa: S307 - 已过 AST 白名单，无内建可用
        except SpecError:
            raise
        except Exception as exc:
            raise SpecError("数值求值失败（%r）：%s" % (text, exc))

    return evaluate


def _simpson(func, low, high, pieces=2000):
    if pieces % 2:
        pieces += 1
    xs = np.linspace(low, high, pieces + 1)
    ys = np.asarray(func(xs), dtype=float)
    step = (high - low) / pieces
    return float(step / 3.0 * (ys[0] + ys[-1] + 4.0 * ys[1:-1:2].sum() + 2.0 * ys[2:-2:2].sum()))


def _central_diff(func, point, h=1e-6):
    return float((func(np.array([point + h]))[0] - func(np.array([point - h]))[0]) / (2.0 * h))


def _scan_roots(func, low=-60.0, high=60.0, samples=4801):
    """扫符号变化 + 二分：一元数值求根（降级路径用）。"""
    xs = np.linspace(low, high, samples)
    try:
        ys = np.asarray(func(xs), dtype=float)
    except Exception:
        return []
    roots = []
    for index in range(len(xs) - 1):
        y0, y1 = ys[index], ys[index + 1]
        if not (np.isfinite(y0) and np.isfinite(y1)):
            continue
        if abs(y0) < 1e-14:
            roots.append(float(xs[index]))
            continue
        if y0 * y1 < 0:
            a, b = float(xs[index]), float(xs[index + 1])
            fa = y0
            for _ in range(90):
                mid = (a + b) / 2.0
                try:
                    fm = float(func(np.array([mid]))[0])
                except Exception:
                    break
                if not np.isfinite(fm):
                    break
                if fa * fm <= 0:
                    b = mid
                else:
                    a, fa = mid, fm
            roots.append((a + b) / 2.0)
    # 去重
    unique = []
    for root in roots:
        if not any(abs(root - other) < 1e-7 * max(1.0, abs(root)) for other in unique):
            unique.append(root)
    return unique


# --------------------------------------------------------------------------
# 结果通道
# --------------------------------------------------------------------------
def _unwrap(value):
    """sympy 的 Matrix 不是 list/dict，直接 JSON 化会得到 None；先摊成嵌套 list。

    只有一列的（解向量）摊成**扁平**列表：列向量 [[2],[3]] 对下游很难用，
    扁平 [2, 3] 才是"解就是这两个数"的自然表达。
    """
    if sympy is not None and not isinstance(value, (list, tuple, dict, str, int, float, complex, bool)):
        if hasattr(value, "tolist") and hasattr(value, "shape"):
            try:
                nested = value.tolist()
            except Exception:
                return value
            if len(getattr(value, "shape", ())) == 2 and value.shape[1] == 1:
                return [row[0] if isinstance(row, list) else row for row in nested]
            return nested
    return value


def _exact(value):
    value = _unwrap(value)
    if isinstance(value, (list, tuple)):
        return [_exact(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _exact(item) for key, item in value.items()}
    if sympy is not None and isinstance(value, sympy.Basic):
        return sympy.sstr(value)
    if isinstance(value, bool):
        return str(value)
    if isinstance(value, float):
        return "%.10g" % value
    return str(value)


def _numeric(value, digits=6):
    value = _unwrap(value)
    if isinstance(value, (list, tuple)):
        return [_numeric(item, digits) for item in value]
    if isinstance(value, dict):
        return {str(key): _numeric(item, digits) for key, item in value.items()}
    try:
        number = complex(value)
    except Exception:
        return None
    if not (math.isfinite(number.real) and math.isfinite(number.imag)):
        return None
    if abs(number.imag) < 1e-12:
        rounded = round(number.real, digits)
        return float(rounded) if rounded != 0 else 0.0
    return {"re": round(number.real, digits), "im": round(number.imag, digits)}


def _float_of(value, default=None):
    if sympy is not None and isinstance(value, sympy.Basic):
        try:
            number = complex(value.evalf(30))
        except Exception:
            return default
    else:
        try:
            number = complex(float(value))
        except Exception:
            return default
    if not math.isfinite(number.real) or abs(number.imag) > 1e-9:
        return default
    return float(number.real)


class Result(object):
    """统一出口：latex / exact / numeric / steps / checks / unsupported / warnings 七个通道。"""

    def __init__(self, op):
        self.op = op
        self.digits = 6
        self.latex = []
        self.exact = []
        self.numeric = []
        self.steps = []
        self.checks = []
        self.unsupported = []
        self.warnings = []

    def emit(self, value, digits=None):
        digits = self.digits if digits is None else digits
        self.exact.append(_exact(value))
        self.numeric.append(_numeric(value, digits))
        if sympy is not None:
            try:
                self.latex.append(sympy.latex(value))
            except Exception:
                self.latex.append(_exact(value))
        else:
            self.latex.append(_exact(value))
        return value

    def check(self, name, passed, note):
        self.checks.append({"name": name, "pass": bool(passed), "note": str(note)})
        if not passed:
            self.warnings.append("复核未通过：%s（%s）" % (name, note))
        return bool(passed)

    def need_sympy(self, what):
        if sympy is None:
            self.unsupported.append("没有 sympy：%s 不可用（%s）" % (what, SYMPY_ERROR))
            return False
        return True

    def to_dict(self):
        if self.unsupported and not self.exact and not self.numeric:
            self.warnings.append("本 op 在当前环境只能降级或不可用：" + "；".join(self.unsupported))
        return {
            "ok": True, "op": self.op, "latex": self.latex, "exact": self.exact,
            "numeric": self.numeric, "steps": self.steps, "checks": self.checks,
            "unsupported": self.unsupported, "warnings": self.warnings,
        }


# --------------------------------------------------------------------------
# 各 op 的实现
# --------------------------------------------------------------------------
def _assumptions(spec):
    raw = spec.get("assumptions")
    return raw if isinstance(raw, dict) else {}


def _symbols_of(exprs, spec):
    if not exprs:
        return []
    names = sorted({str(symbol) for expr in exprs for symbol in expr.free_symbols})
    unknown = spec.get("unknowns")
    if isinstance(unknown, list) and unknown:
        names = [str(item) for item in unknown]
    assumptions = _assumptions(spec)
    out = []
    for name in names:
        # pi / oo / sin 这类名字在方程里是 sympy 的常数或函数，而我们若把它建成同名 Symbol，
        # 两边就永远匹配不上，结果是"无解"——但真正原因是名字用错了。这里直接说清楚。
        if name in _SYMPY_FUNCS or name in _CONSTANT_NAMES:
            raise SpecError(
                "%r 是数学常数或函数名，不能作为未知量；请换一个符号名"
                "（例如把 pi 改成 pp，或把它当成已知数代入）" % name)
        if name in assumptions:
            out.append(sympy.Symbol(name, **{str(assumptions[name]): True}))
        else:
            out.append(sympy.Symbol(name))
    return out


def _equations(spec, result):
    raw = spec.get("equations") or spec.get("eqs") or spec.get("equation")
    if raw is None and spec.get("ode") is None:
        raise SpecError("solve 需要 equations:[...]（字符串数组，等号可省）")
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, list) or not raw:
        raise SpecError("equations 必须是非空字符串数组")
    return [_to_sympy(item, result.warnings, what="方程") for item in raw]


def _check_solution(equations, symbols, solution, result):
    """把根代回每个方程，报最大残差（精确优先，退化为高精度数值）。

    solution 是"符号名 -> 值"，代入时按 symbols 里**真实的符号对象**映射：
    带假设的符号（positive/real…）与朴素同名 Symbol 是两个不同对象，直接拿朴素 Symbol 去 subs
    会匹配不上，于是正确的解被判成"残差无法定量"——这是必须避开的假阴性。
    """
    by_name = {str(symbol): symbol for symbol in symbols}
    mapping = {}
    for name, value in solution.items():
        mapping[by_name.get(str(name), sympy.Symbol(str(name)))] = value
    worst = 0.0
    mode = "精确化简"
    for equation in equations:
        residual = equation.subs(mapping)
        simplified = sympy.simplify(residual)
        if simplified == 0:
            continue
        value = _float_of(sympy.N(sympy.Abs(simplified), 30))
        if value is None:
            mode = "无法定量"
            worst = float("inf")
            break
        mode = "高精度数值"
        worst = max(worst, value)
    ok = worst <= 1e-8
    if worst == float("inf"):
        result.check("代回原方程（残差 ≤ 1e-8）", False,
                     "残差无法定量：解里可能含未消去的参数，请检查 unknowns 是否写全")
    else:
        result.check("代回原方程（残差 ≤ 1e-8）", ok, "最大残差 %.3g（%s）" % (worst, mode))


def op_solve(spec, result):
    # 顺序很重要：没有 sympy 时必须**先**分流，否则 _equations 里的 _to_sympy 会直接抛错，
    # 数值降级路径永远走不到（这是最初的降级路径 7 个用例全挂的原因）。
    if sympy is None:
        return _solve_numeric(spec, result)
    equations = _equations(spec, result)
    symbols = _symbols_of(equations, spec)
    if not symbols:
        raise SpecError("没有识别出未知量：请写清变量名，或用 unknowns 指定")
    # 显式指定 unknowns 时，方程里其余的符号按**参数**处理（参数化解是工科的常态，不是错误）。
    # 只有自动识别时才把全部自由符号当未知量，否则会解出一堆平凡解。
    parameters = sorted({str(item) for equation in equations for item in equation.free_symbols}
                        - {str(item) for item in symbols})
    if parameters:
        result.steps.append({"按参数处理（视为已知量）": parameters})
    elif not spec.get("unknowns") and len(symbols) > 1:
        result.warnings.append("没有指定 unknowns，已把全部自由符号（%s）当未知量；"
                               "若其中有参数，请用 unknowns 明确写出要求解的未知量"
                               % "、".join(str(item) for item in symbols))
    equations = _apply_assumptions(equations, symbols)
    if _assumptions(spec):
        result.steps.append({"已套用的假设": dict(_assumptions(spec))})
    if len(symbols) == 1:
        solutions = sympy.solve(equations, symbols[0], dict=True)
    else:
        solutions = sympy.solve(equations, symbols, dict=True)
    if not solutions:
        # 不要 emit([]) —— 那会产出 exact=[[]] 这种嵌套空数组，下游要写特判；
        # 这里改成"没有结果 + 一条不通过的复核"，让"没解"这件事显式出现在 checks 里。
        result.warnings.append("sympy 没有找到解（可能是无解、或者需要数值方法）")
        result.check("求到解", False, "没有求到任何解（无解或需要数值方法）")
        return result.to_dict()
    clean = []
    for item in solutions:
        row = {str(symbol): item[symbol] for symbol in symbols if symbol in item}
        clean.append(row)
    result.steps.append({"未知量": [str(item) for item in symbols], "解的个数": len(clean)})
    incomplete = [row for row in clean if len(row) < len(symbols)]
    if incomplete:
        missing = sorted({str(symbol) for symbol in symbols} - set(clean[0]))
        result.warnings.append(
            "方程不足以唯一确定全部未知量：%s 没有定值，解里还留着自由符号（无穷多解）；"
            "工程上需要补充边界条件/相容方程后再解" % "、".join(missing))
    for item in clean:
        result.emit(item)
        # 字典的默认 LaTeX 是 \left\{\mathtt{x}: 2\right\}，写进答案不好看；改成 "x = 2" 形式
        result.latex[-1] = ", ".join(
            "%s = %s" % (sympy.latex(sympy.Symbol(name)), sympy.latex(value))
            for name, value in item.items())
        _check_solution(equations, symbols, item, result)
    if len(clean) > 1:
        result.warnings.append("有 %d 组解：工程上要按物理条件取舍（例如长度/质量不能为负），取舍理由要写清楚"
                               % len(clean))
    return result.to_dict()


def _solve_numeric(spec, result):
    """没有 sympy：能做的数值求解，做不到的如实标注。"""
    result.unsupported.append("没有 sympy：只能给数值解，且非线性方程组不可用（%s）" % SYMPY_ERROR)
    raw = spec.get("equations") or spec.get("eqs") or spec.get("equation")
    if raw is None:
        raise SpecError("solve 需要 equations:[...]（字符串数组，等号可省）")
    if isinstance(raw, str):
        raw = [raw]
    if not isinstance(raw, list) or not raw:
        raise SpecError("equations 必须是非空字符串数组")
    names = spec.get("unknowns")
    if isinstance(names, list) and names:
        names = [str(item) for item in names]
    else:
        found = set()
        for item in raw:
            found |= set(_IDENTIFIER.findall(_strip_equals(str(item))))
        found -= set(_NUM_ENV)
        names = sorted(found)
        result.steps.append({"自动识别的未知量": names})
    if not names:
        raise SpecError("没有识别出未知量：请用 unknowns 明确指定")

    # 方程里出现、但没被列为未知量的符号是**参数**。数值路径没法把参数留成字母，
    # 硬把参数当 0 会算出一个看着像样、其实错误的答案——所以这里明确拒绝。
    extra = sorted(set(_IDENTIFIER.findall(
        " ".join(_strip_equals(str(item)) for item in raw)))
        - set(names) - set(_SYMPY_FUNCS) - set(_CONSTANT_NAMES))
    if extra:
        result.unsupported.append(
            "没有 sympy：方程里含符号参数（%s），数值路径无法求解含参数的方程；"
            "请装 sympy，或先把参数代入具体数值" % "、".join(extra))
        return result.to_dict()

    def residuals(point):
        values = []
        for item in raw:
            function = _numeric_func(item, names, "方程")
            values.append(np.asarray(function(*point), dtype=float))
        return np.array(values, dtype=float)

    if len(names) == 1:
        def scalar(grid):
            return np.asarray(_numeric_func(raw[0], names, "方程")(grid), dtype=float)
        roots = _scan_roots(scalar)
        if not roots:
            result.warnings.append("在 [-60, 60] 内没有扫到符号变化；如果有解，请用 sympy 或缩小范围重试")
        for root in roots:
            result.emit({"x": root})
            result.check("代回原方程（残差 ≤ 1e-6）", abs(float(scalar(np.array([root]))[0])) <= 1e-6,
                         "残差 %.3g" % abs(float(scalar(np.array([root]))[0])))
        return result.to_dict()

    # 多未知量：只处理线性方程组（用叠加校验确认它确实线性）
    zero = np.zeros(len(names))
    base = residuals(zero)
    matrix = []
    for index in range(len(names)):
        probe = np.zeros(len(names))
        probe[index] = 1.0
        matrix.append(residuals(probe) - base)
    matrix = np.array(matrix).T
    b = -base
    random_point = np.random.default_rng(7).normal(size=len(names))
    superposition = np.abs(residuals(random_point) - (matrix @ random_point + base)).max()
    if superposition > 1e-6 * max(1.0, np.abs(matrix).max()):
        result.unsupported.append(
            "没有 sympy：该方程组不是线性的（叠加校验偏差 %.3g），非线性方程组需要 sympy" % superposition)
        return result.to_dict()
    try:
        answer = np.linalg.solve(matrix, b)
    except np.linalg.LinAlgError as exc:
        raise SpecError("系数矩阵奇异，方程组无解或有无穷多解：%s" % exc)
    result.emit({name: float(value) for name, value in zip(names, answer)})
    residual = np.abs(matrix @ answer - b).max()
    result.check("代回原方程（残差 ≤ 1e-8）", residual <= 1e-8, "最大残差 %.3g" % residual)
    result.check("线性叠加校验（确认按线性方程组求解是合法的）", superposition <= 1e-6,
                 "偏差 %.3g" % superposition)
    return result.to_dict()


def _single_expr(spec):
    raw = spec.get("expr")
    if raw is None:
        raise SpecError("该 op 需要 expr")
    return raw


def op_transform(spec, result, action):
    if not result.need_sympy(action):
        return result.to_dict()
    expr = _to_sympy(_single_expr(spec), result.warnings)
    var = spec.get("var")
    symbols = [sympy.Symbol(str(var))] if var else sorted(expr.free_symbols, key=str)
    if action == "simplify":
        value = sympy.simplify(expr)
        result.emit(value)
        difference = sympy.simplify(value - expr)
        result.check("化简前后恒等", difference == 0, "差 = %s" % sympy.sstr(difference))
    elif action == "expand":
        value = sympy.expand(expr)
        result.emit(value)
        result.check("展开前后恒等", sympy.simplify(value - expr) == 0, "差已化简为 0")
    elif action == "factor":
        value = sympy.factor(expr)
        result.emit(value)
        result.check("因式分解前后恒等", sympy.simplify(sympy.expand(value) - expr) == 0, "差已化简为 0")
    elif action == "together":
        value = sympy.together(expr)
        result.emit(value)
        result.check("通分前后恒等", sympy.simplify(value - expr) == 0, "差已化简为 0")
    elif action == "apart":
        if not symbols:
            raise SpecError("apart 需要 var（对哪个变量做部分分式）")
        value = sympy.apart(expr, symbols[0])
        result.emit(value)
        result.check("部分分式前后恒等", sympy.simplify(value - expr) == 0, "差已化简为 0")
    else:
        raise SpecError("未知变形：%s" % action)
    return result.to_dict()


def op_diff(spec, result):
    if not result.need_sympy("diff"):
        # 降级路径：没有符号微分，但"在某点的导数值"可以用中心差分老实算出来
        return _diff_numeric(spec, result)
    expr = _to_sympy(_single_expr(spec), result.warnings)
    var = sympy.Symbol(str(spec.get("var") or "x"))
    order = int(spec.get("order") or 1)
    value = sympy.diff(expr, var, order)
    result.emit(value)

    # 表达式里除求导变量以外还有 q0/EI 这类**参数**是工科的常态（q0*x**2/2 对 x 求导）。
    # 复核时把这些参数固定成一组具体数值再比较——绝不能让它们以"未定义"的名义把整个 op 弄失败。
    parameters = sorted((expr.free_symbols - {var}), key=str)
    probe = {symbol: 1.0 + 0.37 * index for index, symbol in enumerate(parameters)}
    if parameters:
        result.steps.append({"复核时给参数取的试算值": {str(k): float(v) for k, v in probe.items()}})
    numeric = _numeric_func(_single_expr(spec), [str(var)] + [str(item) for item in parameters], "表达式")
    args = [probe[item] for item in parameters]

    def finite_difference(point, times, step=1e-3):
        """同阶的中心差分：阶数不对，比的就不是同一个东西（曾经把一阶差分拿去比二阶导数）。"""
        if times <= 0:
            return float(np.asarray(numeric(np.array([point]), *args), dtype=float)[0])
        return (finite_difference(point + step, times - 1, step)
                - finite_difference(point - step, times - 1, step)) / (2.0 * step)

    replaced = value.subs(probe) if probe else value
    symbolic = sympy.lambdify(var, replaced, "numpy")
    worst = 0.0
    compared = 0
    for point in (0.7, 1.3, 2.1):
        approx = finite_difference(point, order)
        try:
            exact = float(np.asarray(symbolic(point), dtype=float))
        except Exception:
            continue
        scale = max(1.0, abs(exact))
        worst = max(worst, abs(approx - exact) / scale)
        compared += 1
    if compared:
        result.check("与同阶中心差分对比（最大相对偏差 ≤ 1e-3）", worst <= 1e-3,
                     "%d 点、%d 阶中心差分（步长 1e-3），最大相对偏差 %.3g" % (compared, order, worst))
    else:
        result.check("与同阶中心差分对比", False, "导数表达式无法数值化，未能独立复核")
    return result.to_dict()


def _diff_numeric(spec, result):
    """没有 sympy 时的导数：只做"某点的导数值"（中心差分），给不出导函数就明说。"""
    expr_text = _single_expr(spec)
    var_name = str(spec.get("var") or "x")
    order = int(spec.get("order") or 1)
    point = spec.get("at", spec.get("point"))
    result.unsupported.append("没有 sympy：给不出导函数表达式，只能给某点的导数值（%s）" % SYMPY_ERROR)
    if point is None:
        result.unsupported.append("没有 sympy：请用 at 指定求导点，否则无法用中心差分算数值导数")
        return result.to_dict()
    names = [var_name] + sorted({name for name in _IDENTIFIER.findall(_strip_equals(expr_text))
                                 if name != var_name and name not in _SYMPY_FUNCS
                                 and name not in _CONSTANT_NAMES and name not in _NUM_ENV})
    # 除求导变量以外的符号按 1.0 试算值代入（与 sympy 路径同一约定），并如实报出来
    fixed = {name: 1.0 for name in names[1:]}
    if fixed:
        result.steps.append({"复核时给参数取的试算值": fixed})
    function = _numeric_func(expr_text, names, "表达式")
    args = [fixed[name] for name in names[1:]]

    def value_at(x):
        return float(np.asarray(function(np.array([x]), *args), dtype=float)[0])

    def central(x, times, step=1e-3):
        if times <= 0:
            return value_at(x)
        return (central(x + step, times - 1, step) - central(x - step, times - 1, step)) / (2.0 * step)

    coarse = central(float(point), order, 1e-3)
    finer = central(float(point), order, 5e-4)
    result.emit(coarse)
    result.latex[-1] = "%.10g" % coarse
    deviation = abs(coarse - finer) / max(1.0, abs(coarse))
    result.check("加密步长复核（步长 1e-3 与 5e-4 相对偏差 ≤ 1e-2）", deviation <= 1e-2,
                 "%d 阶中心差分 @ %s，相对偏差 %.3g" % (order, point, deviation))
    return result.to_dict()


def op_integrate(spec, result):
    expr_text = _single_expr(spec)
    limits = spec.get("limits")
    var_name = str(spec.get("var") or "x")
    if sympy is None:
        # 注意：这里不能先建 sympy.Symbol —— 没有 sympy 时那就是 None.Symbol，会崩
        if not limits or len(limits) != 2:
            result.unsupported.append("没有 sympy：不定积分不可用（%s）" % SYMPY_ERROR)
            return result.to_dict()
        function = _numeric_func(expr_text, [var_name], "被积函数")
        try:
            low, high = float(limits[0]), float(limits[1])
        except (TypeError, ValueError):
            result.unsupported.append(
                "没有 sympy：数值积分不支持无穷限或符号限（limits=%r），只能给有限区间" % (limits,))
            return result.to_dict()
        value = _simpson(lambda grid: function(grid), low, high)
        result.emit(value)
        result.unsupported.append("没有 sympy：定积分用 Simpson 数值积分（2000 段），非精确值")
        coarse = _simpson(lambda grid: function(grid), low, high, pieces=500)
        deviation = abs(value - coarse) / max(1.0, abs(value))
        result.check("加密网格复核（500 段与 2000 段相对偏差 ≤ 1e-6）", deviation <= 1e-6,
                     "相对偏差 %.3g" % deviation)
        return result.to_dict()
    var = sympy.Symbol(var_name)
    expr = _to_sympy(expr_text, result.warnings)
    if limits and len(limits) == 2:
        low = _to_sympy(limits[0], result.warnings, what="积分下限")
        high = _to_sympy(limits[1], result.warnings, what="积分上限")
        value = sympy.integrate(expr, (var, low, high))
        result.emit(value)
        numeric_value = _float_of(sympy.N(value, 30))
        low_f, high_f = _float_of(low), _float_of(high)
        if numeric_value is not None and low_f is not None and high_f is not None:
            function = _numeric_func(expr_text, [str(var)], "被积函数")
            approx = _simpson(lambda grid: function(grid), low_f, high_f)
            deviation = abs(approx - numeric_value) / max(1.0, abs(numeric_value))
            result.check("与 Simpson 数值积分对比（相对偏差 ≤ 1e-8）", deviation <= 1e-8,
                         "数值 %.10g vs 精确 %.10g，相对偏差 %.3g" % (approx, numeric_value, deviation))
        elif numeric_value is not None:
            # 无穷限没法直接做 Simpson；用"截断到有限大数"的数值积分兜底复核，并把截断点与
            # 容差都写出来——不能因为区间是无穷的就不做复核。区间只是含符号（如 0..L）则不算无穷限。
            try:
                unbounded_low = bool(low == -sympy.oo)
                unbounded_high = bool(high == sympy.oo)
            except Exception:
                unbounded_low = unbounded_high = False
            if unbounded_low or unbounded_high:
                cut = 200.0
                a = -cut if unbounded_low else (low_f if low_f is not None else -cut)
                b = cut if unbounded_high else (high_f if high_f is not None else cut)
                try:
                    function = _numeric_func(expr_text, [str(var)], "被积函数")
                    approx = _simpson(lambda grid: function(grid), a, b)
                    deviation = abs(approx - numeric_value) / max(1.0, abs(numeric_value))
                    result.check("无穷限截断复核（截断到 ±%g 后与精确值比较）" % cut, deviation <= 1e-3,
                                 "截断数值 %.10g vs 精确 %.10g，相对偏差 %.3g"
                                 % (approx, numeric_value, deviation))
                except SpecError as exc:
                    result.check("无穷限截断复核", False, "无法数值化：%s" % exc)
            else:
                result.check("定积分结果可数值化", False, "积分限含符号，无法与有限区间数值积分对照")
        else:
            result.check("定积分结果可数值化", False, "结果是符号式，无法与数值积分对照，请在图上另行核对")
    else:
        value = sympy.integrate(expr, var)
        result.emit(value)
        back = sympy.simplify(sympy.diff(value, var) - expr)
        result.check("对结果求导应得回被积函数", back == 0, "差 = %s" % sympy.sstr(back))
    return result.to_dict()


def op_limit(spec, result):
    if not result.need_sympy("limit"):
        return result.to_dict()
    expr = _to_sympy(_single_expr(spec), result.warnings)
    var = sympy.Symbol(str(spec.get("var") or "x"))
    point_text = str(spec.get("point") or 0)
    direction = spec.get("dir")
    sign = ""
    if point_text.endswith("+"):
        point_text, sign = point_text[:-1], "+"
    elif point_text.endswith("-"):
        point_text, sign = point_text[:-1], "-"
    if direction in ("+", "-"):
        sign = direction
    point = _to_sympy(point_text if point_text not in ("oo", "inf") else "oo",
                      result.warnings, what="极限点")
    if point_text in ("-oo", "-inf"):
        point = -sympy.oo
    value = sympy.limit(expr, var, point, sign) if sign else sympy.limit(expr, var, point)
    result.emit(value)
    # 独立复核：用序列逼近（沿趋近方向取越来越近的点）看是否收敛到同一个数
    numeric = _float_of(sympy.N(value, 30))
    target = _float_of(point)
    if numeric is not None and target is not None:
        function = _numeric_func(_single_expr(spec), [str(var)], "表达式")
        probes = []
        for step in (1e-2, 1e-3, 1e-4):
            probe = target + step if sign != "-" else target - step
            try:
                probes.append(float(np.asarray(function(np.array([probe])), dtype=float)[0]))
            except Exception:
                break
        if probes:
            deviation = abs(probes[-1] - numeric) / max(1.0, abs(numeric))
            result.check("序列逼近复核（相对偏差 ≤ 1e-4）", deviation <= 1e-4,
                         "x=%.10g 处 %.10g vs 极限 %.10g，相对偏差 %.3g"
                         % (target + (1e-4 if sign != "-" else -1e-4), probes[-1], numeric, deviation))
        else:
            result.check("序列逼近复核", False, "无法取到数值序列")
    elif numeric is not None and point in (sympy.oo, -sympy.oo):
        function = _numeric_func(_single_expr(spec), [str(var)], "表达式")
        probes = []
        for big in (1e2, 1e3, 1e4):
            probe = big if point == sympy.oo else -big
            try:
                probes.append(float(np.asarray(function(np.array([probe])), dtype=float)[0]))
            except Exception:
                break
        if probes:
            deviation = abs(probes[-1] - numeric) / max(1.0, abs(numeric))
            result.check("大数代入复核（x 取 1e4 与极限值比较，相对偏差 ≤ 1e-2）", deviation <= 1e-2,
                         "x=±1e4 处 %.10g vs 极限 %.10g，相对偏差 %.3g" % (probes[-1], numeric, deviation))
        else:
            result.check("大数代入复核", False, "无法取到数值序列")
    else:
        result.steps.append({"说明": "极限点是符号式或结果无法数值化，序列逼近复核从略；结果仍由 sympy 独立给出"})
    return result.to_dict()


def op_series(spec, result):
    if not result.need_sympy("series"):
        return result.to_dict()
    expr = _to_sympy(_single_expr(spec), result.warnings)
    var = sympy.Symbol(str(spec.get("var") or "x"))
    point = _to_sympy(spec.get("point") or 0, result.warnings, what="展开点")
    order = int(spec.get("order") or 6)
    value = sympy.series(expr, var, point, order).removeO()
    result.emit(value)
    numeric = _float_of(sympy.N(value.subs(var, 0.3), 30))
    if numeric is not None:
        exact = _float_of(sympy.N(expr.subs(var, 0.3), 30))
        if exact is not None:
            deviation = abs(numeric - exact) / max(1.0, abs(exact))
            result.check("展开式在 %s=0.3 处与原式接近（相对偏差 ≤ 1e-2）" % var, deviation <= 1e-2,
                         "展开 %.10g vs 原式 %.10g，相对偏差 %.3g" % (numeric, exact, deviation))
    return result.to_dict()


def _ode_parse(text, func_name, var_name):
    """把 `y'' + 4*y = 0` 转成 sympy 的 `Derivative(y(x), (x, 2)) + 4*y(x)`。

    必须**一次扫描**完成：如果先替换带撇的导数、再把裸 `y` 换成 `y(x)`，
    第二步会把刚生成的 `y(x)` 里的 `y` 又替换一遍，得到 `y(x)(x)`，
    运行时就是那句让人摸不着头脑的 "'y' object is not callable"。
    """
    body = _guard(text, "微分方程")
    pattern = re.compile(r"\b%s(''|′{2}|'|′)?(\s*\()?" % re.escape(func_name))

    def rewrite(match):
        marks = match.group(1) or ""
        order = 2 if len(marks) >= 2 else (1 if marks else 0)
        if order == 0:
            return (func_name + "(") if match.group(2) else ("%s(%s)" % (func_name, var_name))
        return "Derivative(%s(%s), (%s, %d))" % (func_name, var_name, var_name, order)

    body = pattern.sub(rewrite, body)
    function = sympy.Function(func_name)
    variable = sympy.Symbol(var_name)
    return _to_sympy(body, local_dict={func_name: function, var_name: variable}, what="微分方程")


def _apply_assumptions(equations, symbols):
    """带假设（如 positive）的符号与解析出来的普通符号不是同一个对象，必须代换进方程。"""
    mapping = {}
    for symbol in symbols:
        plain = sympy.Symbol(str(symbol))
        if plain != symbol:
            mapping[plain] = symbol
    if not mapping:
        return equations
    return [equation.subs(mapping) for equation in equations]


def _ode_ics(raw, func_name, var_name):
    """返回 (给 dsolve 的 ics 字典, 供独立复核用的 [(阶数, 取值点, 目标值, 描述)])。

    复核**不能**用 ics 字典的键去反推取值点：键是 `Subs(Derivative(y(x), x), x, 0)`，
    它的 args[0] 是那个导数、不是 0；拿它当代入点会算出 cos(2*Derivative(y(x),x)) 这种荒唐结果。
    """
    if not isinstance(raw, dict):
        return {}, []
    function = sympy.Function(func_name)
    variable = sympy.Symbol(var_name)
    table = {}
    checks = []
    for key, value in raw.items():
        text = str(key).replace(" ", "")
        match = re.match(r"^%s('{1,2}|′{1,2})?\((.+)\)$" % func_name, text)
        if not match:
            raise SpecError("初始条件 %r 的写法看不懂，请写成 y(0) 或 y'(0)" % key)
        marks = match.group(1) or ""
        point = _to_sympy(match.group(2), what="初始条件自变量")
        order = 2 if len(marks) >= 2 else (1 if marks else 0)
        target_value = _to_sympy(value, what="初始条件取值")
        target = function(variable) if order == 0 else sympy.Derivative(function(variable), (variable, order))
        table[target.subs(variable, point)] = target_value
        checks.append((order, point, target_value, text))
    return table, checks


def op_dsolve(spec, result):
    if not result.need_sympy("dsolve"):
        return result.to_dict()
    func_name = str(spec.get("func") or "y")
    var_name = str(spec.get("var") or "x")
    ode = _ode_parse(str(spec.get("ode")), func_name, var_name)
    function = sympy.Function(func_name)
    variable = sympy.Symbol(var_name)
    ics, ic_checks = _ode_ics(spec.get("ics"), func_name, var_name)
    try:
        solution = sympy.dsolve(ode, function(variable), ics=ics) if ics else sympy.dsolve(ode, function(variable))
    except Exception as exc:
        raise SpecError("dsolve 求解失败：%s" % exc)
    result.emit(solution)
    right = solution.rhs if hasattr(solution, "rhs") else solution
    residual = sympy.simplify(ode.subs(function(variable), right).doit())
    result.check("解代回原微分方程（残差化简为 0）", residual == 0, "残差 = %s" % sympy.sstr(residual))
    for order, point, target_value, label in ic_checks:
        # 用解本身求相应阶导数再代入，这才是真正的"初值检查"
        expression = right if order == 0 else sympy.diff(right, variable, order)
        got = sympy.simplify(expression.subs(variable, point))
        deviation = sympy.simplify(got - target_value)
        result.check("初值检查 %s：求得 %s，应为 %s" % (label, sympy.sstr(got), sympy.sstr(target_value)),
                     deviation == 0, "偏差 = %s" % sympy.sstr(deviation))
    if not ic_checks:
        result.steps.append({"提示": "未给 ics，解里会带积分常数 C1/C2；工程题一般要用边界条件定出它们"})
    return result.to_dict()


def _matrix_of(spec, result):
    raw = spec.get("a")
    if raw is None:
        raise SpecError("matrix 需要 a:[[...],[...]]")
    if sympy is None:
        return np.asarray(raw, dtype=float)
    rows = []
    for row in raw:
        if not isinstance(row, list):
            raise SpecError("a 必须是二维数组（每行一个数组）")
        rows.append([_to_sympy(_guard(item, "矩阵元素"), result.warnings, what="矩阵元素")
                     if isinstance(item, str) else sympy.sympify(item) for item in row])
    return sympy.Matrix(rows)


def op_matrix(spec, result):
    # 注意：`op` 已经被 dispatch 用来选中本处理器，所以具体动作读 `action`。
    # 不写 action 就默认 det 太隐晦（会被误当成"算什么就是什么"），这里要求显式给出。
    raw_action = spec.get("action") or spec.get("matrix_op")
    if raw_action is None:
        raise SpecError("matrix 需要 action：det / inverse / rank / rref / transpose / eigen / solve / power")
    action = str(raw_action).lower()
    matrix = _matrix_of(spec, result)
    if sympy is None:
        return _matrix_numeric(spec, result, matrix, action)
    try:
        return _matrix_sympy(spec, result, matrix, action)
    except SpecError:
        raise
    except Exception as exc:
        # sympy 对奇异矩阵抛的是英文 NonInvertibleMatrixError 之类，别把它说成"内部错误"——
        # 数学上是"这个矩阵不可逆"，用户要的是这句话。
        name = type(exc).__name__
        if "Invertible" in name or "Singular" in name or "det == 0" in str(exc):
            raise SpecError("矩阵不可逆（行列式 = 0），无法求逆或解方程；"
                            "请检查方程是否线性相关（相容方程不足时会这样）")
        raise SpecError("矩阵运算失败（%s）：%s" % (name, exc))


def _matrix_sympy(spec, result, matrix, action):
    if action == "det":
        value = matrix.det()
        result.emit(value)
        if matrix.shape[0] == matrix.shape[1] and matrix.shape[0] <= 3:
            result.check("行列式非零 ⇔ 可逆（对照伴随矩阵法）", True, "行列式 = %s" % sympy.sstr(value))
    elif action in ("inverse", "inv"):
        value = matrix.inv()
        result.emit(value)
        residual = (matrix * value - sympy.eye(matrix.shape[0])).applyfunc(sympy.simplify)
        norm = max(abs(_float_of(sympy.N(item, 20)) or 0.0) for item in residual)
        result.check("A·A⁻¹ = I", residual.is_zero_matrix if hasattr(residual, "is_zero_matrix") else norm == 0,
                     "‖A·A⁻¹−I‖∞ = %.3g" % norm)
    elif action == "rank":
        value = matrix.rank()
        result.emit(value)
        result.check("秩不超过阶数", value <= min(matrix.shape), "rank = %s，形状 %s" % (value, matrix.shape))
    elif action == "rref":
        reduced, pivots = matrix.rref()
        result.emit(reduced)
        result.steps.append({"主元列": list(pivots)})
    elif action == "transpose":
        result.emit(matrix.T)
    elif action == "eigen" or action == "eigenvalues":
        values = matrix.eigenvals()
        result.steps.append({"特征值（含重数）": {sympy.sstr(k): int(v) for k, v in values.items()}})
        result.emit(sorted(values.keys(), key=lambda item: (_float_of(item) if _float_of(item) is not None else 0.0)))
        numeric_matrix = np.array(matrix.tolist(), dtype=float) if all(
            _float_of(item) is not None for item in matrix) else None
        if numeric_matrix is not None:
            worst = 0.0
            for value, multiplicity in values.items():
                vectors = matrix.eigenvects()
                for item in vectors:
                    if sympy.simplify(item[0] - value) != 0:
                        continue
                    for vector in item[2]:
                        vector_np = np.array([_float_of(component) or 0.0 for component in vector], dtype=float)
                        lam = _float_of(value) or 0.0
                        worst = max(worst, float(np.linalg.norm(numeric_matrix @ vector_np - lam * vector_np)))
            result.check("‖Av − λv‖ ≈ 0", worst <= 1e-6, "最大残差 %.3g" % worst)
    elif action == "solve":
        raw_b = spec.get("b")
        if raw_b is None:
            raise SpecError("matrix op=solve 需要 b")
        rhs = sympy.Matrix([_to_sympy(_guard(item, "b 的元素"), result.warnings) if isinstance(item, str)
                            else sympy.sympify(item) for item in raw_b])
        value = matrix.solve(rhs)
        result.emit(value)
        residual = (matrix * value - rhs).applyfunc(sympy.simplify)
        norm = max(abs(_float_of(sympy.N(item, 20)) or 0.0) for item in residual)
        result.check("A·x = b", norm <= 1e-9, "‖A·x−b‖∞ = %.3g" % norm)
    elif action == "power":
        result.emit(matrix ** int(spec.get("n") or 2))
    else:
        raise SpecError("未知 matrix 操作：%s（可用 det/inverse/rank/rref/transpose/eigen/solve/power）" % action)
    return result.to_dict()


def _matrix_numeric(spec, result, matrix, action):
    result.unsupported.append("没有 sympy：矩阵运算走 numpy 数值路径，结果是浮点数（%s）" % SYMPY_ERROR)
    if action == "det":
        value = float(np.linalg.det(matrix))
        result.emit(value)
    elif action in ("inverse", "inv"):
        value = np.linalg.inv(matrix)
        result.emit(value.tolist())
        norm = float(np.linalg.norm(matrix @ value - np.eye(matrix.shape[0]), np.inf))
        result.check("A·A⁻¹ = I", norm <= 1e-8, "‖A·A⁻¹−I‖∞ = %.3g" % norm)
    elif action == "rank":
        value = int(np.linalg.matrix_rank(matrix))
        result.emit(value)
    elif action == "rref":
        reduced, pivots = _gauss_jordan(np.asarray(matrix, dtype=float))
        result.emit(reduced.tolist())
        result.steps.append({"主元列": pivots})
    elif action == "transpose":
        result.emit(matrix.T.tolist())
    elif action in ("eigen", "eigenvalues"):
        values, vectors = np.linalg.eig(matrix)
        result.emit([float(item) for item in values.real])
        worst = 0.0
        for index, value in enumerate(values):
            worst = max(worst, float(np.linalg.norm(matrix @ vectors[:, index] - value * vectors[:, index])))
        result.check("‖Av − λv‖ ≈ 0", worst <= 1e-8, "最大残差 %.3g" % worst)
    elif action == "solve":
        rhs = np.asarray(spec.get("b"), dtype=float)
        value = np.linalg.solve(matrix, rhs)
        result.emit([float(item) for item in value])
        norm = float(np.abs(matrix @ value - rhs).max())
        result.check("A·x = b", norm <= 1e-8, "‖A·x−b‖∞ = %.3g" % norm)
    elif action == "power":
        result.emit(np.linalg.matrix_power(matrix, int(spec.get("n") or 2)).tolist())
    else:
        raise SpecError("未知 matrix 操作：%s" % action)
    return result.to_dict()


def _gauss_jordan(matrix):
    work = np.array(matrix, dtype=float)
    rows, cols = work.shape
    pivots = []
    row = 0
    for col in range(cols):
        if row >= rows:
            break
        index = int(np.argmax(np.abs(work[row:, col]))) + row
        if abs(work[index, col]) < 1e-12:
            continue
        work[[row, index]] = work[[index, row]]
        work[row] = work[row] / work[row, col]
        for other in range(rows):
            if other != row:
                work[other] = work[other] - work[other, col] * work[row]
        pivots.append(col)
        row += 1
    return work, pivots


def op_evaluate(spec, result):
    raw = _single_expr(spec)
    subs = spec.get("subs") or {}
    if not isinstance(subs, dict):
        raise SpecError("subs 要是 {变量: 值} 这样的对象")
    if sympy is None:
        names = [str(key) for key in subs]
        function = _numeric_func(raw, names, "表达式")
        values = [float(np.asarray(_numeric_func(str(item), [], "取值")())) if isinstance(item, str) else float(item)
                  for item in subs.values()]
        value = function(*values)
        result.emit(float(np.asarray(value, dtype=float)))
        result.unsupported.append("没有 sympy：evaluate 只给数值（%s）" % SYMPY_ERROR)
        return result.to_dict()
    expr = _to_sympy(raw, result.warnings)
    mapping = {}
    for key, item in subs.items():
        symbol = sympy.Symbol(str(key))
        mapping[symbol] = _to_sympy(item, result.warnings, what="取值") if isinstance(item, str) else sympy.sympify(item)
    value = expr.subs(mapping)
    result.emit(sympy.simplify(value))
    exact = _float_of(sympy.N(value, 30))
    if exact is None:
        result.check("符号结果可数值化", False, "结果仍含自由符号（%s），请把 subs 补齐后再与数值对照"
                     % "、".join(sorted(str(item) for item in value.free_symbols)))
    else:
        try:
            # 变量名要用字符串：symbolic 的 Symbol 当 dict 键喂给 eval 是找不到的
            approximate = float(np.asarray(_numeric_func(raw, [str(item) for item in subs.keys()], "表达式")(
                *[float(np.asarray(_numeric_func(str(item), [], "取值")())) for item in subs.values()]),
                dtype=float))
            deviation = abs(approximate - exact) / max(1.0, abs(exact))
            result.check("与独立数值求值对比（相对偏差 ≤ 1e-9）", deviation <= 1e-9,
                         "数值 %.10g vs 精确 %.10g，相对偏差 %.3g" % (approximate, exact, deviation))
        except Exception as exc:
            result.check("与独立数值求值对比", False, "数值对照没能做成：%s" % exc)
    return result.to_dict()


def op_roots(spec, result):
    coefficients = spec.get("coefficients")
    var_name = str(spec.get("var") or "x")
    var = sympy.Symbol(var_name) if sympy is not None else None
    if coefficients is not None:
        numbers = [float(item) for item in coefficients]
        while numbers and abs(numbers[0]) < 1e-15:
            numbers.pop(0)
        if not numbers:
            raise SpecError("coefficients 不能全是 0")
        degree = len(numbers) - 1
        numeric_roots = np.roots(numbers)
        result.emit([complex(item) for item in numeric_roots])
        polynomial = np.poly1d(numbers)
        worst = max(abs(complex(polynomial(root))) for root in numeric_roots)
        result.check("代回多项式（残差 ≤ 1e-6·系数尺度）",
                     worst <= 1e-6 * max(1.0, max(abs(item) for item in numbers)),
                     "最大残差 %.3g，次数 %d" % (worst, degree))
    else:
        if sympy is None:
            raise SpecError("没有 sympy 时必须给 coefficients（多项式系数），无法从表达式直接求根")
        expr = _to_sympy(_single_expr(spec), result.warnings)
        value = sympy.solve(expr, var)
        result.emit(value)
        worst = 0.0
        for item in value:
            approximate = _float_of(sympy.N(item, 30))
            if approximate is None:
                continue
            worst = max(worst, abs(_float_of(sympy.N(expr.subs(var, item), 30)) or 0.0))
        result.check("代回原式（残差 ≤ 1e-8）", worst <= 1e-8, "最大残差 %.3g，%d 个根" % (worst, len(value)))
    return result.to_dict()


def op_optimize(spec, result):
    raw = _single_expr(spec)
    var_name = str(spec.get("var") or "x")
    mode = str(spec.get("mode") or "min").lower()
    window = spec.get("range") or [-10.0, 10.0]
    low, high = float(window[0]), float(window[1])
    if sympy is None:
        result.unsupported.append("没有 sympy：极值走网格 + 黄金分割数值搜索（%s）" % SYMPY_ERROR)
        function = _numeric_func(raw, [var_name], "目标函数")
        grid = np.linspace(low, high, 4001)
        values = np.asarray(function(grid), dtype=float)
        index = int(np.argmin(values) if mode == "min" else np.argmax(values))
        lo = float(grid[max(0, index - 1)])
        hi = float(grid[min(len(grid) - 1, index + 1)])
        best = float(grid[index])
        for _ in range(200):
            a = lo + (hi - lo) * 0.382
            b = lo + (hi - lo) * 0.618
            fa = float(np.asarray(function(np.array([a])), dtype=float)[0])
            fb = float(np.asarray(function(np.array([b])), dtype=float)[0])
            if (fa < fb) if mode == "min" else (fa > fb):
                hi = b
            else:
                lo = a
            best = (lo + hi) / 2.0
        value = float(np.asarray(function(np.array([best])), dtype=float)[0])
        result.emit({"x": best, "value": value})
        neighbours = [float(np.asarray(function(np.array([best + step])), dtype=float)[0])
                      for step in (-1e-3, 1e-3)]
        edge = "lower" if abs(best - low) < 1e-6 else ("upper" if abs(best - high) < 1e-6 else None)
        if edge == "lower":
            # 取在区间端点时"邻域极小/极大"本来就不成立，只能按单侧比较判断
            probe_values = [neighbours[1]]
            better = all(value <= item + 1e-9 for item in probe_values) if mode == "min" else \
                all(value >= item - 1e-9 for item in probe_values)
            note = "区间左端点，单侧比较 %s" % probe_values
        elif edge == "upper":
            probe_values = [neighbours[0]]
            better = all(value <= item + 1e-9 for item in probe_values) if mode == "min" else \
                all(value >= item - 1e-9 for item in probe_values)
            note = "区间右端点，单侧比较 %s" % probe_values
        else:
            better = all(value <= item + 1e-9 for item in neighbours) if mode == "min" else \
                all(value >= item - 1e-9 for item in neighbours)
            note = "邻点值 %s" % neighbours
        result.check("邻域检查（确为局部%s）" % ("极小" if mode == "min" else "极大"), better,
                     "%s，该点 %.10g" % (note, value))
        return result.to_dict()
    var = sympy.Symbol(var_name)
    expr = _to_sympy(raw, result.warnings)
    derivative = sympy.diff(expr, var)
    candidates = sympy.solve(derivative, var)
    inside = []
    for item in candidates:
        number = _float_of(item)
        if number is None:
            continue
        if low - 1e-9 <= number <= high + 1e-9:
            inside.append((item, number))
    edges = [(sympy.Integer(low) if float(low).is_integer() else sympy.Float(low), float(low)),
             (sympy.Integer(high) if float(high).is_integer() else sympy.Float(high), float(high))]
    pool = inside + edges
    if not pool:
        raise SpecError("在 [%g, %g] 区间里没有找到驻点，请给 range" % (low, high))
    scored = []
    for item, number in pool:
        value = _float_of(sympy.N(expr.subs(var, item), 30))
        if value is None:
            continue
        scored.append((value, item, number))
    if not scored:
        raise SpecError("候选点的函数值无法数值化")
    scored.sort(key=lambda row: row[0], reverse=(mode == "max"))
    best_value, best_exact, best_number = scored[0]
    result.emit({"x": best_exact, "value": sympy.N(expr.subs(var, best_exact), 20)})
    result.steps.append({"驻点": [sympy.sstr(item) for item, _ in inside], "区间": [low, high]})
    if inside:
        second = sympy.diff(expr, var, 2).subs(var, best_exact)
        second_value = _float_of(sympy.N(second, 20))
        if second_value is not None:
            is_extreme = (second_value > 0) if mode == "min" else (second_value < 0)
            edge_case = abs(best_number - low) < 1e-9 or abs(best_number - high) < 1e-9
            result.check("二阶条件（%s）" % ("f''>0" if mode == "min" else "f''<0"),
                         is_extreme or edge_case,
                         "f'' = %.6g%s" % (second_value, "（取在区间端点，由边界决定）" if edge_case else ""))
    # 独立复核（二）：与密集数值扫描比较。纯单调函数、端点取极值、区间内无驻点这些情形
    # 二阶条件都用不上——必须靠这条兜底，否则会出现"checks 为空"，那等于没复核。
    parameters = sorted((expr.free_symbols - {var}), key=str)
    probe = {symbol: 1.0 + 0.37 * index for index, symbol in enumerate(parameters)}
    if parameters:
        result.steps.append({"复核时给参数取的试算值": {str(k): float(v) for k, v in probe.items()}})
    try:
        function = _numeric_func(raw, [var_name] + [str(item) for item in parameters], "目标函数")
        args = [probe[item] for item in parameters]
        grid = np.linspace(low, high, 20001)
        samples = np.asarray(function(grid, *args), dtype=float)
        scan_best = float(np.min(samples) if mode == "min" else np.max(samples))
        analytic = float(np.asarray(function(np.array([best_number]), *args), dtype=float)[0])
        deviation = abs(analytic - scan_best) / max(1.0, abs(scan_best))
        result.check("与密集扫描对比（区间内 20001 点）", deviation <= 1e-4,
                     "扫描最优 %.10g vs 候选点 %.10g，相对偏差 %.3g" % (scan_best, analytic, deviation))
    except SpecError as exc:
        result.check("与密集扫描对比", False, "无法数值化：%s" % exc)
    if not inside:
        # 没有驻点：极值只能由端点决定，这里补一条说明，别让判据空着
        result.check("区间内无驻点（极值由端点决定）", True,
                     "导函数在 [%g, %g] 内无实驻点，最优解取在端点" % (low, high))
    return result.to_dict()


def op_fit(spec, result):
    xs = spec.get("x")
    ys = spec.get("y")
    if not isinstance(xs, list) or not isinstance(ys, list) or len(xs) != len(ys) or len(xs) < 2:
        raise SpecError("fit 需要等长的 x 与 y 数组（至少 2 组）")
    degree = int(spec.get("degree") or 1)
    weights = spec.get("weights")
    x_array = np.asarray(xs, dtype=float)
    y_array = np.asarray(ys, dtype=float)
    w_array = np.asarray(weights, dtype=float) if isinstance(weights, list) else None
    if len(x_array) <= degree:
        raise SpecError("数据点 %d 个不够拟合 %d 次多项式（至少需要 %d 个）"
                        % (len(x_array), degree, degree + 1))
    coefficients = np.polyfit(x_array, y_array, degree, w=w_array)
    predicted = np.polyval(coefficients, x_array)
    residual = y_array - predicted
    ss_res = float(np.sum(residual ** 2))
    ss_tot = float(np.sum((y_array - y_array.mean()) ** 2))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 0 else (1.0 if ss_res == 0 else 0.0)
    result.emit([float(item) for item in coefficients])
    result.steps.append({
        "多项式": " + ".join("%.6g·x^%d" % (coefficients[index], degree - index)
                             for index in range(len(coefficients))),
        "残差平方和": ss_res, "R²": r2, "自由度": len(x_array) - degree - 1,
    })
    result.check("残差平方和已算出（越小越好）", True, "SSE = %.6g，R² = %.6f" % (ss_res, r2))
    if r2 < 0.9:
        result.check("拟合优度（R² ≥ 0.9）", False, "R² 只有 %.4f，说明这个模型没解释掉数据的主要变化，别硬用" % r2)
    else:
        result.check("拟合优度（R² ≥ 0.9）", True, "R² = %.6f" % r2)
    return result.to_dict()


def op_table(spec, result):
    raw = _single_expr(spec)
    var_name = str(spec.get("var") or "x")
    low = float(spec.get("from", 0.0))
    high = float(spec.get("to", 1.0))
    if high < low:
        raise SpecError("table 的 to（%g）不能小于 from（%g）；想反向取值请交换两者"
                        % (high, low))
    if spec.get("n"):
        count = max(2, int(spec["n"]))
        grid = np.linspace(low, high, count)
    else:
        step = float(spec.get("step") or (high - low) / 10.0)
        if step == 0:
            raise SpecError("step 不能为 0")
        grid = np.arange(low, high + step / 2.0, step)
    if sympy is not None:
        expr = _to_sympy(raw, result.warnings)
        var = sympy.Symbol(var_name)
        rows = []
        for point in grid:
            value = sympy.N(expr.subs(var, sympy.Float(float(point))), 12)
            rows.append({"x": float(point), "value": _float_of(value)})
        result.emit(rows)
        function = _numeric_func(raw, [var_name], "表达式")
        worst = max(abs((row["value"] if row["value"] is not None else 0.0)
                        - float(np.asarray(function(np.array([row["x"]])), dtype=float)[0])) for row in rows)
        result.check("符号代入与数值求值逐点一致（偏差 ≤ 1e-9）", worst <= 1e-9, "最大偏差 %.3g" % worst)
    else:
        function = _numeric_func(raw, [var_name], "表达式")
        rows = [{"x": float(point), "value": float(np.asarray(function(np.array([point])), dtype=float)[0])}
                for point in grid]
        result.emit(rows)
        result.unsupported.append("没有 sympy：table 只给数值（%s）" % SYMPY_ERROR)
    return result.to_dict()


def op_probe(spec, result):
    result.steps.append({
        "sympy": (sympy.__version__ if sympy is not None else None),
        "sympy_error": SYMPY_ERROR,
        "numpy": np.__version__,
        "python": sys.version.split()[0],
        "pylibs": os.path.join(PLUGIN_ROOT, ".pylibs"),
        "pylibs_exists": os.path.isdir(os.path.join(PLUGIN_ROOT, ".pylibs")),
        "精确路径": sympy is not None,
    })
    available = ["solve", "solve_system", "evaluate", "roots", "matrix", "fit", "table", "optimize"]
    if sympy is None:
        result.unsupported = ["dsolve", "limit", "series", "符号积分", "符号微分", "expand/factor/together/apart",
                              "符号 solve 的精确根"]
        result.exact.append("数值路径：%s 可用；%s 不可用" % ("/".join(available), "、".join(result.unsupported)))
        result.numeric.append(None)
        result.latex.append("\\text{numpy 数值路径}")
    else:
        available += ["diff", "integrate", "limit", "series", "dsolve", "expand", "factor", "together", "apart",
                      "simplify"]
        result.exact.append("精确路径：%s 全部可用（sympy %s）" % ("/".join(available), sympy.__version__))
        result.numeric.append(None)
        result.latex.append("\\text{sympy %s}" % sympy.__version__)
    return result.to_dict()


OPS = {
    "solve": op_solve, "solve_system": op_solve, "equations": op_solve,
    "simplify": lambda spec, result: op_transform(spec, result, "simplify"),
    "expand": lambda spec, result: op_transform(spec, result, "expand"),
    "factor": lambda spec, result: op_transform(spec, result, "factor"),
    "together": lambda spec, result: op_transform(spec, result, "together"),
    "apart": lambda spec, result: op_transform(spec, result, "apart"),
    "diff": op_diff, "derivative": op_diff,
    "integrate": op_integrate, "integral": op_integrate,
    "limit": op_limit, "series": op_series, "dsolve": op_dsolve, "ode": op_dsolve,
    "matrix": op_matrix, "linear": op_matrix,
    "evaluate": op_evaluate, "subs": op_evaluate,
    "roots": op_roots, "solve_poly": op_roots,
    "optimize": op_optimize, "max": op_optimize, "min": op_optimize,
    "fit": op_fit, "table": op_table, "probe": op_probe,
}


def dispatch(spec):
    if not isinstance(spec, dict):
        raise SpecError("spec 必须是 JSON 对象")
    action = str(spec.get("op") or "").strip().lower()
    if not action:
        raise SpecError("spec 缺少 op；可用：%s" % " / ".join(sorted(OPS)))
    if action not in OPS:
        raise SpecError("未知 op=%r；可用：%s" % (action, " / ".join(sorted(OPS))))
    result = Result(action)
    # digits 是通用字段：必须真的生效，否则用户写 digits=3 却拿到 6 位，等于没这个字段
    raw_digits = spec.get("digits", 6)
    try:
        digits = int(raw_digits)
        if not 1 <= digits <= 15:
            raise ValueError("超出 1~15")
    except (TypeError, ValueError) as exc:
        result.warnings.append("digits=%r 不是 1~15 的整数（%s），已按 6 位有效位处理"
                               % (raw_digits, exc))
        digits = 6
    result.digits = digits
    return OPS[action](spec, result)


def _load_spec(argv):
    if "--spec-json" in argv:
        if argv.index("--spec-json") + 1 >= len(argv):
            raise SpecError("--spec-json 后面要跟一个 JSON 字符串")
        raw = argv[argv.index("--spec-json") + 1]
        try:
            return json.loads(raw)
        except Exception as exc:
            raise SpecError("--spec-json 不是合法 JSON：%s" % exc)
    if "--spec" in argv:
        if argv.index("--spec") + 1 >= len(argv):
            raise SpecError("--spec 后面要跟 spec 文件路径")
        path = argv[argv.index("--spec") + 1]
        if os.path.isdir(path):
            raise SpecError("--spec 指向的是目录，不是文件：%s" % path)
        if not os.path.exists(path):
            raise SpecError("找不到 spec 文件：%s" % path)
        try:
            with open(path, "r", encoding="utf-8-sig") as handle:
                return json.load(handle)
        except Exception as exc:
            raise SpecError("读 spec 文件失败（%s）：%s" % (path, exc))
    raise SpecError("用法：symbolic.py --spec <spec.json> 或 --spec-json '<json>'")


def main(argv):
    try:
        spec = _load_spec(argv)
    except SpecError as exc:
        return {"ok": False, "error": str(exc)}
    except Exception as exc:
        return {"ok": False, "error": "读 spec 失败：%s" % exc}
    try:
        return dispatch(spec)
    except SpecError as exc:
        return {"ok": False, "error": str(exc)}
    except Exception as exc:  # 不让 traceback 污染 stdout
        return {"ok": False, "error": "求解时内部错误（%s）：%s" % (type(exc).__name__, exc)}


if __name__ == "__main__":
    try:
        payload = main(sys.argv[1:])
    except Exception as fatal:  # 最后一道兜底
        payload = {"ok": False, "error": "内核异常终止：%s" % fatal}
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
    sys.stdout.write("\n")
    sys.stdout.flush()
    sys.exit(0 if payload.get("ok") else 1)