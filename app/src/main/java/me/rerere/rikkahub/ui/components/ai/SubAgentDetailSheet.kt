package me.rerere.rikkahub.ui.components.ai


/* ───【自研】SubAgentDetailSheet.kt — 对话子代理唯一展示窗口
 * 入口: 输入栏加号呼出面板「子代理详情」。
 * 本对话派发的子代理不再散落在消息列表中, 全部汇聚到这里展示。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import kotlin.math.roundToInt

/** 状态显示文案 */
private fun statusLabel(status: SubAgentStatus): String = when (status) {
    SubAgentStatus.PENDING -> "排队中"
    SubAgentStatus.RUNNING -> "运行中"
    SubAgentStatus.SUCCEEDED -> "已完成"
    SubAgentStatus.FAILED -> "失败"
    SubAgentStatus.TIMED_OUT -> "超时"
    SubAgentStatus.CANCELLED -> "已取消"
}

@Composable
private fun statusColor(status: SubAgentStatus): androidx.compose.ui.graphics.Color = when (status) {
    SubAgentStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    SubAgentStatus.SUCCEEDED -> MaterialTheme.colorScheme.primary
    SubAgentStatus.FAILED, SubAgentStatus.TIMED_OUT -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 子代理详情面板 — 当前对话派发的子代理的唯一展示窗口。
 * 列表可滚动; 结果文本限高内滚 (用户铁律: 弹窗滑动交互必须完整支持)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentDetailSheet(
    runs: List<SubAgentRun>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "子代理详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${runs.count { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING }} 运行中 / ${runs.size} 全部",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (runs.isEmpty()) {
                Text(
                    text = "当前对话暂无子代理。模型会在任务需要并行或隔离上下文时自行派发。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    runs.forEach { run ->
                        var expanded by remember(run.id) { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable { expanded = !expanded }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = run.label.ifBlank { run.id.takeLast(8) },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = statusLabel(run.status),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor(run.status),
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "轮次 ${run.tripCount}/max ${run.maxTrips}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                run.tokenCountText()?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                run.finishedAtMs?.let { end ->
                                    run.startedAtMs?.let { start ->
                                        Text(
                                            text = "耗时 ${((end - start) / 1000f).roundToInt()}s",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            // 摘要: 折叠态 — 任务首行 (点击展开看详情)
                            Text(
                                text = run.task.takeIf { it.isNotBlank() } ?: run.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (expanded) {
                                run.task.takeIf { it.isNotBlank() }?.let { task ->
                                    Text(
                                        text = "任务: $task",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                run.error?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                run.result?.takeIf { it.isNotBlank() }?.let { result ->
                                    Text(
                                        text = result,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .heightIn(max = 160.dp)
                                            .verticalScroll(rememberScrollState()),
                                    )
                                }
                            }
                            if (run.status == SubAgentStatus.RUNNING || run.status == SubAgentStatus.PENDING) {
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { onCancel(run.id) }) {
                                        Text("停止")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 12.dp))
        }
    }
}

// v3.11.35: 耗费统计 — in/out + 合计 (账单口径), 无用量时不显示
private fun SubAgentRun.tokenCountText(): String? {
    if (tokensIn <= 0L && tokensOut <= 0L) return null
    val total = tokensIn + tokensOut
    return "tokens ${tokensIn}/${tokensOut} (合计 $total)"
}
