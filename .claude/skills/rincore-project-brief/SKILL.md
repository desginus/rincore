---
name: rincore-project-brief
description: "[高优先级·RinCore开发必加载] RinCore 项目总览与工作约定。触发词：RinCore、rincore、项目结构、SSOT、版本链、当前版本、核心模型、四投影、验证路径、工作约定。涉及 RinCore 安卓项目的任何修改前必须加载本 Skill 对齐架构基线。不涉及：具体 Bug 修复（用 rincore-bug-record）、版本历史（用 rincore-changelog）、方案决策（用 rincore-decisions）。"
---

# RinCore 项目总览

## 定位
安卓个人助理（基于 RikkaHub 独立维护，原版同步跟进至 2.4.17）。当前主线：v4.0 重写后架构稳定性、网关兼容性（形状级校验对抗）、全文档渲染、澎湃 OS 4 动效。单产品线，无 B 线。

## 版本链
- 当前版本：v4.0.6（versionCode 锁 9999，CI ✅）
- 关键节点：v3.15.0（原版 2.4.16 定向移植）→ v4.0.0（全量重写工程：RetryPolicy/WatchdogPolicy/请求构造/流解析）→ v4.0.1（原版 2.4.17 全量适配移植+备份体系重构）→ v4.0.2（Office/PDF 文档预览+澎湃 OS 4 动效基建 HyperMotion）→ v4.0.3（OpenCode 缓存修正+5xx 双保险）→ v4.0.4-4.0.6（工具图片兼容四部曲，B118 闭环）
- 版本规则：versionCode 锁 9999 只动 versionName；release 按 versionName 打 tag
- 历史产品线：WaterHub（B 线）已废弃移除，相当于从未存在（除非用户主动提出）

## 核心模型
DeepSeek V4 系列（测试环境：api.deepseek.com Chat Completions 流式）、OpenCode Zen（ox/grok）、OpenAI 兼容系列

## 架构 SSOT（唯一真值源）
`settingsStore.settingsFlow.value` —— 四投影全部从它派生：
1. UI 统计行
2. `list_domains`（域列表）
3. `invoke_tools`（工具池）
4. Prompt（system 提示）

**验证路径**：更新环境后 UI 统计行与 `invoke_tools` 返回的工具数应完全一致。

## 传输层状态（v4.0.x）
- 架构：v4.0.0 全量重写完成 — RetryPolicy.kt（重试策略抽象）/WatchdogPolicy（跨族统一）/thinkingControlFields（思考控制三态提取）/请求构造分层/流解析收口
- 关键组件：GenerationHandler（重试链）→ RetryPhase 密封类；CC 通道 ChatCompletionsAPI；ClaudeProvider（Anthropic 协议）；工具图片序列化层默认剥离+规范化后处理（B118）
- 网关形状约束（B118 铁律）：qwen 兼容层唯一安全形状四条 = 纯 tool_result user / text+image 混合 user / assistant(text+tool_use) / assistant(text)

## MCP 体系（v3.8.41 懒连接）
- 启动/配置变更只登记待连接（pendingConfigs），首次工具调用才建连
- 工具声明静态化：由配置决定（enable + assistant 绑定），不受连接状态影响，不破坏缓存
- getTransport 必须在 runCatching 内（B19 状态撕裂教训）
- 禁止启动路径批量 addClient（v3.6.112 教训）

## 工具池
430 tools（MCP 动态 + 域扩展 + 搜索 + 闹钟 + 电池 + Skill）。分层注入：7 个框架工具在 system（v2.9.4 瘦身），其余在请求 tools 数组。禁止任何形式全量注入（用户铁律，214 记忆）。

## 渲染能力（v3.9.1）
胶囊窗渲染卡片支持全文档类型：HTML/HTM/SVG WebView 动态交互、PDF PdfRenderer 逐页、DOCX 段落提取、XLSX/CSV 表格、文本族等宽渲染。入口：工作区文件胶囊窗 → 渲染。

## 用户元指令与工作约定（关键约束）
1. 输出规范：禁用横线、语气词、括号及装饰性符号，直接简洁
2. 严禁覆盖本地文件；先定位对比澄清再修改
3. 缓存是严肃问题：任何逐轮变化的字段都会断缓存前缀，改动前必须评估
4. 功能边界：只做用户点名的功能，不自作主张附带（v3.8.44 教训）
5. 产品线：未经用户明确确认不建（D18）
6. 版本号变更必须同步 versionCode；推送只交付 RinCore 单 artifact
7. 签名必须连续（release.jks 30 年，唯一副本 /workspace/rincore-keystore/）
8. 补丁式修复不可接受，必须根因架构级深修
9. 先交付成果文本，归档静默后台进行；推送后等 CI 再回馈

## 知识库
- 活跃版：.claude/skills（8 个 skill，完整提交史）
- .agents/skills 为同步副本（改动后同步）
- 文档生态：docs/ecosystem/（01 来源归档 / 02 模块地图 / 03 修改全记录 / 04 错误对照表 / 05 迭代框架）

## 开发流程（必读）
任何修改前加载 rincore-dev-process（Scrum 式工作流），先查 rincore-bug-record 对照历史根因。连接配置参照 v2.9.8，缓存参照 v3.5.11，原版行为参照 upstream-try/master。