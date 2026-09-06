---
name: rincore-roadmap
description: "[中优先级·RinCore路线对照] RinCore 更新路线与待办状态。触发词：路线图、roadmap、下一步、待办、待续、工作计划、接下来做什么、未完成。任何规划 RinCore 下一步工作、确认任务状态时加载。不涉及：已完成版本细节（用 rincore-changelog）。"
---

# RinCore 更新路线

## 当前状态（v4.0.6 已发布，2026-09-06）
RinCore 4.0.x；CI 双 run 绿色；Release APK 68.0MB。
v4.0.0 全量重写工程完成（RetryPolicy/WatchdogPolicy/请求构造/流解析四模块）；
v4.0.1 原版 2.4.17 全量适配移植；v4.0.2 文档预览+澎湃 OS 4 动效基建；
v4.0.3-4.0.6 OpenCode 缓存修正与工具图片兼容四部曲（B118 闭环）。
v4.0.6 初次压力测试通过（工具读图链路）。

## 待续事项（按优先级）

### P0：验证待用户反馈
- [ ] v4.0.6 扩展压测：CC 通道（Command Code）工具读图链路复验
- [ ] 工具图片终态形状在多模型（非 qwen 家族）下的兼容性抽查
- [ ] 备份恢复流程实测（v4.0.1 重构后 PendingRestore 链路首次真实恢复）

### P1：挂起项（用户明示先留着）
- [ ] CC/OC 焦点大窗用量渲染差异（早期设计耦合，底层查询链已同构化，后续有精力再规划）
- [ ] 大窗小窗 Bug（挂起中）

### P2：动效与体验延展
- [ ] HyperMotion 基建扩展：全局按钮按压动效（hyperPress 容器已备，未铺开）
- [ ] 预览格式灰度扩展（特定二进制/特殊编码文本）

### P3：Cherry 内核能力移植（v3.11.25/26 完成任务清单，后续未续）
- [ ] Plan mode + 权限模式开关（状态未知，需重新评估 v4.0 重写后的基线）
- [ ] Subagent 强化（角色 prompt 模板/结果二次摘要/超时自适应）
- [ ] Auto-compact 重新设计（旧方案 v3.6.74 已废弃）

## 维护纪律
- 文档随版本滚动：changelog 每版必更；bug-record 出根因必记；roadmap 每轮整顿校准
- 版本链与实证经验以 memory id 209 为动态主档，skill 文档为静态沉淀
