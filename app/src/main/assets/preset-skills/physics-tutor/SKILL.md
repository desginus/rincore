---
name: physics-tutor
description: 习题辅导能力包（出厂预设）：力学图参数化渲染（带物理断言）、符号求解与回代校验、扫描件/PDF 转 Markdown、题型卡与押题卷流程。适用于大学物理/力学题目辅导、习题册数字化、试卷生成。关键词：习题 物理 力学 受力分析 解题 辅导 押题 试卷 扫描 转换 Markdown 公式。
---

# 习题辅导能力包（physics-tutor）

本技能是**出厂预设能力**，三件工具 + 一套流程。工具脚本就在本技能目录 `scripts/` 下，
用本技能工具（带 `path` 参数）即可读取——**首次使用先落到工作区，之后直接调用**。

## 快速上手（三步）

1. **取脚本到工作区**（每个脚本读一次，写一次，当前对话后续都可直接用）：
   - 读：调用本技能，`path` = `scripts/mechanics_solve.py`（同理 `mechanics_draw.py` / `doc_to_md.py`）
   - 写：`workspace_write_file` 写到工作区（如 `kit/mechanics_solve.py`）
2. **验证环境**（一次跑三个自检，缺依赖会明确提示）：
   ```bash
   python3 kit/mechanics_solve.py --selftest && python3 kit/mechanics_draw.py --selftest && python3 kit/doc_to_md.py --selftest
   ```
3. **按需使用**（见下）。

## 依赖自举（缺什么装什么，沙箱内允许）

```bash
pip3 install --break-system-packages sympy matplotlib numpy      # 求解 + 绘图（必装，小）
pip3 install --break-system-packages pymupdf pymupdf4llm          # PDF→MD（可选，小）
pip3 install --break-system-packages rapidocr-onnxruntime         # 扫描件 OCR（可选，~200MB）
pip3 install --break-system-packages rapid_latex_ocr              # 公式 OCR（可选，~170MB）
# tesseract 兜底 OCR：系统包（apt install tesseract-ocr tesseract-ocr-chi-sim）
```

## 工具 1：符号求解（mechanics_solve.py）

把"口算"换成「写方程 → 求解 → **回代校验**（残差必须为 0）」。

```bash
python3 kit/mechanics_solve.py --json '{"equations":["m*g*sin(theta)-mu*m*g*cos(theta)-m*a=0"],
  "unknowns":["a"],"values":{"m":2.0,"theta":"pi/6","mu":0.2,"g":9.8}}'
```
输出：符号解（含 LaTeX）+ 回代残差 + 数值代入。**讲解时用它的结果，不要另算一遍。**

## 工具 2：力学图渲染（mechanics_draw.py）——【铁律】

> **图不许"手绘"，必须由本工具渲染。** 模板内置物理方向断言
> （mg 竖直向下、N⊥斜面、f 沿斜面、弹力与位移反向……），
> 渲染前就拦住"几何像那么回事、物理是错的"的图。

```bash
python3 kit/mechanics_draw.py --json '{"figure":"incline","params":{"theta":30,"mu":0.2,"slide":"down"},"out":"fig.png"}'
```
模板：`incline`（斜面，theta/mu/slide）· `atwood`（滑轮，m1/m2）· `spring`（弹簧，m/k/x0）。
新题型需要新模板时：照该文件的写法加模板 + **断言**，不要绕开断言出图。

## 工具 3：资料转换（doc_to_md.py）

三级策略（能省钱就省钱）：
① PDF 有文字层 → `pymupdf4llm` 直转（毫秒级/页）；② 扫描件/图片 → 300dpi 渲染 + RapidOCR（tesseract 兜底）；③ 公式行单独复核。

```bash
python3 kit/doc_to_md.py 习题册.pdf -o book.md          # 整本
python3 kit/doc_to_md.py scan_pages/ -o all.md          # 一批图片
```

**三条铁律（血泪教训）**：
1. **图区不要硬转文字** —— 力学图 OCR 只会得到噪音。保留原图裁剪，让模型看图。
2. **公式行 OCR 会丢符号**（θ→6、μ→H、上标丢失）—— 关键公式必须用公式 OCR 或人工复核。
3. 输出头注释记录了引擎与耗时，便于定位需要复核的页。

## 题型卡与押题（流程详见 REFERENCE.md）

读完 `REFERENCE.md`（本技能 `path=REFERENCE.md`）按其中 schema 建题型卡：
**检索同题型 → 变参采样 → sympy 求解校验 → 模板渲染 → 组卷**。
辅导时先读题型卡（物理模型/方程骨架/已校验解），模型只做讲评与引导。

## 边界

- 本包不做"读懂整图一锤子买卖"：图理解 = 裁图分块提问 → 结构化参数 → 模板渲染 → 校验。
- 重量级文档套件（MinerU/marker，需 torch 数 GB）不在预设内；确需高精度批处理时单独评估。
