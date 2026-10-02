/* 【域 B·AI 传输】 — @工具名 语义注入 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/*
 * v4.8.90: `@工具名` 语义注入。
 *
 * 输入栏 `/@` 选择器会在用户消息里留下 `@<tool-name>`（如 `@workspace_shell`）。
 * 本转换器把这种"艾特"变成模型可执行的明确意图：当最后一条用户消息里出现
 * `@<tool-name>` 时，向系统提示追加一句"用户点名了该工具，请精确调用"。
 *
 * 无提及 → 原样返回（零注入、零开销，不污染提示词缓存前缀）。
 * 不依赖工具池 —— 提及是否真是工具名交给模型按名字对照（写信徒匹配会误伤邮箱等文本）。
 */

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object ToolMentionTransformer : InputMessageTransformer {

    /** 工具名形态：字母开头，可含数字/下划线/连字符/点（MCP 工具名含 __） */
    private val MENTION = Regex("@([A-Za-z][A-Za-z0-9_.-]{0,63})")

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val lastUser = messages.lastOrNull { it.role == MessageRole.USER } ?: return messages
        val text = lastUser.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) return messages
        val mentions = MENTION.findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .take(8)
            .toList()
        if (mentions.isEmpty()) return messages

        val note = buildString {
            append("\n\n<tool-mention>")
            append("The user explicitly references the tool(s) ")
            mentions.forEachIndexed { i, m -> if (i > 0) append(", "); append('`').append('@').append(m).append('`') }
            append(" using `@`-mentions. When tools are needed for this request, prefer calling exactly ")
            append("the mentioned tool(s) (name = the text after @; case-insensitive). Do not substitute ")
            append("a generic alternative unless the mentioned tool genuinely cannot do the job.")
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
