# RinCore 症状路由表（SYMPTOM_ROUTER · v1 · 2026-10-18）

> 用途：拿到"某处不对劲"的症状 → 在本文搜关键词 → 直达域/文件/第一诊断要点。
> 层级：本表（症状级）→ `docs/APP_MAP.md`（域卡片级）→ `docs/CODE_INDEX.md`（全文件级）→ 知识库 `rincore-bug-record`（案例级）。
> 维护：每轮 bug 修复后回填一行；症状描述用"用户语言"。

## 输出 / 流式 / 网络
| 症状关键词 | 去向 | 第一诊断要点 |
|---|---|---|
| 输出中断、静默截断、不重试、突然停 | `data/ai/GenerationHandler.kt`（streamLoop/完成门/重试链）+ `ai/.../ChatCompletionsAPI.kt` | 先分三层：watchdog fired（客户端判死）/ onFailure（上游断）/ stream_retry（重试动作）——运行日志直接可辨；完成门语义=v4.8.124 回滚后的 116 基线 |
| 发消息一直加载、无首次输出、加载很久 | `GenerationHandler`(isDeviceOffline/isConnectPhaseFailure) + `ProviderManager` + 网络切换监听 | 系统"按应用联网封锁"绕过能力位预检（B138）；终态文案含设备侧排查清单 |
| 两者并行、失焦对话中断 | `CallTracer`(TraceKey 隔离) + `GenerationHandler` | v4.8.124 已回滚并行揣测链；日志按对话隔离；未解释的干净边界关流=上游行为 |
| "某版本更好/最近变差了" | 先做全量 diff 分类（B151 方法） | "事件多了" vs "事件被看见了"（可见化改变感知）；再分上游劣化 vs 客户端回归 |
| 首字慢、TTFT 高、工具后恢复慢 | `ConnectionWarmer`/`ProviderWarmup`/探活链 | resume_gap 的 poke= 字段分辨探活慢 vs 组装慢（v4.8.132） |
| 缓存命中骤降、反复全量 prefill | 网关侧（CACHE/gateway_miss 事件自动定罪）| 指纹零漂移+断裂点固定+回退现象=服务端缓存块丢失 |

## 图片 / 渲染地址（B193 域）
| 症状关键词 | 去向 | 第一诊断要点 |
|---|---|---|
| 图片显示错、一批里个别错、图自己变样 | `ChatMediaStore` + `MessageImageSnapshotTransformer`（固化链） | 判据："过一会/过几天变样"=活引用回灌（已由固化根治）；"生成当场错"=模型抄错地址 |
| 图片地址渲染不出、alt 可见图不可见 | `ToolImagePayload`(产出) → XSS 门(isAppPrivateFileUri) → `WorkspaceImageFetch`/`LocalImageAddress`(认领) → `WorkspaceImageResolver`(解析) | 五段链任一段断都表现为"图不可见"；先看地址形态（编码 file:// 为规范） |
| 多工作区同名文件串图 | `WorkspaceImageResolver.extractHostWorkspaceUuid`（定点优先） | 地址带 UUID 则只解析该工作区（v4.8.185） |
| 图片错位、撞名、同轮多图变同图 | `UploadCodes` + `FileUtils.createUploadFile` + `FilesManager` | 8 位前缀正则+原子取号+64 次重试；新 casing 见 B155/B158 |
| 图片尺寸跳变/滚动抽动 | `StableSizeImage`(尺寸预留) + `Markdown.kt`(快照流等值跳过) | 本地图走单一解析点预读头；渲染重入=跳过等值写（B164） |
| 保存图片失败 | `ChatFileActions`/`ImagePreviewDialog` 保存链 | 必须与渲染同源解析（B150）；file:/ 单斜杠形态已接纳 |

## 玻璃 / 主题 / 动效
| 症状关键词 | 去向 | 第一诊断要点 |
|---|---|---|
| 输入栏玻璃、格栅、条纹、织物感 | `ui/components/motion/RinGlass.kt` + `ChatInput.kt` | 物理半径<32px 才无降采样网格（B191）；定版=2dp+噪点映射+蒙版 50%（D26）；大半径=格栅域不要碰 |
| 标题栏模糊/分隔线/板带 | `ChatPage.kt` topBar 段 | 定版=单层 titleBlurStyle（透明底+零噪点）；"分界线"=不透明底料/雾层边缘（v4.8.184） |
| 主题预设、色板、深浅色 | `ui/theme` + `SettingPreferencesThemePage` + `material3`(color-utilities) | 主题预设=极简白默认；动态色走 material3 模块 |
| 弹窗/动效/手感 | `ui/components/motion/CherryMotion.kt`(令牌) + `HyperDialog` | 开 350 overshoot / 关 250 settle（D17）；入场必须 false→true 翻转驱动 |
| 碳氪背景/动态渐变背景 | `AssistantBackground` + `MeshGradient` | 未配置=不画；背景层是 haze 源 |

## 对话 / 消息 / 输入
| 症状关键词 | 去向 | 第一诊断要点 |
|---|---|---|
| 消息渲染：公式/表格/嵌套/围栏错 | `markdown` 模块（`MarkdownNew`/`TableFenceUnwrapper`/`LatexCommandSanitizer`/`UrlRegionScanner`） | 数学 CJK 触发、表格单元格行内直渲、```md 围栏解包、\tag 清洗（B142/B144/B149） |
| 输入栏、光标、粘贴、@补全、/@ 矩阵 | `ui/components/ai/ChatInput.kt` + `ToolMatrixPicker` + `WorkspaceCompletionProvider` | 粘贴自管光标（v4.8.144）；/@=光标触发；@工具名=ToolMention 注入 |
| 消息气泡边距/抽屉宽度/轨道选择器 | `ChatList.kt` + `ChatPage.kt` + `EmptyOrbitSelector.kt` | 抽屉 300dp 固定；轨道 100s/圈；气泡 8dp |
| 胶囊窗/产出/文件三动作 | `ChatFileChip.kt` + `ConversationArtifactsSheet.kt` + `ChatFileActions.kt` | 三动作[导出/分享/渲染]必须走共享实现；dismiss 僵尸态教训（B157） |
| 压缩/记忆/单对话记忆 | `ChatService`(压缩) + `MemoryRepository` | 压缩期可发送排队（v4.8.123）；单对话记忆只在所属对话可见 |

## 工具 / 代理 / 技能
| 症状关键词 | 去向 | 第一诊断要点 |
|---|---|---|
| 工具矩阵写不进/假成功 | `ToolZone`+`ZoneOps`+`SettingVM.applyZoneOp` | 一次动作一次事务写（v4.8.89 教训）；回执对"刚写的值"判定 |
| MCP 连不上/工具没出现 | `McpManager` + `DynamicTools` + `ToolsBuilder` | mcp__<server>__<tool>；$defs 透传；FRAMEWORK_TOOL_SET 三件套 |
| 子代理：超时/并发/产物落错 | `SubAgentEngine` + `SubAgentBudget` + `dispatch` 工具 | 超时恒定 1800（B154）；并发迁移（B162）；CWD 继承（B163） |
| 工作区/沙箱/CWD/挂载 | `WorkspaceManager` + `WorkspaceRepository` + `ProotShellRunner` | CWD=folder→assistant 链；围栏已废（B: 4.8.138）；/dev/shm 已绑 |
| 上传/文档/上传码 | `FilesManager` + `DocumentAsPromptTransformer` + `UploadCodes` + `upload_fetch` | classic/compat 两模式；码=文件名前缀；收件箱标注 v4.8.186 |
| 技能/生态/插件 | `data/skill`（SkillManager/SkillDiscover）+ `ecosystem`（EcosystemBridge/PluginManager） | SKILL.md 指令注入；发现= CherryIN + skills.sh 双源 |
| 浏览器工具 | `browser` 包（BrowserController/Navigation） | 导航生命周期唯一事实源=WebViewClient（B139） |
| 定时/工作流 | `data/alarm` + `workflow` 包 | — |

## 数据 / 设置 / 系统
| 症状关键词 | 去向 | 第一诊断要点 |
|---|---|---|
| 设置改不动/重启丢失 | `PreferencesStore`（双 Store）+ `SettingVM` | update 持锁；事务写；迁移标记模式（upgraded 字段+键+双保存路径） |
| 导入/导出/备份 | `data/export` + `data/sync` + `Export.kt` | 导出器不得复用 UI 列表层；markdown 导出图片=data URI 内联 |
| 启动崩溃 | `RikkaHubApp` + 通知频道(setName!) + DB migration | Room 迁移链最高危；hyperOS 联网控制坑 |
| 通知不对/不响 | `AppEvent` + `RikkaHubApp` 频道创建 | 频道必须 setName；子代理完成独立频道 |
| 用量/密钥/余额 | `data/usage`（UsageApi=OC / CommandCodeUsageApi=CC） | 空密钥折叠判据；60s 缓存 |
| 搜索/语音 | `search` / `speech` 模块 | — |

## 维护约定
- 新 bug 修复后：在 bug-record 归档同时，把"用户语言症状"回填本表（若属新族）。
- 本表只放"去哪看+第一判据"，不放案例细节（案例在 bug-record）。
