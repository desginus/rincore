# RinCore 问题台账（ISSUES LEDGER）

> 2026-10-18 建立 · 状态词：待办 / 进行中 / ✔已改 / ✔已标 / ✔已核 / 存疑 / 搁置
> 本台账 = 全局推进的唯一事实源（审计、幽灵、屎山、文档、收尾全在这里挂号）。

## 统计（随批更新）
- 幽灵域：✔已核 941 / ✔已改 9 / 待办 8 / 已标 1
- 屎山域：✔已改 2 / 待办 3
- 文档域：✔已改 3 / 待办 3

## A. 幽灵更改（对齐原版 2.5.6）
- A1 ✔已核 **ALIGNED 665 件**——与 2.5.1 差异 = 仅注记头，代码本体逐字节一致。
- A2 ✔已改 **声明失真改标 ×8**：RouteActivity（±432→≈1812）/ Markdown（±22→≈465）/ ChatMessageEditedFiles（±2→≈410）/ ChatInput（±45→≈404）/ ChatDrawerVM（±31→≈362）/ WorkspaceManager（±27→≈277）/ ChatMessage（±8→≈254）/ ContextUtil（±14→≈223）（均注"vs 2.5.1·2026-10-18 复核"）。
- A3 ✔已改 **MiMoTTSProvider** Failure 分支回补（statusCode 诊断，对齐上游）。
- A4 ✔已核 **删除件 68**：无残留引用；= 搬家 / 未携带 / 上游特性自有实现 / 测试布局差异。
- A5 ✔已改（第一批）+ 待办 **speech 簇对齐**：6 文件整件取回上游 2.5.6 原文（MiMoTTSProvider / Gemini·OpenAI·MiniMax TTSProvider / OpenAIRealtimeASR（voiceTurn 轮次跟踪 + failed 上报恢复）/ TtsController）——均与 2.5.6 零差（numstat 复核）；**待办：QwenTTS 全链（Provider + Setting + UI，涉及设置迁移 → 排期）**。
- A6 待办 P2 9 件细看（ItemActionMenu / ReorderableDrag / workspace ExampleUnitTest / ai·app 构建件等，低优先）。
- A7 ✔已核 方法与工件：`GHOST-REPORT.md` + final4 / dossier / numstat / diffs 全链留档。

## B. 屎山消化
- B1 ✔已改 `.versions` 残留 13 处清除；实验件 6 件归档 `archive/2026-10-experiments`。
- B2 待办 死代码扫描第一轮：未用常量 / 孤儿文件 / 重复实现 / 陈旧注释抽查（脚本待跑）。
- B3 待办 README×3 更新日志与徽章同步（现停 v4.8.153 / v4.8.101 / v4.8.56）。
- B4 待办 `module-map.md` 刷新（+audit 目录说明）。
- B5 ✔已核 TODO/FIXME/HACK 全库仅 2 处（健康）。

## C. 文档系统评审（用户点④ · 独立判断"是否最合理/更高效架构"）
- C1 待办 全链评审：上传 → 解析（pdf/docx/pptx/epub）→ 注入（DocumentAsPrompt classic/compat + UploadsListing）→ 工具（upload_fetch/上传码）→ 渲染/导出（render chain）→ 生成技能（docx/pptx/xlsx+officecli）→ 收件箱；产出提案。

## D. 检索体系
- D1 ✔已改 CODE_INDEX（906 文件）/ SYMPTOM_ROUTER（~30 族）/ APP_MAP §0 检索入口（仓库 docs/）。
- D2 待办 CODE_INDEX 47 条"待校订"逐批补写。
- D3 待办 索引维护约定写入 dev-process（新文件必须带头部标注；索引随版本维护）。

## E. 阶段推送
- E1 ✔已推 **v4.8.187**：speech 对齐 6 文件 + 8 件改标 + 审计双文档进仓（docs/audit/）+ 知识库（CI 核验中）。
- E2 已排期 后续批：speech 对齐（A5）→ 死代码（B2）→ 文档系统评审（C1）→ README/module-map（B3/B4）。
