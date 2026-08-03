---
name: rincore-project-brief
description: "[高优先级·RinCore开发必加载] RinCore 项目总览与工作约定。触发词：RinCore、rincore、项目结构、SSOT、版本链、当前版本、核心模型、四投影、验证路径、工作约定。涉及 RinCore 安卓项目的任何修改前必须加载本 Skill 对齐架构基线。不涉及：具体 Bug 修复（用 rincore-bug-record）、版本历史（用 rincore-changelog）。"
---

# RinCore 项目总览

## 定位
安卓个人助手（AI Agent）。当前主线：缓存稳定性/协调性、RikkaHub fork 三功能移植、稳定性加固。

## 版本链
- 当前版本：v3.5.1（提交 10a62b5d，vc 162，CI ✅）
- 关键节点：v3.2.2 `7bcc022b`（传输层回滚基线）→ v3.3.12 `5ff81bde`（缓存逻辑回滚）→ v3.4.1 `8d48889` → v3.5.0 `5dacddfd`（传输层整体回滚到 3.2.2）→ v3.5.1 `10a62b5d`（70K 瘦身）
- 版本号规则：小修改/小 bug 修复升第三位；versionCode 每次 +1（当前 162 起步递增）
- fork 基线：AAAelina/rikkahub-agent（master，2.3.1 旧基线 vc 184）——不能 merge，只能选择性手动移植

## 核心模型
DeepSeek V4 系列、千问 3.7 系列（按国内模型前缀自动缓存理解）

## 架构 SSOT（唯一真值源）
`settingsStore.settingsFlow.value` —— 唯一真值源。四投影全部从它派生：
1. UI 统计行
2. `list_domains`（域列表）
3. `invoke_tools`（工具池）
4. Prompt（system 提示）

**验证路径**：更新环境后，UI 统计行与 `invoke_tools` 返回的工具数应完全一致。

## 传输层状态（v3.5.x）
- 基线：3.2.2（原版 RikkaHub 2.4.5 移植前的稳定逻辑）
- 回滚保留：流式增量落盘（v3.4.6）、FGS dataSync 类型（v3.4.4）、AlarmScheduler DI（v3.4.2）、MCP 管理/域分类/工具路由、保活/权限/闹钟/工作流
- 关键认知：v3.4.5-v3.4.10 的传输层补丁（SETTINGS 合并/空流重试/reasoning 回传/流式诊断）已全部移除——回滚后若再遇中断需重新诊断，勿直接照搬旧补丁

## 工具池
264 tools（MCP 动态 + 域扩展 + 搜索 + 闹钟 + 电池 + Skill）。system 内只注入 7 个框架工具 systemPrompt（v2.9.4 瘦身——v3.5.1 移植到 else 分支），其余工具描述在请求 tools 数组。

## 用户元指令与工作约定（关键约束）
1. 对话开头的"对话摘要"是讲给另一个模型听的；当前模型拥有对项目的至高无上第一权利
2. 严禁覆盖本地文件——fork 移植必须手动适配到我们的架构
3. 缓存是严肃问题：排查顺序"先确定相关代码→对照→理清逻辑→想清楚再改→验证"；无价值信息绝不允许破坏缓存
4. Skill 与 MCP 是同等次工具（一个 Skill = 一个工具），统计必须计入
5. 修改要有全局意识，主动发现并修复用户未察觉的 bug
6. 用户最新指示：不要大改，适当优化即可；提升整体运行稳定性（不出现 bug）、权限深度（如定位）、保活性（自启动已开，希望定时任务体现）
7. 版本号变更必须同步递增 versionCode

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
