package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import android.util.Log
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import org.koin.java.KoinJavaComponent

/**
 * 开机/时区变化/应用更新后重排所有 AlarmManager 闹钟。
 * AlarmManager 注册在系统进程, 但重启后丢失 — 必须在此恢复。
 */
class AlarmRescheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReschedule"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Reschedule triggered: ${intent.action}")
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val scheduler: AlarmScheduler = KoinJavaComponent.get(AlarmScheduler::class.java)
                scheduler.rescheduleAll()
                // 同步恢复定时任务的 AlarmManager 双通道
                val cronScheduler: CronJobScheduler = KoinJavaComponent.get(CronJobScheduler::class.java)
                cronScheduler.rescheduleAlarmChannels()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule alarms", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
