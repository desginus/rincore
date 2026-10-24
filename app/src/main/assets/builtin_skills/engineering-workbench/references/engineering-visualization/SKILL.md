---
name: engineering-visualization
description: 通用可视化规范。任何需要图形化表达的内容（内力图/受力图/函数图像/数据趋势/占比对比/分布/拟合曲线/流程图/架构图/节点连线图/表格/基于原图的讲解标注）都使用。由本技能决定"该不该画、画哪种、用哪个工具、spec 怎么写"，不依赖预设模板。
---

# 0 总原则

- 图必须**明显优于文字**才画：关系、趋势、分布、结构、位置、过程。
- **先算数据、后画图**：图里每个数字都要能回溯到计算或题面。示例数据必须标"示例"。
- 画不了 / 画不准 → 降级为表格或文字，并说明为什么。**禁止错误图形，也禁止留空。**

# 1 该不该画（按顺序命中即停）

1. 图明显优于文字？否 → 直接文字/表格。
2. 核心是**精确数值的趋势/占比/比较/分布** → 统计图：`bar`/`barh`/`stacked_bar`/`pie`/`line`/`step`/`scatter`/`errorbar`/`hist`/`box`/`stem`。
3. 核心是**函数/曲线/参数变化** → `function` / `parametric` / `line`，标注极值、交点、渐近线。
4. 核心是**受力/几何/矢量/物理模型** → 坐标系 + `polygon`/`arrow`/`circle`/`dimension`；
   工程力学专属图（N/Q/M/T、应力分布、莫尔圆、挠曲线）用 `mech_diagram` 的 preset。
5. 核心是**流程/机制/架构/时间线** → `graph`（节点 + 连线，支持 `directed`）。
6. 需要**基于学生原图讲解** → `viz_annotate`（在原图上标注，不改变原图）。
7. 核心是**数据恢复/拟合/插值** → `solve_math` 的 `fit` / `table` 出数据，再交给引擎画。
8. 结构或事实不足 → 文字回答，说明不画图的原因。

# 2 用哪个工具

| 场景 | 工具 |
|---|---|
| 轴力/剪力/弯矩/扭矩图、应力分布、莫尔圆、挠曲线 | `mech_diagram`（力学预设，已含正负配色、零线、极值引线标注带） |
| 其他一切学科无关的图 | `viz_figure`（单一声明式引擎） |
| 在学生原图上圈画讲解 | `viz_annotate` |
| 出图后自查图面质量 | `viz_check`（独立视觉模型，返回 PASS/FAIL 与具体问题） |

**`viz_figure` 是唯一通用引擎，不要为某个学科新开画图脚本。** 缺图元就在 spec 里用已有图元组合；
组合不了再考虑给引擎加图元（`SERIES_KINDS`），而不是写一次性代码。

# 3 spec 写法要点（`viz_figure`）

- 顶层：`title`、`subtitle`、`note`（底部注）、`width`/`height`、`dpi_scale`、`grid:{rows,cols,sharex}`、`axes:[...]`。
- 每个 axes：`type`（`cartesian`/`logx`/`logy`/`loglog`/`polar`）、`xlim`/`ylim`、`xlabel`/`ylabel`、
  `unit`、`title`、`legend`、`aspect:"equal"`、`grid`、`series:[...]`。
- 常用 series：`line`/`step`/`scatter`/`area`/`bar`/`barh`/`stacked_bar`/`pie`/`hist`/`box`/`stem`/
  `errorbar`/`quiver`/`contour`/`heatmap`/`surface3d`/`function`/`parametric`/
  `polygon`/`circle`/`ellipse`/`arrow`/`vline`/`hline`/`text`/`annotate`/`table`/`graph`。
- 多图共享 x 轴（例如 Q、M 上下对照）：用 `grid` + 每个 axes 的 `slot:[row,col]`。
- 关键数值直接标在图上：分段值、极值点、突变点、交点 → 用 `annotate`（带引线）或 `marks`。
- **正方向/符号约定必须显式写在图上或 `note` 里**（例如"剪力使微段顺时针为正，向上绘制"）。

# 4 绘制规范（所有图通用）

- 坐标轴、单位、图例、刻度齐全；轴名与单位不能被刻度或数据压住。
- 颜色只作辅助，不能是唯一信息通道（同时用线型/标记/直接标注）。
- 标注不许压住数据点、曲线、轴名；宁可挪到图外也不能叠。
- 数值位数与题面一致；单位写全，不要只写数字。
- 中文字体：引擎在 matplotlib 下用微软雅黑/黑体，Pillow 兜底也用系统字体；缺字会进 `warnings`。

# 5 出图后必须自查

1. 调用 `viz_figure` 时把 `check:true` 打开（或出图后单独调 `viz_check`）拿独立评审结论。
2. 评审报 FAIL 或列出 overlap/clipping 时，**改 spec 重画**，不要把有问题的图交付。
3. 你现在自己能看图：对照评审结论**亲眼确认**一遍再交付。
4. 交付时说明产物路径；图与答案的数值必须一致（极值直接引用工具返回的 `extrema`，不要另估）。

# 6 失败降级链

复杂图 → 静态简化图 → 表格/结构化文字。
任何情况不得输出空白图或明知道画错的图；Pillow 后端不支持的部分会进 `unsupported`，要如实转达学生。