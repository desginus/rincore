package me.rerere.rikkahub.ui.components.message.tools


/* ───【自研】TaskToolUI.kt — Cherry Studio Agent 任务卡片等价
 * 任务清单工具卡: 折叠标题 + 展开任务清单 (状态/标题/进行中描述)
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.rikkahub.R

/**
 * task_tool 任务清单卡片 — Cherry Studio Agent 任务卡片等价。
 * 清单数据来自 task_tool 的 output JSON ({tasks:[{id,title,status,activeForm}]}),
 * 随会话消息持久化, 跨会话重启照常渲染。
 */
object TaskToolUI : ToolUIRenderer {
    override val toolName: String = "task_tool"

    override fun icon(context: ToolUIContext) = HugeIcons.LeftToRightListBullet

    @Composable
    override fun title(context: ToolUIContext): String =
        stringResource(R.string.tool_ui_task_list)

    override fun hasSummary(context: ToolUIContext): Boolean = parseTasks(context).isNotEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val tasks = parseTasks(context)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = when (task.status) {
                            "completed" -> "✓"
                            "in_progress" -> "◐"
                            else -> "○"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (task.status) {
                            "completed" -> MaterialTheme.colorScheme.primary
                            "in_progress" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (task.status == "completed") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    task.activeForm?.takeIf { it.isNotBlank() && task.status == "in_progress" }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    /** 从工具输出 JSON 解析任务清单 */
    private fun parseTasks(context: ToolUIContext): List<TaskItem> {
        val tasksEl = context.content ?: return emptyList()
        val obj = tasksEl as? JsonObject ?: return emptyList()
        val arr = obj["tasks"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            TaskItem(
                id = (obj["id"] as? JsonPrimitive)?.content.orEmpty(),
                title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "pending",
                activeForm = (obj["activeForm"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            )
        }
    }
}

/** 任务条目 (任务卡片最小数据) */
data class TaskItem(
    val id: String,
    val title: String,
    val status: String,
    val activeForm: String?,
)
