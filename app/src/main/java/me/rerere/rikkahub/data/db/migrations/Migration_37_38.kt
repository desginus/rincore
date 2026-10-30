package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_37_38"

/**
 * v5.3.7 ★★用户定案（原话）：「记忆只有一个存储类别，那就是记忆。模型通过这一个类别读取、写入。
 * 这一个类别里面分为两个小类，一个是主动的，一个是做梦的。然后每个小类里面还有三个不同的 tag，
 * 分别是助手级的、项目包级的和单对话级的。」
 *
 * 统一前有两张互不相通的表：
 *  - `memory_file`（path/content，模型 memory_write 等工具写的就是这张）
 *  - `memoryentity`（content/source/folder_id/conversation_id，**注入给模型上下文的却是这张**）
 * → 模型写的记忆，模型自己下一轮都读不到（工具写 A 表、注入读 B 表）。
 *
 * 本迁移把 `memory_file` 升级为**唯一记忆表**：补上来源（source）与归属（folder_id /
 * conversation_id）两个维度，并把 `memoryentity` 的全部历史数据搬进来。
 * `memoryentity` 表保留不删（Room 迁移不能随便 DROP 旧表，且留作审计/回滚）。
 */
val MIGRATION_37_38 = object : Migration(37, 38) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 37 to 38 (unify memory storage into memory_file)")
        DatabaseMigrationTracker.onMigrationStart(37, 38)
        try {
            // ① 补维度列
            db.execSQL("ALTER TABLE memory_file ADD COLUMN source TEXT NOT NULL DEFAULT 'active'")
            db.execSQL("ALTER TABLE memory_file ADD COLUMN folder_id TEXT")
            db.execSQL("ALTER TABLE memory_file ADD COLUMN conversation_id TEXT")
            db.execSQL("ALTER TABLE memory_file ADD COLUMN invalidated_at INTEGER")

            // ② 旧条目式记忆搬进唯一表
            var moved = 0
            var skipped = 0
            val cursor = db.query(
                "SELECT id, assistant_id, content, conversation_id, folder_id, source, " +
                    "invalidated_at, updated_at FROM memoryentity"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val assistantId = if (c.isNull(1)) null else c.getString(1)
                    if (assistantId.isNullOrBlank()) {
                        skipped++
                        continue
                    }
                    val content = if (c.isNull(2)) "" else c.getString(2)
                    val conversationId = if (c.isNull(3)) null else c.getString(3)
                    val folderId = if (c.isNull(4)) null else c.getString(4)
                    val legacySource = if (c.isNull(5)) "manual" else c.getString(5)
                    val invalidatedAt = if (c.isNull(6)) null else c.getLong(6)
                    val updatedAt = if (c.isNull(7)) 0L else c.getLong(7)

                    // 归属互斥：对话级优先于项目包级（与 MemoryRepository 的归一化同口径）
                    val finalConversationId = conversationId?.takeIf { it.isNotBlank() }
                    val finalFolderId = if (finalConversationId != null) null else folderId?.takeIf { it.isNotBlank() }
                    // 来源二选一：dream / 其余一律 active（user 与 manual 都算主动）
                    val unifiedSource = if (legacySource == "dream") "dream" else "active"

                    val basePath = memoryPathOf(content, id)
                    var path = basePath
                    var suffix = 1
                    while (memoryPathExists(db, assistantId, path)) {
                        path = basePath.removeSuffix(".md") + "-${id}_${suffix}.md"
                        suffix++
                    }

                    db.execSQL(
                        "INSERT OR REPLACE INTO memory_file " +
                            "(assistant_id, path, content, updated_at, source, folder_id, conversation_id, invalidated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(assistantId, path, content, updatedAt, unifiedSource, finalFolderId, finalConversationId, invalidatedAt)
                    )
                    moved++
                }
            }
            Log.i(TAG, "migrate: migrate from 37 to 38 success (moved=$moved skipped=$skipped)")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

/** 从正文生成一个可读的文件名（迁移时用作 path，UI 的标题就取这里）。 */
private fun memoryPathOf(content: String, id: Long): String {
    val firstLine = content.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .removePrefix("#")
        .removePrefix("【")
        .removeSuffix("】")
        .trim()
    val cleaned = firstLine
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(40)
    return if (cleaned.isBlank()) "memory_$id.md" else "$cleaned.md"
}

private fun memoryPathExists(db: SupportSQLiteDatabase, assistantId: String, path: String): Boolean {
    val c = db.query("SELECT 1 FROM memory_file WHERE assistant_id = ? AND path = ? LIMIT 1", arrayOf(assistantId, path))
    return c.use { it.moveToFirst() }
}
