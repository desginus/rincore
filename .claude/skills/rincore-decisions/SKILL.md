---
name: rincore-decisions
description: "[中优先级·RinCore决策对照] RinCore 关键方案对比迭代记录：为什么这么选/备选方案/回滚记录。触发词：方案对比、为什么这么选、这个方案、决策记录、权衡、回滚、迭代历史。任何涉及方案选择、架构权衡、回滚决策时加载。不涉及：Bug 细节（用 rincore-bug-record）、版本历史（用 rincore-changelog）。"
---

# RinCore 方案决策记录

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
