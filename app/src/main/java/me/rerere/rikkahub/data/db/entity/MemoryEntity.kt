package me.rerere.rikkahub.data.db.entity

/* ───【原版对齐】MemoryEntity.kt | 差异 ±6 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/


import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Long = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
)
