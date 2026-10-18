/* 【域 H·语音搜索】 | 地图: docs/APP_MAP.md §H */
package me.rerere.rikkahub.browser


/* ───【自研】BrowserCacheSweeper.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
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

    /**
     * v4.8.116: 增加 TTL — 超过 [ttlMs] 的截图无论排名一律删除
     * (缓存目录只存本应用自产工件, 无用户数据风险)。
     */
    internal fun sweep(cacheDir: File, keepLast: Int, ttlMs: Long = 24 * 60 * 60 * 1000L) {
        for (subdir in CACHE_SUBDIRS) {
            val dir = File(cacheDir, subdir)
            if (!dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            files.sortByDescending { it.lastModified() }
            val now = System.currentTimeMillis()
            files.forEachIndexed { idx, file ->
                val expired = now - file.lastModified() > ttlMs
                if (idx >= keepLast || expired) {
                    runCatching { file.delete() }.onFailure {
                        Log.w(TAG, "Failed to delete ${file.name}", it)
                    }
                }
            }
        }
    }
}
