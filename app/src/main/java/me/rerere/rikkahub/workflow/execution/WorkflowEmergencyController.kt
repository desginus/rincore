package me.rerere.rikkahub.workflow.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

data class WorkflowEmergencyStopResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val affectedCount: Int,
)

/** Tracks only live workflow jobs; workflow definitions and enabled flags are never mutated. */
class WorkflowEmergencyController(
    private val persistedEmergencyStop: () -> Boolean = { false },
) {
    private val paused = AtomicBoolean(false)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    val isPaused: Boolean get() = paused.get() || persistedEmergencyStop()
    val activeCount: Int get() = activeJobs.size

    suspend fun <T> runTracked(workflowId: String, block: suspend () -> T): T? {
        if (isPaused) return null
        val job = currentCoroutineContext()[Job] ?: return if (isPaused) null else block()
        activeJobs[workflowId] = job
        if (isPaused) {
            activeJobs.remove(workflowId, job)
            return null
        }
        return try {
            block()
        } finally {
            activeJobs.remove(workflowId, job)
        }
    }

    fun pauseAndCancelAll(): WorkflowEmergencyStopResult {
        paused.set(true)
        val snapshot = activeJobs.values.distinct()
        snapshot.forEach { it.cancel() }
        return WorkflowEmergencyStopResult(
            ok = true,
            code = "WORKFLOWS_PAUSED",
            message = "Paused new workflow fires and cancelled ${snapshot.size} active run(s).",
            affectedCount = snapshot.size,
        )
    }

    fun resumeNewRuns() {
        paused.set(false)
    }
}
