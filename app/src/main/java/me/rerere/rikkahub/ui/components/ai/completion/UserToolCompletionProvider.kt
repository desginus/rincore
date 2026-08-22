package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.model.UserTool

/**
 * 用户自定义工具的 @ 引用补全 — 与 WorkspaceCompletionProvider 同层位，
 * 用户输入 @ 时展示已注册的工具；选择后在原文插入 @path（精确路径，模型可直接引用）。
 */
class UserToolCompletionProvider(
    private val tools: List<UserTool>,
) : ChatCompletionProvider {
    override val id: String = "user_tools"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (tools.isEmpty() || context.hasSelection) return null
        val mention = findAtMention(context.text, context.cursor) ?: return null
        val query = mention.query.lowercase()

        val items = tools
            .filter { tool ->
                query.isBlank() ||
                    tool.name.lowercase().contains(query) ||
                    tool.path.lowercase().contains(query)
            }
            .sortedWith(
                compareByDescending<UserTool> {
                    when {
                        query.isBlank() -> 0
                        it.name.lowercase().startsWith(query) -> 1000
                        it.name.lowercase().contains(query) -> 500
                        else -> 0
                    }
                }.thenBy { it.name }
            )
            .take(8)
            .map { tool ->
                ChatCompletionItem(
                    label = tool.name,
                    insertText = "@${tool.path} ",
                    detail = tool.description.ifBlank { tool.path },
                    icon = HugeIcons.Tools,
                    sortScore = when {
                        query.isBlank() -> 0
                        tool.name.lowercase().startsWith(query) -> 1000
                        tool.name.lowercase().contains(query) -> 500
                        else -> 0
                    },
                )
            }

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = mention.range,
            items = items,
        )
    }

    private data class AtMention(
        val query: String,
        val range: TextRange,
    )

    private fun findAtMention(text: String, cursor: Int): AtMention? {
        if (cursor < 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0 && !text[start - 1].isMentionBoundary()) return null
        val query = prefix.substring(start + 1)
        if (query.any { it.isWhitespace() }) return null
        return AtMention(query = query, range = TextRange(start, cursor))
    }

    private fun Char.isMentionBoundary(): Boolean =
        isWhitespace() || this in "([{<\"'"
}
