package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus

/**
 * v5.0.25 用户定案：子代理预算面板（与「子代理」运行详情并列的第二格）。
 *
 * 「子代理」= 谁在跑/跑到哪（SubAgentDetailSheet）；
 * 「子代理预算」= 每个子代理花了多少/还剩多少（本面板）：
 *   - 工具调用次数 tripCount / maxTrips
 *   - 超时预算 timeoutSeconds
 *   - 累计 Token 入/出
 *   - 模型（null = 继承父对话）
 */
@Composable
fun SubAgentBudgetSheet(runs: List<SubAgentRun>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "子代理预算与消耗",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "预算来自派发时的超时与工具调用上限；消耗按当前运行实时累计。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (runs.isEmpty()) {
                Text(
                    text = "本对话暂无子代理运行记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            runs.forEach { run ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = run.label.ifBlank { "子代理" },
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = statusText(run.status),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "工具调用 ${run.tripCount} / ${run.maxTrips}    ·    超时预算 ${run.timeoutSeconds}s",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Token 入 ${run.tokensIn} / 出 ${run.tokensOut}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = if (run.modelId == null) "模型：继承父对话" else "模型：${run.modelId}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun statusText(status: SubAgentStatus): String = when (status) {
    SubAgentStatus.PENDING -> "排队"
    SubAgentStatus.RUNNING -> "运行中"
    SubAgentStatus.SUCCEEDED -> "已完成"
    SubAgentStatus.FAILED -> "失败"
    SubAgentStatus.TIMED_OUT -> "超时"
    SubAgentStatus.CANCELLED -> "已取消"
}
