---
name: rincore-changelog
description: "[中优先级·RinCore开发对照] RinCore 完整版本更新日志。触发词：版本历史、更新日志、changelog、这个版本改了什么、版本对比、回滚历史、版本链。任何需要了解 RinCore 某版本改动/某功能何时引入/何时回滚时加载。不涉及：Bug 根因细节（用 rincore-bug-record）、方案决策（用 rincore-decisions）。"
---

# RinCore 更新日志（v2.9.4 → v3.5.14）

## 历史教训（防重踩——每次改动前必读）
- **limitContext 滞回策略 ↔ 缓存**：v3.3.0 引入（2.4.5 适配）→ v3.3.5 回滚（**缓存机制报废**）→ v3.3.12 确认回滚。函数仍在 Message.kt 但未启用（无 contextMessageSize 字段）——**勿重新启用**，启用即破坏缓存前缀
- **缓存锚点/注入隔离**：v2.9.5 注入隔离（BEFORE_SYSTEM_PROMPT 变独立 user 消息）引入 SETTINGS 协议违规 → v3.4.5 修复——**协议合规 > 缓存边际收益**
- **DeepSeek Responses reasoning**：3.5.4~3.5.6 猜测性修复全废（服务端格式不成熟）→ 3.5.7 按官方协议（明文 content）→ 3.5.8 工具轮相邻 assistant 消息 → 3.5.9 起搁置（等官方更新）
- **工具执行无超时**：3.5.9 withTimeout 60s 兜底——工具挂起不永久阻塞生成
- **缓存"卡-跳-线性"**：DeepSeek 服务端磁盘缓存机制（构建延迟秒级+固定间隔切分+SWA 独立单元）——客户端不可控，已入库 decisions D2

## v3.5.x（传输层回滚期 → 当前）
- **v3.5.26**（ac889337，2026-08-07）：工具域分类体系重构
  - 自动分类按名称结构化（类别__分类字段），替代模型分类
  - Skill 工具 skill__ 命名 + 归「技能/<名>」子域（与 MCP 同层级）
  - 内置/自定义域对齐 + 空壳域过滤 + 孤儿数据清理 + search_domains 修复
  - 注意：工具名 skill_ → skill__ 变化 → tools 数组一次性重建缓存
- **v3.5.25**（2026-08-07）：缓存根因修复 + 助手删除放开
  - MCP 静态化恢复（仅 Error 过滤移除）：tools 数组配置决定，服务器波动不断缓存
  - 助手删除：DEFAULT_ASSISTANTS_IDS 限制取消，仅剩最后一个时禁止删除
  - 注意：请求体无变化（工具声明静态化不改变请求体内容）
- **v3.5.24**（a2b2ac5d，2026-08-06）：缓存机制回滚 3.5.17
  - WorkspaceReminderTransformer/McpManager/GenerationPrompts 对齐 520b4cb0
  - get_location 真实定位优先（缓存仅兜底）
  - 中断 3s join 超时 + 思考链立即停表
  - 注意：请求体恢复 3.5.17 状态（show 说明行移除 → system 变化，缓存单次重建）
- **v3.5.19**（8e705d16，2026-08-06）：工具数量注入
  - 回滚全量注入回归（冷启动 100K+ → 分层动态恢复）
  - layer1 注入工具池总数 + 域分布；帮助文本各域工具数
  - 分层注入定为铁律（用户决策，D7 修正）
- **v3.5.18**（7937d962，2026-08-06 正式版）：工具体系重构
  - workspace_show_file：写入与展示解耦（胶囊窗仅认 show）
  - search_domains：关键词/标签反查域位置，mcp/skill 类别过滤
  - skill_<清洗名> 独立工具：Skill 直接可用（decisions D8）
  - 工具加载机制取消：所有工具直接可用，无加载/缓存引导
  - 缓存阶梯化根治：tools 定值化 + MCP 声明静态化（decisions D7）
  - 注意：请求体/system 变化，缓存单次重建后稳定
- **v3.5.18-beta1**（9e73b04d，2026-08-06）：workspace_show_file 展示解耦
  - 胶囊窗仅认 show 工具，写入/编辑不再自动显示（用户决策，decisions D6）
  - 工具风格对齐 workspace 族：校验存在、默认免审批、入 FILE 域与框架集
  - 全链路对齐：ToolUI 渲染器/审批页/域管理页/字符串/system 提示
  - 缓存：请求体变化单次重建（已评估）
- **v3.5.17**（520b4cb0 完成，2026-08-05 连接根治系列）：整体根治中断与缓存问题
  - 缓存回滚（1784ba75）：use_skill 移出框架工具集 + UNCLASSIFIED 域移除——3.5.16 请求体改动导致缓存率暴跌，请求体恢复稳定
  - 静默恢复移除（1f9d350e/60389917）：三 Provider hasData→close 全部删除——v3.1.0 引入的中断被吞根因
  - v2.9.8 SSE 重试移植（98f5d7d6）：未收到数据自动重试 5 次指数退避——2.x 稳定机制，3.5.0 回滚丢失
  - HTTP/2 完全禁用（b27253cd）：protocols 只留 HTTP_1_1——DeepSeek ALPN 协商 h2 后 PROTOCOL_ERROR 根治
  - 连接配置对齐 v2.9.8（75c8e595）：ConnectionPool(12,60s) 防陈旧连接 EOF；writeTimeout 120s；pingInterval 30s
  - 收尾完整（f9ea683a）：onCompletion NonCancellable + stopGeneration 显式收尾——计时器/灵动岛不停根治
  - MCP 状态撕裂修复（7ecda960）：getTransport 包 runCatching + Error 状态工具过滤 + 报错明确化
  - 经验文档固化（7ecda960）：B18/B19/B20/B21/B22 + 缓存教训写入 bug-record
- **v3.5.14**（a4b73fc0 起）：连接稳定性加固 + MCP STDIO + effort 映射
  - ResponseAPI 断线恢复（对齐 ChatCompletionsAPI：stream reset/timeout 等保留部分数据）
  - SSE 无数据看门狗 120s（两个 API）——挂起快速失败，不再无限等
  - readTimeout 10min → 3min
  - DeepSeek reasoning_effort 映射（medium→high/xhigh→max；AUTO 不触发——非根因，保留无害）
  - MCP 第三种连接 STDIO（getTransport 实现 + UI 三并列 + command 拆分 + 进程生命周期）
  - 生成错误上下文日志（CallTracer ERROR/generation_failed + baseUrl/msgs/tools/thinking）
  - 补: CallTracer 移入 launch + 级联清理移到 onEdit + baseUrl 类型修复 (编译修复)
- **v3.5.15**（1de789a4 起）：MCP STDIO 导入支持 + HTTP/1.1 优先
  - parseMcpServersFromJson 补 stdio 分支（command 必需 + args 可选；原只认 url → stdio 配置被丢弃报"没有找到正确的 MCP 配置"）
  - HTTP/1.1 优先（protocols HTTP_2,HTTP_1_1 → HTTP_1_1,HTTP_2）：v3.1.0 引入的 HTTP/2 优先在弱网下 SETTINGS 帧丢失/超时 → DeepSeek 服务端报 'required settings preferences not received'（原版默认 HTTP/1.1 优先无此问题）【SETTINGS 帧根因修复】
- **v3.5.16**（cd51a2fa 起）：缺陷报告 v5 全部 7 项
  - P0-1 根因: 子请求（标题/建议/背景文本/工具分类）首条 user 无 system → DeepSeek 报 'Required SETTINGS preface not received'（主请求有 MessageProtocol 保证, 子请求漏了）→ OpenAIProvider.generateText 统一兜底前置空 system；Trace ID 从 model.id 改随机 UUID；CallTracer 计数修正（trace_end 实参先于 add 求值）
  - P0-2 错误弹窗细化（ErrorCard 点击展开完整详情/复制完整堆栈）
  - P1-1 MCP 状态文本（Idle 引导/Error 消息/Connected 工具列表）
  - P1-2 UNCLASSIFIED('未分类') 显式兜底域（原'方法域'兜底）
  - P2-1 思考链计时兜底（finishedAt null 不再持续累计）
  - P2-2 list_domains 移除'已删除域'残留行
  - P3-1 use_skill 入框架工具集（懒加载直接可用）
- **v3.5.13**（fa61f6ca）：删除模型级联清理引用（设置项 9 字段/收藏/助手绑定）
- **v3.5.12**（bcec24a0）：热力图/统计页崩溃——json_each 展开损坏 JSON（json_valid 过滤 + VM 兜底）
- **v3.5.11**（fbd5e11e）：移植原版 SystemPromptBuilder（stable/volatile 分区）——缓存前缀稳定【缓存正常化关键版本】
- **v3.5.10**（772d00e5）：G4 缓存诊断增强——msg_fp 消息指纹（跨请求对比定位缓存断点）
- **v3.5.9**（e22e7fc9）：工具执行超时兜底（withTimeout 60s）+ reasoning 发送诊断日志
- **v3.5.8**（690f9d7f）：DeepSeek 工具轮 reasoning 相邻 assistant 消息（Responses API）
- **v3.5.7**（8b36dca3+df796efb）：DeepSeek reasoning 回传按官方协议重写（明文 content 替代 summary）——核心修复
- **v3.5.4 ~ v3.5.6**：**废弃**（reasoning 猜测性修复全部无效）——d36eac68 回滚到 3.5.3 纯基线
- **v3.5.3**（67701acf/c46a9607）：70K 根治——MCP 懒加载移植（冷启动 65K→~6K）【干净基线】
- **v3.5.2**（05fc3485/e36562aa）：系统梳理——toolsInternal 构成诊断 + 版本号补正
- **v3.5.1**（10a62b5d）：冷启动 70K 注入修复——v2.9.4 工具 systemPrompt 瘦身移植到 else 分支（layer1Prompt 无调用方传入，恒走 else 全量注入 264 工具 → 70K；改后只注入 7 框架工具）
- **v3.5.0**（5dacddfd）：传输层整体回滚到 3.2.2（checkout 8 文件：OpenAIProvider/ChatCompletionsAPI/ResponseAPI/Message/GenerationHandler/PlaceholderTransformer/PromptInjectionTransformer/ChatService）——用户决策：补丁式修复无法解决连接中断；保留流式落盘/保活/权限/闹钟/FGS/DI

## v3.4.x（传输层补丁期——已全部回滚）
- **v3.4.10**（d2cf333a）：Responses API reasoning 回传格式修正（summary 元素类型按 host：deepseek→reasoning_text，其他→summary_text；content 内 reasoning_text 被拒 unknown variant）
- **v3.4.9**（40f941ca）：Responses API thinking 回传——content 内加 reasoning_text 块（后被 v3.4.10 修正位置）
- **v3.4.8**（ee79b24b）：ChatCompletions DeepSeek 强制 reasoning 回传（host.contains("deepseek")）+ 千问缓存机制确认（隐式缓存自动/Qwen3.7 门槛 ~2000 tokens/命中率非 100%）
- **v3.4.7**（9e71dfb6）：空返回自动重试（流式/非流式，平台偶发空流）+ 流式诊断（SSE done/failure chunks+finish_reason、gen_return）
- **v3.4.6**（0029f256）：生成中切后台回答丢失修复——流式增量落盘（GenerationChunk.Messages 每步 saveConversation + onCompletion 兜底）【回滚后保留】
- **v3.4.5**（011d0692）：Required SETTINGS preface 根治——BEFORE_SYSTEM_PROMPT 合并进 system（v2.9.5 注入隔离引入的协议违规）
- **v3.4.4**（3647bb07）：定时任务崩溃——FGS type=none 被 Android 16 禁止；ForegroundInfo 指定 dataSync + Manifest SystemForegroundService 覆盖声明【回滚后保留】
- **v3.4.3**（b7061b06）：闹钟 AI 工具移植（alarm_create/list/delete）+ UI 入口藏到数据与存储
- **v3.4.2**（1561747f）：闹钟页崩溃——AlarmScheduler 未注册 DI（R8 混淆显示 LanguageCircleKt）；权限页加固（build 兜底/startActivity try-catch/SDK 过滤）【回滚后保留】
- **v3.4.1**（13a2ab89）：缓存断层诊断日志（cache: prompt/cached + 骤降>50% 输出缓存断层!）+ CronJobWorker 前台服务 + AlarmReceiver 协程化 + ACCESS_BACKGROUND_LOCATION

## v3.3.x（稳定性期）
- **v3.3.14**（6af4e530）：后台保障链移植（阶段 1/3）——AlarmManager 双通道 + 设备闹钟 + 电池工具 + DB migration 26
- **v3.3.13**（3b22848c）：Required SETTINGS preface 修复——sanitizeToolCallSequence 循环内每步调用 + 移除所有未配对 tool_call
- **v3.3.12**（5ff81bde）：缓存逻辑整体回滚到 2.4.5 移植前
- **v3.3.10-11**：工具域分类管理 UI 修复（工具池对齐/统一分类/幽灵域清理/skill 显示）+ Skill 计入工具统计
- **v3.3.8**（5fd777f6）：16 bug 全量修复——域管理原子化/move 校验与 skill 挂载/skill 体系修复
- **v3.3.6**（aa4b0a30）：消息序列清洗 + skill-MCP 统一到 invoke_tools + 缓存稳定；system 完全静态化（移除 use_skill 动态注入）
- **v3.3.7**（35182bfe）：审批死循环修复 + UI 合并

## v3.2.x（移植前稳定期）
- **v3.2.2**（7bcc022b）：域分类引擎白板化 + 域变更事务化 + MCP 硬前缀映射——【传输层回滚基线】
- **v3.2.0**（616af07b）：F1 根因修复——MCP 动态工具注入全链路

## v2.9.x（缓存优化期）
- **v2.9.5**（43cf1b47）：缓存锚点 + 注入隔离（BEFORE_SYSTEM_PROMPT 变独立 user 消息——引入 SETTINGS 协议违规的根源，v3.4.5 修复）；system prompt 缓存优化（53780f85）
- **v2.9.4**（97faaa9a）：5 工具入域管理 UI + system prompt 瘦身（分层模式仅注入框架工具 systemPrompt）+ 五工具归域 + Skill 开关 + 动态域注入

## 版本号/versionCode 对照（近期）
| 版本 | versionCode | 提交 |
|------|:---:|------|
| v3.5.1 | 162 | 10a62b5d |
| v3.5.0 | 161 | 5dacddfd |
| v3.4.10 | 160 | d2cf333a |
| v3.4.9 | 159 | 40f941ca |
| v3.4.8 | 158 | ee79b24b |
| v3.4.7 | 157 | 9e71dfb6 |
| v3.4.6 | 156 | 0029f256 |
| v3.4.5 | 155 | 011d0692 |
| v3.4.4 | 154 | 3647bb07 |
| v3.4.3 | 153 | b7061b06 |
| v3.4.2 | 152 | 1561747f |
| v3.4.1 | 151 | 13a2ab89 |
| v3.3.14 | 150 | 6af4e530 |
| v3.3.13 | 149 | 3b22848c |
| v3.3.12 | 148 | 5ff81bde |

## 移植状态（fork 三功能）
| 功能 | 状态 |
|---|---|
| 后台保活（AlarmScheduler 双通道 + SCHEDULE_EXACT_ALARM + 电池优化引导） | ✅ 阶段 1（6af4e530） |
| 权限自动发现（PermissionInventory + SettingPermissionsPage + BatteryTool） | ✅ 阶段 2（89fbf274） |
| 工作流（WorkflowEngine + 触发器 + DB + MCP 工具） | ⚠️ 阶段 3 未完成（执行层/UI/MCP 集成未做） |

## 版本线理顺（2026-08-03 深度审计）
- **两轮循环**：第一轮 v2.9.x→v3.8.x（07-11~07-30 上午，已废弃）；第二轮 v3.2.0→v3.5.x（07-30 下午~今，当前）
- **硬重置**：07-30 13:16 v3.8.0→v3.2.0（版本号复用）
- **唯一可靠标识：versionCode**（全程递增，当前 164）——此后版本对照一律以 versionCode 为准
- 分支：backup-bad-merge（第一轮存档）/ desk（Desk 面板）/ upstream-try（上游 2.4.5 适配试验）
