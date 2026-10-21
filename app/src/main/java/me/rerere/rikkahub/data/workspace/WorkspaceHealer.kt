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
