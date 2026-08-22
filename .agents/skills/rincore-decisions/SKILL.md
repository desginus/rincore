---
name: rincore-decisions
description: "[中优先级·RinCore决策对照] RinCore 关键方案对比迭代记录：为什么这么选/备选方案/回滚记录。触发词：方案对比、为什么这么选、这个方案、决策记录、权衡、回滚、迭代历史。任何涉及方案选择、架构权衡、回滚决策时加载。不涉及：Bug 细节（用 rincore-bug-record）、版本历史（用 rincore-changelog）。"
---

# RinCore 方案决策记录

## D18. 产品线拆分废弃：只保留 A 线单产品构建（v3.9.2，用户决策）
- 背景：v3.8.44 建 WaterHub 方向功能（工具入口/漂浮字幕），v3.8.45 拆 flavor 双线
- 用户反馈：只要求渲染功能，其余全量回滚；B 线整体废弃，"相当于从来没有过"
- 决策：移除 waterhub flavor/资源/文档，恢复单线构建单 artifact；git 历史不重写（风险大于收益，代码+文档清理已达成"废弃"语义）
- 经验：产品线扩张必须用户明确确认；功能边界=只做被点名的

## D17. MCP 参数必须按 schema 类型清洗（v3.8.29，用户决策）
- **问题**：模型生成嵌套参数常字符串化（"[{...}]"），MCP 服务端 -32602
- **规则**：callTool 前按 inputSchema 声明的类型恢复字符串值（array/object 解析、number/boolean 转换），递归 properties/items；schema 缺失不动作
- **依赖**：工具 inputSchema 必须从服务端 tools/list 完整保留（McpTool.inputSchema）

## D16. 压缩保留边界：对话轮 + token 混合判定（v3.8.28，用户决策）
- **问题**：按固定条数（60%）压缩遇消息长短不均，token 效果高度不稳
- **规则**：保留边界以轮（user+assistant，含工具消息）为粒度；token 60% 定位 + 四舍五入到最近整轮；始终保留最近至少一轮、始终压缩至少一轮；轮数少（如两轮）直接保留一轮不依赖 token
- **UI**：保留最近消息数量仍按条数显示，默认值 = 智能推荐，可手动微调

## D15. 请求 tools 顶层白名单（v3.8.27，用户决策）
- **规则**：请求 tools 数组只允许 批准框架集(FRAMEWORK_TOOL_SET) + 豁免集(exemptFromDomainTools) + 引擎工具(memory_tool/invoke_tools) + 已加载域(loadedDomains) 工具
- **其余一律**强制归入 invoke_tools 池内（invoke_tools 加载后才进请求），Skill 不得跳脱暴露顶层
- **实现**：toolsInternal 构建后白名单硬过滤 + 泄漏 Log.e（回归自曝），loadedDomainToolNames 预计算避免重复分类

## D14. 域标识统一性：normalizedFullPath 铁律（v3.8.25，用户决策）
- **教训**：域展示/操作必须用完整路径（normalizedFullPath / unifiedView.tree），任何 CustomDomain.name 短名自拼都会与 classified 完整路径 key 断裂（B35：管理子域全 0）
- **同源**：子域列表一律 unifiedView.tree[parentDomain]，禁用 entries+customDomains 自拼（幽灵风险 + 路径不一致）

## D13. 工具域系统三修：持久化/幽灵清理/顺序（v3.8.23，用户决策）
- **持久化**：Settings 新字段必须同步 PreferencesStore 读写段——exemptFromDomainTools 缺失 key 导致重启失效（B33），补后根治
- **幽灵/僵尸域**：已删(removed)/隐藏(hidden)由 isValidDomain 过滤；空壳内置域从移动目标与域树剔除；自定义空域保留（用户显式创建，invoke_tools 需可寻址）
- **顺序**：移动目标按路径字典序（前缀聚合父域在先），不再枚举声明序杂乱
- **子域落点对照**：override 精确、关键词路径深度子域优先——无移动后回落根域路径；落根域仅发生在手动移到父域或目标失效回落（设计行为）

## D10. 压缩机制：单留存 vs 多留存位点（v3.8.14 重做，用户决策）
- **旧方案（v3.8.13 前）**：compressedContext 单值——只留最近一次压缩的原文，撤销=整体还原
- **问题**：多次压缩后只能撤销最后一次；恢复困难、容易出各种状态 bug
- **新方案（v3.8.14 定稿）**：compressRetentions 列表（最多 3 个，最新在前，超出覆盖最旧）
  - 每个位点：时间戳（年/月/日 时:分 星期几）+ savedMessageNodes（原文，恢复用）+ summaryMessageNodes（摘要，查看用）
  - 恢复索引 k：还原位点 k 的原文，k 之后（更新）的位点级联撤销，更早的保留
  - compressedContext 旧字段保留兼容，读取时惰性迁移（migrateLegacyCompress）
- **UI**：更多页"上下文压缩管理"按钮 → 弹窗列出最近 1~3 个位点（条状窄 UI，每项查看/恢复）

## D11. OpenCode 中转流式优化（v3.8.3-8.8，用户实测迭代）
- **问题**：首字延迟久（中转静默期无限挂起）+ 输出一节一节顿挫 + 输出中途静默中断
- **决策链**：
  1. UI 节流 100→50ms（v3.8.3）→ 5ms（v3.8.6 用户指令，实测掉帧式顿挫）→ 回 50ms（v3.8.7）——50ms 为终值
  2. ClaudeProvider 补 watchdog（v3.8.3）：opencode.ai 首包 120s/流中 180s，其他 60s/120s；无 message_stop 的连接关闭视为断流（v3.8.5，Anthropic 协议 message_stop 为强制收尾）
  3. 断流重试 5→7 次、5 秒内完成（v3.8.8）：前 3 次指数 200/400/800ms，第 4 次起固定 900ms x4，总 5000ms（旧线性 15s 太慢）
- **教训**：节流不是越小越流畅——低于渲染帧率阈值（~16ms）时重组请求堆积导致掉帧式顿挫

## D12. 输出完成抽动：animateContentSize 条件化 vs 参数化（v3.8.12，对照原版）
- **旧方案（v3.7.x）**：`if (loading) 无动画 else animateContentSize`——流式防抖
- **问题**：loading 翻转瞬间修饰符链变化 → 强制重组合 + 动画从无到有 → 完成消息 Markdown 重渲染 + 高度动画 → 页面抽动
- **新方案（v3.8.12 定稿）**：always animateContentSize（对齐原版），动画速度参数化——流式 TweenSpec(0) 瞬跳（保留防抖），完成 SpringSpec
- **教训**：tween()/spring() 是 @Composable 函数，不能用于顶层属性初始化——用 TweenSpec/SpringSpec 类构造

## D9. 插件 MCP 桥接：自动注册 vs 手动 mcp_connect（v3.6.120 回滚，用户决策）

- 方案 A（v3.6.112 引入，已回滚）：插件 .mcp.json 的 command 由客户端自动经 workspace STDIO 注册（registerPluginBridges）
- 方案 B（v3.6.120 定稿）：插件 MCP 服务器退回手动 mcp_connect，与普通 STDIO MCP 同通道
- 回滚原因：自动注册破坏既有 MCP 连接（用户实测大部分 MCP 无法连接），且每次启动随机 id 新增条目导致 settings 增长
- 结论：客户端不做任何启动路径的自动 MCP 注册。插件声明的 MCP 由用户显式连接，桥接能力复用 STDIO viaWorkspace 通道即可

## D6. 写入与展示解耦（v3.5.18，用户决策）
- **背景**：workspace_write_file/edit_file 执行后自动显示在对话下附胶囊窗，完全不可控
- **决策**：新增 workspace_show_file 工具，胶囊窗仅认 show 工具；写入/编辑不再触发显示
- **语义**：show 工具校验文件存在（rootfsFileSize 抛异常即失败），成功则 Tool part 留在消息中，EditedFilesList 提取 path 渲染胶囊
- **缓存代价**：请求体 tools 数组 + system 提示（WorkspaceReminderTransformer）变化 → 单次缓存前缀重建，新前缀稳定后恢复。与 3.5.16 反复改动不同，此为一次性
- **风险**：模型可能忘记显式调用 show 导致文件不显示，靠工具描述引导

## D7. 工具加载机制取消 + 缓存定值化（v3.5.18，用户决策）
- **背景**：模型被引导"invoke_tools 加载域→工具可用"，跨轮需重新加载浪费轮次；loadedDomains 动态注入 tools 数组导致缓存阶梯化
- **决策**：
  1. tools 数组定值化 — loadedDomains 动态注入 → 全量静态注入（配置决定，跨请求逐字节一致）
  2. MCP 工具声明静态化 — Error 状态不删工具，调用时明确报错
  3. 加载引导文本全部移除 — 所有工具直接可用，无需加载
- **效果**：缓存前缀只随 messages 线性增长；模型直接调用任意工具
- **行为变化**：失败 MCP 服务器工具保留在列表（可见但调用报错）；skill 通过 skill_<name> 直接可用

## D8. Skill 直接使用 + 域反查（v3.5.18，用户决策）
- **Skill**：每个已启用 skill 生成 skill_<清洗名> 独立工具（描述取自 frontmatter），模型 tools 数组直接可见，无需 invoke_tools→use_skill 两步。use_skill 保留兼容
- **search_domains**：按关键词/标签反查域位置（名称/触发描述/触发条件），支持 mcp/skill 类别过滤，无返回上限

## D7 修正. 工具注入方向（v3.5.20，用户决策，铁律·严厉警告）
- **铁律（用户极度严厉）**：工具分层注入是底层逻辑，禁止任何形式的全量加载——包括 skill_* 直注 tools 数组。永远不许再试
- **教训链**：
  - 3.5.18-beta2 全量注入 264 工具 → 请求体 13→264 → 冷启动 100K+（严重回归，用户严厉批评）
  - 3.5.19 skill 直注（enabled skills 全量进 tools）→ 冷启动 36K（再次违规，用户极度严厉批评）
- **正确组合**：
  - 分层动态注入：框架工具 + invoke_tools + 已加载域（loadedDomains）→ 冷启动最小
  - MCP 声明静态化：Error 状态不删工具 → 工具池由配置决定 → layer1 数量统计可安全注入 system
  - skill 分层：skill_<name> 工具经 invoke_tools("技能") 加载后直接可用（无 use_skill 两步），不直注
  - 数量注入：layer1 告知模型工具池总数 + 域分布（静态）
- **缓存 6K 断点**：system 的 volatile 部分（memory 变化）或 tools 变化（工具轮加载新域，分层固有）；memory 已限 30 条；tools 变化是分层铁律的固有代价（模型加载新域一次性断，加载后保持）
- **memory 限制**：注入上限 30 条（v3.5.20），超出由 memory_tool 按需读取

## D7 终版. 缓存机制回滚 3.5.17（v3.5.24，用户最终决策）
- **决策**：缓存机制与消息注入彻底回滚到 3.5.17（520b4cb0）——三个文件对齐（WorkspaceReminderTransformer/McpManager/GenerationPrompts）
- **背景**：3.5.18 起缓存阶梯化反复出现，3.5.18-beta2 全量注入/3.5.19 skill 直注/3.5.22 layer1 数量统计均为错误方向（用户严厉批评）
- **教训**：3.5.17 是缓存稳定基准；任何缓存/注入机制改动前必读 bug-record"缓存反复被改坏的经验"与 decisions D2
- **保留**：功能改动（show/search_domains/skill 直用/UI/激活路径）不受影响

## D7 终版修正. MCP 静态化单独恢复（v3.5.25）
- **背景**：v3.5.24 回滚 3.5.17 后缓存仍不稳——Error 过滤（3.5.17 行为）在用户环境（数百 MCP 工具 + 连接波动频繁）必断缓存
- **决策**：单独恢复 MCP 工具声明静态化——仅移除 getAllAvailableTools 的 Error 过滤（配置决定），callTool 显式报错；不带全量注入/skill 直注/layer1 数量统计（3.5.18 系列错误改动）
- **结果**：tools 数组完全静态（框架 + 已加载域 + 配置决定的 MCP），服务器波动不影响请求体前缀
- **与 D7 原版区别**：v3.5.24 是整体回滚（含正确的 Error 过滤移除被一并回滚）；本版精准恢复正确的部分

## D1. 传输层回滚到 3.2.2（v3.5.0，用户决策）
- **背景**：DeepSeek V4 Flash 连接中断反复出现（SETTINGS/reasoning/空流），v3.4.5-v3.4.10 连续补丁无法根治，用户判定"补丁式修改已没救"
- **决策**：传输层整体 checkout 3.2.2（原版 RikkaHub 2.4.5 移植前基线）
- **回滚文件**：OpenAIProvider/ChatCompletionsAPI/ResponseAPI/Message.kt/GenerationHandler/PlaceholderTransformer/PromptInjectionTransformer/ChatService
- **保留**：流式落盘（v3.4.6）、FGS 类型（v3.4.4）、AlarmScheduler DI（v3.4.2）、保活/权限/闹钟/工作流、MCP/域分类/工具路由
- **教训**：补丁累积会偏离原始架构——当问题反复出现时，回滚到已知稳定基线比继续打补丁更高效
- **代价**：3.2.2 时代工具池小，全量注入无感；现在 264 工具暴露 70K 注入问题（v3.5.1 修复）

## D2. 缓存策略（DeepSeek 前缀单元制）
- **机制**：每个缓存前缀是独立完整单元；后续请求必须完全匹配某单元；单元产生于请求边界/公共前缀检测/固定 token 间隔
- **推论**：system 是公共前缀始终命中（断后掉到 ~9.7K）；60K 附近跨单元边界会断；跨边界后 2-3 轮恢复
- **决策**：system 完全静态化（v3.3.6 移除 use_skill 动态注入）——动态注入会破坏公共前缀
- **矛盾记录**：v2.9.5"注入隔离"（BEFORE_SYSTEM_PROMPT 独立消息）为了缓存前缀稳定，却破坏协议（首条非 system）——缓存优化不能牺牲协议正确性
- **缓存门槛**（千问）：隐式缓存自动开启；Qwen3.7 ~2000 tokens 门槛；命中率非 100%；首次无缓存——非客户端可控
- **v3.5.11 结论（2026-08-04）**：移植原版 SystemPromptBuilder（stable/volatile 分区）后缓存正常（用户确认）。跨步不稳定（卡 9.7K→突跳 80K→线性）确认是 DeepSeek 服务端磁盘缓存机制（官方文档：构建延迟秒级 + 固定 token 间隔切分 + 滑动窗口独立单元），客户端不可控——停止优化（用户指示"优化不了就不要改了"）。诊断日志保留：msg_fp 指纹 + cache: prompt/cached

## D3. 注入隔离 vs 协议正确性（v2.9.5 → v3.4.5 → v3.5.0）
- **v2.9.5 决策**：BEFORE_SYSTEM_PROMPT 作为独立 user 消息（注入隔离，最大化前缀命中）——结果：首条非 system，严格端点报 SETTINGS
- **v3.4.5 修正**：合并进 system 开头（与既有测试期望一致）——协议正确优先
- **v3.5.0 回滚**：3.2.2 基线（合并语义）——协议正确性 > 缓存边际收益
- **结论**：协议合规（首条 system）是硬约束，缓存优化只能在硬约束内做

## D4. 闹钟功能去向（v3.4.3 用户决策）
- **选项**：默认开启 / 藏 UI / AI 工具管理
- **决策**：定时任务 AlarmManager 双通道默认开启（无需 UI 操作）；设备闹钟由 AI 工具管理（alarm_create/list/delete——fork 移植）；UI 入口移到"数据与存储"（不扎眼）
- **理由**：闹钟是用户意图驱动的（无人创建就是空表，"默认"无意义）；AI 管理符合助手定位；用户嫌设置页乱

## D5. 消息序列清洗策略（v3.3.13 → v3.5.0）
- **v3.3.13**：清洗移到循环内每步 + 移除所有未配对 tool_call（不只是末尾）——修复 SETTINGS 报错（孤儿 tool_call 残留）
- **v3.5.0 回滚**：3.2.2 基线（清洗在入口一次）——回滚后孤儿 tool_call 处理回到旧逻辑；若再遇 SETTINGS 需评估是否重移植
- **教训**：v3.3.13 的清洗强化本身合理（保留文本与已配对 tool_call），但作为补丁链一部分被整体回滚——重移植时只取验证过的部分

## D6. 工作流移植（阶段 3 未完成）
- **现状**：纯逻辑文件已搬运（DB/模型/动作定义）；Workflow.kt 裁剪（去 Capability 依赖）；TriggerRegistry 裁剪为 4 核心 family（Time/Manual/Boot/Battery）；WorkflowBootDispatcher 在 BootTrigger.kt 内
- **未完成**：WorkflowEngine 执行层重写（对齐 ToolExecutionContext/ToolCallOrigin）、UI 适配、MCP 工具集成、导航注册与设置入口
- **关键发现**：已有 DirectModeActionRunner（CronJobWorker 使用）→ 工作流动作可直接复用，执行层零适配；AgentRunKind.Workflow 已有

## D7. 保活链路设计（阶段 1）
- **核心结论**：定时任务不依赖进程存活——AlarmManager PendingIntent 注册在系统进程，app 被杀后到点系统重新拉起进程执行
- **链路**：系统闹钟唤醒（被杀也能触发）→ 前台服务执行（dataSync 类型，执行中不被打断）→ 结果落库
- **限制**：Android 系统层面"强制停止"后任何机制无法唤醒（需诚实说明）；澎湃 OS 墓碑/速冻不影响 AlarmManager 唤醒

## D8. 前台服务类型选择（v3.4.4）
- **选项**：dataSync vs specialUse
- **决策**：dataSync（ForegroundInfo 指定 + Manifest 覆盖声明 + 权限）——specialUse 需要 manifest property 且语义不符；dataSync 对定时任务执行语义匹配（6 小时限制对 1-2 分钟执行无影响）

## 决策原则（沉淀）
1. 协议合规 > 缓存收益 > 代码优雅
2. 用户决策优先（回滚/UI 取舍听用户的）
3. 补丁累积到反复失败时，回滚到稳定基线重来
4. 改代码先看既有测试期望（v2.9.5 偏离测试的教训）
5. 平台行为（缓存命中率/空流/清理机制）不可修复时，文档化并调整预期

## D5. limitContext 滞回策略 ↔ 缓存（v3.3.0 → v3.3.5 回滚）
- **v3.3.0 引入**：适配移植 RikkaHub 2.4.5 的 limitContext 滞回策略（限制历史消息数 + 缓存更新方向）
- **后果**：**整个缓存机制报废**（滞回策略与 DeepSeek 前缀缓存冲突）
- **v3.3.5 回滚**：回滚 2.4.5 limitContext 缓存更新方向；v3.3.12 确认（缓存逻辑整体回滚到 2.4.5 移植前）
- **现状**：limitContext 函数仍在 Message.kt 但**未启用**（Assistant 无 contextMessageSize 字段，GenerationHandler 不调用）
- **铁律**：**勿重新启用 limitContext**——历史上启用即破坏缓存前缀（2026-08-05 我再次"发现"其缺失并差点移植，靠版本控制补全拦下——这正是版本记录不完整的代价）
