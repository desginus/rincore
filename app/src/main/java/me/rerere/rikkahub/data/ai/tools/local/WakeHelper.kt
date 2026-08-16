package me.rerere.rikkahub.data.ai.tools.local


/* ───【自研】WakeHelper.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
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
