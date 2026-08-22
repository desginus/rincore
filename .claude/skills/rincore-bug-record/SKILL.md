---
name: rincore-bug-record
description: "[高优先级·RinCore Bug对照] RinCore 历史 Bug 完整记录：根因/修复/验证路径。触发词：Bug、崩溃、闪退、报错、中断、修复、异常、日志分析。遇到任何 RinCore 运行问题（崩溃/中断/缓存/权限/保活）先查本 Skill 对照历史根因——避免重复诊断已修复问题。不涉及：版本历史（用 rincore-changelog）、方案决策（用 rincore-decisions）。"
---

# RinCore Bug 记录（全部历史）

## F 系列（早期）
| ID | 问题 | 根因 | 状态 |
|:--:|------|------|:----:|
| F1 | 动态 MCP 工具不注入调用列表 | McpManager 未同步到工具池 | ✅ McpManager.sync() + getMcpTools() + GenerationHandler 每步注入 |
| F2 | workspace_shell 进程无法持久化 | proot 沙箱机制 | ❌ 代码不可修复（环境限制） |
| F3 | plugin_install 提取为空 | 解析器只查固定路径 | ✅ ClaudePluginParser 全局搜索 + 嵌套 skills |
| F4 | mcp_connect stdio 不启动进程 | SDK 限制 | ❌ 走 workspace_shell + streamable_http |

## 已修复 Bug 明细（按时间倒序）

### B41. token 统计虚高与缓存越界（v3.8.43 修复）
- **现象**：会话 token 统计与实际严重不符，出现共计十几 K 缓存六十几 K 的荒谬组合
- **根因**：自研 TokenBudgetTracker 把每条消息 usage.promptTokens（该轮完整上下文）逐条求和致数倍虚高；cached 无钳制，中转将历史累计命中打包显示越界
- **修复**：aggregate 改为 input=最近轮 prompt，output=全部 completion 累计；cachedTokens=min(cached, prompt)
- **验证**：会话统计显示当前上下文真实大小，cached 永不越界

### B40. 思考链判成正文（v3.8.42 修复）
- **现象**：ox-alpha-free 思考链出现在正文区（v3.8.40 无条件提升 reasoning_content 副作用）
- **修复**：运行时自适应 — 流中思考保持思考链，仅流结束无 content 时缓冲正文化补发
- **经验**：按模型名一刀切必出副作用，运行期按实际流内容自适应才是终态

### B39. MCP 启动即主动连接服务器（v3.8.41 修复）
- **现象**：每次重启应用 MCP 都尝试连接非正常网络地址（用户强制：启动不得发起网络请求）
- **修复**：懒连接 — 启动只登记 pendingConfigs，首次工具调用才 addClient；工具声明已静态化不受影响
- **验证**：重启无任何 MCP 网络请求；日志 callTool: lazy-connecting 后才建连

### B38. OpenCode Zen 完成信号缺失被误判为断流（v3.8.31-33 修复链）
- **现象**：Zen 网关对 ox 系模型完成时不发 [DONE]/finish_reason，直接关闭连接，重试轰炸
- **修复链**：v3.8.31 已收数据即完成 → v3.8.32 模型名单分流 → v3.8.33 SSE 物理判据（最后一行 JSON 完整性）= 最终形态
- **v3.8.36 补充**：内容级截断检测（tail 形态启发）解决"行完整但内容被裁"
- **v3.8.42 补充**：正文/思考运行时自适应（见 B40）

### F 系列（早期）


### B34. 压缩留存位点重启全消失（v3.8.22 根治）
- **现象**：压缩功能正常，重启 App 后 compressRetentions 全部为空
- **根因**：conversationToConversationEntity 未映射 compressRetentions，ConversationEntity 无对应列——位点写入即丢，重启读空
- **修复**：ConversationEntity 加 compress_retentions 列；Room v27→v28 Migration_27_28 (ALTER TABLE ADD COLUMN)；写入 JSON 序列化、读取 runCatching 解码回退空；存量数据无损

### B37. MCP 图表工具 -32602 Invalid parameters（v3.8.29 根治）
- **现象**：mcp__charting__* 调用返回 -32602，data/style/width/height 类型全错（array→string 等）
- **根因**：模型对深嵌套 schema 生成 JSON 字符串字面量（data 为 "[{...}]" 字符串），服务端类型校验失败；首调成功属模型生成偶然正确
- **修复**：McpManager.callTool 按 inputSchema 递归类型恢复（array/object 解析回结构化、number/boolean 转原生），schema 缺失不污染
- **教训**：客户端到 MCP 的参数必须按 schema 类型清洗，不能盲信模型生成的参数类型

### B36. Skill 跳脱 invoke_tools 暴露请求顶层（v3.8.27 加固）
- **现象**：数个 Skill 工具未经 invoke_tools 加载即出现在请求 tools 数组顶层（框架工具之外）
- **风险评估**：分层构建逻辑本身干净（skill 仅经 loadedDomains 技能域注入）；泄漏多来自对话 loadedDomains 持久化或非分层兜底
- **加固**：toolsInternal 构建后白名单硬过滤（框架+豁免+引擎+已加载域），泄漏工具剔除并 Log.e，回归自曝
- **规则（用户）**：请求顶层只允许批准框架 + 豁免 + 已加载域工具，其余一律归 invoke_tools 内部

### B35. 管理子域页面所有子域显示 0 个工具（v3.8.25 根治）
- **现象**：工具域分类管理→点根域→设置→管理子域，所有子域显示 0 个工具
- **根因**：管理子域对话框自拼子域列表：customSubs 取 CustomDomain.name（短名"引擎"）而非 normalizedFullPath（完整路径"搜索/引擎"），unifiedView.classified 的 key 是完整路径 → 查表落空 → 0；自定义子域删除按 it.name 匹配与完整路径不符 → 删除无效
- **修复**：allSubs 改用 unifiedView.tree[parentDomain] 统一信息源头；isCustom 判断与删除匹配改 normalizedFullPath
- **教训**：同源铁律——任何以"域"为单位的展示/操作必须用 normalizedFullPath/统一视图，不得用 name 短名自拼

### B33. 移出域管理重启失效（v3.8.23 根治）
- **现象**：工具设置里开启"移出域管理"（exemptFromDomainTools），重启后该操作完全失效（工具重新并入域分类）
- **根因**：Settings 数据类有 exemptFromDomainTools 字段，但 PreferencesStore 无对应 PreferencesKey——读段取默认空集、写段不落盘，仅存活于当次运行内存
- **修复**：补 EXEMPT_FROM_DOMAIN_TOOLS key + 读写段（与 v3.8.2 密钥持久化同类根因）
- **教训**：Settings 加新字段必须同步 PreferencesStore 读写段——遗漏即"重启消失"类 bug

### B32. 液态玻璃分享后失效成黑框（v3.8.9 根治）
- **现象**：分享消息后返回软件，液态玻璃模糊丢失变普通黑框；进设置再返回恢复
- **根因**：Haze 模糊纹理依赖背景渲染，分享面板是外部 Activity，返回不触发任何重组，模糊纹理失效
- **修复**：ChatPage ON_RESUME 递增 hazeRebuildTick，key 包裹 AssistantBackground 强制重建纹理
- **教训**：外部 Activity 返回 ≠ 导航返回——系统 UI 覆盖不会触发 Compose 重组

### B31. Anthropic 接口输出中途静默中断（v3.8.5 根治）
- **现象**：千问 3.7 Plus（OpenCode 中转 /v1/messages）输出中途莫名中断，客户端日志显示正常完成（FINISH/no_tools，无异常无重试），半截回复被保存
- **根因**：ClaudeProvider 把连接关闭一律当正常结束（onClosed=close()）。Anthropic 协议 message_stop 是唯一强制收尾，中转断流/网关切换直接关连接（不发 message_stop）→ 客户端保存半截
- **修复**：completed 标记——message_stop 到达才视为完成；onClosed 无 message_stop → close(IOException) → 既有断流重试链路（回滚+重试）
- **依据**：对照 ChatCompletionsAPI 的 completed/gotFinish（v3.6.75 双向教训）；Anthropic 协议 message_stop 为强制（与 OpenAI 的 [DONE] 约定强度不同）

### B30. 输出完成瞬间整条消息抽动（v3.8.12 根治，v3.7.x 引入）
- **现象**：消息输出完成时整条消息 Markdown 重渲染一遍，页面上下抽动
- **根因**：v3.7.x 把 animateContentSize 条件化（loading 时无动画）——loading 翻转瞬间修饰符链变化 → 强制重组合 + 动画从无到有 → 重渲染 + 高度动画
- **修复**：改回原版 always animateContentSize，动画速度参数化（流式 TweenSpec(0) 瞬跳 / 完成 SpringSpec）
- **教训**：修饰符链的变化会强制整棵子树重组合——条件化 Modifier 比想象中重

### B24. MCP 大部分无法连接（127.x 无法连接/连接关闭，v3.6.120 回滚根治）

- 现象：v3.6.112-119 期间大部分 MCP 报"无法连接到 127.x:端口"、"连接关闭"
- 根因：v3.6.112 引入插件自动桥接 registerPluginBridges，App 启动时对每个插件 command 自动 addClient（viaWorkspace STDIO），部分环境破坏 MCP 连接状态；且每次启动用随机 id 新增 settings 条目持续增长
- 修复：v3.6.120 移除两个调用点；v3.6.121 启动时清理 plugin__ 残留服务器与白名单条目
- 教训：新增自动 MCP 注册机制必须评估对既有连接的影响面，禁止在启动路径批量 addClient

### B23. 插件列表恒空（installFromParsed 死代码，v3.6.118 根治）

- 现象：plugin_install 报安装成功，设置页插件列表永远空
- 根因：写 plugin.json 元数据的 ClawPluginRegistry.installFromParsed 从未被任何调用点调用（死代码），refresh() 的 readManifest 恒失败
- 修复：v3.6.118 安装流显式调 installFromParsed
- 教训：修复"写元数据"类 bug 时必须验证调用链存在，静态校验脚本加"方法被调用点存在性"检查

### B22. unexpected end of stream（v3.5.17 根治）
- **现象**：工具执行 60s+ 后继续生成的请求报 java.io.IOException unexpected end of stream（Http1ExchangeCodec.readResponseHeaders，Caused by EOFException \n not found: limit=0）
- **根因**：连接池复用陈旧连接——服务端空闲关闭连接后客户端 keepalive 5min 仍保留，复用即 EOF；工具执行 60s+ 使连接空闲超服务端关闭时间，必触发
- **修复**：ConnectionPool(12, 60s) keepalive 低于服务端空闲关闭时间；writeTimeout 120s 对齐 v2.9.8；SSE 重试 3→5 次（31s 窗口）
- **对比**：v2.9.8 稳定连接配置 writeTimeout 120s / ConnectionPool(12,10min) / pingInterval 30s，v3.1.0 改动三处

### B21. stream was reset: PROTOCOL_ERROR（v3.5.17 根治）
- **现象**：流式生成报 okhttp3.internal.http2.StreamResetException: stream was reset: PROTOCOL_ERROR（ALPN 协商 h2 后）
- **根因**：DeepSeek 服务端 HTTP/2 连接异常。protocols(HTTP_1_1, HTTP_2) 顺序不影响 ALPN——服务端支持 h2 必选 h2
- **修复**：protocols 只留 HTTP_1_1，完全禁用 HTTP/2
- **验证证据**：2026-08-05 20:06:23 Trace c003249a 堆栈 Http2Stream$FramingSource.read

### B20. 思考链计时持续 / 灵动岛不停（v3.5.17 根治）
- **现象**：对话中断后思考链持续显示思考秒数，灵动岛一直显示思考中（近几版出现）
- **根因**：停止生成 job.cancel() 后 onCompletion 在取消态执行，挂起调用（saveConversation/appEventBus.emit）直接跳过——ChatGenerationEnded 未发出灵动岛不取消，落盘未执行
- **修复**：onCompletion 收尾包 withContext(NonCancellable)；stopGeneration 显式 tryEmit ChatGenerationEnded
- **注意**：ChatMessageReasoning 计时实时累计依赖 finishedAt 被收尾设置

### G3. 平台空流（v3.5.17 实现重试）
- **现象**：流式正常结束但模型未产出任何内容（无文本/无思考/无工具调用）
- **实现**：GenerationHandler 空响应检测 + 重试一次（emptyRetryCount < 1）
- **判定**：assistant 消息 parts 无 Text/Reasoning/Tool；工具轮后 user 消息不触发；thinking-only 不算空流
- **缓存**：重试请求消息相同，缓存命中无破坏
- **传输层缺口现状**：G1（BEFORE_SYSTEM_PROMPT 已合并 system）、G2（孤立 tool_call 已有清洗）、G3（本项）、G4（msg_fp 已有）——全部关闭

### B18. 流式中断静默恢复（根因版本 v3.1.0 — 已根治 2026-08-05）
- **现象**：工具轮后请求返回空/回复缺失，无任何报错，用户感知莫名中断；运行日志 SEND→RECV 正常、FINISH 正常、messages 无新增
- **根因链**：
  - v3.1.0 (583a38c1) 在三个 Provider（ChatCompletionsAPI/Claude/Google）同时引入静默恢复：onFailure 时若 hasData=true 直接 close() 结束，中断被吞
  - v2.9.8 (c0280099) 有正确机制：未收到任何数据时自动重试（指数退避 3 次），收到数据后中断才传播异常
  - v3.5.0 回滚到 3.2.2 基线时丢失 v2.9.8 重试；3.5.14 又重复引入静默恢复（hasData→close），问题复现且更隐蔽
- **修复**：ChatCompletionsAPI 移植 v2.9.8 完整重试机制（hasReceivedData/retryCount/maxRetries=3/currentEventSource/connect()）；三 Provider 静默恢复全部移除，中断统一传播异常可见
- **铁律**：连接层改动的正确参照是 v2.9.8（自己 2.x 稳定版），不是原版 fork-ref；禁止任何"静默吞错"逻辑；中断必须可见或自动重试
- **对比法**：查问题首次引入版本 = git log 连接相关关键词 → 对比该版本与上一版本

### B19. MCP 状态撕裂 — no such mcp client（2026-08-05 修复）
- **现象**：分层调用工具时报 Failed to execute tool, because no such mcp client for the tool
- **根因**：addClient 中 getTransport 在 runCatching 外——stdio 分支 check(command 非空)/ProcessBuilder.start() 失败时，removeClient 已执行但新 client 未注册，clients 缺失；而配置里 commonOptions.tools 仍持久化，getAllAvailableTools 显示工具 → 调用撕裂
- **修复**：getTransport 包 runCatching（失败 setStatus Error 并明确显示原因）；getAllAvailableTools 过滤 Error 状态服务器；callTool 报错明确化（区分未连接/不存在）
- **教训**：任何资源创建（进程/连接）必须在错误处理内，失败要状态一致；配置持久化的工具可见性必须与连接状态联动

### 缓存反复被改坏的经验（2026-08-05 三次教训）
- **事实**：缓存键 = 请求体前缀（system + 早期消息 + tools 数组）；请求体任何变化都导致缓存失效
- **已犯错误**：3.5.16 把 use_skill 加入框架工具集（tools 数组变化）+ 新增 UNCLASSIFIED 域（layer1 域概览变化）→ 缓存率暴跌
- **铁律**：请求体零改动原则——任何想改 system 提示/tools 数组/域概览/消息结构的改动，必须先评估缓存影响；缓存优先于功能优化；P1-2/P3-1 类优化需以不影响请求体的方式实现
- **正确参照**：3.5.11（SystemPromptBuilder stable/volatile 分区）是缓存正常化的基准版本

### B23. 缓存阶梯化反复出现 — 最终决策回滚 3.5.17（v3.5.24）
- **现象链**：3.5.18 起缓存阶梯化（10K 卡住→跳 20K→倒退 3K）、冷启动 100K/36K 反复
- **错误尝试**：3.5.18-beta2 全量注入（100K 回归）→ 3.5.19 skill 直注（36K）→ 3.5.22 layer1 数量统计（用户批评"不是服务端机制"）
- **用户决策**：缓存机制彻底回滚到 3.5.17（520b4cb0）——WorkspaceReminderTransformer/McpManager/GenerationPrompts 对齐；功能改动保留
- **教训**：3.5.17 是缓存稳定基准，任何缓存机制性改动必须先对照 bug-record"缓存反复被改坏的经验"（请求体零改动原则）

### B23 修正. 缓存阶梯化最终根因确认（v3.5.25）
- **根因确认**：MCP 服务器连接波动 → Error → getAllAvailableTools 过滤（3.5.17 行为）→ 工具从数组消失 → tools 数组每轮变化 → 请求体前缀断裂 → 缓存阶梯化/倒退。用户环境 MCP 工具多（数百）且波动频繁，此机制必断缓存——非平台正常现象
- **v3.5.24 回滚 3.5.17 后问题仍在**（Error 过滤是 3.5.17 固有行为），确认此根因
- **修复**：单独恢复静态化——仅移除 Error 过滤（工具声明由配置决定），callTool 调用时显式报错。不带全量注入/skill 直注/数量统计等 3.5.18 错误改动
- **教训**：3.5.18-beta2 的静态化方向正确但被错误改动拖累；回滚要精准，不能连带回滚正确的修复

### B29. SSE 静默中断（v3.5.38 修复）— 消息莫名其妙中断
- **根因**：SSE onClosed 无条件 close() — 服务器未发 [DONE] 直接关连接被当正常结束 → 消息不完整且无报错（静默中断）
- **修复**：completed 标记 — onClosed 未收到 [DONE] → close(IOException) 可见化（对齐 B18 可见化原则）
- **教训**：流结束必须校验终止信号（[DONE]），不能只依赖连接关闭事件

### B28. 技能子域体系六处断裂（v3.5.34 稳定版梳理）
- **override 校验**：validDomainLabels 不含技能子域（动态）→ 挂载到"技能/名"失效 → root 有效即放行
- **UI 域树**：buildNestedDomains 只遍历枚举+customDomains → 技能子域管理页不可见 → 从分类结果派生
- **move 目标**：allValid 不含技能子域 → 无法移动到技能/<名> → 补 knownSkillNames 派生
- **子域删除**：buildDomainTree 无条件重建技能子域 → 删除无效 → 过滤 removed/hidden
- **分类一致性**：classifyByName 不查子域删除 → 删除后仍归"技能/名"（与域树错位）→ 归技能根域
- **挂载键**：skill__名/skill:名/原始名 三套 key 混用 → move 规范化统一 skill:原始名
- **教训**：动态域（技能/<名>）必须全链路一致——分类/域树/UI/移动/删除 同源校验

### B27. 工具域分类体系重构（v3.5.26）
- **问题**：AI 分类调模型不稳定；Skill/MCP 层级不对齐（skill_ 单字段 vs mcp__服务器__工具）；空壳域/残留空壳；孤儿注册数据（skill 删除后 overrides 残留）；search_domains 域路径解析失败（技能子域不在域列表）；工具池域数与域内计数不一致
- **修复**：自动分类改本地名称结构化分类（第一字段类别/第二字段分类字段）；Skill 工具 skill__ 命名 + 归「技能/<名>」；buildDomainTree(tools) 技能子域派生（与 classifyByName 同源）；空壳域过滤（UI+帮助一致）；SkillManager.deleteSkill 清 overrides 孤儿；search_domains 域列表同源
- **教训**：域分类必须单一事实源（classifyByName 与域树同源），UI/模型/帮助三处一致

### B26. 助手删除限制取消（v3.5.25）
- **改动**：DEFAULT_ASSISTANTS_IDS 限制移除，所有助手可删除；仅剩最后一个助手时禁止（避免无助手可用）

### B24. get_location 固定返回上海缓存（v3.5.24 修复）
- **现象**：FUSED 模式不触发系统定位请求，固定返回上海坐标（31.1959831, 121.4234426）
- **根因**：quick cache（<=5min）优先于真实定位，缓存命中直接返回
- **修复**：重排为真实定位优先（FusedLocation → Network → GPS），缓存仅最终兜底并标注 age

### B25. 思考链计时器中断后一直计数（v3.5.24 修复）
- **现象**：对话中断后"思考了多少秒"计时器不停
- **根因**：中断后 onCompletion 收尾（NonCancellable saveConversation 落盘）耗时期间，消息 finishedAt 未更新，UI 计时循环继续
- **修复**：stopGeneration 中断时立即 finishReasoning + 更新 flow 停表；join 3s 超时（UI 立即响应，收尾后台继续）；onCompletion finishReasoning 幂等不冲突

### B17. 冷启动注入 70K tokens（v3.5.1 修复）
- **现象**：回滚 3.2.2 后每次对话起始冷启动注入 70K+ tokens
- **根因**：GenerationHandler system 构建——`layer1Prompt` 无任何调用方传入（全项目 grep 确认），恒走 `else` 分支 `tools.forEach` 全量注入 264 工具 systemPrompt；3.2.2 时代工具池小（几十个）无感，工具池膨胀后（264 tools）暴露
- **修复**：else 分支移植 v2.9.4 瘦身逻辑——只注入 7 个框架工具（invoke_tools/workspace_*/manage_domain/list_domains/move_tool_to_domain），其余工具描述在请求 tools 数组
- **验证**：system 从 ~70K → ~10K tokens

### B16. Responses API reasoning 回传格式（v3.4.9-3.4.10）
- **现象**：DeepSeek（Responses API + thinking 模式）工具调用后中断——"The reasoning_text in the thinking mode must be passed back to the API"
- **根因**：ResponseAPI 回传历史 reasoning 用 OpenAI 标准独立 reasoning item（summary_text）——DeepSeek 要求 summary 元素类型为 reasoning_text
- **坑**：v3.4.9 曾在 content 数组加 reasoning_text 块 → 被拒 "unknown variant reasoning_text, expected input_text/output_text/input_image/input_file"（content item 类型枚举不含 reasoning_text）——正确位置是独立 reasoning item 的 summary 数组
- **修复**：summary 元素类型按 host：deepseek → reasoning_text；其他 → summary_text
- **触发链路**：启动正常（无历史）→ 思考正常（单请求内）→ 工具调用后请求携带思考轮次历史 → 回传格式错 → 中断
- **注意**：v3.5.0 已回滚传输层——此修复已移除；若 3.5.x 再遇此错误需重新移植（位置：ResponseAPI.addAssistantItems 的 summary 数组）

### B15. ChatCompletions DeepSeek reasoning 回传（v3.4.8）
- **现象**：DeepSeek 思考模式报 "reason text 必须返回给 API"
- **根因**：includeHistoryReasoning 受 provider UI 开关控制，关闭时剥离历史 reasoning
- **修复**：host.contains("deepseek") 强制 includeHistoryReasoning=true
- **审计结论**：客户端全链路（appendChunk/sanitize/buildAssistantMessageJson/DB 序列化 @SerialName("reasoning")）均保留 reasoning——唯一漏洞是开关

### B14. 千问前几轮缓存率低（v3.4.8 确认非 bug）
- **现象**：9K 测试文本无缓存、前几轮缓存率低
- **结论**（官方文档）：隐式缓存自动开启无需配置；Qwen3.7 触发门槛 ~2000 tokens；命中率非 100%（平台判定）；首次请求无缓存（创建）；隐式缓存有效期不确定（系统定期清理）
- **已做对的**：system 完全静态（公共前缀）+ 差异内容置后（user 在末尾）——符合官方最佳实践

### B13. DeepSeek V4 Flash 输出中断（v3.4.7 补丁——已随回滚移除）
- **现象**：流式中途中断，无错误；日志 SEND → RECV messages=8（无新增）——模型 19 秒返回但流无有效 chunk（平台偶发空流）
- **补丁**：空返回自动重试 + 流式诊断（SSE done/failure chunks+finish_reason、gen_return last_role+last_len）
- **注意**：v3.5.0 回滚后此补丁已移除——3.5.x 再遇中断需从 3.2.2 基线重新诊断（先看是否仍是平台空流，再决定是否重移植）

### B12. 生成中切后台回答全部消失（v3.4.6——回滚后保留）
- **现象**：模型运行中切后台，重新进入后回答全部消失（灵动岛还在显示输出但对话页丢失）
- **根因**：assistant 消息仅在生成结束（onSuccess）落盘；生成中只有内存 flow 更新；user 消息发送时已落盘（所以只丢回答）
- **修复**：GenerationChunk.Messages 分支每步 saveConversation（流式增量落盘）+ onCompletion 兜底落盘（异常/中断路径）
- **位置**：ChatService（v3.5.0 回滚后已重新应用）

### B11. Required SETTINGS preface not received（v3.4.5 根治——已随回滚移除）
- **现象**：DeepSeek V4 Flash 流式中断，报 "Required SETTINGS preface not received"；古早版本无此问题
- **根因**：v2.9.5（缓存锚点+注入隔离）把 BEFORE_SYSTEM_PROMPT 注入改为独立 user 消息插到 system 前 → 首条非 system → 严格端点拒绝
- **偶发性**：注入为触发式（lorebook 按上下文触发），搜索工具结果加入后重新 transform → 注入出现 → 首条变 user
- **修复**：BEFORE_SYSTEM_PROMPT 合并进 system 开头（无 system 时创建 system 消息）+ GenerationHandler 协议兜底（首条非 system 自动合并）
- **教训**：v2.9.5 的实现偏离了既有测试期望（测试至今仍期望合并语义）——改代码先看测试

### B10. 定时任务崩溃 FGS type=none（v3.4.4——回滚后保留）
- **现象**：InvalidForegroundServiceTypeException——"Starting FGS with type none ... targetSDK=37 has been prohibited"
- **根因**：CronJobWorker setForeground 未指定类型，WorkManager SystemForegroundService 以 type=none 启动被 Android 14+ 禁止
- **修复**：ForegroundInfo 指定 FOREGROUND_SERVICE_TYPE_DATA_SYNC + Manifest 覆盖 SystemForegroundService 声明（foregroundServiceType="dataSync" + tools:node="merge"）+ FOREGROUND_SERVICE_DATA_SYNC 权限

### B9. 闹钟页崩溃（v3.4.2——回滚后保留）
- **现象**：点击设置→闹钟闪退；NoDefinitionFoundException for type 'me.rerere.hugeicons.stroke.LanguageCircleKt'
- **根因**：AlarmScheduler 未注册 Koin DI——R8 混淆后类名显示为 LanguageCircleKt（实际是 AlarmScheduler）
- **修复**：AppModule 补 single { AlarmScheduler(get(), get()) }
- **教训**：NoDefinitionFoundException 的混淆类名 ≠ 实际类型——先查 DI 注册

### B8. 权限管理点击无反应（v3.4.2）
- **现象**：权限管理页点击不跳转/无反应
- **根因**：PermissionInventory.build 抛异常（部分 ROM AppOps 差异）崩整页 + startActivity ActivityNotFoundException 静默失败 + 低版本平台请求高版本权限无反应
- **修复**：build 整体 runCatching 兜底 + startActivity try-catch（Toast 提示）+ 运行时权限按 SDK 过滤（ACCESS_LOCAL_NETWORK 36/POST_NOTIFICATIONS 33/ACCESS_BACKGROUND_LOCATION 29 等）

### B7. DeepSeek 缓存阶段性断层（确认非 bug）
- **现象**：token 稳定上增、缓存阶段性跳台阶（60K 附近常断，断后 ~9.7K 公共前缀）
- **根因**：DeepSeek 前缀单元制——每个缓存前缀是独立完整单元，后续请求必须完全匹配；60K 附近跨固定间隔单元边界 → 单元结构重排；模型输出/工具结果长度波动导致请求边界单元切分点移动
- **结论**：非客户端 bug，是平台分块机制；跨边界后继续对话 2-3 轮应恢复全命中
- **已审计干净**：TimeReminder 基于固定消息时间戳注入；system 完全静态化（v3.3.6）；工具池请求内快照稳定

### B6. workspace 文件生成失败/路径混乱（早期）
- 命名规范：MMDDHH-简述.ext（KEEP-AI工作区）；交付区只放最终交付物

## 未解决/环境限制
- F2（workspace_shell 进程不持久）：proot 沙箱——不可修复
- F4（mcp_connect stdio）：SDK 限制——走替代方案
- 澎湃 OS"强制停止"后任何机制无法唤醒（系统限制，需诚实说明）
- 小米澎湃 3 白名单引导未自建（fork 没有——需自建：自启动 AppOps AUTO_START / 省电策略无限制 / 后台弹出界面 / ADB appops set RUN_IN_BACKGROUND allow）

### B38. OpenCode Zen 无信号关流误判断流（v3.8.31 修复）
- **现象**：ox-alpha-free（opencode.ai/zen/go/v1）SSE 在完成前被服务器关闭；rollback & retry 7 次全败，10 分钟耗尽后 generation_failed；每轮重试间隔 40-60s
- **根因**：Zen 网关对部分模型（grok 系/ox 系免费模型）完成时不发 [DONE]/finish_reason=stop/usage，直接关闭连接；onClosed 视为断流 → 上层 rollback 重试 → 每次重试服务端重新生成 → 7 次叠加超长失败。另发现独立缺陷：finish_reason 判定写在 message!=null 分支内，delta:null + finish_reason:"stop" 结尾 chunk 漏判
- **修复（v3.8.31）**：isOpencode && 已收到数据 => 关闭即正常完结，未收到数据仍按断流重试；finish_reason 上移到 choice 层
- **修正（v3.8.32）**：v3.8.31 特判过宽把服务端中途掐断也吞成完成（静默截断，用户不接受）。改为按模型分流：grok 系维持"已收数据=>完成"；ox 系等=>OpenCodeStreamUnconfirmedException 保留内容+明确报错不回滚不重试；诊断加事件数+最近 5 条原始数据缓冲
- **定稿（v3.8.33）**：分流仍误报（ox 每轮都弹错，服务端完整发完但也无完成信号）。弃用模型名单猜测，改物理判据：SSE 最后一行 JSON 解析成功=完整发完（正常完结不打扰），残缺=真断流（保留内容+报错）。对照原版不可对齐（原版只认 [DONE] 且无 Zen 适配），自行斟酌定稿。附：openCode близнец ox-alpha-free 网关行为=完整行后无信号关流
- **验证**：ox-alpha-free 不再误报 SSE 中断；DeepSeek 真断流（无数据关闭）重试路径不变
- **排查起点**：ChatCompletionsAPI.kt onClosed / onEvent finish_reason 判定
- **注意**：ResponseAPI onClosed 为宽松语义（直接 close），无此问题，无需同步

### B39. MCP 启动即主动连接服务器（v3.8.41 修复）
- **现象**：每次重启应用 MCP 都尝试连接非正常网络地址；原版无此行为（用户强制诉求，不接受启动发起网络请求）
- **根因**：McpManager.init 收集 settingsFlow 首 emit 即对所有启用服务器 addClient → 立即 getTransport + 网络连接（上游原版 reconcile 同样行为，判定不可沿用）
- **修复**：懒连接——启动/配置变更只登记 pendingConfigs，首次 MCP 工具调用（callTool）才 addClient；工具声明已静态化不受连接影响；断线重连/OAuth 刷新/mcp_connect 手动路径语义不变
- **验证**：重启应用无任何 MCP 网络请求；日志 callTool: lazy-connecting 后才建连
- **注意**：currentConfigs 对比须含 pending（List<McpServerConfig> 保持, eq 泛型参考）

### B40. 思考链判成正文（v3.8.42 修复）
- **现象**：ox-alpha-free 思考链经常出现在正文区（v3.8.40 无条件提升 reasoning_content 为正文的副作用，ox 流同时含 content 与 reasoning 时思考混入正文）
- **修复**：运行时自适应——流中 reasoning_content 保持思考链实时显示（parseMessage 原生 Reasoning part），仅流结束确认无 content 且存在思考缓冲时才整段正文化补发（对齐 opencode）
- **经验**：按模型名一刀切必出副作用；运行期按实际流内容自适应才是终态

## 排查方法论（用户约定）
1. 排查顺序：先确定相关代码 → 对照 → 理清逻辑 → 想清楚再改 → 验证
2. 无价值信息绝不允许破坏缓存
3. 修改要有全局意识，主动发现并修复用户未察觉的 bug
4. 数值计算必须用代码执行，禁止心算
5. 单次修改小步提交，CI 验证后继续
