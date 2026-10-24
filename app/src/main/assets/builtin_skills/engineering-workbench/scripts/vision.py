#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""vision.py —— 「第二读者」：用独立视觉模型把题图转写成学科无关的结构化 JSON。

**定位（重要）**：上层模型自己已经能直接看图，所以本脚本不是唯一通道，而是
**独立第二读者 / 交叉核对**——同一个张图让另一个引擎再抄一遍，两份转写不一致的地方
就是题意风险点（再用 render_math.py 画成对照图请学生确认）。
因此提示词里**不假设"你看不到图"**，只要求"照实抄、看不清就标存疑、不许求解"。

转写字段（学科无关，力学/电路/热力学/数学/化学/统计图表/表格/多栏试卷都适用）：
    subject / problem_type / statement_latex / given / find / figure / tables /
    equations / constants / units_system / low_confidence / unreadable / notes
两条写死的规则：
    ① 看不清就标存疑，**禁止猜数值**（模糊的数字只留可见部分并写进 low_confidence）；
    ② **只转写不求解**（不计算答案、不补全题目没写的条件）。

调用方可覆盖：
    prompt  完全自定义提示词（连字段说明一起换掉）
    schema  只换字段说明，角色与两条硬规则仍由本脚本保证
    lang    默认 zh；给 en 用英文提示词

provider 链（按顺序尝试，只有检测到 key 才加入；可用 --provider 钉死单个）：
    siliconflow-qwen（默认首选）→ zhipu-glm → deepseek → sensenova → gemini
    → 环境变量 VISION_FALLBACKS 声明的任意 OpenAI 兼容端点

Key 读取顺序（**key 绝不写入文件、日志或 stdout**）：
    1. 进程环境变量
    2. C:\\Users\\<用户>\\.dsh\\secrets\\media-tools.env
    3. %USERPROFILE%\\.dsh\\secrets\\media-tools.env
    4. %APPDATA%\\dsh-desktop\\harness\\.credentials.yaml（顶层 key 或 refs 子映射）

CLI（stdout 只能是 JSON）：
    <python> vision.py --spec <spec.json>
    <python> vision.py --spec-json '<inline json>'
    <python> vision.py <图片...> [--provider NAME] [--prompt 文本] [--doctor]

成功：{"ok":true,"provider","model","parsed","transcription","raw","attempts"}
失败：{"ok":false,"error":"中文原因"}，退出码 1。
"""

import base64
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

DEFAULT_TIMEOUT = 120
DOCTOR_TIMEOUT = 5


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
        sys.stderr.write("[vision] %s\n" % message)
    except Exception:
        pass


# --------------------------------------------------------------------------- #
# key 读取：环境变量 → secrets env 文件 → DSH 凭据库
# --------------------------------------------------------------------------- #
HOME = Path.home()
USERPROFILE = Path(os.environ.get("USERPROFILE") or str(HOME))
APPDATA = Path(os.environ.get("APPDATA") or (HOME / "AppData" / "Roaming"))


def secret_env_files():
    """按顺序列出候选 secrets 文件（去重、保留顺序）。

    顺序：当前用户 home → %USERPROFILE% → 同机其他用户配置目录（契约里点名的
    C:\\Users\\<用户>\\.dsh\\secrets\\media-tools.env 就在这一档，用兄弟目录扫描
    拿到，避免把某个用户名写死在脚本里）→ 历史 Codex 路径。
    """
    candidates = [
        HOME / ".dsh" / "secrets" / "media-tools.env",
        USERPROFILE / ".dsh" / "secrets" / "media-tools.env",
    ]
    try:
        users_root = HOME.parent
        if users_root.is_dir():
            for sibling in sorted(users_root.iterdir(), key=lambda item: item.name)[:64]:
                candidate = sibling / ".dsh" / "secrets" / "media-tools.env"
                if candidate.is_file():
                    candidates.append(candidate)
    except Exception as exc:
        log("扫描同机用户目录失败（忽略）：%s" % exc)
    candidates.append(HOME / ".codex" / "secrets" / "media-tools.env")

    seen = set()
    ordered = []
    for path in candidates:
        try:
            key = str(path.resolve()).lower()
        except Exception:
            key = str(path).lower()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(path)
    return ordered


def credential_file():
    return APPDATA / "dsh-desktop" / "harness" / ".credentials.yaml"


def parse_env_file(text):
    """每行 KEY=value，允许 # 注释、export 前缀、引号。"""
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        name, _, value = line.partition("=")
        name = name.strip()
        value = value.strip().strip('"').strip("'")
        if name and value:
            values[name] = value
    return values


def parse_credentials_yaml(text, name):
    """极简 YAML 读取：顶层 `KEY: value` 或 `refs:` 下缩进的 `KEY: value`。

    不依赖 pyyaml（内置 Python 没装），只解析本项目需要的形状。
    """
    block = None
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if indent == 0:
            block = key
            if key == name and value:
                return value
        else:
            if block == "refs" and key == name and value:
                return value
    return None


def load_key(name):
    """返回 (value, source)；找不到返回 (None, None)。"""
    value = os.environ.get(name)
    if value and value.strip():
        return value.strip(), "env"

    for path in secret_env_files():
        try:
            if path.is_file():
                values = parse_env_file(path.read_text(encoding="utf-8-sig", errors="replace"))
                if values.get(name):
                    return values[name], "file:%s" % path
        except Exception as exc:
            log("读取 %s 失败：%s" % (path, exc))

    creds = credential_file()
    try:
        if creds.is_file():
            found = parse_credentials_yaml(
                creds.read_text(encoding="utf-8-sig", errors="replace"), name)
            if found:
                return found, "credentials:%s" % creds
    except Exception as exc:
        log("读取凭据库失败：%s" % exc)

    return None, None


# --------------------------------------------------------------------------- #
# 转写提示词：学科无关的结构化抽取
# --------------------------------------------------------------------------- #
# 两条硬规则必须原样出现在任何提示词里（自定义 prompt 除外）。
RULES_ZH = (
    "【两条硬规则，必须遵守】\n"
    "1. 看不清就标存疑，禁止猜数值：任何模糊、被遮挡、被裁切、手写难辨的数字/单位/符号，"
    "只能原样保留你确实看清的部分，并把不确定之处写进 low_confidence（说明原图位置与为什么不"
    "确定）或 unreadable；**绝不允许**用\"看起来像\"的数值或凭常识补全的数字填充字段。\n"
    "2. 只转写不求解：不要计算答案、不要推导、不要判断对错、不要补全题目没写的条件；"
    "只把图中真实存在的信息抄成结构化文本。\n"
    "另外：图片你可以直接看，不要输出\"我无法查看图片\"这类话；图中确实没有的字段给空数组或"
    "空字符串，不要编造；不要输出解释文字、不要 Markdown 代码围栏，只输出一个 JSON 对象。"
)

RULES_EN = (
    "[Two hard rules, always apply]\n"
    "1. If you cannot read it clearly, flag it as uncertain — NEVER guess numbers: for any blurry, "
    "occluded, cropped or illegible digit/unit/symbol, keep only what you truly see and record the "
    "uncertainty in low_confidence (with its location in the image and why you are unsure) or "
    "unreadable. Do NOT fill fields with a plausible-looking value.\n"
    "2. Transcribe only, never solve: do not compute answers, do not derive, do not complete missing "
    "conditions; just copy what really exists in the image into structured text.\n"
    "Also: you CAN see the image directly — do not reply that you are unable to view images. Use empty "
    "arrays or empty strings for fields that are absent rather than inventing content. Output a single "
    "JSON object only: no prose, no Markdown fences."
)

FIELDS_ZH = (
    "{\n"
    "  \"subject\": {\"name\": \"学科猜测，如 材料力学/理论力学/电路/热力学/数学分析/化学/统计\", "
    "\"confidence\": 0.0},\n"
    "  \"problem_type\": \"题型，如 选择题/填空题/计算题/证明题/实验题/图表分析/阅读理解\",\n"
    "  \"statement_latex\": \"题面全文（照抄原文；公式写成 LaTeX，如 $M_{\\\\max}=\\\\frac{PL}{4}$；"
    "看不清的字用 \\u25a1 占位）\",\n"
    "  \"given\": [{\"quantity\": \"集中力\", \"symbol\": \"P\", \"value\": \"10\", \"unit\": \"kN\", "
    "\"where\": \"跨中\"}],\n"
    "  \"find\": [{\"quantity\": \"支座反力\", \"symbol\": \"R_A\", \"unit\": \"kN\"}],\n"
    "  \"figure\": [{\"kind\": \"尺寸标注\", \"detail\": \"q = 5 kN/m 分布在 0~2 m\", "
    "\"at\": \"图形上方箭头\"}],\n"
    "  \"tables\": [{\"caption\": \"表 1\", \"columns\": [\"x\", \"y\"], \"rows\": [[\"1\", \"2\"]]}],\n"
    "  \"equations\": [\"$\\\\sigma_{\\\\max}=\\\\frac{M_{\\\\max}}{W}$\"],\n"
    "  \"constants\": [{\"name\": \"弹性模量\", \"symbol\": \"E\", \"value\": \"200\", \"unit\": \"GPa\", "
    "\"source\": \"图中给出\"}],\n"
    "  \"units_system\": \"图中使用的单位制，如 SI / 工程单位 / 未标注\",\n"
    "  \"low_confidence\": [{\"field\": \"P 的数值\", \"at\": \"第一行右侧\", "
    "\"why\": \"手写 10 与 20 难分辨\"}],\n"
    "  \"unreadable\": [{\"at\": \"右下角被手指遮挡的 2 行\", \"note\": \"疑似第 (4) 问的条件\"}],\n"
    "  \"notes\": \"其它值得对方注意的地方：手写/反光/折痕/裁切/多栏/页码等\"\n"
    "}"
)

FIELDS_EN = (
    "{\n"
    "  \"subject\": {\"name\": \"subject guess, e.g. mechanics/circuits/thermodynamics/calculus/"
    "chemistry/statistics\", \"confidence\": 0.0},\n"
    "  \"problem_type\": \"e.g. multiple choice / fill in the blank / calculation / proof / lab / "
    "chart analysis\",\n"
    "  \"statement_latex\": \"full statement, verbatim; formulas as LaTeX like "
    "$M_{\\\\max}=\\\\frac{PL}{4}$; use \\u25a1 for characters you cannot read\",\n"
    "  \"given\": [{\"quantity\": \"concentrated load\", \"symbol\": \"P\", \"value\": \"10\", "
    "\"unit\": \"kN\", \"where\": \"mid-span\"}],\n"
    "  \"find\": [{\"quantity\": \"support reaction\", \"symbol\": \"R_A\", \"unit\": \"kN\"}],\n"
    "  \"figure\": [{\"kind\": \"dimension\", \"detail\": \"q = 5 kN/m over 0~2 m\", "
    "\"at\": \"arrow above the beam\"}],\n"
    "  \"tables\": [{\"caption\": \"Table 1\", \"columns\": [\"x\", \"y\"], \"rows\": [[\"1\", \"2\"]]}],\n"
    "  \"equations\": [\"$\\\\sigma_{\\\\max}=\\\\frac{M_{\\\\max}}{W}$\"],\n"
    "  \"constants\": [{\"name\": \"Young's modulus\", \"symbol\": \"E\", \"value\": \"200\", "
    "\"unit\": \"GPa\", \"source\": \"given in the figure\"}],\n"
    "  \"units_system\": \"unit system used, e.g. SI / engineering / not stated\",\n"
    "  \"low_confidence\": [{\"field\": \"value of P\", \"at\": \"first line, right side\", "
    "\"why\": \"handwritten 10 vs 20\"}],\n"
    "  \"unreadable\": [{\"at\": \"bottom-right 2 lines covered by a finger\", "
    "\"note\": \"looks like condition (4)\"}],\n"
    "  \"notes\": \"anything else worth checking: handwriting, glare, folds, cropping, columns, page "
    "numbers\"\n"
    "}"
)

ROLE_ZH = (
    "你是一个题图的**独立转写员**（第二个读者）。另一个模型已经看过这张图并会自己读题，"
    "你的价值在于：独立、照实、结构化地再抄一遍，便于和它的读法交叉核对。"
)
ROLE_EN = (
    "You are an independent transcriber (a second reader) for problem images. Another model has "
    "already seen this image and will read it on its own; your value is an independent, faithful, "
    "structured copy that can be cross-checked against its reading."
)


def build_prompt(prompt=None, schema=None, lang="zh"):
    """组装最终提示词。

    - prompt 给了就完全用调用方的（一字不改）；
    - schema 只替换字段说明，角色与两条硬规则仍由本脚本保证；
    - lang 只认 zh / en（默认 zh），决定角色、规则与默认字段说明用哪种语言。
    """
    if prompt is not None:
        if not isinstance(prompt, str) or not prompt.strip():
            raise ValueError("prompt 必须是非空字符串")
        return prompt.strip(), "custom"

    lang = str(lang or "zh").strip().lower()
    if lang in ("zh", "cn", "chinese", "\u4e2d\u6587"):
        role, rules, fields = ROLE_ZH, RULES_ZH, FIELDS_ZH
    elif lang in ("en", "english", "eng"):
        role, rules, fields = ROLE_EN, RULES_EN, FIELDS_EN
    else:
        raise ValueError("lang 只支持 zh / en（收到 %r）" % lang)

    if schema is not None:
        if not isinstance(schema, str) or not schema.strip():
            raise ValueError("schema 必须是非空字符串（字段说明，可直接给 JSON 骨架或字段列表）")
        fields = schema.strip()
        mode = "schema"
    else:
        mode = "default"

    if lang in ("en", "english", "eng"):
        header = ("%s\n\n%s\n\nOutput exactly one JSON object with these fields "
                  "(keep the same key names):\n%s" % (role, rules, fields))
    else:
        header = ("%s\n\n%s\n\n只输出一个 JSON 对象，字段与含义如下（键名照写）：\n%s"
                  % (role, rules, fields))
    return header, mode


# 模块级默认提示词（保持旧名字可用）
TRANSCRIPTION_PROMPT = build_prompt()[0]
TRANSCRIPTION_PROMPT_EN = build_prompt(lang="en")[0]


def _split_endpoint(url):
    url = str(url).rstrip("/")
    if url.endswith("/chat/completions"):
        return url[: -len("/chat/completions")]
    return url


def build_providers():
    """全部候选 provider（按尝试顺序）。key 是否存在由调用方判断。"""
    providers = []

    providers.append({
        "name": "siliconflow-qwen",
        "baseUrl": _split_endpoint(os.environ.get(
            "SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")),
        "apiKeyEnv": "SILICONFLOW_API_KEY",
        "model": os.environ.get("SILICONFLOW_VISION_MODEL", "Qwen/Qwen3-VL-8B-Instruct"),
        "maxTokens": 2048,
        # 走 OpenAI 兼容的 json_object 模式；端点不认（400）时会自动退回纯提示词约束
        "jsonObject": True,
        "note": "SiliconFlow Qwen3-VL（国内直连，当前默认首选）",
    })

    providers.append({
        "name": "zhipu-glm",
        "baseUrl": _split_endpoint(os.environ.get(
            "GLM_BASE_URL", "https://open.bigmodel.cn/api/paas/v4")),
        "apiKeyEnv": "GLM_API_KEY",
        "model": os.environ.get("GLM_VISION_MODEL", "glm-4v-flash"),
        # 实测 glm-4v-flash 端点硬性要求 max_tokens ∈ [1,1024]
        "maxTokens": 1024,
        "jsonObject": True,
        "note": "智谱 GLM-4V-Flash（免费，max_tokens 上限 1024）",
    })

    providers.append({
        "name": "deepseek",
        "baseUrl": _split_endpoint(os.environ.get(
            "DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")),
        "apiKeyEnv": "DEEPSEEK_API_KEY",
        "model": os.environ.get("DEEPSEEK_VISION_MODEL", "deepseek-v4-flash-vision-exp"),
        "maxTokens": 4096,
        # 实测：不开 json_object 时它爱回自然语言，解析必失败（第 545 行那条 400 回退保护仍在）
        "jsonObject": True,
        # 思考会吃掉全部输出预算导致 content 为空，显式关闭
        "extraBody": {"thinking": {"type": "disabled"}},
        "note": "DeepSeek 视觉模型（付费）",
    })

    sensenova_base = os.environ.get("SENSENOVA_BASE_URL", "https://token.sensenova.ai/v1")
    providers.append({
        "name": "sensenova",
        "baseUrl": _split_endpoint(sensenova_base),
        "apiKeyEnv": "SENSENOVA_API_KEY",
        "model": os.environ.get(
            "SENSENOVA_VISION_MODEL",
            os.environ.get("SENSENOVA_MODEL", "sensenova-6.8-flash-lite")),
        "maxTokens": 2048,
        "jsonObject": False,
        "note": "商汤 SenseNova（OpenAI 兼容端点）",
    })

    providers.append({
        "name": "gemini",
        "baseUrl": _split_endpoint(os.environ.get(
            "GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai")),
        "apiKeyEnv": "GEMINI_API_KEY",
        "model": os.environ.get(
            "GEMINI_VISION_MODEL", os.environ.get("GEMINI_MODEL", "gemini-3.6-flash")),
        "maxTokens": 4096,
        "jsonObject": True,
        "note": "Google Gemini OpenAI 兼容端点（可能需要代理）",
    })

    providers.extend(load_fallbacks())
    return providers


def load_fallbacks():
    """VISION_FALLBACKS：JSON 数组，元素含 name/baseUrl/model/apiKeyEnv/maxTokens/jsonObject。"""
    raw = os.environ.get("VISION_FALLBACKS", "").strip()
    if not raw:
        return []
    try:
        items = json.loads(raw)
    except Exception as exc:
        log("VISION_FALLBACKS 不是合法 JSON（已忽略）：%s" % exc)
        return []
    if not isinstance(items, list):
        log("VISION_FALLBACKS 必须是 JSON 数组（已忽略）")
        return []
    out = []
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            log("VISION_FALLBACKS[%d] 不是对象，已跳过" % index)
            continue
        if not item.get("baseUrl") or not item.get("model"):
            log("VISION_FALLBACKS[%d] 缺少 baseUrl/model，已跳过" % index)
            continue
        out.append({
            "name": str(item.get("name") or "fallback%d" % (index + 1)),
            "baseUrl": _split_endpoint(item["baseUrl"]),
            "apiKeyEnv": str(item.get("apiKeyEnv") or "OPENAI_API_KEY"),
            "model": str(item["model"]),
            "maxTokens": int(item.get("maxTokens") or 2048),
            "jsonObject": bool(item.get("jsonObject", False)),
            "note": "VISION_FALLBACKS 自定义端点",
        })
    return out


def active_chain(providers):
    """只保留检测到 key 的 provider，同时给出缺失清单。"""
    chain = []
    missing = []
    cache = {}
    for provider in providers:
        env_name = provider["apiKeyEnv"]
        if env_name not in cache:
            cache[env_name] = load_key(env_name)
        value, source = cache[env_name]
        if value:
            provider = dict(provider)
            provider["_keySource"] = source
            chain.append(provider)
        else:
            missing.append(env_name)
    return chain, missing


# --------------------------------------------------------------------------- #
# 图片编码
# --------------------------------------------------------------------------- #
def image_max_side(count):
    """边长随批内张数自适应：1–2 张 1024px，3–4 张 768px，5+ 张 512px。"""
    if count <= 2:
        return 1024
    if count <= 4:
        return 768
    return 512


def b64_jpeg(path, max_side=1024, quality=88):
    try:
        from PIL import Image
    except Exception as exc:
        raise RuntimeError("本机 Python 缺少 Pillow，无法压缩图片：%s" % exc)
    with Image.open(path) as image:
        image.load()
        if max(image.size) > max_side:
            image.thumbnail((max_side, max_side), Image.LANCZOS)
        if image.mode not in ("RGB", "L"):
            image = image.convert("RGB")
        elif image.mode == "L":
            image = image.convert("RGB")
        import io
        buffer = io.BytesIO()
        image.save(buffer, "JPEG", quality=quality)
    return base64.b64encode(buffer.getvalue()).decode("ascii")


# --------------------------------------------------------------------------- #
# 调用
# --------------------------------------------------------------------------- #
class AttemptError(Exception):
    def __init__(self, status, detail):
        super().__init__(detail)
        self.status = status
        self.detail = detail


def classify_exception(exc):
    if isinstance(exc, urllib.error.HTTPError):
        return "http_%d" % exc.code, _http_detail(exc)
    if isinstance(exc, urllib.error.URLError):
        reason = getattr(exc, "reason", exc)
        if isinstance(reason, (socket.timeout, TimeoutError)):
            return "timeout", "连接或读取超时：%s" % reason
        return "network", "网络错误：%s" % reason
    if isinstance(exc, (socket.timeout, TimeoutError)):
        return "timeout", "请求超时：%s" % exc
    if isinstance(exc, AttemptError):
        return exc.status, exc.detail
    return "error", str(exc)


def _http_detail(exc):
    body = ""
    try:
        body = exc.read().decode("utf-8", "replace")
    except Exception:
        body = ""
    body = re.sub(r"\s+", " ", body).strip()
    if len(body) > 300:
        body = body[:300] + "…"
    return "HTTP %s %s%s" % (exc.code, exc.reason, ("： " + body) if body else "")


def call_provider(provider, images_b64, prompt, timeout):
    key, _source = load_key(provider["apiKeyEnv"])
    if not key:
        raise AttemptError("no_key", "缺少 %s" % provider["apiKeyEnv"])

    parts = [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + item}}
             for item in images_b64]
    parts.append({"type": "text", "text": prompt})
    body = {
        "model": provider["model"],
        "messages": [{"role": "user", "content": parts}],
        "max_tokens": provider["maxTokens"],
    }
    if provider.get("jsonObject"):
        body["response_format"] = {"type": "json_object"}
    body.update(provider.get("extraBody") or {})

    url = provider["baseUrl"] + "/chat/completions"
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + key}
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        # 端点不认 response_format（400）时退回纯 prompt 约束再试一次
        if exc.code == 400 and "response_format" in body:
            body.pop("response_format", None)
            retry = urllib.request.Request(
                url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
            with urllib.request.urlopen(retry, timeout=timeout) as response:
                payload = json.loads(response.read().decode("utf-8", "replace"))
        else:
            raise

    try:
        choice = payload["choices"][0]
    except Exception:
        raise AttemptError("bad_response", "响应里没有 choices 字段：%s"
                           % json.dumps(payload, ensure_ascii=False)[:300])
    message = choice.get("message") or {}
    content = message.get("content")
    if isinstance(content, list):  # 少数端点回数组形式
        content = "".join(part.get("text", "") for part in content
                          if isinstance(part, dict))
    if content is None or not str(content).strip():
        finish = choice.get("finish_reason")
        raise AttemptError("empty",
                           "模型返回空内容（finish_reason=%s，max_tokens=%s）"
                           % (finish, provider["maxTokens"]))
    return str(content)


# --------------------------------------------------------------------------- #
# JSON 提取
# --------------------------------------------------------------------------- #
def extract_json(text):
    """从模型原文里挖出 JSON 对象；失败返回 (None, 原因)。"""
    if not text or not text.strip():
        return None, "空文本"
    raw = text.strip()
    fence = re.search(r"```(?:json)?\s*(.+?)```", raw, re.S | re.I)
    if fence:
        raw = fence.group(1).strip()
    try:
        data = json.loads(raw)
        if isinstance(data, dict):
            return data, None
        return None, "JSON 顶层不是对象"
    except Exception:
        pass
    start = raw.find("{")
    end = raw.rfind("}")
    if start != -1 and end > start:
        try:
            data = json.loads(raw[start:end + 1])
            if isinstance(data, dict):
                return data, None
        except Exception as exc:
            return None, "JSON 解析失败：%s" % exc
    return None, "未找到 JSON 对象"


# --------------------------------------------------------------------------- #
# doctor
# --------------------------------------------------------------------------- #
def probe_endpoint(base_url, timeout=DOCTOR_TIMEOUT):
    """只做 DNS + TCP 连通性探测（不消耗额度、不发送图片）。"""
    match = re.match(r"^(https?)://([^/]+)", base_url)
    if not match:
        return False, "端点格式无法解析"
    scheme, hostport = match.group(1), match.group(2)
    host = hostport.split(":")[0]
    port = int(hostport.split(":")[1]) if ":" in hostport else (443 if scheme == "https" else 80)
    started = time.time()
    try:
        socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    except Exception as exc:
        return False, "DNS 解析失败：%s" % exc
    try:
        with socket.create_connection((host, port), timeout=timeout):
            pass
    except Exception as exc:
        return False, "TCP %s:%d 连接失败：%s" % (host, port, exc)
    return True, "DNS+TCP %s:%d 连通（%.0f ms）" % (host, port, (time.time() - started) * 1000)


def doctor():
    providers = build_providers()
    chain, missing = active_chain(providers)

    key_names = ["SILICONFLOW_API_KEY", "GLM_API_KEY", "DEEPSEEK_API_KEY",
                 "SENSENOVA_API_KEY", "GEMINI_API_KEY"]
    keys = []
    seen = set()
    for name in key_names:
        value, source = load_key(name)
        seen.add(name)
        keys.append({"name": name, "found": bool(value),
                     "source": source, "length": len(value) if value else 0})
    for provider in providers:
        env_name = provider["apiKeyEnv"]
        if env_name in seen:
            continue
        seen.add(env_name)
        value, source = load_key(env_name)
        keys.append({"name": env_name, "found": bool(value),
                     "source": source, "length": len(value) if value else 0})

    chain_names = [provider["name"] for provider in chain]
    entries = []
    for provider in providers:
        has_key = provider["name"] in chain_names
        reachable, detail = probe_endpoint(provider["baseUrl"])
        entry = {
            "name": provider["name"],
            "model": provider["model"],
            "endpoint": provider["baseUrl"] + "/chat/completions",
            "apiKeyEnv": provider["apiKeyEnv"],
            "hasKey": has_key,
            "inChain": has_key,
            "reachable": reachable,
            "probe": detail,
            "note": provider.get("note", ""),
        }
        if not has_key:
            entry["probe"] = "未探测（缺少 %s）" % provider["apiKeyEnv"]
            entry["reachable"] = None
        entries.append(entry)

    pillow_version = None
    try:
        import PIL
        pillow_version = getattr(PIL, "__version__", "unknown")
    except Exception as exc:
        pillow_version = "缺失（%s）" % exc

    notes = []
    if not chain:
        notes.append("没有任何 provider 检测到 key：请按下文 keys 里 found=false 的名字配置环境变量。")
    if missing:
        notes.append("未检测到的 key：%s" % "、".join(sorted(set(missing))))
    notes.append("本脚本定位：独立第二读者 / 交叉核对（主模型自己也能看图）；"
                 "默认提示词已写死「看不清就标存疑、禁止猜数值」「只转写不求解」两条规则。")

    return {
        "ok": True,
        "mode": "doctor",
        "python": sys.version.split()[0],
        "executable": sys.executable,
        "pillow": pillow_version,
        "keys": keys,
        "providers": entries,
        "chain": chain_names,
        "activeProvider": chain_names[0] if chain_names else None,
        "transcription_fields": ["subject", "problem_type", "statement_latex", "given",
                                 "find", "figure", "tables", "equations", "constants",
                                 "units_system", "low_confidence", "unreadable", "notes"],
        "prompt_modes": ["default（内置学科无关提示词）", "schema（只换字段说明）",
                         "custom（prompt 完全自定义）"],
        "notes": notes,
    }


# --------------------------------------------------------------------------- #
# 参数
# --------------------------------------------------------------------------- #
def load_spec(argv):
    """解析 CLI：--spec / --spec-json 走通用契约；位置参数 + 选项走便捷形式。"""
    spec_path = None
    spec_json = None
    positional = []
    prompt = None
    provider = None
    timeout = None
    schema = None
    lang = None
    if_doctor = False

    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--doctor":
            if_doctor = True
        elif arg == "--spec":
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
        elif arg in ("--prompt", "-p"):
            i += 1
            if i >= len(argv):
                raise ValueError("--prompt 后面缺少文本")
            prompt = argv[i]
        elif arg.startswith("--prompt="):
            prompt = arg.split("=", 1)[1]
        elif arg.startswith("--schema="):
            schema = arg.split("=", 1)[1]
        elif arg == "--schema":
            i += 1
            if i >= len(argv):
                raise ValueError("--schema 后面缺少字段说明文本")
            schema = argv[i]
        elif arg.startswith("--lang="):
            lang = arg.split("=", 1)[1]
        elif arg == "--lang":
            i += 1
            if i >= len(argv):
                raise ValueError("--lang 后面缺少语言（zh / en）")
            lang = argv[i]
        elif arg.startswith("--provider="):
            provider = arg.split("=", 1)[1]
        elif arg == "--provider":
            i += 1
            if i >= len(argv):
                raise ValueError("--provider 后面缺少引擎名")
            provider = argv[i]
        elif arg.startswith("--timeout="):
            timeout = argv[i].split("=", 1)[1]
        elif arg == "--timeout":
            i += 1
            if i >= len(argv):
                raise ValueError("--timeout 后面缺少秒数")
            timeout = argv[i]
        elif arg.startswith("-") and arg != "-":
            raise ValueError("未知参数 %r" % arg)
        else:
            positional.append(arg)
        i += 1

    spec = {}
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

    if not isinstance(spec, dict):
        raise ValueError("spec 必须是一个 JSON 对象")

    if if_doctor:
        spec["doctor"] = True
    if positional:
        spec.setdefault("images", positional)
    if prompt is not None:
        spec["prompt"] = prompt
    if schema is not None:
        spec["schema"] = schema
    if lang is not None:
        spec["lang"] = lang
    if provider is not None:
        spec["provider"] = provider
    if timeout is not None:
        spec["timeout"] = timeout
    return spec


def collect_images(spec):
    raw = spec.get("images")
    if raw is None:
        raw = spec.get("src") or spec.get("image") or spec.get("path")
    if raw is None:
        return []
    if isinstance(raw, str):
        return [raw]
    if isinstance(raw, (list, tuple)):
        return [str(item) for item in raw]
    raise ValueError("images 必须是路径字符串或路径数组")


def run(spec):
    if spec.get("doctor"):
        return doctor()

    images = collect_images(spec)
    if not images:
        raise ValueError("缺少图片：请给出 images/src 字段（或直接传图片路径）")
    for path in images:
        if not os.path.isfile(path):
            raise ValueError("图片不存在：%s" % path)

    prompt, prompt_mode = build_prompt(spec.get("prompt"), spec.get("schema"), spec.get("lang"))
    prompt_lang = str(spec.get("lang") or "zh").strip().lower() or "zh"

    try:
        timeout = float(spec.get("timeout") or DEFAULT_TIMEOUT)
    except Exception:
        raise ValueError("timeout 必须是秒数")
    if timeout <= 0:
        raise ValueError("timeout 必须大于 0")

    providers = build_providers()
    chain, missing = active_chain(providers)

    pinned = spec.get("provider")
    if pinned:
        available = {provider["name"]: provider for provider in providers}
        if pinned not in available:
            raise ValueError("未知 provider %r；可用：%s"
                             % (pinned, "、".join(provider["name"] for provider in providers)))
        chosen = available[pinned]
        value, source = load_key(chosen["apiKeyEnv"])
        if not value:
            raise ValueError(
                "provider %s 缺少 %s：请在 %s 里写入 %s=... 后重试"
                % (pinned, chosen["apiKeyEnv"], secret_env_files()[0],
                   chosen["apiKeyEnv"]))
        chosen = dict(chosen)
        chosen["_keySource"] = source
        chain = [chosen]

    if not chain:
        raise ValueError(
            "未检测到任何视觉引擎的 API key；请在 %s 设置 SILICONFLOW_API_KEY=... 后重试"
            "（也可设置 GLM_API_KEY / DEEPSEEK_API_KEY / SENSENOVA_API_KEY / GEMINI_API_KEY，"
            "或用 VISION_FALLBACKS 声明自定义 OpenAI 兼容端点）"
            % secret_env_files()[0])

    count = len(images)
    max_side = spec.get("max_side") or image_max_side(count)
    try:
        max_side = int(max_side)
    except Exception:
        raise ValueError("max_side 必须是整数")
    encoded = []
    for path in images:
        try:
            encoded.append(b64_jpeg(path, max_side=max_side))
        except Exception as exc:
            raise ValueError("图片编码失败 %s：%s" % (path, exc))

    attempts = []
    last_text = None
    last_provider = None
    for provider in chain:
        name = provider["name"]
        started = time.time()
        log("尝试 %s（%s，%d 张图，max_side=%d）" % (name, provider["model"], count, max_side))
        try:
            text = call_provider(provider, encoded, prompt, timeout)
        except Exception as exc:
            status, detail = classify_exception(exc)
            attempts.append({"provider": name, "model": provider["model"],
                             "status": status, "detail": detail,
                             "elapsed": round(time.time() - started, 2)})
            log("%s 失败（%s）：%s" % (name, status, detail))
            continue

        data, reason = extract_json(text)
        if data is None:
            # 回到自然语言不算失败：先在本 provider 上做一次「整理成 JSON」的重试。
            # 这是**纯文本**调用（不带图），很便宜，但能把一份本来要丢掉的转写救回来。
            repair_prompt = (
                prompt
                + "\n\n【重试】你上一次的回答是自然语言，没有按上面的 JSON 结构输出。"
                  "现在请**只输出那一个 JSON 对象**：不要任何解释、不要 Markdown 代码围栏。"
                  "请依据下面这条你上一条回答的原文整理，**不要新增、不要猜测**原文没有的内容：\n"
                + text)
            repaired = None
            repair_note = None
            try:
                repaired = call_provider(provider, [], repair_prompt, timeout)
            except Exception as exc:
                repair_note = classify_exception(exc)[1] or str(exc)
            if repaired:
                data, reason2 = extract_json(repaired)
                if data is not None:
                    attempts.append({"provider": name, "model": provider["model"],
                                     "status": "ok_after_repair",
                                     "detail": "首次返回自然语言，已重试整理成 JSON",
                                     "elapsed": round(time.time() - started, 2)})
                    log("%s 首次不是 JSON，整理重试成功" % name)
                    return {
                        "ok": True,
                        "provider": name,
                        "model": provider["model"],
                        "parsed": True,
                        "repaired": True,
                        "transcription": data,
                        "raw": text,
                        "repair_raw": repaired,
                        "attempts": attempts,
                        "images": images,
                        "prompt_mode": prompt_mode,
                        "lang": prompt_lang,
                        "note": "引擎首次返回自然语言，已重试整理成 JSON；raw 保留了首次原文",
                    }
                reason = "%s；整理重试后仍非 JSON：%s" % (reason, reason2)
            elif repair_note:
                reason = "%s；整理重试调用失败：%s" % (reason, repair_note)
            attempts.append({"provider": name, "model": provider["model"],
                             "status": "bad_json", "detail": reason,
                             "elapsed": round(time.time() - started, 2)})
            log("%s 返回的不是 JSON：%s" % (name, reason))
            last_text = text
            last_provider = provider
            continue

        attempts.append({"provider": name, "model": provider["model"], "status": "ok",
                         "elapsed": round(time.time() - started, 2)})
        return {
            "ok": True,
            "provider": name,
            "model": provider["model"],
            "parsed": True,
            "transcription": data,
            "raw": text,
            "attempts": attempts,
            "images": images,
            "prompt_mode": prompt_mode,
            "lang": prompt_lang,
        }

    # 有 provider 回了 200 但始终不是 JSON：仍算「部分成功」，把原文交给上层模型自己读。
    # 关键：**绝不能把这份原文丢掉**——它常常是一次完整可用的转写，只是没套 JSON 壳。
    if last_text is not None:
        return {
            "ok": True,
            "provider": last_provider["name"],
            "model": last_provider["model"],
            "parsed": False,
            "degraded": True,
            "transcription": None,
            "raw": last_text,
            "attempts": attempts,
            "images": images,
            "prompt_mode": prompt_mode,
            "lang": prompt_lang,
            "warning": "模型返回的不是合法 JSON（整理重试也没成功），已把原文放进 raw："
                       "它可能仍是可用的转写，请按自然语言使用，不要当作结构化字段",
        }

    detail = "；".join("%s(%s)" % (item["provider"], item.get("detail", item["status"]))
                       for item in attempts[-3:]) or "无可用引擎"
    raise ValueError(
        "所有视觉引擎都调用失败：%s。请检查 %s 是否正确、网络是否可达（可运行 --doctor 自检）"
        % (detail, "、".join(item["provider"] for item in attempts) or "provider 配置"))


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