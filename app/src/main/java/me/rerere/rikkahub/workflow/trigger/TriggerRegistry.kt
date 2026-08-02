package me.rerere.rikkahub.workflow.trigger

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository

/**
 * Phase 12 — orchestrates all trigger families.
 *
 * Lifecycle:
 *  - At app start, [start] is called. It subscribes to the workflow repository's enabled-set
 *    and, on every change, drives [resync] which buckets workflows by family and asks each
 *    family to reconcile its registration state.
 *  - When a trigger fires, the family calls [TriggerFireCallback], which forwards to the
 *    workflow engine's `fire(workflowId, spec)`.
 *
 * Battery hygiene:
 *  - Each family registers/unregisters its underlying receiver based on whether any enabled
 *    workflow uses that family. Zero enabled workflows → no receivers from this feature.
 *
 * Diagnostics:
 *  - [enabledFamilyNames] returns the live family count, used by an acceptance check
 *    ("no broadcast receivers from this feature are registered with zero enabled workflows").
 */
class TriggerRegistry(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val workflowRepository: WorkflowRepository,
) {

    private val triggerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()

    /** Engine wires this in via [setEngineCallback] before [start]. */
    @Volatile private var engineFire: TriggerFireCallback? = null

    // 核心触发器 (阶段1): 时间/手动/开机/电量 — 覆盖定时任务核心需求
    // 二期: wifi/bluetooth/headphones/power/screen/geofence/appForeground/notification
    private val battery = BatteryTriggerFamily(context, triggerScope)
    private val timeCron = TimeCronTriggerFamily(context, triggerScope)
    private val boot = BootTriggerFamily(triggerScope)
    private val manual = ManualTriggerFamily()

    private val families: List<WorkflowTriggerFamily> = listOf(
        timeCron, manual, boot, battery,
    )

    fun setEngineCallback(callback: TriggerFireCallback) {
        engineFire = callback
        WorkflowBootDispatcher.bind(boot)
    }

    /** Start observing workflows. Idempotent — a second call is ignored. */
    @Volatile private var started = false
    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        appScope.launch(Dispatchers.IO) {
            workflowRepository.observeAll()
                .map { loaded -> loaded.filter { it.entity.enabled }.map { it.definition } }
                .distinctUntilChanged()
                // 500 ms quiet window so a burst of edits in the workflow editor (10 rapid
                // toggles, drag-to-reorder, paste-to-edit) coalesces into a single resync
                // instead of paying 10 × (12 trigger families × register/unregister) of
                // receiver churn. The first toggle to enable a previously-zero family
                // still pays a one-off 500 ms latency before the receiver is live, which
                // is well below user-perceptible and well above the OS broadcast delay.
                .debounce(RESYNC_DEBOUNCE_MS)
                .onEach { resync(it) }
                .collect { /* drained via onEach */ }
        }
    }

    /** Re-bucket workflows and ask each family to reconcile. */
    suspend fun resync(enabled: List<WorkflowDefinition>): Unit = syncMutex.withLock {
        val fire = engineFire ?: return@withLock
        for (family in families) {
            val matching = enabled.filter { family.handles(it.trigger) }
            runCatching { family.sync(matching, fire) }.onFailure {
                Log.w(TAG, "resync failed for family=${family.name}", it)
            }
        }
    }

    /**
     * Fire a workflow by id (used by `workflow_run` tool and the Settings "Run now"
     * button). Bypasses trigger-level filters; conditions/cooldown still apply at the
     * engine level.
     */
    suspend fun fireManual(workflowId: String) {
        val fire = engineFire ?: return
        val loaded = workflowRepository.getById(workflowId) ?: return
        runCatching { fire.onFire(workflowId, loaded.definition.trigger) }.onFailure {
            Log.w(TAG, "manual fire failed for wf=$workflowId", it)
        }
    }

    /** Called by [WorkflowTimeCronWorker] when its scheduled time arrives. */
    suspend fun fireFromTimeCronWorker(workflowId: String) {
        timeCron.onWorkerFired(workflowId)
    }

    suspend fun shutdown() {
        for (family in families) runCatching { family.shutdown() }
        triggerScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** For diagnostics / tests. */
    fun debugFamilyState(): Map<String, Boolean> = families.associate { it.name to (it.handles(TriggerSpec.Manual)) }

    companion object {
        private const val TAG = "WorkflowTrigger"
        private const val RESYNC_DEBOUNCE_MS = 500L
    }
}
