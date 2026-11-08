package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.core.context.GlobalContext

private const val TAG = "WorkspaceJobKeeper"

/**
 * ★v5.3.62（对齐上游 3.1.2）：让 `workspace_job` 起的后台命令在应用退到后台后仍能跑完。
 *
 * 分工：
 *  · [WorkspaceJobForegroundService] 负责「不被系统回收」（前台服务 + 通知）；
 *  · 这里负责「任务结束就撤掉前台服务」—— 每 5s 查一次 `jobStatus`，非 running 即 release。
 *
 * 设计要点（都是踩过的坑）：
 *  ①**用进程级 scope**，不能用 `viewModelScope`：用户切走对话/退出页面时 VM 会被销毁，
 *    协程被取消 → 前台服务永远不释放 = 通知常驻、白耗电。
 *  ②**所有可失败点都吞掉**：拿不到 Context/AppScope、startForegroundService 抛
 *    ForegroundServiceStartNotAllowedException（Android 12+ 后台启动限制）—— 一律只记日志，
 *    任务本身照常运行，与没有这个服务时完全一致。
 *  ③**先 acquire 再启动轮询**：反过来会有极短窗口任务已结束但服务还没起来，导致
 *    release 找不到该 jobId → 服务永不停止。
 */
object WorkspaceJobKeeper {
    private val watchers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** 任务启动后调用。失败无副作用。 */
    fun watch(
        scope: CoroutineScope,
        repository: WorkspaceRepository,
        workspaceId: String,
        jobId: String,
    ) {
        val context: Context? = runCatching {
            GlobalContext.getOrNull()?.getOrNull<Context>()
        }.getOrNull()
        if (context == null) {
            Log.w(TAG, "no Context available — job $jobId runs without foreground protection")
            return
        }

        WorkspaceJobForegroundService.acquire(context, jobId)

        watchers[jobId]?.cancel()
        watchers[jobId] = scope.launch(Dispatchers.IO) {
            try {
                // 先给任务一点启动时间，避免 startJob 刚返回、状态还是上一个任务残留时误判结束
                delay(2_000)
                while (true) {
                    val status = runCatching { repository.jobStatus(workspaceId, jobId) }.getOrNull()
                    // status == null 说明任务目录被清理或从未登记 —— 同样没有保活的理由
                    if (status == null || status.state != "running") break
                    delay(5_000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "watcher for job $jobId failed", e)
            } finally {
                watchers.remove(jobId)
                runCatching { WorkspaceJobForegroundService.release(context, jobId) }
            }
        }
    }

    /** 任务被主动 kill 时立即释放，不必等轮询发现。 */
    fun forget(jobId: String) {
        watchers.remove(jobId)?.cancel()
        val context: Context? = runCatching {
            GlobalContext.getOrNull()?.getOrNull<Context>()
        }.getOrNull() ?: return
        runCatching { WorkspaceJobForegroundService.release(context, jobId) }
    }
}
