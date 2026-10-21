package me.rerere.rikkahub.data.workspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// v5.0.40 用户最终定案（2026-10-21）：
//   「工作区里的文件不要删，只保留文件 + 工作区的配置，剩下的全清，强制清除」
//
// 保留（绝对）：
//   A. files/workspaces/<真实工作区 id>/files/**   —— 工作区文件（用户数据本体）
//   B. databases/**                                —— 工作区配置 + 聊天记录（工作区列表靠它）
//
// 删除（其余一切）：
//   C. files/workspaces/<id>/linux/**   —— rootfs（GB 级，可重建）
//   D. files/workspaces/<id>/tmp/**     —— 临时
//   E. files/workspaces/*.import-backup-* / *.backup-*  —— 我做的导入备份副本（本次元凶）
//   F. files/ 下除 workspaces 外全部（upload / chat-media / media_creation / tool_outputs /
//      skills / fonts / builtin_skills / .rin-bridge / 导出压缩包）
//   G. shared_prefs/ cache/ code_cache/ no_backup/ app_webview/ app_textures/
//
// 安全：工作区 files 目录与 databases 的 canonicalPath 进保护集合，永不进删除队列。
object StorageCleanup {

    data class Item(val path: String, val bytes: Long, val label: String, val keep: Boolean = false)

    data class Report(val items: List<Item>) {
        val totalBytes: Long get() = items.filter { !it.keep }.sumOf { it.bytes }
        val keepBytes: Long get() = items.filter { it.keep }.sumOf { it.bytes }
        val deleteCount: Int get() = items.count { !it.keep }
    }

    private fun sizeOf(file: File): Long =
        runCatching {
            if (file.isFile) file.length()
            else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    /** 受保护：每个真实工作区的 files 子目录 + databases */
    private fun protectedPaths(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        val wsRoot = File(context.filesDir, "workspaces")
        wsRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.contains(".import-backup-") && !it.name.contains(".backup-") }
            ?.forEach { ws ->
                val filesDir = File(ws, "files")
                if (filesDir.exists()) {
                    runCatching { out += filesDir.canonicalPath }
                }
            }
        val dbDir = File(context.filesDir.parentFile, "databases")
        if (dbDir.exists()) runCatching { out += dbDir.canonicalPath }
        return out
    }

    suspend fun scan(context: Context): Report = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        val files = context.filesDir
        val wsRoot = File(files, "workspaces")
        val protectedSet = protectedPaths(context)

        fun addIfDeletable(file: File, label: String) {
            if (!file.exists()) return
            val cp = runCatching { file.canonicalPath }.getOrNull() ?: return
            // 命中保护集合（或在其内部）→ 标记为保留，不参与删除
            val isKept = protectedSet.any { cp == it || cp.startsWith("$it/") }
            val size = sizeOf(file)
            if (size <= 0L && !file.isDirectory) return
            out += Item(file.absolutePath, size, label, keep = isKept)
        }

        // ① 各工作区：files 保留；linux/tmp 删除
        wsRoot.listFiles()?.forEach { ws ->
            if (ws.name.contains(".import-backup-") || ws.name.contains(".backup-")) {
                out += Item(ws.absolutePath, sizeOf(ws), "导入备份副本（我留下的垃圾）", keep = false)
                return@forEach
            }
            if (!ws.isDirectory) return@forEach
            addIfDeletable(File(ws, "files"), "工作区文件（保留）")
            addIfDeletable(File(ws, "linux"), "工作区 rootfs（可重建）")
            addIfDeletable(File(ws, "tmp"), "工作区临时文件")
            ws.listFiles()?.forEach { other ->
                if (other.name !in setOf("files", "linux", "tmp")) {
                    addIfDeletable(other, "工作区其他：${other.name}")
                }
            }
        }

        // ② files 下除 workspaces 外全部
        files.listFiles()?.forEach { child ->
            if (child.name == "workspaces") return@forEach
            addIfDeletable(child, "应用文件：${child.name}")
        }

        // ③ 应用私有根下其余（databases 会因保护集合标为 keep）
        files.parentFile?.listFiles()?.forEach { child ->
            if (child.name == "files") return@forEach
            addIfDeletable(child, "应用数据：${child.name}")
        }

        Report(out.sortedByDescending { it.bytes })
    }

    /** 执行清理：删除所有非 keep 项，返回释放字节；同时把 keep 项的真实体积也返回（便于 UI 展示） */
    suspend fun clean(context: Context, report: Report): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val protectedSet = protectedPaths(context)
        var freed = 0L
        var kept = 0L
        report.items.forEach { item ->
            if (item.keep) {
                kept += item.bytes
                return@forEach
            }
            val f = File(item.path)
            val cp = runCatching { f.canonicalPath }.getOrNull()
            if (cp != null && protectedSet.any { cp == it || cp.startsWith("$it/") }) {
                kept += item.bytes
                return@forEach
            }
            freed += sizeOf(f)
            runCatching { f.deleteRecursively() }
        }
        context.cacheDir?.deleteRecursively()
        runCatching { context.codeCacheDir?.deleteRecursively() }
        freed to kept
    }
}
