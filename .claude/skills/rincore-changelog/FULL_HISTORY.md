# RinCore 全量版本历史归档（v3.6.0 → v4.8.53）

> 自动整理自 git 提交信息（2026-09-26 生成于 rincore-src 全量 log；448 个版本条目）。
> 排序: 新 → 旧；每条含 版本号 + 提交短 SHA + 日期 + 提交信息摘要。
> 用途: SKILL.md 为人工整理的高密度摘要; 本文件为完整逐版本清单（追查“某版本改了什么/何时引入”时按此对账）。
> 注: v3.6.x 存在两轮（07 月第一轮已废弃；08 月第二轮为正式线）——条目按提交信息原样保留。
> 注: v4.3.x 后存在版本跳号（v4.4 未使用）与并存修正提交（同版本名多 commit）——以 SHA 为准。

### v4.8.x 系列

- **v4.8.52**（763099082·2026-09-26）: 4.8.52 — 背景裁切 + 动态背景×壁纸共存
- **v4.8.51**（7f90b1125·2026-09-26）: 4.8.51 — 四项: 压缩蓝标阈值 / 缓存命中率三修 / 壁纸启动黑屏 / CI 一推一建
- **v4.8.50**（130aa8253·2026-09-26）: 4.8.50 编译修复 — ChatPage 补 milliseconds/collect 两处 import (不升版本)；4.8.50 — 预热管线 v1 (铺展架构) + 追赶线残留终审 + 设计文档
- **v4.8.49**（7b894e424·2026-09-26）: 4.8.49 编译修复 — 缓存回植锚点吞掉 MarkdownParseResult 收尾括号 (不升版本)；4.8.49 — 回植批次二: 渲染缓存 + vendored 库 + 图片版本键 (回植完成)
- **v4.8.48**（912aa322e·2026-09-26）: 4.8.48 — 回植批次一: 桥恢复 + 消息路径四项回归 (冷态诊断后的第一批)
- **v4.8.47**（e7171e323·2026-09-26）: 4.8.47 — 诊断: 沙箱桥启动禁用 (卡顿归因裁决版)
- **v4.8.46**（d5beecfa4·2026-09-25）: 4.8.46 — 消息路径最后残留清零: ChainOfThought + HighlightCodeBlock 回 4.7.26
- **v4.8.45**（ea145f87d·2026-09-25）: 4.8.45 — 重做第二发: 项目包回归 (以 4.8.43 旧码为参考适配新基线)
- **v4.8.44**（04e7f36f9·2026-09-25）: 4.8.44 — 基线重建第一发: 4.7.26 + 绝对无关集 (撤聊天页组合路径全部嫌疑)
- **v4.8.43**（9e9717358·2026-09-25）: 4.8.43 — 渲染提速批次回滚 (缓存/预热撤除 + 渲染库回退 fork) — 控制变量实验第一发
- **v4.8.42**（7873a66cd·2026-09-25）: 4.8.42 — 滚动历史"抽动/卡"专项: 预热全量扩容 + 缓存容量提升 (对照 4.7.26)
- **v4.8.41**（dab67a49b·2026-09-25）: 4.8.41 — 渲染层回滚至存档点1 纯净态 (撤 v4.8.38 检查点)
- **v4.8.40**（87c2944ca·2026-09-25）: 4.8.40 — 移植原版 2.5.4 (适配性重写: 思考档位 / 流解码 / 注册表 / 三处 UI)
- **v4.8.39**（01bb6b988·2026-09-25）: 4.8.39 — OpenCode+Responses+grok 上游瞬时错误场景分流 (转重试链)
- **v4.8.38**（3046ae177·2026-09-25）: 4.8.38 — 渲染回滚至存档点1 + 心跳线程修复 + CPU 空转修复；4.8.38 — 渲染机制回滚至存档点1 + 心跳线程修复
- **v4.8.37**（ac051320d·2026-09-25）: 4.8.37 — 卡死修复: 渲染更新从"每次上屏"改为"合并窗口+自适应节流"
- **v4.8.36**（4f115a9b6·2026-09-25）: 4.8.36 — 流式显示速度修复: 渲染解析调度重写 (取消风暴 → 单飞行最新推进)
- **v4.8.35**（d6da9490f·2026-09-25）: 4.8.35 — 保活/缓存类参数全面放宽 (用户定版: 时间大幅延长)
- **v4.8.34**（6e33e00d3·2026-09-25）: 4.8.34 — 当前 provider 心跳保活 + 发送链主线程卸载 (工具后恢复专项)
- **v4.8.33**（f9bbe32f4·2026-09-25）: 4.8.33 编译修复 — CC streamTextRaw 的 requestBody 引用 (onFailure 诊断仍用)；4.8.33 — 请求体瘦身 + 每轮构建/图片编码卸载 (TTFT 与工具后等待专项)
- **v4.8.32**（5b1524255·2026-09-25）: 4.8.32 — 运行稳定性/卡顿专项 (五项) + 生成中禁压缩
- **v4.8.31**（4b26b8b96·2026-09-24）: 4.8.31 编译修复 — ast-grep 示例的 $ 未转义 (Kotlin 字符串模板误插值)；fix+feat: 4.8.31 — PDF 回归原数据直读 + 编程工具扩展 (ast-grep / yq)
- **v4.8.30**（f003ee199·2026-09-24）: 4.8.30 — 文档工作哲学落地: 看原貌 / 就地编辑 / 不重建
- **v4.8.29**（f28519ce9·2026-09-24）: 4.8.29 — `pdfmd` 工具 (PDF→Markdown, 含扫描件 OCR) + 文档工具链收尾
- **v4.8.28**（ae2425661·2026-09-24）: 4.8.28 补 — toolbox workflow hint 更新 (补 echo 包裹, 防运行时命令误执行)；4.8.28 — 深色选中态修复 + .doc 工具链强化 (setarch/水印实战固化)
- **v4.8.27**（829a39f03·2026-09-24）: 4.8.27 — 紧急: 对话显示为空/跳包根因根治 (加载窗口期误判)
- **v4.8.26**（a62eb24c0·2026-09-24）: 4.8.26 — 项目包交互修正 + 选区持久化 + 空对话归属跟随
- **v4.8.25**（4faccd01a·2026-09-24）: 4.8.25 — 项目包栏精简 + CWD 选择器空间限定 + 全链路一致性核验
- **v4.8.24**（b2dffcaec·2026-09-24）: 4.8.24 编译修复 — ChatDrawer 缺 getCurrentAssistant 扩展 import；4.8.24 — 文件夹 → 项目包 (功能大改动) + 弹窗深色修复
- **v4.8.23**（2723a1a57·2026-09-24）: 4.8.23 — 启动崩溃根治 (通知渠道缺 name) + 渠道创建全面防护
- **v4.8.22**（eee9edc05·2026-09-23）: 4.8.22 — 后台连接稳定性最高级强化 (三项)
- **v4.8.21**（3db001fb6·2026-09-23）: 4.8.21 — 流式能效深挖 (WifiLock 降级 + Jsoup 主线程卸载)
- **v4.8.20**（f8cc497c0·2026-09-23）: 4.8.20 — 后台任务可持续性: 生成保活前台服务
- **v4.8.19**（0fc47c0d6·2026-09-23）: 4.8.19 — 高负载热点深挖 (四处热点消除)；versionName 修正 4.8.19 → 4.8.18；4.8.19 — checkFilesDelete 快路径 (流式高频路径减负)
- **v4.8.18**（e0d6d3a0b·2026-09-23）: 4.8.18 编译修复 — toLowerCase() 弃用错误 (3 处)；4.8.18 — 主题调色盘升级 (HSL 三通道全开)；4.8.18 — 渲染库升级 (vendored): 上游 0.7.14 + CJK 修复合并
- **v4.8.17**（dd509e752·2026-09-23）: 4.8.17 编译修复 — MarkdownNew 常量补齐 (同包文件级 private 不可跨文件)；4.8.17 — 词内下划线渲染修复 (M_O / xF_y 类公式文本) + MarkdownNew 正则预编译
- **v4.8.16**（f23781ce9·2026-09-23）: 4.8.16 — 死代码清理 (compression 包整包, 358 行)
- **v4.8.15**（34687fe9d·2026-09-23）: 4.8.15 — 流式消息更新快路径 (updateCurrentMessages 引用同一性短路)
- **v4.8.14**（1818f151e·2026-09-23）: 4.8.14 — 长时间多工具调用发热修复 (per-step 重复计算消除)
- **v4.8.13**（214ef5b75·2026-09-23）: 4.8.13 — Phase 1 第三刀: ChatService 保活体系独立
- **v4.8.12**（ecb88cbc9·2026-09-23）: 4.8.12 — 大重构启动: Phase 0 标注归一 (552 文件) + Phase 1 拆解第一、二刀
- **v4.8.11**（36f23b101·2026-09-23）: v4.8.11 — 输入框玻璃自愈扩展 + 设置返回跳级修复 + 标注第二梯队
- **v4.8.10**（17517e90b·2026-09-23）: v4.8.10 — 应用裂变与标注工程 (APP_MAP 地图 + 20 核心文件域标注) + 分享加载条
- **v4.8.9**（21e3bcf8f·2026-09-22）: v4.8.9 — 压缩提示条 z-order 修复 + 压缩中禁发 + 主题色相渐变条
- **v4.8.8**（c88fa932a·2026-09-22）: v4.8.8 编译修正 — CompressContextDialog 恢复 Job import (import 清理误删, 签名仍引用)；v4.8.8 — 压缩弹窗根治 (确认即关, 无压缩态弹窗) + 主题自定义改预设色板
- **v4.8.7**（78e028eb2·2026-09-22）: v4.8.7 — 压缩管理上移 + 预设主题「默认」(莫兰迪紫)
- **v4.8.6**（8782a4c61·2026-09-22）: v4.8.6 — MCP 重启断联根治 + 压缩弹窗非阻塞化
- **v4.8.5**（bf26291af·2026-09-22）: v4.8.5 — 文件夹完整导出 (内容零丢失零破损, 根因根治)
- **v4.8.4**（25c571998·2026-09-22）: v4.8.4 — 发送键位置修正 + 三处状态蓝色提醒 + 文件传输加载动画
- **v4.8.3**（8259282bf·2026-09-22）: v4.8.3 — 延时回复交互重构 + CWD 名称净化 + 发送/思考键互换
- **v4.8.2**（88b87dc23·2026-09-22）: v4.8.2 — 地址渲染图缓存修复 (模型改图覆盖后仍显示旧图)
- **v4.8.1**（dac169d15·2026-09-22）: v4.8.1 — 进入对话首帧卡顿 + 启动黑屏 (双修复)
- **v4.8.0**（adcc81ab4·2026-09-22）: v4.8.0 编译修复 — ProviderManager 实例获取 (class 非 object)；v4.8.0 — 沙箱穿透: 软件-沙箱一体化桥 (SandboxBridge + rin 命令)

### v4.7.x 系列

- **v4.7.26**（59b755fb7·2026-09-22）: v4.7.26 — Markdown 渲染完全对齐原版 RikkaHub (撤除分段/缓存/占位方案)
- **v4.7.25**（4b98ef624·2026-09-22）: v4.7.25 — Markdown 解析全进程缓存 ("只渲染一次", 消除进入对话/滚动历史抽动)
- **v4.7.24**（42be82fcd·2026-09-22）: v4.7.24 — 图片数量限制精细化管理 (仅 GLM 网关限 6 张, 其他模型放开)
- **v4.7.23**（9fdea58af·2026-09-22）: v4.7.23 — 五 Bug 修复 (分享文档流 / CWD 选择器 / 应用文件默认目录 / Markdown 渲染负载)
- **v4.7.22**（05c0dfafc·2026-09-22）: v4.7.22 编译修复 + #1586 keep 实现恢复 (CI 首轮反馈闭环)；v4.7.22 — 统一使用体验 (玻璃基建/防错/返回层级 全量落地)
- **v4.7.21**（f2669b12d·2026-09-22）: v4.7.21 — 输入栏渲染回归原生款 (haze rc02→beta01, 消除'格栅式'变化)
- **v4.7.20**（523aa6169·2026-09-22）: v4.7.20 — 代码能力增强 (吸收 Codex/OpenCode 开源精华)
- **v4.7.19**（270d5e44d·2026-09-22）: v4.7.19 — 沙箱文档工具链升级 (OfficeCLI 集成 + LibreOffice 治理)
- **v4.7.18**（415bd3f89·2026-09-22）: v4.7.18 — 断流重试可靠性三件套 (连接池清理 + 网络切换监听 + WifiLock)
- **v4.7.17**（13f52c160·2026-09-22）: v4.7.17 — SoC 发热修复 (visualTransforms 前缀缓存) + task_tool 卡片全程显示
- **v4.7.16**（b3b6fa535·2026-09-21）: v4.7.16 — 域管理三坑修复 (内置域 update 放开 / rename 父级保留 / 删除防御)
- **v4.7.15**（c3d4febcf·2026-09-21）: v4.7.15 — 域的两个条目正式定性 + 展示格式统一
- **v4.7.14**（ec516750e·2026-09-21）: v4.7.14 — 已加载域工具注入请求 schema (喂到嘴边)
- **v4.7.13**（1b54529f5·2026-09-21）: v4.7.13 — 防吸硬指令升级 + 域返回文本强化
- **v4.7.12**（aa6be4738·2026-09-21）: v4.7.12 — 序列思考工具彻底删除 + 管理三件套意图门控
- **v4.7.11**（465adee1f·2026-09-21）: v4.7.11 — 思考工具 name/参数错位检测 + 背景效果回滚
- **v4.7.10**（139a0e507·2026-09-21）: v4.7.10 — 防吸机制 + AGENTS.md 缓存(卡顿修复) + 三件套回滚顶层
- **v4.7.9**（8639d93d4·2026-09-21）: v4.7.9 — 管理工具吸住根修: 移出顶层 + 堆栈净化
- **v4.7.8**（a6c1e93ba·2026-09-21）: v4.7.8 — GLM 管理工具吸住修复 + 热路径日志清理
- **v4.7.7**（1d38515df·2026-09-21）: revert: v4.7.7 — 流处理链回滚到 v4.5.27 (用户实测正常版)
- **v4.7.6**（6670d4181·2026-09-21）: v4.7.6 编译修复 — 变量名 chunkId (非 id)；v4.7.6 — GLM 中断根因: tool_calls 空 id fallback 生成对齐原版
- **v4.7.5**（3c6c7c55f·2026-09-21）: v4.7.5 编译修复 — TraceLogger.log 签名 (三 String→两参)；v4.7.5 — 请求体 messages 取证 (GLM 空回复定位)
- **v4.7.4**（9eba31466·2026-09-21）: v4.7.4 — GLM 空回复根因: buildAssistantMessageJson content="" 对齐原版
- **v4.7.3**（bc51604bc·2026-09-21）: v4.7.3 — 空回复判定根修 (size 对比) + 版本第三位递增
- **v4.7.2**（55b8c33e7·2026-09-21）: v4.7.2 — GLM 空回复根治: emptyResponse 判定加非空条件 + 重试预算 1→2 + 序列取证；v4.7.2 编译修复 — link 幂等检查 JsonPrimitive 类型比较；v4.7.2 — extended_memory_tools link/move 补齐 (复测报告驱动)；v4.7.2 编译修复 — finishReason 局部变量声明恢复 (let 链改回 val)
- **v4.7.1**（c94964458·2026-09-21）: Revert "fix: v4.7.1 断流判定根治 — usage 弱信号掩盖的静默截断进重试链"；v4.7.1 编译修复 — write/show 快照调用 cwdRel 可空传参 (orEmpty)；v4.7.1 工具链酌情优化 — 10 条清单中 7 项小改进 (不过激)；v4.7.1 断流判定根治 — usage 弱信号掩盖的静默截断进重试链
- **v4.7.0**（6f0561149·2026-09-20）: v4.7.0 — 原版 2.5.3 定向移植 (2.5.2 高价值项一并合入)

### v4.6.x 系列

- **v4.6.7**（0e647dcb7·2026-09-20）: v4.6.7 记忆打通极简重写 — 增强记忆工具直连原生记忆 (回滚复杂实现)
- **v4.6.6**（6af7f263f·2026-09-20）: v4.6.6 编译修复(二) — 增强记忆持久化改手写 JSON 编解码 (零 serialization 插件依赖)；v4.6.6 编译修复 — MemStore 可见性 (kotlinx-serialization 不为 private 类生成 serializer)；v4.6.6 紧急止血 — 启动闪退根治 (Room 迁移链回退 → 文件存储)
- **v4.6.5**（b9d31942e·2026-09-20）: v4.6.5 编译修复(二) — JsonArray import / AppModule 逗号 / PaddingValues plus import；v4.6.5 编译修复 — memory 桥 runBlocking 包裹 (同步 hostCall 调 suspend 仓库)；v4.6.5 CI 修复 — Room 迁移改手写 (AutoMigration 缺 30.json schema)；v4.6.5 记忆交火 + 默认开启 + 框架工具页 (三任务)
- **v4.6.4**（e32a0ce3c·2026-09-20）: v4.6.4 生态与插件综合统一 + 运行兼容强化 (根治'显示用不了')
- **v4.6.3**（8b2b0ee68·2026-09-20）: v4.6.3 内置包播种记忆 — 卸载不再复活 + 插件页去混淆
- **v4.6.2**（45d1fe275·2026-09-20）: v4.6.2 生态模块 — Operit 生态页拆入 RinCore (独立渲染管线, 双并行)
- **v4.6.1**（b0adbace5·2026-09-20）: v4.6.1 编程能力增强 — Read 分页/行号 + Grep/Glob 双件套 + append

### v4.5.x 系列

- **v4.5.40**（2cedb91d9·2026-09-20）: v4.5.40 — 崩溃修复 + UI 恢复原样 + 生态入口收纳
- **v4.5.39**（cad95046e·2026-09-20）: v4.5.39 宿主环境共享层 — require 缺口根治 (系统性, 非补丁)
- **v4.5.38**（d3a47d6ef·2026-09-20）: v4.5.38 DSL 渲染层 — 三种面板形态全覆盖 (所有工具都好)
- **v4.5.37**（76a9fba0f·2026-09-20）: v4.5.37 根治 — 跨边界类型转换全量归一化 (不打补丁)
- **v4.5.36**（345a35276·2026-09-20）: v4.5.36 — 面板加载类型错误 + DSH 发现链 + 抽屉精简
- **v4.5.35**（4b5cfbabf·2026-09-20）: v4.5.35 补全注册桩 — toolbox/ipc/钩子/Java 桥 + 兜底扫描双形态；v4.5.35 侧边栏机制移植 — main.js 注册发现 + 面板分组
- **v4.5.34**（d210e22dc·2026-09-20）: v4.5.34 CI 修复 — 注释嵌套/路由返回/import；v4.5.34 插件 UI 渲染页 — Operit 面板原生渲染 (WebView 桥)
- **v4.5.33**（26684defc·2026-09-20）: v4.5.33 插件页分类融合 + 工具清单弹窗 + MCP 文案
- **v4.5.32**（2ec6607f6·2026-09-20）: v4.5.32 插件市场修复 — list 字段类型宽容 (实机报错根治)
- **v4.5.31**（7241385e2·2026-09-19）: v4.5.31 收尾 — 文件标注统一 + unused import 清理；v4.5.31 审计修复批次2 — 深度复查 6 项 (含 2 个运行时崩溃级问题)；v4.5.31 审计修复批次 — 全链路复查发现的 5 个问题；v4.5.31 修复批次 — 编译转义 + 4 个历史断言测试对齐实现
- **v4.5.30**（baf2dcc11·2026-09-19）: v4.5.30 CI 修复 — UUID 类型对齐 kotlin.uuid.Uuid；v4.5.30 岔路口计划·阶段3a — MCP 一键导入 + Skill 仓库导入
- **v4.5.29**（7de53a5a2·2026-09-19）: v4.5.29 测试债务清理 — 30 个历史失败测试对齐 v4.x 架构；v4.5.29 岔路口计划·阶段2 — Operit 脚本运行时 (QuickJS 最小宿主面)
- **v4.5.28**（e4fc673b6·2026-09-19）: v4.5.28 CI 修复2 — LazyListLayoutInfo.totalItems → totalItemsCount；v4.5.28 CI 修复 — MarketModels.kt 注释内 v2/* 被 Kotlin 当嵌套块注释开始；v4.5.28 岔路口计划·阶段1 — Operit 云商城接入 (浏览+下载+安装落盘)
- **v4.5.27**（0ade94a08·2026-09-19）: v4.5.27 - CWD becomes a physically enforced per-assistant sandbox scope
- **v4.5.26**（8e525ee6f·2026-09-19）: v4.5.26 - share cold-start + tool discoverability + CWD exclusive scope
- **v4.5.25**（51fdcb1b1·2026-09-19）: v4.5.25 - sandbox gets apply-patch (codex-style editing) + office-check (deliverable QA)
- **v4.5.24**（fc9f6f6be·2026-09-19）: v4.5.24 - built-in office-edit tool injected into workspaces (byte-preserving Office editing)
- **v4.5.23**（afd053c41·2026-09-18）: v4.5.23 - workspace CWD re-scoped from conversation-level to assistant-level
- **v4.5.22**（30dde6cd1·2026-09-18）: v4.5.22 - CWD persistence hardening: write-through with read-back verification
- **v4.5.21**（ea7012971·2026-09-18）: v4.5.21 - task_tool anti-idle: no-op rejection, stall alerts, action linkage
- **v4.5.20**（addf86c1f·2026-09-18）: v4.5.20 - multi-model silent interruptions misclassified as complete
- **v4.5.19**（5b152c669·2026-09-18）: v4.5.19 - same-tool fuse off, image budget vs TCP payload, CWD persistence
- **v4.5.18**（c15b686f9·2026-09-18）: v4.5.18 - illegal assistant message from model output (content or tool_calls must be set)
- **v4.5.17**（ef6aad169·2026-09-17）: v4.5.17 - OpenCode request mode (per-model protocol dispatch)
- **v4.5.16**（c2650b36f·2026-09-17）: v4.5.16 - download of workspace images, compress hang, defer gate, render diagnostics
- **v4.5.15**（db71cc140·2026-09-16）: v4.5.15 - actual schema fix (previous push carried version bump only)；v4.5.15 - tool parameter schema visible via invoke_tools output
- **v4.5.14**（61fa88368·2026-09-16）: v4.5.14 - domain tool execution chain + formula-context tilde
- **v4.5.13**（4f3d5b96c·2026-09-16）: v4.5.13 - tool timeout misclassified as user cancellation
- **v4.5.12**（65d63812b·2026-09-16）: v4.5.12 - stream completion attribution + constant top-level toolset
- **v4.5.11**（9417f8cc5·2026-09-16）: v4.5.11 - actual tilde fix (previous push carried version bump only)；v4.5.11 - tilde rendering: escape -> char substitution
- **v4.5.10**（f2bf88897·2026-09-16）: v4.5.10 - reasoning slider range restore + share pipeline hardening
- **v4.5.9**（550a5ec3a·2026-09-16）: v4.5.9 ci - SliderState needs newer material3; keep behavior-equivalent form；v4.5.9 - full reasoning audit across providers + 2.5.2 feature port
- **v4.5.8**（9bc1623ca·2026-09-16）: v4.5.8 - gate GLM thinking field to confirmed-supporting host
- **v4.5.7**（184949fda·2026-09-16）: v4.5.7 - GLM thinking switch root fix + folder export stream contract
- **v4.5.6**（4cb895f4c·2026-09-15）: v4.5.6 - audit closure: compat image mode skips budget marking entirely
- **v4.5.5**（8aef80095·2026-09-15）: v4.5.5 ci - import custom PaddingValues plus extension；v4.5.5 ci - restore line clobbered by flag cleanup regex；v4.5.5 - client settings page (upload modes) + global Beijing timezone + dead-code sweep
- **v4.5.4**（df3b9ca4d·2026-09-15）: v4.5.4 - per-segment hashes in cache fingerprint
- **v4.5.3**（352d2e497·2026-09-15）: v4.5.3 ci - scope and signature of tail memory injection；v4.5.3 - cache-bust eradication (memory tail-injection) + pure-Kotlin folder zip
- **v4.5.2**（8154f0fee·2026-09-15）: v4.5.2 - attachment naming integrity, silent abort marker, graceful length-truncation recovery, folder export/share
- **v4.5.1**（115565e44·2026-09-15）: v4.5.1 - length-truncated tool calls never execute; abort placeholder made unobtrusive
- **v4.5.0**（70cb0a803·2026-09-14）: v4.5.0 - OpenCode/CC adapter completion, final audit of this scenario

### v4.3.x 系列

- **v4.3.16**（e0634d762·2026-09-14）: v4.3.16 - empty-content defenses (gateway bricking scenarios)
- **v4.3.15**（1750dafff·2026-09-14）: v4.3.15 - unescape six corrupted string templates (audit sweep)
- **v4.3.14**（66f359f22·2026-09-14）: v4.3.14 - doc as exact workspace path (user verdict) + mention browser fixes
- **v4.3.13**（b66e6b2a9·2026-09-14）: v4.3.13 - rebuild truncation notice as plain concatenation；v4.3.13 - document content cap + 4xx terminal (no retry on semantic rejects)
- **v4.3.12**（60e21bd9a·2026-09-14）: v4.3.12 - remaining empty-text fallback in addNonAssistantMessage；v4.3.12 - tool-result images moved to trailing user message (BUG19)
- **v4.3.11**（e58f135ae·2026-09-14）: v4.3.11 - real part index in image budget slots + retry classifier reset
- **v4.3.10**（d861ebbac·2026-09-14）: v4.3.10 - scoped markedMessages to generateInternal only；v4.3.10 - image budget marking moved out of buildList (val reassign)；v4.3.10 - persistent image downgrade marks + history stability in fp
- **v4.3.9**（a586fcebc·2026-09-14）: v4.3.9 - session header host whitelist broke gateway sticky routing
- **v4.3.8**（7c7f138dc·2026-09-13）: v4.3.8 - image budget back to 8 (user decision, read_image loop retained)
- **v4.3.7**（10b047bb2·2026-09-13）: v4.3.7 - runCatching getOrElse requires lambda (compile fix)；v4.3.7 - image budget closed loop: read_image + adaptive budget
- **v4.3.6**（44ab54839·2026-09-13）: v4.3.6 - image budget guard + wire up dead CCImageCompat transformer
- **v4.3.5**（17841b636·2026-09-13）: v4.3.5 - component-level cache prefix fingerprint drift detection
- **v4.3.4**（75f6e9cf1·2026-09-13）: v4.3.4 - reasoning timer keeps running through the whole message
- **v4.3.3**（412291f8d·2026-09-12）: v4.3.3 - remove legacy name field from tool result messages
- **v4.3.2**（ebc0301f9·2026-09-12）: v4.3.2 - header watchdog 25s kills slow gateways (428-tool TTFT 30s+)
- **v4.3.1**（76772e40e·2026-09-12）: v4.3.1 - approval pending deadlock rootfix (auto-deny on timeout)
- **v4.3.0**（b6488b110·2026-09-11）: v4.3.0 - tiered test gate: compile hard-block + behavior signal；v4.3.0 - full test failure diagnostics (expected/actual in CI log)；v4.3.0 - test suite symbol audit: JsonArray.size property, contentOrNull import；v4.3.0 - restore class structure in ResponseAPIMessageTest (stray close brace orphaned half the tests outside the class)

### v4.2.x 系列

- **v4.2.4**（86806e6ae·2026-09-11）: v4.2.4 - tool arguments truncation rootfix
- **v4.2.3**（306450052·2026-09-11）: v4.2.3 — 工具结果图片误杀修复 (host 真实路径被流式短路误判)
- **v4.2.2**（dab08a967·2026-09-11）: v4.2.2 — token 裂段决定性根修 + 流式图片重组风暴短路
- **v4.2.1**（e5b47305a·2026-09-10）: v4.2.1 — 桥接层/消费侧四处 bug 修复 + v4.2.0 全量核验
- **v4.2.0**（b4e9e716f·2026-09-10）: v4.2.0 修正 — OpenAIProvider 转发层双重桥接消除 (内层已完成桥接)；v4.2.0 修正 — TextGenerationResult/OpenAIImpl 接口/桥接器三处适配；v4.2.0 — Provider 接口切换 StreamChunk 体系 (原版 2.5.x 架构同构)

### v4.1.x 系列

- **v4.1.7**（7a9d9f1d0·2026-09-10）: v4.1.7 修正 — Message.kt 补齐原版 2.5.1 扩展函数体系 (6 个)；v4.1.7 — 架构代差清除第一批 (ai/ui StreamChunk 体系入库)
- **v4.1.6**（4f55f7d12·2026-09-10）: v4.1.6 修正 — 运算符扩展 import 误删恢复 (plus/get/set 等 37 文件)；v4.1.6 — 屎山拉齐工程第一批 (标注/清理/地图)
- **v4.1.5**（1f219aead·2026-09-10）: v4.1.5 — 渲染体验全面对齐原版 + 柔光玻璃真模糊根修
- **v4.1.4**（5bcf6e117·2026-09-10）: v4.1.4 — 输出渲染/吐字/速度对齐原版 (SmoothStreamingText 平滑层删除)
- **v4.1.3**（8d8dbd450·2026-09-10）: v4.1.3 — OpenCode/CC 首包延迟定向根治 (TTFT)
- **v4.1.2**（955a79025·2026-09-10）: v4.1.2 补 — ChatInput 思考按钮门控与发送侧同源 (注册表合并 abilities)；v4.1.2 — 思考程度调控失效根修 (abilities 门控单点消除) + 4.1.1 版本号确认
- **v4.1.1**（b66b921d8·2026-09-10）: bump to 4.1.1；v4.1.1 紧急 — 上下文异常截断根修 + 压缩默认算法归位
- **v4.1.0**（f72e37039·2026-09-09）: v4.1.0 — CI 五轮修复: compatibility_mode 字符串 6 语言补齐；v4.1.0 — CI 四轮修复: getSelectedASRProvider/LocalFocusManager imports + imggen/workspace 字符串键名对齐原版；v4.1.0 — CI 三轮修复: queue 字符串补齐 / BuiltinToolUIs imports / ChatList 主签名 hazeState；v4.1.0 — CI 二轮修复: MIGRATION 注解位置 / Assistant 字段 / ChatService imports+suspend / MessageQueuePanel 重定位

### v4.0.x 系列

- **v4.0.16**（c477580c6·2026-09-09）: v4.0.16 存档；v4.0.16 — 补 WorkspaceRenderContent 缺少的 mutableIntStateOf/mutableFloatStateOf import；v4.0.16 — 回滚澎湃 OS4 流光玻璃 + 删除强兼容/CC图片兼容设置 + 工作区预览桥接渲染机
- **v4.0.15**（5b8917eaa·2026-09-08）: v4.0.15 存档；v4.0.15 — startMs 位置修正 (函数体内而非参数列表)；v4.0.15 — 长上下文首 token 延迟计时日志 + 消除 ClaudeProvider 大日志打印
- **v4.0.14**（c2a67a98e·2026-09-08）: v4.0.14 存档；v4.0.14 — buildJsonArray.add(JsonPrimitive) 类型修正；v4.0.14 — 本地图片链接渲染增强
- **v4.0.13**（4b6b18798·2026-09-07）: v4.0.13 存档；v4.0.13 — JsonPrimitive 包装 render_url (Map<String,JsonElement> 要求)；v4.0.13 — 图片落盘/读取自动注入标准本地渲染 URL (render_url)
- **v4.0.12**（4d0c410d1·2026-09-07）: v4.0.12 存档；revert: v4.0.12 — 仅回滚图片方向本地链接解析 (WorkspaceTools display_hint + 工具描述引导 / ZoomableAsyncImage 诊断 reason)
- **v4.0.11**（b6f9701f9·2026-09-07）: v4.0.11 诊断窗口存档；debug: v4.0.11 — ZoomableAsyncImage fallback 显示 resolver 失败原因 (诊断窗口)
- **v4.0.10**（2bd0525ed·2026-09-07）: v4.0.10 存档；v4.0.10 — thinking 字段语义结构根修 + 放弃 Cherry 分裂模式
- **v4.0.9**（15b36c838·2026-09-07）: v4.0.9 存档；v4.0.9 — 消息序列统一规范化两阶段重写 (tool_result 后用户消息连续被网关合并混排挂起)
- **v4.0.8**（72a609b58·2026-09-07）: v4.0.8 存档；v4.0.8 — 思考控制 UI 门控数据层兜底 + 图片展示引导全覆盖
- **v4.0.7**（54f9ce56b·2026-09-07）: v4.0.7 存档；v4.0.7 二次修正 — 删除 modelWithAbilities 重复声明 (checkout 恢复与注入叠加产生, CI 必败)；v4.0.7 修正 — modelWithAbilities 声明前移到 transforms 之前 (上一提交声明在使用之后, CI 红)；v4.0.7 — 本地图片链接渲染根修 + 思考控制对齐原版 + Anthropic session 头
- **v4.0.6**（11a4eb6f3·2026-09-06）: 知识库整顿 — B118 入册 + D9/D10 决策补录 + roadmap 全面校准 v4.0.6 + project-brief 版本基线更新；v4.0.6 versionName 补录 (上单漏改, release 未落新 tag)；v4.0.6 存档；v4.0.6 — 工具图片格式基础层重写 (CC+Anthropic 双通道)
- **v4.0.5**（d2b90d44b·2026-09-06）: v4.0.5 存档；v4.0.5 — 极简错误体识别字符串转义修正 (""model"" → "\"model\"")；v4.0.5 — 工具图片拆独立 user 消息 + 极简错误体细化
- **v4.0.4**（4fa524b8b·2026-09-06）: v4.0.4 存档；v4.0.4 — 工具结果图片重定位 (整段重写) + 网关挂起重试判定修正
- **v4.0.3**（dc2d2af44·2026-09-06）: v4.0.3 — OpenCode 通道缓存修正 + 5xx 重试双保险
- **v4.0.2**（86e2a66cf·2026-09-05）: v4.0.2 存档；v4.0.2 — HyperDialog 签名对齐 M3 (title/text Composable lambda) + DocumentPreview label/ColumnScope 修正；v4.0.2 — Office/PDF 文档预览 + 澎湃 OS 4 动效统一
- **v4.0.1**（22543a262·2026-09-05）: v4.0.1 存档；v4.0.1 — memoryCacheKey import 删 (coil 核心自动解析, 原版无此 import) + TOOL_NAME_OVERRIDES 键定义补齐复验；v4.0.1 — 编译修正: TOOL_NAME_OVERRIDES 键定义补齐 (顺手修历史遗留: toolNameOverrides 字段持久化时键未定义) + UIAvatar/BackgroundPicker launch 缩进变体残留 + memoryCacheKey import；v4.0.1 — 426 报错修复 + 原版 2.4.17 全量移植 (适配式, 非整拷)
- **v4.0.0**（2ad896e91·2026-09-05）: v4.0.0 重写工程全量完成存档；v4.0.0 — 提取点闭合结构修复 (CC if 块闭合补回/Claude 结构核验通过)；v4.0.0 — 孤儿闭合修复写盘 (CC 两处/Claude 一处)；v4.0.0 重写工程 · 模块4 — 思考控制分派提取为纯函数

### v3.22.x 系列

- **v3.22.2**（8c74799a8·2026-09-05）: v3.22.2 存档；v3.22.2 — Log/TAG import 补齐；v3.22.2 — A17/澎湃OS 4 定向优化 (FGS 生命周期防护) + 预览导向链复验
- **v3.22.1**（71d4c40b0·2026-09-05）: v3.22.1 — 工作区文件预览点击无反应修复 (导向断裂)
- **v3.22.0**（005ee3b91·2026-09-05）: v3.22.0 存档；v3.22.0 — PreviewDialog imports 补齐 (background/CircularProgressIndicator/Cancel01)；v3.22.0 — 预览链三函数原子修复 (签名 onPreview/调用点回调/主调用传参/PreviewDialog 定义)；v3.22.0 — 预览链参数化修正 (WorkspaceFilesPage 回调传递/PreviewDialog 定义补回/doQuery focal 前向引用)

### v3.21.x 系列

- **v3.21.0**（fa3d0cadc·2026-09-05）: v3.21.0 存档；v3.21.0 — CallTracer 引用路径修正 (data.ai 包)；v3.21.0 — CC 焦点大窗完全对齐 OC 同构 + 点击编辑取消 + Transformer 链异常隔离

### v3.20.x 系列

- **v3.20.0**（1746440bb·2026-09-05）: v3.20.0 存档；v3.20.0 — x-opencode-session 自动注入 (OpenCode 官方 09/06 强制)

### v3.19.x 系列

- **v3.19.0**（b136e48ff·2026-09-04）: v3.19.0 存档；v3.19.0 — CC 重置环类型修正 (resetColorArgb 传 remainingMs Long, percent toFloat) + followup answered 态显示；v3.19.0 — CC 密钥焦点大窗 + AskUser 开放输入框 + 图片渲染放宽 + 查询失败上报 + 技术债标注

### v3.18.x 系列

- **v3.18.0**（5948221b9·2026-09-04）: v3.18.0 存档；v3.18.0 — 用量页焦点视图矩阵重构 + 密钥统一保存收口 + 导出主线程 IO 修复

### v3.17.x 系列

- **v3.17.0**（6aa26c211·2026-09-04）: v3.17.0 存档 — changelog 补独立 Cherry 路径 + CC 长池 + 预热核查；v3.17.0 — ChatService 消息时预热两处 (CC/OpenCode 定向) 同步进长保活池；v3.17.0 — 强兼容模式重构为完全独立 Cherry 路径 + CC 长保活池 + 预热池错配修复

### v3.16.x 系列

- **v3.16.0**（bdb577880·2026-09-04）: v3.16.0 存档 — changelog 补强兼容模式 + UA 移除 + 基础链路审计；v3.16.0 — OkHttp interceptor 空 UA 检查块清除 (v3.9.13 逻辑空壳残留)；v3.16.0 — 强兼容模式 + User Agent 方向移除 + 基础链路审计

### v3.15.x 系列

- **v3.15.4**（c1a50ebf0·2026-09-04）: v3.15.4 存档 — changelog 补 v3.15.4, bug-record 补 B117 (CC thinking 字段被网关拒绝)；v3.15.4 — CC 思考分支简化为纯 reasoning_effort (B117: thinking 字段被网关拒绝致全模块炸)
- **v3.15.3**（6f740efa8·2026-09-04）: v3.15.3 — resolveAnyFile 是 object 成员不能顶层 import, 改 WorkspaceImageResolver.resolveAnyFile 限定调用；v3.15.3 — resolveAnyFile 补齐 (resolve 单行表达式形式未匹配旧锚点, 插入未生效)；v3.15.3 存档 — changelog 补 v3.15.2/v3.15.3, bug-record 补 B115 (file:// 点击崩溃) / B116 (none 档位 400)；v3.15.3 — AUTOLINK 分支 linkCtx 声明残留清除 (最后一处非 Composable 上下文 LocalContext)
- **v3.15.2**（f5ccc7265·2026-09-04）: v3.15.2 — 思考程度控制修复 (OpenCode/CC 网关全链) + workspace host 前缀适配
- **v3.15.1**（c7abf8f7f·2026-09-04）: v3.15.1 存档 — changelog 补 v3.15.0/v3.15.1, bug-record 补 B113 (发起误判+并发污染) / B114 (MCP 抖动破坏缓存)；v3.15.1 — 作用域架构修正: retry 经参数传入 generateInternal (跨 step 共享预算=一次用户请求总窗口, 原类成员语义等价去并发化), MCP 快照留 generateText (tools 源头)；v3.15.1 — RetryState/MCP 快照声明迁入 generateInternal (与引用同作用域; 每 step 独立恢复周期语义正确: receivedAnyData 每步重置)；v3.15.1 — 突然中断无重试 (发起误判+并发污染) + 缓存骤降 (MCP 工具抖动)
- **v3.15.0**（c6a6ad939·2026-09-03）: v3.15.0 — 自动重试开关 item 从代理弹窗迁至网络页 LazyColumn 首位 (原插点命中 AlertDialog 首个 ') {')；v3.15.0 — TtsController 常量区/imports/isRetryable 扩展补齐 (符号集合对齐 upstream 4309fdfe 终态)；v3.15.0 — 2.4.16 移植收尾 (SettingModelPage 整体重构/选择器对齐原版基准/TTS 语义补全)；v3.15.0 — CI 红四类收尾 (NetworkSetting 字段/SettingModelPage 重构/剩余 GetMultipleContents/TtsController 完整语义)

### v3.14.x 系列

- **v3.14.0**（7a5ca0f53·2026-09-03）: v3.14.0 存档 — changelog 补 v3.14.0, bug-record 补 B111 (CC 图片定案) / B112 (三轮链)；v3.14.0 — 断流重试统一三轮链 (用户定版) + 纯文本卡死症状归入同一恢复链

### v3.13.x 系列

- **v3.13.7**（dc3bf2c4f·2026-09-03）: v3.13.7 — CC 图片重定位目标修正: Tool.output 中的图片 (v3.13.6 找错目标恒不命中)
- **v3.13.6**（b3fe0f788·2026-09-03）: v3.13.6 — 补 CCImageCompatTransformer object 闭合括号 (v3.13.6 首推裁掉了对象收尾, 68:6 Syntax error 根因)；v3.13.6 — CC 图片根治: tool result 图片重定位 (对齐 AI SDK 标准行为)
- **v3.13.5**（13087b659·2026-09-03）: v3.13.5 — CC 图片根治: CLI VISION 工具同款机制 (图→文字转写)
- **v3.13.4**（0ad3c6a6f·2026-09-03）: v3.13.4 — CC 图片根因修复 (请求体过大致 header 判死) + 启动即预热

### v3.12.x 系列

- **v3.12.1**（9007defdc·2026-09-03）: 知识库存档 — changelog 补 v3.12.1-3.13.3, bug-record 补 B110 (CC 图片 Invalid input)；v3.12.1 — 发起/流中断流重试彻底分流

### v3.13.x 系列

- **v3.13.3**（931b608be·2026-09-03）: v3.13.3 — Command Code 图片兼容适配 (CC 专属 opt-in)
- **v3.13.2**（7577f814b·2026-09-03）: v3.13.2 — UsagePage otherVisible 统一小卡 (前次 patch assert 中断未落盘, 补齐)；v3.13.2 — UsagePage crossUsages 声明上移 + 焦点卡 MiniCardData; CC 页 fetchUsage 可空过滤；v3.13.2 — crossUsages 声明上移 (Kotlin 局部变量先声明后用) + 焦点卡调用点改 MiniCardData 新签名；v3.13.2 补 CommandCodeUsagePage 缺失的 UsageApi import (CI 编译失败根因, 跨族查询级联 unresolved)
- **v3.13.1**（bca0c24b0·2026-09-03）: v3.13.1 — planId 提取容错 + 多级授信匹配 + 诊断自证
- **v3.13.0**（ab5a41b60·2026-09-03）: v3.13.0 — Command Code 月度总额授信语义修正 + 缓存连接巡检

### v3.12.x 系列

- **v3.12.8**（02df8d3f0·2026-09-02）: v3.12.8 — 预热开关落盘 + 月度已用百分比 + 重置卡红→绿反向
- **v3.12.7**（50a34d0ff·2026-09-02）: v3.12.7 — CC 查询展示完全对齐 OpenCode + 重置卡颜色同向
- **v3.12.6**（1d6e14400·2026-09-02）: v3.12.6 — 双预热开关化 + CC 第四卡对齐 + 预热节流
- **v3.12.5**（bffc29414·2026-09-02）: v3.12.5 — user_ 分流决定性修复 + 全密钥渐变 + Command Code 专项预热
- **v3.12.4**（a99b26434·2026-09-02）: v3.12.4 — 用量渐变色 + 风暴间隔 500ms + 全套餐目录
- **v3.12.3**（6390ab142·2026-09-02）: v3.12.3 UI 适配 FetchOutcome + 精简三卡 (5h/7d/月度 剩余+重置时间)；v3.12.3 — Command Code 查询诊断增强 + 三卡精简
- **v3.12.2**（20c7c86bd·2026-09-02）: v3.12.2 — Command Code 用量监测 (完全对齐 OpenCode 版)

### v3.11.x 系列

- **v3.11.27**（1054b1a7e·2026-09-02）: 存档 v3.11.27~3.12.0 版本记录与 B42-B45 根因教训 (skill 知识库)；v3.11.27 skipAssistantPrompt 参数链串联 (generateInternal)；v3.11.27 — 四处严重问题修复

### v3.12.x 系列

- **v3.12.0**（9c88088e9·2026-09-02）: v3.12.0 — 对话发起与连接稳定性定版

### v3.11.x 系列

- **v3.11.35**（d463f8f08·2026-09-02）: v3.11.35 harvestTripCount 函数体补齐 (脚本中断未落盘)；v3.11.35 — 子代理统计补齐 + 发起阶段重试重写
- **v3.11.34**（8db45f60a·2026-09-01）: v3.11.34 — 移除 show_image 工具 + 输入条透明修复
- **v3.11.33**（97a9f609f·2026-09-01）: v3.11.33 — 用户图片空白修复 + headerRetryCount 跨请求残留根治
- **v3.11.32**（eb781a698·2026-09-01）: v3.11.32 残留 Pair 两处替换 (replace_all 遗漏)；v3.11.32 编译修正 — ResolveResult data class 封装 / ShowImageTool 解构适配 / matchParentSize 为 BoxScope 成员；v3.11.32 — workspace:// 图片渲染实测故障修复
- **v3.11.31**（a3c64f9f2·2026-09-01）: v3.11.31 WorkspaceImageFetch 整段重写 (Path.toPath + ImageSource(path,fs) coil3.5 口径)；v3.11.31 coil3.5 API 对齐 (key.Keyer / ImageSource / okio.Path.of) + resolver 位运算语法；v3.11.31 resolveRootfsFileSafe 复用 resolvePath 内建 canonical/防逃逸, 移除重复校验；v3.11.31 resolveRootfsFileSafe 类型修正 (rootDir 为 String 需转 File)
- **v3.11.30**（749b0fa03·2026-09-01）: v3.11.30 — 复读熔断误杀修复 + task_tool 22条压测修复 + defer拦截竞态根治 + 子代理token统计
- **v3.11.29**（32ef59008·2026-08-31）: v3.11.29 手写 Migration_28_29 (schema 28.json 缺失, AutoMigration 不可用)；v3.11.29 — 子代理运行记录落盘 (Room)，重启不丢
- **v3.11.28**（288e63b54·2026-08-31）: v3.11.28 — 子代理工具调用与普通工具完全对齐 + 派发执行彻底解耦
- **v3.11.26**（bd1dd468b·2026-08-31）: skills v3.11.26 — changelog 补 v3.11.21~26 + roadmap 重写至当前基线；v3.11.26 快捷入口改为与上方网格对齐的方块行 (BigIconTextButton)；v3.11.26 — task_tool 推进反馈 + 加号面板四快捷入口 + 子代理唯一展示窗口；v3.11.26 FilesPicker 双 else 语法错误
- **v3.11.25**（597b56770·2026-08-31）: v3.11.25 add(createTaskTool()) — Tool 非 Collection；v3.11.25 — Cherry Studio 任务功能移植 (task_tool + 任务卡片) + 工具卡语义化折叠标题
- **v3.11.23**（cd6abb73e·2026-08-31）: changelog v3.11.23/24 归档；v3.11.23 ChatInput imeProgressiveCollapse 残段清理；v3.11.23 isEmptyUIMessage 仅判定本仓存在的 Tool 类型 (上游 ServerTool 本仓无此类型)；v3.11.23 oauth module gradle 改写为本仓 convention-free 形态 (android.library alias 直挂, 补 consumer-rules)
- **v3.11.24**（826b3bda6·2026-08-31）: v3.11.24 thought null-safety (orEmpty before trim)；v3.11.24 F2 args 顶层 JsonObject 降采样校验 (JsonElement 无下标成员)；v3.11.24 — 目击四故障深度修复 (2026-08-31 报告实录)
- **v3.11.22**（4d69d9df5·2026-08-30）: v3.11.22 — MeshGradient 完全回退为原版 HEAD 原文 (彻底对照)
- **v3.11.21**（b8ba29f0f·2026-08-30）: v3.11.21 icon imports 补齐 + twoPi 内联；v3.11.21 specs 声明重复行清除；v3.11.21 BlobSpec 提升至文件顶层 + 清除 radiusRatio 残参；v3.11.21 — 光团速度/轨迹对齐原版最新 + 三对重复图标区分
- **v3.11.20**（f77993738·2026-08-30）: v3.11.20 RepeatMode/StartOffset import 点号修正；v3.11.20 — 光团漂移动画恢复 (v3.6.82 静态化根除) + 撤销 v3.11.19 ChatPage 改动
- **v3.11.19**（064adac00·2026-08-30）: v3.11.19 key 语法修正 — Modifier.key 不存在, 改 composable key() 作用域包裹；v3.11.19 — 渐变背景层叠净化 + 思考程度高/较高图标区分
- **v3.11.18**（db55b4b0c·2026-08-29）: v3.11.18 DynamicTools 补 buildJsonObject/put imports；v3.11.18-3 — 锁死根因二: 框架工具 schema 缺失致空参合法化 (全量补齐)；v3.11.18-2 — 工具锁死根因修复: mcp 词族断裂 (mcp_connect→manage_mcp_servers)；v3.11.18 — 工具熔断升级物理闸门 + mcp_connect 意图边界声明
- **v3.11.17**（61470dac0·2026-08-29）: v3.11.17 truncated List 不可变 — finalOutput 值语义构造替代 add 变异；v3.11.17 toolFailureCounts 作用域修正 (声明移至工具执行外层, 与 executedTools 同级)；v3.11.17 — 工具锁死循环熔断 + 重试静默化 (双系统修复)
- **v3.11.16**（3d4ebbd10·2026-08-28）: v3.11.16 — 网关连接超时分道 15 次密集重试 (策略矩阵定型)
- **v3.11.15**（35c13f9ef·2026-08-28）: v3.11.15 — 错误弹窗强制携带构建版本号 (版本自证)
- **v3.11.14**（75dafa586·2026-08-28）: v3.11.14 TranslatorPage ExposedDropdownMenu→DropdownMenu + MenuAnchorType import 清理 (alpha26 移除)；v3.11.14 TTS ExposedDropdownMenu→DropdownMenu (material3 1.5.0-alpha26 移除该 API, 上游代码不使用故全库绿; 自研 TTS 8 处替换, 交互行为一致)；v3.11.14 依赖矩阵整体对齐上游 2.4.14 + jitpack 解析韧性；v3.11.14 ChatInput 终修 — FullScreen import 对齐 1.3 + modelListState 声明补回
- **v3.11.13**（e3783eccf·2026-08-27）: v3.11.13 — watchdog 三阶段分级 (多尝试, 少静默)
- **v3.11.12**（316281c70·2026-08-27）: v3.11.12 — 断流失败类型分级 (静默超时型与瞬时断流型分离)
- **v3.11.11**（bb636bc1b·2026-08-27）: v3.11.11 — 历史思考不回放 (非官方通道统一规则, 整体化收敛)
- **v3.11.5**（3e860e5d8·2026-08-27）: v3.11.5-v3.11.10 changelog/bug-record/修改全记录联动更新；v3.11.5 编译修复 — Math.pow (kotlin.math.pow 需 import 扩展)；v3.11.5 — 断流重试参数按用户要求: 15 次共 10s, 前 5s 至少 7 次
- **v3.11.10**（e3e983909·2026-08-27）: v3.11.10 编译修复 — warmWithOkHttp 补 ConnectionWarmer 限定；v3.11.10 — 断流预算从断流时刻起算 + Claude 通道预热补全
- **v3.11.9**（f8add12ba·2026-08-27）: v3.11.9 编译修复 — eventSourceRef 声明前置 (局部变量无前向引用)；v3.11.9 编译修复 — AtomicReference 容器化 (object expression 捕获 var 写入受限)；v3.11.9 编译修复 — eventSource 闭包捕获改可空 var; tools 缓存字段 useBlockCache；v3.11.9 — MiniMax 家族请求体分离 + 2013 降级重试 (整体化)
- **v3.11.8**（e8f48b3f9·2026-08-27）: v3.11.8 — 按模型家族分离兜底 + 缓存机制调研存档
- **v3.11.7**（b5789a18f·2026-08-27）: v3.11.7 — 全模型请求体格式调研 + max_tokens 缺失兜底
- **v3.11.6**（8b26ade05·2026-08-27）: v3.11.6 — 缓存恢复 + OpenCode 稳定性定向优化 + 重试提示生命周期
- **v3.11.2**（1ff185c3c·2026-08-26）: 知识库同步 v3.11.2-3.11.4 (MCP 对齐/changelog/B105 工具卡死)；v3.11.2 — MCP 状态机/图标完全对齐原版 RikkaHub
- **v3.11.4**（64be9a562·2026-08-26）: v3.11.4 — 工具调用后"卡死"根因整体修复 (输出丢弃+无限静默)
- **v3.11.3**（90472550c·2026-08-25）: v3.11.3 补偿2 — SettingMcpPage STDIO 表单分支 + 字符串资源；v3.11.3 补偿 — MCP 对齐编译错误 (重复 import/suspend/when 穷尽性)；v3.11.3 — Minimax 400 第三候选修复 (工具轮消息净化) + 诊断升级
- **v3.11.1**（f55c93907·2026-08-24）: 知识库同步 B104 + changelog v3.11.1 + 环境清理；v3.11.1 — Console Go 400 根因闭合: 无签名 thinking 块丢弃 (全链路)

### v3.10.x 系列

- **v3.10.14**（5dbfee6e0·2026-08-24）: v3.10.14 — Console Go 400 根因终结: 非官方 host 跳过 Anthropic 私有字段
- **v3.10.13**（0404587c3·2026-08-24）: v3.10.13 — Console Go 400 根因根治: time_reminder 独立消息 → 连续 user 违交替规则
- **v3.10.12**（27388dcef·2026-08-24）: v3.10.12 — Console Go 400 防御净化 + 请求体进错误详情 + versionName 恢复递增
- **v3.10.8**（1c9370ff5·2026-08-24）: revert: v3.10.8 渲染内核 (mikepenz) 整体回滚 + 版本号锁死 3.10.6；v3.10.8 — 渲染内核增强: 引入 mikepenz markdown (GFM 全覆盖)
- **v3.10.11**（02a787f54·2026-08-24）: v3.10.11 补偿 — ClaudeProvider 补 BuiltInTools import + List toContentBlocks 重载；v3.10.11 — ClaudeProvider 请求构建整体回原版 + versionCode 固定 (任意替换安装)
- **v3.10.10**（660785156·2026-08-24）: v3.10.10 — 平滑输出闭包抽帧修复 + Console Go 400 max_tokens 兼容修复
- **v3.10.9**（bab791f45·2026-08-24）: v3.10.9 收尾2 — SmoothStreamingText now 变量作用域 (回退分支不可见)；v3.10.9 收尾 — 整体影响面复查修复 (最终版)；v3.10.9 — 流式输出平滑整形 (逐字节奏, 动态对齐服务端速度)
- **v3.10.7**（1d2b95979·2026-08-24）: v3.10.7 — TTFT 强化第2轮: 预热池匹配修复 + 打字防抖预热
- **v3.10.6**（98884455c·2026-08-24）: v3.10.6 — 跨模型继续对话 400 (2013): 无签名 thinking 块 + adaptive 参数兼容
- **v3.10.5**（f69e789e5·2026-08-24）: v3.10.5 补偿2 — ChatService 注册补 httpClient 注入；v3.10.5 补偿 — TTFT 埋点变量前移 (listener 闭包作用域)；v3.10.5 — OpenCode TTFT 专项优化 (首字延迟)
- **v3.10.4**（9f0a5ac4c·2026-08-24）: v3.10.4 — 新助手技能全量注入逆转 + MCP Idle 图标语义修复
- **v3.10.3**（0bbbd308e·2026-08-24）: 归档 v3.10.3 Opencode 500 根因 + bug-record B99；v3.10.3 — Opencode 各套餐 HTTP 500 根因: 空 system 兜底
- **v3.10.1**（7900820ae·2026-08-23）: 归档 v3.10.1/v3.10.2 高危修复记录；v3.10.1 编译修复3 — import kotlin.time.Clock (GenerationHandler 同款)；v3.10.1 编译修复2 — Clock 用 kotlin.time typealias (kotlinx 0.8)；v3.10.1 编译修复 — PlaceholderTransformer 补 kotlinx.datetime.toLocalDateTime import
- **v3.10.2**（cad29f59c·2026-08-23）: v3.10.2 — GPT-5.6-Luna (Opencode 订阅) 思考链完全丢失
- **v3.10.0**（84d1c4036·2026-08-23）: 归档 v3.10.0 修改全记录；v3.10.0 稳定版 — 弹窗滚动修复 + Claude 代理路由修正 + 全量自检

### v3.9.x 系列

- **v3.9.15**（e461a8a40·2026-08-23）: v3.9.15 编译修复5 — 代理 client lambda 显式返回 proxyClient；v3.9.15 编译修复4 — ChatCompletionsAPI/ResponseAPI 补 ProxyRoute import；v3.9.15 编译修复3 — 各 Provider import ProxyRoute/resolveProxy; GoogleProvider listModels 回退默认 client；v3.9.15 编译修复 — 模型勾选弹窗块移入函数体 (原追加在函数外)
- **v3.9.14**（1d86908a1·2026-08-23）: v3.9.14 — 澎湃OS4/骁龙8 Elite 适配前置: 设备环境检测日志
- **v3.9.11**（98698b504·2026-08-23）: 归档 v3.9.11-v3.9.13 修改全记录 (正文)；归档 v3.9.11-v3.9.13 修改全记录；v3.9.11 — MCP图标复原/PDF双缓冲/DOCX字号字体间距/PPT滚动包装/Usage焦点视图逻辑/Provider持久化重写
- **v3.9.13**（d7f28ece4·2026-08-23）: v3.9.13 — User-Agent 默认行为纯净化: 移除 RikkaHub 标识；Revert "feat: v3.9.13 — 设置主页增加网络设置直达入口 (UA/代理/连接测试)"；v3.9.13 — 设置主页增加网络设置直达入口 (UA/代理/连接测试)
- **v3.9.12**（b86cc991c·2026-08-23）: v3.9.12 编译修复2 — ImportExportTab 补 TextButton import；v3.9.12 编译修复 — BackupVM import WebDavConfig / ImportExportTab 用短限定名 import SegmentedButton/AlertDialog/TextButton；v3.9.12 — 原版 RikkaHub 2.4.11 全量深度移植
- **v3.9.10**（e5ce1a6ec·2026-08-23）: v3.9.10 编译修复 — USAGE_VIEW_MODE 常量/Clock02 import/ApiKeyQuickSwitcher currentKey 参数恢复；v3.9.10 — PDF 双缓冲防闪 + DOCX 排版 + PPT 主题色 + MCP 图标 + 用量视图切换 + 密钥库立即持久化
- **v3.9.9**（e7495a523·2026-08-23）: v3.9.9 编译修复3 — PenNew01/Checkmark01 不存在, 换 PencilEdit01 + 文本勾；v3.9.9 编译修复2 — 子类 copyProvider 参数列表补 savedKeys, 字段加 override；v3.9.9 编译修复 — ProviderSetting 基类补 abstract val savedKeys 声明；v3.9.9 — 密钥快捷切换/备注 + PPT 占位符形状修复 + Excel 横向浏览 + MCP 状态文案
- **v3.9.8**（5fd8408e8·2026-08-23）: v3.9.8 — 三处渲染根因修复 (PDF 分支误删 + PPT 表格污染 + XLSX 填充索引)；v3.9.8 — AndroidPdfViewer 在 jitpack/central 均不可用 (上游归档), 回退自研 PDF 手势根治: 自实现双指捏合 (仅两指计算距离比), 单指事件不消费 放行 LazyColumn 滚动 — 解决无法滑动/无法缩放/锁第一页；v3.9.8 — 渲染机第三轮 (PDF 换成熟库 + Excel 样式 + PPT 形状级 + 视频控制条)
- **v3.9.7**（9a76caa77·2026-08-23）: v3.9.7 编译修复2 — fromFile 是 android.net.Uri 静态方法；v3.9.7 编译修复 — smart cast 局部变量 + Uri import (扩展函数需 import 不能全限定)；v3.9.7 — 全类型暂不支持 CACHE 根治 + PDF 渲染移后台线程
- **v3.9.6**（4cd8f7043·2026-08-23）: v3.9.6 — extractor 可空调用修复；v3.9.6 — 渲染机核心 (RenderEngine + 统一视图, 根治卡顿/图片/深色)
- **v3.9.5**（a994dac47·2026-08-23）: v3.9.5 编译修复7 — m.group(1) 是 Java API, 改 Kotlin groupValues.getOrNull；v3.9.5 编译修复6 — 去重误删 attr 扩展函数, 补回；v3.9.5 编译修复5 — 补 XmlPullParser 等 import (去重重建时丢失)；v3.9.5 编译修复3 — 拼接损坏残留重复函数定义去重 (csvToHtml 二次定义删除)
- **v3.9.4**（d54a201aa·2026-08-23）: v3.9.4 — ZoomIn/ZoomOut 图标不存在, 缩放按钮改用文本 +/−；v3.9.4 编译修复 — PdfRenderDialog 补 ZoomIn/ZoomOut import；v3.9.4 — 文档渲染解析根因修复 + PDF 缩放/深色模式
- **v3.9.3**（a3cd406ea·2026-08-22）: v3.9.3 编译修复 — material icons 依赖缺失改 HugeIcons + Zoomable 参数类型 + Slider 类型 + FileView import；v3.9.3 — 渲染扩展媒体类型 + PDF/Word 健壮化
- **v3.9.2**（5ca16653a·2026-08-22）: v3.9.2 环境全面刷新节点 — 古早文档/技能全量更新；v3.9.2 — 整体清理 (B 线废弃移除 + 知识库重写 + 单线构建)
- **v3.9.1**（684b65c6e·2026-08-22）: v3.9.1 — 回滚 v3.8.44 非渲染功能 + 渲染扩展为全文档类型

### v3.8.x 系列

- **v3.8.45**（27c79981f·2026-08-22）: v3.8.45 workflow — flavor 化后 APK 输出目录分线 (rincore/release + waterhub/release)；v3.8.45 — resValue 改 flavor sourceSet 资源覆盖 (AGP 9 禁用 flavor resValue)；v3.8.45 脚本编译修复 — buildConfigFields 可空 + srcDirs 废弃 API；v3.8.45 — 双产品线拆分 (RinCore A类主线 3.9.0 + WaterHub B类 0.0.1-beta)
- **v3.8.44**（5e4d2a8db·2026-08-22）: v3.8.44 编译修复 — UserTool id 用 UUID + ToolPickerPopup 字符串引号转义；v3.8.44 — WaterHub 产品线构建 (空对话提示 + 工具入口 + HTML 渲染 + 应用改名)
- **v3.8.43**（71b3e6346·2026-08-22）: v3.8.43 — token 统计口径修正 (并行求和虚高 + 缓存命中钳制)
- **v3.8.35**（e44f0b629·2026-08-22）: 归档 v3.8.35-42 (changelog + B39/B40)；version bump 3.8.35；v3.8.35 — 流式 onFailure 诊断补齐 (请求体摘要 + 响应体)
- **v3.8.42**（7e12a52f8·2026-08-22）: v3.8.42 — 思考链/正文解析修正 (运行时自适应, 不再按模型名一刀切)
- **v3.8.41**（bd2810d37·2026-08-22）: v3.8.41 编译修正 — currentConfigs 保持 McpServerConfig 列表 (eq 泛型)；v3.8.41 — MCP 懒连接 (根治重启即主动连接服务器)
- **v3.8.40**（291a9cbf9·2026-08-22）: v3.8.40 — ox 系模型正文走 reasoning_content (对齐 opencode 客户端)
- **v3.8.39**（5d9b13741·2026-08-22）: v3.8.39 — 无正文场景可见化 (实证 ox-alpha-free 只发思考不发正文)
- **v3.8.38**（516ec3aed·2026-08-22）: v3.8.38 — Zen chunk 结构取证诊断 (deltaKeys + delta 原文)
- **v3.8.37**（00dfc4220·2026-08-22）: v3.8.37 — textTail 提取兼容 content 数组 + 分享逻辑重写
- **v3.8.36**（58a10db93·2026-08-22）: version bump 3.8.36 (v3.8.36 代码提交时漏 bump)；v3.8.36 — 内容级截断检测 (无完成信号模型被平台截短时可见)
- **v3.8.33**（d308bee64·2026-08-22）: 归档 v3.8.33-34 (changelog + B38 定稿)；归档 v3.8.33-34 changelog；v3.8.33 — SSE 行完整性物理判据 (替代模型名单分流)
- **v3.8.34**（da36d0839·2026-08-22）: v3.8.34 编译错误修正 (错误 import/字符串资源残留/BackButton 无 onClick)；v3.8.34 — 运行日志持久化重写 (轮次会话/时间戳ID/两級UI/Markdown导出)
- **v3.8.32**（bd2a14d2a·2026-08-22）: 归档 v3.8.32 changelog + B38 修正记录；v3.8.32 — 异常类移出 import 区 (CI 编译错误修正)；v3.8.32 — Zen 无信号关流改可见化分流 (grok 完成 / ox 保留内容+报错)
- **v3.8.31**（80b79da50·2026-08-22）: B38/v3.8.31 正式归档到 .claude 活跃知识库 + .agents 副本对齐；归档 B38 + changelog v3.8.31；v3.8.31 — OpenCode Zen 无信号关流误判断流 + finish_reason 漏判
- **v3.8.30**（a5459be87·2026-08-21）: 安全审查补记 — 签名连续性恢复 (旧密钥恢复 + Secrets 回切 + v3.8.30 重构建)；v3.8.30 — 安全收尾 (签名密钥轮换 + Secrets 化 + 历史清理)
- **v3.8.29**（a630c449e·2026-08-20）: v3.8.29 CI 编译修复三 — MemoryTools 补 longOrNull import；v3.8.29 CI 编译修复二 — MemoryTools id longOrNull + AssistantMemoryPage 0L；v3.8.29 CI 编译修复 — MemoryTools onDelete Long + McpManager kotlinx import；v3.8.29 changelog/B37/D17
- **v3.8.28**（e0c3d4f4d·2026-08-20）: v3.8.28 changelog/D16；v3.8.28 — 上下文压缩边界智能判定 (按对话轮 + token 60%)
- **v3.8.26**（f253ac920·2026-08-20）: v3.8.26-27 changelog/D15/B36；v3.8.26 — 管理子域工具查询统一寻址兜底 (resolveDomain)
- **v3.8.27**（f3b86776f·2026-08-20）: v3.8.27 — 请求 tools 顶层白名单硬过滤 (Skill 不得跳脱 invoke_tools)
- **v3.8.25**（e62557729·2026-08-20）: v3.8.25 文档归档 (changelog/B35/D14/03 记录)；v3.8.25 — 管理子域数量 0 修复 + 同源化
- **v3.8.24**（05da24ae9·2026-08-20）: v3.8.24 changelog；v3.8.24 — 工具域目标列表统一信息源头 + 点击卡顿根治
- **v3.8.23**（2ca40f1d1·2026-08-20）: v3.8.23 — changelog/bug-record(B33/B34)/decisions(D13) 归档；v3.8.23 — 工具域系统修复: 对照校验/豁免持久化/幽灵域清理/选项排序
- **v3.8.22**（5db8d787d·2026-08-20）: v3.8.22 — 压缩位点持久化修复 + 抽屉删除助手入口
- **v3.8.21**（5b23be5de·2026-08-19）: v3.8.21 — drawWithContent import 补漏 (ChatPage 无 draw 前缀 import)
- **v3.8.20**（ee116fa87·2026-08-19）: v3.8.20 — 液态玻璃全场景失效根治
- **v3.8.19**（2bbd2475b·2026-08-19）: v3.8.19 — 压缩管理 UI 收尾 + 文档归档
- **v3.8.18**（899f81505·2026-08-19）: v3.8.18 — RoundedCornerShape import 补漏
- **v3.8.17**（7d43e432e·2026-08-19）: v3.8.17 — @Composable 归位 + UIMessagePart 包修正 (me.rerere.ai.ui)
- **v3.8.16**（65bf39376·2026-08-19）: v3.8.16 — CI 编译修复: SpringSpec/TweenSpec 类构造 + UIMessagePart import
- **v3.8.15**（6e70734b8·2026-08-19）: v3.8.15 — 抽屉块状三入口 + 清理聊天内容
- **v3.8.14**（f5affedb8·2026-08-19）: v3.8.14 — 压缩机制重做: 留存位点 (最多3) + 弹窗撤销管理
- **v3.8.13**（73b306123·2026-08-19）: versionName 3.8.13；v3.8.13 — 补齐 ChatMessage 剩余 2 处 contentSizeAnimated (213/425 行)
- **v3.8.12**（25d9c7616·2026-08-19）: v3.8.12 — 输出完成瞬间整条消息抽动修复
- **v3.8.11**（0e9cf05af·2026-08-19）: v3.8.11 — Lifecycle import 真正写入 (前缀匹配 bug 两次漏写)
- **v3.8.10**（c8d1aa9f4·2026-08-19）: v3.8.10 — Lifecycle import 补漏 (CI 编译错误)
- **v3.8.9**（8854feeb7·2026-08-19）: v3.8.9 — 用量页时间格式细化 + 液态玻璃分享后恢复
- **v3.8.8**（3ace30bee·2026-08-19）: v3.8.8 — 用量页剩余时间显示 + 断流重试 7 次 5 秒内
- **v3.8.7**（39f233451·2026-08-18）: v3.8.7 — UI 节流回 50ms (5ms 实测掉帧式顿挫)
- **v3.8.6**（81a0752a0·2026-08-18）: v3.8.6 — 节流 5ms + 流式诊断日志抓取
- **v3.8.5**（8d8205f79·2026-08-18）: v3.8.5 — Anthropic 接口输出中途静默中断根治
- **v3.8.4**（dae362256·2026-08-18）: v3.8.4 — toHttpUrl import + watchdog launch receiver
- **v3.8.3**（da6c27487·2026-08-18）: v3.8.3 — OpenCode 中转首字延迟与输出顿挫优化
- **v3.8.2**（5c5134753·2026-08-17）: v3.8.2 — 密钥持久化修复 + 用量页整体重构
- **v3.8.1**（65897e3fc·2026-08-17）: v3.8.1 — 环形图缩小 + API Key 卡包
- **v3.8.0**（81dfd59de·2026-08-17）: v3.8.0 — 用量查询页 (替换翻译/图像生成入口)；v3.8.0-r6: McpManager干净 + SettingMcpPage全量Stdio分支；v3.8.0-r5: StdioTransportServer branch in getTransport + else兜底；v3.8.0-r4: stdio简洁处理 + else兜底

### v3.7.x 系列

- **v3.7.6**（91e6cdc88·2026-08-17）: v3.7.6 — also 移入 single lambda (KoinDefinition 赋值修复)
- **v3.7.5**（7155c0801·2026-08-17）: v3.7.5 — named qualifier + claude bean 独立配置
- **v3.7.4**（d50ee21bb·2026-08-17）: v3.7.4 — 中间残留 claude bean 删除
- **v3.7.3**（915a86b7a·2026-08-17）: v3.7.3 — claude bean 移到模块级 (single 作用域修正)
- **v3.7.2**（55311fb16·2026-08-17）: v3.7.2 — companion 移入类内
- **v3.7.1**（cb10e33fe·2026-08-17）: v3.7.1 — 渲染稳定 + OpenCode 中转连接稳定
- **v3.7.0**（523efe891·2026-08-16）: v3.7.0 — 优化稳定版 (收尾 + 深度优化)；v3.7.0: 移除压缩器 + 诊断日志

### v3.6.x 系列

- **v3.6.123**（93d284388·2026-08-16）: v3.6.123 — registeredBridgeCommands 字段恢复
- **v3.6.122**（75086f19d·2026-08-16）: v3.6.122 — toSet + removePlugin 恢复
- **v3.6.121**（485d67921·2026-08-16）: v3.6.121 — MCP 连接回滚收尾 (plugin__ 残留清理)
- **v3.6.120**（02b2e29b7·2026-08-16）: v3.6.120 — MCP 连接回滚 (插件自动桥接移除)
- **v3.6.119**（0e1d858aa·2026-08-16）: v3.6.119 — MCP 配置落盘 + 缓存概率断裂根因修复
- **v3.6.118**（68e0d678f·2026-08-16）: v3.6.118 — 插件列表空的致命根因 + 测试报告 P0-P2 全项处理
- **v3.6.117**（2c695f1d0·2026-08-16）: v3.6.117 — 插件系统收尾 (真实名目录/删除/静态校验)
- **v3.6.116**（14534c29d·2026-08-16）: v3.6.116 — 插件页列表空的根因修复 (plugin.json 元数据)
- **v3.6.115**（21bcd8cd4·2026-08-16）: v3.6.115 — McpServerConfig 类名修正
- **v3.6.114**（a9d7fb9a0·2026-08-16）: v3.6.114 — buildJsonObject import 全限定修复
- **v3.6.113**（78b692d55·2026-08-16）: v3.6.113 — ClawPluginRegistry 编译修复 (json put import/workspaceId/全限定简化)
- **v3.6.112**（ff5e45574·2026-08-16）: v3.6.112 — 插件与技能彻底隔开 + 插件桥接 STDIO workspace
- **v3.6.111**（d1eba2d5e·2026-08-16）: v3.6.111 — collectAsState import
- **v3.6.110**（0251c7b18·2026-08-16）: v3.6.110 — 插件与技能彻底分开 + 插件页可见 + 注册热生效
- **v3.6.109**（1aaf3a1cc·2026-08-16）: v3.6.109 — 插件技能直接落技能目录 (沙箱 /skills 挂载, 立即可见)
- **v3.6.108**（0a8a73b8a·2026-08-16）: v3.6.108 — getCurrentAssistant import + scope.launch 修正
- **v3.6.107**（eab304da5·2026-08-16）: v3.6.107 — AlertDialog import + forcedSkills 引用修正
- **v3.6.106**（ff3f1b3f9·2026-08-16）: v3.6.106 — 编译错误修复 (图标 import/updateAssistant 签名/McpToolCard 补全/suspend)
- **v3.6.105**（1f2d1b856·2026-08-16）: v3.6.105 — 插件系统修复 (技能进工具池/插件页可见) + 强制启动
- **v3.6.104**（9f00573f1·2026-08-16）: v3.6.104 — 方法域更名未分类 + Skills 搜索 + 生态引导 + 设置页重排
- **v3.6.103**（cf6341e0b·2026-08-16）: v3.6.103 — ClawPluginRegistry 重命名 (贴合性优化)
- **v3.6.102**（6ae8c48f2·2026-08-16）: v3.6.102 — 工具改名能力 + 工具超时 60s 兜底
- **v3.6.101**（1cd655871·2026-08-16）: v3.6.101 — ReasoningPicker 残留 import 清理 + changelog 补录 v3.6.92-100
- **v3.6.100**（d67e37420·2026-08-16）: v3.6.100 — 全量原版对齐标注批次 (方向二)
- **v3.6.99**（c9d6ff99c·2026-08-16）: v3.6.99 — 三处编译错误修复 (suspend/import/双注解)
- **v3.6.98**（fa16e34ef·2026-08-16）: v3.6.98 — 补 DoubaoSearchService.kt (多文件 checkout 因测试路径失败未落盘)
- **v3.6.97**（2a05f782a·2026-08-16）: fix+feat: v3.6.97 — 插件系统收尾 + 原版 RikkaHub 2.4.10 全面移植
- **v3.6.96**（7f71ccfcf·2026-08-16）: v3.6.96 — 非法 MCP 服务器名不弹窗, 改工具剔除+日志
- **v3.6.95**（d959e41df·2026-08-16）: v3.6.95 — 插件 skill 提取修复 + ClawHub 代理配置
- **v3.6.94**（7612957a0·2026-08-16）: v3.6.94 — clawhub 网络栈 OkHttp 化 + plugin_install 错误区分
- **v3.6.93**（5629b5f5c·2026-08-16）: v3.6.93 — plugin_install 假成功根治 (无 manifest 明确判失败)
- **v3.6.92**（a6581a644·2026-08-16）: v3.6.92 — 技能执行去 enabledSkills 过滤 (全部技能恢复可用)
- **v3.6.90**（d17abb0e5·2026-08-16）: v3.6.90-91 changelog 补录；v3.6.90 — 工具移出域管理开关 + MCP 传输类型教堂窗对齐修复
- **v3.6.91**（2bcaadee5·2026-08-16）: v3.6.91 — clawhub_install/clawhub_search 移出框架工具集
- **v3.6.88**（5c8082b84·2026-08-16）: v3.6.88-89 插件独立系统 changelog + 样例/文档更新；v3.6.88 — 插件独立系统 (设置能力模块入口 + 独立技能工具 + 插件域)
- **v3.6.89**（71fdbd7b3·2026-08-16）: v3.6.89 — SettingPluginsPage onBack 参数缺失编译修复
- **v3.6.86**（323756514·2026-08-16）: v3.6.86-87 插件格式 changelog 补录；v3.6.86 — 插件格式 (技能 + workspace STDIO MCP 桥接)
- **v3.6.87**（852227afd·2026-08-16）: v3.6.87 — Uuid.fromString 不存在, 改 Uuid.random + 运行期缓存
- **v3.6.76**（945021322·2026-08-15）: v3.6.76-v3.6.85 changelog 补录；v3.6.76 — 延时开关改点进对话框交互 + grok 系列跳过 reasoning_effort
- **v3.6.85**（0a68747f7·2026-08-15）: v3.6.85 — DeepSeek Harness 插件生态兼容 + 深度清理
- **v3.6.84**（13b3aa9e4·2026-08-15）: revert: v3.6.84 — 回滚 Markdown 流式降级, 恢复输出一点渲染一点
- **v3.6.83**（083476de7·2026-08-15）: v3.6.83 — 对齐原版 RikkaHub 依赖与构建配置
- **v3.6.82**（1a534e63a·2026-08-15）: v3.6.82 — 静态卡顿根治: 背景动画静态化 + 模糊再降级
- **v3.6.81**（c84226de5·2026-08-15）: v3.6.81 — 间歇性卡顿根治批次一 (渲染热点)
- **v3.6.80**（0a8243237·2026-08-15）: v3.6.80 — 对齐原版 RikkaHub 的 grok 请求行为
- **v3.6.79**（025912738·2026-08-15）: v3.6.79 — 400 报错 REQ 改为完整请求体
- **v3.6.78**（db14efae4·2026-08-15）: v3.6.78 — grok 流式完成判断 + 报错带请求体诊断
- **v3.6.77**（f39eb9fee·2026-08-15）: v3.6.77 — 新增 grok-4.6 / grok-build-0.1 模型定义
- **v3.6.75**（2a5f57f8e·2026-08-15）: v3.6.75 — 修复 Luna 多轮重复回复 + Grok 4.5 模型支持
- **v3.6.72**（7cec7ccb3·2026-08-15）: v3.6.72-v3.6.74 修改全记录 + changelog 补录；v3.6.72 — 修灯泡消失 + 消息消失再出现 + 加载动画重做
- **v3.6.74**（8478e9c81·2026-08-15）: v3.6.74 — 彻底删除上下文降维方向 + 对话设置UI + 延时开关移位
- **v3.6.73**（9a93f2cde·2026-08-15）: v3.6.73 — 修复波浪号误渲染删除线 + 上游移植接口
- **v3.6.71**（cd879ea6f·2026-08-15）: v3.6.71 修改全记录 + changelog 补录；v3.6.71 — 灯泡闪烁全局统一 + MAX 映射锚定 + 加载动画换 RinCore 线条
- **v3.6.70**（60ec1e19f·2026-08-15）: v3.6.70 修改全记录补录；v3.6.70 — 思考深度UI全标注+最高档灯泡闪烁 + 降维改名 + 模糊减负
- **v3.6.67**（27da43a39·2026-08-15）: v3.6.67-v3.6.69 修改全记录 + changelog 版本链补录；v3.6.67 — 清理诊断显示 + 移植原版 2.4.9 三处修复
- **v3.6.69**（787925967·2026-08-15）: v3.6.69 — 消息页渲染深度优化 + 截断改为 3 条
- **v3.6.68**（cf70b19af·2026-08-15）: v3.6.68 — 移植原版 2.4.9 首页输入栏模糊视觉
- **v3.6.66**（b5339b557·2026-08-15）: v3.6.66 — 降维压缩重写为纯滞回截断 (对齐原版 RikkaHub)
- **v3.6.65**（049f30ea2·2026-08-15）: v3.6.65 — 修复编译错误 (Clock.System 完全限定名歧义)
- **v3.6.64**（033bb5ebd·2026-08-15）: v3.6.64 — 缓存断点第二处根因: 压缩包 createdAt 固定
- **v3.6.63**（9d5d67373·2026-08-15）: v3.6.63 — 缓存断点根因修复: TimeReminderTransformer 幂等化
- **v3.6.62**（23552e862·2026-08-15）: v3.6.62 — 增量诊断上屏, 定位压缩包为何每轮变化
- **v3.6.61**（d3bc3ed04·2026-08-14）: v3.6.61 — 修复 msg_fp 上屏引入的语法错误 (多余右括号)
- **v3.6.60**（f5fb0ca5f·2026-08-14）: v3.6.60 — 定位缓存衰减: 消息指纹上屏 + 压缩排序确定性
- **v3.6.59**（c008e4ec5·2026-08-14）: v3.6.59 — 降维压缩改直接截断前 40% (修复首尾采样负优化)
- **v3.6.58**（823112f15·2026-08-14）: v3.6.58 — 降维压缩根因修复: 整体保留 40% (首尾采样)
- **v3.6.57**（e3586eb27·2026-08-14）: v3.6.57 — 找到真正错误点: extractHead 取'首句'而非'前N字符'
- **v3.6.56**（1803f24f3·2026-08-14）: v3.6.56 — 降维压缩包保留量增大, 命中从 16.8K 提到 25-26K
- **v3.6.55**（cbeee7b8a·2026-08-14）: v3.6.55 — 降维缓存凡有必存, 逐 token 累积 (不以 K 为单位)
- **v3.6.54**（27d5aa313·2026-08-14）: v3.6.54 — 降维压缩包信息量提升, 修复缓存涨到 16.8K 就停
- **v3.6.53**（757384d7e·2026-08-14）: v3.6.53 — reasoning_content 回传完全对齐原版 + 多模型缓存字段
- **v3.6.52**（676430559·2026-08-14）: v3.6.52 — 恢复非降维模式 DeepSeek 缓存命中率
- **v3.6.51**（463808e86·2026-08-14）: v3.6.51 — 降维缓存修复 (压缩包可命中) + 简化总结
- **v3.6.50**（c4bbd4801·2026-08-14）: v3.6.50 — 千问 3.8 Max 缓存命中修复 (对齐 Qwen-Agent 官方框架)
- **v3.6.49**（5e8f71bc5·2026-08-14）: v3.6.49 — 流式节流 + 缓存对齐 DeepSeek Harness + 文档生成能力
- **v3.6.48**（e4683176e·2026-08-14）: v3.6.48 — 移植原版 RikkaHub 2.4.8 正向更新 (部分)
- **v3.6.47**（fe393e062·2026-08-13）: v3.6.47 — 缓存 reasoning 字段稳定性 + 发热卡顿根治
- **v3.6.46**（4a657c28c·2026-08-13）: ChatInput 补 stroke.Wrench01 import (v3.6.46 编译修复)；v3.6.46 — 对话设置搬入对话框 + OpenCode reasoning/冷启动优化
- **v3.6.45**（48a46c805·2026-08-13）: conversationId 透传到 generateInternal (v3.6.45 编译修复)；v3.6.45 — OpenCode Zen Sticky Session + 多 host 连接预热
- **v3.6.44**（87571c831·2026-08-13）: v3.6.44 — 缓存字段解析修复 + OpenCode 流式适配
- **v3.6.41**（ef9623293·2026-08-12）: 版本号 3.6.41 → 3.6.43 (v3.6.42 版本号遗漏修正)；v3.6.41 — 压缩率上限修正 (≤65%) + 压缩详情证明
- **v3.6.43**（b19d186ba·2026-08-12）: v3.6.43 — 降维缓存命中修复 (追加式增量压缩包)
- **v3.6.42**（bef1dbe79·2026-08-12）: v3.6.42 — HeadroomStats 字段合并 (lastResult 并入 lastDetail)；v3.6.42 — 废弃代码清理 (第二轮: 零引用类/文件)；v3.6.42 — 废弃代码清理 (第一轮: Headroom/压缩模块)
- **v3.6.40**（c801d6127·2026-08-12）: v3.6.40 — 压缩重写为整体总结式 (用户要求整个重来)
- **v3.6.39**（fde72820c·2026-08-12）: v3.6.39 — 核心修复: beforeC 口径 bug (工具输出大头压缩不生效)
- **v3.6.38**（7c51b4522·2026-08-12）: v3.6.38 — 消息统计行直接显示降维节省 (用户唯一观测手段)
- **v3.6.37**（ffd8e26c9·2026-08-12）: v3.6.37 — 发送请求字符统计 (验证压缩真正进入请求)
- **v3.6.36**（68b5eb0ca·2026-08-12）: v3.6.36 — 聊天页完全静默 + 降维状态移入其他功能
- **v3.6.35**（2d11e02f1·2026-08-12）: v3.6.35 — 负压缩修复 (短历史不打包)
- **v3.6.34**（f0f38731b·2026-08-12）: v3.6.34 — UI 消息污染修复 + 重新生成断流修复 + 统计隔离
- **v3.6.33**（ea5025ceb·2026-08-12）: v3.6.33 — 凡过必压缩 (无条件历史打包)
- **v3.6.32**（e13c151a8·2026-08-12）: v3.6.32 — 历史上下文整体打包压缩 (用户明确需求)
- **v3.6.31**（8afacc1cc·2026-08-12）: v3.6.31 — 压缩统计 UI 可见 (HeadroomStats)
- **v3.6.30**（3a9017f4c·2026-08-12）: v3.6.30 — 压缩可观测性 + UI 降维标签
- **v3.6.29**（e0e9ce01b·2026-08-12）: v3.6.29 — 骨架阈值极限降低 + 压缩诊断
- **v3.6.25**（951210d49·2026-08-12）: 版本号 3.6.25 → 3.6.28 (骨架压缩实际版本修正)；v3.6.25 — Headroom 全上下文压缩 (历史消息截断)
- **v3.6.27**（805d93695·2026-08-12）: v3.6.27 — Headroom 历史骨架压缩 (用户选定方案)
- **v3.6.26**（b975c59e9·2026-08-12）: v3.6.26 — Headroom 阈值激进化 (压缩可感知)
- **v3.6.24**（b20c8d85c·2026-08-11）: v3.6.24 Headroom 记录；v3.6.24 — Headroom 彻底落实 (编译修复 + 全量发送压缩)
- **v3.6.23**（636fd6e2b·2026-08-11）: v3.6.23 — Headroom 上下文降维完善 (有效压缩 + 测试)
- **v3.6.19**（840b9ae96·2026-08-11）: v3.6.19 — Headroom 上下文降维 (对话两种模式)；Revert "fix: v3.6.19 — 百炼 MCP 工具 job was cancelled 修复"；v3.6.19 — 百炼 MCP 工具 job was cancelled 修复
- **v3.6.22**（5757d234b·2026-08-11）: v3.6.22 — 百炼 SSE 响应路径修复 (Streamable 兜底)
- **v3.6.21**（8dc966b9a·2026-08-11）: v3.6.21 — 百炼 SSE 断连 job was cancelled 根因修复
- **v3.6.20**（746eb8f6f·2026-08-11）: 版本号 3.6.20；v3.6.20 — 回滚 v3.6.19 + MCP SDK 升级 0.15.0
- **v3.6.18**（a5d40c4bd·2026-08-11）: v3.6.18 — classifyAll 缓存 (域工具构建 O(N*M) 热点)
- **v3.6.17**（27aa5167f·2026-08-11）: v3.6.17 — 深度功耗优化 + 其他功能入口挪底
- **v3.6.16**（6ea9647c3·2026-08-11）: v3.6.16 记录 (高级功能入口 + 深度整理)；v3.6.16 — 高级功能统一入口 + 设置页整理 + 代码清理
- **v3.6.15**（86aac5096·2026-08-10）: v3.6.15 — 生成时 WakeLock 保活 + 断流退避改回线性
- **v3.6.14**（9cd409e72·2026-08-10）: v3.6.14 — 断流重试退避指数化 + 回复连接全链审查
- **v3.6.13**（1305f7a11·2026-08-10）: v3.6.13 — 审批默认直接执行 + 延迟自动回复开关
- **v3.6.12**（130210d4c·2026-08-10）: v3.6.12 追加记录 (420口径 + 技能扫描加固)；v3.6.12 — 计数口径统一 407 + 技能扫描加固 (缓存偶发退化)
- **v3.6.8**（dedc5c4bc·2026-08-09）: 批次收尾 (v3.6.8~v3.6.11 总结 + 缓存对照结论)；v3.6.8 — 自定义域恢复 (工具系统报废根因)
- **v3.6.11**（b7c4e37f2·2026-08-09）: v3.6.11 — 数据监控 Token 估算完全回滚 (用户: bug 太多)
- **v3.6.10**（45bb8461e·2026-08-09）: v3.6.10 — 缓存前缀根治 + 删除假成功 + 计数统一
- **v3.6.9**（f75626309·2026-08-09）: v3.6.9 — 子域计数 + 联网图标删除 + Token 实时估算 + 额度 TTL
- **v3.6.7**（8a6bd2de5·2026-08-09）: v3.6.7 — 写后全入口强制同步 (全局写版本号) + 域工具执行日志；v3.6.7 — 域管理工具执行日志 (验证模型改配置是否真实生效)
- **v3.6.6**（56b6359c3·2026-08-09）: v3.6.6 — 模型配置同步系统 Prompt (缓存键含模型)
- **v3.6.5**（6f6b28a1d·2026-08-09）: v3.6.5 — 中断后挂起收敛 (watchdog 分阶段) + 数据源实时性确认
- **v3.6.4**（0391db9fe·2026-08-09）: v3.6.4 — 能力模块完整 (MCP/Skills/内置) + 工具对照 398 统一
- **v3.6.3**（dd79ca0b0·2026-08-09）: v3.6.3 — 能力模块完整 (MCP/Skills/内置) + 域管理计数统一 counts
- **v3.6.2**（04f3ba7b1·2026-08-09）: v3.6.2 — 域管理总数 398 口径 + 设置结构最终化
- **v3.6.1**（9e08156be·2026-08-09）: v3.6.1 — 计数回 398 口径 (框架工具不计入用户域计数)
- **v3.6.0**（93a05a03b·2026-08-09）: v3.6.0 稳定版 — 能力模块分组 + MCP 5min + 全面自查；v3.6.0: P3全量 — Hooks执行引擎 + Agent编排 + 事件总线
