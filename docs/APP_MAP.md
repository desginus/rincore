# RinCore 应用地图 (APP_MAP)

> **给 AI 的读法**: 拿到本仓库先读本文件。它告诉你 — 每块代码在哪、干什么、
> 用户提某类需求时改哪些文件、该问题历史上是否出现过。
> 版本基线: v4.8.9 (2026-09-23)。大规模改动后请同步更新对应卡片。
>
> 配套历史知识 (必读): `.claude/skills/` 下 `rincore-project-brief`(架构基线) /
> `rincore-changelog`(版本史) / `rincore-bug-record`(Bug 根因) /
> `rincore-decisions`(方案决策) / `rincore-roadmap`(路线待办)。

---

## 1. 功能域总览

| # | 功能域 | 职责一句话 | 主目录 | 规模 |
|---|--------|-----------|--------|------|
| A | 对话核心 | 会话状态机、生成管线、消息队列、压缩 | `ui/pages/chat` + `service/` + `data/model/` | 40f/52K |
| B | AI 传输 | Provider 抽象、SSE、请求体协议、MCP | `ai/` 模块 + `data/ai/` | 122f/27K |
| C | 工具系统 | 工具定义/注入/域路由/插件/子代理 | `data/ai/tools/` + `ecosystem/` + `subagent/` | 65f/9K |
| D | 工作区沙箱 | Rootfs、文件工具、MCP stdio 桥、桥接服务 | `workspace/` 模块 + `sandbox/` + `data/repository/` | 30f/8K |
| E | 设置体系 | 全部设置页 + DataStore 持久化 + DI | `ui/pages/setting/` + `data/datastore/` + `di/` | 57f/15K |
| F | 主题渲染 | 配色主题、Markdown/代码/文档渲染、动效 | `ui/theme/` + `highlight/` + `document/` + `ui/components/` | 185f/60K |
| G | 自动化 | 工作流引擎、定时任务、消息队列调度 | `workflow/` + `service/Cron*` + `data/alarm/` | 30f/5K |
| H | 语音搜索 | TTS/ASR、搜索服务、内置浏览器 | `speech/` + `search/` + `browser/` + `openclaw/` | 65f/9K |
| I | 数据存储 | Room 数据库、同步、收藏、统计、备份导出 | `data/db/` + `data/sync/` + `data/export/` | 60f/8K |
| J | Web 服务 | 嵌入式 Ktor 服务器 + Web 管理界面 | `web/` 模块 + `web-ui/` + `app/web/` | 15f/2K |
| K | 生态扩展 | 插件生态、应用市场、Operit 兼容层 | `ecosystem/` + `data/operit/` + `ui/pages/market` 等 | 30f/4K |
| L | 基础设施 | 工具函数、通用扩展、OAuth、权限、日志 | `utils/` + `common/` + `oauth/` + `data/log/` | 45f/5K |

---

## 2. 域详情卡片

### A. 对话核心 (Chat)

**职责**: 对话页 UI、会话状态机、生成管线 (流式/重试/断流)、消息队列、
上下文压缩、延迟回复、语音模式、TTS 自动朗读。

**关键文件**:
| 文件 | 职责 |
|---|---|
| `ui/pages/chat/ChatPage.kt` | 对话页组装 (Scaffold/顶部栏/输入区接线/压缩提示条) |
| `ui/pages/chat/ChatVM.kt` | 对话 VM — 发送/编辑/删除/压缩/重新生成/助手配置 |
| `ui/pages/chat/ChatList.kt` | 消息列表渲染 (滚动/多版本消息/长按菜单) |
| `ui/components/ai/ChatInput.kt` | 输入区 (发送键/思考键互换 `sendBlocked`/消息队列卡片/语音) |
| `ui/components/ai/CompressContextDialog.kt` | 压缩配置弹窗 (确认即关, 无压缩态弹窗) |
| `service/ChatService.kt` | 生成管线核心 (sendMessage/队列调度/压缩执行/错误聚合) |
| `service/ConversationSession.kt` | 单会话运行时 (Job/消息队列/中断) |
| `service/MessageQueue.kt` | 消息发送队列 (延时回复依赖) |
| `service/ContextCompressor.kt` | 压缩留存量计算/推荐保留条数 |
| `data/model/Conversation.kt` | 会话模型 (消息节点/压缩留存/系统提示词) |
| `data/model/Assistant.kt` | 助手配置 (模型/参数/工具/MCP/CWD) |

**数据流**: 输入 → `ChatVM.handleMessageSend` → `ChatService.sendMessage` →
`MessageQueue.enqueue` → `dispatchNextQueuedMessage` (守卫: 生成中/pending tool
时排队) → `GenerationHandler` 流式生成 → 更新 `Conversation.messageNodes`。

**常见改动**:
- 输入区按钮/布局 → `ChatInput.kt` (注意 swapKeys/sendBlocked 多状态)
- 发送/中断逻辑 → `ChatVM.handleMessageSend` + `ChatService.sendMessage`
- 压缩相关 → `CompressContextDialog.kt` + `ChatVM.handleCompressContext` +
  `ChatService.compressConversation` + `ContextCompressor.kt`
- 消息渲染 → `ChatList.kt` + `ui/components/message/`

**历史** (详见 bug-record/changelog):
- 压缩弹窗全屏阻塞 → v4.8.8 "确认即关" 定版 (勿回退为 loading 弹窗)
- 压缩中禁发 → v4.8.9 定版 (防打断压缩, 非 bug)
- 消息发送队列与延时回复联动 → v3.6.13/v3.11.30 系列

### B. AI 传输 (AI Transport)

**职责**: 各 Provider 的请求构造/流式解析、请求体协议转换 (Anthropic/Chat
Completions/Response API)、MCP 客户端、提示词组装、消息变换器。

**关键文件**:
| 文件 | 职责 |
|---|---|
| `ai/src/main/java/me/rerere/ai/provider/providers/ClaudeProvider.kt` | Claude/Anthropic 请求体 (thinkingFields 顶层字段集) |
| `ai/.../providers/` | 各 Provider (OpenAI/Google/DeepSeek/GLM/MiniMax...) |
| `data/ai/providers/` | Provider 层 (请求体拼装/流式解析/重试) |
| `data/ai/transformers/` | 消息变换器 (12f: ThinkTag/Regex/Base64Image/WorkspaceReminder...) |
| `data/ai/mcp/McpSessionRegistry.kt` | MCP 会话 (连接/重连/stdio 桥/状态机) |
| `data/ai/mcp/McpManager.kt` | MCP 门面 + reconcile 触发 |
| `data/ai/prompts/` | 系统提示词组装 (PromptAssembler) |
| `data/ai/GenerationHandler.kt` | 生成管线执行 (流式组装/图片预算/重试链) |

**核心协议纪律** (铁律, 改动前必读 rincore-project-brief):
- OpenCode 网关按模型分协议: qwen 走 Anthropic 直传, CC 走 Chat Completions
- 请求体统一单一路径 (Cherry 分裂模式已废弃 v4.8.10)
- 网关对含 tool_result 的 user 消息要求纯 tool_result 块序列

**常见改动**:
- 新 Provider → `ai/` 模块 provider 抽象 + `data/ai/providers/` 注册 + 设置页
- 请求体字段 → 对应 provider 的 buildMessageRequest (注意 Map 返回顶层字段集)
- MCP 连接/状态 → `McpSessionRegistry` (reconcile 补连 + 失败重连链, v4.8.6 定版)

**历史**: MCP 重启断联根治 (v4.8.6) / thinking 字段语义结构根修 (v4.8.10) /
断流重试三件套 (v4.8.7) / GLM 图片限制 6 张 (v4.7.24)。

### C. 工具系统 (Tools)

**职责**: 框架工具定义、动态注入/域路由、工具审批、插件、子代理、技能。

**关键文件**:
| 文件 | 职责 |
|---|---|
| `data/ai/tools/ToolsBuilder.kt` | 请求 tools 数组组装 (分层动态注入) |
| `data/ai/tools/DomainTools.kt` | 工具域路由 (search_domains/invoke_tools) |
| `data/ai/tools/WorkspaceTools.kt` | 工作区五件套 (read/write/edit/shell/show) |
| `data/ai/tools/TaskTools.kt` | task_tool (任务清单卡片) |
| `ecosystem/EcosystemManager.kt` | 插件/生态管理 |
| `ecosystem/tools/DynamicTools.kt` | 动态工具池 |
| `subagent/SubAgentEngine.kt` | 子代理引擎 |
| `ui/pages/setting/SettingDomainPage.kt` | 工具域管理 UI (触发描述/条件) |

**分层注入铁律**: 系统提示词只放 7 个框架工具; 其余工具在请求 tools 数组;
MCP 工具静态声明 (配置决定), 连接惰性 (首次调用才连)。

**常见改动**:
- 新工具 → `data/ai/tools/` 新建 + 注册 + 引导文案 (WorkspaceReminderTransformer)
- 工具域行为 → `DomainTools.kt` + `SettingDomainPage.kt`
- 工具审批 → `ToolInvocationContext` + 设置页

**历史**: 序列思考工具彻底删除 (v4.7.12) / 管理三件套意图门控 (v4.7.12) /
工具图片四部曲 (B118) / 域管理三坑修复 (v4.7.16)。

### D. 工作区沙箱 (Workspace & Sandbox)

**职责**: Rootfs 沙箱、文件系统、proot shell、MCP stdio 桥、沙箱-软件双向桥、
文件上传/导出/分享。

**关键文件**:
| 文件 | 职责 |
|---|---|
| `workspace/.../WorkspaceManager.kt` | 沙箱门面 (文件/导出 `exportFolderToZip`/launchProcess) |
| `workspace/.../WorkspaceFileSystem.kt` | 文件系统 (含 500 条列表截断语义, 仅供 UI) |
| `workspace/.../ProotShellRunner.kt` | proot 命令构造/常驻进程 |
| `workspace/.../RootfsPatcher.kt` | link2symlink 补丁 |
| `sandbox/SandboxBridgeServer.kt` | 沙箱→软件桥 (127.0.0.1:17526, rin CLI) |
| `data/repository/WorkspaceRepository.kt` | 仓库层 (DAO+manager 封装) |
| `ui/pages/extensions/workspace/WorkspaceDetailPage.kt` | 文件管理页 (传输加载态 activeTransfers) |
| `ui/pages/extensions/workspace/WorkspaceDetailVM.kt` | 文件操作 VM (导入/导出/分享/重命名) |

**导出铁律** (v4.8.5): 导出器不得复用 UI 列表层 (listFiles 有 500 截断);
文件夹导出直接宿主文件树递归 (`exportFolderToZip`); 分享打包有 activeTransfers 计数。

**常见改动**:
- 文件操作 → `WorkspaceDetailVM.kt` (加操作记得 activeTransfers 包裹)
- 沙箱启动/rootfs → `ProotShellRunner` + `RootfsPatcher`
- rin 桥 → `sandbox/SandboxBridgeServer.kt` + `assets/rin-tools/rin`

**历史**: 文件夹导出内容丢失根治 (v4.8.5) / 沙箱穿透桥 (v4.8.0) /
文件传输加载动画 (v4.8.4) / 分享加载条 (v4.8.10) / LibreOffice 除名 (v4.7.19)。

### E. 设置体系 (Settings)

**职责**: 全部设置页面、DataStore 持久化 (SSOT)、Koin DI。

**关键文件**:
| 文件 | 职责 |
|---|---|
| `data/datastore/PreferencesStore.kt` | **双 Store 同文件**: `PreferencesStore` (客户端布尔偏好) + `SettingsStore` (全局配置 SSOT, `settingsFlow.value`) |
| `data/datastore/migration/` | DataStore 迁移 (PreferenceStoreV1-V3 + SettingsJsonMigrator) |
| `ui/pages/setting/SettingPage.kt` | 设置主入口 (分组导航) |
| `ui/pages/setting/SettingPreferences*.kt` | 偏好设置 (通用/网络/通知/UI/主题) |
| `ui/pages/setting/SettingProviderPage.kt` | Provider 配置 |
| `ui/pages/setting/SettingModelPage.kt` | 模型配置 |
| `ui/pages/setting/SettingVM.kt` | 设置 VM |
| `di/` | Koin 模块 (`GlobalContext.get().get<T>()` 取实例) |

**铁律**: 全局配置唯一真相源 `settingsStore.settingsFlow.value`; UI 层禁止旁路
缓存; 新配置必须 DataStore 持久化 (重启不丢)。新开关落地 5 处: 常量+双写+读+字段+UI。

**常见改动**:
- 新开关 → PreferencesStore (booleanPreferencesKey) + 设置页 Card/Switch
- 新设置页 → `ui/pages/setting/` 新建 + 路由注册 + SettingPage 分组

**历史**: 发送/思考键互换 (v4.8.3) / 状态蓝标三处 (v4.8.4) / 主题条自定义 (v4.8.9)。

### F. 主题渲染 (Theme & Render)

**职责**: 预设/自定义主题配色、Markdown 渲染、代码高亮、文档预览 (Office/PDF)、
图片加载缓存、玻璃材质。

**关键文件**:
| 文件 | 职责 |
|---|---|
| `ui/theme/PresetTheme.kt` | 预设主题注册表 (PresetThemes 列表) |
| `ui/theme/presets/*.kt` | 各预设 (MinimalTheme/DefaultTheme/Sakura...) |
| `ui/theme/CustomTheme.kt` | 自定义主题 (主/辅/点缀三色生成, 色相条选择) |
| `ui/pages/setting/SettingThemePage.kt` | 主题设置页 (HueSliderRow 色相条) |
| `ui/components/message/MarkdownBlock` 相关 | Markdown 渲染 (首帧同步解析, 勿异步化) |
| `ui/components/render/DocumentRenderEngine.kt` | 文档渲染调度 (HTML/PDF/DOCX/XLSX) |
| `highlight/` 模块 | 代码高亮实现 |
| `ui/components/ui/WorkspaceImageFetch.kt` | 图片加载 (缓存键含 mtime+size) |

**渲染铁律**: Markdown 首帧同步解析 (对齐原版, 勿改异步/占位/分段);
Haze 锁 `2.0.0-beta01` (rc 版本有格栅伪影); 玻璃层用 `RinGlass` 单一来源。

**常见改动**:
- 新预设主题 → `ui/theme/presets/` 新建 (抄 MinimalTheme 全色板) +
  `PresetTheme.kt` 注册 + `strings.xml` 双语 (theme_name_*)
- 主题自定义交互 → `SettingThemePage.kt` (色相条 HueSliderRow)
- 渲染问题 → 先 diff 上游 RikkaHub 对应文件 (对齐优先原则)

**历史**: 渲染抽动完全对齐原版 (v4.7.26) / 首帧卡顿修复 (v4.8.1) /
图片缓存失效 (v4.8.2) / 预设主题默认·莫兰迪紫 (v4.8.7) / 色相条 (v4.8.9)。

### G. 自动化 (Automation)

**职责**: 工作流引擎 (条件/触发/执行)、定时任务 (cron)、闹钟、会话清理。

**关键文件**: `workflow/` (23f: model/condition/execution/tools/ui),
`service/CronJobScheduler.kt`, `service/CronJobWorker.kt`,
`service/AlarmReceiver.kt`, `data/alarm/`, `ui/pages/setting/scheduledjobs/`。

**常见改动**: 定时任务 → CronJobScheduler + 设置页; 工作流 → workflow/ + workflow/ui。

### H. 语音搜索 (Speech & Search)

**职责**: TTS/ASR 实现、搜索服务适配 (Exa/Tavily/Zhipu/Bing/Brave/SearXNG)、
内置浏览器、OpenClaw。

**关键文件**: `speech/` 模块 (33f), `search/` 模块 (20f), `browser/` (8f),
`openclaw/` (4f), `ui/pages/setting/SettingSpeechPage.kt`, `SettingSearchPage.kt`。

**常见改动**: 新搜索引擎 → `search/` 模块 + 设置页; TTS/ASR → `speech/` + 语音会话。

### I. 数据存储 (Data)

**职责**: Room 数据库、WebDAV 同步、收藏、用量统计、备份导出、日志。

**关键文件**: `data/db/` (43f: AppDatabase/DAO/实体 — **改动需迁移, 最高危**),
`data/sync/` (11f), `data/favorite/`, `data/usage/`, `data/export/`,
`data/log/`, `ui/pages/backup/`, `ui/pages/usage/`。

**铁律**: Room 升级是启动链最高危改动 (v4.6.5 闪退实证) — 无法本地验证时
宁用文件存储绕过; 记忆类数据已走 JSON 文件 (EnhancedMemoryRepository)。

### J. Web 服务 (Web)

**职责**: 嵌入式 Ktor 服务器 (web 模块) + React 管理界面 (web-ui/, pnpm 构建)。

**关键文件**: `web/` 模块 (WebServerManager), `web-ui/` (TypeScript),
`app/web/routes/` (13f API 路由), `service/WebServerService.kt`。

**常见改动**: 新 API → `app/web/routes/` + web-ui 调用; 服务启停 → WebServerService。

### K. 生态扩展 (Ecosystem)

**职责**: 插件生态、应用市场、Operit 兼容层 (已搁置, UI 收纳于设置深层)。

**关键文件**: `ecosystem/` (10f), `data/operit/` (13f), `ui/pages/market/`,
`ui/pages/operitui/`, `ui/pages/setting/SettingAdvancedPage.kt` (入口收纳位置)。

**状态**: 岔路口计划搁置 (2026-09-20 用户决定) — 代码保留, 不再主动推进。

### L. 基础设施 (Infra)

**职责**: 通用工具函数、扩展、OAuth、权限、崩溃日志。

**关键文件**: `utils/` (23f), `common/` 模块 (13f), `oauth/` 模块 (4f),
`data/permissions/`, `data/log/`, `material3/` 模块 (色彩工具)。

---

## 3. 需求 → 改动索引 (高频模式)

| 用户需求类型 | 改动路径 |
|---|---|
| 改对话页输入区/按钮 | `ui/components/ai/ChatInput.kt` → `ChatPage.kt` 接线 |
| 改对话页布局/顶部栏 | `ui/pages/chat/ChatPage.kt` |
| 改发送/生成行为 | `ChatVM.kt` + `service/ChatService.kt` |
| 压缩功能相关 | 见域 A 卡片 (弹窗/禁发/提示条/压缩执行) |
| 加设置开关 | `PreferencesStore.kt` (常量+读写) + 对应 `Setting*Page.kt` |
| 加预设主题 | `ui/theme/presets/` 新建 + `PresetTheme.kt` 注册 + strings 双语 |
| 改主题自定义 | `SettingThemePage.kt` (色相条) + `CustomTheme.kt` (生成算法) |
| 工作区文件操作 | `WorkspaceDetailVM.kt` (操作加 activeTransfers 包裹) |
| 文件夹导出/分享 | `WorkspaceManager.exportFolderToZip` + `WorkspaceDetailVM` |
| 加 AI 工具 | `data/ai/tools/` 新建 + 注册 + 引导 (WorkspaceReminderTransformer) |
| 改工具域/路由 | `DomainTools.kt` + `SettingDomainPage.kt` |
| MCP 相关 | `data/ai/mcp/McpSessionRegistry.kt` (连接/重连/stdio 桥) |
| Provider/请求体 | `ai/` provider + `data/ai/providers/` (注意协议分派) |
| 渲染问题 (Markdown/文档) | 先 diff 上游 RikkaHub 对齐 (渲染铁律), 再看 `ui/components/message/` |
| 图片显示/缓存 | `WorkspaceImageFetch.kt` (缓存键) + `ui/components/ui/` |
| 沙箱/rin 桥 | `sandbox/SandboxBridgeServer.kt` + `assets/rin-tools/rin` |
| 定时任务 | `service/Cron*` + `ui/pages/setting/scheduledjobs/` |
| 状态栏/通知 | `service/ChatNotificationManager.kt` + 通知设置页 |
| 备份/导出数据 | `data/export/` + `ui/pages/backup/` |

---

## 4. 历史知识入口

**任何修改前, 先查对应 skill** (`.claude/skills/`):

| Skill | 何时查 |
|---|---|
| `rincore-project-brief` | 任何代码修改前 (架构基线/铁律/协议纪律) |
| `rincore-changelog` | 某版本改了什么 / 某功能何时引入/回滚 |
| `rincore-bug-record` | 遇到运行问题 (崩溃/中断/缓存/权限/保活) — 避免重复诊断 |
| `rincore-decisions` | 方案选择/架构权衡/回滚决策 |
| `rincore-roadmap` | 下一步工作/待办状态 |

**关键历史教训索引** (跨域通用):
- 用户报错一律按最新版本处理; 报时间按北京时间
- 主线程禁 runBlocking + file IO (例外: App 启动恢复)
- 改签名全局 grep 调用点; 多函数联动先列全部签名与调用点
- 清理 import 前过一遍全部符号引用 (Kotlin 签名类型也算) — v4.8.8 CI 红实证
- 请求体修到"绝对不会出问题" (统一模式, 无分支)
- 数据层 backfill 类修复会激活潜伏 bug — 上线必须全档位验证
- 渲染类问题对齐原版优先, 勿自研"优化"
- 导出器不得复用 UI 列表层 (有截断/排序/过滤语义)
- Compose Box z-order = 后声明在上层 (UI 层叠顺序敏感)
- 弹窗阻塞修复范式: Dialog onDismissRequest 恒放行 + 状态上升 VM + 页内非模态提示

**物理环境速查**:
- 沙箱 Rootfs: Ubuntu 24.04 arm64 + glibc (proot, 无 /proc)
- 启动清理: `/usr/bin/env -i` 真空环境白名单注入
- 工具箱: rg/git/fdfind/jq/node/python3/officecli/pandoc (LibreOffice 已除名)
- 桌面工具: `/usr/bin/env -i` 下运行; CI: GitHub Actions, 编译约 10-15 分钟

---

*最后更新: v4.8.9 (2026-09-23)。新版本发布时, 若新增/重划功能域或改动
关键文件职责, 请同步本文件与对应 skill。*
