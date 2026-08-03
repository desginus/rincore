---
name: rincore-changelog
description: "[中优先级·RinCore开发对照] RinCore 完整版本更新日志。触发词：版本历史、更新日志、changelog、这个版本改了什么、版本对比、回滚历史、版本链。任何需要了解 RinCore 某版本改动/某功能何时引入/何时回滚时加载。不涉及：Bug 根因细节（用 rincore-bug-record）、方案决策（用 rincore-decisions）。"
---

# RinCore 更新日志（v2.9.4 → v3.5.1）

## v3.5.x（传输层回滚期）
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
