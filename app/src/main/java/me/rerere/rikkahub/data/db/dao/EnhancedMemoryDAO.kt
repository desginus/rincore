package me.rerere.rikkahub.data.db.dao


/* ───【自研】增强记忆 DAO (v4.6.5) ───────────────────────────────*/
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.MemLinkEntity
import me.rerere.rikkahub.data.db.entity.MemNodeEntity

@Dao
interface MemNodeDAO {
    /** 助手专属 + 全局 (assistant_id = '' ) 的节点, 更新时间倒序 */
    @Query("SELECT * FROM mem_nodes WHERE assistant_id = :assistantId OR assistant_id = '' ORDER BY updated_at DESC")
    suspend fun listForAssistant(assistantId: String): List<MemNodeEntity>

    @Query("SELECT * FROM mem_nodes ORDER BY updated_at DESC")
    suspend fun listAll(): List<MemNodeEntity>

    @Query("SELECT * FROM mem_nodes WHERE title = :title AND (assistant_id = :assistantId OR assistant_id = '') ORDER BY id DESC LIMIT 1")
    suspend fun findByTitle(title: String, assistantId: String): MemNodeEntity?

    @Query("SELECT * FROM mem_nodes WHERE id = :id")
    suspend fun findById(id: Long): MemNodeEntity?

    @Insert
    suspend fun insert(entity: MemNodeEntity): Long

    @Update
    suspend fun update(entity: MemNodeEntity)

    @Query("UPDATE mem_nodes SET folder_path = :folderPath, updated_at = :ts WHERE id = :id")
    suspend fun updateFolder(id: Long, folderPath: String, ts: Long)

    @Query("DELETE FROM mem_nodes WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface MemLinkDAO {
    @Query("SELECT * FROM mem_links WHERE assistant_id = :assistantId OR assistant_id = '' ORDER BY id DESC")
    suspend fun listForAssistant(assistantId: String): List<MemLinkEntity>

    @Query("SELECT * FROM mem_links WHERE id = :id")
    suspend fun findById(id: Long): MemLinkEntity?

    @Query("SELECT * FROM mem_links WHERE source_title = :sourceTitle AND target_title = :targetTitle ORDER BY id DESC LIMIT 1")
    suspend fun find(sourceTitle: String, targetTitle: String): MemLinkEntity?

    @Insert
    suspend fun insert(entity: MemLinkEntity): Long

    @Query("UPDATE mem_links SET link_type = :linkType, weight = :weight, description = :description WHERE id = :id")
    suspend fun updateLink(id: Long, linkType: String, weight: Double, description: String)

    @Query("DELETE FROM mem_links WHERE id = :id")
    suspend fun deleteById(id: Long)
}
