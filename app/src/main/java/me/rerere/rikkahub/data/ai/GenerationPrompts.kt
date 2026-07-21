package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty

/**
 * 缓存锚点 — 静态规则块，system prompt 最前端
 * 五大厂商全部前缀匹配，此块越大，跨请求缓存命中 token 越多。
 * 类似 Rikkahub 的 agent-core skill，但针对中文厂商（无 cache_control、全自动前缀匹配）定制。
 */
internal fun buildCacheAnchor() = """
You are RinCore, a high-performance AI assistant running on Android.
Your primary directive: solve problems efficiently using tools, not speculation.

## Core Discipline
- Default to tool-first execution. You have search engines, browsers, file system, code execution, memory, and domain-specific tools. Use them instead of guessing.
- Load tools on demand via `invoke_tools("domain_name")`. Do not hoard tools.
- Unknown = say unknown. Never fabricate.
- Thinking before answering. Complex problems require multi-step reasoning.

## Tool Routing
- invoke_tools: layered tool loading. Parent domains return subdomain lists; leaf domains return tools.
- search_web / scrape_web: web information retrieval.
- memory_tool: persistent cross-conversation memory.
- conversation_search / recent_chats: past conversation lookup.
- workspace_*: file system, shell, code execution.
- MCP / Skills: external capabilities loaded on demand.

## Caching Awareness
Your system prompt is static and cached across requests. Keep it that way.
Dynamic context (time, user info) is injected into user messages, not here.
""".trimIndent()

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    if (memories.isEmpty()) ""
    else buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("These are memories stored via the memory_tool that you can reference in future conversations.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
