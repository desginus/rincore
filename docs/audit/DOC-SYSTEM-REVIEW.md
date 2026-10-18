# 文档系统架构评审（DOC SYSTEM REVIEW · v1）

> 2026-10-18 · 用户点④："你自己再审核一遍整个文档系统…想一下是不是这样最合理？有没有什么更高效的架构？"——本稿为独立判断，不受既有实现框架约束。
> 结论先行：**主干架构是合理的**（沙箱为文档处理主场 + 应用内渲染总线 = "沙软一体"核心竞争力）；问题集中在**三处"同一个东西各写各的"**与两处体验欠账——都不是结构重做级别，属于收敛型改进。

## 一、现架构全景（六层）

```
① 上传层   FilesManager → files/upload/<时间码>_<名>.ext（UploadCodes 时间码=文件名前缀，确定性派生）
② 注入层（请求期，三条并存）
   · UploadsListingTransformer — 消息锚定注记"[用户上传了文档: 名（路径）]"（缓存连续，v4.8.109 定版）
   · DocumentAsPromptTransformer — Document part → <UploadFile code path> 文本；classic=全文内联 / compat=路径引用（v4.5.5 分流）
   · upload_fetch 工具 — 上传码直取（未命中给最近列表兜底）
③ 读取/解析层  沙箱工具链（workspace_read_file / pdf / markitdown / officecli view text）＋ document 模块 4 解析器（Pdf/Docx/Pptx/Epub≈1070 行，服务 classic 内联）
④ 渲染层（软件侧） render/ 包 ~2060 行：DocumentRenderEngine（文本→分页 HTML 产物）+ OfficeCliHtmlRenderer（真渲染，v4.8.102 缓存/免拷贝/LRU）+ PdfRenderView/MediaRenderViews + RenderViewDialog + **RenderRequestHost（沙箱 `rin render` → 应用内弹窗——沙软一体关键桥）**
⑤ 生成层  技能体系（docx/pptx/xlsx 技能 + officecli 经 rin-tools 资产分发到每工作区 /usr/local/bin）＋ 技能商店（SkillDiscover：CherryIN/skills.sh）＋ preset-skills
⑥ 消费/管理层  文件胶囊 [渲染/分享/导出]（ChatFileActions 单一实现）· 本对话产出（ProducedFiles+ArtifactsSheet）· 收件箱（v4.8.186 已修）· Markdown 导出（v4.8.185 data URI 携图）
```

## 二、明确的优点（保留项）

1. **沙箱为文档主场**（解析/生成/编辑全在沙箱工具链）——与"沙软一体"定位一致；软件侧只做"呈现与总线"。
2. **RenderRequestHost 总线**（沙箱→应用内原生渲染）是本应用最具差异化的体验桥，方向正确、实现克制（IO 渲染+关闭即清）。
3. **compat 模式的缓存纪律**（路径引用 + 码，不破坏前缀）证明是对的（v4.8.109 的锚定注记同理）。
4. **上传码体系**（确定性派生、零迁移、任何调用方可算）是干净的引用设计。
5. 渲染缓存链（v4.8.98~102：结果缓存/免拷贝/LRU/预热）已达生产级。

## 三、问题与提案（按优先级）

**P1 ★ 上传引用"四元组"解析收口（三处各写各的）**
- 现状：`DocumentAsPrompt.resolveUploadFile`（Uri.toFile + parent=="upload"）、`UploadsListing`（Uri.parse().path + File(name)）、`收件箱 uploadCodeOf`（字符串切前缀）、`UploadFetchTool`（扫目录算码）——四处独立实现同一"物理名↔码↔路径"关系。
- 提案：单一 `UploadRef` 解析器（输入任意形态：Document.url / 物理名 / 码 / 沙箱路径；输出 {code, 物理名, 宿主路径, 沙箱路径}），四消费点全部改调用。中风险、收益=未来只改一处。
- 排期：与下一次文档域功能批同做。

**P2 ★ classic 模式现代化（或明确淘汰路径）**
- 现状：classic=全文进 prompt（token 重、缓存不连续的历史形态）；compat 才是默认推荐。classic 的价值仅是"用户可选"。
- 提案：classic 增加**按体积/类型自动降级**（>阈值自动走 compat 并在 <UploadFile> 注记说明），保留"小文件全文内联"能力；或评估后淘汰 classic。
- 需用户拍板（影响设置面）。

**P3 ✔已顺手修 `.render` 卫生**（本轮已改）+ 后续评估渲染缓存迁至 app cacheDir。
- 发现：`.render/cache-*/page1.html` 会被"本对话产出"的全工作区扫描卷进产出列表（点目录未排除）。已修：ProducedFiles 扫描整体排除点目录。

**P4 双解析链分工文档化**（软件内 document 模块 vs 沙箱工具链）
- 风险：同一文档两链结果可能漂移（软件内近似排版 vs officecli 真渲染）。现状已基本"沙箱为准"（v4.8.102 真渲染接管，内建提取器仅服务 classic 内联）。提案：在 APP_MAP/CODE_INDEX 明写原则："文档内容以沙箱工具链为准；软件内解析器仅用于 classic 内联兜底"。

**P5 文件中心统一（中远期）**
- 三面并存：收件箱（upload 全量）/ 本对话产出（produced）/ 编辑过的文件（edited）。信息架构可收敛为"文件中心"三分 tab；收件箱补[删除/导出]动作（现状只读）。

**P6 DocumentAsPrompt 插入位置语义（低优先·需缓存评估）**
- 现状 `add(0, ...)` 把 <UploadFile> 文本插到 parts 头部；Listing 注记在尾部——两者位置语义不一致。**注意：add(0) 可能牵连缓存前缀稳定性，改动前必须做缓存影响评估**；本轮不动，登记。

**P7 rin-tools 每工作区副本磁盘成本（观察项）**
- officecli 等二进制（~34MB）逐工作区写入 rootfs /usr/local/bin；N 个工作区 = N×34MB。提案：调研单副本+软链/硬链共享可行性。

**P8 收件箱体验补全**（v4.8.186 已修标注）——后续:删除/导出/批量清理动作。

## 四、边界声明
- 本稿为架构判断，P1/P2/P5 涉及行为或设置面变更，实施前报用户拍板；P3 已修、P4 为文档化（无代码）。
