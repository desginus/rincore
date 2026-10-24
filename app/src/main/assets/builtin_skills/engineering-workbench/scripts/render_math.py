#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""render_math.py —— 把「我读出来的题面」渲染成图，和原图并排，供 AI 与学生肉眼核对。

这是本项目最关键的自检手段：左=原图（裁切后等比缩放，带「原图」标题），
右=转写文本渲染（带「转写」标题），底部一条差异提示区（数值清单用醒目颜色，
方便逐个比对）。**学科无关**：中文/英文长文、任意科目的公式、表格描述都能排。

渲染器链（会在输出的 renderer 字段如实报告用了哪个）：
    1. matplotlib —— 用 mathtext 渲染 $...$ 公式（import 失败就跳过，运行时降级）
    2. pillow-text —— 用 Pillow 画 Unicode 文本，$...$ 去定界符，\\frac{a}{b} → a/b
    用 spec.renderer 可以钉死走哪条："auto"（默认）/ "matplotlib" / "pillow"。

版式降级（都会写进 warnings，不悄悄改变）：
    内容过高时：① 逐步缩小字号（下限 13）→ ② 转写面板分 2 列 → ③ 分 3 列；
    再放不下就如实报告实际高度（绝不截断内容）。

CLI（stdout 只能是 JSON）：
    <python> render_math.py --spec <spec.json>
    <python> render_math.py --spec-json '<inline json>'

spec 关键字段：
    original        原图路径（必填）
    transcription   转写文本（可用 $...$ 写 LaTeX；与 sections 至少给一个）
    out             对照图输出路径（必填）
    title           可选：整张对照图的大标题
    sections        可选：[{heading, lines:[...]}]，把「已知/求/图要素/存疑」分块排版
    highlight       可选：["10 kN", "160 MPa"]，在转写面板里加粗+换色（不画下划线，避免压在字上）。
                    高亮按"原文子串"匹配；用 mathtext 渲染成图的 $...$ 公式内部不做高亮，
                    没有 matplotlib 时公式退化成纯文本，高亮照常生效。
    crop_original   可选：[l,t,r,b]，对原图裁切（0–1 归一化或像素）
    font_size       可选：转写面板字号（默认 22）
    width           可选：画布宽度（默认 1600）
    layout          可选：auto / stack / side
    renderer        可选：auto / matplotlib / pillow

输出：{"ok":true,"out","width","height","renderer","formula_count","substitutions",
      "warnings","layout","columns","value_count","font","matplotlib_available","bytes"}
失败：{"ok":false,"error":"中文原因"}，退出码 1。
"""

import json
import math as _math
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image, ImageDraw, ImageFont
except Exception as exc:  # pragma: no cover
    sys.stdout.write(json.dumps({
        "ok": False,
        "error": "本机 Python 缺少 Pillow，无法渲染对照图：%s（请用内置 Python 运行）" % exc,
    }, ensure_ascii=False) + "\n")
    raise SystemExit(1)


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
    try:
        sys.stderr.write("[render_math] %s\n" % message)
    except Exception:
        pass


# --------------------------------------------------------------------------- #
# 中文字体
# --------------------------------------------------------------------------- #
FONT_FILES = ["msyh.ttc", "msyhl.ttc", "simhei.ttf", "simsun.ttc", "Deng.ttf", "msyhbd.ttc"]
BOLD_FILES = ["msyhbd.ttc", "simhei.ttf", "msyh.ttc", "simsun.ttc"]


def font_dirs():
    dirs = []
    windir = os.environ.get("WINDIR") or "C:\\Windows"
    dirs.append(os.path.join(windir, "Fonts"))
    dirs.append("C:\\Windows\\Fonts")
    local = os.environ.get("LOCALAPPDATA")
    if local:
        dirs.append(os.path.join(local, "Microsoft", "Windows", "Fonts"))
    dirs.extend(["/usr/share/fonts", "/usr/local/share/fonts",
                 os.path.expanduser("~/.fonts")])
    return dirs


def find_font_file(names):
    for directory in font_dirs():
        if not os.path.isdir(directory):
            continue
        for name in names:
            candidate = os.path.join(directory, name)
            if os.path.isfile(candidate):
                return candidate
    return None


def load_font(path, size):
    """ttc 需要 index=0；失败再退回默认字体。"""
    if path:
        try:
            return ImageFont.truetype(path, size, index=0), True
        except Exception as exc:
            log("加载字体失败 %s：%s" % (path, exc))
            try:
                return ImageFont.truetype(path, size), True
            except Exception:
                pass
    return ImageFont.load_default(), False


# --------------------------------------------------------------------------- #
# 符号安全：字体可能缺字形的字符做替换（记录到 substitutions）
# --------------------------------------------------------------------------- #
SUBSTITUTION_TABLE = [
    ("\u22c5", "\u00b7"),        # ⋅ → ·  点乘
    ("\u2219", "\u00b7"),        # ∙ → ·
    ("\u2212", "-"),             # − 真减号 → 连字符（工程题里更安全）
    ("\u207b\u00b9", "^-1"),     # ⁻¹
    ("\u207b\u00b2", "^-2"),     # ⁻²
    ("\u207b\u00b3", "^-3"),     # ⁻³
    ("\u207b", "^-"),
    ("\u207a", "^+"),
    ("\u207f", "^n"),
    ("\u2080", "_0"), ("\u2081", "_1"), ("\u2082", "_2"), ("\u2083", "_3"),
    ("\u2084", "_4"), ("\u2085", "_5"), ("\u2086", "_6"), ("\u2087", "_7"),
    ("\u2088", "_8"), ("\u2089", "_9"),
    ("\u33a1", "m\u00b2"),       # ㎡
    ("\u2103", "\u00b0C"),       # ℃
    ("\u2109", "\u00b0F"),       # ℉
    ("\u339f", "mm\u00b2"), ("\u33a0", "cm\u00b2"), ("\u33a2", "km\u00b2"),
    ("\u33a5", "m\u00b3"), ("\u339c", "mm"), ("\u339d", "cm"), ("\u339e", "km"),
    ("\u338f", "kg"),
    ("\u2013", "-"),             # – 短破折号
    ("\u2011", "-"),             # ‑ 不换行连字符
    ("\u00a0", " "),             # 不换行空格
    ("\u2009", " "), ("\u200a", " "), ("\u2002", " "), ("\u2003", " "),
]


def apply_substitutions(text):
    out = text
    used = []
    for old, new in SUBSTITUTION_TABLE:
        if old in out:
            out = out.replace(old, new)
            label = "%s→%s" % (old, new)
            if label not in used:
                used.append(label)
    return out, used


def collect_latex_substitutions(text, limit=40):
    """如实记录 LaTeX → 纯文本层面的替换（$ 定界符、希腊字母宏等）。"""
    found = []
    if "$$" in text:
        found.append("$$→（去掉公式定界符）")
    if re.search(r"(?<!\$)\$(?!\$)", text):
        found.append("$…$→（去掉公式定界符）")
    macros = []
    for match in re.finditer(r"\\([A-Za-z]+)", text):
        name = match.group(1)
        if name in GREEK:
            macros.append("\\%s→%s" % (name, GREEK[name]))
        elif name in SYMBOL_MACROS and SYMBOL_MACROS[name]:
            macros.append("\\%s→%s" % (name, SYMBOL_MACROS[name]))
        elif name in ("frac", "dfrac", "tfrac"):
            macros.append("\\%s{a}{b}→a/b（纯文本形态）" % name)
        elif name == "sqrt":
            macros.append("\\sqrt{x}→√x")
    for label in macros:
        if label not in found:
            found.append(label)
        if len(found) >= limit:
            break
    return found[:limit]


# --------------------------------------------------------------------------- #
# 文本清洗（LaTeX → 可读纯文本，供 pillow-text / 清单使用）
# --------------------------------------------------------------------------- #
GREEK = {
    "alpha": "\u03b1", "beta": "\u03b2", "gamma": "\u03b3", "delta": "\u03b4",
    "epsilon": "\u03b5", "varepsilon": "\u03b5", "zeta": "\u03b6", "eta": "\u03b7",
    "theta": "\u03b8", "vartheta": "\u03b8", "iota": "\u03b9", "kappa": "\u03ba",
    "lambda": "\u03bb", "mu": "\u03bc", "nu": "\u03bd", "xi": "\u03be",
    "pi": "\u03c0", "rho": "\u03c1", "sigma": "\u03c3", "tau": "\u03c4",
    "upsilon": "\u03c5", "phi": "\u03c6", "varphi": "\u03c6", "chi": "\u03c7",
    "psi": "\u03c8", "omega": "\u03c9",
    "Gamma": "\u0393", "Delta": "\u0394", "Theta": "\u0398", "Lambda": "\u039b",
    "Xi": "\u039e", "Pi": "\u03a0", "Sigma": "\u03a3", "Phi": "\u03a6",
    "Psi": "\u03a8", "Omega": "\u03a9",
}

SYMBOL_MACROS = {
    "cdot": "\u00b7", "times": "\u00d7", "div": "\u00f7", "pm": "\u00b1",
    "mp": "\u2213", "le": "\u2264", "leq": "\u2264", "ge": "\u2265",
    "geq": "\u2265", "neq": "\u2260", "approx": "\u2248", "equiv": "\u2261",
    "infty": "\u221e", "to": "\u2192", "rightarrow": "\u2192",
    "longrightarrow": "\u2192", "leftarrow": "\u2190", "Rightarrow": "\u21d2",
    "sum": "\u03a3", "prod": "\u03a0", "int": "\u222b", "partial": "\u2202",
    "propto": "\u221d", "perp": "\u22a5", "parallel": "\u2225",
    "angle": "\u2220", "degree": "\u00b0", "circ": "\u00b0",
    "sim": "~", "simeq": "\u2243", "cong": "\u2245", "ll": "\u226a",
    "gg": "\u226b", "in": "\u2208", "notin": "\u2209", "subset": "\u2282",
    "cup": "\u222a", "cap": "\u2229", "nabla": "\u2207", "forall": "\u2200",
    "exists": "\u2203", "max": "max", "min": "min", "log": "log", "ln": "ln",
    "sin": "sin", "cos": "cos", "tan": "tan", "lim": "lim", "exp": "exp",
    "prime": "\u2032", "ldots": "\u2026", "cdots": "\u00b7\u00b7\u00b7",
    "dots": "\u2026", "quad": " ", "qquad": "  ", "space": " ",
    "left": "", "right": "", "displaystyle": "", "limits": "",
    "nolimits": "", "big": "", "Big": "", "bigg": "", "Bigg": "",
    "text": None, "mathrm": None, "mathbf": None, "mathit": None,
    "mathsf": None, "operatorname": None, "boldsymbol": None,
    "vec": None, "bar": None, "hat": None, "dot": None, "overline": None,
    "underline": None, "widetilde": None, "widehat": None,
}

SPACING_MACROS = [("\\,", ""), ("\\;", ""), ("\\!", ""), ("\\ ", " "),
                  ("\\:", ""), ("\\/", ""), ("\\%", "%"), ("\\&", "&"),
                  ("\\#", "#"), ("\\_", "_"), ("\\{", "{"), ("\\}", "}")]


def _read_group(text, index):
    """读取 {…} 组（支持嵌套）；index 必须指向 '{'。"""
    depth = 0
    start = index
    while index < len(text):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:index], index + 1
        index += 1
    return text[start + 1:], len(text)


def latex_to_plain(text):
    """把一段 LaTeX 片段转成可读纯文本（用于 Pillow 渲染与数值清单）。"""
    if not text:
        return ""
    out = text
    # 先处理递归结构：\text{…} 去括号 / \frac / \sqrt / ^ / _
    for _ in range(6):
        before = out
        out = _unwrap_macros(out, UNWRAP_MACROS)
        out = _convert_frac(out)
        out = _convert_sqrt(out)
        out = _convert_scripts(out)
        if out == before:
            break
    # 宏替换
    def macro_repl(match):
        name = match.group(1)
        if name in SYMBOL_MACROS:
            value = SYMBOL_MACROS[name]
            return "" if value is None else value
        if name in GREEK:
            return GREEK[name]
        # \muF / \sigmaF 这类"宏名紧跟字母"的写法：贪婪匹配会把 muF 当成一个宏，
        # 于是逐级回退到已知前缀（\mu），把剩下的字母原样留下。
        for length in range(len(name) - 1, 1, -1):
            prefix = name[:length]
            if prefix in GREEK:
                return GREEK[prefix] + name[length:]
            if prefix in SYMBOL_MACROS and SYMBOL_MACROS[prefix]:
                return SYMBOL_MACROS[prefix] + name[length:]
        return name  # 未知宏：去掉反斜杠，保留名字

    out = re.sub(r"\\([A-Za-z]+)", macro_repl, out)
    for old, new in SPACING_MACROS:
        out = out.replace(old, new)
    out = out.replace("\\\\", "\n").replace("$$", "")
    out = out.replace("$", "")
    out = re.sub(r"[ \t]{2,}", " ", out)
    return out.strip()


def _convert_frac(text):
    pattern = re.compile(r"\\[dt]?frac\s*\{")
    while True:
        match = pattern.search(text)
        if not match:
            return text
        try:
            numerator, after_num = _read_group(text, match.end() - 1)
            if after_num >= len(text) or text[after_num] != "{":
                return text
            denominator, after_den = _read_group(text, after_num)
        except Exception:
            return text
        simple = re.fullmatch(r"[A-Za-z0-9.+\-]+", numerator) and \
            re.fullmatch(r"[A-Za-z0-9.+\-]+", denominator)
        replacement = "%s/%s" % (numerator, denominator) if simple else \
            "(%s)/(%s)" % (numerator, denominator)
        text = text[:match.start()] + replacement + text[after_den:]


def _convert_sqrt(text):
    pattern = re.compile(r"\\sqrt\s*(\[[^\]]*\])?\s*\{")
    while True:
        match = pattern.search(text)
        if not match:
            return text
        inner, after = _read_group(text, match.end() - 1)
        simple = bool(re.fullmatch(r"[A-Za-z0-9.]+", inner))
        replacement = ("\u221a" + inner) if simple else ("\u221a(" + inner + ")")
        text = text[:match.start()] + replacement + text[after:]


def _convert_scripts(text):
    """^ / _ 的 {…} 参数 → 可读形式。

    注意：这里不能用 re.sub —— 匹配只覆盖 "_" 和 "{" 两个字符，而组内容在匹配之外，
    用 re.sub 会剩下多余的 "}"（曾把 M_{\\max} 变成 M_(max)max}），必须手工切片。
    """
    pattern = re.compile(r"([\^_])\s*\{")
    out = text
    for _ in range(20):
        match = pattern.search(out)
        if not match:
            return out
        start = match.end() - 1
        if start >= len(out) or out[start] != "{":
            return out
        body, after = _read_group(out, start)
        body = body.strip()
        if len(body) == 1 and re.fullmatch(r"[A-Za-z0-9]", body):
            replacement = match.group(1) + body
        else:
            replacement = "%s(%s)" % (match.group(1), body)
        out = out[:match.start()] + replacement + out[after:]
    return out


def _unwrap_macros(text, pattern):
    """把 \\text{…} / \\mathrm{…} / \\vec{…} 之类连花括号一起去掉，只留内容。"""
    out = text
    for _ in range(40):
        match = pattern.search(out)
        if not match:
            return out
        start = match.end() - 1
        if start >= len(out) or out[start] != "{":
            return out
        inner, after = _read_group(out, start)
        out = out[:match.start()] + inner + out[after:]
    return out


UNWRAP_MACROS = re.compile(
    r"\\(?:text|textrm|textbf|textit|textsf|texttt|mathrm|mathbf|mathit|mathsf|mathtt"
    r"|mathbb|mathcal|operatorname|boldsymbol|bm|vec|bar|hat|dot|ddot|tilde"
    r"|widetilde|widehat|overline|underline|overbrace|underbrace|si|SI)\s*\{")


# --------------------------------------------------------------------------- #
# 段落切分：文本段 / 公式段
# --------------------------------------------------------------------------- #
def split_segments(text):
    """按 $…$ / $$…$$ 切成 [("text"|"math", 内容)]。"""
    segments = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if char == "$":
            delimiter = "$$" if text.startswith("$$", index) else "$"
            end = text.find(delimiter, index + len(delimiter))
            if end == -1:
                segments.append(("text", text[index:]))
                break
            inner = text[index + len(delimiter):end].strip()
            if inner:
                segments.append(("math", inner))
            index = end + len(delimiter)
        else:
            next_dollar = text.find("$", index)
            if next_dollar == -1:
                next_dollar = length
            segments.append(("text", text[index:next_dollar]))
            index = next_dollar
    if not segments:
        segments.append(("text", text))
    return segments


def extract_formulas(segments):
    return [latex_to_plain(content) for kind, content in segments if kind == "math"]


# --------------------------------------------------------------------------- #
# matplotlib mathtext 渲染器（可选，运行时探测）
# --------------------------------------------------------------------------- #
class MathImageRenderer:
    """用 matplotlib mathtext 把一段 LaTeX 渲染成透明 PNG。

    只在**公式里没有中文**时使用 mathtext（中文交给 Pillow 的雅黑，避免方框）。
    """

    RENDER_FONTSIZE = 40

    def __init__(self, font_names):
        self.ok = False
        self.reason = None
        self._cache = {}
        self._scale = None
        self._ref_height = None
        try:
            import matplotlib
            matplotlib.use("Agg", force=True)
            from matplotlib.figure import Figure
            from matplotlib.backends.backend_agg import FigureCanvasAgg

            self._Figure = Figure
            self._Canvas = FigureCanvasAgg
            rc = matplotlib.rcParams
            rc["font.sans-serif"] = list(font_names) + ["DejaVu Sans"]
            rc["font.family"] = "sans-serif"
            rc["axes.unicode_minus"] = False
            try:
                rc["mathtext.fontset"] = "stix"
            except Exception:
                pass
            self.ok = True
        except Exception as exc:
            self.reason = str(exc)
            self.ok = False

    # -- 内部 ------------------------------------------------------------- #
    def _render_raw(self, latex):
        figure = self._Figure(figsize=(10, 1.6), dpi=300)
        figure.patch.set_alpha(0.0)
        figure.text(0.02, 0.5, "$%s$" % latex, fontsize=self.RENDER_FONTSIZE,
                    va="center", ha="left", color="#000000")
        canvas = self._Canvas(figure)
        canvas.draw()
        # 注意：matplotlib>=3.11 的 buffer_rgba() 是三维 memoryview，len() 只给高度，
        # 必须先 bytes() 展平再判断长度，否则会把整幅图错误地重排成小图。
        data = bytes(canvas.buffer_rgba())
        width, height = canvas.get_width_height()
        if len(data) != width * height * 4:
            total = len(data) // 4
            height = 1
            for candidate in range(int(_math.isqrt(total)), 0, -1):
                if total % candidate == 0:
                    width, height = total // candidate, candidate
                    break
        image = Image.frombuffer("RGBA", (width, height), data, "raw", "RGBA", 0, 1)
        bbox = image.getchannel("A").getbbox()
        if not bbox:
            raise ValueError("mathtext 渲染结果为空")
        return image.crop(bbox)

    def _reference_height(self):
        if self._ref_height is None:
            self._ref_height = self._render_raw("x").height
        return self._ref_height

    def scale_for(self, font_size):
        """让公式里的变量 x 高度 ≈ 正文字号的 0.52，保持分数等结构自然比例。"""
        return (font_size * 0.52) / max(1.0, float(self._reference_height()))

    def render(self, latex, font_size):
        """返回 RGBA 图（已按 font_size 缩放到合适大小）；失败返回 None。"""
        if not self.ok:
            return None
        if re.search(r"[\u3000-\u9fff\uff00-\uffef]", latex):
            return None  # 含中文的公式交给 Pillow
        cache_key = (latex, round(font_size, 2))
        if cache_key in self._cache:
            return self._cache[cache_key]
        try:
            raw = self._render_raw(latex)
            scale = self.scale_for(font_size)
            target_w = max(1, int(round(raw.width * scale)))
            target_h = max(1, int(round(raw.height * scale)))
            image = raw.resize((target_w, target_h), Image.LANCZOS)
        except Exception as exc:
            log("mathtext 渲染失败（%s）：%s" % (latex, exc))
            image = None
        self._cache[cache_key] = image
        return image


# --------------------------------------------------------------------------- #
# 版式常量
# --------------------------------------------------------------------------- #
BG = (255, 255, 255)
INK = (26, 26, 26)
TITLE_BG = (232, 238, 248)
TITLE_INK = (31, 56, 100)
BORDER = (166, 178, 196)
PANEL_BG = (252, 252, 250)
VALUE_INK = (192, 0, 0)
HIGHLIGHT_INK = (176, 0, 96)      # 关键数值/单位：加粗 + 换色（不画下划线，避免压在字上）
HEADING_INK = (31, 78, 121)
FORMULA_INK = (31, 78, 121)
FOOT_INK = (110, 110, 110)
MARGIN = 26
GAP = 26
TITLE_H = 46
PAD = 16
MAX_HEIGHT = 6000
COL_GAP = 20
MIN_COL_WIDTH = 240
MAX_COLUMNS = 3
MIN_FONT_SIZE = 13
HEADING_TEXT = "转写（AI 读出来的题面，请逐项核对）"
HEADING_TEXT_SIDE = "转写（AI 读出来的题面）"


def tokenize(text):
    """CJK / 全角按字切，拉丁按词切，便于换行。"""
    tokens = []
    buffer = ""
    for char in text:
        if char == "\n":
            if buffer:
                tokens.append(buffer)
                buffer = ""
            tokens.append("\n")
        elif char == " ":
            if buffer:
                tokens.append(buffer)
                buffer = ""
            tokens.append(" ")
        elif ord(char) > 0x2E7F:
            if buffer:
                tokens.append(buffer)
                buffer = ""
            tokens.append(char)
        else:
            buffer += char
    if buffer:
        tokens.append(buffer)
    return tokens


def split_long_token(token, font, max_width):
    """超宽的拉丁长串硬切。"""
    pieces = []
    current = ""
    for char in token:
        if font.getlength(current + char) > max_width and current:
            pieces.append(current)
            current = char
        else:
            current += char
    if current:
        pieces.append(current)
    return pieces


def highlight_spans(text, highlights):
    """把一段纯文本按 highlight 词切成 [(片段, 是否高亮)]，用于加粗/换色。"""
    if not text:
        return []
    if not highlights:
        return [(text, False)]
    intervals = []
    for word in highlights:
        if not word:
            continue
        start = text.find(word)
        while start != -1:
            intervals.append((start, start + len(word)))
            start = text.find(word, start + len(word))
    if not intervals:
        return [(text, False)]
    intervals.sort()
    merged = []
    for start, end in intervals:
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    spans = []
    cursor = 0
    for start, end in merged:
        if start > cursor:
            spans.append((text[cursor:start], False))
        spans.append((text[start:end], True))
        cursor = end
    if cursor < len(text):
        spans.append((text[cursor:], False))
    return spans


# --------------------------------------------------------------------------- #
# 正文渲染器：文本段 + 公式段（支持高亮加粗）
# --------------------------------------------------------------------------- #
class BodyRenderer:
    def __init__(self, font, bold_font, font_size, math_renderer, canvas=None,
                 highlights=None):
        self.font = font
        self.bold_font = bold_font or font
        self.font_size = font_size
        self.math = math_renderer
        self.canvas = canvas
        self.highlights = list(highlights or [])
        self.used_mathtext = False

    def plain(self, segment):
        kind, content = segment
        if kind == "text":
            return content
        return latex_to_plain(content)

    def font_for(self, bold):
        return self.bold_font if bold else self.font

    def text_height(self):
        return int(self.font_size * 1.5)

    def wrap(self, segments, max_width):
        """把段落切成行；每行是 [(kind, 载荷, 宽, 高)]。

        文本载荷是 (token, 是否高亮)；公式载荷是 LaTeX（有 mathtext 时按图贴，否则退化成
        纯文本并**照常参与高亮**，这样 highlight 里的关键数值不会因为被 $…$ 包住就漏掉）。
        """
        lines = []
        current = []
        current_w = 0

        def flush():
            nonlocal current, current_w
            lines.append(current)
            current = []
            current_w = 0

        for kind, content in segments:
            newline_groups = [content] if kind == "math" else content.split("\n")
            for group_index, group in enumerate(newline_groups):
                if group_index > 0:
                    flush()
                if kind == "math":
                    image = self.math.render(group, self.font_size) if self.math else None
                    if image is not None:
                        self.used_mathtext = True
                        width = image.width
                        height = max(image.height, int(self.font_size * 1.2))
                        if current and current_w + width > max_width:
                            flush()
                        current.append(("math", group, width, height))
                        current_w += width
                        continue
                    group = latex_to_plain(group)  # 没有 mathtext：退化成纯文本继续排
                for span_text, is_highlight in highlight_spans(group, self.highlights):
                    span_font = self.font_for(is_highlight)
                    for token in tokenize(span_text):
                        if token == "\n":
                            flush()
                            continue
                        if token == " ":
                            if current:
                                width = span_font.getlength(" ")
                                current.append(("text", (" ", is_highlight), width,
                                                self.text_height()))
                                current_w += width
                            continue
                        width = span_font.getlength(token)
                        if width > max_width and not current:
                            for piece in split_long_token(token, span_font, max_width):
                                piece_w = span_font.getlength(piece)
                                current.append(("text", (piece, is_highlight), piece_w,
                                                self.text_height()))
                                flush()
                            continue
                        if current and current_w + width > max_width:
                            flush()
                        current.append(("text", (token, is_highlight), width,
                                        self.text_height()))
                        current_w += width
        if current or not lines:
            lines.append(current)
        return lines

    def line_height(self, line):
        height = self.text_height()
        for _kind, _payload, _width, seg_height in line:
            height = max(height, seg_height)
        return height

    def draw_line(self, draw, x, top, line, color, bold=False, font_override=None):
        cursor = x
        height = self.line_height(line)
        for kind, payload, width, seg_height in line:
            if kind == "math":
                image = self.math.render(payload, self.font_size) if self.math else None
                if image is not None and self.canvas is not None:
                    offset = top + max(0, (height - image.height) // 2)
                    self.canvas.paste(image, (int(cursor), int(offset)), image)
                    cursor += width
                    continue
                text = latex_to_plain(payload)
                use_font = font_override or self.font_for(bold)
                draw.text((cursor, top), text, font=use_font, fill=color)
                cursor += width
                continue
            token, is_highlight = payload
            use_font = self.font_for(is_highlight or bold)
            fill = HIGHLIGHT_INK if is_highlight else color
            draw.text((cursor, top), token, font=use_font, fill=fill)
            cursor += width
        return height


# --------------------------------------------------------------------------- #
# 富文本行（底部署值清单：一行里多种颜色）
# --------------------------------------------------------------------------- #
def wrap_rich(parts, font, max_width):
    """parts = [(text, color)] → 行 = [(text, color, width)]"""
    lines = []
    current = []
    current_w = 0.0
    for text, color in parts:
        for token in tokenize(text):
            if token == "\n":
                lines.append(current)
                current = []
                current_w = 0.0
                continue
            if token == " ":
                if current:
                    width = font.getlength(" ")
                    current.append((" ", color, width))
                    current_w += width
                continue
            width = font.getlength(token)
            if width > max_width and not current:
                for piece in split_long_token(token, font, max_width):
                    piece_w = font.getlength(piece)
                    current.append((piece, color, piece_w))
                    lines.append(current)
                    current = []
                    current_w = 0.0
                continue
            if current and current_w + width > max_width:
                lines.append(current)
                current = []
                current_w = 0.0
            current.append((token, color, width))
            current_w += width
    if current or not lines:
        lines.append(current)
    return lines


def draw_rich_lines(draw, x, top, lines, font, line_h):
    y = top
    for line in lines:
        cursor = x
        for text, color, width in line:
            draw.text((cursor, y), text, font=font, fill=color)
            cursor += width
        y += line_h
    return y


# --------------------------------------------------------------------------- #
# 数值 / 公式清单
# --------------------------------------------------------------------------- #
UNIT_PATTERN = ("kN/m", "kN\u00b7m", "N\u00b7m", "kN", "Nm", "MPa", "GPa", "kPa",
                "Pa", "mm", "cm", "km", "kg", "mg", "g", "m", "s", "min", "h",
                "rad", "\u00b0", "\u2103", "%")
UNIT_RE = re.compile(r"^\s*(" + "|".join(re.escape(unit) for unit in UNIT_PATTERN) + r")\b")


def collect_values(plain_text, limit=24):
    values = []
    for match in re.finditer(r"\d+(?:\.\d+)?", plain_text):
        number = match.group(0)
        tail = plain_text[match.end():match.end() + 12]
        unit_match = UNIT_RE.match(tail)
        unit = unit_match.group(1) if unit_match else ""
        if unit_match:
            # 把 cm^3 / m^2 这样的幂次并进单位，避免只显示 "120 cm"
            exponent = re.match(r"\^\{?(\d+)\}?", tail[unit_match.end():])
            if exponent:
                unit += "^" + exponent.group(1)
        label = (number + " " + unit).strip()
        if label not in values:
            values.append(label)
        if len(values) >= limit:
            break
    return values


# --------------------------------------------------------------------------- #
# 参数
# --------------------------------------------------------------------------- #
def load_spec(argv):
    spec_path = None
    spec_json = None
    positional = []
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
        elif arg.startswith("-") and arg != "-":
            raise ValueError("未知参数 %r；只支持 --spec / --spec-json" % arg)
        else:
            positional.append(arg)
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
    if positional:
        spec.setdefault("original", positional[0])
        if len(positional) > 1:
            spec.setdefault("out", positional[1])
    return spec


def parse_box(raw, width, height, name):
    if not isinstance(raw, (list, tuple)) or len(raw) != 4:
        raise ValueError("%s 必须是 [左, 上, 右, 下] 四个数字（归一化或像素）" % name)
    try:
        values = [float(item) for item in raw]
    except Exception:
        raise ValueError("%s 的四个分量必须是数字" % name)
    if any(abs(value) > 1.0 for value in values):
        left, top, right, bottom = [int(round(value)) for value in values]
    else:
        left = int(round(values[0] * width))
        top = int(round(values[1] * height))
        right = int(round(values[2] * width))
        bottom = int(round(values[3] * height))
    left = max(0, min(left, width))
    top = max(0, min(top, height))
    right = max(0, min(right, width))
    bottom = max(0, min(bottom, height))
    if right - left < 8 or bottom - top < 8:
        raise ValueError("%s 裁切结果为空或过小" % name)
    return (left, top, right, bottom)


# --------------------------------------------------------------------------- #
# 段落整理：transcription / sections → [{kind, text}]
# --------------------------------------------------------------------------- #
def build_paragraphs(transcription, sections):
    """把转写文本与分块内容整理成 [{kind: heading|body|spacer, text}]。"""
    paragraphs = []

    def add_transcription(text):
        for chunk in str(text).split("\n"):
            if chunk.strip():
                paragraphs.append({"kind": "body", "text": chunk})

    if transcription and transcription.strip():
        add_transcription(transcription)

    if sections:
        if not isinstance(sections, (list, tuple)):
            raise ValueError("sections 必须是数组，元素形如 {\"heading\": \"已知\", \"lines\": [...]}")
        if paragraphs:
            paragraphs.append({"kind": "spacer", "text": ""})
        for index, section in enumerate(sections):
            if not isinstance(section, dict):
                raise ValueError("sections[%d] 必须是对象，例如 {\"heading\": \"已知\", "
                                 "\"lines\": [\"P = 10 kN\"]}" % index)
            heading = section.get("heading")
            if heading:
                paragraphs.append({"kind": "heading", "text": str(heading)})
            lines = section.get("lines")
            if lines is None:
                lines = []
            if isinstance(lines, str):
                lines = [lines]
            if not isinstance(lines, (list, tuple)):
                raise ValueError("sections[%d].lines 必须是字符串或字符串数组" % index)
            for line in lines:
                if str(line).strip():
                    paragraphs.append({"kind": "body", "text": str(line)})
            paragraphs.append({"kind": "spacer", "text": ""})

    while paragraphs and paragraphs[-1]["kind"] == "spacer":
        paragraphs.pop()
    if not paragraphs:
        paragraphs = [{"kind": "body", "text": ""}]
    return paragraphs


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def render(spec):
    warnings = []
    substitutions = []

    original = spec.get("original") or spec.get("src")
    if not original:
        raise ValueError("spec 缺少 original（原图路径）")
    if not os.path.isfile(original):
        raise ValueError("原图不存在：%s" % original)

    sections = spec.get("sections")
    transcription = spec.get("transcription")
    if transcription is None:
        transcription = spec.get("text")
    if transcription is None and not sections:
        raise ValueError("spec 缺少 transcription（转写文本）或 sections（分块内容）")
    if transcription is not None and not isinstance(transcription, str):
        raise ValueError("transcription 必须是字符串")

    out = spec.get("out")
    if not out:
        raise ValueError("spec 缺少 out（对照图输出路径）")

    title = spec.get("title")
    if title is not None and not isinstance(title, str):
        raise ValueError("title 必须是字符串")

    highlights = spec.get("highlight")
    if highlights is None:
        highlights = []
    elif isinstance(highlights, str):
        highlights = [highlights]
    if not isinstance(highlights, (list, tuple)):
        raise ValueError("highlight 必须是字符串或字符串数组，例如 [\"10 kN\", \"160 MPa\"]")
    highlights = [str(item) for item in highlights if str(item).strip()]

    try:
        width = int(spec.get("width") or 1600)
    except Exception:
        raise ValueError("width 必须是整数")
    if width < 640:
        raise ValueError("width 太小（%s），至少 640" % width)
    try:
        font_size = float(spec.get("font_size") or 22)
    except Exception:
        raise ValueError("font_size 必须是数字")
    if font_size < 9:
        raise ValueError("font_size 太小（%s），至少 9" % font_size)

    layout = str(spec.get("layout") or "auto").lower()
    if layout not in ("auto", "stack", "side", "stacked", "side-by-side"):
        raise ValueError("layout 只能是 auto / stack / side（收到 %r）" % spec.get("layout"))

    renderer_pref = str(spec.get("renderer") or "auto").lower()
    if renderer_pref in ("auto", ""):
        renderer_pref = "auto"
    elif renderer_pref in ("matplotlib", "mpl", "math"):
        renderer_pref = "matplotlib"
    elif renderer_pref in ("pillow", "pillow-text", "text"):
        renderer_pref = "pillow"
    else:
        raise ValueError("renderer 只能是 auto / matplotlib / pillow（收到 %r）"
                         % spec.get("renderer"))

    try:
        with Image.open(original) as handle:
            handle.load()
            source = handle.convert("RGB")
    except Exception as exc:
        raise ValueError("无法读取原图 %s：%s" % (original, exc))

    crop = spec.get("crop_original")
    crop_applied = False
    if crop is not None:
        box = parse_box(crop, source.width, source.height, "crop_original")
        cropped = source.crop(box)
        if (box[2] - box[0], box[3] - box[1]) != source.size:
            crop_applied = True
            warnings.append("原图已按 crop_original=%s 裁切（%dx%d → %dx%d）"
                            % (list(crop), source.width, source.height,
                               cropped.width, cropped.height))
        source = cropped

    # 字体
    regular_path = find_font_file(FONT_FILES)
    bold_path = find_font_file(BOLD_FILES) or regular_path
    if not regular_path:
        warnings.append("未找到中文字体（在 %s 里找 msyh.ttc/msyhl.ttc/simhei.ttf/"
                        "simsun.ttc/Deng.ttf/msyhbd.ttc 都没命中），中文可能显示为方框"
                        % "、".join(font_dirs()[:2]))

    # 文本清洗：字符级替换如实记录
    paragraphs = build_paragraphs(transcription, sections)
    cleaned_paragraphs = []
    for paragraph in paragraphs:
        if paragraph["kind"] == "spacer":
            cleaned_paragraphs.append({"kind": "spacer", "text": ""})
            continue
        cleaned, used = apply_substitutions(paragraph["text"])
        for label in used:
            if label not in substitutions:
                substitutions.append(label)
        cleaned_paragraphs.append({"kind": paragraph["kind"], "text": cleaned})

    raw_all = "\n".join(paragraph["text"] for paragraph in paragraphs)
    for label in collect_latex_substitutions(raw_all):
        if label not in substitutions:
            substitutions.append(label)

    segments_by_paragraph = []
    formulas = []
    plain_chunks = []
    for paragraph in cleaned_paragraphs:
        if paragraph["kind"] == "spacer":
            segments_by_paragraph.append((paragraph["kind"], []))
            continue
        segments = split_segments(paragraph["text"])
        segments_by_paragraph.append((paragraph["kind"], segments))
        formulas.extend(extract_formulas(segments))
        plain_chunks.append(latex_to_plain(paragraph["text"]))
    formula_count = len(formulas)
    plain_text = "\n".join(plain_chunks)
    values = collect_values(plain_text)

    # 渲染器
    math_renderer = MathImageRenderer(["Microsoft YaHei", "Microsoft YaHei UI", "SimHei",
                                       "SimSun", "DengXian"])
    matplotlib_available = bool(math_renderer.ok)  # 只表示"本机 matplotlib 真能用"
    if renderer_pref == "pillow":
        math_renderer.ok = False
        math_renderer.reason = "调用方指定 renderer=pillow"
    elif formula_count == 0:
        math_renderer.ok = False
        math_renderer.reason = "转写里没有 $...$ 公式"
    elif not math_renderer.ok:
        if renderer_pref == "matplotlib":
            warnings.append("调用方指定 renderer=matplotlib，但本机 matplotlib 不可用（%s），"
                            "公式已降级为 Pillow 纯文本替换"
                            % (math_renderer.reason or "未知原因"))
        else:
            warnings.append("matplotlib 不可用（%s），公式降级为 Pillow 纯文本替换"
                            % (math_renderer.reason or "未知原因"))

    # 试排：字号过大 / 内容过长时逐级降级（缩字号 → 分栏），降级方式写进 warnings
    def build(current_font_size, columns):
        font, has_cjk_font = load_font(regular_path, int(round(current_font_size)))
        bold_font, _ = load_font(bold_path, int(round(current_font_size * 1.05)))
        body = BodyRenderer(font, bold_font, current_font_size, math_renderer,
                            highlights=highlights)

        if layout == "stack":
            stacked = True
        elif layout == "side":
            stacked = False
        else:
            aspect = source.height / float(max(1, source.width))
            stacked = width < 1000 or aspect >= 1.4

        if stacked:
            panel_w = width - 2 * MARGIN
            image_panel, image_h = fit_image(source, panel_w)
            text_w = panel_w - 2 * PAD
        else:
            panel_w = (width - 2 * MARGIN - GAP) // 2
            image_panel, image_h = fit_image(source, panel_w)
            text_w = panel_w - 2 * PAD

        columns = max(1, int(columns))
        if columns > 1:
            col_width = (text_w - COL_GAP * (columns - 1)) // columns
            if col_width < MIN_COL_WIDTH:
                columns = max(1, (text_w + COL_GAP) // (MIN_COL_WIDTH + COL_GAP))
                col_width = (text_w - COL_GAP * (columns - 1)) // columns
        else:
            col_width = text_w
        columns = max(1, columns)

        # 逐段换行 → 行序列
        rows = []
        for kind, segments in segments_by_paragraph:
            if kind == "spacer":
                rows.append({"line": None, "height": int(round(current_font_size * 0.8)),
                             "kind": "spacer"})
                continue
            lines = body.wrap(segments, col_width)
            for line in lines:
                rows.append({"line": line, "height": body.line_height(line), "kind": kind})

        if columns == 1:
            column_list = [rows]
        else:
            total = sum(row["height"] for row in rows) or 1
            target = total / float(columns)
            column_list = []
            current = []
            current_h = 0
            for index, row in enumerate(rows):
                current.append(row)
                current_h += row["height"]
                remaining = columns - len(column_list) - 1
                if current_h >= target and remaining > 0 and index < len(rows) - 1:
                    # 断列时不要把 spacer 留在列尾
                    while current and current[-1]["kind"] == "spacer":
                        current.pop()
                    column_list.append(current)
                    current = []
                    current_h = 0
            if current:
                column_list.append(current)
            if not column_list:
                column_list = [[]]

        column_heights = [sum(row["height"] for row in column) for column in column_list]
        text_h = (max(column_heights) if column_heights else 0) + 2 * PAD

        value_parts = [("数值清单（逐个和原图核对）：", INK)]
        if values:
            for position, value in enumerate(values):
                if position:
                    value_parts.append(("   ", INK))
                value_parts.append((value, VALUE_INK))
        else:
            value_parts.append(("（转写里没识别到数字）", FOOT_INK))
        if len(values) >= 24:
            value_parts.append((" …", FOOT_INK))

        strip_w = width - 2 * MARGIN - 2 * PAD
        value_lines = wrap_rich(value_parts, font, strip_w)

        formula_lines = []
        if formulas:
            formula_parts = [("公式清单：", INK)]
            for position, formula in enumerate(formulas[:6]):
                if position:
                    formula_parts.append(("；", INK))
                formula_parts.append((formula, FORMULA_INK))
            if len(formulas) > 6:
                formula_parts.append((" …", FOOT_INK))
            formula_lines = wrap_rich(formula_parts, font, strip_w)

        footer_text = "渲染器：%s · 中文：%s · 若与原图有出入，请以原图为准" % (
            "matplotlib mathtext" if math_renderer.ok else "Pillow 纯文本",
            os.path.basename(regular_path) if regular_path else "默认字体（可能缺中文）")
        footer_lines = wrap_rich([(footer_text, FOOT_INK)], font, strip_w)

        original_title = "原图（已按 crop_original 裁切）" if crop_applied else "原图"

        strip_h = 2 * PAD + int(round(current_font_size * 1.6)) * (
            len(value_lines) + len(formula_lines) + len(footer_lines))

        banner_h = TITLE_H if title else 0
        if stacked:
            content_h = TITLE_H + image_h + GAP + TITLE_H + text_h
        else:
            content_h = TITLE_H + max(image_h, text_h)
        total_h = MARGIN * 2 + banner_h + content_h + GAP + strip_h
        return {
            "font": font, "bold_font": bold_font, "body": body, "stacked": stacked,
            "panel_w": panel_w, "image_panel": image_panel, "image_h": image_h,
            "text_h": text_h, "columns": column_list, "column_heights": column_heights,
            "col_width": col_width, "col_count": len(column_list),
            "value_lines": value_lines, "formula_lines": formula_lines,
            "footer_lines": footer_lines, "strip_h": strip_h, "total_h": total_h,
            "has_cjk_font": has_cjk_font, "original_title": original_title,
            "banner_h": banner_h,
        }

    # ① 缩字号 ② 分栏 ③ 仍超高就如实报告
    plan = build(font_size, 1)
    degradation = []
    effective_font = font_size
    while plan["total_h"] > MAX_HEIGHT and effective_font > MIN_FONT_SIZE:
        effective_font -= 1
        plan = build(effective_font, 1)
    if effective_font < font_size:
        degradation.append("字号从 %g 降到 %g" % (font_size, effective_font))

    columns = 1
    while plan["total_h"] > MAX_HEIGHT and columns < MAX_COLUMNS:
        candidate_columns = columns + 1
        candidate = build(effective_font, candidate_columns)
        if candidate["col_count"] <= columns:
            break
        columns = candidate_columns
        plan = candidate
    if columns > 1:
        degradation.append("转写面板改用 %d 列排版" % columns)

    if plan["total_h"] > MAX_HEIGHT:
        warnings.append("内容非常长：已用「%s」降级（font_size=%g，%d 列），"
                        "成图高 %d px 超过参考上限 %d px，但**没有截断任何内容**"
                        % ("；".join(degradation) or "无", effective_font,
                           plan["col_count"], plan["total_h"], MAX_HEIGHT))
    elif degradation:
        warnings.append("内容较长，为避免成图过高已降级：%s（未截断内容）"
                        % "；".join(degradation))
    if plan["col_count"] > 1 and plan["col_width"] < MIN_COL_WIDTH:
        warnings.append("分栏后每列仅 %d px 宽，较窄；可加大 width 或减少内容"
                        % plan["col_width"])

    # 开始画
    canvas_h = plan["total_h"]
    canvas = Image.new("RGB", (width, canvas_h), BG)
    plan["body"].canvas = canvas  # 供 BodyRenderer 粘贴 mathtext 公式图
    draw = ImageDraw.Draw(canvas)

    if not plan["has_cjk_font"]:
        warnings.append("Pillow 未能加载中文字体，已用默认字体（中文可能显示为方框）")

    y = MARGIN
    title_font = plan["bold_font"]
    font = plan["font"]
    panel_w = plan["panel_w"]
    line_h = int(round(effective_font * 1.6))
    strip_w = width - 2 * MARGIN - 2 * PAD

    def draw_title(x, top, text, panel_width, font_override=None, bg=TITLE_BG):
        draw.rectangle([x, top, x + panel_width, top + TITLE_H], fill=bg,
                       outline=BORDER, width=2)
        draw.text((x + PAD, top + (TITLE_H - int(effective_font * 1.4)) // 2), text,
                  font=font_override or title_font, fill=TITLE_INK)

    def draw_image_panel(x, top, image, panel_width, target_h):
        draw.rectangle([x, top, x + panel_width, top + target_h], fill=PANEL_BG,
                       outline=BORDER, width=2)
        offset_x = x + max(0, (panel_width - image.width) // 2)
        offset_y = top + max(0, (target_h - image.height) // 2)
        canvas.paste(image, (int(offset_x), int(offset_y)))

    def draw_text_panel(x, top, panel_width, target_h):
        draw.rectangle([x, top, x + panel_width, top + target_h], fill=PANEL_BG,
                       outline=BORDER, width=2)
        for column_index, column in enumerate(plan["columns"]):
            column_x = x + PAD + column_index * (plan["col_width"] + COL_GAP)
            cursor = top + PAD
            for row in column:
                if row["line"]:
                    is_heading = row["kind"] == "heading"
                    plan["body"].draw_line(draw, column_x, cursor, row["line"],
                                           HEADING_INK if is_heading else INK,
                                           bold=is_heading)
                cursor += row["height"]

    def draw_strip(x, top, panel_width):
        draw.rectangle([x, top, x + panel_width, top + plan["strip_h"]], fill=(248, 246, 240),
                       outline=BORDER, width=2)
        cursor = top + PAD
        cursor = draw_rich_lines(draw, x + PAD, cursor, plan["value_lines"], font, line_h)
        if plan["formula_lines"]:
            cursor = draw_rich_lines(draw, x + PAD, cursor, plan["formula_lines"], font, line_h)
        draw_rich_lines(draw, x + PAD, cursor, plan["footer_lines"], font, line_h)

    if plan["banner_h"]:
        draw_title(MARGIN, y, title, width - 2 * MARGIN)
        y += TITLE_H + GAP // 2

    if plan["stacked"]:
        left_x = MARGIN
        draw_title(left_x, y, plan["original_title"], panel_w)
        y += TITLE_H
        draw_image_panel(left_x, y, plan["image_panel"], panel_w, plan["image_h"])
        y += plan["image_h"] + GAP
        draw_title(left_x, y, HEADING_TEXT, panel_w)
        y += TITLE_H
        draw_text_panel(left_x, y, panel_w, plan["text_h"])
        y += plan["text_h"] + GAP
        draw_strip(left_x, y, panel_w)
    else:
        left_x = MARGIN
        right_x = MARGIN + panel_w + GAP
        draw_title(left_x, y, plan["original_title"], panel_w)
        draw_title(right_x, y, HEADING_TEXT_SIDE, panel_w)
        y += TITLE_H
        row_h = max(plan["image_h"], plan["text_h"])
        draw_image_panel(left_x, y, plan["image_panel"], panel_w, row_h)
        draw_text_panel(right_x, y, panel_w, row_h)
        y += row_h + GAP
        draw_strip(MARGIN, y, width - 2 * MARGIN)

    del draw
    parent = os.path.dirname(os.path.abspath(out))
    if parent and not os.path.isdir(parent):
        os.makedirs(parent, exist_ok=True)
    canvas.save(out, "PNG", optimize=True, compress_level=9)

    renderer = "matplotlib" if plan["body"].used_mathtext else "pillow-text"
    if renderer == "pillow-text" and formula_count and renderer_pref != "pillow":
        warnings.append("未使用 matplotlib 渲染公式，已用可读纯文本替换（\\frac{a}{b}→a/b）")
    if renderer_pref == "pillow" and formula_count:
        warnings.append("调用方要求 renderer=pillow，公式按 Pillow 纯文本替换渲染"
                        "（\\sigma→σ、\\frac{a}{b}→a/b）")

    return {
        "ok": True,
        "out": out,
        "width": canvas.width,
        "height": canvas.height,
        "renderer": renderer,
        "formula_count": formula_count,
        "substitutions": substitutions,
        "warnings": warnings,
        "layout": "stacked" if plan["stacked"] else "side-by-side",
        "columns": plan["col_count"],
        "font_size": effective_font,
        "sections": len(sections or []),
        "highlight": highlights,
        "value_count": len(values),
        "font": os.path.basename(regular_path) if regular_path else "default",
        "matplotlib_available": matplotlib_available,
        "bytes": os.path.getsize(out),
    }


def fit_image(image, panel_w):
    """等比缩放到 panel_w 内；过高再压到 MAX_HEIGHT*0.45。"""
    scale = panel_w / float(image.width)
    target_w = max(1, int(round(image.width * scale)))
    target_h = max(1, int(round(image.height * scale)))
    cap = int(MAX_HEIGHT * 0.45)
    if target_h > cap:
        target_h = cap
        target_w = max(1, int(round(image.width * (cap / float(image.height)))))
    if (target_w, target_h) != image.size:
        image = image.resize((target_w, target_h), Image.LANCZOS)
    return image, image.height


def main(argv):
    try:
        spec = load_spec(argv)
    except Exception as exc:
        return fail(exc)
    try:
        result = render(spec)
    except Exception as exc:
        log("失败：%s" % exc)
        return fail(exc)
    return emit(result, 0)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))