---
name: engineering-workbench
description: 通用工科解题工作台（源自 DSH 插件 dsh-mech-workbench，MIT）。读题图→回读自检→求解→可视化→出图后自检的确定性链路：题目照片预处理、静定结构内力/应力校核、力学标准图（轴力/剪力/弯矩/莫尔圆/挠曲线）、通用符号求解、通用出图与视觉校核。工科/力学/数学题目解答与出图任务使用。
---

# 工程解题工作台（engineering-workbench）

来源：github.com/insideyou-0501/dsh-mech-workbench v0.2.1（MIT）。
本技能只带**脚本引擎**（原图样、注册壳原样不带入）：脚本在沙箱 `/builtin_skills/engineering-workbench/scripts/` 下，
用 `workspace_shell` 的 `python3` 直接调用，结果以 JSON/图片落 `/workspace` 或 stdout。

## 铁律（五步链路，不可跳步）

1. **读题图** → `preprocess.py`（对题目照片做 17 种预处理算子 + 分栏切图，产出 `problem.png` / 各子题图到 `/workspace/`）
2. **回读自检** → 把预处理产物重新读一遍，与原题逐字比对（`mech_verify_reading` 语义：并排回读图）；**不一致先纠正预处理再往下**
3. **求解** → `analyze.py`（静定反力/NQMT/截面应力 + 8 项独立校核）；符号计算用 `symbolic.py`（solve/diff/integrate/dsolve/optimize…；缺 sympy 走数值等价路径并**如实声明**）
4. **可视化** → `figure.py` 配 `presets.py`（学科预设：轴力/剪力/弯矩/扭矩/莫尔圆/挠曲线…引擎不认识学科，预设展开通用系列）
5. **出图后自检** → `annotate.py`/`vision.py`（学生/生成图讲解标注 / 独立视觉评审 PASS/FAIL；视觉评审需要外部视觉 API key，未配置则跳过此步并如实说明）

## 快速用法（workspace_shell）

```bash
# 依赖自检（Pillow/numpy 已内置；缺啥补啥：
#   pip3 install --break-system-packages sympy matplotlib 可选）
python3 -c "import PIL, numpy; print('ok')"

# 1) 题照预处理（输入输出都放 /workspace）
python3 /builtin_skills/engineering-workbench/scripts/preprocess.py --help
# 2) 静定求解 + 校核
python3 /builtin_skills/engineering-workbench/scripts/analyze.py --help
# 3) 符号求解
python3 /builtin_skills/engineering-workbench/scripts/symbolic.py --help
# 4) 出图（预设名见 presets.py；先 help 看参数）
python3 /builtin_skills/engineering-workbench/scripts/figure.py --help
# 5) 标注讲解
python3 /builtin_skills/engineering-workbench/scripts/annotate.py --help
```

每个脚本先 `--help` 看参数再调用；具体 CLI 用法与数据形状以 `references/engineering-*` 四份原文为准。

## 硬约束（原 README 原文）

- 不静默降级：缺依赖时要么走数值等价路径并声明，要么返回可读的 `unsupported`，绝不给看似正常的错答案
- 复核是独立算法（残差/求导往返/辛普森对比/特征值残差/密集扫描/截断复核），不是把同一个公式再算一遍
- 符号约定：N 拉/轴力为正、M 下侧受拉为正、默认单位 m/kN/MPa
- 已知边界：`analyze.py` 只解**静定**问题；视觉评审依赖外部 key（未配则跳过并说明）
- sympy 可选：需要时 `pip3 install --break-system-packages sympy`（或 mpmath）
