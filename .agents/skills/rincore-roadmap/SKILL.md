---
name: rincore-roadmap
description: "[中优先级·RinCore路线对照] RinCore 更新路线与待办状态。触发词：路线图、roadmap、下一步、待办、待续、工作计划、接下来做什么、未完成。任何规划 RinCore 下一步工作、确认任务状态时加载。不涉及：已完成版本细节（用 rincore-changelog）。"
---

# RinCore 更新路线

## 当前状态（v3.9.2）
单产品线 RinCore 3.9.x（me.rincore.app），含全文档类型渲染。水工程 WaterHub B 线已废弃移除。CI 绿。

## 待续事项（按优先级）

### P0：验证待用户反馈
- [ ] v3.9.1 全文档渲染装包验证：胶囊窗渲染分发文稿（HTML 交互/PDF 翻页/Word 提取/Excel 表格）
- [ ] v3.9.2 单线构建验证：已装包覆盖升级正常

### P1：持续维护
- [ ] 渲染扩展：doc/xls 老二进制格式提取（当前尽力而为，失败提示）
- [ ] README 版本徽章随发版同步（当前 v3.9.2）
- [ ] 每版本当日归档（changelog/bug-record/decisions/修改全记录）

## 已关闭
- 缓存稳定性（D2 服务端机制不可控，停止优化）
- Zen 通道完成信号（v3.8.33 物理判据定稿）
- 运行日志持久化（v3.8.34）
- token 统计口径（v3.8.43）
- 产品线拆分（v3.9.2 废弃 B 线，D18）

### P1：工作流阶段 3（fork 三功能最后一环）
- [ ] WorkflowEngine 执行层重写（对齐 ToolExecutionContext/ToolCallOrigin）
- [ ] UI 适配（工作流列表/编辑页）
- [ ] MCP 工具集成（workflow_create/list/trigger）
- [ ] 导航注册 + 设置入口
- [ ] 关键发现：DirectModeActionRunner 可复用（执行层零适配）；AgentRunKind.Workflow 已有

### P2：小米澎湃 3 专属白名单引导（fork 没有，需自建）
- [ ] 自启动 AppOps AUTO_START 引导
- [ ] 省电策略无限制引导
- [ ] 后台弹出界面引导
- [ ] ADB 命令提示：`appops set <pkg> RUN_IN_BACKGROUND allow`
- [ ] HyperOS 墓碑/速冻不影响 AlarmManager 唤醒，无需处理

### P3：缓存断层诊断（待验证）
- [ ] 下次遇 9.7K 时查 `cache:` 日志行——若 `缓存断层!` 且下一轮恢复 → 平台机制确认非 bug
- [ ] 若持续低命中 → 排查前缀变化（记忆写入/工具池变化）——日志会显示具体 prompt/cached 值

### P4：可选增强
- [ ] 前台服务常驻（常驻通知 + 耗电代价，AI 助手场景可接受）
- [ ] 工作流地理围栏（二期，依赖 ACCESS_BACKGROUND_LOCATION——v3.4.1 已加 Manifest + 权限页引导）

## 回滚后注意事项
- v3.4.5-v3.4.10 的传输层补丁已移除——记录在 rincore-bug-record（B11-B16），需要时按"已验证的修复"重新移植，不整体照搬
- 3.2.2 基线的系统提示注入：框架工具瘦身（v3.5.1 已移植到 else 分支）
- 孤儿 tool_call 清洗：3.2.2 基线只在入口一次——若 SETTINGS 类报错再现，评估重移植 v3.3.13 的循环内清洗

## 版本规划
- 下一个版本：v3.5.2（若复测发现基线问题则优先修复）
- 版本号规则：小修改升第三位；versionCode 从 162 起每次 +1

## 工作节奏
- 每次修改：小步提交 → CI 验证（约 11-15 分钟）→ 用户实测
- 用户偏好：不要大改；适当优化；稳定优先
- 遇到反复问题的原则：回滚到稳定基线 → 从基线重新诊断 → 只移植验证过的修复

## 石山清理计划（全量清单见 docs/architecture-audit-20260803.md）
- G1 BEFORE_SYSTEM_PROMPT 协议违规（P0——SETTINGS 隐患，需移植 v3.4.5 合并修复）
- G2 孤儿 tool_call 无清洗（P1——需评估移植 v3.3.13）
- G3 平台空流无应对（P1——观察后决定）
- G4 缓存断层诊断缺失（P2——重加 cache 日志）
- S6 RouteActivity 导航碎片化（P2——适配时拆分）
- S7 SkillsTools 裁剪完整性验证（P2——skill_ 工具检查）
- S9 fork 未移植 165 文件适配管理（P2——Telegram/Codex/Skill管理/触发器）
- S10 版本号漏改无自动校验（P3——发布流程加校验）
