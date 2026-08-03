# RinCore 架构梳理与来源标注报告

> 日期：2026-08-03 | 版本：v3.5.3 | 目的：全量技术债盘点 + 内容来源管理（为后续 RikkaHub 更新适配做准备）

## 一、内容来源标注体系

### 三类来源定义
| 来源 | 定义 | 判别方法 |
|---|---|---|
| **🟢 自研** | 我们在 fork 基础上自己新增的模块 | fork 源码中无同名文件 |
| **🟡 移植 fork** | 从 AAAelina/rikkahub-agent 选择性手动移植 | fork 有同名文件，我们改动/裁剪 |
| **🔵 继承原版** | 继承自原版 RikkaHub（上游）并经原版更新移植演进 | fork 与原版共有，我们保持同步 |

### 模块来源总表（app 主包）
| 模块 | 来源 | 状态 |
|---|---|---|
| `data/ai/GenerationHandler` | 🔵 继承 + 🟢 演进（缓存/域/分层） | 已回滚 3.2.2 基线 |
| `data/ai/transformers/` | 🔵 继承 + 🟢 自研（ContextCompressionTransformer） | 部分回滚 |
| `data/ai/tools/local/` | 🟢 自研为主（LocalTools 体系 13 个工具） | 健康 |
| `data/ai/tools/routing/` | 🟢 自研（ToolRouter/ToolDomain/ToolClassifier 域体系） | 健康 |
| `data/ai/mcp/` | 🔵 继承 + 🟢 自研（McpOAuthClient/Callback） | 健康 |
| `data/ai/compression/` | 🟢 自研（ToolOutputCompressor/NaturalLanguageFormatter） | 健康 |
| `data/alarm/` | 🟢 **自研**（设备闹钟——fork 无此模块，仅借用 AlarmManager 思路） | ⚠️ 来源记录曾误标"移植 fork"，已修正 |
| `data/permissions/` | 🟡 移植 fork（PermissionInventory 自包含版） | 健康 |
| `data/db/` | 🔵 继承 + 🟢 自研 migration（24_25/25_27）+ 🟢 Alarm/Folder 表 | 早期链缺失 |
| `ecosystem/` | 🟢 自研（EcosystemManager/Scanner/Plugin 解析/DynamicTools/SlashCommand） | 健康 |
| `openclaw/` | 🟢 自研（ClawSkill 体系） | 健康 |
| `service/` | 混合：CronJob 系列 🔵 继承；Alarm/通知/前台 🟢 自研 | 健康 |
| `workflow/` | 🟡 移植 fork（阶段 3 未完成：model/trigger 已搬，engine/UI 未接） | ⚠️ 未完成 |
| `ui/` | 混合：🔵 继承页面 + 🟡 移植（Permission）+ 🟢 自研（Alarm/Domain/Ecosystem/CallTrace/ClawSkills） | 健康 |
| `web/` | 🟢 自研（FolderRoutes/EventsRoutes） | 健康 |
| `costguards/` | 🔵 继承 | 健康 |

### fork 未移植能力（165 个文件——后续适配目标）
| 分组 | 数量 | 说明 |
|---|---|---|
| `data/ai`（fork 工具集） | 65 | fork 的 AI 工具（未移植的本地工具） |
| `ui/pages` | 20 | fork UI 页面 |
| `data/db` | 8 | fork DB 层 |
| `data/telegram` | 7 | **Telegram 集成（fork 独有）** |
| `data/codex` | 6 | **Codex 集成（fork 独有）** |
| `workflow/trigger` | 5 | 工作流触发器（阶段 3 余量） |
| `data/preferences` | 4 | 偏好设置 |
| `skills/*` | 5+ | **Skill 管理（ZipImporter/Catalog/TestRunner/InstallTools）** |
| `workflow/ui` + `workflow/tools` | 3 | 工作流 UI/工具 |
| 其他 | 少量 | NetworkChangeMonitor/notification 等 |

## 二、石山清单（全量）

### 回滚缝隙（G 系列——3.2.2 基线 vs v3.4.x 业务代码）
| 编号 | 问题 | 严重度 |
|---|---|---|
| G1 | BEFORE_SYSTEM_PROMPT 协议违规潜伏（v2.9.5 独立消息版在 3.2.2 基线内）——DeepSeek 触发 SETTINGS 风险 | P0 |
| G2 | 孤儿 tool_call 无清洗（v3.3.13 修复随回滚丢失） | P1 |
| G3 | 平台空流无应对（v3.4.7 重试随回滚丢失） | P1 |
| G4 | 缓存断层诊断缺失（v3.4.1 日志随回滚丢失） | P2 |

### 功能增减积累（S 系列）
| 编号 | 问题 | 严重度 |
|---|---|---|
| S1 | 工具池无启用过滤（264 全量注册——域分类只影响 system 概览）——v3.5.3 已部分缓解（MCP 懒加载） | P1→缓解中 |
| S2 | DB migration 早期链缺失（7_8/9_10 等，version=27 但仅 11 个文件）——运行正常 | P2 |
| S3 | 传输核心链路无测试（27 个测试偏序列化/transformer） | P2 |
| S4 | buildCacheAnchor 注释与实现脱节（注释"含工具目录"实际纯静态） | P3 |
| S5 | runBlocking 使用（AlarmTools/ContentProvider——可接受） | P3 |
| S6 | **RouteActivity 432 行 diff**（fork 838→我们 894——导航注册/入口全堆一个文件，增长中） | P2 |
| S7 | **SkillsTools 大幅裁剪**（fork 175→我们 76 行——裁剪可能丢失 skill 管理能力，需验证 skill_ 工具完整性） | P2 |
| S8 | **设备闹钟来源误标**（此前记录"移植 fork"——实为自研——文档误导后续排查） | P3 |
| S9 | fork 未移植 165 文件无系统管理（Telegram/Codex/Skill 管理——适配时无对照清单） | P2 |
| S10 | **v3.5.2 版本号漏改**（build.gradle.kts 未随代码提交更新——发布流程无自动校验） | P3 |

### 已确认干净
- v3.4.x 补丁残留：零（sanitizeToolCallSequence/gen_retry/缓存断层/normalizedMessages 均无引用）
- 动态源审计：system 静态化、TimeReminder 固定时间戳、工具池请求内快照
- fork 双通道保活：CronJob* 系列继承完整

## 三、后续适配规划（RikkaHub 更新能力）

### 适配优先级（fork 未移植 165 文件）
| 优先级 | 能力 | 内容 | 状态 |
|:---:|---|---|---|
| P0 | 工作流阶段 3 | WorkflowEngine 执行层/UI/MCP 工具/导航入口（DirectModeActionRunner 可复用） | ⚠️ 进行中 |
| P1 | Skill 管理补齐 | SkillZipImporter/SkillCatalog/SkillInstallTools（补全 skill 安装/目录/测试） | 未开始 |
| P2 | 工作流触发器 | 5 个未移植触发器（补齐 TriggerRegistry） | 未开始 |
| P3 | Telegram/Codex | fork 独有集成（按需评估） | 观望 |

### 适配原则（防止新石山）
1. 移植必须手动适配（严禁覆盖本地文件）
2. 每移植一个模块：更新来源标注总表 + 记录到 changelog
3. 移植前对照 S7 教训：裁剪前确认功能完整性
4. 大文件（RouteActivity）适配时拆分散布入口

## 四、结论
- **石山主体**：G 系列（回滚缝隙 4 处）+ S 系列（功能增减积累 10 处）——**已全部登记**
- **来源标注**：三来源体系建立——后续任何模块改动/适配都能追溯来源
- **下一步**：按优先级逐个清石山（G1 协议违规 → G2 清洗 → S7 skill 完整性验证 → 发布流程版本号校验）
