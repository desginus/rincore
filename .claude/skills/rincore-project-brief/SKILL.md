---
name: rincore-project-brief
description: "[高优先级·RinCore开发必加载] RinCore 项目总览与工作约定。触发词：RinCore、rincore、项目结构、SSOT、版本链、当前版本、核心模型、四投影、验证路径、工作约定。涉及 RinCore 安卓项目的任何修改前必须加载本 Skill 对齐架构基线。不涉及：具体 Bug 修复（用 rincore-bug-record）、版本历史（用 rincore-changelog）。"
---

# RinCore 项目总览

## 定位
安卓个人助手（AI Agent）。当前主线：功能链稳定 + 体验铺展架构（预热管线 v1）+ 冷态性能改进 + 大型文件拆解消化（见 rincore-roadmap）。

> **基线提示（2026-10-09 更新）**：当前主线已推进到 **v4.8.136**（versionCode 锁 9999，只动 versionName 第三位）。本文件正文停留在 v4.8.53 期快照，v4.8.54→v4.8.136 的全部改动以 `rincore-changelog` 为准；近期要点（v4.8.96→4.8.118）：MCP stdio 回归修复（B122）、officecli 渲染链三轮（断链/加载反馈/提速，B123-125）、极简模式铁闸（B126）、无感衔接（B127；并行执行后按 CC 模型重写为分区模型，B131）、**上传图片缓存全崩对账原版根治（B128）＋ XLSX 降级弹窗/网关空数组/上传轮缓存连续性三合一（B130）**、子代理预算误熔断根治 100K→1000K（B129）、**需求单 v2 全单（MCP ctx 下传 _meta / 产物归集 rescued / tool_outputs 语义定死 / stdio env argv 注入 / grep -E / edit 串行化+wrote+postHash）**、**切换助手零导航终版（B135）**、**静默中断完成门五通道全覆盖＋分享/拍照加固＋ToolMention 缓存根治＋英文诱导强化（B136，v4.8.112）**、**工具产图气泡内直接可见整链改造（XSS file:// 放行＋render_markdown 直贴＋工具段缩略图＋占位复读根，B137，v4.8.113）**、**网络连接失败可见化（连接期失败识别＋重试状态展示＋终态文案可操作化，B138，v4.8.114）**、**工具产图渲染地址统一（规范形态=编码file://＋Coil认领层单一解码＋ToolImagePayload单一来源，v4.8.115）**、**浏览器工具 B139 整链重写（导航结算/失败可见/受控输入/页面状态语义/并行互斥/截图工作区化，v4.8.116）**、**多对话并行中断不重试双因根治（并行流登记+完成门并行盲区+假取消区分，B140，v4.8.117）**、**完成门v2证据化+toolPoolProvider助手错位+CallTracer键控（B141，v4.8.118）**、**渲染链升级（数学解析器整移植 CJK 公式根修+链接递归+单元格行内直渲，v4.8.119）**、**渲染器再增强 codex 对齐（md围栏表格解包+GFM列对齐）+子代理默认超时1800s（v4.8.120）**、**子代理双模式+去420疑云+并发7 / 代码块自动渲染 / 代码块滑动防抽屉 / 极简模式删除 / 抽屉露边条40 / 本对话产出（v4.8.121）**、**图片地址'_'污染根修（URL opaque 化）+本对话产出扩面+上传文件胶囊选项弹层+消息边距定版（B144，v4.8.122）**、**图片稳定占位/胶囊窗选项化/压缩期可发送（v4.8.123）**、**输出门禁彻底回滚（v4.8.124，用户定版）**、**看门狗 150s 统一+重试可见化+产出路径规范化+文件胶囊 3 选项重写（v4.8.125）**、**OC 客户端合规：伪装 UA（opencode/codex）+辅助会话归属（v4.8.126-127）**、**LaTeX `\tag` 家族清洗（v4.8.128）**、**文件三件套统一+图片保存根修（v4.8.129）**、**重试链递增退避改版（v4.8.130）**、**撤流中断可见化+发起重试 4→5+全链审计（v4.8.131）**、**缓存退化定罪+探活治理（v4.8.132）**、**子代理超时真值对齐（v4.8.133）**、原版 2.5.6 全量移植（图表工具链/MCP fork/模型表等）。

## 版本链
- 当前版本：v4.8.133（versionCode 锁 9999，只动 versionName）
- v4.x 为当前单线：重写工程（4.0）→ 拉齐（4.1）→ 适配移植（4.2-4.3）→ 功能爆发（4.5-4.7）→ 冷态诊断/全量回植/铺展架构（4.8）
- 完整版本链见 rincore-changelog（+ FULL_HISTORY.md 全量归档）；版本号规则见 rincore-dev-process §0
- fork 基线：原版 RikkaHub（上游对拍走 upstream-try remote；2.5.4 / 2.5.6 已适配移植）

## 核心模型
DeepSeek V4 系列、千问 3.7 系列（按国内模型前缀自动缓存理解）

## 架构 SSOT（唯一真值源）
`settingsStore.settingsFlow.value` —— 唯一真值源。四投影全部从它派生：
1. UI 统计行
2. `list_domains`（域列表）
3. `invoke_tools`（工具池）
4. Prompt（system 提示）

**验证路径**：更新环境后，UI 统计行与 `invoke_tools` 返回的工具数应完全一致。

## 传输层现状（v4.8.x）
- 三通道：CC / Claude / ResponseAPI（OpenCode 网关流转）；断流重试三件套（清池/网络监听/WifiLock）+ watchdog 三阶段 + 网络感知等待；保活参数已放宽（4.8.35）；前台服务保活（4.8.20）
- 场景分流：OpenCode+Responses+grok 上游瞬时错误转重试链（4.8.39）；移植 2.5.4 思考档位/流解码对齐（4.8.40）
- 关键认知：无完成信号网关"完成 vs 掐流"用回合语义判定（B28）；升级用户 DB 迁移=启动链最高危（B31）；断流=连接层优先（B34）

## 工具池
**工具矩阵**（v4.8.83 重写，取代旧「工具域」）：全部**工具区**是同一种声明式数据 `ToolZone{id=完整路径,title,description,keywords}`，内置/自定义不再区分；出厂模板首启播种一次，之后用户完全拥有；**声明即存在**（0 工具也不蒸发）；兜底区 `未分类` 恒存在。**顶层直连工具** = 出厂模板 + 用户提升 − 用户降级（注入链直接消费）。入口：设置→工具矩阵（`SettingZonePage`）；AI 工具 `manage_zone / list_zones / search_zones / move_tool_to_zone / invoke_tools`。MCP 动态 + 搜索 + 闹钟 + 电池 + Skill + 沙箱/文档链（OfficeCLI / ast-grep / yq / pdf / doc 等）。system 内只注入顶层工具描述，其余工具描述在请求 tools 数组；计数口径 = UI 统计行与 invoke_tools 一致。

## 用户元指令与工作约定（关键约束）
1. 对话开头的"对话摘要"是讲给另一个模型听的；当前模型拥有对项目的至高无上第一权利
2. 严禁覆盖本地文件——fork 移植必须手动适配到我们的架构
3. 缓存是严肃问题：排查顺序"先确定相关代码→对照→理清逻辑→想清楚再改→验证"；无价值信息绝不允许破坏缓存
4. Skill 与 MCP 是同等次工具（一个 Skill = 一个工具），统计必须计入
5. 修改要有全局意识，主动发现并修复用户未察觉的 bug
6. 不再开倒车：功能性改动只进不退；暂停/删除必须用户定版并记录 rincore-decisions（D12）；渲染调度类方案对照 D11 禁区
7. 版本号：仅动 versionName 第三位；versionCode 锁 9999（不动）

## 历史 Bug 状态（F1–F4）
| ID | 问题 | 状态 |
|:--:|------|:----:|
| F1 | 动态 MCP 工具不注入调用列表 | ✅ McpManager.sync() + getMcpTools() + GenerationHandler 每步注入 |
| F2 | workspace_shell 进程无法持久化 | ❌ proot 沙箱机制，代码不可修复 |
| F3 | plugin_install 提取为空 | ✅ ClaudePluginParser 全局搜索 + 嵌套 skills |
| F4 | mcp_connect stdio 不启动进程 | ❌ SDK 限制，走 workspace_shell + streamable_http |

## 验证检查点
- CI 绿（GitHub Actions，约 11-15 分钟）
- 工具数：UI 统计行 == invoke_tools 返回
- 缓存日志：`cache: prompt=X cached=Y` / `缓存断层!` 行
- 流式诊断：`SSE done/failure` chunks+finish_reason / `gen_return` last_role+last_len


## 内容来源标注体系（三来源）
| 来源 | 定义 |
|---|---|
| 🟢 自研 | 我们在 fork 基础上自己新增（fork 无同名文件）：LocalTools 体系/域分类/生态/闹钟/文件夹/压缩/诊断/MCP OAuth/ClawSkill |
| 🟡 移植 fork | 从 AAAelina/rikkahub-agent 选择性手动移植：权限/工作流(部分)/三功能 |
| 🔵 继承原版 | 继承自原版 RikkaHub 并随上游更新移植：GenerationHandler/ChatService/Provider/UI 框架/CronJob 定时任务 |

**关键修正**：设备闹钟（data/alarm/）实为**自研**（fork 无此模块——之前误标"移植 fork"）。
**fork 未移植 165 文件**（Telegram 7/Codex 6/Skill 管理 5+/工作流触发器 5/UI 20）——后续适配目标，详见 docs/architecture-audit-20260803.md

## 开发流程（必读）
任何修改前加载 **rincore-dev-process**——开发与发布环境全图（Git 工作流/日志体系/工具链/Skill 维护/热态测试协议）。
