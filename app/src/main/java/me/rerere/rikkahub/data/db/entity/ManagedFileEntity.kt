package me.rerere.rikkahub.data.db.entity

/* ───【原版对齐】ManagedFileEntity.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "managed_files",
    indices = [
        Index(value = ["relative_path"], unique = true),
        Index(value = ["folder"])
    ]
)
data class ManagedFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("folder")
    val folder: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("mime_type")
    val mimeType: String,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
