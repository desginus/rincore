/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

/* ───【原版对齐】ChatSizeChecker.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Conversation

// 消息节点数量警告阈值
const val MESSAGE_NODE_WARNING_THRESHOLD = 768
const val LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD = 300_000

data class ConversationSizeInfo(
    val nodeCount: Int,
    val lastAssistantInputTokens: Int,
    val exceedNodeCountThreshold: Boolean,
    val exceedInputTokenThreshold: Boolean,
    val showWarning: Boolean
)

private val DefaultSizeInfo = ConversationSizeInfo(
    nodeCount = 0,
    lastAssistantInputTokens = 0,
    exceedNodeCountThreshold = false,
    exceedInputTokenThreshold = false,
    showWarning = false
)

@Composable
fun rememberConversationSizeInfo(conversation: Conversation): ConversationSizeInfo {
    return remember(conversation.messageNodes) {
        val nodeCount = conversation.messageNodes.size
        // ★v5.3.70：旧实现 `asReversed().map { it.currentMessage }.firstOrNull { ... }`
        // 每次重组都分配一个 N 元素中间列表（流式期每 50ms 一次）；倒序索引循环零分配。
        var lastAssistantInputTokens = 0
        for (i in conversation.messageNodes.indices.reversed()) {
            val message = conversation.messageNodes[i].currentMessage
            if (message.role == MessageRole.ASSISTANT) {
                lastAssistantInputTokens = message.usage?.promptTokens ?: 0
                break
            }
        }
        val exceedNodeCountThreshold = nodeCount > MESSAGE_NODE_WARNING_THRESHOLD
        val exceedInputTokenThreshold = lastAssistantInputTokens > LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD
        ConversationSizeInfo(
            nodeCount = nodeCount,
            lastAssistantInputTokens = lastAssistantInputTokens,
            exceedNodeCountThreshold = exceedNodeCountThreshold,
            exceedInputTokenThreshold = exceedInputTokenThreshold,
            showWarning = exceedNodeCountThreshold && exceedInputTokenThreshold
        )
    }
}

@Composable
fun ConversationSizeWarningDialog(
    sizeInfo: ConversationSizeInfo,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = HugeIcons.Alert01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary
            )
        },
        title = {
            Text(text = stringResource(R.string.chat_size_dialog_title))
        },
        text = {
            Text(text = stringResource(R.string.chat_size_dialog_content, sizeInfo.nodeCount))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
