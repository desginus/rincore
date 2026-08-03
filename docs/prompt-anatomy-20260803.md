# 每轮对话实际发送的 Prompt 解剖

> 日期：2026-08-03 | 版本：v3.5.3（本地修改未推送）| 来源：代码静态还原（GenerationHandler/GenerationPrompts/ToolRouter）
> 说明：展示一次典型请求（分层模式、冷启动、DeepSeek ChatCompletions）发送给模型的完整内容

## 请求结构（JSON 视角）

```json
{
  "model": "<用户配置>",
  "messages": [ ... 见下 ... ],
  "tools": [ ... 见下 ... ],
  "temperature": null,
  "max_tokens": null
}
```

## 一、messages[0] — system（完整内容）

### 1.1 缓存锚点（buildCacheAnchor，纯静态 ~870 字符）

```
## Core Principles

1. **Tool-First**: Default to using tools. Speculation is a last resort.
2. **Honesty**: If you don't know, say "I don't know." Never fabricate facts, URLs, or code.
3. **Accuracy Over Speed**: Verify with multiple sources. Complex problems deserve thorough analysis.
4. **Precision**: Quantify when you can. Use numbers, not vague descriptions.
5. **Privacy**: You run on the user's device. Data stays local.

## Execution Rules

- When asked to do something, do it. Don't ask for confirmation on straightforward tasks.
- If blocked, explain why and suggest alternatives.
- For multi-step tasks, plan first, then execute concisely.
- Break complex problems into manageable steps.
- Handle errors gracefully. Try alternatives before giving up.

## Response Style

- Direct and concise. Remove filler words.
- Match the user's language. Use Chinese for Chinese queries.
- Use markdown: headers, lists, code blocks, tables.
- Cite sources from search results. Format: [source](url).

## Caching Note

This prompt block is static. Dynamic content (tool domain map, memories, context) is injected separately after this block.
```

### 1.2 助手系统提示（assistant.systemPrompt）

默认助手为**空字符串**（`DEFAULT_ASSISTANT_ID`）。内置备用助手（`3d47790c...`）示例：

```
You are a helpful assistant, called {{char}}, based on model {{model_name}}.

## Info
- Time: {{cur_datetime}}
- Locale: {{locale}}
- Timezone: {{timezone}}
- Device Info: {{device_info}}
- System Version: {{system_version}}
- User Nickname: {{user}}

## Hint
- If the user does not specify a language, reply in the user's primary language.
- Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
```

（占位符由 PlaceholderTransformer 替换；实际 systemPrompt 为用户在助手设置里配置的内容）

### 1.3 Layer1 域概览（ToolRouter.buildLayer1 —— 每次请求动态生成，**全部 68 个域全列**）

```
## 工具调度

你拥有一个工具总域 `工具`，按功能场景树状组织。每个域含：显示名称、触发描述、触发条件。

**加载**：`invoke_tools("场景名")` 查看子域；`invoke_tools("场景/子域")` 加载工具。调 `invoke_tools("帮助")` 查看全部。

### 可用场景域

- 搜索: 搜索网页、查资料、查新闻 [触发: 搜索、查找、搜、查、查询 等6个]
  - 搜索/搜索引擎: 通用网页搜索引擎 [...]
  - 搜索/商品搜索: 商品搜索、比价 [...]
  - 搜索/政策搜索: 法律法规政策查询 [...]
- 物理引擎: 物理力学仿真计算 [触发: 物理、physics、力学、运动]
  - 物理引擎/动力学仿真: 抛体、碰撞、浮力等仿真 [...]
  - 物理引擎/流体力学: 流体力学计算 [...]
- 设备状态: 剪贴板、通知、定位、调度 [触发: 设备、device、剪贴板...]
  - 设备状态/调度: 定时任务、日历事件 [...]
- 文件控制: 读写管理文件、压缩解压、工作区Shell [触发: 文件、file、读文件...]
  - 文件控制/浏览 / 读写 / 压缩: ...
- 浏览工具: 打开网页、点击填表、截图提取 [...]
  - 浏览工具/导航 / 交互 / 提取: ...
- 生成部署: 图像视频生成、网页部署、二维码 [...]
  - 生成部署/图像生成 / 视频生成 / 网页部署 / 二维码 / 图表: ...
- 对话工具: 子代理、记忆、时间、高频率小工具 [...]
  - 对话工具/记忆 / 子代理 / 时间 / 小工具: ...
- 辅助推理: 深度推理、序列思考、方法论分析 [...]
  - 辅助推理/序列思考: ...
- ...（其余域：应用、工作流、MCP 等 68 个条目全列）

加载域后其工具保持可用，跨请求不会丢失。若任务需要多个域的工具，请一次加载齐所需域（每次加载新域会使一次请求的缓存失效，加载齐后保持稳定）。

调 `invoke_tools("域名称")` 加载。不确定时调 `invoke_tools("帮助")`。
```

### 1.4 框架工具 systemPrompt（分层模式：只注入 frameworkTools 中非空 systemPrompt 者）

**现状：几乎所有框架工具 systemPrompt 为空**（`Tool.systemPrompt` 默认 `{ _, _ -> "" }`，仅 TTS/ClawSkillBridge 等少数传入）——**此段实际注入内容 ≈ 0**。

### 1.5 记忆（buildMemoryPrompt —— 仅 enableMemory 且记忆非空时）

```
**Memories**
These are memories stored via the memory_tool that you can reference in future conversations.
[
  { "id": "xxx", "content": "用户偏好..." },
  ...
]
```

### 1.6 注入（PromptInjectionTransformer —— 仅命中 ModeInjection/Lorebook 时）

- BEFORE_SYSTEM_PROMPT → 合并到本 system 开头（v3.5.x 修正后）
- AFTER_SYSTEM_PROMPT → 合并到本 system 末尾（如默认注册的 Learning Mode，未启用不注入）
- TOP_OF_CHAT → 独立消息

## 二、messages[1..n-1] — 历史消息

- 最近 N 条（limitContext 截断，含工具调用对）
- 每条含 reasoning_content（DeepSeek 思考模式，3.2.2 基线回传）

## 三、messages[n] — 本次用户消息

用户输入（TimeReminder 按固定消息时间戳注入时间）

## 四、tools 数组（分层模式冷启动实际发送）

```
[memory_tool（enableMemory 时）] + frameworkTools（非 memory）+ invoke_tools
```

- **frameworkTools**（frameworkToolSet 17 个中实际注册者）：invoke_tools / workspace_shell / workspace_read_file / workspace_write_file / workspace_edit_file / manage_domain / list_domains / move_tool_to_domain / mcp_connect / clawhub_install / clawhub_search / plugin_install / skills_lock / list_ecosystem_tools
- **invoke_tools**：`按类别加载工具。有子域时返回子域列表(需再调用加载子域)，无子域时直接返回工具列表。` 参数：name（类别/子域路径，可选）
- **MCP 工具**：v3.5.3 起**不直接注入**（走域池懒加载）
- **冷启动工具数 ≈ 15-18 个**（264 全池 → 分层过滤后）

## 五、潜在屎山标注（供审查）

| # | 位置 | 观察 | 性质 |
|---|------|------|------|
| 1 | Layer1 域概览 | **68 个域全列**（含大量用户可能永远不用的域：物理引擎/商品搜索/政策搜索等）——每次请求都完整发送 | 静态可缓存，但浪费输入 token（估算 2-4K tokens） |
| 2 | buildCacheAnchor Caching Note | "Dynamic content injected separately"——说明性文字，无行为价值 | 微小冗余 |
| 3 | frameworkTools systemPrompt 注入段 | 实际内容 ≈ 0（工具 systemPrompt 全空）——但 GenerationHandler 仍遍历执行 | 空转逻辑（可观察：system 组装遍历 17 个工具判断 isNotBlank） |
| 4 | 记忆 JSON | 记忆多时全量 JSON 注入（无数量/长度限制） | 潜在膨胀（记忆工具写入无上限） |
| 5 | 域描述冗余 | Layer1 概览 + invoke_tools 返回内容重复描述域信息 | 双层描述，冗余 |
| 6 | 默认助手 systemPrompt 为空 | 用户未配置时 system 只有锚点+域概览——模型行为依赖锚点 | 设计如此（可接受） |
| 7 | 工具名/描述全英文 | workspace_shell 等描述英文（fork 继承）——与中文 system 混排 | 一致性 |
| 8 | TimeReminder | 每次请求注入时间——**已确认注入在 USER 消息内**（TimeReminderTransformer L46 `role == USER` 时）→ 不影响 system 前缀 → 缓存稳定 | 已澄清（非问题） |

## 六、Token 估算（冷启动）

| 段 | 估算 |
|---|---|
| system 锚点 + 提示 | ~1-2K |
| Layer1 域概览（68 域） | ~2-4K |
| 框架工具 systemPrompt | ~0 |
| 记忆 | 0（默认关闭） |
| **system 合计** | **~3-6K** |
| tools 数组（15-18 个） | ~3-5K |
| 历史 + 新消息 | ~0.1K |
| **冷启动合计** | **~6-11K**（vs 回滚前 70K） |
