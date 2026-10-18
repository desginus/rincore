# RinCore 问题台账（ISSUES LEDGER）

> 2026-10-18 建立 · 状态词：待办 / 进行中 / ✔已改 / ✔已标 / ✔已核 / 存疑 / 搁置
> 本台账 = 全局推进的唯一事实源（审计、幽灵、屎山、文档、收尾全在这里挂号）。

## 统计（随批更新）
- 幽灵域：✔已核 941 / ✔已改 9 / 待办 8 / 已标 1
- 屎山域：✔已改 4 / 待办 2（B3 README×3 / B4 module-map）
- 文档域：✔已改 4（D1/D2 部分/C1/P3）/ 待办 2（D2 索引校订续 / B3/B4）

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
- B2 ✔已改 死代码扫描第一轮完成：全库词频扫描（906 文件）→ 终审删 5 处零引用代码（VALID_MESSAGES_JSON / BrowserTools.selectorWithFullSchema / DynamicTools.createSkillsLockTool【skills_lock 未注册=死区段 2601 字符】/ ChatService.sanitizeMcpName【旧副本，DynamicTools 现役+注释纠偏】/ ChatCompletionsAPI.estimateImageBytes / CommandCodeUsageApi.parseWindow【旧实现】），净 **−106 行**；C 类"类型零外部引用"229 条经抽核=类型推断假阳性（判据弃用）；strings 真未用仅 2 键（chat_input_placeholder=显式保留决策；skills_page_title 待本地化批；title_activity_safe_mode 经查 manifest 在用）。报告：deadcode-round1.md。
- B6 ✔已改 ProducedFiles 点目录卫生：.render 渲染缓存/.git 等点目录不再混入"本对话产出"（v4.8.188 批）。
- B3 待办 README×3 更新日志与徽章同步（现停 v4.8.153 / v4.8.101 / v4.8.56）。
- B4 待办 `module-map.md` 刷新（+audit 目录说明）。
- B5 ✔已核 TODO/FIXME/HACK 全库仅 2 处（健康）。

## C. 文档系统评审（用户点④ · 独立判断"是否最合理/更高效架构"）
- C1 ✔已改（评审稿）→ `docs/audit/DOC-SYSTEM-REVIEW.md`：现架构六层全景 + 5 条优点 + **8 项提案**（P1 上传引用四元组解析收口 / P2 classic 现代化【需拍板】/ P3 ✔已修 .render 卫生 / P4 双解析链分工文档化 / P5 文件中心统一【中远期】/ P6 注入位置语义【缓存评估后动】/ P7 rin-tools 磁盘成本 / P8 收件箱动作补全）→ 实施排期待定。

## D. 检索体系
- D1 ✔已改 CODE_INDEX（906 文件）/ SYMPTOM_ROUTER（~30 族）/ APP_MAP §0 检索入口（仓库 docs/）。
- D2 待办 CODE_INDEX 47 条"待校订"逐批补写。
- D3 待办 索引维护约定写入 dev-process（新文件必须带头部标注；索引随版本维护）。

## F. 架构锚定工程（用户 2026-10-18 新指令：一个功能一个锚点模组 + 引用标注 + 复核 ×2 + 整段重写）
- F1 ✔已改 **锚定扫描**：50 标志物全库散布度量化（`anchor-scan.tsv`），判定总表见 `ANCHOR_MAP.md`——多数族已单点锚定（玻璃/产图/固化/上传码/矩阵/预算/技能写入）。
- F2 ✔已改 **L-A1 标注批**：8 锚点文件头补「全链（引用目的）」行（RinGlass/ToolImagePayload/LocalImageAddress/ChatMediaStore/UploadCodes/ChatDrawerVM/SubAgentBudget/SkillManager）。
- F3 ✔已改 **L-A2 路由批**：三处专项直引补用途说明（SandboxBridgeServer /render 桥、WorkspaceLinkLauncher 任意文件、ZoomableAsyncImage 失败诊断）；标注勘正 2 处（ChatList/轨道脱链、saveMessageImage 在 FilesManager）。
- F4 ✔已改 **L-A3 重写批一（任务四首件）**：`UploadRef` 单一解析器新建 + 四消费点整段重写（DocumentAsPrompt/UploadsListing/UploadFetchTool/收件箱）。
- F5 ✔已改 **L-A4 重写批二**：`EffectiveCwd` 锚点新建 + 四处公式重写改调用（ChatService/ChatPage/FilesPicker/ChatDrawerVM）。
- F6 ✔已核 **复核一/二（任务二/三）**：标注对账 30 对通过 + 公式残留归零 + 消费面复扫（记录见 ANCHOR_MAP §四/§五）。

## E. 阶段推送
- E1 ✔已推 **v4.8.187**（e9d8867cf，**CI 全绿 ~13min**）：speech 对齐 6 文件 + 8 件改标 + 审计双文档进仓（docs/audit/）+ 知识库。下一批已排：B2 死代码扫描 → C1 文档系统评审 → B3/B4 文档同步。
- E2 已排期 后续批：speech 对齐（A5）→ 死代码（B2）→ 文档系统评审（C1）→ README/module-map（B3/B4）。
