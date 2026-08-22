> **历史快照（2026-08-03 时点）**：本文档为当时审计结论的历史存档，版本描述停留在 v3.5.x。当前状态以 docs/ecosystem/ 与 .claude/skills 知识库为准（当前 v3.9.2），历史归档仅作追溯参考。

# RinCore 模块地图（分块标注）

> 用途：代码分块定位——哪里有问题就查哪个模块。标注类文档，不影响运行。
> 来源标注：🟢自研 / 🟡移植fork / 🔵继承原版（详见 architecture-audit-20260803.md）

## 分层总览

```
app (Android 壳)                    ← UI/服务/资源
├── ui/         界面层             ← 页面/组件/主题/路由
├── service/    系统服务层         ← 后台/通知/闹钟/前台服务
├── data/       数据与逻辑层       ← AI 链路/存储/工具/模型
├── ecosystem/  生态集成层         ← MCP/插件/动态工具/技能
├── openclaw/   ClawSkill 层       ← 技能桥接
├── workflow/   工作流层           ← 工作流引擎(阶段3)
├── web/        HTTP 服务层        ← 本地 web 路由
└── di/         Koin 依赖注入      ← 全局注册
ai (独立模块)                       ← 模型传输/消息模型/Provider
```

## 模块明细

### A. AI 传输链（核心链路——问题高发区）

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `data/ai/` | 生成编排：消息组装/分层路由/工具池过滤/协议强制/流式输出 | GenerationHandler.kt | 🔵+🟢 |
| `data/ai/protocol/` | **消息协议层**：首条 system 保证 + tool 配对清洗（发送前结构性兜底） | MessageProtocol.kt | 🟢 新 |
| `data/ai/transformers/` | 消息变换管线：注入/占位符/时间提醒/上下文压缩 | PromptInjectionTransformer, PlaceholderTransformer, TimeReminderTransformer, ContextCompressionTransformer | 🔵+🟢 |
| `data/ai/tools/routing/` | 域分类路由：域树/层1概览/invoke_tools 生成 | ToolRouter, ToolDomain, ToolClassifier | 🟢 |
| `data/ai/tools/local/` | 本地工具集：13 个工具（时间/剪贴板/TTS/闹钟/日历/屏幕等） | LocalTools, LocalToolOption | 🟢 |
| `data/ai/tools/` | 域管理工具 + 会话工具 + 技能工具 | DomainTools, ConversationTools | 🟢 |
| `data/ai/mcp/` | MCP 客户端 + OAuth | McpManager(ecosystem), McpOAuthClient | 🔵+🟢 |
| `data/ai/compression/` | 工具输出压缩（自然语言化） | ToolOutputCompressor | 🟢 |
| `data/ai/diagnostics/` | 诊断日志 | DiagnosticLogger | 🟢 |
| `ai/` (模块) | 消息模型/Provider 分发/API 传输（ChatCompletions/Responses） | Message.kt, OpenAIProvider, ChatCompletionsAPI, ResponseAPI | 🔵 |

### B. 会话与存储

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `service/ChatService.kt` | 会话编排：工具池构建/落盘/通知/审批 | ChatService.kt | 🔵+🟢 |
| `data/repository/` | 仓库层（会话/消息/助手/记忆/文件） | ConversationRepository 等 | 🔵+🟢 |
| `data/db/` | Room 数据库（version 27）+ migration | AppDatabase, migrations/ | 🔵+🟢 |
| `data/datastore/` | SettingsStore（SSOT）+ 偏好 | SettingsStore, RecommendedProviders | 🔵+🟢 |
| `data/model/` | 领域模型：Assistant/Conversation/注入定义 | Assistant.kt, PromptInjection | 🔵+🟢 |
| `data/files/` | 文件与技能解析 | SkillFrontmatterParser | 🟢 |

### C. 系统服务（保活/闹钟/通知）

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `service/` 闹钟 | 设备闹钟（自研）+ 定时任务（继承） | AlarmScheduler, AlarmReceiver, CronJobScheduler, CronJobWorker | 🟢+🔵 |
| `service/` 通知 | 会话通知/前台服务 | ChatNotificationManager, FloatingNotificationService | 🟢 |
| `service/` 环境 | 环境优化（杀后台前保护） | EnvironmentOptimizer | 🟢 |
| `data/alarm/` | 闹钟仓库（DB 访问） | AlarmRepository | 🟢 |

### D. 生态与技能

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `ecosystem/` | 生态管理：MCP 连接/插件解析/动态工具/斜杠命令 | EcosystemManager, DynamicTools, SlashCommandRouter | 🟢 |
| `ecosystem/plugin/` | 插件体系：Claude 解析/Hook 引擎/Agent 注册 | ClaudePluginParser, HookEngine | 🟢 |
| `openclaw/` | ClawSkill 加载与桥接 | ClawSkillManager, ClawSkillBridge | 🟢 |

### E. 工作流（阶段 3 未完成）

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `workflow/model/` | 工作流模型（已搬） | TriggerSpec, ConditionSpec, WorkflowContext | 🟡 |
| `workflow/execution/` | 执行层（引擎未接） | WorkflowEmergencyController | 🟡 |
| `workflow/trigger/` | 触发器（5 个未移植） | — | 🟡 |

### F. UI 层

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `ui/pages/` | 页面（聊天/设置/域/生态/闹钟/权限/技能） | RouteActivity(导航注册), 各 Setting* 页 | 🔵+🟡+🟢 |
| `ui/components/` | 组件（WebView/权限/审批） | WebViewContentCache, permission/ | 🔵+🟡 |
| `ui/theme/presets/` | 主题 | ClaudeTheme, MinimalTheme | 🟢 |
| `ui/activity/` | Activity（OAuth 回调/分享接收） | McpOAuthCallbackActivity | 🟢 |

### G. 其他

| 模块 | 职责 | 关键文件 | 来源 |
|---|---|---|---|
| `web/routes/` | 本地 HTTP 路由（事件/文件夹） | EventsRoutes, FolderRoutes | 🟢 |
| `browser/` | 浏览器能力 | — | 🔵 |
| `costguards/` | 成本防护 | — | 🔵 |
| `subagent/` | 子代理 | — | 🔵 |
| `utils/` | 工具函数 | — | 🔵 |
| `di/` | Koin 注册（AppModule） | AppModule.kt | 🔵+🟢 |

## 问题定位速查

| 症状 | 查哪个模块 |
|---|---|
| 连接中断/SETTINGS 报错/首条非 system | A: data/ai/protocol + transformers + GenerationHandler |
| 工具调用异常/孤儿 tool_call | A: protocol + GenerationHandler |
| 冷启动 token 高 | A: GenerationHandler 工具池 + ToolRouter + DynamicTools |
| 缓存断层 | A: GenerationHandler (cache 日志) |
| 回答丢失/切后台 | B: ChatService 落盘 |
| 闹钟不响/定时任务崩 | C: service 闹钟 + FGS 类型 |
| 工具不显示/域混乱 | A: tools/routing + ecosystem |
| MCP 连不上 | D: ecosystem + data/ai/mcp |
| 工作流不生效 | E: workflow（未完成） |
| 页面闪退 | F: ui + di（Koin 注册） |

## 分块约定（后续开发）
1. 改哪里 → 先查本地图定位模块 → 加载对应 Skill（rincore-*）
2. 新代码：先归属模块（包），文件头加 KDoc 标注职责与来源
3. 大改动：更新本地图 + architecture-audit 来源表
4. 协议层（A: protocol）是发送前兜底——所有传输链路问题先检查它
