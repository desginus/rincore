# 已知问题总表（RinCore）

> 用途：**排查前先看这里**，避免把「已知未修」当成「新 bug」重新调查一遍。
> 维护：每次发版把新发现/新关闭的条目更新到这里；状态用 `OPEN` / `PARTIAL` / `CLOSED` / `WONTFIX`。
> 最后更新：2026-11-13（v5.3.77 手机操控 P1 交付）

---

## 一、OPEN（确实还没修，会影响使用）

| # | 问题 | 影响 | 位置 | 备注 |
|---|---|---|---|---|
| I-1 | **全量 `unit-tests` 仍为观察态**（只保留白名单硬门禁） | 白名单外的存量测试失败不阻断发版 | `.github/workflows/build.yml` | **v5.3.26 已改成「全量观察 + 白名单硬门禁」**：`MemoryToolsTest`/`MemoryPromptTest`/`MemoryRepositoryTest`/`ConversationSessionManagerTest` 失败即红；其余 31 条存量债见 I-10。待债清完后把全量转硬 |
| I-3 | **`MemoryDAO` / `MemoryEntity` 死表** | Room schema 里存在但零读写路径 | `data/db/dao/MemoryDAO.kt`、`entity/MemoryEntity.kt` | 删表需再做一次 DROP TABLE 迁移，收益不成比例 |
| I-5 | **`resolvePathById` 全表扫描 O(n)** | 记忆库上千条时，按 id 删/改各扫描一次 | `MemoryRepository.resolvePathById` | 低频操作；真要优化可加 `path_hash` 列 + 索引 |
| I-7 | **历史重复记忆不会自动清理** | v5.3.7 之前产生的同主题多条仍在 | 数据层 | 只能手动删；新写入会自动归一 |
| I-8 | **`unit-tests` 会话层测试覆盖薄** | `ConversationSessionManagerTest` 仅 5 用例，`ConversationSessionTest` 已删 | `app/src/test/.../repository/` | 建议按 `apply` / `initialize` / `isReady` 新语义重写 |
| I-9 | **3.1.1 未移植项** | 功能缺失（非 bug） | 见 `docs/3.1.1-移植清单.md` | `ModelsDevUpdater`（依赖 ModelRegistry 730 行重构）/ `SettingMediaProviderDetailPage`（依赖 ProviderSetting 重构）/ 二维码（用户确认无场景，WONTFIX） |
| I-10 | **31 条存量测试债**（全部 `@Ignore` + 原因） | 这些测试的断言停在旧实现，不覆盖当前行为；已在测试树里保留代码 | `app/src/test/**`，清单见 `rincore-adopt/docs/测试债-v5.3.26.md` | **严禁删断言变绿**；逐条按当前实现重写后移除 `@Ignore`，全量门禁才能转硬。根因：test sourceSet 自 v5.3.19 起编译失败被 `continue-on-error` 掩盖，681 个测试一条都没跑过 |
| I-11 | **4 条「疑似实现回归」**（下一版第一优先） | 可能是测试过期，也可能是实现真回归 | `MemoryRepositoryTest > invalidating a missing path` / `> changing scope of a truly missing path` / `> delete checks the version` 、`MemoryFileTest > keys without an input field` | **v5.3.39 已核查（详见 `docs/稳定性复查-v5.3.39.md` 第三节）**：3 条为「测试过期」（v5.3.19 把 `deleteFile` 改软删、`updateScope` 返真实行数，断言停在旧语义）；`MemoryFileTest > keys without an input field` 经代码推演**应通过**（`source` 不在 `editableKeys`，会被保留）→ 疑似 v5.3.26 批量标 31 条时误标。**未改写断言**：沙箱无 Android SDK/SnakeYAML，实跑不了，改断言可能把真回归改成假绿。下一轮有 CI/真机环境时按此逐条落地 |
| I-13 | **手机操控只有 A11y + 截图视觉** | 没有 Mika 的 ADB/Shizuku shell、没有 OCR；节点树为空/安全窗口/输入法不支持时能力有限 | `app/.../phoneuse/`、`PhoneUseTool.kt` | v5.3.77 已交付 P1；按真机结果决定 P2 接 shell 或 OCR，不假装三传输路径已齐 |
| I-14 | **手机操控无真机实测** | 读屏/点击/输入/截图/拦截均未在设备上验证 | `docs/手机操控-v5.3.77.md` | 当前最高优先级 P0；先按测试步骤验收，再谈 P2 |
| I-15 | **ChatListPreview 长列表性能未专门优化** | 超长对话概览可能掉帧 | `ChatList.kt` 内 `ChatListPreview` | 搜索框已在 LazyColumn 外（功能上固定）；先拿 framestats 再做 `produceState + Dispatchers.Default`，不要猜 |

---

## 二、PARTIAL（修了一部分，仍有残留）

| # | 问题 | 已修部分 | 残留 |
|---|---|---|---|
| P-1 | **输入框光标抖动** | v5.1.14 消掉三处每键开销（补全防抖 / `//@` 窗口读取 / 判据） | IME composition 期行为受系统输入法影响，未彻底验证 |
| P-2 | **公式渲染** | v5.3.15 双线箭头归一化 + v5.3.18 间距参数手写扫描器 | JLatexMath 二进制依赖，**双线箭头不伸展**（取舍：不空白 > 伸展） |
| P-3 | **图片地址** | v5.1.16 单一出口 + v5.0.65 重试链 + v5.3.17 WebView 本地化 | 历史 chat-media 地址失效时仍可能空白（未做反查回填） |
| P-4 | **看门狗 480s** | 保留 5.0.12 甜点值 | 上游挂起时首字最长等 8 分钟；期间后续消息排队（有 UI 可取消） |

---

## 三、CLOSED（已修，保留作对照，防止复现）

按版本倒序，只列**根因值得记住**的：

| 版本 | 问题 | 根因（一句话） |
|---|---|---|
| v5.3.26 | `MemoryFilesTab` 630 行死代码（I-2 关闭） | v5.3.7 统一后无调用点，v5.3.22 删除 |
| v5.3.26 | `MemoryVisibility.kt` 旧判据文件（已删） | v5.3.7 统一后全库 0 调用点，v5.3.9 删除 |
| v5.3.26 | 环境整顿：文档 0 孤儿 / 编码 0 问题 | 3 份记忆文档停在 v5.3.22 却无索引，易误导后续改动 |
| v5.3.26 | 工具矩阵里的记忆工具点不动 / 池构建失败静默 | 归类能分区但工具不在池子里；`catch{emptyList()}` 吞掉整个池 |
| v5.3.26 | 31 条测试从未执行却显示全绿 | test sourceSet 编译失败被 `continue-on-error` 掩盖 |
| v5.3.25 | 工具矩阵找不到记忆工具 | `buildAssistantToolPool` 不含记忆六工具，矩阵唯一数据源就是它 |
| v5.3.24 | 挂载层级设置形同虚设 | 只有注入链应用 mount，工具层 `memory_read/list` 恒走默认档 |
| v5.3.19 | 记忆跨范围覆盖 / 越权读 / 两套删除 | 主键 `(assistant_id, path)` + 读路径忘了过滤归属 |
| v5.3.18 | 所有带 `\\[Npt]` 的公式渲染失败 | `Regex.replace(regex, "\\\\")` 把 `\\[4pt]` 变成单反斜杠 |
| v5.3.17 | 断网时表格/公式全白屏 | `mark.html` 8 个依赖全走 CDN |
| v5.3.16 | `cases` 公式 0×0 | 控制空格 `\ ` + 单 token 分数 `\dfrac13` 两个 jlatexmath 盲区 |
| v5.3.15 | `\xRightarrow` 静默空白 | 库缺宏 + **0×0 尺寸被判成功** |
| v5.3.14 | 老记忆删不掉 | 迁移 path 无前导 `/`，精确匹配落空 |
| v5.3.12 | 做梦记忆删不掉 | 同一张表两套读法（主查询漏了 `invalidated_at`） |
| v5.3.10 | 681 个单测从未执行 | `:app:compileResearchKotlin` 只编译 main |
| v5.3.4 | 模型写的记忆用户看不到 | 文件式 UI `MemoryFilesTab` 零调用点 |
| v5.3.6 | 悬浮窗一渲染就崩 | `Theme.kt` 硬转 `context as Activity` |
| v5.2.0 | 压缩扣费零结果 / 吞第二轮消息 | `saveConversation` 用节点数判新旧，误杀"合法变少" |
| v5.1.12 | 关闭预热仍预热 | `ensureProviderKeepAliveAuto` 只判 host 白名单不读开关 |
| v5.1.9 | 7 项操作只有删除失效 | 删除走 `saveConversation`，session 未就绪时 skip |
| v5.0.96 | 发消息死锁 | `saveLock` 内 `suspend` 初始化（反向加锁） |
| v5.0.86 | Token 用量全是 UUID | 迁移把 modelId 写进 model_name + 生成链从未写用量 |

---

---

## 三·补：v5.3.39 关闭条目（2026-11-04）

| # | 问题 | 根因 | 修法 |
|---|---|---|---|
| I-4 | `MemoryFileDAO.updateContentAny` 死方法 | v5.3.19 后 `update()` 已用多形态 + 迁移，它再没有被调用 | 删除 DAO 方法 + `FakeMemoryDAO` 的 override（全库 0 处） |
| I-12 | `SubAgentRegistry.restoreFromDisk()` 在 Koin 单例构造里 `runBlocking { dao.getAll() }` | `AppModule.kt:153` 的 `.also { it.restoreFromDisk() }` 在**首次注入时**执行，而首次注入点通常在 UI 侧 → 冷启动后第一次打开对话可能卡在 DB 全表读 + 逐条 upsert | `RikkaHubApp` 启动 IO 阶段主动 `get<SubAgentRegistry>()`，让构造与恢复都在后台完成（语义零变化） |
| — | `ChatRequestFactory` 死 import `buildMemoryPrompt` | v5.3.22 删旧重载时漏清 | 删除 |
| — | `MemoryFile.kt:192` 过期注释（称旧重载仍在） | 注释腐化 | 改为写明「v5.3.22 起旧重载已物理删除，唯一入口是 `buildMemoryPrompt(List<MemoryFile>)`」 |

**教训（写进经验库）**：注释腐化是一种 bug 源 —— 它会误导后续每一轮排查（v5.3.32 的 `lastWarmAtMs[host]` 假修复注释就是同一族）。凡注释里出现「既有/本来/无需改动」这类关于**其它代码形态**的断言，改动相关代码时必须同步核对。

---

## 三·补二：v5.3.62 关闭条目（2026-11-08）

| # | 事项 | 结论 |
|---|---|---|
| C-16 | 世界书（世界树）条目**不能调整顺序** | 已实现长按拖拽排序（对齐上游 3.1.2 `ReorderableColumn` + `longPressReorder`）。顺序即注入优先级，此前只能删掉重建 |
| C-17 | `workspace_job` 起的后台命令**随应用被回收而消失** | 已加 `WorkspaceJobForegroundService`（dataSync 前台服务）+ `WorkspaceJobKeeper`（轮询状态自动释放）。可失败旁路：拿不到 Context / 系统禁止后台起前台服务时只记日志，任务照常跑 |
| C-18 | 「要不要换渲染引擎（bettertext）」悬而未决 | 用户定案：**不换**。保持 Compose + JLatexMath + 自研 markdown 栈；已有 KaTeX/mermaid/图片管线/LaTeX 清洗（v5.3.15/16/18）不丢 |

## 四、WONTFIX（明确不做，避免反复讨论）

| 项 | 原因 |
|---|---|
| 双线箭头伸展渲染 | 渲染器是二进制依赖，Kotlin 层补不出；换渲染器成本远高于收益 |
| 同时向多模型发真实请求竞速 | N 倍 token 费用（自费不可接受）；已用连接探针 + 空闲预取替代 |
| 删除 `memoryentity` 表 | Room schema 一致性，需额外迁移；收益不成比例 |
| 德语 `values-de` / 联网搜索入口 | 用户定案不移植 |
| 二维码扫描 | 用户确认本应用无此场景 |

---

## 五、排查前必读（三个硬规则）

1. **先跑 `bin/audit`** —— 一条命令覆盖 env/static/wire/health/render，很多"看起来是新 bug"其实是已知项。
2. **看这条操作走哪条路径** —— 记忆有两类工具、公式有两条渲染路径（Compose `LatexText` vs WebView `mark.html`），报错文案一样但根因完全不同。路径对照见 `docs/APP-MAP.md`。
3. **先查"哪条路径忘了哪条规则"** —— 本项目 18 个记忆类 bug 里 11 个是这个模式（忘过滤软删/归属、忘多形态 path、忘检查返回值）。方法见 `docs/RINCORE-经验教训库.md` 第七种检查角度。

> 仓库副本：与 `/workspace/docs/KNOWN-ISSUES.md` 同步；以仓库副本随代码走，工作区文档中心作沙箱速查。
