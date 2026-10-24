package me.rerere.rikkahub.data.workspace

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File

/**
 * v5.0.49 用户定案：**让工作区从零可用**。
 *
 * 背景（用户实证）：
 *  - 备份里工作区文件在 `workspaces.tar.gz`（目录树），而"工作区条目"在 `database` 的 `workspaces` 表；
 *  - 但用户可能只勾「文件与工作区」不勾「聊天记录」→ 目录解压出来了，DB 里却没有条目
 *    → 打开应用看不到任何工作区，等于白导；
 *  - 另一个场景：DB 恢复失败/回滚，但目录已在，同样"有文件没条目"。
 *
 * 本类在启动时做一次**只读目录 → 补齐 DB 条目**的自愈：
 *  - 扫描 `files/workspaces/<id>/`（跳过 `*.import-backup-*` / `*.backup-*`）；
 *  - 若 `<id>` 在 `workspaces` 表里不存在，且目录内确有 `files/` 或 `linux/`，
 *    则插入一条 WorkspaceEntity（id=目录名、root=目录名 —— root 是**相对 id**，跨包名安全）；
 *  - 绝不删除、绝不改动已有条目。
 */
object WorkspaceHealer {

    /**
     * v5.0.89（用户报「工作区里凭空多出 RENDER 和 RIN-GLBS 两个目录」）：
     * 这两个目录**不是应用代码创建的**（全库无字面量），来源是运行期工具/脚本在工作区根
     * 随手建的中转目录。这里只做**保守清理**：
     *  - 只处理白名单名字（RENDER / RIN-GLBS / .render / render / rin-glbs）
     *  - **只删空目录**（有内容一律不动，避免误删用户产物）
     *  - 幂等，启动时跑一次
     */
    private val TEMP_DIR_NAMES = setOf(
        "RENDER", "RIN-GLBS", "render", "rin-glbs", ".render", ".rin-glbs",
    )

    suspend fun purgeEmptyTempDirs(context: Context): Int = withContext(Dispatchers.IO) {
        val wsRoot = File(context.filesDir, "workspaces")
        if (!wsRoot.isDirectory) return@withContext 0
        var removed = 0
        wsRoot.listFiles()?.forEach { ws ->
            if (!ws.isDirectory) return@forEach
            val filesRoot = File(ws, "files")
            if (!filesRoot.isDirectory) return@forEach
            TEMP_DIR_NAMES.forEach { name ->
                val dir = File(filesRoot, name)
                if (dir.isDirectory) {
                    val children = dir.listFiles()
                    if (children.isNullOrEmpty()) {
                        if (runCatching { dir.delete() }.getOrDefault(false)) removed++
                    } else {
                        android.util.Log.i(
                            "WorkspaceHealer",
                            "keep non-empty temp dir: ${dir.absolutePath} (${children.size} entries)",
                        )
                    }
                }
            }
        }
        removed
    }

    suspend fun heal(context: Context, dao: WorkspaceDAO): Int = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "workspaces")
        if (!root.isDirectory) return@withContext 0

        val existing = runCatching { dao.getAll().map { it.id }.toSet() }.getOrElse { emptySet() }
        var added = 0

        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val name = dir.name
            if (name.contains(".import-backup-") || name.contains(".backup-")) return@forEach
            if (name in existing) return@forEach
            val hasContent = File(dir, "files").exists() || File(dir, "linux").exists()
            if (!hasContent) return@forEach
            runCatching {
                dao.upsert(
                    WorkspaceEntity(
                        id = name,
                        name = name,
                        root = name,
                        shellStatus = WorkspaceShellStatus.DISABLED.name,
                        createdAt = dir.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                added++
            }
        }
        added
    }
}
