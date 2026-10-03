/* 【域 B·AI 传输】 — @工具/工具区 语义注入 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/*
 * v4.8.90: `@工具名` 语义注入；v4.8.92 升级：同时识别**工具区**提及。
 *
 * 输入栏 `/@` 选择器会留下 `@<tool-name>` 或 `@<zone-path>`（如 `@workspace_shell`、`@搜索/搜索引擎`）。
 * 本转换器把这种"艾特"变成模型可执行的明确意图：
 *  · 工具 → 精确调用它；
 *  · 工具区 → 用 invoke_tools 加载该区并使用其工具。
 *
 * 提及校验（防误伤普通文本）：token 要么是"工具名形态"（ASCII 字母开头），要么能被当前
 * 设置的 ZoneRouter 解析成一个真实存在的工具区。无提及 → 零注入（不污染提示词缓存前缀）。
 */

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf

object ToolMentionTransformer : InputMessageTransformer {

    /** 工具名形态：字母开头，可含数字/下划线/连字符/点（MCP 工具名含 __） */
    private val ASCII_TOOL = Regex("@([A-Za-z][A-Za-z0-9_.-]{0,63})")

    /** 任意 @提及候选（含中文与 `/` —— 工具区路径形如 搜索/搜索引擎） */
    private val ANY_MENTION = Regex("@([^\\s@]{1,120})")

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER } ?: return messages
        val text = lastUser.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) return messages
        val router = runCatching { zoneRouterOf(ctx.settings) }.getOrNull()
        val mentions = ANY_MENTION.findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .filter { token ->
                ASCII_TOOL.matches("@" + token) ||
                    router?.resolveCandidates(token)?.isNotEmpty() == true
            }
            .take(8)
            .toList()
        if (mentions.isEmpty()) return messages

        val note = buildString {
            append("\n\n<tool-mention>")
            append("The user explicitly references the following tool(s)/tool zone(s) via `@`-mentions: ")
            mentions.forEachIndexed { i, m -> if (i > 0) append(", "); append('`').append('@').append(m).append('`') }
            append(". If it is a tool, prefer calling exactly that tool (name = the text after @; case-insensitive); ")
            append("if it is a tool zone, load it with `invoke_tools` and use its tools. ")
            append("Do not substitute a generic alternative unless the mentioned tool/zone genuinely cannot do the job.")
            append("</tool-mention>")
        }

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendMentionNote(note)
            }
        } else {
            listOf(UIMessage.system(note.trim())) + messages
        }
    }
}

/** 把注记追加到消息的第一个文本 part（没有则补一个） */
private fun UIMessage.appendMentionNote(extra: String): UIMessage {
    val updated = parts.toMutableList()
    val firstTextIndex = updated.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updated[firstTextIndex] as UIMessagePart.Text
        updated[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updated.add(0, UIMessagePart.Text(extra))
    }
    return copy(parts = updated)
}
