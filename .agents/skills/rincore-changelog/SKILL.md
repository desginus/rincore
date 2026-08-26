---
name: rincore-changelog
description: "[中优先级·RinCore开发对照] RinCore 完整版本更新日志。触发词：版本历史、更新日志、changelog、这个版本改了什么、版本对比、回滚历史、版本链。任何需要了解 RinCore 某版本改动/某功能何时引入/何时回滚时加载。不涉及：Bug 根因细节（用 rincore-bug-record）、方案决策（用 rincore-decisions）。"
---

# RinCore 更新日志（v2.9.4 → v3.10.0）

## v3.9.9 ~ v3.10.0（媒体/渲染/网络/代理路由系列，2026-08-23）
- v3.9.9: MCP 待命文案修正 + 密钥快捷切换（备注/掩码列表）+ Excel 横向浏览 + PPT 流式占位符
- v3.9.10: PDF 双缓冲缩放 + DOCX 排版还原 + PPT schemeClr 主题色 + 用量视图切换持久化 + 密钥管理落盘
- v3.9.11: MCP 图标回 MessageBlocked + PPT 滚动容器 + DOCX rPr 字号字体还原 + ApiKeyQuickSwitcher 文件持久化
- v3.9.12: 原版 2.4.11 全量移植 — 网络设置（UA/代理/SOCKS5/连接测试）+ 备份选择覆盖确认 + V4 Flash Vision + contextLength + MiMo
- v3.9.13: UA 纯净（撤 RikkaHub 标识，留空不发自定义 UA）
- v3.9.14: 澎湃 OS4/骁龙 8E 设备环境检测日志（pageSize/SOC/ABI），16KB 对齐全量核查达标
- v3.9.15: 代理开关 + 部分开启按模型勾选（ProxyRoute 路由，默认 client 剥离代理，named("proxy") 独立）
- v3.10.0: 稳定版 — 勾选弹窗滚动修复 + Claude 连接池代理路由修正 + 版本号里程碑 3.10

## 历史教训（防重踩——每次改动前必读）

## 历史教训（防重踩——每次改动前必读）
- **产品线**：v3.8.44-45 曾建 WaterHub B 类产品线（flavor 拆分），用户 3.9.1 令废弃回滚——只保留 A 线 RinCore 单产品构建。教训：未与用户对齐的产品线扩张立即废弃，勿自行推进
- **功能界线**：用户只要求移植的功能就只移植，不自作主张附带其他功能（v3.8.44 附带工具入口/漂浮字幕被要求全量回滚）
- **limitContext 滞回策略 ↔ 缓存**：v3.3.0 引入（2.4.5 适配）→ v3.3.5 回滚（**缓存机制报废**）→ v3.3.12 确认回滚。函数仍在 Message.kt 但未启用（无 contextMessageSize 字段）——**勿重新启用**，启用即破坏缓存前缀
- **缓存锚点/注入隔离**：v2.9.5 注入隔离（BEFORE_SYSTEM_PROMPT 变独立 user 消息）引入 SETTINGS 协议违规 → v3.4.5 修复——**协议合规 > 缓存边际收益**
- **DeepSeek Responses reasoning**：3.5.4~3.5.6 猜测性修复全废（服务端格式不成熟）→ 3.5.7 按官方协议（明文 content）→ 3.5.8 工具轮相邻 assistant 消息 → 3.5.9 起搁置（等官方更新）
- **工具执行无超时**：3.5.9 withTimeout 60s 兜底——工具挂起不永久阻塞生成
- **缓存"卡-跳-线性"**：DeepSeek 服务端磁盘缓存机制（构建延迟秒级+固定间隔切分+SWA 独立单元）——客户端不可控，已入库 decisions D2

## v3.8.43-45 / v3.9.0-3.9.2（token 统计修正 + 产品线废弃回滚 + 全文档渲染，2026-08-22~23）
- v3.8.43: token 统计口径修正 — TokenBudgetTracker 逐轮 prompt 求和虚高改最近轮真实口径; cachedTokens 钳制 min(cached, prompt)
- v3.8.44: WaterHub 功能批次（空对话漂浮字幕/工具入口/UserTool 体系/胶囊窗渲染）— 后被用户要求全量回滚（见 3.9.1）
- v3.8.45: 产品线拆分（flavor 双线 rincore/waterhub + 双 artifact）— 后被废弃
- v3.9.0: 主线 3.9.0（flavor 拆分产物）
- v3.9.1: 按用户指令回滚 v3.8.44 非渲染功能（漂浮字幕/工具入口/UserTool 全删）; 渲染扩展为全文档类型（HTML WebView 动态交互 / PDF PdfRenderer 逐页 / DOCX 段落提取 / XLSX 表格 / CSV 表格 / 文本 pre）
- v3.9.2: 整体清理 — B 线 waterhub 废弃移除（flavor/资源/文档全删，恢复单线构建单 artifact），知识库重写，README 徽章同步
- 经验: 用户功能界线 = 只做被点名的功能; 产品线不经用户确认不建; 每个版本归档当日完成

## v3.8.35-42（Zen 通道定稿 + MCP 懒连接 + 运行日志修复，2026-08-22）
- v3.8.35: 流式 onFailure 诊断补齐（请求体摘要 REQ + 响应体 RESP 600 字符）— 400/500 拒绝可直接定位触发字段
- v3.8.36: 内容级截断检测（textTail 强截断特征: 未闭合代码块/逗号分号顿号冒号破折号收尾/连接词收尾）
- v3.8.37: textTail 兼容 content 数组形态（Zen 网关 chunk 结构）；分享逻辑重写 — 列表页导出全部、详情页单轮分享（文件名带轮次时间戳 ID）
- v3.8.38: Zen chunk 结构取证诊断（deltaKeys + delta 原文）— 实证 ox 文本走 reasoning_content 字段
- v3.8.39: 无正文场景可见化（仅思考无正文时报错保留思考）
- v3.8.40: ox 系（displayName/modelId 含 ox/x-preview）reasoning_content 提升为正文，对齐 opencode 客户端行为
- v3.8.41: MCP 懒连接 — 启动/配置变更只登记待连接，首次工具调用才建连（根治重启即主动连接服务器；原版同样启动连接但用户强制要求，自行斟酌）
- v3.8.42: 思考链/正文自适应修正 — 流中思考保持思考链实时显示，仅流结束无 content 时缓冲思考正文化补发；删除一刀切提升（v3.8.40 副作用: 思考混入正文）
- 经验: Zen 无完成信号模型的行为按连接层事实判定（行完整性）+ 内容层运行期自适应，不按模型名猜；补丁式修复在三轮内收敛为运行时自适应

## v3.8.33-34（Zen 关流物理判据 + 运行日志持久化重写，2026-08-22）
- v3.8.33: SSE 行完整性物理判据 — v3.8.32 的"一律未确认"对 ox 系每轮弹错; 改为最后一行 JSON 解析成功=服务端完整发完(正常完结, 不弹错), 残缺=真断流(保留内容+报错)。对照原版: 原版只认 [DONE] 无 Zen 适配, 不可对齐, 自行斟酌。
- v3.8.34: 运行日志持久化重写 (整段重写不打补丁) — 旧实现只存最近一条且纯内存重启丢失; 新增 LogSessionStore (每轮对话=一个会话, ID=精确时间戳 yyyyMMdd-HHmmss-SSS, 最多保留 10 轮, 文件快照持久化); 运行日志页改为两级: 轮次列表→点击进该轮报告; 新增全量 Markdown 导出 (FileProvider 分享); CallTracer 时间戳 ID + finishTraceIfActive 兜底 (onCompletion 全路径收尾); 清空按钮保留
- 验证路径: 多轮对话后重启, 运行日志保留最近 10 轮; 点轮次看报告; 导出 md 可分享
- 注意: 导出文件走 filesDir/exports (已有 file_paths 覆盖); 系统返回键在详情态先回列表

## v3.8.33-34（Zen 关流物理判据 + 运行日志持久化重写，2026-08-22）
- v3.8.33: SSE 行完整性物理判据 — v3.8.32 的"一律未确认"对 ox 系每轮弹错; 改为最后一行 JSON 解析成功=服务端完整发完(正常完结, 不弹错), 残缺=真断流(保留内容+报错)。对照原版: 原版只认 [DONE] 无 Zen 适配, 不可对齐, 自行斟酌。
- v3.8.34: 运行日志持久化重写 (整段重写不打补丁) — 旧实现只存最近一条且纯内存重启丢失; 新增 LogSessionStore (每轮对话=一个会话, ID=精确时间戳 yyyyMMdd-HHmmss-SSS, 最多保留 10 轮, 文件快照持久化); 运行日志页改为两级: 轮次列表→点击进该轮报告; 新增全量 Markdown 导出 (FileProvider 分享); CallTracer 时间戳 ID + finishTraceIfActive 兜底 (onCompletion 全路径收尾); 清空按钮保留
- 验证路径: 多轮对话后重启, 运行日志保留最近 10 轮; 点轮次看报告; 导出 md 可分享
- 注意: 导出文件走 filesDir/exports (已有 file_paths 覆盖); 系统返回键在详情态先回列表

## v3.8.32（Zen 无信号关流可见化分流 — 杜绝静默截断，2026-08-22）
- 用户反馈 v3.8.31 特判过宽：isOpencode&&hasReceivedData=>完成 把 ox 系免费模型的中途掐断也吞成正常完成，输出途中忽然断且无任何报错（静默截断）
- 根因：信号层面无法区分 Zen"正常完结"与"服务端中途掐断"（均无完成信号）
- 修复（按模型分流）：grok 系（有 usage/cost 生态、实测稳定）已收数据关流=>完成；ox 系免费模型等=>OpenCodeStreamUnconfirmedException，已生成内容随 chunk 流保留、不回滚不重试、上层明确报错"服务未发送完成信号即关闭连接，已保留已生成内容"；DeepSeek 等严格路径不变
- 诊断：事件计数 + 最近 5 条原始数据缓冲，关流/失败时打印
- CI 两次编译失败修正：异常类误插 import 区（imports only allowed in beginning of file）；本地无 SDK 增量缓存假象，验证以 CI 为准

## v3.8.31（OpenCode Zen 无信号关流误判断流修复，2026-08-22）
- 用户报告 ox-alpha-free（opencode.ai/zen/go/v1）SSE 流在完成前被服务器关闭，rollback & retry 7 次全败后 generation_failed（10 分钟耗尽）
- 根因：Zen 网关完成时无任何完成信号直接关连接（v3.6.78 grok 特判靠 usage/cost 行识别，ox 系连 usage/cost 都不发）；onClosed 误判断流 → 每轮重试服务端重新生成。另修独立缺陷：finish_reason 判定在 message!=null 分支内，delta:null + finish_reason 结尾漏判
- 修复：isOpencode && hasReceivedData → 关闭即完成（真断流无数据仍重试）；finish_reason 上移 choice 层；onClosed 诊断增强
- 注意：ResponseAPI onClosed 为宽松语义无需同步；DeepSeek 严格判定不受影响

## v3.8.29（MCP 参数类型恢复 / 记忆 ID 时间戳 / 锁定最新消息，2026-08-20）
- MCP 图表工具 -32602 根治：按 inputSchema 类型恢复字符串化参数（array/object 解析回结构化、number/boolean 转原生），递归 properties/items
- 记忆 ID 改创建时间戳 YYMMDDHHMMSS（Int 自增 → Long 时间戳，Long 防溢出），同秒冲突顺延
- 进入对话锁定最新一条消息（原超界索引 clamp 顶部对齐导致长消息显示开头；改 scrollOffset=Int.MAX_VALUE 底部对齐）

## v3.8.28（上下文压缩边界智能判定，2026-08-20）
- 新增 ContextCompressor：按对话轮 + token 60% 定位保留边界（非固定条数），从最新轮往旧累计切片，四舍五入到最近整轮
- 轮粒度保证：始终保留最近至少一轮（绝不压缩刚发内容）、始终压缩至少一轮（压缩必真实发生）；轮数少时直接保留一轮不依赖 token
- UI 仍按条数显示推荐值可手动微调；ChatService keep≤0 禁止全压缩

## v3.8.26-27（管理子域寻址兜底 / 请求 tools 顶层白名单，2026-08-20）
- v3.8.26：管理子域父域直接工具/子域行/下钻工具查询统一走 resolveDomain 兜底（任何路径格式解析回 classified key）
- v3.8.27：请求 tools 顶层白名单硬过滤 — 除批准框架 + 豁免 + 引擎工具（memory_tool/invoke_tools）+ 已加载域工具外一律剔除并 Log.e，Skill/插件不得跳脱 invoke_tools 暴露顶层

## v3.8.25（管理子域数量 0 修复，2026-08-20）
- 管理子域页面所有子域显示 0 工具根因：customSubs 取 CustomDomain.name 短名（非 normalizedFullPath）→ classified 完整路径查表落空；同轮删除匹配失效
- allSubs 改用 unifiedView.tree[parentDomain] 统一信息源头（防幽灵/完整路径/同源），isCustom/删除匹配改 normalizedFullPath

## v3.8.24（工具域目标列表统一信息源头 + 卡顿根治，2026-08-20）
- 移动工具目标列表/筛选 chips 全部改用 unifiedDomainView.tree 同一上游（与域分类管理页/layer1/invoke_tools/list_domains 同源），废除自拼 ToolDomain.entries + customDomains
- 点击卡顿根治：预构建工具名→域映射一次（原每 chip 点击对 400+ 工具重复分类）
- 删除的域经 isValidDomain 全源过滤永久消失

## v3.8.22-23（压缩位点持久化/抽屉删助手/工具域系统修复，2026-08-20）

- **v3.8.23**：工具域系统修复 — 对照页真校验（域内合计 vs 池总数，完全一致/请对照bug）；exemptFromDomainTools 持久化缺失根治（移出域管理重启失效）；自定义空域保留进域树（invoke_tools 可找到新建域）；移动工具目标列表路径字典序排序（前缀聚合）+ 内置空壳幽灵域剔除
- **v3.8.22**：压缩位点重启丢失修复（ConversationEntity 加 compress_retentions 列 + Room v27→v28）+ 抽屉更多页删除"助手"入口

## v3.8.12-19（渲染抽动/压缩重做/抽屉块状/清理功能，2026-08-19）

- **v3.8.19**：压缩位点条状窄 UI + 去红色（改基础色）+ 改名"上下文压缩管理" + 查看显示压缩摘要（不再累计原文）；收尾文档与 tag
- **v3.8.15-18**：抽屉块状三入口（搜索/查询/清理）+ 清理聊天内容（3 个月/1 个月/1 周前，置顶或含收藏跳过，DAO getCleanupCandidates）——期间三次 CI 编译修复（SpringSpec/TweenSpec 顶层属性、@Composable 归位、UIMessagePart 包名 me.rerere.ai.ui、RoundedCornerShape import）
- **v3.8.14**：压缩机制重做——CompressRetention 留存位点（最多 3 个，时间戳 + 原文 + 摘要），restoreCompressAt 级联撤销，旧 compressedContext 兼容迁移
- **v3.8.12-13**：输出完成抽动修复——animateContentSize 参数化（TweenSpec(0)/SpringSpec），loading 翻转不再改修饰符链
- **v3.8.9-11**：用量页精确时间（12 小时制 + 8 时段标注）+ 液态玻璃分享后恢复（ON_RESUME 重建背景纹理）；v3.8.11 曾漏改 versionName（净版本跳号）
- **v3.8.8**：断流重试 7 次 5 秒内（前 3 次指数 200/400/800ms + 后 4 次固定 900ms）+ 用量页剩余时间进位（天/周）
- **v3.8.5-7**：Anthropic message_stop 断流检测（无 message_stop 关闭=断流重试）+ SSE 诊断日志（TraceLogger takeTagged + sse_diag metrics into 运行日志页）+ 节流回 50ms（5ms 实测掉帧式顿挫）
- **v3.8.3-4**：OpenCode 中转优化——UI 节流 50ms + ClaudeProvider watchdog（opencode 首包 120s/流中 180s）

## v3.7.1-7.6（渲染稳定 + 连接池，2026-08-18）

- **v3.7.6**：animateContentSize 流式期间禁用（生成完成恢复）+ 思考链滚动防抖 100ms；Claude/Anthropic 独立连接池（keepalive 300s + pingInterval 30s）
- **v3.7.1-7.5**：渲染批量稳定（流式顿挫修复链起点）+ Claude 连接池细化

## v3.7.0（插件系统定稿，2026-08-17）

- 插件装 dock 目录（plugin.json + skills + .mcp.json），工具 plugin__ 前缀，MCP 手动 connect；清理 v3.6.112 自动桥接残留
- 死代码清理（ClawPluginRegistry）+ createPluginSkillTools 缓存

## v3.6.101-123（对齐标注/工具改名/插件系统攻坚/MCP 回滚，2026-08-16 深夜）


- **v3.6.123**：registeredBridgeCommands 字段恢复（编译修复）
- **v3.6.121-122**：MCP 连接回滚收尾 — 启动时自动清理 settings 里 plugin__ 前缀服务器残留 + 助手白名单条目；registerPluginBridges 方法彻底删除
- **v3.6.120**：MCP 连接回滚 — 插件自动桥接（v3.6.112 引入）两个调用点全部移除，用户实测该机制破坏大部分 MCP 连接（127.x 无法连接/连接关闭）
- **v3.6.119**：.mcp.json 落盘 + 桥接 id 复用（后者随回滚失效）
- **v3.6.118**：插件列表空的致命根因修复 — installFromParsed 从未被调用（死代码），plugin.json 从未写入；P0-P2 全项处理（readTimeout 120s/临时清理/锁文件真名/execute exists 检查/invoke_tools 实时域）
- **v3.6.117**：插件真实名目录（重复安装覆盖）+ 删除能力（目录+桥接记录+settings 服务器+刷新四环节）
- **v3.6.116**：plugin.json 元数据写入 + readManifest 加 _parsed.json 兜底
- **v3.6.115**：McpServerConfig 类名修正（编译）
- **v3.6.113-114**：json put import/workspaceId 断行/buildJsonObject 全限定（编译）
- **v3.6.112**：插件与技能彻底隔开 — plugin__ 工具 + 插件桥接（后回滚）
- **v3.6.111**：collectAsState import（编译）
- **v3.6.110**：插件技能落回插件目录 + readManifest 支持 .claude-plugin + migrateLegacySkills
- **v3.6.109**：插件技能落技能目录（沙箱 /skills 挂载可见）— 后被 3.6.110 纠正（用户要求插件技能分开）
- **v3.6.108**：getCurrentAssistant import + scope.launch（编译）
- **v3.6.105-107**：插件技能根注入 + 插件页合并 ClawHub 源 + 强制启动（技能/插件"对话开始时强制启动"开关）
- **v3.6.104**：方法域改未分类 + Skills 搜索框 + invoke_tools 生态引导 + 设置页重排（任务自动化/数据与日志分区）
- **v3.6.102-103**：工具改名能力（MCP 页+工具页）+ callTool 60s 兜底 + ClawPluginRegistry 重命名
- **v3.6.101**：ReasoningPicker 残留 import 清理 + changelog 补录

## v3.6.92-100（技能修复/插件收尾/2.4.10 移植/全量对齐标注）

- **v3.6.100**（8162620b）：全量原版对齐标注 — 对齐地图文档（767 文件四类清单）+ 核心链路 7 文件专项标注 + UI/数据层 33 文件专项标注 + 小差异/自研文件自动标注。纯注释零行为变化
- **v3.6.97-99**（4435e386）：
  - 插件收尾：github 源无 token 走 codeload ZIP；clawhub 直连失败自动探测代理端口
  - 原版 2.4.10 六项移植：豆包搜索/替换规则拖动排序/阿里云 ASR 重写/工作区识别+AGC/加密推理不重发明文/英文句首大写+推理选择简化
  - 注意：ReasoningPicker 刻度删除、DashScope 默认端点 realtime — 与 2.4.10 对齐
- **v3.6.96**（9978198e）：非法 MCP 服务器名不弹窗（工具剔除+日志）— 测试残留不再炸全部消息
- **v3.6.95**（bbf48d36）：插件 skill 提取修复（.claude-plugin 根查找 skills）+ ClawHub 代理配置（设置→生态）
- **v3.6.94**（cec1a6b6）：clawhub 网络栈 OkHttp 化（走系统代理）+ plugin_install 错误区分（文件不存在/无 manifest）
- **v3.6.93**（9c2dd4bf）：plugin_install 假成功根治（无 manifest 明确判失败 + 临时文件唯一名）
- **v3.6.92**（6619d7d1）：技能执行去 enabledSkills 过滤 — 修复技能全灭（生成全量与执行过滤的矛盾）

## v3.6.90-91（移出域管理 + 框架集收缩，2026-08-16）

- **v3.6.91**（60969272）：clawhub_install/clawhub_search 移出 FRAMEWORK_TOOL_SET（8→6 实际常用）— 归系统域经 invoke_tools(系统) 加载，不再始终注入。注意：请求体 tools 数组 -2，缓存前缀一次性重建
- **v3.6.90**（7fd365af）：
  - 工具列表对话框新增「移出域管理」开关（Settings.exemptFromDomainTools）：开启后工具与框架工具同等（始终注入、不进域统计），全链路动态框架集 frameworkSetOf = FRAMEWORK_TOOL_SET + 豁免集（GenerationHandler 分层/ToolRouter 计数/DomainTools/各 UI 页口径统一）
  - MCP 传输类型教堂窗对齐：Streamable HTTP 折两行 → 短标签 HTTP/SSE/Stdio 严格单行，描述补完整协议名

## v3.6.88-89（插件独立系统，2026-08-16）

- **v3.6.89**（005f5e21）：SettingPluginsPage onBack 参数缺失编译修复
- **v3.6.88**（f8665f20）：插件从 Skill 下单拎出来，独立系统
  - 设置「能力模块」Agent Skills 之后、内置工具之前新增「插件」入口（SettingPluginsPage：列表/状态徽标/刷新/空状态说明）
  - 插件技能脱离 Skill 列表：不再经 SkillManager 注入，独立工具 plugin__<名>__skill
  - 工具域新增「插件」域：plugin__ / mcp__plugin__ 前缀统一归插件域
  - PluginManager 扩展：hasSkill/pluginsUiSnapshot（McpStatus→中文映射）/createPluginTools 全量注入
  - 注意：插件技能工具命名从 skill__plugin__ 改为 plugin__<名>__skill，tools 数组一次性重建缓存

## v3.6.86-87（插件格式，2026-08-16）

- **v3.6.87**（572ee78a）：Uuid.fromString 不存在编译失败修复 — 桥接 id 改 Uuid.random + 运行期缓存（registeredBridges/bridgeIds 保证重复 refresh 不重启进程）
- **v3.6.86**（b6de3f1e）：「插件」新格式 — 插件 = 技能 + 桥接合一
  - 目录：workspace files 区 .plugins/<插件名>/，plugin.yaml（name/description/command）+ SKILL.md + 桥接脚本
  - Skill 部分：skill__plugin__<名> 正常读取（SkillManager extraSkillRoots 泛化，前缀随根）
  - 桥接部分：command 经 workspace launchProcess 常驻启动，STDIO 走 MCP JSON-RPC，工具注册 mcp__plugin__<名>__<工具>（McpManager.addClient viaWorkspace）
  - PluginManager（data/plugin）+ App 启动刷新 + WorkspaceRepository.getAllWorkspaces
  - dsh__ 技能源（v3.6.85）保留 — 纯技能层兼容，带工具的插件用插件格式

## v3.6.x（2026-08-15 起，grok 排查/性能/DSH 生态）

- **v3.6.85**（cf6c93dc，2026-08-16）：DeepSeek Harness 插件生态兼容 + 深度清理
  - SkillManager 额外技能根：扫描 workspace files 区 .dsh/skills 与 .agents/skills（DSH 官方发现根），技能名 dsh__ 前缀，只读保护（delete/save 拒绝 dsh__）
  - WorkspaceRepository.refreshDshSkillRoots + App 启动刷新
  - 清理 generateText conversationId 残留参数链 + MeshGradient 注释
  - 注意：DSH 技能经 skill__dsh__xxx 工具加载，冷启动体积零增长
- **v3.6.84**（ac45e1bc）：回滚 v3.6.81 Markdown 流式降级
  - 用户明确不接受"输出完成后统一渲染"，恢复流式期间逐 chunk 实时解析渲染（mapLatest 后台解析保留）
  - 注意：流式期间全文解析的 CPU 开销是该体验的固有代价
- **v3.6.83**（63e672bb）：对齐原版依赖与构建配置（用户实测原版流畅）
  - Compose BOM 2026.06.01 → 2026.08.00；appcompat 1.8.0/coil 3.5.0/firebase-bom 34.17.0/baselineprofile rc01
  - release 移除 R8 混淆与资源收缩（原版不 minify，堆栈恢复可读）
  - 注意：APK 体积增大（无混淆），诊断堆栈可读性提升
- **v3.6.82**（6e336c8a）：静态卡顿根治
  - MeshGradientBackground 4 个无限漂移动画静态化（固定相位）——此前每帧重绘全屏 Canvas + haze 重采样，静态也 GPU 满载
  - 输入栏毛玻璃 8dp → 4dp
- **v3.6.81**（2f9fae52）：渲染热点批次一
  - rememberAvatarShape 非 loading 不再创建无限动画（静态头像每帧空转修复）
  - MarkdownBlock 流式降级（live 参数）——v3.6.84 已回滚
- **v3.6.80**（46323dfa）：对齐原版 grok 请求行为（原版 grok 正常）
  - opencode.ai reasoning 分支删 grok 跳过与 xhigh 映射；else 分支删 MAX 封顶
  - 删自研 x-opencode-session 头（原版无此头且 grok 正常，疑似 400 来源）
- **v3.6.79**（75620e27）：400 报错 REQ 改完整请求体
- **v3.6.78**（003b1849）：grok 流式完成判断（OpenCode Zen grok 不发 [DONE]/stop，只在结尾发 usage/cost 后关连接——此前误判断流）+ 报错带请求体
- **v3.6.77**（4d9b906b）：grok-4.6 / grok-build-0.1 模型定义（OpenCode Zen）
- **v3.6.76**（35e01a25）：延时自动回复改对话框交互

## v3.5.x（传输层回滚期 → 当前）
- **v3.5.39**（a30993fe）：工具域系统整体重构重写（DomainInfo 单一数据源 + resolveDomain 统一寻址 + 防幽灵）；versionCode 锁定 200
- **v3.5.38**（8f258c22）：域标识 normalizedFullPath 全视图统一 + onClosed 中断可见化
- **v3.5.34**（稳定版，2026-08-07）：域分类深层梳理 6 bug（技能子域 override/UI/move/删除/规范化）
- **v3.5.33**（583607cf）：STDIO 启动自动回退 workspace + 名称可修改
- **v3.5.32**（eebfc04c）：编辑重生成恢复 + mcp_connect stdio workspace 启动
- **v3.5.31**（e15b84d8）：编辑版本切换恢复 + STDIO 全链路对齐（持久化+绑定）+ 崩溃加固
- **v3.5.30**（484b728b）：文件夹实时焦点 + assistantId 脏值
- **v3.5.29**（ade4fbcd）：编辑崩溃/默认助手补回/Skill 描述
- **v3.5.28**（6d7793b0，2026-08-07）：编辑消息发送即重新生成 + 对话默认存储文件夹（焦点文件夹）
- **v3.5.27**（cbc4fd44，2026-08-07）：MCP STDIO workspace 桥接（launchProcess 常驻 + viaWorkspace 配置）
- **v3.5.26**（ac889337，2026-08-07）：工具域分类体系重构
  - 自动分类按名称结构化（类别__分类字段），替代模型分类
  - Skill 工具 skill__ 命名 + 归「技能/<名>」子域（与 MCP 同层级）
  - 内置/自定义域对齐 + 空壳域过滤 + 孤儿数据清理 + search_domains 修复
  - 注意：工具名 skill_ → skill__ 变化 → tools 数组一次性重建缓存
- **v3.5.25**（2026-08-07）：缓存根因修复 + 助手删除放开
  - MCP 静态化恢复（仅 Error 过滤移除）：tools 数组配置决定，服务器波动不断缓存
  - 助手删除：DEFAULT_ASSISTANTS_IDS 限制取消，仅剩最后一个时禁止删除
  - 注意：请求体无变化（工具声明静态化不改变请求体内容）
- **v3.5.24**（a2b2ac5d，2026-08-06）：缓存机制回滚 3.5.17
  - WorkspaceReminderTransformer/McpManager/GenerationPrompts 对齐 520b4cb0
  - get_location 真实定位优先（缓存仅兜底）
  - 中断 3s join 超时 + 思考链立即停表
  - 注意：请求体恢复 3.5.17 状态（show 说明行移除 → system 变化，缓存单次重建）
- **v3.5.19**（8e705d16，2026-08-06）：工具数量注入
  - 回滚全量注入回归（冷启动 100K+ → 分层动态恢复）
  - layer1 注入工具池总数 + 域分布；帮助文本各域工具数
  - 分层注入定为铁律（用户决策，D7 修正）
- **v3.5.18**（7937d962，2026-08-06 正式版）：工具体系重构
  - workspace_show_file：写入与展示解耦（胶囊窗仅认 show）
  - search_domains：关键词/标签反查域位置，mcp/skill 类别过滤
  - skill_<清洗名> 独立工具：Skill 直接可用（decisions D8）
  - 工具加载机制取消：所有工具直接可用，无加载/缓存引导
  - 缓存阶梯化根治：tools 定值化 + MCP 声明静态化（decisions D7）
  - 注意：请求体/system 变化，缓存单次重建后稳定
- **v3.5.18-beta1**（9e73b04d，2026-08-06）：workspace_show_file 展示解耦
  - 胶囊窗仅认 show 工具，写入/编辑不再自动显示（用户决策，decisions D6）
  - 工具风格对齐 workspace 族：校验存在、默认免审批、入 FILE 域与框架集
  - 全链路对齐：ToolUI 渲染器/审批页/域管理页/字符串/system 提示
  - 缓存：请求体变化单次重建（已评估）
- **v3.5.17**（520b4cb0 完成，2026-08-05 连接根治系列）：整体根治中断与缓存问题
  - 缓存回滚（1784ba75）：use_skill 移出框架工具集 + UNCLASSIFIED 域移除——3.5.16 请求体改动导致缓存率暴跌，请求体恢复稳定
  - 静默恢复移除（1f9d350e/60389917）：三 Provider hasData→close 全部删除——v3.1.0 引入的中断被吞根因
  - v2.9.8 SSE 重试移植（98f5d7d6）：未收到数据自动重试 5 次指数退避——2.x 稳定机制，3.5.0 回滚丢失
  - HTTP/2 完全禁用（b27253cd）：protocols 只留 HTTP_1_1——DeepSeek ALPN 协商 h2 后 PROTOCOL_ERROR 根治
  - 连接配置对齐 v2.9.8（75c8e595）：ConnectionPool(12,60s) 防陈旧连接 EOF；writeTimeout 120s；pingInterval 30s
  - 收尾完整（f9ea683a）：onCompletion NonCancellable + stopGeneration 显式收尾——计时器/灵动岛不停根治
  - MCP 状态撕裂修复（7ecda960）：getTransport 包 runCatching + Error 状态工具过滤 + 报错明确化
  - 经验文档固化（7ecda960）：B18/B19/B20/B21/B22 + 缓存教训写入 bug-record
- **v3.5.14**（a4b73fc0 起）：连接稳定性加固 + MCP STDIO + effort 映射
  - ResponseAPI 断线恢复（对齐 ChatCompletionsAPI：stream reset/timeout 等保留部分数据）
  - SSE 无数据看门狗 120s（两个 API）——挂起快速失败，不再无限等
  - readTimeout 10min → 3min
  - DeepSeek reasoning_effort 映射（medium→high/xhigh→max；AUTO 不触发——非根因，保留无害）
  - MCP 第三种连接 STDIO（getTransport 实现 + UI 三并列 + command 拆分 + 进程生命周期）
  - 生成错误上下文日志（CallTracer ERROR/generation_failed + baseUrl/msgs/tools/thinking）
  - 补: CallTracer 移入 launch + 级联清理移到 onEdit + baseUrl 类型修复 (编译修复)
- **v3.5.15**（1de789a4 起）：MCP STDIO 导入支持 + HTTP/1.1 优先
  - parseMcpServersFromJson 补 stdio 分支（command 必需 + args 可选；原只认 url → stdio 配置被丢弃报"没有找到正确的 MCP 配置"）
  - HTTP/1.1 优先（protocols HTTP_2,HTTP_1_1 → HTTP_1_1,HTTP_2）：v3.1.0 引入的 HTTP/2 优先在弱网下 SETTINGS 帧丢失/超时 → DeepSeek 服务端报 'required settings preferences not received'（原版默认 HTTP/1.1 优先无此问题）【SETTINGS 帧根因修复】
- **v3.5.16**（cd51a2fa 起）：缺陷报告 v5 全部 7 项
  - P0-1 根因: 子请求（标题/建议/背景文本/工具分类）首条 user 无 system → DeepSeek 报 'Required SETTINGS preface not received'（主请求有 MessageProtocol 保证, 子请求漏了）→ OpenAIProvider.generateText 统一兜底前置空 system；Trace ID 从 model.id 改随机 UUID；CallTracer 计数修正（trace_end 实参先于 add 求值）
  - P0-2 错误弹窗细化（ErrorCard 点击展开完整详情/复制完整堆栈）
  - P1-1 MCP 状态文本（Idle 引导/Error 消息/Connected 工具列表）
  - P1-2 UNCLASSIFIED('未分类') 显式兜底域（原'方法域'兜底）
  - P2-1 思考链计时兜底（finishedAt null 不再持续累计）
  - P2-2 list_domains 移除'已删除域'残留行
  - P3-1 use_skill 入框架工具集（懒加载直接可用）
- **v3.5.13**（fa61f6ca）：删除模型级联清理引用（设置项 9 字段/收藏/助手绑定）
- **v3.5.12**（bcec24a0）：热力图/统计页崩溃——json_each 展开损坏 JSON（json_valid 过滤 + VM 兜底）
- **v3.5.11**（fbd5e11e）：移植原版 SystemPromptBuilder（stable/volatile 分区）——缓存前缀稳定【缓存正常化关键版本】
- **v3.5.10**（772d00e5）：G4 缓存诊断增强——msg_fp 消息指纹（跨请求对比定位缓存断点）
- **v3.5.9**（e22e7fc9）：工具执行超时兜底（withTimeout 60s）+ reasoning 发送诊断日志
- **v3.5.8**（690f9d7f）：DeepSeek 工具轮 reasoning 相邻 assistant 消息（Responses API）
- **v3.5.7**（8b36dca3+df796efb）：DeepSeek reasoning 回传按官方协议重写（明文 content 替代 summary）——核心修复
- **v3.5.4 ~ v3.5.6**：**废弃**（reasoning 猜测性修复全部无效）——d36eac68 回滚到 3.5.3 纯基线
- **v3.5.3**（67701acf/c46a9607）：70K 根治——MCP 懒加载移植（冷启动 65K→~6K）【干净基线】
- **v3.5.2**（05fc3485/e36562aa）：系统梳理——toolsInternal 构成诊断 + 版本号补正
- **v3.5.1**（10a62b5d）：冷启动 70K 注入修复——v2.9.4 工具 systemPrompt 瘦身移植到 else 分支（layer1Prompt 无调用方传入，恒走 else 全量注入 264 工具 → 70K；改后只注入 7 框架工具）
- **v3.5.0**（5dacddfd）：传输层整体回滚到 3.2.2（checkout 8 文件：OpenAIProvider/ChatCompletionsAPI/ResponseAPI/Message/GenerationHandler/PlaceholderTransformer/PromptInjectionTransformer/ChatService）——用户决策：补丁式修复无法解决连接中断；保留流式落盘/保活/权限/闹钟/FGS/DI

## v3.4.x（传输层补丁期——已全部回滚）
- **v3.4.10**（d2cf333a）：Responses API reasoning 回传格式修正（summary 元素类型按 host：deepseek→reasoning_text，其他→summary_text；content 内 reasoning_text 被拒 unknown variant）
- **v3.4.9**（40f941ca）：Responses API thinking 回传——content 内加 reasoning_text 块（后被 v3.4.10 修正位置）
- **v3.4.8**（ee79b24b）：ChatCompletions DeepSeek 强制 reasoning 回传（host.contains("deepseek")）+ 千问缓存机制确认（隐式缓存自动/Qwen3.7 门槛 ~2000 tokens/命中率非 100%）
- **v3.4.7**（9e71dfb6）：空返回自动重试（流式/非流式，平台偶发空流）+ 流式诊断（SSE done/failure chunks+finish_reason、gen_return）
- **v3.4.6**（0029f256）：生成中切后台回答丢失修复——流式增量落盘（GenerationChunk.Messages 每步 saveConversation + onCompletion 兜底）【回滚后保留】
- **v3.4.5**（011d0692）：Required SETTINGS preface 根治——BEFORE_SYSTEM_PROMPT 合并进 system（v2.9.5 注入隔离引入的协议违规）
- **v3.4.4**（3647bb07）：定时任务崩溃——FGS type=none 被 Android 16 禁止；ForegroundInfo 指定 dataSync + Manifest SystemForegroundService 覆盖声明【回滚后保留】
- **v3.4.3**（b7061b06）：闹钟 AI 工具移植（alarm_create/list/delete）+ UI 入口藏到数据与存储
- **v3.4.2**（1561747f）：闹钟页崩溃——AlarmScheduler 未注册 DI（R8 混淆显示 LanguageCircleKt）；权限页加固（build 兜底/startActivity try-catch/SDK 过滤）【回滚后保留】
- **v3.4.1**（13a2ab89）：缓存断层诊断日志（cache: prompt/cached + 骤降>50% 输出缓存断层!）+ CronJobWorker 前台服务 + AlarmReceiver 协程化 + ACCESS_BACKGROUND_LOCATION

## v3.3.x（稳定性期）
- **v3.3.14**（6af4e530）：后台保障链移植（阶段 1/3）——AlarmManager 双通道 + 设备闹钟 + 电池工具 + DB migration 26
- **v3.3.13**（3b22848c）：Required SETTINGS preface 修复——sanitizeToolCallSequence 循环内每步调用 + 移除所有未配对 tool_call
- **v3.3.12**（5ff81bde）：缓存逻辑整体回滚到 2.4.5 移植前
- **v3.3.10-11**：工具域分类管理 UI 修复（工具池对齐/统一分类/幽灵域清理/skill 显示）+ Skill 计入工具统计
- **v3.3.8**（5fd777f6）：16 bug 全量修复——域管理原子化/move 校验与 skill 挂载/skill 体系修复
- **v3.3.6**（aa4b0a30）：消息序列清洗 + skill-MCP 统一到 invoke_tools + 缓存稳定；system 完全静态化（移除 use_skill 动态注入）
- **v3.3.7**（35182bfe）：审批死循环修复 + UI 合并

## v3.2.x（移植前稳定期）
- **v3.2.2**（7bcc022b）：域分类引擎白板化 + 域变更事务化 + MCP 硬前缀映射——【传输层回滚基线】
- **v3.2.0**（616af07b）：F1 根因修复——MCP 动态工具注入全链路

## v2.9.x（缓存优化期）
- **v2.9.5**（43cf1b47）：缓存锚点 + 注入隔离（BEFORE_SYSTEM_PROMPT 变独立 user 消息——引入 SETTINGS 协议违规的根源，v3.4.5 修复）；system prompt 缓存优化（53780f85）
- **v2.9.4**（97faaa9a）：5 工具入域管理 UI + system prompt 瘦身（分层模式仅注入框架工具 systemPrompt）+ 五工具归域 + Skill 开关 + 动态域注入

## 版本号/versionCode 对照（近期）
| 版本 | versionCode | 提交 |
|------|:---:|------|
| v3.6.74 | 200 | 80b33ee9 |
| v3.6.73 | 200 | 7c024fe8 |
| v3.6.72 | 200 | 465d10fe |
| v3.6.71 | 200 | 215928b2 |
| v3.6.70 | 200 | 0a5a08a8 |
| v3.6.69 | 200 | 48d4bf0a |
| v3.6.68 | 200 | 65dce1be |
| v3.6.67 | 200 | c5635397 |
| v3.6.66 | 200 | ab42958b |
| v3.6.65 | 200 | 23b8c31a |
| v3.6.64 | 200 | 8c1cba20 |
| v3.6.63 | 200 | 77b88e23 |
| v3.6.62 | 200 | 9697693e |
| v3.6.61 | 200 | ec08a85e |
| v3.6.60 | 200 | 12b80983 |
| v3.5.1 | 162 | 10a62b5d |
| v3.5.0 | 161 | 5dacddfd |
| v3.4.10 | 160 | d2cf333a |
| v3.4.9 | 159 | 40f941ca |
| v3.4.8 | 158 | ee79b24b |
| v3.4.7 | 157 | 9e71dfb6 |
| v3.4.6 | 156 | 0029f256 |
| v3.4.5 | 155 | 011d0692 |
| v3.4.4 | 154 | 3647bb07 |
| v3.4.3 | 153 | b7061b06 |
| v3.4.2 | 152 | 1561747f |
| v3.4.1 | 151 | 13a2ab89 |
| v3.3.14 | 150 | 6af4e530 |
| v3.3.13 | 149 | 3b22848c |
| v3.3.12 | 148 | 5ff81bde |

## 移植状态（fork 三功能）
| 功能 | 状态 |
|---|---|
| 后台保活（AlarmScheduler 双通道 + SCHEDULE_EXACT_ALARM + 电池优化引导） | ✅ 阶段 1（6af4e530） |
| 权限自动发现（PermissionInventory + SettingPermissionsPage + BatteryTool） | ✅ 阶段 2（89fbf274） |
| 工作流（WorkflowEngine + 触发器 + DB + MCP 工具） | ⚠️ 阶段 3 未完成（执行层/UI/MCP 集成未做） |

## 版本线理顺（2026-08-03 深度审计）
- **两轮循环**：第一轮 v2.9.x→v3.8.x（07-11~07-30 上午，已废弃）；第二轮 v3.2.0→v3.5.x（07-30 下午~今，当前）
- **硬重置**：07-30 13:16 v3.8.0→v3.2.0（版本号复用）
- **唯一可靠标识：versionCode**（全程递增，当前 164）——此后版本对照一律以 versionCode 为准
- 分支：backup-bad-merge（第一轮存档）/ desk（Desk 面板）/ upstream-try（上游 2.4.5 适配试验）

## v3.11.0 / v3.11.1 (2026-08-24/25)
- v3.11.0: v3.10.4 内容原样 + versionCode 统一锁定 9999 (任意替换安装)
- v3.11.1: Console Go 400 根因闭合 — 千问历史无签名 thinking 块丢弃;
  非官方 host 私有字段 gating (v3.10.14); BOM strip + max_tokens 8192
  兜底 + REQ_META (v3.10.15); 推送格式恢复 (Release 带 changelog)
- 对照包: v3.10.16 (自研深修线) / v3.10.17 (原版对齐线) / v3.10.15-base
- 300+ 补丁期 (v3.10.4→v3.10.17) 收敛: 400 根因=千问 thinking, 
  修复已入 v3.11.1 main

## v3.11.2 / v3.11.3 / v3.11.4 (2026-08-25/27)
- v3.11.2: MCP 数据层整体回原版 2.4.12 (McpManager 委托版 + McpSessionRegistry
  状态机 + OAuthCoordinator; 启动即连无懒加载); SettingMcpPage 图标回原版
  (Idle→MessageBlocked 等); STDIO viaWorkspace 作为兼容分支保留
- v3.11.3: Minimax 400 第三候选修复 — time_reminder 不污染 tool_result 消息
  (Tool part 消息跳过合并); input_json_delta 空 id Tool 丢弃; REQ_META 升级
  为逐消息块类型统计 (下次 400 直接定位被拒块)
- v3.11.4: 工具调用后"卡死"根治 — 断流重试时间预算 75s (旧: 7轮xwatchdog
  60-180s=20+分钟静默); 预算耗尽保留已输出内容+明确报错 (不再回滚丢弃);
  重试期间 UI 提示"生成中断, 正在自动重试 (n/7)"; TraceLogger 工具轮标记
