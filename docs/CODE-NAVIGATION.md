# 代码导航（CODE-NAVIGATION）

> 目的：**「我要改 X，先看哪几个文件」**。按任务组织，不按目录。
> 每条都可用 `bin/origin of <path>` 查来源（UPSTREAM / MODIFIED / PORTED / OURS），用 `bin/origin diff <path>` 看相对 3.1.0 的改动。
> 全量来源清单见 **本仓库** `docs/FILE-ORIGIN.md`（自动生成）。
> **两份 docs 的分工**：`rincore-adopt/docs/` 随代码走（FILE-ORIGIN / RELEASE-NOTES / 测试债）；
> `/workspace/docs/` 是工作区文档中心（体检报告 / 契约 / 经验库）。下文提到**体检/契约类**文档都在后者。

## 0. 三条最常用的查询

```bash
cd /workspace && ./bin/origin modules        # 哪块最乱（按模块聚合四类占比，0.4s）
./bin/origin modified                        # 我们改过的 231 个文件（按改动量排）
./bin/origin of app/src/main/java/.../ChatService.kt   # 单文件溯源
```

---

## 1. 对话与消息（最核心，也最容易改坏）

| 关注点 | 文件 | 来源 |
|---|---|---|
| 发消息、落库、派发队列 | `app/.../service/ChatService.kt` | MODIFIED（+666/-78） |
| 会话内存状态 / 原子写 | `app/.../service/ConversationSession.kt` | MODIFIED |
| 会话生命周期 / 引用计数 | `app/.../service/ConversationSessionManager.kt` | MODIFIED |
| 生成循环、工具调度、超时 | `app/.../data/ai/GenerationHandler.kt` | MODIFIED |
| 页面状态（VM） | `app/.../ui/pages/chat/ChatVM.kt` | MODIFIED |
| 消息渲染 | `app/.../ui/components/message/ChatMessage.kt` | MODIFIED |
| 消息操作菜单 | `app/.../ui/components/message/ChatMessageActions.kt` | MODIFIED（-305） |

**改这里的铁律**（都是踩过的）：
- 消息增删改一律 `session.apply { current -> ... }` 原子读改写；**禁止「读快照 → 改 → 整对象写回」**（跨挂起点必丢）。
- `saveLock` 内**绝不允许** suspend 的会话初始化/加载调用（v5.0.97 死锁）。
- 压缩是**结构性减少**，必须走 `forceReplace = true`，否则被判「旧快照」丢弃（v5.1.19 扣费零结果）。
- 契约文档：`docs/会话架构全量体检-2026-10-24.md`、`docs/切换会话时序核验-v5.0.84.md`

---

## 2. 记忆系统

| 关注点 | 文件 |
|---|---|
| 唯一存储 + 所有读写门面 | `app/.../data/repository/MemoryRepository.kt`（MODIFIED，+750） |
| 表结构 / 软删过滤 | `app/.../data/db/dao/MemoryFileDAO.kt` |
| 实体与 `layer` 派生 | `app/.../data/model/MemoryFile.kt` |
| 注入 prompt（唯一入口） | `app/.../data/ai/prompts/MemoryPrompts.kt` |
| 六个记忆工具 | `app/.../data/ai/tools/MemoryTools.kt` |
| 做梦整理 | `app/.../data/ai/MemoryConsolidator.kt` + `app/.../data/ai/MemoryConsolidationScheduler.kt` |
| 管理页（2×3 分流） | `app/.../ui/pages/assistant/detail/AssistantMemoryPage.kt`（MODIFIED，+1017） |
| 可见性判据（唯一实现） | `MemoryRepository.visibleForInjection` |

**契约**：`docs/记忆系统-当前契约-v5.3.26.md`（**只写不变式，不写实现**——写实现必然过期）。
**三条不可动**：删除=软删 / path 六形态匹配 / 归属互斥（对话优先于包）。
**自查**：`adb logcat -s MemoryInject` 看 `structured=N legacy=M`。

---

## 3. 工具系统

| 关注点 | 文件 |
|---|---|
| 8 个工作区工具 + 描述 | `app/.../data/ai/tools/WorkspaceTools.kt` |
| 工具池构建（模型实际拿到的） | `app/.../data/ai/tools/ToolsBuilder.kt` |
| 工具矩阵预览（UI 数据源） | `app/.../ui/pages/setting/ToolPreviewBuilder.kt` |
| 区内分类 | `app/.../data/ai/tools/routing/ZoneRouter.kt`、`app/.../data/ai/tools/routing/ToolZone.kt` |
| 区配置归一/合并 | `.../data/ai/tools/routing/ZoneOps.kt` |
| 工具 UI 渲染器注册表 | `app/.../ui/components/message/tools/ToolUI.kt` |
| 超时分档 | `GenerationHandler.toolExecutionTimeoutMs()` |

---

## 4. 模型与 Provider

| 关注点 | 文件 |
|---|---|
| 模型/Provider 数据类 | `ai/.../provider/Model.kt` |
| OpenAI 兼容（含 tools 门控） | `ai/.../provider/providers/openai/ChatCompletionsAPI.kt` |
| Claude | `ai/.../provider/providers/claude/ClaudeProvider.kt` |
| 快速/极致路由（能力准入） | `app/.../data/ai/ModelRouting.kt` + `SettingsExt.resolveRoutedModel` |
| 首字统计 | `app/.../data/ai/ModelLatencyStats.kt` |
| 重试策略 | `app/.../data/ai/RetryPolicy.kt`、`ai/.../core/WatchdogPolicy.kt` |
| 连接预热 | `app/.../service/EnvironmentOptimizer.kt`（`ConnectionWarmer`） |

**关键门控**：`tools` 参数只在 `abilities.contains(ModelAbility.TOOL)` 时发送（`ChatCompletionsAPI:1070`）——路由到未声明 TOOL 的模型会把工具调用当正文（v5.3.35）。

---

## 5. 设置与持久化

| 关注点 | 文件 |
|---|---|
| 顶层 `Settings` 数据类 | `app/.../data/datastore/Settings.kt`（从 `app/.../data/datastore/PreferencesStore.kt` **拆出来**的） |
| 扩展函数 | `app/.../data/datastore/SettingsExt.kt` |
| **字段映射表（新增字段必加）** | `app/.../data/datastore/SettingsFields.kt` |
| 设置页导航 | `app/.../ui/pages/setting/SettingPage.kt` |

**必踩坑**：`Settings` 顶层字段若不加进 `SETTINGS_FIELDS`，**内存有效、重启即丢**（v5.1.11）。`DisplaySetting` 这类整块 JSON 序列化的子对象不受影响。

---

## 6. 工作区 / Shell / 文件

| 关注点 | 文件 |
|---|---|
| Shell 执行 | `workspace/src/main/java/me/rerere/workspace/ProotShellRunner.kt`、`.../WorkspaceManager.kt` |
| 常驻会话池 | `app/.../data/workspace/ShellSessionPool.kt` |
| 工具→仓库 | `app/.../data/repository/WorkspaceRepository.kt` |
| 产出登记 | `app/.../data/ai/tools/ProducedFiles.kt` |
| 工作区自愈 | `app/.../data/workspace/WorkspaceHealer.kt` |
| 路径形态（唯一出口） | `app/.../utils/LocalImageAddress.kt`、`ProducedFiles.normalizeScope` |

**代价对照**：宿主专用工具（read/write/edit/glob/grep）毫秒级；shell 里 `cat/ls/find` 每次付 1~3s proot 冷启动。

---

## 7. 渲染与导出

| 关注点 | 文件 |
|---|---|
| 正文公式（Compose） | `app/.../ui/components/richtext/LatexText.kt` + `markdown/.../LatexCommandSanitizer.kt` |
| 表格/网页视图（WebView） | `app/src/main/assets/html/mark.html` + `vendor/`（24 文件，零 CDN） |
| 渲染端到端测试 | `tools/test-mark-render.js`（26 项，CI 每次跑） |
| Markdown/TXT/PDF 导出 | `app/.../ui/components/ui/Export.kt` + `app/.../ui/pages/chat/ExportPdf.kt` |

**两条公式路径互相独立**：正文走 JLatexMath（Kotlin），WebView 走 KaTeX（JS）。改一处不影响另一处。

---

## 8. 备份 / 迁移

| 关注点 | 文件 |
|---|---|
| 备份打包 | `app/.../data/sync/BackupManager.kt`（含 `workspaces.tar.gz`） |
| 恢复暂存/落地 | `app/.../data/sync/PendingRestore.kt`、`BackupManager.stageRestore()` |
| DB 版本与迁移链 | `app/.../data/db/AppDatabase.kt` + `app/.../data/db/migrations/Migration_*.kt` |

**已知陷阱**：恢复时 `workspaces.tar.gz` **就地解压**（不进 journal，v5.0.50），否则 15G 目录逐文件 journal 会 OOM。
