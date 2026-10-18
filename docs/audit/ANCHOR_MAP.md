# RinCore 功能锚定地图（ANCHOR MAP · v0）

> 2026-10-18 · 用户架构锚定工程准则：**一个功能 = 一个锚点模组**；功能性代码必须明确锚定（基座代码可例外）；一切跨面引用必须标注"为了什么目的、引用去做什么"。
> 方法：50 标志物全库散布度量化（`anchor-scan.tsv`：token × 文件数 × 目录数 × 模块分布）+ 逐族定性取证。

## 一、判定总表

| 功能族 | 锚点（单一来源） | 散布面 | 判定 | 动作 |
|---|---|---|---|---|
| 玻璃体系（输入栏/标题栏/chrome） | `ui/components/motion/RinGlass.kt`（inputBlurStyle/titleBlurStyle/titleFogBrush/rinGlass） | 消费 4：ChatInput/ChatPage/ChatList/EmptyOrbitSelector（同模式调用） | ✅ 良好 | L-A1 标注 |
| 工具产图（产出侧） | `data/ai/tools/ToolImagePayload.kt`（buildRenderUrl/renderMarkdownOf/extractRenderUrls） | 5 个生产点全经单点 | ✅ 良好 | L-A1 标注 |
| 本地图片认领/解码 | `utils/LocalImageAddress.kt`（单一解析点） | WorkspaceImageResolver 直引残点 4：ZoomableAsyncImage(1 可路由) / SandboxBridgeServer(/render 桥·正当) / WorkspaceLinkLauncher(任意文件打开·正当) / WorkspaceRepository+RikkaHubApp(生命周期) | ⚠️ 留观 | L-A2 |
| 渲染即固化 | `ChatMediaStore.kt` + `MessageImageSnapshotTransformer.kt` | 消费 3 | ✅ 良好 | L-A1 标注 |
| 上传码 | `data/files/UploadCodes.kt`（时间码唯一派生） | 5 文件 | ✅ 良好 | L-A1 标注 |
| 上传四元组（名/码/宿主路径/沙箱路径） | **✔ `data/files/UploadRef.kt`**（v4.8.189 L-A3 新建：任意形态→统一 Parsed） | 消费 4：DocumentAsPrompt / UploadsListing / 收件箱 / UploadFetchTool（全部改调用） | ✅ **已收敛** | ✔ |
| 上传命名/取号 | `FileUtils.createUploadFile` + `UploadCodes.nextCode`（原子） | 1 目录 | ✅ 良好 | — |
| **CWD 生效链** | **✔ `data/workspace/EffectiveCwd.kt`**（v4.8.189 L-A4 新建：pick/fromFolderId/resolve） | 消费 4：ChatService / ChatPage / FilesPicker / ChatDrawerVM（公式重写全改调用；ChatVM 保留响应式订阅） | ✅ **已收敛** | ✔ |
| 项目包归属 | `ChatService.moveConversationToFolder`（移动单入口） | 写入入口清晰：orbit 内弧/ChatDrawer/新建默认/SubAgent 继承（B163）/深链 | ✅ 基本 | L-A1 标注入口 |
| 项目包选择状态 | `ChatDrawerVM.selectFolder`（单入口）+ `ProjectPackSelection`（进程记忆） | 2 文件 | ✅ 良好 | L-A1 标注 |
| 工具矩阵 | `ZoneOps` + `applyZoneOp`（事务单入口） | 1 目录 3 文件 | ✅ 良好 | — |
| 子代理预算 | `SubAgentBudget.kt`（live+落盘单点） | 3 | ✅ 良好 | L-A1 标注 |
| 追踪/诊断 | `CallTracer.kt` + `TraceKey` | 消费 13 面 | ✅ 基座例外 | — |
| 技能安装写入 | `SkillManager.saveSkillFileBytesAtomically` | 3 写入点全经此（SkillDiscover/SkillsVM/SkillManager） | ✅ 良好 | L-A1 标注 |
| 预热（连接/沙箱） | ConnectionWarmer / SandboxPrewarmer | 独立入口 | ✅ 良好 | — |
| 消息渲染链 | MarkdownNew + MarkdownParseCache（首帧同步） | 消费面=消息组件族 | ✅ 基座例外 | — |

## 二、引用标注约定（本工程建立）

**锚点文件头部统一补一行**（插在既有注记块内）：

```
 * 全链: <文件>(用途) / <文件>(用途) …
```

用途词表（固定）：`状态源 / 写入 / 显示 / 桥接 / 消费 / 生命周期 / 测试`。
基座文件不加（被广泛消费=基座本性）；只有**功能性锚点**需要列清"谁在用它、为何用"。

## 三、行动清单

- **L-A1 标注批**：8 个锚点文件补「全链」行（RinGlass / ToolImagePayload / LocalImageAddress / ChatMediaStore / UploadCodes / ChatDrawerVM(selectFolder) / SubAgentBudget / SkillManager）。
- **L-A2 路由批**：ZoomableAsyncImage 直引 → 评估路由 LocalImageAddress 或加用途标注；其余 3 处=正当用途补标注。
- **L-A3 重写批一（→任务四）**：upload 四元组单一解析器 `UploadRef`（任意输入形态 → {code, 物理名, 宿主路径, 沙箱路径}），4 消费点全部改调用。
- **L-A4 重写批二（→任务四）**：CWD 单一解析器（`resolveEffectiveCwd(folder, assistant)` + `cwdCandidates()` 单一实现），4 处公式重写全改调用。
- **L-A5 复核一/二（→任务二/三）**：重扫对比（散布度应下降且无新散点）+ 双写点/悬空引用/单入口抽查的独立二轮。

## 四、复核一（2026-10-18 · 标注对账 30 对）记录

- **手段**：「全链」声明逐对回代码验证（声明点名的每个消费文件必须含锚点符号）。
- **首轮 20/30**；10 处分歧定性结果：
  - 7 处 = 标注/校验措辞问题（顶层函数经 import 直用、"文件名"字符串不出现；间接消费未写明链路中转）→ 已勘正标注。
  - 2 处 = 事实勘正：① ChatList/轨道已随玻璃回滚**脱链**（旧声明残留）；② 保存图片链实际在 `FilesManager.saveMessageImage`（非 ChatFileActions）。
  - 1 处 = **真缺口**：收件箱自解析上传码（`uploadCodeOf` 手搓），不走 `UploadCodes` → 归入 L-A3 重写批修复。
- **对账器规则修正**：按"符号"核验而非"文件名"（RinGlass/ToolImagePayload/LocalImageAddress 等文件以顶层函数为接口）。
- **收敛状态**：修正后 29/30 通过，唯一缺口 = 收件箱→UploadCodes（L-A3 处理中）。

## 五、复核二（2026-10-18 · 收敛终验）记录

- **公式残留扫描**：`workspaceCwd ?: / cwd ?: ...` 全库仅剩 3 处**非公式**命中（log 字符串 / DB 序列化映射 / 历史注释）——四处真公式全部收敛至 `EffectiveCwd`。
- **消费面复扫**：`EffectiveCwd` 消费=4 站点+定义；`UploadRef` 消费=4 站点+定义；`resolveDocumentUrl` 只出现在四个受控消费点。
- **标注对账（修订版）**：30 对全部通过（含修订：Export→resolveLocalImageFile、收件箱→UploadRef；Export 首轮"no-token"经查为同名文件读取偏差，直接 grep 证实令牌在位）。
- **结论**：任务一（锚定+标注+收口）**完成**；任务二（复核一：对账）与任务三（复核二：残留扫描+复扫）**双双通过**；进入任务四（重写批）。
