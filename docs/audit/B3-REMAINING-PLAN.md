# B3 收尾批 · 执行计划（2026-10-19 起）

## 0. 现状基线
- **B1 已发布**：`v4.9.0-dev`（APK 51.38MB，main=317ab11ae）。
- **B2 主体已推**：`adopt-3.0.0` = f5969e05b（20 件「上游赢」UI 文件全处理 + 我方语义回贴），本地再+1（versionName→4.9.5-dev）。
- **量化结论**：
  - 「微差手看」85 件：47 件零语义差异（已是上游内容）；38 件差异均为 1-2 行（import/API 细节）→ 基本就绪。
  - 「手工合并」87 件：4 件零差异；**29 件我方语义 ≤10 行**（低风险，可优先细看/采纳）；其余 58 件为我方核心件（ChatDrawer/ChatPage/FilesPicker/WorkspaceDetailPage/Markdown/TTSProviderConfigure/SettingMcpPage 等 = 保底项，保留我方 + 选择性吸收）。

## 1. 待办清单（按优先级）
### P0 · 让 5.0.0 可发布
1. B2 oracle 转绿（当前批 f5969e05b）。
2. FF main（含 versionName 4.9.5-dev）→ Release 4.9.5-dev 验证。
3. 文档：changelog/B2 记录/README 徽章（停 v4.8.153）/APP_MAP。

### P1 · 记忆 2.0（用户点名完全跟进上游）
- `AssistantMemoryPage`（我方 362 行）→ 上游结构（MemorySettingsTab/MemoryFilesTab/MemoryConsolidationSection）+ 回贴我方单对话记忆（conversationMemories/conversationTitle/recentConversations）。
- `AssistantDetailVM`：补 `memoryConsolidation`/`organizeMemory`/`memoryId`/memory-file CRUD（上游面），保留 addMemory/updateMemory/deleteMemory（我方单对话记忆）。
- 入口：记忆文件页（Screen.MemoryFile 已接线）+ 工具渲染（MemoryFileToolUI 已注册）。

### P2 · 媒体生成（用户点名全量移植上游 mediagen）
- 入口接线：`Screen.MediaCreationSessions` ← ChatDrawer / ImgGenPage；`Screen.MediaCreation`（已接线）；`SettingMedia`（已接线）。
- 我方 ImgGenPage/VideoGen 与上游 MediaCreation 的关系裁决（替换 or 并存）。

### P3 · 低风险合并（29 件 ≤10 行语义）
按清单逐件复查（多数为 import/API 微差，可直接采纳上游）：
ToolApprovalStateTest / ChatMessageCot / HeroAnimation / WorkspaceVM / AssistantMcpPage / AttachmentChips / DebugVM / WorkspaceTerminalPage / SearchPage / ModelList / ProviderBalanceText / TTSController / AvatarShape / ChatInputState / AssistantImporter / MeshGradientBackground / FavoritePage / StatsVM / PermissionTypes / Theme / ChatMessageReasoning / ChatMessageNerdLine / ExtensionsPage / LogPage / StreamChunkHandler / WorkspaceSelectSheet / UIAvatar / SettingWebPage。

### P4 · 收尾清理
- 删除死代码 `data/export/ExportHooks.kt`（Export.kt 已切 ui.hooks.ExporterState，全库零引用）。
- SettingMcpDetailPage：补 `StdioTransportServer` 分支（13 处 when）后启用上游详情页。
- 三遍对账（功能/代码双校验）+ parity 报告。

## 2. 红线（用户定版）
- 主页结构我方（加号→工具箱/右上角→产出/双星轨/工具懒加载）。
- 头像恒 CircleShape（排除 Cookie 形状）。
- 我方玻璃链不迁移（用上游 3.0.0 玻璃）。
- 沙箱一体/生成稳定链/上传链/CWD/渲染链/检索体系 = 保底不动。
