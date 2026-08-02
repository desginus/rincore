package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 定时任务 (cron job) 的 AlarmManager 精确触发通道。
 *
 * 背景: WorkManager 依赖 JobScheduler, 在小米澎湃的省电策略下可能被延迟;
 * 本接收器由 AlarmManager.setExactAndAllowWhileIdle 精确唤醒 (系统进程持有,
 * app 被杀后仍触发), 收到后立即 enqueue 执行 CronJobWorker。
 *
 * 去重: 使用独立 work 名 "cron_job_<id>_alarm" — 若 WorkManager 原 pending 同时到点,
 * CronJobWorker 的 CronJobRunningTracker + replay guard 会抑制重复执行。
 * 执行完成后 worker 内部重排 (schedule 下一轮 → 同时重排 AlarmManager 通道)。
 */
class CronAlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CronAlarmReceiver"
        const val EXTRA_JOB_ID = "cron_job_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        Log.i(TAG, "Cron alarm triggered: $jobId")

        val req = OneTimeWorkRequestBuilder<CronJobWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder()
                .putString(CronJobWorker.KEY_JOB_ID, jobId)
                .build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cron_job_${jobId}_alarm",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
