package me.rerere.rikkahub.browser

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Best-effort cleanup of browser screenshot cache directories.
 * Each PNG capture ≈7.9 MB. A long session can produce hundreds of MBs.
 * Cleanup runs on every browser bind so orphans from force-stop are cleared.
 */
internal object BrowserCacheSweeper {

    private const val TAG = "BrowserCacheSweeper"
    private val CACHE_SUBDIRS = listOf("browser-stream", "browser-shots")

    fun sweep(context: Context, keepLast: Int = 20) {
        val cacheDir = context.cacheDir ?: return
        sweep(cacheDir, keepLast)
    }

    internal fun sweep(cacheDir: File, keepLast: Int) {
        for (subdir in CACHE_SUBDIRS) {
            val dir = File(cacheDir, subdir)
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            files.sortByDescending { it.lastModified() }
            files.drop(keepLast).forEach {
                runCatching { it.delete() }.onFailure {
                    Log.w(TAG, "Failed to delete ${it.name}", it)
                }
            }
        }
    }
}
