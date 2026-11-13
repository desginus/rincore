---
schema: rincore-agent-handoff/v1
project: RinCore
package: me.rincore.app.hub
version: 5.3.77
commit: c603b87e3
updated: 2026-11-13
status: ACTIVE
owner: integration-agent
canonical: true
next_action: 真机验证手机操控；再决定是否接 ADB/Shizuku shell 或 OCR
---

# RinCore 全栈交接文档（唯一主文档）

> 这份文档是 RinCore 的**常驻交接入口**。新 Agent 接手先读这一份；每次交付后必须更新它。
> 代码是事实，CI 是证据，交付文档是索引，记忆是提醒；四者冲突时按这个顺序判断。

## 0. 30 秒摘要

- 当前版本：**v5.3.77**，包名 `me.rincore.app.hub`，单一工作模式（Mika 双模式已于 v5.3.51 回撤）。
- 发布：<https://github.com/desginus/rincore/releases/tag/v5.3.77>（APK 由 `c603b87e3` 构建）
- CI：**43441103268**，`build` + `unit-tests` 均 **success**。
- APK：`app-arm64-v8a-research.apk`，**79,950,950 B**。
- 代码发布点：`c603b87e3`（v5.3.77 APK/CI 对应这个 commit）。发布后 main/adopt 还会包含纯文档交接提交；**最新 HEAD 以 `git rev-parse HEAD` 为准**，不要拿本文档里的 SHA 当代码 SHA。
- 这一版的核心：3.2.0 非手机项收口 + **手机操控 P1**（A11y 读屏、DSH 三级点击、截图视觉网格、支付/银行拦截）。
- **最大未完成**：手机操控没有 ADB/Shizuku shell 与 OCR；没有真机实测；ChatListPreview 长列表性能未专门优化。
- 接手第一件事：读第 6 节验证命令，跑 `bin/audit fast` + `bin/rc check`；先证明环境可信，再改代码。

## 1. 接手与退出规则（先读）

### 1.1 新 Agent 接手（30 分钟路径）

1. 读本文件第 0、2、3、4、5、6 节。
2. 读最新一份 `docs/handoffs/*.md`（当前是 `2026-11-13-integration-agent-v5.3.77.md`）。
3. 在 `rincore-adopt` 执行：`git status`、`git log -3`、`bin/doctor`、`bin/audit fast`、`bin/rc check`。
4. 在 `docs/handoffs/CLAIMS.tsv` 声明你要改的路径（模板见第 8 节）；没有声明前不要动共享文件。
5. 在最新交付文档的 `## 接手回执` 填 `accepted_by / accepted_at / base_commit / verified_commands / open_questions`。
6. 只改你声明的范围；跨范围改动先写“需要协调”，不要直接改别人的文件。

### 1.2 交付 Agent 退出（每次必做）

1. 代码通过 `tree-sitter`（变更文件 0 ERROR/MISSING）+ `bin/rc check`。
2. 跑 `bin/audit fast`（或完整 `bin/audit`），记录退出码。
3. 发版按第 7 节；未发版也要写清“未发版/未实测”。
4. 写 `docs/handoffs/YYYYMMDD-<agent>-<topic>.md`（模板见第 9 节）。
5. 更新本文件第 0、5、6、11、13 节。
6. 更新 `docs/KNOWN-ISSUES.md`（新增/关闭条目）。
7. 写长期记忆（路径 + 版本 + 未完成项）。
8. `git status` 干净，记录 HEAD；不要 force push，不要回滚别人的改动。

### 1.3 绝对禁止

- 不删除用户数据；不清理工作区、备份、上传附件；不动 keystore/签名凭据。
- 不跳过测试/lint/混淆来“让构建通过”；要跳过必须先说明代价并让用户决定。
- 不擅自升级/降级 AGP、Gradle、Kotlin、NDK、JDK 大版本。
- 不把“CI 绿”当“功能已验证”；UI/设备行为必须单独标注“未实测”。
- 不在文档、日志、提交里写密码、token、keystore 口令、API key。
- 不回滚旧版本；整体对齐 3.1.0 语义，不回滚会让旧问题重现。
- 版本号只改第三位，不进位（5.3.77 → 5.3.78）。

## 2. 当前项目事实

| 项 | 值 |
|---|---|
| 产品 | Android AI 聊天/工作客户端，自用工具 |
| 当前形态 | **单一工作模式**（无双模式外壳、无 Mika 代码） |
| 分支 | `adopt-3.0.0` 为当前工作分支；`main` 为发布分支 |
| 当前版本 | `versionName = 5.3.77`，`me.rincore.app.hub` |
| 发布 APK | research 变体 `app-arm64-v8a-research.apk` |
| 代码规模 | 约 1372 个 `.kt`（`bin/origin stats`） |
| 文档 HEAD | 发布后另有纯 docs/AGENTS 提交；`git rev-parse HEAD` 为准 |
| 来源分布 | UPSTREAM 55.5% / MODIFIED 18.1% / PORTED 14.4% / PORTED-MODIFIED 5.1% / OURS 5.5% / DELETED 1.5% |
| 无循环依赖 | `bin/deps cycles` 通过 |
| 工作区 | `/workspace/rincore-adopt`；父 worktree `/workspace/rincore-src`（不能移走） |

**用户边界**：用户不懂代码但懂需求；基座（构建、工具链、发布、框架）由 Agent 负责；基座之外的功能取舍由用户决定。需要签名、发布凭据、改包名/签名/ABI/SDK/工具链大版本时先问。

## 3. 架构地图（先看路径，不要全仓读）

### 3.1 模块

| 模块 | 职责 | 备注 |
|---|---|---|
| `app` | UI、ViewModel、ChatService、工具接线、设置 | 唯一顶层应用模块 |
| `ai` | Provider/SDK 抽象、流式解析、消息模型 | 最大依赖边 `app → ai` |
| `common` | 通用工具 | 叶子底层 |
| `workspace` | proot 工作区/文件/Shell | `SandboxPort` 类能力 |
| `document` | PDF/DOCX/PPTX/EPUB 解析 | 叶子模块 |
| `markdown` / `highlight` | Markdown 解析、代码高亮 | 渲染链关键 |
| `material3` | Material 颜色扩展 | 叶子 |
| `speech` / `search` / `web` | 语音、搜索 SDK、Web 服务 | `search` 按用户定案不移植 |

### 3.2 关键文件（排障入口）

| 主题 | 路径 |
|---|---|
| 会话状态/原子写 | `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt` |
| 生成主循环 | `app/.../data/ai/GenerationHandler.kt` |
| ChatService/压缩/落库 | `app/.../service/ChatService.kt` |
| ChatVM | `app/.../ui/pages/chat/ChatVM.kt` |
| 设置字段/持久化 | `app/.../data/datastore/Settings.kt`、`SettingsFields.kt`、`PreferencesStore.kt` |
| 记忆仓库 | `app/.../data/repository/MemoryRepository.kt` |
| 记忆注入 prompt | `app/.../data/ai/prompts/MemoryPrompts.kt` |
| 记忆工具 | `app/.../data/ai/tools/local/MemoryTools.kt` |
| 工作区工具 | `app/.../data/ai/tools/WorkspaceTools.kt` |
| 工作区 manager | `app/.../data/workspace/WorkspaceManager.kt` |
| 工具区/矩阵 | `app/.../data/ai/tools/routing/`、`ToolPreviewBuilder.kt` |
| 手机操控服务 | `app/.../phoneuse/PhoneUseAccessibilityService.kt` |
| 手机操控工具 | `app/.../data/ai/tools/local/PhoneUseTool.kt` |
| 手机操控控制器 | `app/.../phoneuse/PhoneController.kt` |
| 手机手势指示 | `app/.../phoneuse/GestureIndicator.kt` |
| 手机 UI | `app/.../ui/components/message/tools/PhoneUseToolUI.kt` |
| 手机设置入口 | `app/.../ui/pages/assistant/detail/AssistantLocalToolPage.kt` |
| 公式（Compose） | `app/.../ui/components/richtext/LatexText.kt` |
| WebView 渲染 | `app/src/main/assets/html/mark.html` + `assets/html/vendor/` |

> 更完整的“我要改 X 去哪”见 `docs/CODE-NAVIGATION.md`；模块依赖见 `docs/STRUCTURE-MAP.md`。

## 4. 领域契约（不要凭印象改）

### 4.1 会话

- `ConversationSession.apply/updateConversation` 是内存写入口；`phase` 状态机 + `isReady = initialized || newConversation`。
- 发消息/流式/编辑一律**原子读改写**，禁止“读快照 → 改 → 整对象写回”。
- `saveConversation` 是危险写入口：已做 id 一致性硬闸、空消息守卫、per-conversation 锁；结构性变少必须显式 `forceReplace/allowShrink`。
- `ChatVM` 与 `onCleared` 的引用计数必须成对；草稿清理走 `DraftStore.clearNow`，不要用 `save(\"\")`。

### 4.2 设置

- 设置不是整体 JSON，而是 `DataStore<Preferences>` + `SETTINGS_FIELDS` 手动映射。
- **每加一个 Settings 字段必须登记**，否则只存内存、重启丢失；`SettingsPersistenceTest` 是白名单硬门禁。
- 单值损坏不能拖垮整份设置（逐字段 decode 保护）。
- 版本号只改第三位。

### 4.3 记忆

- 唯一表 `memory_file`；两个维度：`source`（active/dream）× `layer`（assistant/folder/conversation）。
- 删除=软删 `invalidated_at`；`purgeFile` 才是物理删除。
- 注入/工具/UI 必须共用同一可见性判据；`memory_read/list` 不能绕过 mount。
- 旧格式（无 frontmatter）默认不注入；结构化的 `profile/preferences` 有特殊处理。
- 记忆工具由 `assistant.enableMemory` 控制，不参与工具区“顶层直连”。

### 4.4 工作区

- 三层身份：执行沙箱 / 文件夹 / 个人资料库；`files/` 是用户资料，`linux/` 是 rootfs，`tmp/` 可清理。
- `workspace_shell` 默认复用常驻会话；`session=\"none\"` 回一次性；取消时杀会话、不重跑。
- `/workspace` 的语义在 shell 与文件工具间有差异，改路径解析先看 `docs/WORKSPACE-MODEL.md` 与 `WorkspaceTools` 描述。
- 文件操作优先专用工具（read/write/edit/glob/grep），不要用 shell 做文件操作。

### 4.5 手机操控（本版新增）

- 入口：助手设置 → 本地工具 →「手机操控」；开启时若服务未连接，跳系统无障碍设置。
- 服务：`PhoneUseAccessibilityService`，系统显示「RinCore 手机操控」；仅 API 30+。
- 工具：`phone_use`，动作 `observe/screenshot/tap/long_press/swipe/scroll/type/press/open_app/list_apps/wait`。
- 点击：**节点 `ACTION_CLICK` → 最近可点击祖先 → 坐标手势**；长按同理。
- 安全：支付/银行类硬拦截；本应用在前台时不允许操作；悬浮窗建议但不强制。
- **未实现**：ADB/Shizuku shell、OCR；不要声称三传输路径已齐。

### 4.6 工具面

- 顶层工具已收敛；搜索统一 `workspace_search`；旧文件管理工具已退场。
- 工具矩阵的唯一数据源是 `buildToolList`；新增工具必须同时进池/归类/预览，三处都要验证。
- HugeIcons 使用必须 import；`grep -nx` 检查 import（前缀会误判）。

## 5. 当前状态（v5.3.77）

### 5.1 已完成

| 项 | 状态 |
|---|---|
| 3.2.0 快捷消息追加/长按替换 | ✅ |
| 模型列表手动刷新 | ✅ |
| 自动压缩（回合结束 + 工具轮中途） | ✅ |
| Claude redacted thinking / signature | ✅ |
| Mistral 多轮 422 | ✅ |
| 通知权限 / 用户 CA / specialUse FGS | ✅ |
| 500k 调试对话 / 默认提供商 / STA1N | ✅ |
| Web UI lastUsage/stepCount | ✅ |
| 手机操控 P1（A11y + 三级点击 + 截图 + 拦截） | ✅（未真机实测） |

### 5.2 未完成 / 风险

| # | 项 | 说明 |
|---|---|---|
| P0 | 手机操控真机验证 | 读屏/点击/输入/截图/拦截均未在设备上验证 |
| P1 | 手机 shell/OCR 路径 | Mika 三路径只完成 A11y + 视觉截图；ADB/Shizuku/OCR 未接 |
| P1 | ChatListPreview 长列表性能 | 搜索框已固定在列表外，但未做 `produceState + Dispatchers.Default` 重构 |
| P2 | 31 条存量测试债 | 见 `docs/测试债-v5.3.26.md`；不要删断言变绿 |
| P2 | `SubAgentRegistry` 的 `runBlocking` | health audit 仍报；启动阶段已 eager 初始化，非主线程，未阻塞发版 |
| P2 | 文档时效 | `KNOWN-ISSUES.md` 曾停在 v5.3.39；本文件是新的主入口，后续必须同步 |
| P3 | 真机性能数据 | 无 framestats/冷启动数据，不做猜测性 Compose 优化 |

### 5.3 不要做的方向

- 不合并 Mika（已回撤，保持单工作模式）。
- 不做“用户画像/人格分析”记忆；记忆按工作知识（playbook/project/pitfall/domain/convention）组织。
- 不换渲染引擎（bettertext）；保持 Compose + JLatexMath + 自研 Markdown。
- 不做多模型真实请求竞速（费用 N 倍）；用连接探针 + 历史 TTFB。
- 不删 `memoryentity` 死表（Room schema 一致性，收益不成比例）。

## 6. 验证与证据（接手先跑）

```bash
cd /workspace
./bin/selfcheck          # 检查器可信吗
./bin/doctor             # 环境/worktree/版本/快照
./bin/audit fast         # env/static/wire/health/render
./bin/rc check           # 变更面语法/import/符号/资源
./bin/deps cycles        # 模块循环依赖
./bin/origin stats       # 文件来源分布
```

**最近实测（2026-11-13，v5.3.77）**：

| 检查 | 结果 |
|---|---|
| `selfcheck` | **14/14 探针** |
| `doctor` | **14 通过 / 0 警告 / 0 失败**（可用空间约 66G） |
| `audit` | **AUDIT-OK**；wire 0 断线；MAINIO 0 / COMPOSE-IO 0；render 26/26 |
| `rc check --all` | **RC-CHECK-PASS** |
| `deps cycles` | **无循环依赖** |
| CI | **43441103268**，build + unit-tests success |
| Release | v5.3.77，draft=false，prerelease=false，APK 79,950,950 B |
| SHA | 代码发布点 `c603b87e3`；文档 HEAD 以 `git rev-parse HEAD` 为准 |

**验证纪律**：

- 只看退出码，不看“输出看起来没问题”。
- CI 绿 ≠ 设备通过；文档必须把“已验证/未验证”分开写。
- 每次发版必须核对三个 SHA；`rc release` 只推 main，adopt 要补推（v5.3.46 起 dev-build 不再自动触发）。
- 长等待用 `workspace_job` + `bin/watch`，禁止 `sleep + grep` 轮询。

## 7. 发布协议

```bash
cd /workspace
./bin/selfcheck
./bin/audit fast
./bin/rc check

cd /workspace && ./bin/rc release 5.3.78 "$(cat /workspace/.rel-title-xxxx.txt)"
# 用 workspace_job 包住；CI 完成后：
./bin/watch <runId>
# 核对：
git rev-parse --short HEAD origin/adopt-3.0.0 origin/main
curl -s .../releases/tags/v5.3.78   # draft=false / APK 大小
git tag -f checkpoint/v5.3.78 HEAD && git push -f origin checkpoint/v5.3.78
git push origin HEAD:adopt-3.0.0
```

- 标题/说明写文件再用 `$(cat ...)`，避免反引号被 shell 执行。
- `rc release` 会 commit 工作区；工作区有半成品时不能发。
- `RELEASE OK: None` 可能是 API 抖动，必须用 `curl /releases/tags/<tag>` 复核。
- 版本号 5.3.x；不进位。

## 8. 多 Agent 并行协议（本项目的正式协作方式）

### 8.1 核心原则

**并行的是“任务”，串行的是“同一路径的写”。**

- 每个 Agent 在独立 worktree/分支工作，交付物是一份标准交付文档 + 可验证的 commit。
- 同一时刻，**一个文件只能有一个 writer**；共享文件（`AGENTS.md`、本文件、`settings.gradle.kts`、`build.yml`、`ToolUI.kt`、`ChatService.kt` 等）必须走 claims。
- 集成 Agent（当前 `integration-agent`）负责：合并、跑全量检查、发版、更新主文档。
- 不允许两个 Agent 同时改同一文件后“谁后推谁赢”；冲突在 claims 阶段解决。

### 8.2 Claims（占用声明）

`docs/handoffs/CLAIMS.tsv`（UTF-8，Tab 分隔）：

```tsv
claim_id	agent	branch_worktree	scope_paths	status	started_at	expires_at	note
HANDOFF-DOC	integration-agent	rincore-adopt/adopt-3.0.0	docs/AGENT-HANDOFF.md,docs/handoffs/**	ACTIVE	2026-11-13	2026-11-20	主文档维护
PHONE-P2	unassigned	-	app/.../phoneuse/**,PhoneUseTool.kt	OPEN	-	-	等 P1 真机验证后再接
```

规则：

1. 开工前写入 claim；`status=ACTIVE` 才能改。
2. `scope_paths` 尽量具体到文件/目录；不要写 `app/**`。
3. 超过 `expires_at` 未续期 = 自动释放；其他人可以接管，但要在交付文档说明。
4. 集成 Agent 有最终合并权；发现未声明改动可以拒绝合并。
5. 文档类共享文件（本文件、README、KNOWN-ISSUES）由集成 Agent 串行更新；其他 Agent 只提交“建议 patch”。

### 8.3 分支/worktree

- 每个并行 Agent 用独立 worktree：`git worktree add /workspace/rincore-<agent> -b agent/<name>-<topic> origin/adopt-3.0.0`。
- 不要共用 `rincore-adopt` 工作区同时改文件（会互相覆盖/CI 误判）。
- 交付时把 commit SHA 写进交付文档；集成 Agent cherry-pick 或 merge，不直接覆盖。

### 8.4 交付文档生命周期

`DRAFT → READY_FOR_REVIEW → ACCEPTED → MERGED`；失败/等待用户= `BLOCKED`。

- `DRAFT`：作者还在改，不要合并。
- `READY_FOR_REVIEW`：代码/命令/证据齐全，等待集成 Agent。
- `ACCEPTED`：集成 Agent 已复验并签字。
- `MERGED`：已进 main/adopt 并发布（或明确“仅文档”）。
- `BLOCKED`：写清阻塞点、需要谁决策。

### 8.5 证据等级（交付文档必须标注）

| 级别 | 含义 | 可信度 |
|---|---|---|
| E0 | 只读代码/推测 | 不可作为完成依据 |
| E1 | tree-sitter/rc check 静态通过 | 只能证明语法/接线 |
| E2 | 本地 kotlinc/单测实跑通过 | 证明纯逻辑 |
| E3 | CI build + unit-tests 绿 | 证明可编译/既有测试 |
| E4 | 真机/设备实测通过 | 功能完成的最低证据 |
| E5 | 发布产物 + 三 SHA + release 核验 | 交付完成 |

**没有 E4 的功能，一律写“未实测”，不能写“已修复”。**

## 9. 交付文档模板（出站）

复制 `docs/handoffs/TEMPLATE.md`，文件名 `YYYYMMDD-<agent>-<topic>.md`。

必须包含：

```markdown
---
schema: rincore-delivery/v1
agent: <name>
task: <one line>
version: 5.3.x
base_commit: <sha>
head_commit: <sha>
status: DRAFT|READY_FOR_REVIEW|ACCEPTED|MERGED|BLOCKED
evidence: E0|E1|E2|E3|E4|E5
started_at: YYYY-MM-DD HH:mm
finished_at: YYYY-MM-DD HH:mm
---

## TL;DR
## 范围（改了什么/没改什么）
## 变更文件
## 验证（命令 + 退出码 + 证据等级）
## 未验证/已知风险
## 决策与取舍
## 下一步
## 接手回执
```

写交付文档的硬要求：

- 版本/commit/CI run/APK 大小/SHA 必须写具体值。
- “已验证”和“未验证”分开；不要用“应该/大概/看起来”。
- 每个风险写“触发条件 + 影响 + 建议”。
- 命令必须能复制执行；不要写“跑一下测试”这种无法复核的话。
- 不写密码/token/keystore。

## 10. 如何快速读懂另一个 Agent 的交付文档

5 分钟顺序：

1. 看 frontmatter：`status`、`evidence`、`base_commit → head_commit`、`version`。
2. 看 `TL;DR`：它声称完成了什么。
3. 看 `范围` + `变更文件`：有没有越界、有没有改共享文件。
4. 看 `验证`：命令、退出码、CI run、APK/SHA；E0/E1 直接降级。
5. 看 `未验证/风险`：这里通常藏着真正的问题。
6. 看 `决策与取舍`：是否与用户定案冲突。
7. 看 `接手回执`：是否已有人验收；没有就自己复验。

**红旗**：

- 只有“CI 绿”，没有设备证据，却说“已修复”。
- 只写“改了 X”，没有文件路径/commit。
- 没有 `base_commit`，无法判断 diff 范围。
- 把“没做”写成“已绕过/兼容”。
- 交付文档版本号与 `build.gradle.kts` 不一致。
- 改共享文件但没有 claim。

## 11. 下一步建议（按优先级）

1. **P0 手机操控真机验证**：按 `docs/手机操控-v5.3.77.md` 的测试步骤，重点看无障碍服务能否开启、读屏元素、三级点击、输入、截图、支付拦截。
2. **P1 手机操控 P2**：根据真机结果决定接 ADB/Shizuku shell 或 OCR；不要一次接三种，先做可失败旁路。
3. **P1 ChatListPreview 性能**：先拿 `framestats`/recomposition 数据，再做 `produceState + Dispatchers.Default`；不要凭猜测改渲染链。
4. **P2 测试债**：按 `docs/测试债-v5.3.26.md` 逐条重写；全量 unit-tests 转硬门禁。
5. **P2 文档同步**：本文件、`KNOWN-ISSUES`、`3.2.0-移植进度`、交付文档索引保持一致。

## 12. 用户决策记录（不要反复问）

| 决策 | 内容 |
|---|---|
| 版本号 | 只改第三位，不进位 |
| 回滚 | 不回滚旧版；整体对齐 3.1.0 语义 |
| 双模式 | v5.3.51 已回撤；当前单一工作模式 |
| 记忆 | 唯一表；不做用户画像；按工作知识组织 |
| 渲染 | 不换 bettertext；保持 Compose + JLatexMath + 自研 Markdown |
| 手机 | A11y 主路径 + DSH 三级点击；shell/OCR 后续按真机结果定 |
| 发布 | `rc release` 只推 main；adopt 补推；三 SHA 一致 |

## 13. 变更日志

| 日期 | 版本 | 变更 |
|---|---|---|
| 2026-11-13 | 5.3.77 | 手机操控 P1（A11y/三级点击/截图/拦截）；3.2.0 非手机项收口；建立交接协议 |
| 2026-11-13 | 5.3.76 | 工具轮中途自动压缩；Web UI lastUsage |
| 2026-11-13 | 5.3.75 | 3.2.0 非手机首批；自动压缩（回合结束）/Claude/Mistral |
| 2026-11-13 | 5.3.74 | DSH 鲸娘宠物 + 快捷消息/模型刷新 |
| 2026-11-12 | 5.3.x | 3.2.0 调研、工作区定位、悬浮球改造 |
