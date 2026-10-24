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
    // v5.0.24 用户定案：记忆三范围 —— 非空 = 项目包记忆（仅在该项目包内的对话可见）
    @ColumnInfo("folder_id")
    val folderId: String? = null,
    // v5.0.26 用户定案：记忆来源 —— manual=模型主动写入 / user=用户手动添加 / dream=做梦整理
    @ColumnInfo("source")
    val source: String = "manual",
    // v5.0.85（用户 B3）：失效标记（软删）。非空 = 已废弃，不再注入上下文/不在管理页展示，但留痕。
    @ColumnInfo("invalidated_at")
    val invalidatedAt: Long? = null,
    // v5.0.85（用户 B3）：最后更新时间戳（环境类结论随时间失效，用户据此判断新旧）。
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0L,
)
