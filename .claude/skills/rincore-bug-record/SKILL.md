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

## 排查方法论（用户约定）
1. 排查顺序：先确定相关代码 → 对照 → 理清逻辑 → 想清楚再改 → 验证
2. 无价值信息绝不允许破坏缓存
3. 修改要有全局意识，主动发现并修复用户未察觉的 bug
4. 数值计算必须用代码执行，禁止心算
5. 单次修改小步提交，CI 验证后继续
