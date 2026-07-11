package me.rerere.rikkahub.data.agentrun

import android.util.Log
import me.rerere.rikkahub.AppScope
import kotlinx.coroutines.launch

private const val TAG = "AgentRunBootRecovery"

/**
 * 启动时扫描 agent_runs 账本，将进程被杀时遗留的 in-flight 行标记为 process_lost。
 *
 * 覆盖范围：cron、sub-agent 等所有通过 AgentRunRepository 写入的自主运行路径。
 * 进程被杀时，正在执行的 run 状态停留在 queued / awaiting_approval / running，
 * 重启后这些行永远不会自然终止。本类在应用启动时一次性扫描并标记。
 *
 * 调用时机：RikkaHubApp.onCreate → AppScope.launch。
 * 幂等：只翻转非终止行，对已终止行无副作用。
 */
class AgentRunBootRecovery(
    private val repository: AgentRunRepository,
    private val appScope: AppScope,
) {
    fun runSweep() {
        appScope.launch {
            runCatching {
                val stranded = repository.getStranded(
                    System.currentTimeMillis() - AgentRunDefaults.STRANDED_THRESHOLD_MS
                )
                if (stranded.isEmpty()) return@runCatching
                val flipped = repository.markAllProcessLost(stranded.map { it.id })
                if (flipped > 0) {
                    Log.i(TAG, "boot recovery: flipped $flipped stranded run(s) to process_lost")
                }
            }.onFailure {
                Log.w(TAG, "boot recovery sweep failed", it)
            }
        }
    }
}
