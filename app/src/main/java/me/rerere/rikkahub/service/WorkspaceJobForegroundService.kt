package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID

private const val TAG = "WorkspaceJobFgs"

/**
 * ★v5.3.62（对齐上游 3.1.2 的 BackgroundShellForegroundService）：有后台命令在跑时把进程留在前台。
 *
 * 为什么必须有它：`workspace_job` 起的 proot 子进程是**应用进程的子进程** —— 应用被系统回收，
 * 子进程一起被杀。此前只有界面在前台时任务才可靠，切走/锁屏久了就可能「任务凭空消失」。
 * 前台服务让系统把进程当作「正在为用户工作」而不回收。
 *
 * 命令本身仍由 `WorkspaceManager.startJob` 持有，这里**只负责生命周期**（不碰命令、不碰文件）。
 * 任务结束由 [WorkspaceJobKeeper] 轮询 `jobStatus` 后调用 [release]。
 */
class WorkspaceJobForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.WORKSPACE_JOB_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.WORKSPACE_JOB_RELEASE"
        private const val EXTRA_JOB_ID = "job_id"

        const val NOTIFICATION_ID = 2004

        /**
         * 申请保活。返回 false 时调用方**不需要做任何补偿** —— 任务照常跑，只是可能被系统回收，
         * 与引入这个服务之前的行为完全一致（可失败旁路）。
         */
        fun acquire(context: Context, jobId: String): Boolean {
            val intent = Intent(context, WorkspaceJobForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start workspace job foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, jobId: String) {
            val intent = Intent(context, WorkspaceJobForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release workspace job foreground service", it)
            }
        }
    }

    /** 正在保活的任务 ID（多个任务同时跑时只留一个通知，文案报数量） */
    private val activeJobs = linkedSetOf<String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeJobs.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 系统对 dataSync 前台服务有时长上限。命令本身不依赖前台服务才能跑 ——
        // 进程还在就继续，被回收了就靠日志与 job 状态在下次查询时反映（不在这里杀任务）。
        Log.w(TAG, "Foreground service timed out (type=$fgsType) — job keeps running")
        activeJobs.clear()
        stopService()
    }

    private fun acquire(intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return stopService()
        activeJobs.add(jobId)
        updateForegroundNotification()
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_JOB_ID)?.let(activeJobs::remove)
        if (activeJobs.isEmpty()) stopService() else updateForegroundNotification()
    }

    private fun updateForegroundNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeJobs.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, WORKSPACE_JOB_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.workspace_job_notification_title))
            .setContentText(
                getString(R.string.workspace_job_notification_running, activeJobs.size)
            )
            .setContentIntent(openAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
