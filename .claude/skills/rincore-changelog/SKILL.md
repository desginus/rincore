---
name: rincore-changelog
description: "[中优先级·RinCore开发对照] RinCore 完整版本更新日志。触发词：版本历史、更新日志、changelog、这个版本改了什么、版本对比、回滚历史、版本链。任何需要了解 RinCore 某版本改动/某功能何时引入/何时回滚时加载。不涉及：Bug 根因细节（用 rincore-bug-record）、方案决策（用 rincore-decisions）。"
---

# RinCore 更新日志（v3.20.0 为最新）

## v3.20.0（x-opencode-session 自动注入 — OpenCode 官方 09/06 强制，2026-09-05）
- OpenCode 官方邮件: 缺 x-opencode-session 头的请求 09/06 起可能报错, 要求每会话一个稳定 ID
- 实现 (自动生效非 opt-in): generateText +conversationId 参数 (默认 null 零破坏) → params → ChatCompletionsAPI 两处 Request 的 sessionHeader 扩展, 仅 host==opencode.ai 且 ID 在场时注入; ChatService 主对话传 conversation.id (会话 UUID 稳定); 标题/建议/翻译等单轮调用不传不发
- v3.6.80 曾全局删除该头 (疑似 grok 400), 现按 host 精确判定恢复, 官方语义反转

## v3.19.0（CC 焦点大窗 + AskUser 开放输入框 + 渲染放宽 + 报错敏感，2026-09-04）
- CC 密钥焦点模式不显示完整形态: 根因=数据分支只认 usages[apiKey] (OC), CC 结果在 crossUsages, CC 聚焦时 focal 分支恒缺失; 修复=焦点数据双族统一 focalOC/focalCC, focus 大窗新增 CC 四环, cards 焦点卡按族选 mini 卡, error 判定双族
- AskUser 默认开放输入框 (用户定版): 恒显示 补充说明 可选框, 提交并入顶层 followup 字段 (模型解析兼容), answered 态显示
- 本地图片渲染放宽: 扩展名白名单拒绝删除, 全部尝试解码失败落占位符; mime 补 heic/avif/svg, 未知扩展 image/* 嗅探
- 报错敏感性: 用量查询部分密钥失败不再静默 (error 追加 OC/CC 失败张数); cache-fp 诊断 catch 加吞错标注
- 消息点击编辑: 链路核实完整 (Surface onClick → onEdit), 无需改动
- 技术债标注: GenerationHandler/ChatService/ChatCompletionsAPI/PreferencesStore 四核心文件审计头

## v3.18.0（用量页焦点视图重构 + 密钥统一保存收口 + 导出 ANR 修复，2026-09-04）
- 焦点视图矩阵: 渲染完全由 usageViewMode 决定与 otherVisible 解耦 (旧实现其他密钥用量全满被滤空时 cards 模式错误落入大窗分支); cards=焦点小卡+其他小卡, focus=仅焦点密钥完整形态 (竖列大窗)
- 密钥统一保存收口 (用户定版: 密钥就是密钥不分族): UsagePage LaunchedEffect 兜底 — 当前 key 不在卡包自动收编, 根治备份恢复/迁移等单槽路径的概率性漏保存; UI 文案全部去族化
- 技术债扫描修复: SettingCallTracePage 导出链 runBlocking+file IO 在主线程 (日志大时 ANR) 协程化; 审计达标: GenerationHandler/ChatService 无主线程阻塞, ChatCompletionsAPI eventSource 生命周期正确

## v3.17.0（强兼容完全独立 Cherry 路径 + CC 长保活池 + 预热池错配修复，2026-09-04）
- 强兼容重构: 新增 buildMessagesCherry 独立构造路径 (assistant reasoning 一律不回传/纯 reasoning 整条跳过/tool 四要素无 name/空 content+tool_calls → content:null); 常规路径全部还原 (v3.16.0 穿透参数全撤), 调用处 if/else 分流, 开关两态互不可见
- CC 长保活池: v3.13.4 CC 预热进默认池 keepalive 60s, 60 秒后连接被 OkHttp 回收 = 预热白做; effClient 将 api.commandcode.ai 同入 opencodeClient 300s 池 (对齐 OpenCode); warmWithOkHttp 长池判定 + RikkaHubApp/ChatService 四处预热调用传参同步修正
- 预热核查: OpenCode 链 (启动 warmWithOkHttp → GET /models 真实请求 → 300s 池) 落实; CC 链动作真实但此前进错池, 本次修复; 消息时定向预热与裸 socket DNS 兜底独立正常

## v3.16.0（强兼容模式 + User Agent 方向移除 + 基础链路审计，2026-09-04）
- 强兼容模式: NetworkSetting.cherryCompatMode (持久化, 网络页首位开关, 默认关) → TextGenerationParams.cherryCompatMode → ChatCompletionsAPI 分支。开启后请求体对齐 Cherry Studio/AI SDK 极简形状: ①reasoning_content 不回传 (纯 reasoning assistant 自然跳过) ②tool 消息去 name 字段 ③空 content+tool_calls → content:null ④思考控制整体停用 ⑤常规模式行为零变化
- User Agent 方向移除: 网络设置页 UA UI 整块删除、DataSourceModule 两处 interceptor UA 注入删除、v3.9.13 空 UA 检查空壳清除; NetworkSetting.userAgent 字段保留仅作 DataStore 反序列化兼容; 工具 fetch UA (RinCore/3.6) 与 CC 查询 UA (commandcode-cli) 属功能标识保留
- 基础链路审计: buildChatCompletionRequest 逐字段核对 (model/messages/temperature/top_p/max_tokens/stream/stream_options/tools/tool_choice) 全部合规; 非兼容风险点 (reasoning_content 回传/name 字段/thinking 字段) 已由强兼容模式统一覆盖

## v3.15.4（CC 思考分支简化 — B117 回归修复，2026-09-04）
- CC 通道关思考无法建连、整个思考模块炸: v3.15.2 发的 thinking 字段被网关拒绝 (Bifrost 类 DeepSeek provider 不认识 thinking; claude 子分支永不命中 — CC 严格校验模型-端点配对, claude 发 chat/completions 直接 400 wrong endpoint)
- CC 分支简化为纯 reasoning_effort: AUTO 不发 / OFF→low (v4-flash 最低档, CC 无 none/minimal) / low|medium→low / high→high / xhigh|max→max

## v3.15.3（file:// 点击崩溃根治 + 偶发无响应，2026-09-04）
- FileUriExposedException 主线程崩溃: 模型输出 file:// 链接 (xlsx 等) 被 Compose 默认 handler 直通 Intent.setData → StrictMode 判死。四处 markdown 链接点击统一拦截 → openWorkspaceLink → resolveAnyFile (新增, 不限图片扩展名) → FileProvider content:// → ACTION_VIEW
- 偶发发消息无反应: v3.15.2 非 DeepSeek 家族 OFF 发 reasoning_effort:none 原样, 强制思考型模型 (GLM-5.3 mandatory) 与部分后端 none=400/空响应 → OFF 语义改 minimal (Bifrost 全档支持)

## v3.15.2（思考程度控制修复 + host 前缀适配，2026-09-04）
- 思考不可调三重根因: ①REASONING 能力门槛吞参数 (聚合网关放行) ②OpenCode 非 DeepSeek 家族静默跳过 (v3.11.8 防 400 副作用, 补 reasoning_effort 原样发送) ③CC api.commandcode.ai 落 else 分支 OFF→low 假关 (新增 CC 分支: deepseek→thinking+effort / claude→thinking{type,budget_tokens} / 其他→effort 原样); 清理 v3.6.80 opencode 死分支
- workspace host 前缀适配: [file://]/data/data|user/0/<pkg>/files/workspaces/<UUID>/files/<rel> 归一化为 /workspace/<rel> (教训固化: proot 与 host 渲染端互不可见, /data/user/0 symlink 别名等价归一)

## v3.15.1（突然中断无重试 + 缓存骤降双修，2026-09-04）
- Bug1 突然中断无重试: isInitPhase 判据 retry.stream==0 把输出中断流误判为发起阶段 → init 池 4×25s=100s 全静默终报, 三轮链没机会跑; 且类成员计数在子代理并发时互相偷预算
- 修复: RetryState per-request 局部对象经参数传入 generateInternal + receivedAnyData 置位 (collect 内 chunk.choices 非空即置位) + 判据改为 receivedAnyData==false 才是发起失败 — 输出中断一律走三轮链
- Bug2 缓存 90%→0: currentMcpTools 每步实时拉取, MCP 连接波动时 tools JSON 抖动 → DeepSeek 前缀缓存每步全灭 (缓存键含 tools 序列)
- 修复: MCP 工具会话内快照 + DynamicTools.isDirty/markMcpDirty 置脏刷新 (manage_mcp_servers 功能保留)

## v3.15.0（原版 2.4.16 定向移植，2026-09-03）
- 8 项中 6 项移植: 气泡透明度 roundToInt / HTML-SVG 默认不预览 / 快速模型思考级别+移除标题建议模型 / 自动重试开关 (NetworkSetting.enableAutoRetry) / PickVisualMedia 图片选择 (对齐原版只迁 image) / TTS 稳定性 (TtsController 重构+TTSProviderException+倍速移常规页)
- 2 项跳过: Gemini 混合工具 (upstream 已拆 google/ 子目录, RinCore 单文件结构前提不存在) / 硅基流动余额移除 (RinCore 自研 provider 集合无此对象)
- 方法论: 先分清基准 (upstream 4309fdfe 终态符号集合对照) 再整体交织, 不追编译错误

## v3.14.0（断流重试统一三轮链，2026-09-03）
- 用户实证: 纯文本输出中途卡几十秒, 极少数恢复多数彻底卡死 — 多链并行恢复混乱
- 重构为用户定版三轮链: 每轮=风暴 3 次×300ms + 经典 3 次 (1s/2s/4s, 对齐原版 2.4.16 指数退避), 共 3 轮 18 次, 总窗口 ≈26s 必有终报
- 废弃: watchdog 单次恢复 / headerRetry 4 次流中分支 / 风暴 15×500ms 三分支; 发起阶段 4 次独立池保留
- CC 图片根治 (v3.13.3-7 迭代): 最终方案 = tool result 图片重定位 (图片在 Tool part.output 里非 TOOL 消息), 对齐 AI SDK 标准行为

## v3.13.3（Command Code 图片兼容适配，2026-09-03）
- 根因: CC 网关严格校验 ChatCompletions 图片 — GIF data URI 被拒 / 编码失败发空 text 块被拒 → Invalid input → 发起重试 4 次 = 无报错硬等卡死；OpenCode 网关宽容 + Cherry Studio 统一转 JPEG 所以正常
- CCImageCompatTransformer (CC 专属 opt-in): JPEG/PNG/WebP 放行, GIF/HEIC 等转 JPEG 静态帧, 不可解码剔除+文本备注, 绝不发空块
- 双重生效: 设置开关 ccImageCompat && key user_ 前缀; 设置-偏好-网络独立开关组; DataStore 两端接通

## v3.13.2（时间进制 + 跨族统一小卡 + CC 小卡第四环，2026-09-03）
- countdownText 进制对齐: 24h 进位天、7 天进位周 (同 OpenCode formatRemaining); UsageCards.formatRemainingMs 统一
- 卡片视图跨族密钥可见: 新建 UsageCards.kt (UsageMiniCardData 四环 5h/周/月/重置), 两页 doQuery 按 key 前缀分流存 crossUsages, otherVisible 合并本族+跨族
- CC KeyCard 补第四环"重置" (红→绿反向), 恒显四环; 两页 KeyCard 签名统一 MiniCardData
- 编译失败三连教训: 局部变量先声明后用 / 改签名全局 grep 调用点 / fetchUsage 可空 mapNotNull 过滤 / patch assert 失败静默中断需独立小脚本

## v3.13.1（planId 提取容错 + 多级授信匹配，2026-09-03）
- planId 三级回退: data.planId → 顶层 planId → data 本身是字符串
- 授信多级: 精确 → 包含匹配 (最长 key, goat_v2 命中 goat) → 月度剩余"最近不足档"反推; percent clamp 0-100
- 诊断自证: 未匹配时 UI 副标题显示真实 planId 原文, 日志打 subs 原文前 300 字符

## v3.13.0（月度总额授信语义修正 + 缓存连接巡检，2026-09-03）
- 月度"目录不匹配"根因: 三重校验过度设计 (官方积分滚存永不过期, 剩余超月度池合法) → 改授信语义: planId 命中即显示总额与百分比; cap 锚点降级日志警告
- 缓存/连接巡检全绿: 重试三池请求级重置 / 幂等缓存函数局部 / workspace cache key mtime+size / 预热节流 host 60s / 连接池 12x60s+长保活 12x300s / CC 生成走 claude 池

## v3.12.8（预热开关落盘 + 月度已用百分比 + 重置卡红→绿，2026-09-02）
- 预热开关重启丢失根因: 只在 Settings 数据类定义, DataStore 两端都没接 (纯内存字段); 补齐键定义+流恢复+updateSync — 教训: 新增设置字段必须同 commit 接通两端
- 月度已用百分比宽松回退 (目录总额算已用比例, 不再 0%)
- 重置倒计时颜色定版: 与用量卡反向 红→绿 (环+文本同色, CC+OpenCode 双侧)

## v3.12.7（CC 展示完全对齐 OpenCode，2026-09-02）
- CC 单密钥四卡复刻 UsageRingCard 竖列布局: 顺序 5h→周→月→重置倒计时最下, 环 64dp 上名字下重置时间, 四卡恒显
- bottomText 合并剩余数量+重置时间; 重置卡时段标注式; 多密钥 MiniRing 渐变统一; 空壳 item 嵌套修复

## v3.12.6（双预热开关化 + CC 第四卡 + 预热节流，2026-09-02）
- 设置-偏好-网络"密钥预热"组: OpenCode/Command Code 两独立 Switch (默认关); ChatService 按开关分流
- warmWithOkHttp 60s 节流; 定向预热变慢根因 = 同 key 并发预热+生成被服务端串行化 → opt-in

## v3.12.5（user_ 分流决定性修复 + 全密钥渐变 + CC 专项预热，2026-09-02）
- 分流决定性 bug: 判据 startsWith("User") 大小写敏感, 真实 key user_ 永不命中 → 一直查 OpenCode 端点必然失败; 修复 ignoreCase=true
- 渐变配色升级全密钥默认: OpenCode 四卡+MiniRing 全接 usageColorArgb; 重置倒计时红→绿 (后 v3.12.8 定版反向)
- ChatService CC 专项预热 (v3.12.6 开关化)

## v3.12.0（对话发起与连接稳定性定版，2026-09-02）
- 发起阶段优化定版: header 判死 15s→25s (ClaudeProvider+ChatCompletionsAPI 同步, 吸收网关冷启动 10-20s 单窗口直接成功) + 重试 15→4 次封顶 (4x25s+退避≈110s 快速终报, 旧 225s)
- 退避分级: 前 2 次 800ms (瞬时挂起快速恢复), 3 次起 2s (持续不可达不连续怼)
- 预热请求短超时 clone (connect 4s/read 6s, 同池): 死网关时预热线程不再按主 client 3min readTimeout 挂死
- keepAlive 60s 维持 v2.9.8 定版 (DeepSeek 空闲关闭快, 拉长撞陈旧连接 → unexpected end of stream)

## v3.11.35（子代理统计补齐 + 发起重试重写，2026-09-02）
- 子代理三路径 (成功/超时/失败) 填充 tokensIn/Out + tripCount; harvestTripCount = 会话内 assistant 消息数 (此前 tripCount 恒 0)
- 详情卡耗费统计: tokens in/out + 合计 (账单口径)
- 发起重试重写: header 判死 25s + 4 次封顶 (见 v3.12.0)
- 教训: v3.11.30 成功路径填充块因脚本中断未落盘 (统计恒空的根因)

## v3.11.34（show_image 下线 + 输入条透明修复，2026-09-01）
- show_image 工具删除 (被 workspace:// 内联渲染取代); 注册点 ToolInvocationContext+LocalTools 两处; 历史调用卡回退通用渲染
- 输入条透明 bug: Surface color 与 hazeBlur modifier 条件必须同源 (enableBlurEffect && !loading), color 单看 enableBlurEffect 时生成中透明

## v3.11.33（用户图片空白修复 + 重试计数残留根治，2026-09-01）
- ZoomableAsyncImage 回退分支式布局: Box+matchParentSize 在无宽度约束场景 (clip+height(72dp)) 塌缩空白; 失败态/正常态共用同一 modifier 直挂显示组件
- GenerationHandler headerRetryCount 请求级重置 (Koin single 类成员跨请求残留, 2-3 秒报"15 次"物理不可能=残留铁证); header retry 日志带 elapsed

## v3.11.32（workspace:// 渲染死点修复，2026-09-01）
- 真实死点: resolver 转发路径须带 "/workspace" 前缀 (ROOTFS_WORKSPACE_DIR 常量), 直接传 rel 会 fallthrough 到 linuxDir 必然 not_found
- show_image detail 插值事故修复 + 失败环节分级文案; file:///workspace/ 三斜杠支持; workspace:// 失败渲染 alt+占位框
- 外部测试模型"跨进程/桥通信"结论被证伪: 单进程架构, PathSafetyGuard 策略文案≠内核权限

## v3.11.31（workspace:// 图片内联渲染首版，2026-09-01）
- 纯函数 resolver (三前缀/宽松 percent-decode/UTF-8 中文/.. 折叠穿越拒绝) + WorkspaceManager.resolveRootfsFileSafe (复用 resolvePath 内建 canonical 防逃逸)
- Coil3: WorkspaceUriKeyer (mtime+size cache key, 覆盖更新即新图) + WorkspaceImageFetcherFactory (扩展名白名单, ImageSource(path,fs) 直读 rootfs)
- coil3.5 API 口径: coil3.key.Keyer / ImageSource(path, fileSystem) / Path.toPath(); matchParentSize 是 BoxScope 成员不 import
- 磨砂生成期降级: loading 时输入条 hazeBlur → 半透明纯色 (静止恢复)
- WorkspacePathResolverTest 单测 (R 系列用例)

## v3.11.30（task_tool 压测 + 熔断双条件 + token 统计，2026-09-01）
- task_tool 22 条压测修复 (响应轻量化不回显清单 <1KB, UI 从 input.tasks 解析)
- 复读熔断误杀修复 = 双条件: 全文重复 >=4 且 repetitionTailCount 尾窗 768 内近邻 >=3
- GenerationHandler idempotentCache: 同工具+同参数重放返回上次结果, 不占 TOOL_SAME_TOOL_CALL_LIMIT 预算
- 子代理 token 统计 harvestTokenUsage (账单口径)
- CI 失败教训: 多文件 python patch 中断丢整段 (success fill 漏提交, harvestTokenUsage 函数体 v3.11.31 补)

## v3.11.29（子代理运行记录 Room 落盘，2026-08-31）
- sub_agent_runs 表 + SubAgentRunDao; 启动恢复 restoreFromDisk; 遗留 running/pending 标记 FAILED(process_lost)
- Migration_28_29 手写 (schema 28.json 缺失时 AutoMigration 不可用)

## v3.11.28（派发/执行解耦，2026-08-31）
- dispatch() 不再 join: 前台/后台统一异步派发, 立即返回 run id + pending; 终态由 subagent_get 轮询
- subagent_* 工具卡恢复显示 (与普通工具一致)

## v3.11.27（子代理清理 + prompt 隔离，2026-08-31）
- notifyParentIfBackground 移除; ConversationDAO 全列表排除子代理会话; 子代理 skipAssistantPrompt 跳过用户 system prompt

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
