---
name: rincore-roadmap
description: "[中优先级·RinCore路线对照] RinCore 更新路线与待办状态。触发词：路线图、roadmap、下一步、待办、待续、工作计划、接下来做什么、未完成。任何规划 RinCore 下一步工作、确认任务状态时加载。不涉及：已完成版本细节（用 rincore-changelog）。"
---

# RinCore 更新路线（v4.8.53 期 · 2026-09-26）

> **基线提示（2026-10-09 更新）**：主线已到 **v4.8.178**，本路线图为 v4.8.53 期快照。
> 期间批次（v4.8.54→4.8.112 数十版）详情见 `rincore-changelog`。
> 新增在办项：多助手协作 A2A —— 阶段 A（项目包移出为助手，4.8.77）已落地，
> **阶段 B（`talk_to_assistant` 工具 / agent-as-tool）待用户点头立项**，设计见 `docs/assistant-a2a-design.md`。

## 当前状态
- 版本 v4.8.81；CI 一推一建全绿（push 即自动构建，Release 交付）。
- 近期主线（已完成）：冷态诊断 + 全量回植（4.8.43-49）→ 预热管线 v1 铺展架构（4.8.50）→ 五项修复/功能（4.8.51-52：蓝标/缓存/黑屏/裁切/双背景）→ 保存链假成功根治（4.8.53）。
- 纪律有效期：不再开倒车（D12）/ 追赶线永不复用（D11）/ 热态判读（D9）/ 一推一建（D13）。

## 进行中 / 下一步（按优先级）
### P1：预热管线 Phase 3 — 存储沉淀
- [ ] 解析结果 content-hash 磁盘 LRU（启动预热优先读盘回填、未命中再解析）
- [ ] AST 编解码可行性已在案（vendored :markdown 模块内加 codec；退路 = 持久化 preprocessed+html）
- [ ] 设计文档：docs/WARM_PIPELINE.md §存储沉淀阶段

### P2：冷态性能系统改进
- [ ] baseline profile 生成器扩展（覆盖"进入对话 + 向上翻滚动"路径——当前只覆盖启动/发消息）
- [ ] 重生成 profile（需设备/模拟器跑 :app:generateReleaseBaselineProfile；可研究 CI 模拟器方案）
- [ ] 目标：冷态（新装包首用）也尽量流畅——缓解 B47 类体验

### P3：深层次大重构（备忘，非紧急）
- [ ] docs/REFACTOR_PLAN.md Phase 2+：GenerationHandler / ChatService 等超大文件继续拆解
- [ ] 历史补丁密度清理

### P4：历史待办（低优先，用户优先级为准）
- [ ] 工作流阶段 3（fork 三功能最后一环——执行层/UI/MCP 集成）
- [ ] 小米澎湃白名单引导自建（自启动/省电/后台弹出）
- [ ] "Upstream response was not valid JSON" 存档观察（已加场景分流重试）

## 工作节奏（定版）
- 小步提交 → push（一推一建）→ CI 绿即交付 → 用户热态实测
- 探针包体系保留（diag-X.Y.Zp，单变量隔离）；每次修改同步更新 changelog / bug-record / decisions

## 版本规划
- 下一版本：4.8.54（按需）；版本号规则：仅动 versionName 第三位；versionCode 锁 9999

## 上游对齐候选（v4.8.97 审计发现）
- **OpenRouter reasoning_details 直通**（上游 #1648 引入，属 2.5.4 基线内缺口）：上游 ChatCompletions 在流式解析时把结构化 reasoning_details 存入 OpenReasoningMetadata 并在后续请求原样回传；我方仅保留该元数据类型、无读写接线。影响面=OpenRouter 上推理类模型的多轮思考连续性。移植需适配我方定制流式解析器（谨慎，先本地复现再动）。
- NodeDiff/WebServerState 等 web 模块零引用符号（v4.8.97 已核为"同文件内部使用"，保留）。
