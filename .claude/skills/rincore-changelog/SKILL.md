---
name: rincore-changelog
description: "[中优先级·RinCore开发对照] RinCore 完整版本更新日志。触发词：版本历史、更新日志、changelog、这个版本改了什么、版本对比、回滚历史、版本链。任何需要了解 RinCore 某版本改动/某功能何时引入/何时回滚时加载。不涉及：Bug 根因细节（用 rincore-bug-record）、方案决策（用 rincore-decisions）。"
---

# RinCore 更新日志（v3.11.26 为最新）

## v3.11.26（Cherry Studio 任务功能延伸 + 加号面板改造 + 子代理展示收敛，2026-08-31）
- task_tool 推进反馈 (sequential-thinking 风格) — 每次调用返回 {tasks, progress, hint}，hint 给下一步指引（单 in_progress 规则 / 全部 completed 交付 / 空转纠偏）
- 加号面板下方新增四快捷入口行（BigIconTextButton 与上方网格式对齐）：
  - 子代理详情 (AiBrain01) — 显示当前对话派发子代理列表、运行中计数、token/轮次/耗时、结果内显、停止按钮
  - 模型记忆 (Book02) — 直接路由 Screen.AssistantMemory（无顶部4选项卡）
  - 工作区目录 (Folder01) — 原底部文件夹入口迁入此处收纳
  - 上下文条数 (BubbleChatQuestion) — 显示 conversation.messageNodes.size（ContextCompressor 按条数压缩的可视化参考）
- 子代理统一展示窗口：ChatMessage.kt 过滤 `subagent_*` 工具调用 — 消息列表不再散落子代理过程，所有子代理信息汇聚到 SubAgentDetailSheet
- SubAgentStatus 枚举对齐：PENDING/RUNNING/SUCCEEDED/FAILED/TIMED_OUT/CANCELLED；状态色按运行→tertiary、完成→primary、失败/超时→error
- 修复：SubAgentDetailSheet 原双 else 语法错误；FilesPicker 删除原单独工作区按钮避免功能重叠

## v3.11.25（Cherry Studio 任务清单 + 工具卡语义化折叠标题，2026-08-31）
- 新框架工具 task_tool — TodoWrite 全量清单模式；清单存 tool output 随会话消息持久化，零 DB，跨轮可见
- schema: tasks 数组，每项 {id, title, status: pending|in_progress|completed, activeForm}；执行时校验 id 唯一/title 非空/status 合法
- systemPrompt 引导模型"多步任务先建清单；一次只一个 in_progress；完成即更新；全 completed 才交付"
- TaskToolUI 任务卡片 — 折叠标题"任务清单"，展开渲染清单行（✓/◐/○ + 进行中描述 + activeForm）
- DefaultToolUIRenderer 按 Snake name 动词映射语义化折叠标题 — "写入文件"替代"写入 /path/to/file.json"；完整路径留展开详情
- FRAMEWORK_TOOL_SET 新增 task_tool，不参与视图统计；TaskToolUI 注册到 ToolUIRegistry.renderers 列表第 1 位

## v3.11.24（四故障深度修复，2026-08-31）
- F1 记忆写入健康门 (memoryHealthCheck: 4000c/时间锚断言/重复度) + buildMemoryPrompt 渲染过滤 + TimeReminder 幂等收窄
- F2 思考工具四道协议门 + 同工具累计连调 >6 次物理拦截
- F3 流式复读熔断 (repetitionSampleCount 384 字符粒度 → ClientGenerationGuardException)
- D STREAM_RETRY_BUDGET_MS 10s→45s ("重试 1 次后仍失败" 假终报根治)

## v3.11.23（原版 2.4.15 全量移植，2026-08-30）
- MCP OAuth 架构重写 (:oauth module/loopback 回调/DiscoveryClient/Coordinator 重写)
- 文件清理时间范围 (CleanRange 7/14/30/ALL BottomSheet)
- Gemini 云端+本地工具并存 (tools 数组合并)
- tool-only 消息操作按钮修复 (isEmptyUIMessage Tool→false)
- 输入栏折叠动画随上游回滚; 依赖矩阵对齐 (m3 alpha27/okhttp 5.5.0/sqlite-vector 1.0.0/删 nav2)

## v3.11.22（渐变背景卡死根因治理，2026-08-30）
- 故障真因：hazeSource + drawWithContent 包裹下 rememberInfiniteTransition 不被唤醒；帧时钟驱动方案均失效
- v3.6.82 砍动画的理由（GPU 满载/静态卡顿）在当前环境被证伪
- 修复：整文件零增删替换为原版 RikkaHub HEAD MeshGradientBackground.kt，恢复 rememberInfiniteTransition + Canvas 逐帧绘制

## v3.11.21（光团动画校准 + 图标差异化，2026-08-30）
- 光团动画参数整文件对齐原版 RikkaHub HEAD：四周期 5.5s/7s/8.5s/6.2s，四中心正上 0.48w/左上 0.18w/右上 0.82w/中上 0.58w
- 图标差异化：偏好设置 (CursorPointer01) / 能力模块 (Agent Skills 保留 Puzzle / 插件改 Package) / 工具域 (分类管理→Sorting01 / 内置工具→Tools)

## v3.11.21 之前的历史版本详情

> 完整历史记录保留于 `.claude/skills/rincore-changelog/legacy-changelog.txt`（如需查阅历史某版本）。

## 历史教训（防重踩——每次改动前必读）
- **产品线**：v3.8.44-45 曾建 WaterHub B 类产品线（flavor 拆分），用户 3.9.1 令废弃回滚——只保留 A 线 RinCore 单产品构建。教训：未与用户对齐的产品线扩张立即废弃，勿自行推进
- **功能界线**：用户只要求移植的功能就只移植，不自作主张附带其他功能（v3.8.44 附带工具入口/漂浮字幕被要求全量回滚）
- **limitContext 滞回策略 ↔ 缓存**：v3.3.0 引入（2.4.5 适配）→ v3.3.5 回滚（**缓存机制报废**）→ v3.3.12 确认回滚。函数仍在 Message.kt 但未启用（无 contextMessageSize 字段）——**勿重新启用**，启用即破坏缓存前缀
- **缓存锚点/注入隔离**：v2.9.5 注入隔离（BEFORE_SYSTEM_PROMPT 变独立 user 消息）引入 SETTINGS 协议违规 → v3.4.5 修复——**协议合规 > 缓存边际收益**
- **DeepSeek Responses reasoning**：3.5.4~3.5.6 猜测性修复全废（服务端格式不成熟）→ 3.5.7 按官方协议（明文 content）→ 3.5.8 工具轮相邻 assistant 消息 → 3.5.9 起搁置（等官方更新）
- **工具执行无超时**：3.5.9 withTimeout 60s 兜底——工具挂起不永久阻塞生成
- **缓存"卡-跳-线性"**：DeepSeek 服务端磁盘缓存机制（构建延迟秒级+固定间隔切分+SWA 独立单元）——客户端不可控，已入库 decisions D2
- **消息渲染红线**：消息列表渲染链是历史严重事故遗留红线，绝对不可再动（只读不动，改文件渲染另走通道）
