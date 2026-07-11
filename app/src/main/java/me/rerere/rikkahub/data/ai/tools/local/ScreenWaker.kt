package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.os.PowerManager

/** Reusable helpers for tools that need the screen on. */
internal object ScreenWaker {
    fun isInteractive(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    @Suppress("DEPRECATION")
    fun wakeIfOff(ctx: Context, holdMs: Long = 3_000L): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        if (pm.isInteractive) return false
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "rincore:wake_screen"
        )
        return try {
            wl.acquire(holdMs)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
