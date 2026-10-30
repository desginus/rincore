package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "memory_file", primaryKeys = ["assistant_id", "path"])
data class MemoryFileEntity(
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("path")
    val path: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
    // v5.3.7 ★统一记忆唯一表：来源（active=主动写入 / dream=做梦整理）
    @ColumnInfo("source")
    val source: String = "active",
    // v5.3.7 ★归属维度其一：项目包级（非空 = 仅该包内对话可见）
    @ColumnInfo("folder_id")
    val folderId: String? = null,
    // v5.3.7 ★归属维度其二：单对话级（非空 = 仅该对话可见；与 folderId 互斥）
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    // v5.3.7：软删标记（非空 = 已废弃，不注入/不展示，但留痕）
    @ColumnInfo("invalidated_at")
    val invalidatedAt: Long? = null,
)
