# 扩展接口与检测接口（EXTENSION-POINTS）

> 目的：**「我要加一个新的 X，改哪几处」**。每条都标出**必踩的坑**（全部是真实踩过的）和**验收命令**。
> 原则：新功能一律做成**可失败旁路** —— 坏了只回退到旧行为，绝不拖累主链路。

---

## 一、加一个 AI 工具（模型可调用）

**改 3 处**：

| # | 文件 | 做什么 |
|---|---|---|
| 1 | `app/.../data/ai/tools/WorkspaceTools.kt`（或新建 `XxxTools.kt`） | 用 `Tool` DSL 定义：`name` / `description` / `parameters` / `execute` |
| 2 | `app/.../data/ai/tools/ToolsBuilder.kt` | 把工具加进 `buildAssistantToolPool` 返回的集合（**模型实际拿到的就是这个函数的结果**） |
| 3 | `app/.../ui/pages/setting/ToolPreviewBuilder.kt` | 加一条 `ToolPreview`（否则工具矩阵里看不到它） |

**必踩坑**：
- **只加 1 不加 2** → 工具"存在"但模型永远拿不到（v5.3.25 记忆工具就是这么漏的）。
- **只加 1 不加 3** → 模型有、工具矩阵看不到（同一坑的另一面）。
- 描述里必须写清**路径语义**与**耗时代价**；模型只看描述决定用哪个工具（v5.3.37）。
- 需要工具 UI 时另见下一节；不加也能跑（走通用渲染）。

**验收**：
```bash
cd /workspace && ./bin/rc check          # 符号/import/注解
./bin/audit fast                          # 接线 + 主线程 IO
# 启动后：设置→工具，应能看到它；模型调用应成功
```

---

## 二、加一个工具 UI 渲染器

**改 1 处**：`app/.../ui/components/message/tools/ToolUI.kt` 的 `renderers` 注册表加一项。

**必踩坑**：**文件存在 ≠ 已注册**。v5.0.39 的事故是 4 个渲染器文件都在，但注册表漏了 4 项 → 界面只显示"调用了这个工具"而没有内容。**加完必须 grep 注册表确认。**

**验收**：`grep -c "XxxToolUI" ToolUI.kt` 应 ≥ 1；界面上工具卡片应有专属渲染。

---

## 三、加一个工具区 / 子区

**改 4 处**：
1. `app/.../data/ai/tools/routing/ToolZone.kt` —— `DEFAULT_TOOL_ZONES` 加区定义
2. 同文件 `ZoneRouter.classify` —— 加归类规则（**优先用 `prefixRules`/`keywordZone`，避免硬编码点名某个区**）
3. `app/.../data/ai/tools/routing/ZoneOps.kt` —— 确认 `mergeBuiltinZones` 会无条件补齐（**内置区 = 代码常量唯一权威**）
4. UI 的区树自动从数据源渲染，无需改

**必踩坑**：
- **硬编码 `hit("某区")` 是最脆弱的写法** —— 区缺失就落"未分类"（v5.3.28 记忆工具"未分类"的真因）；其它工具看起来正常只是因为它们走模糊匹配。
- 新增配置项必须问：**谁播种？老用户怎么拿到？**（`normalize` 里 `mergeBuiltinZones` 兜底）。

**验收**：`bin/origin` 不适用；用 `zoneRouterOf(settings)` 实跑（参考 `ZoneOpsTest` 的 7 条用例）。

---

## 四、加一个 Provider（模型服务商）

**改 3 处**：
1. `ai/.../provider/ProviderSetting.kt` —— 加 `data class XxxSetting : ProviderSetting()`（含 `baseUrl`）
2. `ai/.../provider/providers/xxx/XxxProvider.kt` —— 实现 `Provider`（`listModels` / `getStreamingClient`）
3. `app/.../di/DataSourceModule.kt`（或 `AppModule`）—— Koin 注册 + 设置页入口

**必踩坑**：
- `ProviderSetting` 是**接口**，`baseUrl` 在各子类各自声明（基类没有）。
- 新增 provider 后要检查 `ProviderManager.effClient` 的 **host 分流**，否则走错连接池（v5.3.33 代理池事故）。
- 若该 provider 需要预热，必须在 `ConnectionWarmer.isWarmAllowed` 显式放行（默认只允许 opencode/commandcode）。

---

## 五、加一个设置项

**改 3 处**：
1. `app/.../data/datastore/Settings.kt` —— 顶层字段加默认值
2. **`app/.../data/datastore/SettingsFields.kt` —— 加进 `SETTINGS_FIELDS` 映射（漏了就是"内存有效、重启即丢"）**
3. 对应设置页加 UI

**必踩坑**：
- **`Settings` 是 DataStore 手动字段映射**，不加映射表 = 永不落盘（v5.1.11 快速/极致模型池就是这么丢的）。
- `DisplaySetting` / `NetworkSetting` 这类**整块 JSON 序列化的子对象**不受影响，加字段零迁移（v5.3.2 悬浮窗开关）。
- 新增字段后跑 `SettingsFields`（若有测试） 若有的话；否则 `grep SETTINGS_FIELDS` 确认。

---

## 六、加一个 `workspace_*` 工具

**改 2 处**：`app/.../data/ai/tools/WorkspaceTools.kt` 定义 + `ToolsBuilder` 注册（同第一节）。

**必踩坑**：
- **`/workspace` 语义必须写清**：shell/job 的 `/workspace` 是「这个 shell 自己定义的根」；文件工具按**当前 CWD** 解析。两边不一致（v5.3.37/38）。
- 文件操作**不要经过 shell**：宿主直读毫秒级，shell 每次 1~3s proot 冷启动。
- 长任务必须是 `workspace_job`，`workspace_shell` 有 660s 硬上限。

---

## 七、记忆系统扩展开关

| 想加什么 | 改哪里 | 判据单一事实源 |
|---|---|---|
| 新的挂载档（如"仅项目包"） | `MemoryRepository.MOUNT_*` 常量 + `visibleForInjection` 的 `when` | **只能改 `visibleForInjection` 一处**，UI/工具/注入都调它 |
| 新的记忆层 | `MemoryFile.MemoryLayer` 枚举 + `layerOf` | 同上 |
| 新的来源（如 `source="import"`） | `MemoryFile.source` 取值 + `isDream` 判据 | `isDream` 只有一处 |

**必踩坑**：
- **任何"是否可见"的判断只能调 `MemoryRepository`** —— UI 和注入各写一份语义就会漂移（v5.3.19 的 18 个 bug 有 11 个是"某条路径忘了某条规则"）。
- 删除**只有软删一条路**（`invalidateFile`）；物理删只在 `purgeFile`。

---

## 八、快速/极致路由扩展

| 想加什么 | 改哪里 |
|---|---|
| 新的准入门槛（如"必须支持视觉"） | `ModelRouting.ineligibility` 加一条 |
| 新的排序因子（如"按价格"） | `ModelRouting.score` |
| 新的粘性策略 | `ModelRouting.pick` 的容忍度常量 |

**铁律**：**能力是准入门槛，速度只在合格集合内排序**（v5.3.35）。任何"按性能选实现"的机制都必须先有门槛，否则会选出"很快但干不了这件事"的（工具调用被当正文）。

---

## 九、检测接口（现成的，别自己再造）

| 命令 | 查什么 | 何时跑 | 退出码 |
|---|---|---|---|
| `bin/selfcheck` | **检查器本身可信吗**（14 项探针） | 每次发版前**第一个** | 0=可信 |
| `bin/audit` / `audit fast` | 环境/静态/接线/健康/渲染 五组 | 发版前 | 0=全绿 |
| `bin/rc check` | 语法/import/符号/注解/资源 | 每次改代码 | 0=通过 |
| `bin/wire_audit.py` | Composable 空回调未接线 | 改 UI 后 | 报告 |
| `bin/health_audit.py` | 主线程 IO / 静默失败 / 阻塞 | 大改后 | 报告 |
| `tools/test-mark-render.js` | WebView 渲染 26 项端到端 | 改 `app/src/main/assets/html/mark.html` 后 | 0=过 |
| **`bin/origin`** | **文件来源 + 迁移能力** | 任何"这是谁的文件/能不能回退" | 0 |
| `./bin/watch` | CI 状态（**唯一姿势**） | 推送后 | 0=绿 |

**新增检查器时必须同步加探针**（`selfcheck` 里加一条"已知坏样本必须报错"），否则它可能在**永远不报错**的状态下被当成门禁。

---

## 十、覆盖性迁移（回 3.1.0 / 移植上游）

```bash
# 1) 看整体：哪些与基线逐字节一致（可零冲突回退）
./bin/origin untouched | tail -3

# 2) 看某文件是谁的、改了多少
./bin/origin of app/.../ChatService.kt
./bin/origin diff app/.../ChatService.kt

# 3) 单文件回退到 3.1.0 基线（覆盖式，会丢本地改动）
./bin/origin restore app/.../Xxx.kt --yes

# 4) 从真实上游 3.1.1 移植：先看它是否已在（PORTED = 已经是上游版本）
./bin/origin ported | grep Xxx
```

**规模参考（当前 HEAD）**：1353 个 .kt —— `UPSTREAM 778(57.5%)` / `MODIFIED 231(17.1%)` / `PORTED 200(14.8%)` / `PORTED-MODIFIED 67(5.0%)` / `OURS 56(4.1%)` / `DELETED 21(1.6%)`。

**迁移策略建议**：
- `UPSTREAM` 778 个 → 可直接整体对齐，零风险。
- `PORTED` 200 个 → 上游资产，升级时整文件替换。
- `MODIFIED` 231 + `PORTED-MODIFIED` 67 → **必须三方合并**，这是"乱"的实际所在。
- `OURS` 56 个（其中 22 个在 `docs/upstream-3.1.0-staging/` 存档，**非生产代码**）→ 真正自研只有 27 个文件。

---

## 十一、最终目标：personal AI agent 的扩展点

面向"随用户习惯持续优化自身"的能力，当前已有的挂载点：

| 能力 | 现状 | 可扩展处 |
|---|---|---|
| 长期记忆 | ✅ 唯一表 + 2×3 维度 + 做梦整理 | `MemoryPrompts` 的写入规则、`MemoryConsolidator` 的整理频率 |
| 习惯学习 | ⚠️ 只有 profile/preferences 两个槽 | 新增 `/habits/*.md` 结构化记忆 + 注入规则 |
| 自我反思 | ❌ 无 | 可由"做梦整理"扩展：加一个 `fullReview` 的复盘 prompt |
| 工具偏好 | ⚠️ 仅有 clamp 统计 | `Settings` 加 per-tool 使用统计 + 自动排序 |
| 失败学习 | ⚠️ 仅 `ModelLatencyStats.failureCount` | 扩展到工具层：记录哪类工具常失败，并在描述里提示 |

**建议下一步（按价值）**：①习惯槽（复用现有记忆架构，零新表）②工具使用统计（喂给工具排序）③失败学习（先只做可观测，不做自动调整）。
