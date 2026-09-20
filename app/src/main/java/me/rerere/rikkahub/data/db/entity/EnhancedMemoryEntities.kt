package me.rerere.rikkahub.data.db.entity


/* ───【自研】增强记忆实体 (v4.6.5 — 增强记忆工具 ↔ RinCore 原生记忆系统"交火"数据面)
 * 承载 extended_memory_tools 包的记忆节点/链接: 标题 + 内容 + 文件夹 + 标签 + 语义链接。
 * assistant_id 为空串 = 全局记忆; 非空 = 助手专属 (与原生 MemoryEntity 同语义)。
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mem_nodes")
data class MemNodeEntity(
    @PrimaryKey(true)
    val id: Long = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String = "",
    val title: String = "",
    val content: String = "",
    @ColumnInfo("content_type")
    val contentType: String = "text",
    val source: String = "",
    @ColumnInfo("folder_path")
    val folderPath: String = "",
    /** JSON array 字符串 (标签列表) */
    val tags: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long = 0,
    @ColumnInfo("updated_at")
    val updatedAt: Long = 0,
)

@Entity(tableName = "mem_links")
data class MemLinkEntity(
    @PrimaryKey(true)
    val id: Long = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String = "",
    @ColumnInfo("source_title")
    val sourceTitle: String = "",
    @ColumnInfo("target_title")
    val targetTitle: String = "",
    @ColumnInfo("link_type")
    val linkType: String = "related",
    val weight: Double = 1.0,
    val description: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long = 0,
)
