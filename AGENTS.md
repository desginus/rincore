# Repository Guidelines

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers
for conversations.
Built with Jetpack Compose, Kotlin, and follows Material 3 Expressive (MD3 Expressive) design language.

## Design Language

The UI follows Material 3 Expressive. New and reworked screens should match it rather than the older baseline
Material 3 look (cards with dividers, plain forms).

- The app theme is `MaterialExpressiveTheme` (app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt); prefer the
  expressive variants of components, shapes (`MaterialShapes`), emphasized typography (e.g. `titleSmallEmphasized`)
  and motion from `androidx.compose.material3` over hand-rolled equivalents.
- Settings-style and form pages use segmented groups via `CardGroup` (
  app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt) instead of a card with dividers.
- Favor larger corner radii, filled/tonal containers, and whole-row click targets.

## Build, Test, and Development Commands

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew lint                   # 运行 Android Lint
```

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **ui**: Reusable Compose UI components that do not depend on app logic
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings.
  Once a conversation is persisted it also holds a `ConversationConfig` snapshot (chat model, reasoning level, search,
  MCP servers, workspace, skills) taken from the assistant; from then on chat-page changes to those settings stay on
  the conversation, and code should read them through `Settings.getAssistantOf(conversation)` /
  `Settings.getChatModelOf(conversation)` instead of the assistant directly. Once loaded, the in-memory
  `ConversationSession` is the single source of truth: change a conversation through `ChatService` (which applies
  `session.update { latest -> ... }` and then `flush()`), never by saving a whole `Conversation` snapshot or one read
  back from the database; deleting also goes through `ChatService` so a running generation cannot re-insert it. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt,
  app/src/main/java/me/rerere/rikkahub/data/model/ConversationConfig.kt,
  app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.

---

## RinCore 工作流（本项目特有，2026-11-04 整顿）

> 上面是上游 RikkaHub 的通用约定；以下是本仓库（desginus/rincore）额外的硬规则。

### 交接文档（先读）

- **唯一主文档：`docs/AGENT-HANDOFF.md`**。接手/交付/并行协作都从它开始。
- 交付模板：`docs/handoffs/TEMPLATE.md`；并行占用：`docs/handoffs/CLAIMS.tsv`；最新交付：`docs/handoffs/`。
- 每次交付必须更新主文档 + 新增交付记录 + 更新 `docs/KNOWN-ISSUES.md`；只写代码不写交接 = 未完成。

### 等待与查询 CI

- **只用 `cd /workspace && ./bin/watch`**：
  - `watch` 无参 = 最近 run 概览（秒回）+ **自动比对是否就是本地 HEAD**；
  - `watch <runId>` = 阻塞到终态，绿/红都打印摘要，退出码 0/1；
  - `watch <sha>` = 按 commit 找 run 再等。
- **不要 `sleep N` + `grep 日志` 轮询**；长等待用 `workspace_job` 包住 `watch`。
- `rc release` 只推 `main`；推完必须核对 `HEAD / origin/adopt-3.0.0 / origin/main` 三个 SHA 一致。

### 发版前

```bash
./bin/selfcheck     # 检查器可信吗（14 项探针）
./bin/audit fast    # env/static/wire/health/render
./bin/rc check      # preflight + ci-check
```

**三者都看退出码，不看输出文字。** 若 `selfcheck` 失败，其余结论不可信。

### 版本号纪律

只改**第三位**，不进位（5.3.39 → 5.3.40）。严禁私自提高第二位。

### 已定案、别再改回的语义

- 记忆**只有一张表** `memory_file`；两个维度：`source`（active/dream）× `layer`（assistant/folder/conversation）。
- 记忆删除 = **软删**（`invalidated_at`）；`purgeFile` 才是物理删除。
- 记忆工具由 `assistant.enableMemory` 统一控制，**不参与工具区归属**。
- 会话 `isReady` = `initialized || newConversation`；**不能用「有消息」当就绪**。
- 快速/极致模型路由：**能力是准入门槛**（`ModelAbility.TOOL` + `contextLength`），速度只在合格集合内排序。

### 改 Kotlin 的三条纪律

1. 改完立刻 `tree-sitter` 复验（ERROR/MISSING = 0）。
2. 删函数必须同时删「签名 + 函数体」，删完 grep 残留调用 + 检查孤立 `}`。
3. 判断锚点在类内还是顶层（看缩进）再决定 `const val` / `val`、成员 / 顶层函数。

### 工具坑（都踩过）

- HugeIcons 使用必须 `import me.rerere.hugeicons.stroke.X`。
- 检查 import 用 `grep -nx 'import 全名'`（前缀会命中 `filterNot` 等）。
- 集合字面量在 if/else 或返回值位置显式写泛型。
- `override` 函数不能带参数默认值。
- 写共享文件名（`AGENTS.md` / `README.md` / `package.json`）前**先读**，它们可能已有内容。
