# RinCore 5.0.0 · 功能性校验总账（FEATURE-PARITY）

> 双校验机制之一：**功能有没有都移过去**。每一行最终必须 = ✅已验（真机或代码级证据）。
> 状态词：⬜未开始 / 🟡进行中 / 🟢已搬（代码在库）/ ✅已验（编译/真机/对账通过）
> 同步物：MIGRATION-LEDGER.md（去向分档）、parity-check.py（代码层对账）、ISSUES-LEDGER.md（问题台账）

## A. B1 基础设施批（本轮）

| # | 功能 | 归属链 | 状态 | 证据 |
|---|---|---|---|---|
| A1 | 构建/身份（me.rincore.app/9999/4.9.0-dev/arm64/去 Firebase） | app/build.gradle.kts / 根 build / settings / stub | ✅已验 | CI run3 绿 |
| A2 | :markdown 渲染模块（vendored 0.7.14+CJK 数学） | markdown/** | ✅已验 | CI run4 绿 |
| A3 | 设置体系（上游五件套 + 自研字段族 40+ 键 + updateWithResult + 数据修复链 + V5 工具矩阵迁移） | data/datastore/** | 🟢已搬 | 编译收敛待验 |
| A4 | 数据模型（Assistant 8 字段/AssistantMemory；Conversation 压缩留存+config+checkpoint+流式快路径；Folder.cwd） | data/model/** | 🟢已搬 | 编译收敛待验 |
| A5 | 数据库（我方链 1→32 + 32→33 上游 6 新表；手写链全注册；MemoryFileDAO 拆分） | data/db/** | 🟢已搬 | 编译收敛待验 |
| A6 | 工具矩阵数据层（ToolZone/ZoneOps/ZoneRouter/迁移/Origin） | data/ai/tools/routing/** | 🟡进行中 | 本轮回植 |
| A7 | 工具池与本地工具（浏览器/文件/闹钟/定时/工作流/子代理工具/上传工具…） | data/ai/tools/** | ⬜未开始 | 收敛队列 |
| A8 | 生成稳定链（重试18/完成门/看门狗/outputStarted/假取消/连接保持/伪装 UA） | ai 模块 + GenerationHandler | ⬜未开始 | 收敛队列 |
| A9 | 子代理体系（Engine/Budget/通知/看板） | subagent/** | ⬜未开始 | 收敛队列 |
| A10 | 上传链（UploadRef/上传码/收件箱/文档注入） | data/files/** | ⬜未开始 | 收敛队列 |
| A11 | CWD/项目包（EffectiveCwd/folder 链/轨道） | data/workspace + ui | ⬜未开始 | 收敛队列 |
| A12 | 沙箱一体（proot/桥/渲染总线/officecli/rin 工具） | workspace 模块 + bridge | ⬜未开始 | 收敛队列 |
| A13 | 服务层（ChatService/GenerationHandler/MediaCreationService…） | service/** | ⬜未开始 | 收敛队列 |
| A14 | 记忆（上游文件库+自动整理；我方单对话记忆保留） | data/ai/memory + memory/** | ⬜未开始 | 收敛队列 |
| A15 | 媒体创作（上游 mediagen+MediaCreation 全量） | mediagen/ + ui/pages/mediacreation | ⬜未开始 | 收敛队列 |
| A16 | 主页面 UI 结构（加号→工具箱/右上角产出/双星轨/无搜索引擎 UI） | ui/pages/chat/** | ⬜未开始 | B2 UI 批 |
| A17 | 渲染显示（我方 markdown 渲染链/地址渲染法/渲染即固化/StableSizeImage） | richtext/** | ⬜未开始 | B2 UI 批 |
| A18 | 消息与胶囊（ChatFileActions/产出/编辑文件/压缩留存 UI） | ui/components/message/** | ⬜未开始 | B2 UI 批 |
| A19 | 工作流/定时/闹钟 UI 与调度 | workflow/** | ⬜未开始 | 收敛队列 |
| A20 | 检索体系（CODE_INDEX/SYMPTOM_ROUTER/ANCHOR_MAP/台账） | docs/** | 🟡进行中 | 本总账/账户 |
| A21 | 玻璃/标题栏（弃我方实验链，采上游 3.0.0） | ui/components/ai/ChatInputHaze.kt 等 | ⬜未开始（采上游） | 上游随底树自带 |
| A22 | 头像 Cookie6Sided 排除（恒 CircleShape） | ui/hooks/AvatarShape.kt | ⬜未开始 | 一行改动，UI 批 |
| A23 | 设置拆分五件+字段回填 | data/datastore/** | ✅已搬（A3 内） | — |
| A24 | 依赖全跟（上游 catalog + compose BOM/版本） | gradle/catalog | ✅已验 | CI 绿基线 |
| A25 | 单对话记忆（conversationScopedMemory） | Assistant + memory 链 | 🟢已搬（模型层） | 消费面待迁移 |

## B. 待开始批次
- B2 UI 批：主页面结构、MD3 Expressive、点名保底项逐项核对（Scale 等改动需用户给例子）
- B3 收尾批：媒体/记忆/压缩/动效融合 + 三遍对账（搬移核验/功能核验/体验核验）

## C. 校验方法
1. **代码层**：`python3 /workspace/scripts/parity-check.py` → parity-report.md（未裁决项=红牌，逐条处置后写回 parity-ledger.tsv）。
2. **功能层**：本表逐行打钩；✅ 的证据必须可复述（CI run 号/真机验证描述/对账脚本输出）。
3. **终局双签**：5.0.0 发布前，本表 0 个 ⬜/🟡，parity-report 的 `*` 未裁决 = 0。
