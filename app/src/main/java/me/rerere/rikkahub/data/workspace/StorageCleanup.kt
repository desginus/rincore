package me.rerere.rikkahub.data.workspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

///**
// * v5.0.37 用户定案：**只留 workspaces 正文，其他全部删除**（对话数据用户已另行备份）。
// *
// * 保留（绝对）：
// *  - `files/workspaces/<真实工作区 id>/` 整个目录（含 files/、linux/、tmp/）
// *
// * 删除（其余一切）：
// *  - `files/workspaces/*.import-backup-*` / `*.backup-*`（导入时我留下的整目录副本，通常等于工作区体积）
// *  - `files/` 下除 workspaces 外全部（upload / skills / fonts / media_creation / chat-media / 导出压缩包 …）
// *  - `databases/`（聊天/设置/记忆 —— 用户已备份）
// *  - `shared_prefs/`、`datastore/`（设置）
// *  - `cache/`、`code_cache/`（含 GPU 着色器缓存）
// *  - `no_backup/`、`app_webview/`、`app_textures/`
// *
// * 安全：真实工作区目录的 canonicalPath 进保护集合，永不进删除队列。
// */
object StorageCleanup {

    data class Item(val path: String, val bytes: Long, val label: String, val keep: Boolean = false)

    data class Report(val items: List<Item>) {
        /** 可删除总量（不含受保护项） */
        val totalBytes: Long get() = items.filter { !it.keep }.sumOf { it.bytes }
        val protectedBytes: Long get() = items.filter { it.keep }.sumOf { it.bytes }
    }

    private fun sizeOf(file: File): Long =
        runCatching {
            if (file.isFile) file.length()
            else file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    private fun realWorkspaceCanonicalPaths(context: Context): Set<String> {
        val wsRoot = File(context.filesDir, "workspaces")
        return wsRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.contains(".import-backup-") && !it.name.contains(".backup-") }
            ?.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            ?.toSet()
            .orEmpty()
    }

    suspend fun scan(context: Context): Report = withContext(Dispatchers.IO) {
        val out = mutableListOf<Item>()
        val files = context.filesDir
        val wsRoot = File(files, "workspaces")
        val protectedSet = realWorkspaceCanonicalPaths(context)

        // ① workspaces 下的导入备份副本 → 删
        wsRoot.listFiles()?.forEach { child ->
            if (child.name.contains(".import-backup-") || child.name.contains(".backup-")) {
                val b = sizeOf(child)
                if (b > 0) out += Item(child.absolutePath, b, "工作区备份副本（导入残留）")
            }
        }

        // ② 真实工作区 → 保留（仅用于展示）
        wsRoot.listFiles()?.forEach { child ->
            val cp = runCatching { child.canonicalPath }.getOrNull() ?: return@forEach
            if (cp in protectedSet) {
                out += Item(child.absolutePath, sizeOf(child), "工作区（保留）", keep = true)
            }
        }

        // ③ files 下除 workspaces 外全部 → 删
        files.listFiles()?.forEach { child ->
            if (child.name == "workspaces") return@forEach
            val b = sizeOf(child)
            if (b > 0) out += Item(child.absolutePath, b, "应用文件：${child.name}")
        }

        // ④ 应用私有根下其余目录 → 删
        files.parentFile?.listFiles()?.forEach { child ->
            if (child.name == "files") return@forEach
            val b = sizeOf(child)
            if (b > 0) out += Item(child.absolutePath, b, "应用数据：${child.name}")
        }

        Report(out.sortedByDescending { it.bytes })
    }

    /**
     * 执行清理：删除所有非 protected 项。
     * @return 释放字节数
     */
    suspend fun clean(context: Context, report: Report): Long = withContext(Dispatchers.IO) {
        val protectedSet = realWorkspaceCanonicalPaths(context)
        var freed = 0L
        report.items.forEach { item ->
            if (item.keep) return@forEach
            val f = File(item.path)
            val cp = runCatching { f.canonicalPath }.getOrNull()
            // 双保险：真实工作区绝不删
            if (cp != null && (cp in protectedSet || protectedSet.any { cp.startsWith("$it/") })) return@forEach
            freed += sizeOf(f)
            runCatching { f.deleteRecursively() }
        }
        context.cacheDir?.deleteRecursively()
        runCatching { context.codeCacheDir?.deleteRecursively() }
        freed
    }
}
