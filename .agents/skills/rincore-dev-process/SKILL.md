---
name: rincore-dev-process
description: "[最高优先级·RinCore开发必加载] RinCore 开发与发布环境全图：Git 工作流（版本纪律/一推一建/savepoint）、日志体系（CI 日志 API/知识库/运行日志）、工具链（scripts/keystore/CI/沙箱）与 Skill 维护。触发词：开发流程、推包、怎么推、Git、版本号、日志、工具、Skill、工作流、CI、热态、探针。任何 RinCore 修改/发布/查证前加载本 Skill 对齐流程。不涉及：具体 Bug 记录（rincore-bug-record）、架构（rincore-project-brief）。"
---

# RinCore 开发与发布环境全图（v4.8.53 期 · 2026-09-26 重写；2026-10-04 补单轮全量纪律与移植终审三件套）

> 本文覆盖：Git → 日志 → 更新环境工具 → Skill 体系。由多次血泪教训固化，**违反任一条都会挨骂**。

## 0. 总则（最高优先）
1. **不再开倒车**：功能性改动只进不退（回植完成于 4.8.48-49）；废弃方案必须用户定版并记录到 rincore-decisions。
2. **一推一建**：版本提交 push 到 main 即自动触发 build.yml——**禁止再手动 workflow_dispatch**（一版双跑=用户挨骂点）；诊断 tag 不在 push 触发范围，才走 dispatch。
3. **版本纪律**：仅动 versionName 第三位（4.8.x→4.8.y）；versionCode 锁 9999 不动；编译修复**不升版本号**（新 commit 同版本名）。
4. **热态判读**：体验类测试必须"装包 → 充分使用数分钟至手感稳定 → 再判"；新装包首用慢=安装冷态（非 bug，见 bug-record B47）。
5. **完成判定**：CI 绿 = 定性完成（用户自行下载 Release APK）；推送后脚本轮询至终态（失败则诊断修复闭环）。
6. **改完先本地核验再推**（括号平衡/import 对照先例/调用点全查/重复注入检查）；CI 失败 = 本地核验失职，拉日志修（不重复盲推）。
7. **诚实**：没证据不装懂（"需要日志"就说需要日志）；时间上报按北京时间。
8. **单轮全量纪律（2026-10-04 用户强定）**：改可以分批，**推送必须一次性**——一轮把全部问题改完再推；**严禁**"先发一版、中途停止、再说下一步"的挤牙膏模式。CI 首跑若因下载 Gradle 网络抖动失败（日志尾部 Connection reset），直接 rerun-failed-jobs，不盲目改代码。

## 1. Git 工作流
- 仓库：desginus/rincore；本地 /workspace/rincore-src（全量历史 1341+ commits）。
- 提交信息：`fix:/feat:/refactor: X.Y.Z — 标题` + 详细正文（根因/修复/教训）；正文多行用 `-m "行1\n\n行2..."`。
- 版本号位置：app/build.gradle.kts 的 versionName；versionCode=9999 恒定。
- 存档点（tags）：savepoint-1 = d6da9490f（4.8.35，渲染基线）/ savepoint-2 = ac051320d（4.8.37）；回滚用整文件 `git checkout <tag> -- <file>`，禁止整目录 checkout 叠加（重复注入）。
- 诊断 tag：diag-X.Y.Zp 系列（探针包）；tag 推送不触发构建，需手动 dispatch。
- 推送：`git push origin HEAD` 后**必须验证 origin/main SHA 已更新**（不能只看退出码；网络失败重试）。
- 上游对拍：upstream-try remote（原版 tag fetch 用）。

## 2. 日志体系
### 2.1 CI 日志（核验唯一权威）
- 查 run：`GET /repos/desginus/rincore/actions/runs?head_sha=<sha>`（取 run id）。
- 查 jobs：`/actions/runs/<id>/jobs`（job id + 步骤状态）。
- 拉日志：`/actions/jobs/<id>/logs`（`curl -sSL` + Authorization）→ grep `e: \|error:\|FAILED`。
- 轮询：**`/workspace/scripts/ci.sh`（2026-10-19 用户定版：查询 CI 严禁使用其他命令/裸 curl/API——统一本脚本，30s 一轮网页直读）**。子命令：`ci.sh watch <run_id>`（30s 轮询到终态）/ `status` 单查 / `find <分支>` 列 runs / `log <run_id>` 失败日志关键行（唯一用 API 的子命令）/ `release <tag>` 查资产 / `rerun <run_id>` 网络抖动重跑。旧 ci_poll.sh/watch_ci_html.sh 退役为兼容备件。
- **发布产物纪律（2026-10-19 用户警告）**：任何发版只出现正式版 APK——dev-build.yml（oracle）已改为 release 变体编译门禁（compileReleaseKotlin）且**零工件上传**；严禁 debug APK/Dev Debug 版本出现在任何用户可见面。
### 2.2 知识库（随用随更）
- /skills/rincore-changelog（版本链摘要 + FULL_HISTORY.md 全量归档 448 条）
- /skills/rincore-bug-record（B 编号：根因/修复/教训）· /skills/rincore-decisions（D 编号）· /skills/rincore-roadmap
- repo docs：docs/APP_MAP.md（域地图入口）/ docs/WARM_PIPELINE.md / docs/REFACTOR_PLAN.md / AGENTS.md / CLAUDE.md
### 2.3 应用内日志
- 运行日志（CallTracer，release 可见）；诊断埋点必须用 **Log.i**（Log.d 在 release 被滤）；崩溃日志。
- 用户反馈模板：版本号 + 现象 + 复现步骤（+ 运行日志片段更佳）。

## 2.4 检索索引维护约定（v5.0.4 定版 · D3）

- **新文件必带头部标注**：`/* 【域 X·名称】 — 类型 | 地图: docs/APP_MAP.md §X */` + （移植件）`【原版对齐】文件名 | 基线 …` 区块。
- **索引随版本维护**：新增/改名/删除 .kt 时同步更新 `docs/CODE_INDEX.md`（路径+行数+来源+一句话职责）；域级入口变动同步 `docs/APP_MAP.md`；症状新族加入 `docs/SYMPTOM_ROUTER.md`。
- **文档真值分工（P4）**：文档内容真值=沙箱工具链（rin-tools）；软件内 document/ 仅 classic 兜底与预览。

## 3. 工具与更新环境
| 项 | 位置/说明 |
|---|---|
| CI 轮询 | /workspace/scripts/ci_poll.sh（另有 ci-check.sh / git-release.sh / ci_watch.py / restore-env.sh） |
| 构建 | .github/workflows/build.yml：on.push(main) + workflow_dispatch；产出 Release APK（rincore-release） |
| 签名 | /workspace/rincore-keystore（+ CI secrets） |
| 沙箱 | /workspace = Rootfs 工作区（rincore-src / KEEP-* 文件区 / scripts / docs / 概率论素材库） |
| 验证辅助 | /tmp/bracket_check.py（Kotlin 括号精确检查，可重建）；eval_javascript 用于逻辑模拟 |
| 交付物 | GitHub Releases（版本对照以 changelog/SHA 为准） |

## 4. Skill 体系（清单与维护）
| Skill | 职责 | 维护时机 |
|---|---|---|
| rincore-project-brief | 架构总览/SSOT | 架构变化时 |
| rincore-changelog | 版本链 + 全量归档 | 每版本同提交或紧随 |
| rincore-bug-record | B 编号 bug（根因/修复/教训） | 每 bug 修复时（顶部插入，编号递增） |
| rincore-decisions | D 编号决策（定版/权衡/回滚） | 每定版决策时 |
| rincore-roadmap | 路线/待办 | 阶段变更时 |
| rincore-dev-process（本文） | 环境全图 | 流程变更时 |
固定格式：Bug `### B#. 标题（修复版本）` + 现象/根因/修复/教训；决策 `## D#. 标题（版本，定版方）`。

## 5. 热态测试协议（2026-09-26 定版）
1. 安装 → 正常使用数分钟（反复滑动/进出对话至手感稳定）；
2. 再判流畅度/功能；跨版本对比 = 热态 vs 热态；
3. 诊断需求 → 探针包（单变量）+ 明确复现步骤；
4. 反馈：版本号 + 现象 + 步骤；"卡"类反馈附上"冷/热态阶段"。

## 6. 铁律集（高频，违反即错）
- 主线程禁 runBlocking / file IO；onCreate 内任何系统交互必须容错（渠道 name/权限校验）；onCreate 内耗时初始化一律后台线程。
- 改签名前全局 grep 调用点；新符号对照"可用先例文件"核 import；替换锚点含边界符（`)`、模板 `}`）必须完整复现；Kotlin 字符串模板 `$` 需转义。
- 数据层 backfill 上线必须同时验证全档位；DB 升级=启动链最高危（能不用就不用，B31）。
- 断流=连接层优先（清池）；推送/派发网络失败重试并验证结果。
- 导出器不得复用 UI 列表层（B39）；缓存三要素=键一致/防污染/全覆盖（B49）；提示必须真实结果驱动（B51）。
- 渲染调度只做零行为变化优化（D11）；"对齐原版"是基线，渲染行为问题先 diff 上游（B37）。
- 工作区文件放对分区（KEEP-AI工作区/交付区/上传区）；时区北京时间。
- **移植终审三件套**：上游 delta 文件清单逐一闭环（每个文件给出"已移植/合理跳过+理由"）；字符串覆盖用键集合机器比对（输出覆盖 N/缺失 M）；"上游特性已具备"结论必须验到**触发路径三段**（触发条件→文案→失败分支），不得只看功能名存在。
- 批量生成新文件的 package 行：保留上游原文或严格剥离源码根前缀（app/src/main/java/）；移植上游测试前先扫其引用的枚举/常量全集。
- **死代码判定必须枚举文件全部导出符号**：按文件名/首个声明判定会漏（实证：SettingModelPromptPage.kt 的活入口叫 `PromptSettingsPage`，差点误删；反之 SearchPicker.kt 的入口 SearchPickerButton 真死才可删）。删除前 `git show` 出文件全部顶层声明逐一 grep；删除后再全量重扫"未使用字符串"（会浮出该文件独占的键）。
- **线程与调度铁律（v4.8.99）**：调度器走 `AppDispatchers` 单一事实源；UI 只决策与派发（禁 file IO/runBlocking/进程启动）；**沙箱进程族（proot 启动/阻塞等待/PTY/MCP stdio）必须走 `AppDispatchers.Sandbox` 隔离池**，不得占用共享 IO（长命令会饿死 UI 侧 IO）；同步 API（Coil Keyer/ContentProvider）允许 runBlocking 但须不在 Main 且热路径用快照（WorkspaceImageResolver 60s TTL 模式）；长跑服务（心跳/预热/看门狗）须有能效策略（后台无生成暂停，AppForegroundState 判据）。
- **SDK/依赖换源升级**：连接/握手级行为必须**本地复现验证**（kotlinc + 依赖 jar 直跑 SDK，见 bug-record B122）；上游无对应功能面时（如自研 stdio），上游默认值没有参考性；stderr 关键词分类永不 FATAL。
