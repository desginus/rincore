<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=构建&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/版本-v3.9.2-blue)](https://github.com/desginus/rincore/releases)
[![License](https://img.shields.io/badge/许可-segmented_dual-cyan)](LICENSE)

**手机上一个真正全面的 AI 助手。** 不是套壳，是一台重新打造过的引擎——背后是六周的高强度实测迭代与 400+ 个版本。

RinCore 是 [RikkaHub](https://github.com/re-ovo/rikkahub) 的独立分支持续维护。它继承了 Rika 系一贯的基因（原生 Android、Material You、多 Provider），然后把底层的引擎重新做了一遍——更便宜、更稳定、更可控，并注入设备级的 Agent 能力。

> **真机实测：** 400+ 工具加载、叠加超长角色预设上下文，冷启动只需约 10K token。（全量注入时代是 70K~100K+。）

[English](README.md) | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

</div>

## 🚀 下载

RinCore 每次推送都会构建。两种方式获取最新 APK：

1. **GitHub Releases（推荐）** —— `nightly` 预发布版每晚重新发布，始终指向最新构建：<https://github.com/desginus/rincore/releases>
2. **GitHub Actions 产物** —— 每次 CI 通过都会产出即时 APK，打开最新运行、展开 `rincore-release` 即可下载：<https://github.com/desginus/rincore/actions>

下载后直接安装，无需任何商店。

## 🔁 更新节奏：快，且每一步都是真的

RinCore 的更新是持续的，每个版本号都承载了实打实的改进：

- 项目历史约 **6 周**（2026 年 7 月起），累计 **400+ 个版本发布**，近 30 天 **540+ 次提交**。
- 每个版本都是真实的一步：每一个设计与修改都来自**日常使用的实测**——用着不舒服就记录，量出问题就改，改完就发版。
- 更新日志公开可查，没有空转版本号、没有为发而发。

## 🏗️ 这是一次重建，不是加功能

RinCore 不是"RikkaHub 加了些功能"。核心是被日常使用推着重做出来的：

- **单一信源（SSOT）** —— UI、域名列表、工具注入、Prompt 全部派生自同一个设置源，任何视图只读同一份数据，逐层漂移在机制上不可能发生。
- **网络与缓存，按失败数据重写** —— SSE 断流指数退避重试、挂起看门狗、纯 HTTP/1.1 传输（弱网失败的真根因）、TCP+TLS 连接预热压首字延迟、断流续传、缓存指纹诊断（精确告诉你 DeepSeek 前缀缓存在哪一环断了）。中断与静默被杀，全部从根上修。
- **压缩机制重做** —— 不再按固定条数截断。保留边界按对话轮 + token 数（60%）定位、四舍五入到最近的整轮；绝不压缩你刚发的内容，且按下压缩必然真正压缩到东西。
- **成本从设计上降低** —— 工具区分层使冷启动从 100K+ 降到约 10K，请求前缀保持稳定，Provider 的 Prompt 缓存持续命中。

## ✨ 为什么选 RinCore —— 引擎级的差异

- **工具矩阵分层管理（成本杀手）** —— 400+ 工具按**工具区**归类，经 `invoke_tools` 按需加载，不再把全部工具塞进每个请求。工具矩阵全程可视化：新建/删除工具区、调整工具归属、按区计数、开发者对照（信源一致性自校验）。
- **完整 MCP，含 STDIO** —— HTTP / Streamable HTTP / SSE / **STDIO** 全支持。STDIO 服务器在沙箱工作区以进程方式启动（设备端无需 Python）；工具声明静态化，连接波动不影响请求前缀与缓存；OAuth 令牌透明刷新。
- **插件与技能** —— `ecosystem/plugins` 即装即卸；技能以一等工具存在（`skill__名称`）。请求顶层只放行批准的框架工具与用户豁免工具，其余一律藏在 `invoke_tools` 之内。
- **手机 Agent，划好安全线** —— 值得搬的 Agent 能力几乎全部移植并在设备端运行：proot Linux 工作区、文件管理（批量/归档/读写/下载）、浏览器、媒体播放、闹钟、日历、电量、真实定位 + 地图、剪贴板、TTS、通知、屏幕常亮、定时任务、交互式流式输出、需要时向你确认。**高风险行为——替你操作屏幕（点击/滑动）——明确不做**。Agent 处理的是你的数据与文件，不是你的屏幕。
- **OpenCode / OpenCode Zen 专项适配** —— 看门狗、无 `[DONE]` 流式完成判定、模型定义、推理模式对齐，上游打不顺的这里能跑。
- **容量随时可见** —— 用量面板：多密钥卡片、实时余额、剩余时间倒计时、精确重置时段，额度心里有数。同时提供便捷的容量查询入口。
- **一个软件，多份工作** —— 常规配置之后，图片生成、工作区数据分析、文档生成与导出、学习助手模式，与核心聊天体验一起扛。
- **一整打体验级修复** —— 延迟自动回复（消息先排队，模型不会打断你输入）、消息多版本编辑、记忆 ID 时间戳化、时间感知调度、液态玻璃输入、可复现崩溃日志，等等。

## 🎨 能力传承

**继承自原版 RikkaHub（保留且可用）：**

Material You + 深色模式 · 多 Provider（自定义 API/地址/模型，OpenAI/Anthropic/Google 兼容）· 多模态输入（图片/文本/PDF/DOCX）· proot Linux 工作区 · Web 多端 · MCP · Markdown（代码高亮/LaTeX/表格/Mermaid）· 消息分支 · 多引擎搜索（Exa/Tavily/Zhipu/LinkUp/Brave/Perplexity 等）· Prompt 变量 · 配置二维码导入导出 · 助手定制 · 类 ChatGPT 记忆 · AI 翻译 · 自定义请求头与请求体 · Silly Tavern 角色卡导入。

**自 Agent 系移植（设备端运行，去掉高风险项）：**

设备工具——闹钟、日历、电量、定位与地图、媒体播放与扫描、通知、剪贴板、TTS、屏幕常亮、系统意图 · 文件管理（批量与归档）· 定时任务 · 内置浏览器/网页抓取 · 跨对话读取 · 时间戳记忆 · 交互式流式工具输出。

*（替用户点击/滑动屏幕的屏幕控制，是我们不越的那条线。）*

## 🛠️ 构建

使用 [Android Studio](https://developer.android.com/studio) 开发。

技术栈： [Kotlin](https://kotlinlang.org/) · [Jetpack Compose](https://developer.android.com/jetpack/compose) · [Koin](https://insert-koin.io/) · [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) · [Room](https://developer.android.com/training/data-storage/room) · [Coil](https://coil-kt.github.io/coil/) · [Material You](https://m3.material.io/) · [OkHttp](https://square.github.io/okhttp/) · [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)

> [!TIP]
> 本地构建需要 `app` 目录下存在 `google-services.json`。

## 🔧 维护与支持

RinCore 处于活跃维护中，欢迎 Issue 与 PR。每一个被报告的问题都会进入更新日志并转化为回归防线。优化与适配持续进行。

## 📋 更新日志

近期大版本（倒序）：

- **v4.8.116** — 浏览器工具整链重写：导航结算等待、导航失败可见（ERR/超时 + throw_on_error）、React/Vue 受控输入原生 setter、页面状态语义、同批并行互斥、截图统一落工作区（沙箱直读 + 气泡可显示）
- **v4.8.115** — 工具产图渲染地址统一：全链单一形态（编码 file://）+ 单一解码点（Coil 认领层）+ 产出/提取单一来源
- **v4.8.114** — 网络连接失败可见化：「自动重试中（第 N 次）」+ 终态文案可操作化（联网控制/代理/切网指引）
- **v4.8.113** — 工具产图「气泡内直接可见」：file:// 私有目录放行 + render_urls 统一 + render_markdown 直贴 + 工具段缩略图 + 占位复读根治
- **v4.8.112** — 静默中断完成门五通道全覆盖 + 分享/拍照加固 + /@ 缓存根治 + 英文诱导强化
- **v4.8.111** — 切换助手「停留」终版：只切助手、零导航
- **v4.8.110** — 需求单全单（B1-B6）+ 工具并行调用对齐 Claude Code
- **v4.8.109** — XLSX 上传异常 + 降级弹窗 + 缓存连续性三合一根治
- **v4.8.108** — 子代理预算误熔断根治
- **v4.8.107** — 「上传图片后缓存全崩」对账原版后根治
- **v4.8.106** — 删除「工具已完成，继续生成中…」+ 探活门兜底回调
- **v4.8.105** — 工具↔输出「无感衔接」：预探活 + 门零等待 + TTFT 分解埋点
- **v4.8.104** — 抽屉去「功能折叠」+ 速览页新入口 + 点空白返回 + 极简模式工具铁闸
- **v4.8.103** — VideoGen 崩溃修复 + 视频生成回归与图像生成同流程
- **v4.8.102** — 视频生成真做（Google Veo 直连）+ 渲染提速双管
- **v4.8.101** — 渲染断链修复（officecli 产物契约对齐）+ 视频生成页对齐图像生成
- **v4.8.100** — 文档渲染缺口补齐（officecli 真渲染接入）+ 视频生成功能
- **v4.8.99** — 线程体系改革：AppDispatchers 单一事实源 + 沙箱进程族隔离池
- **v4.8.98** — 文档渲染真实形态 + 提速 + 一体化四件套（PPT 画布真尺寸根治）
- **v4.8.97** — 全库深度优化批：死代码清理 + 缓存重中之重
- **v4.8.96** — MCP stdio 连接回归修复
- **v4.8.95** — GoogleProvider 请求头补全 + 三语言键补齐 + 标题模型精确报错
- **v4.8.94** — 原版 2.5.6 全量移植：chart_display 图表工具链 + MCP OAuth/$ref 修复
- **v4.8.93** — 抽屉入口恢复 + 统计页复刻 + 重复入口清理
- **v4.8.92** — 移除恢复出厂（误触风险）+ /@ 选择器区可选&来源标签 + 单对话记忆 + 子代理预算复核
- **v4.8.91** — 上传码时间码化（东八区、入文件名）+ 本对话上传清单注入 + 回前台即热连接
- **v4.8.90** — /@ 工具矩阵选择器（三级下钻）+ 上传码直取 + 工具池 UI/模型同口径
- **v4.8.89** — 工具矩阵写链重构：唯一事务写入口，修「假成功/假失败」
- **v4.8.88** — 预设能力（physics-tutor 出厂技能）+ 子代理预算 + 通知分流 + 取消真停生成
- **v4.8.87** — 工具矩阵架构重写：身份与路径分离 + 唯一写入口
- **v4.8.86** — 流式渲染/缓存重构：MarkdownStream 引擎，消除每分片全量重解析
- **v4.8.85** — 极简模式（零工具注入）+ 工具归属自由化
- **v4.8.84** — 工具矩阵：模型管理能力补全（manage_zone 四动作）+ 四修
- **v4.8.83** — 工具矩阵彻底重写：取消内置/自定义分裂，统一声明式工具区
- **v4.8.82** — 编译日志可读性：shell 头尾双保留，报错不再被截断扔掉
- **v4.8.81** — 能力模块一键清空（MCP / Skill）
- **v4.8.80** — MCP schema 悬空 $ref 修复 + parameters null 兜底
- **v4.8.75** — ask_user 折叠豁免 + 等候期直发修复
- **v4.8.72** — 沙箱后台任务原语（workspace_job）+ 超时放宽（600s/700s）
- **v4.8.67** — 渲染路径回滚（回归 v4.8.64 稳定形态）+ 过程折叠范围修正

## 🤝 致谢

- **desginus** —— 设计、开发与维护
- **Claude** —— 模型侧协作者

## 📄 许可

[许可协议](LICENSE)