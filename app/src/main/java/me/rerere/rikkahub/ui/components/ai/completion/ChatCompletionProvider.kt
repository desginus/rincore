package me.rerere.rikkahub.ui.components.ai.completion

/* ───【原版对齐】ChatCompletionProvider.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange

data class ChatCompletionContext(
    val text: String,
    val selection: TextRange,
) {
    val cursor: Int = selection.max
    val hasSelection: Boolean = selection.min != selection.max
}

data class ChatCompletionList(
    val providerId: String,
    val replacementRange: TextRange,
    val items: List<ChatCompletionItem>,
)

data class ChatCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val sortScore: Int = 0,
)

interface ChatCompletionProvider {
    val id: String

    suspend fun complete(context: ChatCompletionContext): ChatCompletionList?
}
