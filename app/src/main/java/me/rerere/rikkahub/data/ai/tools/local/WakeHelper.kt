package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context

/**
 * Shared entry point — wake the screen before doing something the user can see.
 * Best-effort only; never throws.
 */
fun wakeScreenIfNeeded(context: Context) {
    try {
        if (!ScreenWaker.isInteractive(context)) {
            ScreenWaker.wakeIfOff(context)
        }
    } catch (_: Throwable) { /* best-effort */ }
}
