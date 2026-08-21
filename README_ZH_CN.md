<div align="center">
  <h1>RinCore</h1>

[![Build](https://img.shields.io/github/actions/workflow/status/desginus/rincore/build.yml?label=构建&logo=github)](https://github.com/desginus/rincore/actions)
[![Last commit](https://img.shields.io/github/last-commit/desginus/rincore?logo=git)](https://github.com/desginus/rincore/commits)
[![Version](https://img.shields.io/badge/版本-v3.8.29-blue)](https://github.com/desginus/rincore/releases)
[![License](https://img.shields.io/badge/许可-segmented_dual-cyan)](LICENSE)

Rika 系原生 Android 大模型聊天客户端的深度改造版。以低成本调用、高可控性、真正可扩展为目标，绝不只是给原版套了一层皮。

> RinCore 作为 [RikkaHub](https://github.com/re-ovo/rikkahub) 的独立分支持续维护。它继承了原版的理念（原生 Android、Material You、多 Provider），再往前走一步：重构整体架构、修复系统性 Bug、加入工具域分层管理、MCP STDIO 支持、插件系统与智能压缩。

English | [简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

</div>

## 🚀 下载

RinCore 每次推送都会构建。两种方式获取最新 APK：

1. **GitHub Releases（推荐）** —— `nightly` 预发布版每晚重新发布，始终指向最新每日构建：

   🔗 <https://github.com/desginus/rincore/releases>

2. **GitHub Actions 构建产物** —— 每次 CI 通过都会产出即时 APK。打开最新一次工作流运行，展开 `rincore-release` 产物即可直接下载：

   🔗 <https://github.com/desginus/rincore/actions>

   产物直链（构建通过后即可用）：
   `https://github.com/desginus/rincore/actions/runs/<run-id>/artifacts/<artifact-id>`

下载 APK 直接安装即可，无需任何商店。

## ✨ 为什么选 RinCore

我们相对上游做了什么：

- **整体架构优化** —— 模块职责重新梳理，UI、域名列表、工具注入、Prompt 全部派生自同一个信息源头（SSOT），任何视图只读同一份数据，杜绝层层漂移。

- **大量 Bug 修复** —— 流式中断、静默失败、冷启动回退、设置不落盘、界面抽动等，全部从根因级别修复，而不是打补丁。

- **完整 MCP 支持，含 STDIO** —— STDIO 服务器在沙箱工作区内以进程方式启动（设备端无需 Python 依赖）；工具声明静态化、连接状态可见、OAuth 令牌自动刷新。

- **插件系统** —— 从 `ecosystem/plugins` 安装/卸载插件，插件技能与 MCP 桥接清晰分离。

- **工具域分类分层管理** —— 工具按域归类，通过 `invoke_tools` 按需加载，而不是把全部工具塞进每个请求。冷启动 token 从全量注入的 100K+ 降至约 6K，调用成本巨幅下降，同时显著提升 Prompt 缓存命中率。

- **更智能的压缩模式** —— 压缩不再按固定条数截断。边界按对话轮 + token 数（60%）定位，四舍五入到最近整轮；绝不压缩刚发出的内容，且按下压缩必然真正压缩到东西。

- **延迟自动回复** —— 开启 `deferAutoReply` 后，发消息先排队、不立即触发模型回复，你的消息不会发到一半就被打断，回复时机由你掌握。

- **多个小功能** —— 密钥用量统计页（多密钥卡片 + 剩余时间倒计时）、断流自动重试（快速失败）、TCP+TLS 连接预热降低首字延迟、上下文压缩位点管理（最多 3 个，可查看摘要可恢复）、工具对照一致性校验、记忆 ID 时间戳化、崩溃日志持久化、液态玻璃输入框、消息多版本编辑。

## 🎨 核心特性（继承并保留）

- Material You 设计 + 深色模式
- 多 Provider 支持：自定义 API / 地址 / 模型（兼容 OpenAI、Google、Anthropic 系）
- 多模态输入：图片、文本、PDF、DOCX 等
- Workspace：proot 的 Linux 代理环境
- Web 多端使用
- MCP 支持（HTTP / SSE / STDIO）
- Markdown 渲染：代码高亮、LaTeX 公式、表格、Mermaid
- 消息分支
- 多搜索引擎（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity 等）
- Prompt 变量、AI 翻译
- 配置二维码导入导出
- 助手定制、类 ChatGPT 记忆
- 自定义 HTTP 请求头与请求体
- Silly Tavern 角色卡导入

## 🛠️ 构建

使用 [Android Studio](https://developer.android.com/studio) 开发。

技术栈：

- [Kotlin](https://kotlinlang.org/) —— 开发语言
- [Jetpack Compose](https://developer.android.com/jetpack/compose) —— UI
- [Koin](https://insert-koin.io/) —— 依赖注入
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) —— 偏好存储
- [Room](https://developer.android.com/training/data-storage/room) —— 数据库
- [Coil](https://coil-kt.github.io/coil/) —— 图片加载
- [Material You](https://m3.material.io/) —— 设计
- [OkHttp](https://square.github.io/okhttp/) —— 网络
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) —— 序列化

> [!TIP]
> 本地构建需要 `app` 目录下存在 `google-services.json`。

## 🙋 参与贡献

RinCore 是独立开源项目，欢迎提 Issue 与 PR。大改动请先开 Issue 讨论方案。

## 📄 许可与致谢

- [许可协议](LICENSE)
- 基于 [RikkaHub](https://github.com/re-ovo/rikkahub)（作者 re-ovo 及贡献者）二次开发。感谢 Rika 系客户端为我们打下的基础。
