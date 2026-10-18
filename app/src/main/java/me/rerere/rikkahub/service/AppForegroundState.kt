/* 【域 L·基础设施】 — 服务 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.service

/* ───【自研】AppForegroundState.kt — 前后台状态单一事实源 (v4.8.99 线程/能效改革)
 * ActivityLifecycleCallbacks resumed 计数判据 (HyperOS 3 上 ProcessLifecycleOwner
 * 不可靠 — 与 ChatNotificationManager 同款); 供心跳能效策略等消费。
 * ───────────────────────────────────────────────────────────────*/

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

object AppForegroundState {
    @Volatile
    var isBackground: Boolean = true
        private set

    /** 前台恢复纪元 — 每次"回到前台"自增 (心跳循环据此立即补 ping) */
    @Volatile
    var foregroundEpoch: Long = 0L
        private set

    private val resumedCount = AtomicInteger(0)

    @Volatile
    private var registered = false

    fun register(context: Application) {
        if (registered) return
        registered = true
        context.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (resumedCount.incrementAndGet() > 0) {
                    isBackground = false
                    foregroundEpoch += 1
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedCount.decrementAndGet() <= 0) {
                    isBackground = true
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
