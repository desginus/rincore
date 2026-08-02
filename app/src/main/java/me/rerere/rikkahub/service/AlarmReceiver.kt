package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val CHANNEL_ID = "alarm"
        const val NOTIFICATION_ID_BASE = 10000
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra("alarm_id") ?: return
        Log.i(TAG, "Alarm triggered: $alarmId")

        // 协程化处理 (BroadcastReceiver 主线程 — runBlocking 会阻塞主线程导致 ANR)
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val repository: AlarmRepository = KoinJavaComponent.get(AlarmRepository::class.java)
                val scheduler: AlarmScheduler = KoinJavaComponent.get(AlarmScheduler::class.java)
                val alarm = repository.getById(alarmId)

                if (alarm != null && alarm.enabled) {
                    val now = System.currentTimeMillis()

                    if (alarm.scheduleType == "once") {
                        repository.markFired(alarmId, now, null)
                        repository.setEnabled(alarmId, false)
                    } else {
                        // Weekly: 重算下次触发
                        val updatedAlarm = alarm.copy(lastFiredAtMs = now, updatedAtMs = now)
                        val nextFire = scheduler.calculateNextFireAt(updatedAlarm)
                        repository.markFired(alarmId, now, nextFire)
                        if (nextFire != null) {
                            scheduler.schedule(updatedAlarm.copy(nextFireAtMs = nextFire))
                        }
                    }

                    sendAlarmNotification(context, alarm)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process alarm $alarmId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun sendAlarmNotification(context: Context, alarm: AlarmEntity) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "闹钟提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alarm.label)
            .setContentText(alarm.note ?: context.getString(me.rerere.rikkahub.R.string.alarm_notification_default_text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    context.packageManager.getLaunchIntentForPackage(context.packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        try {
            nm.notify(NOTIFICATION_ID_BASE + alarm.id.hashCode() % 1000, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted", e)
        }
    }
}
