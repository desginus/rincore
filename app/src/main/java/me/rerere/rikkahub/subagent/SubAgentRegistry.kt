package me.rerere.rikkahub.subagent


/* ───【自研】SubAgentRegistry.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * v3.11.29: 运行记录接入 Room 落盘 (sub_agent_runs) — 重启不丢。
 * 内存 StateFlow 仍是唯一实时源 (UI 收集), 每次变更同步 upsert 到磁盘;
 * 启动时 [restoreFromDisk] 全量恢复, 遗留 running/pending 孤儿标记 FAILED。
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.SubAgentRunDao
import me.rerere.rikkahub.data.db.entity.SubAgentRunEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 11 — in-memory store of sub-agent runs + their associated coroutine [Job]s.
 *
 * The map of runs is a [StateFlow] so the chat UI's chip row can collect it; the map of
 * jobs is private since callers shouldn't be cancelling Jobs through random handles.
 * Capped at [SubAgentDefaults.REGISTRY_LRU_CAP] entries — when the cap is reached, the
 * oldest TERMINAL run gets evicted (running runs are never evicted).
 *
 * The registry intentionally does NOT enforce concurrency caps on its own — that's the
 * engine's job, since the engine has access to per-assistant configuration. This object
 * is just a typed mutable map with cancel hooks.
 */
class SubAgentRegistry(
    private val runDao: SubAgentRunDao? = null,
) {
    private val _runs = MutableStateFlow<Map<String, SubAgentRun>>(emptyMap())
    val runs: StateFlow<Map<String, SubAgentRun>> = _runs

    /** 落盘任务作用域 — 每次状态变更异步写盘, 不阻塞调用方 */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Side-table of cancellable Jobs for currently RUNNING runs. Removed once the run
     * reaches a terminal status. Kept separate from the StateFlow because [Job] is not
     * serialisable and we don't want UI consumers re-collecting on Job-pointer churn.
     */
    private val activeJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap()

    /**
     * 启动恢复: 从磁盘读回全部运行记录。
     * 遗留 running/pending (进程被杀) 标记 FAILED(process_lost)。
     * 同步返回, 供注入层在 UI 使用前一次性完成。
     */
    fun restoreFromDisk() {
        val dao = runDao ?: return
        val rows = runCatching { kotlinx.coroutines.runBlocking { dao.getAll() } }.getOrElse {
            android.util.Log.w("SubAgentRegistry", "restore: read failed", it)
            emptyList()
        }
        if (rows.isEmpty()) return
        val restored = LinkedHashMap<String, SubAgentRun>()
        val orphans = mutableListOf<SubAgentRunEntity>()
        rows.forEach { e ->
            val run = e.toRun()
            if (run.status == SubAgentStatus.RUNNING || run.status == SubAgentStatus.PENDING) {
                // 进程存活期间恢复? 不可能 — 恢复只发生在冷启动。遗留活跃记录 = 进程被杀。
                orphans += e
            } else {
                restored[run.id] = run
            }
        }
        _runs.update { restored }
        if (orphans.isNotEmpty()) {
            val now = System.currentTimeMillis()
            orphanRuns = orphans.map { e ->
                val run = e.toRun().copy(
                    status = SubAgentStatus.FAILED,
                    error = "process_lost",
                    finishedAtMs = now,
                )
                restored[run.id] = run
                run
            }
            _runs.update { restored }
            orphanRuns.forEach { persist(it) }
        }
        // 磁盘保留上限 — 与内存 cap 对齐
        runCatching { kotlinx.coroutines.runBlocking { dao.trimTerminalKeep(SubAgentDefaults.REGISTRY_LRU_CAP) } }
    }

    /** 恢复时标记的孤儿 (进程被杀) — 供外部日志/UI 说明 */
    var orphanRuns: List<SubAgentRun> = emptyList()
        private set

    fun addPending(run: SubAgentRun, job: Job? = null) {
        _runs.update { current ->
            val pruned = pruneIfNeeded(current)
            pruned + (run.id to run)
        }
        if (job != null) activeJobs[run.id] = job
        persist(run)
    }

    fun update(id: String, transform: (SubAgentRun) -> SubAgentRun) {
        _runs.update { current ->
            val existing = current[id] ?: return@update current
            val updated = transform(existing)
            persist(updated)
            current + (id to updated)
        }
    }

    fun setJob(id: String, job: Job) {
        activeJobs[id] = job
    }

    fun get(id: String): SubAgentRun? = _runs.value[id]

    fun list(activeOnly: Boolean): List<SubAgentRun> {
        val all = _runs.value.values
        return if (activeOnly) all.filter { it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING }
        else all.toList()
    }

    fun activeCountForAssistant(parentAssistantId: String): Int =
        _runs.value.values.count {
            it.parentAssistantId == parentAssistantId &&
                (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING)
        }

    fun globalActiveCount(): Int =
        _runs.value.values.count {
            it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING
        }

    /**
     * Cancel a single run by id. Returns true if a cancellable job existed; false if the
     * run was already in a terminal state or if the id is unknown. Marking the status to
     * CANCELLED is the caller's job (typically the engine after the Job's onCompletion
     * fires) so we don't double-write.
     */
    fun requestCancel(id: String): Boolean {
        val job = activeJobs.remove(id) ?: return false
        job.cancel()
        return true
    }

    /**
     * Cancel every currently-active run dispatched from [parentChatId]. Hooked into the
     * Telegram /stop handler and the in-app stop button so a single tick takes down the
     * parent generation AND all of its sub-agents. Returns the count cancelled.
     */
    fun cancelAllForParent(parentChatId: String): Int {
        var count = 0
        val toCancel = _runs.value.values
            .filter { it.parentChatId == parentChatId && (it.status == SubAgentStatus.RUNNING || it.status == SubAgentStatus.PENDING) }
            .map { it.id }
        for (runId in toCancel) {
            if (requestCancel(runId)) count++
        }
        return count
    }

    fun clearJob(id: String) {
        activeJobs.remove(id)
    }

    private fun pruneIfNeeded(current: Map<String, SubAgentRun>): Map<String, SubAgentRun> {
        if (current.size < SubAgentDefaults.REGISTRY_LRU_CAP) return current
        // Evict the oldest TERMINAL run; never evict a running one. If every run is
        // running, the cap would be exceeded — we accept this since it should be rare
        // (50 concurrent sub-agents would already have been blocked by the global cap of 16).
        val terminalSorted = current.values
            .filter { it.status != SubAgentStatus.RUNNING && it.status != SubAgentStatus.PENDING }
            .sortedBy { it.finishedAtMs ?: it.startedAtMs }
        val toEvictId = terminalSorted.firstOrNull()?.id
        if (toEvictId != null) {
            // 磁盘同步删除被逐出的记录
            persistScope.launch { runDao?.deleteById(toEvictId) }
            return current - toEvictId
        }
        return current
    }

    /** 异步落盘当前运行快照 */
    private fun persist(run: SubAgentRun) {
        val dao = runDao ?: return
        persistScope.launch {
            runCatching { dao.upsert(run.toEntity()) }
                .onFailure { android.util.Log.w("SubAgentRegistry", "persist failed for ${run.id}", it) }
        }
    }
}

/** 内存模型 → 磁盘实体 */
internal fun SubAgentRun.toEntity(): SubAgentRunEntity = SubAgentRunEntity(
    id = id,
    parentChatId = parentChatId,
    parentAssistantId = parentAssistantId,
    label = label,
    task = task,
    modelId = modelId,
    toolsJson = tools?.let { list ->
        kotlinx.serialization.json.buildJsonArray {
            list.forEach { s -> add(kotlinx.serialization.json.JsonPrimitive(s)) }
        }.toString()
    },
    runInBackground = runInBackground,
    timeoutSeconds = timeoutSeconds,
    maxTrips = maxTrips,
    status = status.name,
    result = result,
    error = error,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    tripCount = tripCount,
    updatedAtMs = System.currentTimeMillis(),
)

/** 磁盘实体 → 内存模型 */
internal fun SubAgentRunEntity.toRun(): SubAgentRun = SubAgentRun(
    id = id,
    parentChatId = parentChatId,
    parentAssistantId = parentAssistantId,
    label = label,
    task = task,
    modelId = modelId,
    tools = toolsJson?.let { raw ->
        val arr = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return@let null
        arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    },
    runInBackground = runInBackground,
    timeoutSeconds = timeoutSeconds,
    maxTrips = maxTrips,
    status = runCatching { SubAgentStatus.valueOf(status) }.getOrDefault(SubAgentStatus.FAILED),
    result = result,
    error = error,
    startedAtMs = startedAtMs,
    finishedAtMs = finishedAtMs,
    tokensIn = tokensIn,
    tokensOut = tokensOut,
    tripCount = tripCount,
)
