# 结构总图（STRUCTURE-MAP）

> **四层索引，从「是谁的」一路到「改它会影响谁」。**
> 每层都是**可重跑的机械判定**，不是人工整理的印象。

## 0. 四条命令（全部秒级）

```bash
cd /workspace

./bin/origin modules        # ① 文件级：哪些模块最"乱"（UPSTREAM/MODIFIED/PORTED/OURS 分布）
./bin/origin of <path>      #    单文件溯源 + 与 3.1.0 基线的改动行数
./bin/symbols hot 20        # ② 符号级：改它要动多少个文件（耦合热点）
./bin/symbols dead          #    孤立顶层声明（低置信初筛，见 §3 的误报说明）
./bin/deps                  # ③ 模块级：谁依赖谁 + 有无循环
./bin/deps layer            #    层次推断（谁该在下层）
```

---

## ① 文件级：来源分布（`bin/origin`）

1353 个 `.kt` 的实测分布：

| 类别 | 数量 | 占比 | 迁移动作 |
|---|---|---|---|
| `UPSTREAM` | 778 | 57.5% | 与 3.1.0 逐字节一致 → **可零冲突回退** |
| `MODIFIED` | 231 | 17.1% | 我们改过 → **三方合并** |
| `PORTED` | 200 | 14.8% | 上游 3.1.1 资产 → 整文件替换 |
| `PORTED-MODIFIED` | 67 | 5.0% | 移植后又改 → 三方合并 |
| `OURS` | 56 | 4.1% | 自研（仅 27 个生产文件，其余是 staging 存档） |
| `DELETED` | 21 | 1.6% | 基线有、我们删了 |

**乱源定位**：`app/src/main` 752 个里有 168 个 MODIFIED（22%）；`speech` 68% 被改、`search` 95% 被改（有意精简）；`highlight` / `markdown` / `material3` **几乎零改动**。

---

## ② 符号级：耦合热点（`bin/symbols hot`）

被最多文件引用的符号 —— **改它们等于改整个应用**：

| 文件数 | 符号 | 含义 |
|---|---|---|
| **140** | `UIMessagePart` | 消息内容的基础类型，几乎每个 UI 都碰 |
| **102** | `UIMessage` | 一条消息 |
| **84** | `SettingsStore` | 全局设置入口 |
| **70** | `MessageRole` / `InputSchema` | 角色枚举 / 工具参数结构 |
| **67** | `CustomColors` | 主题色 |
| **65** | `Settings` | 设置数据类 |
| **64** | `Tool` | 工具定义 |
| **59** | `Model` | 模型数据类 |
| **48** | `Assistant` | 助手配置 |
| **39** | `Conversation` | 会话 |

**读法**：要动这十个类型中的任何一个，先跑 `symbols hot` 确认影响面，再决定是否需要分批改、是否要保留兼容重载。

**实现说明**：引用数 = 有多少个文件写了 `import me.rerere.*.<Name>`。同包内使用不计（Kotlin 同包免 import）→ **数值是下限**，真实影响面只会更大。

---

## ③ 模块级：依赖与层次（`bin/deps`）

**没有循环依赖**（`deps cycles` 退出码 0，Tarjan 强连通分量全部为单点）。

层次（按被依赖程度推断）：

| 层 | 模块 | 被依赖 / 依赖他人 |
|---|---|---|
| **底层** | `hugeicons` | 824 / 0 |
| | `ai` | 579 / 29 |
| | `common` | 67 / 0 |
| | `mediagen` / `workspace` | 47 / 1、46 / 0 |
| **中间** | `ui` | 50 / 42 |
| | `search` | 16 / 24 |
| **顶层** | `app` | **0 / 1544** |
| | `speech` | 0 / 9 |

**结论**：
- `app` 是**唯一顶层**（依赖他人 1544 条、零被依赖）→ 这是健康的单一应用层，改动都从它往下；
- **`highlight` / `document` / `material3` / `oauth` 零跨模块依赖**（纯叶子），可以独立替换/升级，风险最低；
- `ai` 被 `app` 依赖 556 条 —— **改造 ai 模块的成本最集中在这里**。

---

## ④ 孤立声明初筛（`bin/symbols dead`）

**这是初筛，不是删除建议。** 判据：①顶层声明 ②无任何 `import me.rerere.*` 引用 ③定义文件内只出现一次。

已知会**漏掉三类误报**（看起来孤立、其实在用）：
- **同包内使用** —— Kotlin 同包免 import（如 `document/pdf` 内部互相引用）
- **被同文件的列表/工厂引用** —— 外部只 `import` 外层对象（如 `BuiltinToolUIs.all`）
- **反射 / 序列化 / manifest 按名字引用**

**使用优先级**：只看 **`OURS` 文件**里的条目；`UPSTREAM` 的重合原版基线，删了会偏离基线，不值得动。

---

## ⑤ 这四层怎么配合用

| 你想做的事 | 用哪层 |
|---|---|
| 「这个文件是谁写的？能不能安全回退？」 | `origin of <path>` |
| 「我要改 `ChatService`，会影响谁？」 | `symbols hot` 看它依赖的 `UIMessage`/`Conversation` 面 |
| 「这个模块能整体升级吗？」 | `deps layer` 看它是不是叶子 |
| 「有没有人还在用这个函数？」 | `symbols uses <name>` + `dead` 交叉验证 |
| 「哪些是我们自己加的？」 | `origin ours` |
| 「整体有多乱？」 | `origin modules` |

**四层的共同前提**：判据都是**机械的**（内容哈希 / import 语句 / 引用可达性），不依赖任何人的印象或注释。

---

## ⑥ 已知边界（避免把它们当成结论）

1. **`symbols` 的耦合数是下限** —— 同包使用不计入 import。
2. **`dead` 的误报率不可消除** —— 见 §4 三类。
3. **`deps` 只看 Kotlin import** —— Gradle 依赖声明（`implementation(project(":ai"))`）未纳入；两者不一致时以 Gradle 为准。
4. **`origin` 的 `lost` 识别不了改名** —— 重构会被报成「丢失」，需人工判读。
