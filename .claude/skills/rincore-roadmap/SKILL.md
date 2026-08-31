---
name: rincore-roadmap
description: "[中优先级·RinCore路线对照] RinCore 更新路线与待办状态。触发词：路线图、roadmap、下一步、待办、待续、工作计划、接下来做什么、未完成。任何规划 RinCore 下一步工作、确认任务状态时加载。不涉及：已完成版本细节（用 rincore-changelog）。"
---

# RinCore 更新路线

## 当前状态（v3.11.26 已发布，2026-08-31）
单产品线 RinCore 3.11.x；CI 全线绿色；远端 Release v3.11.26 APK 67.9MB。
Cherry Studio 内核能力移植启动中：任务清单功能已完成（v3.11.25/26）。

## 待续事项（按优先级）

### P0：验证待用户反馈
- [ ] 用户装包验证 v3.11.26：
  - 多步任务触发 task_tool 时卡片渲染、push/fail 反馈、任务状态持久化
  - 加号面板四快捷入口（子代理详情/模型记忆/CWD/上下文条数）交互
  - 子代理派发后消息列表不再散落 subagent_* 过程，只在子代理详情页可见
  - Memory 写入健康门拦截退化内容
- [ ] v3.11.26 Release body 是否需要重写（当前自动生成的仅 commit 列表）

### P1：Cherry Studio 内核能力后续移植
- [ ] Plan mode（ExitPlanMode 等价）+ 权限模式开关（逐次/自动/仅规划）
- [ ] Subagent 强化：
  - 任务派发时传入显式 sub-agent 角色 prompt 模板
  - 子代理结果汇总回主对话的二次摘要
  - 超时策略调优（当前 default=300s，按任务复杂度自适应）
- [ ] Auto-compact（v3.6.74 旧压缩方案已废弃，需重新设计）

### P2：工作流阶段 3（fork 三功能最后一环）
- [ ] WorkflowEngine 执行层重写（对齐 ToolExecutionContext/ToolCallOrigin）
- [ ] UI 适配（工作流列表/编辑页）
- [ ] MCP 工具集成（workflow_create/list/trigger）
- [ ] 导航注册 + 设置入口

### P3：可选增强
- [ ] 小米澎湃 3 专属白名单引导（自启动/省电/后台弹出界面）
- [ ] 工作流地理围栏（二期，依赖 ACCESS_BACKGROUND_LOCATION）
- [ ] 前台服务常驻（常驻通知 + 耗电代价）

### 已关闭（2026-08 近期）
- Cherry Studio 任务清单/任务卡片 + 折叠卡语义化（v3.11.25）
- task_tool sequentialthinking 式推进反馈（v3.11.26）
- 子代理消息列表过滤 + 唯一展示窗口（v3.11.26）
- 四故障深度修复 B106-B109（v3.11.24）
- 原版 2.4.15 全量移植（v3.11.23）
- 缓存断层诊断 + Zen 关流物理判据 + 运行日志持久化

## 工作节奏
- 每次修改：小步提交 → CI 验证（约 11-15 分钟）→ 用户实测
- 用户偏好：不要大改；适当优化；稳定优先；版本号只升第三位
- 推送纪律：单版本单推，推完即由 CI 自动发 Release；绝不追加修正补丁

## 回滚/石山注意事项
- v3.11.24 四故障修复架构级闭环，B106-B109 bug-record 已归档
- limitContext 函数仍保留在 Message.kt 但 **未启用**；启用即破坏缓存前缀，勿重蹈
- BEFORE_SYSTEM_PROMPT 协议违规在 v3.4.5 修复；缓存锚点/注入隔离合规 > 缓存边际收益
