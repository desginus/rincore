# 上游 RikkaHub 3.0.0 更新审读报告（UPSTREAM-3.0.0-REVIEW · v1）

> 2026-10-18 · 基线：upstream-2.5.6（447bb7e）→ upstream-3.0.0（**55a108a44**，今晚发布）
> 规模：**94 commits · 506 文件（+67,898 / −37,666 行）**——266 改 / 163 增 / 26 删 / 33 改名
> 工件：`u300-log.txt`（全提交）/ `u300-name-status.txt`（全文件表）/ `u300-numstat.txt`（体积表）

## 一、六条主线

1. **模块化重构**：新 `ui/` 模块（通用组件 + Markdown 全链 + 画板 sketch + Toaster + WebView 组件）；`videogen` → 新 `mediagen/` 模块（图+视频统一）；ai 模块新 `agent/` 包（可复用 Agent 引擎）。
2. **对话内核重做**：删 GenerationLoop / 拆 ChatRequestFactory（ChatTurnContext=会话固定配置一次解析，"正常对话与压缩共用同一请求上下文以命中缓存"）+ ChatService 削 877 行 + ConversationMetaGenerator/Config/Fork/Messages 拆分。
3. **记忆 2.0**："记忆改为按文件组织的 **Markdown 记忆库**，对话后自动整理"（MemoryConsolidator + Scheduler + MemoryDream 前台服务 + MemoryContentGuard + MemoryFilePage/VM；DB 27→29 迁移）。
4. **媒体创作**：mediagen 模块（OpenAI/阿里/火山/MiniMax/OpenRouter，图像+视频，S3 临时地址）+ 应用层 MediaCreation 全套（页面/会话/Composer/服务/DAO/Entity）；旧 imggen 页标注"即将废弃"；创作页卡片化、多选批量保存/删除。
5. **MD3 Expressive 全站改造**：聊天消息/加号面板/搜索/思考/输入控件/设置全族/助手/翻译/备份/媒体/语音/MCP 详情页；PagerTabs；2025 色彩规范；本地设置 compositionLocalOf。
6. **`ui` 模块 Markdown 收敛**："不再并行维护两套实现"——删除 app 侧 MarkdownNew.kt/SimpleHtmlBlock.kt；新组件"解析在后台线程、内容没变的块不重组"、组件注入式（高亮/图片/公式）。

## 二、逐域更新清单（全部 94 提交归域）

### A. 架构与模块化
- ui 模块成立（7c0113173）：ConfirmDialog/Input/Select/Switch/Tooltip/Charts/DataTable/DiffView/WebView 组件族（20+ 文件）
- mediagen 模块（2267943a3 / a26d1d55f / 0450c4c19）：videogen 重构为图+视频统一
- ai/agent 包（e10e10433）：Agent/AgentLoop/Retry —— AgentRequest + AgentHooks(prepareStep/previewStep/finishStep) + maxSteps=256
- data 包整理（fffa6e82e）：拆 skills、合并 remote（RikkaHubAPI 移 data/remote）
- PreferencesStore 拆分五件（735ade8a8）：Settings/DefaultSettings/SettingsExt/SettingsFields/SettingsNormalization + PreferenceStoreV4Migration

### B. 生成/对话内核
- 删 GenerationLoop、拆 ChatRequestFactory（9c5331d4a）：内存会话为唯一权威，修旧快照覆盖/写错节点/删除后重写
- ChatService 拆纯变换与标题/建议生成（e342909ac）
- 会话配置固定（b64beeef6）：模型/思考/搜索配置固化到会话（ConversationConfig）
- 工具审批协议与多步循环入 ai 模块（e10e10433）：修混合审批、中途停止丢工具结果、正则重复应用
- token 统计修复（e7f2c04f7）：多轮工具调用 TPS 修正
- 会话列表按创建时间排序（35fbf61d5）；编辑思考内容（83b1f3e9e）；自动标题开关（f6e3e49a4）

### C. 压缩 v2
- bf614d89a：压缩改为**插入摘要检查点**（不再删历史消息）
- 7cceda23e：**压缩在对话末尾追加结构化总结指令**（命中提示词缓存）+ 输入面板显示上下文使用量；UI=ChatMessageContextCheckpoint（分隔线+折叠摘要）

### D. 记忆 2.0
- c9a159a5c：Markdown 文件库（frontmatter: description/aliases/title + 版本乐观锁 + `[stated]` 标记）；MemoryFilePage/VM；对话后自动整理（MemoryConsolidator/Scheduler/Dream 服务）；MemoryContentGuard；DB 27-29

### E. 媒体创作
- 模块：五商 provider（图+视频）+ 能力矩阵 + S3 临时下载 + keepRules + 8 测试
- 应用：MediaCreationPage/SessionsPage/VM/Composer/RecordItem/Service/前台服务/Files/DAO/Entity/Repository
- 体验：会话列表封面、多选批量、创作页容器变换展开、预设模型换代
- imggen 废弃预警（1c8c3fd37）

### F. UI 与设计系统
- MD3 Expressive 全站（17+ 提交）：聊天消息/加号面板/搜索/思考/输入/设置全线/助手/翻译/备份/媒体/语音/MCP
- 标题栏：渐变遮罩 + 消息可滚到栏下（cb66ba9ed）；输入栏底部渐变遮罩（97060dd51）
- 输入框玻璃：**模糊/液态玻璃双分支**（ChatInputHaze.kt，BackgroundEffectType.BLUR/GLASS + appearanceResponse(dark) 明暗自适应 + 修"系统浅色应用深色发白"）
- 画板 sketch（9be5e5aa6/d7f240d7e/19a5e2310）：androidx.ink 压感笔刷 + 文字/选择工具
- 翻译页 v2 + 翻译历史（93f231149）；MCP 详情页（b8d309e66）；侧边栏搜索并入历史行（660571449）

### G. 设置/数据/杂项
- 设置写入内存为准（4dbf303f8/3462d9ee1）；解码移出主线程（640496127）
- 模型上下文长度配置（05b655bb3，默认 1M）；提供商类型锁（aaa07fd52）
- DB schemas 26-30 + Migration_27_28/28_29（记忆整理/翻译历史/媒体创作）
- MCP：SDK 0.15.0-rikka.4（c907608bc）+ Client ID Metadata Document（b8e0fec4b）+ 资源元数据回退（4ba5d79f2）
- 工作区：复制 Rootfs 路径（927fdd51f）；proot 5.1.107.96（bf8ff5a27）；Alpine rootfs（298e2771e）
- 修复组：CRLF 围栏（c91d21463）/ null 行解码（240deb5fe）/ 前台服务竞态（72747fc9e）/ R8 SnakeYAML（942e09453）/ 搜索截断 #2000（c211c0d44）/ 文件夹写回旧值（4ed5f38d9）/ 新会话归入当前文件夹 #2133（0ee642ff8）
- DeepSeek 搜索服务（893e83f84）；搜索选择器 Claude 内置搜索（aae986e55）；引号高亮（b69331473）；角标样式（a6abed3be）
- 删除：MarkdownNew.kt / SimpleHtmlBlock / ExpressivePopup / RabbitLoading / ViewText / ChatSizeChecker / CoroutineUtils / debug 角标资源 / videogen 全模块 / **ChatServiceTest**

## 三、我方 fork 冲突面（→ 批复问题 q1-q10）

| 域 | 我方现状 | 冲突/风险点 |
|---|---|---|
| 生成链 | GenerationHandler ~2400 行定制（重试链 18/看门狗 150s/五通道完成门/子代理/工具矩阵/工具池绑定） | 上游删 GenerationLoop 换 Agent 引擎——命脉级决策（q2） |
| Markdown | 自研 MarkdownNew/Markdown（首帧同步/增量/数学触发/表格解包/LaTeX 清洗/地址保护/稳定占位 20+ 修复） | 上游收敛 ui 一套并删同名件（q3） |
| 记忆 | Room 记忆 + 单对话记忆 + 注入链 | 上游 Markdown 文件库 + 自动整理（q4） |
| 媒体 | 自研 imggen/videogen 页面 | 上游 mediagen + MediaCreation 全套（q5） |
| UI | 玻璃链终版（183/184：双层串行/噪点/蒙版/雾）+ 轨道/抽屉 300dp/开发者面板 | MD3 Expressive 全站 + 上游玻璃双分支（q6/q7） |
| 结构 | PreferencesStore 单文件+zone 事务；DB 32+ | 上游拆五件 + DB 26-30 合并需重排（q8） |
| 依赖 | Compose BOM 2026.08.00 / haze beta01（119 基准） | 3.0.0 依赖更新（chore: update deps）需评估（q8 附带） |

## 四、结论
- 3.0.0 是**一次真正的平台级换代**（模块化+内核+设计系统三线齐动），与我方 180+ 版本的自研增量几乎全面交叠——**不可全量对齐，只能按域批复选择性移植**。
- 各域具体去留 = 见随后 10 个问题（q1-q10）。

## 五、路线终选分析：正向搬入 vs 反向换底（2026-10-18 · 实测数据）

### 量化对照（本次实测）
| 维度 | 数值 |
|---|---|
| 上游 3.0.0 改动 | 506 文件（kt/kts 400）/ +67,898 −37,666 行 |
| 我方相对 2.5.6 分歧（kt/kts） | 1,065 文件（含仅注记级） |
| **正向·冲突区**（上游改 ∩ 我方改） | **209 文件**（上游变更量 ≈28.5k 行）——全部集中在 ChatService / MarkdownNew / Markdown / PreferencesStore / FilesPicker / TTSProviderConfigure / UI 全族等命脉定制面 |
| 正向·顺收区 | 191 文件（≈24.8k 行，可较净搬入） |
| **反向·迁移面** | 我方独有 **412** + 共有分歧 514（含注记级；功能面净估 ≈600）——全部是"我们自己的代码" |
| 3.0.0 模块集 | +`ui` +`mediagen` +`build-logic`；**无 markdown 模块**（并入 ui）；document/workspace 等我方依赖模块均在 |

### 两路本质与风险
- **正向** = 把上游"重架构"（agent 拆包 / ChatService 拆分 / MD3 全站 / 设置拆分 / 记忆 2.0）在我们的定制树上重新实施；209 个冲突点全部人工嫁接——每个点都是"隐性行为漂移"的入口（幽灵再生面），且最重的恰是命脉文件。
- **反向** = 以上游 3.0.0 为新主梁（用户批复里"全跟"的全部大域——UI/MD3/玻璃/媒体/记忆/设置拆分/依赖——零移植成本），把我方 keep-list（≈600 件，我们自己的代码）搬过去。风险=遗漏——用《迁移总账》逐项验收治。

### 推荐：反向（3.0.0 为底 + 完整迁移总账 + 2-3 批推送）
三步保险：① 先出《迁移总账 v1》（v4.8.189 全功能面 × 去向：搬移 / 上游等效替代 / 放弃需批注）→ 用户过目；② 批1 基础设施 → 批2 UI → 批3 收尾，每批 4.9.x-dev 真机验收；③ 收尾三遍对账（搬移核验 / 功能核验 / 体验核验）后发 5.0.0。

### 已定位用户排除项
- **`AvatarShape.kt` 的 `MaterialShapes.Cookie6Sided`**（加载态把头像变六边饼干形，即用户所说"类齿轮/锯齿边缘/较皱"）→ 反向迁移时头像恒 `CircleShape`（该项不跟）。

### 现成工件
- 全量清单：`u300-log.txt` / `u300-name-status.txt` / `u300-numstat.txt`
- 双轴差异：`numstat-251-nr.tsv` / `numstat-256-nr.tsv` / `ghost-final4.tsv`（= 迁移总账原料）
- 我方索引：`CODE_INDEX` / `APP_MAP` / `ANCHOR_MAP`（= keep-list 来源账本）

---

## 五、路线终选记录（2026-10-18）与量化依据

**用户拍板：反向换底**（3.0.0 为底树 + 《迁移总账》搬我方，2-3 批推进；验收基准=按 v4.8.189 功能一个不少，凑数/负面可弃需批注）。

**量化（实测）**：
- 上游 3.0.0 改动 506 文件（kt/kts 400）；我方相对 2.5.6 分歧 1065 文件（kt/kts，含注记级）。
- 正向冲突区（上游改 × 我方改）= **209 文件**≈28.5k 行上游变更——全部集中在 ChatService/MarkdownNew/Markdown/PreferencesStore/FilesPicker/UI 全族等命脉定制面（每点人工嫁接=幽灵再生面）。
- 正向顺收区=191 文件≈24.8k 行；反向迁移面≈**926 原始文件（功能面净估≈600）**——全是我方自有代码。
- 3.0.0 模块集：+`ui` +`mediagen` +`build-logic`；无 `markdown` 模块（我方保留为自有模块）；document/workspace 等依赖模块均在。

**关键裁定**：我方标题栏/输入栏模糊实验链**不迁移**（用户："新版做的比我们那个优秀太多了"）；头像排除项已定位=3.0.0 `ui/hooks/AvatarShape.kt` 加载态 `MaterialShapes.Cookie6Sided`（"类齿轮/锯齿边缘"）→ 迁移时恒 `CircleShape`。

**执行工件**：`MIGRATION-LEDGER.md`（迁移总账 v1：14 节 × 去向分档 × 三批计划）；安全存档点 `checkpoint/v4.8.189-pre-adopt`。
