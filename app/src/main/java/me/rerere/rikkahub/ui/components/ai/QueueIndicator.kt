/* 【域 A·聊天交互】 */
package me.rerere.rikkahub.ui.components.ai

/* ───【自研】QueueIndicator.kt — 原版无此文件（v5.1.9 新增，用户要求）
 * 排队等候面板：正在输出时再发消息、压缩时发消息、输出中点压缩 —— 都会排队，
 * 这里把「等待中的消息」与「等待中的压缩」统一展示在输入框上方。
 * 材质与输入框一致（rinGlass 玻璃）。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Scroll01
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.ui.components.motion.rinGlass
import me.rerere.rikkahub.ui.components.motion.rinGlassHighlight
import me.rerere.rikkahub.ui.components.motion.LocalHazeState
import kotlin.uuid.Uuid

private val glassShape = RoundedCornerShape(22.dp)

/**
 * @param queue 当前对话的消息队列
 * @param compressionPending 是否有一个压缩任务正在排队
 * @param onRemove 取消某条排队消息
 * @param onCancelCompression 取消排队的压缩
 */
@Composable
fun QueueIndicator(
    queue: MessageQueueState,
    compressionPending: Boolean,
    onRemove: (Uuid) -> Unit,
    onCancelCompression: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queue.messages.isEmpty() && !compressionPending) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .rinGlass(hazeState = LocalHazeState.current, shape = glassShape)
            .rinGlassHighlight()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (compressionPending) {
            QueueRow(
                icon = HugeIcons.Scroll01,
                text = "压缩任务排队中…（当前输出结束后自动执行）",
                onRemove = onCancelCompression,
            )
        }
        queue.messages.forEach { queued ->
            val preview = queued.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString(" ") { it.text }
                .replace('\n', ' ')
                .trim()
                .ifBlank { "[附件消息]" }
            QueueRow(
                icon = HugeIcons.Clock01,
                text = if (preview.length > 60) preview.take(60) + "…" else preview,
                onRemove = { onRemove(queued.id) },
            )
        }
    }
}

@Composable
private fun QueueRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Cancel01,
                contentDescription = "取消排队",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
