# 幽灵更改审计报告（GHOST REPORT · v1 定稿）

> 2026-10-18 · 基线: `upstream-2.5.1` / `upstream-2.5.6`（447bb7e）· 存档点 `checkpoint/v4.8.185-pre-audit`
> 目标：查清"改错模块且未回滚"的累积漂移（幽灵更改）并区分"有实际意义的特色改动"。
> 方法：双轴全库 numstat（`--no-renames`）→ 逐文件差异定量（注释级/代码级）→ 自述头解析（差异±N/基线/来源四种格式）→ 痕迹（文件内 v4.x 注释）与知识库（changelog/bug-record/decisions/lessons）取证 → 人工开审。
> 工件（`/workspace/docs/audit/`）: `numstat-251-nr.tsv` / `numstat-256-nr.tsv` / `ghost-dossier.tsv` / `ghost-final4.tsv` / `diffs-251/`（逐文件 diff 快照）。

## 一、总账（1100 个与上游有差异的文件）

| 分类 | 数量 | 判定 | 动作 |
|---|---|---|---|
| ALIGNED-仅注记 | 665 | 健康：与 2.5.1 的差异 = 仅我方注记头（代码本体逐字节一致） | 无 |
| 有据-自述 | 275 | 文件头自带"差异 ±N / 来源 / 基线"声明 | 无（自述在案） |
| P2-复核 | 9 | 弱痕迹件（ExpressivePopup / ConversationSessionManager / AIRequestInterceptor / ItemActionMenu / ReorderableDrag / 构建件 / ExampleUnitTest / Workspace.kt） | 抽样通过 = 已知功能件或构建件 |
| **P1-真无据** | **31** | 无声明 + 无痕迹 | 逐件裁定（见 §二） |
| 声明失真-改标 | 8 | 声明数值与实测差距大 | ✔ 已改标（本批） |
| 有据-痕迹 | 1 | — | 无 |
| ADDED-BY-US | 407 | 我方新增文件 | 非嫌疑 |
| DELETED-BY-US | 82（去重后 68） | 上游有、我方无 | ✔ 全量核查（见 §三） |

## 二、P1-真无据 31 件逐件裁定

- **6 件 = 健康移植**（与 2.5.6 完全一致；差异仅因基线是 2.5.1）：InteractionsApiTest / McpToolSchemaTest / RequestHeadersTest / ProviderConfigureConvertToTest / OAuthLoopbackCallbackServer（+Test）→ 无动作。
- **13 件 = 构建独立化（有据）**：12 个 `build.gradle.kts` + `settings.gradle.kts` 用"显式配置"替代上游 `build-logic` 约定插件（fork 未携带 build-logic）；根 build 移除 google-services / crashlytics（无 Firebase 化）→ 无动作（标注）。
- **8 件 = speech 簇（本轮最大发现：对齐欠账）**：
  - `QwenTTSProvider` + `TTSProviderSetting(Qwen)`：上游把 Qwen TTS 重做为阿里云百炼 MaaS（端点 `/services/audio/tts/SpeechSynthesizer` + 旧模型拦截 + EOF 兼容 + format/sampleRate 字段）；我方保留旧 dashscope/aigc 实现（`languageType` 字段）→ **登记待办：移植评估（涉及设置字段/UI 迁移）**
  - `MiMoTTSProvider` / `Gemini·OpenAI·MiniMax TTSProvider` / `TtsController`：上游错误路径带 `response.close()` 与 statusCode 诊断（我方曾简化）；TtsController 取消分支的上游版含 cache 清理 → **✔ 本批整件取回上游原文（与 2.5.6 零差）**
  - `OpenAIRealtimeASRController`：我方移除 voiceTurn 轮次跟踪 + failed 上报 → **✔ 本批整件取回上游（语音会话轮次信号恢复）**
  - `Gemini/OpenAI/MiniMax TTSProvider` + `TtsController`：小差异（≤14 行）→ 登记随 speech 批核对
- **5 件 = 测试随码演进（有据）**：ChatServiceTest（上游旧测试随我方功能重构而移除）/ SkillPathsTest / TimeReminderTransformerTest / ToolApprovalStateTest / PromptInjectionTransformerTest → 无动作（标注）。
- **3 件 = 微差已核**：oauth Loopback（实为移植健康件）/ Theme.kt / 杂项 → 无动作。

## 三、删除件 68 全量核查结论

- **无残留引用**：对全部删除件做全库基名扫描，仅 3 处告警且均为误报——`ClaudeProvider`（搬家：`providers/claude/` → `providers/`）、`Lifecycle`（通用词误伤：全库 260 处为 androidx Lifecycle 引用）、`videogen/VideoGeneration`（上游有 videogen 模块，我方在 app 内自实现视频生成）。
- 分类：**搬家 / 未携带（有声/字幕/捐赠页/排行榜等） / 上游特性自有实现（视频生成） / 测试布局差异**。未发现"误删原件"。

## 四、结论

1. **未发现结构性幽灵**——"无据"层全部落定为"移植 / 构建 / 测试 / 自有实现"四类；不存在"改错模块且未回滚"级别的病灶。
2. 真问题收束为 **speech 模块对齐欠账（8 件，QwenTTS 为主）**——登记台账逐件消化。
3. 对齐基本面健康：**665 件仅注记头** + **275 件自述在案** + 407 件我方新增；标注体系（注记头/自述差异）经本次全量复核**可信**。
4. 本批已执行修复：声明失真改标 ×8（台账 A2）+ speech 6 文件整件对齐 2.5.6（A3/A5 第一批：MiMo / Gemini / OpenAI / MiniMax / ASR / TtsController）。

## 五、方法可复跑（供后续）

```bash
# 双轴差异
git diff --no-renames --numstat upstream-2.5.1 -- '*.kt' '*.kts'
git diff --no-renames --numstat upstream-2.5.6 -- '*.kt' '*.kts'
# 分类链: numstat → 逐文件 diff（注释级/代码级）→ 自述头/痕迹/知识库 → 人工开审
```
