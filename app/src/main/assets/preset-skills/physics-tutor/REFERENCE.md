# 题型卡 · 押题卷 · 管线参考（physics-tutor）

## 一、题型卡 schema（每题一张卡，人工/模型共建，**sympy 解过才准入库**）

```json
{
  "id": "mech-incline-friction-001",
  "title": "斜面滑块（含摩擦）",
  "model": "斜面 + 滑动摩擦",
  "figure_template": "incline",
  "figure_params": {"theta": 30, "mu": 0.2, "slide": "down"},
  "given": ["m", "theta", "mu", "g"],
  "unknowns": ["a", "s"],
  "equation_skeleton": "m*g*sin(theta) - mu*m*g*cos(theta) = m*a",
  "solution_verified": {"a": "g*(sin(theta)-mu*cos(theta))", "checked_by": "mechanics_solve"},
  "traps": ["上滑时摩擦力方向相反", "μ 是动摩擦因数不是静摩擦"],
  "variation_axes": ["theta", "mu", "初速度", "外加水平/沿面力"],
  "source": {"file": "习题册03.pdf", "page": 7, "crop": "img/p007_q7.png"}
}
```

存放建议：`题库/<科目>/<题型ID>.json` + `题库/<科目>/img/`（原图裁剪）。
检索：标题/关键词/模型直接 grep；体量大时再接向量库（LightRAG/chroma，非必需）。

## 二、押题卷生成流程（全自动 + 可校验）

```
① 选考点分布（各题型出题数）
② 检索题型卡 → 变参采样（如 theta∈{25,30,37}, mu∈{0.1,0.2,0.3}, m∈{1,2,3}）
③ sympy 求解校验：有解？唯一？量级合理？（残差必须为 0）
④ mechanics_draw 渲染配图（带方向断言）
⑤ 组装卷面：题目 MD + 图；答案页附"方程骨架 + 关键步骤"
⑥ 输出 md 文件（可再转 PDF/DOCX）
```

要点：**变参必须重算**（不能只改数字沿用旧答案）；同一张卷子覆盖多个 variation_axes 才算"新题"。

## 三、习题册数字化管线（成本从低到高）

| 层级 | 手段 | 成本 | 适用 |
|---|---|---|---|
| ① | pymupdf4llm（有文字层直接转） | 秒级/页，零模型 | 电子版/可选中 PDF |
| ② | 300dpi 渲染 + RapidOCR（CPU 离线） | ~10s/页 | 扫描件正文 |
| ③ | 公式小块 → 公式 OCR（书籍字体识别近乎完美，自渲染图需用 STIX/Times 字体） | 秒级/块 | 关键公式 |
| ④ | 图区裁剪**保留原图**（给模型看图） | ~0 | 所有图/受力图 |
| ⑤（可选） | MinerU/marker 高精度批处理（需 torch 数 GB） | 分钟级/页 | 版式极复杂页 |

精度提示：OCR 对 `θ μ` 等希腊字母与上标易错；**答案与公式一律人工或工具复核**。

## 四、图理解（把"看懂"拆成可验证的动作）

1. 裁出图区 → 放大；
2. 分块提问：物体/连接/角度/已知力/运动方向（一次问一簇，别一次问全图）；
3. 汇总成结构化参数（就是题型卡里的 figure_params）；
4. 用 `mechanics_draw` 重画一遍 → 与原图对照（发现理解错误）；
5. 参数进 `mechanics_solve` 求解。

## 五、常见坑

- 图：几何"像"≠物理对（mg 必须竖直，别沿斜面）——模板+断言是唯一可靠做法。
- 公式：手写体/低清扫描识别率骤降，宁可标记"待复核"。
- 单位：数值代入前统一 SI；g 取 9.8 或 9.81 全卷要一致。
- 押题：变参后极限情形（μ=0、θ→0）应退化到已知结论——用 mechanics_solve 顺手验。
