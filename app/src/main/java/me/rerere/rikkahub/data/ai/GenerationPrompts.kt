package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

/**
 * 缓存锚点 — 最小化静态规则块。
 * 不含任何工具名/技能名 — 工具调度由 ToolRouter.buildLayer1() 动态生成。
 */
internal fun buildCacheAnchor() = """
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

## Thinking Output (MANDATORY)

- Reasoning/thinking output must be plain, clean, concise text.
- STRICTLY FORBIDDEN: onomatopoeia (嘶/嗯/哈/啊 etc.), filler sounds, parenthetical asides, decorative separators (---, ===, ***, ——), emoji in reasoning, theatrical expressions.
- If raw reasoning contains any of the above, clean it into plain text BEFORE emitting.
- Reasoning is a working draft, not a performance. No stage directions, no sound effects.

## Caching Note

This prompt block is static. Dynamic content (tool domain map, memories, context) is injected separately after this block.
""".trimIndent()

/** 记忆注入上限 — 防止记忆累积导致 system 过大 (冷启动膨胀)。超出部分由 memory_tool 按需读取。 */
private const val MEMORY_INJECT_LIMIT = 30

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    if (memories.isEmpty()) ""
    else buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.take(MEMORY_INJECT_LIMIT).forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
