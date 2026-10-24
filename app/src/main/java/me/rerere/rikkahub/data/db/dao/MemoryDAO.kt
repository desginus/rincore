/* 【域 I·数据存储】 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.db.dao


/* ───【原版对齐】MemoryDAO.kt | 差异 ±4 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    // v4.8.92: 助手级记忆 = 排除单对话记忆（conversation_id IS NULL）—— 单对话记忆绝不出现在
    // 其它对话的注入与"整个助手"列表里（用户定版：其它对话无法观测/修改/阅读）。
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND conversation_id IS NULL AND folder_id IS NULL ORDER BY id ASC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND conversation_id IS NULL AND folder_id IS NULL ORDER BY id ASC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    // ── v4.8.92: 单对话记忆 ──
    // v5.0.24: 项目包记忆 —— 该包内所有对话可见
    @Query("SELECT * FROM memoryentity WHERE folder_id = :folderId AND conversation_id IS NULL ORDER BY id ASC")
    fun getMemoriesOfFolderFlow(folderId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE folder_id = :folderId AND conversation_id IS NULL ORDER BY id ASC")
    suspend fun getMemoriesOfFolder(folderId: String): List<MemoryEntity>

    // v5.0.25: 助手名下全部项目包记忆（按包分组展示）
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND folder_id IS NOT NULL AND conversation_id IS NULL ORDER BY folder_id ASC, id ASC")
    fun getFolderMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE conversation_id = :conversationId ORDER BY id ASC")
    fun getMemoriesOfConversationFlow(conversationId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE conversation_id = :conversationId ORDER BY id ASC")
    suspend fun getMemoriesOfConversation(conversationId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND conversation_id IS NOT NULL ORDER BY conversation_id ASC, id ASC")
    fun getConversationMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    /**
     * v5.0.67 ★项目包记忆的**真正实现**：取一批对话各自的「仅对话内可见」记忆。
     * 用户定案：项目包不是独立存储层，而是「该对话 + 同包所有对话」的对话级记忆集合。
     */
    @Query("SELECT * FROM memoryentity WHERE conversation_id IN (:conversationIds) ORDER BY conversation_id ASC, id ASC")
    suspend fun getMemoriesOfConversations(conversationIds: List<String>): List<MemoryEntity>

    /** v5.0.67：删某 scope 下由做梦整理产生的条目（每次整理后全量重建，保证幂等） */
    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId AND source = 'dream' AND folder_id IS :folderId AND conversation_id IS :conversationId")
    suspend fun deleteDreamMemories(assistantId: String, folderId: String?, conversationId: String?)

    // ── v5.0.82 隔离带：精准重建 + 归属互斥 ──

    /** 取某 scope 下全部 dream 条目（与 deleteDreamMemories 同条件），供"只清 touched"精准重建 */
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND source = 'dream' AND folder_id IS :folderId AND conversation_id IS :conversationId")
    suspend fun getDreamMemories(assistantId: String, folderId: String?, conversationId: String?): List<MemoryEntity>

    @Query("DELETE FROM memoryentity WHERE id IN (:ids)")
    suspend fun deleteMemoriesByIds(ids: List<Long>)

    /** 历史混合层 dream 条目洗正：归属以最细层为准（对话层），folder_id 清空（幂等） */
    @Query("UPDATE memoryentity SET folder_id = NULL WHERE assistant_id = :assistantId AND source = 'dream' AND folder_id IS NOT NULL AND conversation_id IS NOT NULL")
    suspend fun clearFolderOnMixedDreams(assistantId: String)

    @Query("DELETE FROM memoryentity WHERE conversation_id = :conversationId")
    suspend fun deleteMemoriesOfConversation(conversationId: String)

    @Query("SELECT * FROM memoryentity")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Long): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Insert
    suspend fun insertMemories(memories: List<MemoryEntity>)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
