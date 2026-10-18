# RinCore 5.0.0 迁移总账（KEEP-LIST LEDGER · 反向换底版 · v1）

> 2026-10-18 · **路线=反向**（upstream 3.0.0=55a108a44 为底树 + 迁移我方）· 基准口径（**终定**）：v4.8.189 功能面**全搬，一个不少**；唯一弃项=**我方输入栏/标题栏模糊实验链**（用户"坏功能"原话所指）+ Cookie6Sided 头像排除；"凑功能"系笔误，取消"凑数可弃"表述。
> 状态词：**搬移**（P=port 到新底座，需适配新架构）／**上游等效替代**（U=上游已有，逐件核验语义覆盖后采用上游）／**放弃**（D=弃，需批注理由）／**评估**（E=执行到该域时定）
> 批次：B1 基础设施 → B2 UI → B3 收尾（每批 4.9.x-dev 真机验收）；三遍对账（搬移核验/功能核验/体验核验）后发 5.0.0。
> 来源账本：changelog FULL_HISTORY（180 版）× CODE_INDEX（906 文件）× ANCHOR_MAP × GHOST final4 × ISSUES-LEDGER。

## 0. 总规则
1. 玻璃/标题栏模糊=（我方 146→189 历轮实验链）**整体不迁移**（用户定："新版比我们优秀太多"）→ 上游 3.0.0 玻璃/标题栏全量采用。
2. 头像形状：上游 `Cookie6Sided` 加载态 → **恒 CircleShape**（用户排除项，已定位 `ui/hooks/AvatarShape.kt`）。
3. 一切搬移件**在新底座上重新适配**（agent 引擎/新设置结构/新 DB 迁移序列），严禁 copy 编译。
4. 每批开工前把该批条目展开为**逐文件清单**（从 v4.8.189 逐项对照），完成后逐项打钩。

## 1. 生成稳定链（B1 · 命脉级）
| 项 | 去向 | 备注 |
|---|---|---|
| 重试链 18 次（300/700/1s/2s/3s/5s×3 组） | 搬移 | 接骨到上游 agent/Retry.kt（RetryPolicy） |
| 完成门五通道（B136：usage 门/零输出重试/截断特征续写/正判据优先） | 搬移 | 以新引擎 hook 重挂 |
| 看门狗 150s + 判死打点（TraceLogger "watchdog fired"） | 搬移 | |
| outputStarted 相位分流（发起期 5 次可见提示｜有输出后 18 次静默） | 搬移 | |
| 假取消区分（job 死 vs 子作用域泄漏）+ 取消语义 | 搬移 | |
| 连接保持（ConnectionWarmer 60s/探活冷却/6s 总限/pokeMs 单列） | 搬移 | |
| 网关身份（UA 伪装 OC/Codex，AIRequestInterceptor） | 搬移 | |
| 预热链（ProviderWarmup + RouteActivity onResume） | 搬移 | |
| 中断可见性（我方"网络连接失败，自动重试中"文案） | 搬移 | |
| 多对话并行隔离（TraceKey/fp3/Cache 定罪事件） | 搬移 | |
| 压缩策略 | **E** | 上游=检查点+尾部结构化总结（缓存友好）；我方=弹窗确认即关+压缩期排队发送 → B3 端对账定（默认采上游机制+保留我方两条体验项） |

## 2. 工具系统（B1）
- 工具矩阵全体系（ToolZone/ZoneOps/applyZoneOp 事务/五键迁移/对照页）→ **搬移**（用户点名"能力模块"）
- FRAMEWORK_TOOL_SET 三合一（模板+提升−降级，注入链消费）→ 搬移
- 本地工具族（时间/剪贴板/TTS/闹钟/日历/屏幕时间/电量/位置/JS/询问用户）→ 搬移（**工作流/闹钟=用户点名**）
- 浏览器工具整链（导航生命周期/受控输入/存储统一/截图 render_urls）→ 搬移
- 工具产图**地址渲染法**（ToolImagePayload/RenderPayload/render_urls+render_markdown/验存/映射表/死链零输出）→ 搬移（**用户点名**）
- XSS file:// 放行门 + 单一解码点 LocalImageAddress + **渲染即固化 ChatMediaStore** + 导出携图 → 搬移
- upload_fetch + UploadRef 四元组 + 收件箱 → 搬移
- 子代理工具组（dispatch/get 双模式/cancel/预算闸门）→ 搬移（§4）
- 记忆工具 + 技能工具 + 任务清单（TaskListState 按对话隔离）+ ask_user → 在新结构重挂
- MCP 我方定制（懒加载合并域池/名字 sanitize/会话 reconcile/ConnectionKey）→ 搬移（叠加在上游新 MCP 详情页 UI 上）
- 生态桥（OpenClaw/Claude Code/Cursor 指令文件扫描/claw skill loader/插件注册）→ 搬移
- **工具池懒加载**（"巨量工具栏加载"用户点名；/@ 工具矩阵选择器）→ 搬移

## 3. 上传/文档链（B1）
- 上传码 v2（时间码/东八区/原子命名）UploadCodes/FileUtils/FilesManager 四命名点 → 搬移
- DocumentAsPrompt（classic/compat 双模式+兜底纪律）→ 搬移（叠加在上游新注入链上）
- UploadsListing 注记（消息锚定+缓存连续）→ 搬移
- **沙箱→应用渲染总线**（RenderRequestHost `/render` 桥）+ officecli 真渲染（缓存/免拷贝/LRU/整页适配脚本）+ DocumentRenderEngine + PdfRenderView/MediaRenderViews + 渲染等待标志 → 搬移（**沙软一体=用户点名**）
- 文件胶囊 [渲染/分享/导出]（ChatFileActions 单一实现）+ **本对话产出**（ProducedFiles 扫描+胶囊+canonicalPath）→ 搬移（**用户点名**）
- 编辑过的文件（ChatMessageEditedFiles）→ 搬移
- 导出 Markdown（data URI 携图/组装进 IO）→ 搬移
- 文档技能/文件生成最佳实践（officecli 工具链脚本）→ 搬移

## 4. 子代理体系（B1）
- SubAgentEngine（队列 7 并发/1800s 恒定/6s 看门狗结算/熔断）→ 搬移
- SubAgentBudget（live+落盘/256 裁剪/闸门/清零/面板）→ 搬移
- 通知分流（subagent_completed 频道/跳父对话/取消不打扰）→ 搬移
- 子代理会话 folderId 继承（B163）→ 搬移
- **单对话记忆 conversationScopedMemory** → 搬移（**用户点名保留**，叠在上游文件式记忆上）

## 5. CWD/项目包（B1）
- EffectiveCwd 单解析点（pick/fromFolderId/resolve）→ 搬移
- folder 链（selectFolder 单入口/进程记忆 ProjectPackSelection/landing 一次性消费）→ 搬移
- 移动对话到文件夹/移出为助手/守卫（生成中拒操作）→ 搬移
- 项目包胶囊/轨道选包/子代理继承 → 搬移

## 6. 主页 UI 与交互（B2 · 用户点名区）
- **主页结构=我方**：加号→工具箱（**无搜索引擎选择 UI**）；**右上角→本对话产出** → 搬移
- **双星轨导链**（EmptyOrbitSelector：外弧助手/内弧包+聊天项/100s/淡入淡出/文字变色选中）→ 搬移（视觉叠上游 MD3）
- 标题：项目包胶囊 + 标题栏（**弃我方模糊，采上游 3.0.0** 渐变遮罩+滚到栏下）
- 消息区：我方结构（分段胶囊折叠/压缩提示条/分享文档消费/时间展示）+ 上游消息视觉 → 合并
- 抽屉（300dp 固定/露边条/默认页记忆）→ 搬移（视觉跟上游）
- 输入栏：我方功能（/@ 选择器/工具提及/粘贴自管光标/拍照丢照加固/全屏编辑器行为/压缩期排队发送）→ 搬移；视觉模糊=上游
- 会话列表/历史页（我方：搜索并入行/Gauge 预算入口/极简史）→ 与上游"创建时间排序"合并评估
- 通知/FGS 通道族（含子代理/完成分流）→ 搬移

## 7. 渲染与显示（B2）
- **我方 Markdown 渲染链全量保留**（用户定：Codex 路线不改）：vendored markdown 模块 + preProcess（URL 域保护/表格解包/数学 CJK 触发）+ MarkdownParseCache 首帧同步 + MarkdownNew/Markdown + LaTeX 清洗 + StableSizeImage + 代码块自动预览/滑动不触发抽屉 → 搬移（替换上游 ui 模块 markdown 消费位）
- 显示设置（消息边距/布局项）→ 随结构搬移
- 动效：Cherry 令牌（settle/overshoot/临界阻尼/fade-through）→ 与上游 MD3 动效融合（效果最优裁）
- 标题栏/输入栏模糊链 → **D（不迁移，用户定）**

## 8. 媒体与创作（B3）
- 上游 mediagen + MediaCreation 全量采用；我方 imggen/videogen → **D（被替换，批注）**
- 图片保存/预览链（saveMessageImage 同源/ImagePreviewDialog/VideoPlayerDialog）→ 按上游结构核验吸收

## 9. 记忆（B3）
- 上游记忆 2.0（文件库/自动整理/Dream 服务）全量采用
- 单对话记忆（见 §4）+ 旧 Room 记忆数据迁移方案 → E（导出/兼容，执行时定）

## 10. 设置与数据（B1）
- 上游设置拆分五件+ V4 迁移 采用；我方字段族回填（工具矩阵五键/DisplaySetting 我方项/子代理/Skills/单对话记忆/workspace）→ 搬移
- DB：上游 26-30 采用；我方迁移（30_31/31_32 等）重排合并 → 搬移
- 备份/恢复（WebDAV/S3 我方改动）→ 对照择优

## 11. 沙箱/工作区（B1）
- 沙箱一体基建（ProotShellRunner 绑定/端口/dev-shm/预热/rin-tools 注入/桥端点族）→ 搬移
- 工作区截断策略（48K+76K/omittedChars）/后台原语（workspace_job）/终端 → 搬移
- proot：上游 5.1.107.96 与 Alpine 采用；我方绑定改动合并 → 对照
- 文件系统工具（workspace_read/write/edit/glob/grep + 版本快照 .versions）→ 搬移

## 12. 检索与文档体系（B3 · repo 层）
- CODE_INDEX/SYMPTOM_ROUTER/APP_MAP/ANCHOR_MAP/docs 审计件/lessons/知识库 → **保留**（不受换底影响）；换底后逐批刷新
- 开发者面板（DEVELOPER_BUILD 门控+RinDevPanel）→ E（玻璃面板已弃，机制是否保留执行时定）

## 13. 点名保底 × 排除（用户口径 · 不可丢）
**保底**：加号工具箱 / 右上角本对话产出 / 地址渲染法 / 双星轨导链 / 巨量工具栏加载 / "Scale 等改动"（具体面 B2 逐项对照展示）/ 能力模块（内置工具+工具矩阵）/ 工作流·定时·闹钟 / 沙箱一体 / 生成稳定链 / 上传链 / CWD / 渲染链 / 检索体系。
**排除（仅此二项，终定）**：①Cookie6Sided 头像（恒圆形）；②我方输入栏/标题栏模糊实验链（"坏功能"，不迁移，上游 3.0.0 全量采用）。**其余全部搬移——"一个不要缺，一个不要少"（用户 2026-10-18 终令）**。

## 14. 三批推进计划
- **B1 基础设施**：底树 adopt（build-logic/依赖/模块回挂【markdown/document 保留】/签名）→ 生成稳定链接骨 → 工具矩阵/子代理/上传/CWD/沙箱/设置+DB → 编译打通 → **4.9.0-dev** 真机验收
- **B2 UI**：主页结构与各功能面回归 + MD3 视觉 + 点名项核对 → **4.9.1-dev**
- **B3 收尾**：记忆/媒体/压缩/动效融合 + **三遍对账** + 文档/知识库重排 → **5.0.0 正式发布**

> 下一步：等你过目本总账（有漏点直接点名；"Scale 等改动"请给一两个例子便于精确入账）→ 我按账面开 B1。
