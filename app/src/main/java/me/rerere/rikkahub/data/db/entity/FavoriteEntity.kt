package me.rerere.rikkahub.data.db.entity

/* ───【原版对齐】FavoriteEntity.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["ref_key"], unique = true),
        Index(value = ["type"]),
        Index(value = ["created_at"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("type")
    val type: String,
    @ColumnInfo("ref_key")
    val refKey: String,
    @ColumnInfo("ref_json")
    val refJson: String,
    @ColumnInfo("snapshot_json")
    val snapshotJson: String,
    @ColumnInfo("meta_json")
    val metaJson: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
