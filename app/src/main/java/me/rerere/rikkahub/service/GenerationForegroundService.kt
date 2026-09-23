/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import me.rerere.rikkahub.GENERATION_FOREGROUND_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * 【域 A·对话核心】生成保活前台服务
 *
 * 问题: 生成期间切后台 (卡片态 / 切其他应用), 进程降级 cached 优先级 —
 * 澎湃 OS 等激进 ROM 直接冻结网络/进程, 断流率近 100%; WakeLock/WifiLock
 * 只保 CPU/射频, 不保进程优先级与网络管制。
 *
 * 方案: 生成期间持有前台服务 — 进程为 foreground 优先级, 系统不冻结网络、
 * 不杀进程、不受 App Standby 限制。Android 上"后台持续工作"的系统级正解。
 *
 * 生命周期 (由 ChatService.handleMessageComplete 驱动):
 *   acquire → 生成开始 (前台时调用, 不受后台启动限制)
 *   release → 生成结束 (NonCancellable 内, 取消态也执行; 3s 防抖停止)
 * 多会话共享同一服务; 停止由 activeSessions 集合控制。
 */
class GenerationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即 startForeground (startForegroundService 5s 规则; 重复调用幂等)
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // 兜底: 服务被拉起但已无活跃生成 (进程重启等边缘)
        if (activeSessions.isEmpty()) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM 会在系统侧拒绝 FGS 类型权限 (WebServerService 同款防护)
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, GENERATION_FOREGROUND_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.generation_service_notification_title))
            .setContentText(getString(R.string.generation_service_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "GenFgSvc"
        private const val NOTIFICATION_ID = 8842
        private const val STOP_DEBOUNCE_MS = 3_000L

        /** 活跃生成会话 (多会话共享一个前台服务)。 */
        private val activeSessions = ConcurrentHashMap.newKeySet<String>()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var appContext: Context? = null

        private val stopRunnable = Runnable {
            if (activeSessions.isEmpty()) {
                appContext?.let { ctx ->
                    try {
                        ctx.stopService(Intent(ctx, GenerationForegroundService::class.java))
                    } catch (e: Exception) {
                        Log.w(TAG, "stopService failed: ${e.message}")
                    }
                }
            }
        }

        /** 生成开始: 启动 (或复用) 前台服务。前台发起时调用无限制; 后台
         *  启动 (队列自动继续等) 被系统拒绝时静默降级 (WakeLock/WifiLock 兜底)。 */
        fun acquire(context: Context, conversationId: String) {
            val app = context.applicationContext
            appContext = app
            mainHandler.removeCallbacks(stopRunnable)
            activeSessions.add(conversationId)
            try {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, GenerationForegroundService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "startForegroundService rejected (background restriction?): ${e.message}")
            }
        }

        /** 生成结束: 减记; 全部结束时 3s 防抖停止 (regenerate 瞬态不误停)。 */
        fun release(context: Context, conversationId: String) {
            val app = context.applicationContext
            appContext = app
            activeSessions.remove(conversationId)
            if (activeSessions.isEmpty()) {
                mainHandler.postDelayed(stopRunnable, STOP_DEBOUNCE_MS)
            }
        }
    }
}
