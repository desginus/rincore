package me.rerere.rikkahub.browser

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-tool toggle store backed by SharedPreferences.
 * Defaults come from BrowserToolDefaults.DEFAULT_ENABLED — read tools default ON,
 * write tools default OFF, loop-control tool default ON.
 */
class BrowserPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)

    fun isToolEnabled(toolName: String): Boolean {
        val key = "tool_$toolName"
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, false)
        } else {
            BrowserToolDefaults.DEFAULT_ENABLED[toolName] ?: false
        }
    }

    fun setToolEnabled(toolName: String, enabled: Boolean) {
        prefs.edit().putBoolean("tool_$toolName", enabled).apply()
    }

    fun getAllEnabled(): Map<String, Boolean> =
        BrowserToolDefaults.ALL_TOOLS.associateWith { isToolEnabled(it) }
}
