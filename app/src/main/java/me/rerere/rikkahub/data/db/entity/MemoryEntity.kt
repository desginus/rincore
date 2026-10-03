/* 【域 I·数据存储】 | 地图: docs/APP_MAP.md §I */
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
    // v4.8.92: 单对话记忆 —— 非空 = 挂载到对应对话（仅在该对话内可见/可用；随对话删除而销毁）
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    // v4.8.92: 来源对话 —— 助手级记忆标注"从哪个对话产生"（UI 小标签用；不参与可见性判定）
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
)
