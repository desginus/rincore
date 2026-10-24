package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.subagent.SubAgentBudget
import me.rerere.rikkahub.subagent.SubAgentDefaults
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import me.rerere.rikkahub.ui.components.motion.LocalHazeState
import me.rerere.rikkahub.ui.components.motion.rinGlass
import me.rerere.rikkahub.ui.components.motion.rinGlassHighlight

/**
 * v5.0.57 用户定版：子代理**预算仪表盘**（与「子代理」运行详情并列的两个不同面板）。
 *
 * 用户原话约束：
 *  - 「子代理」点开 = 正在运行的子代理详细信息（SubAgentDetailSheet）
 *  - 「子代理预算」点开 = **可调节**的三项 + 实时消耗（本面板）
 *
 * 三项可调：
 *  ① 总预算（Token，跨对话累计上限；来自 Settings.subagentTokenBudget，熔断判据）
 *  ② 同时运行上限（每助手 1..8；Assistant.maxConcurrentSubAgents）
 *  ③ 全局并发硬上限（SubAgentDefaults.GLOBAL_CONCURRENCY_CAP，只读展示当前常数）
 *
 * 参考实现：v4.8.190 `SubAgentBudget`（live + 落盘双账、节流 flush、reset 同清两者）
 * 与 `ChatList` 的速览预算面板；此处把「可调节」部分补齐（4.8.190 只能看不能调）。
 */
@Composable
fun SubAgentBudgetSheet(
    runs: List<SubAgentRun>,
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    budget: SubAgentBudget,
    conversationId: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val limit = budget.limit()
    val used = conversationId?.let { budget.used(it) } ?: 0L
    val ratio = if (limit > 0) (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f

    // 总预算滑杆：0.1M ~ 5M，步进 0.1M（不能拖到 0，SubAgentBudget 有 MIN_LIMIT 硬下限）
    var budgetMillion by remember(limit) {
        mutableFloatStateOf((limit / 1_000_000f).coerceIn(0.1f, 5f))
    }

    // v5.0.89 ★统一玻璃
    val glassShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        scrimColor = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rinGlass(hazeState = LocalHazeState.current, shape = glassShape)
                .rinGlassHighlight(glassShape)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "子代理预算", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "调节上限、看实时消耗。真正的运行详情请看「子代理」那一格。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── ① 总预算（Token） ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "总预算（每个对话累计 Token）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${"%.1f".format(budgetMillion)} M",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = budgetMillion,
                        onValueChange = { budgetMillion = it },
                        valueRange = 0.1f..5f,
                        steps = 48,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val newLimit = (budgetMillion * 1_000_000f).toLong()
                            scope.launch { budget.setLimit(newLimit) }
                        }) { Text("保存上限") }
                        TextButton(onClick = {
                            budgetMillion = (limit / 1_000_000f).coerceIn(0.1f, 5f)
                        }) { Text("还原") }
                    }
                    Text(
                        text = "超出即熔断：终止运行中的子代理 + 禁止新派发。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── ② 同时运行上限（每助手） ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "同时运行上限（本助手）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${assistant.maxConcurrentSubAgents} 个",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    var perAssistant by remember(assistant.id) {
                        mutableFloatStateOf(assistant.maxConcurrentSubAgents.toFloat())
                    }
                    Slider(
                        value = perAssistant,
                        onValueChange = { perAssistant = it },
                        onValueChangeFinished = {
                            onUpdateAssistant(
                                assistant.copy(
                                    maxConcurrentSubAgents = perAssistant.toInt().coerceIn(
                                        SubAgentDefaults.MIN_PER_ASSISTANT_CAP,
                                        SubAgentDefaults.MAX_PER_ASSISTANT_CAP,
                                    )
                                )
                            )
                        },
                        valueRange = SubAgentDefaults.MIN_PER_ASSISTANT_CAP.toFloat()..
                            SubAgentDefaults.MAX_PER_ASSISTANT_CAP.toFloat(),
                        steps = SubAgentDefaults.MAX_PER_ASSISTANT_CAP -
                            SubAgentDefaults.MIN_PER_ASSISTANT_CAP - 1,
                    )
                    Text(
                        text = "全局硬上限 ${SubAgentDefaults.GLOBAL_CONCURRENCY_CAP} 个" +
                            "（超出会排队，不会丢失）。单次派发轮次 ${SubAgentDefaults.DEFAULT_MAX_TRIPS}，" +
                            "超时 ${SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS}s。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── ③ 本对话消耗 ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("本对话已用", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "${fmtTokens(used)} / ${fmtTokens(limit)}（${(ratio * 100).toInt()}%）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { ratio },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !conversationId.isNullOrBlank() && used > 0,
                            onClick = {
                                if (!conversationId.isNullOrBlank()) {
                                    scope.launch { budget.reset(conversationId) }
                                }
                            },
                        ) { Text("清零本对话") }
                    }
                }
            }

            // ── ④ 运行列表（简要，详情去「子代理」） ──
            Text(text = "最近运行", style = MaterialTheme.typography.labelLarge)
            if (runs.isEmpty()) {
                Text(
                    text = "本对话暂无子代理运行记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            runs.forEach { run ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = run.label.ifBlank { "子代理" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${statusText(run.status)} · ${run.tripCount}/${run.maxTrips}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun fmtTokens(v: Long): String = when {
    v >= 1_000_000 -> "${"%.1f".format(v / 1_000_000f)}M"
    v >= 1_000 -> "${"%.1f".format(v / 1_000f)}K"
    else -> v.toString()
}

private fun statusText(status: SubAgentStatus): String = when (status) {
    SubAgentStatus.PENDING -> "排队"
    SubAgentStatus.RUNNING -> "运行中"
    SubAgentStatus.SUCCEEDED -> "已完成"
    SubAgentStatus.FAILED -> "失败"
    SubAgentStatus.TIMED_OUT -> "超时"
    SubAgentStatus.CANCELLED -> "已取消"
}
