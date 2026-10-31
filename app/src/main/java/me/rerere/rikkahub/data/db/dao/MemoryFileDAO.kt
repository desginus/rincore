package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

@Dao
interface MemoryFileDAO {
    // ═══ v5.3.12 ★★软删（invalidated_at）的唯一口径 ═══
    //
    // 此前 `getFilesOfAssistantFlow` / `getFilesOfAssistant` **没有过滤 invalidated_at**，
    // 而 `getFilesOfConversationFlow` / `getFilesBySourceFlow` 有 —— 同一张表两套读法。
    // 后果：用户在记忆页点删除 → `invalidateFile` 写入失效标记 → **列表照常显示该条**
    // = 用户视角「做梦整理的记忆无法删除」（真实根因，不是 UI、不是权限）。
    //
    // 现在的规则（读过滤 / 写可见）：
    //  · 给 UI / 注入用的**读取查询**一律带 `invalidated_at IS NULL OR = 0`；
    //  · 给 `update()` 判存用的 **`getFile` 不过滤** —— 它必须看到已失效的条目，
    //    才能把原 `invalidatedAt` 原样写回，保证「用户删掉的内容不会被模型/做梦写复活」。
    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND (invalidated_at IS NULL OR invalidated_at = 0) ORDER BY path")
    fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND (invalidated_at IS NULL OR invalidated_at = 0) ORDER BY path")
    suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity>

    /** 写入判存专用：**不过滤**失效标记（见上方规则说明）。 */
    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    suspend fun getFile(assistantId: String, path: String): MemoryFileEntity?

    @Upsert
    suspend fun upsertFile(file: MemoryFileEntity)

    @Upsert
    suspend fun upsertFiles(files: List<MemoryFileEntity>)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND path = :path")
    suspend fun deleteFile(assistantId: String, path: String)

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId")
    suspend fun deleteFilesOfAssistant(assistantId: String)

    // ═══ v5.3.7 统一记忆：归属 / 来源 / 软删 ═══

    @Query("UPDATE memory_file SET folder_id = :folderId, conversation_id = :conversationId, updated_at = :at WHERE assistant_id = :assistantId AND path = :path")
    suspend fun updateScope(assistantId: String, path: String, folderId: String?, conversationId: String?, at: Long)

    @Query("SELECT * FROM memory_file WHERE conversation_id = :conversationId AND (invalidated_at IS NULL OR invalidated_at = 0) ORDER BY path")
    fun getFilesOfConversationFlow(conversationId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM memory_file WHERE conversation_id = :conversationId AND (invalidated_at IS NULL OR invalidated_at = 0) ORDER BY path")
    suspend fun getFilesOfConversation(conversationId: String): List<MemoryFileEntity>

    @Query("DELETE FROM memory_file WHERE conversation_id = :conversationId")
    suspend fun deleteFilesOfConversation(conversationId: String)

    @Query("UPDATE memory_file SET source = :source, updated_at = :at WHERE assistant_id = :assistantId AND path = :path")
    suspend fun updateSource(assistantId: String, path: String, source: String, at: Long)

    @Query("UPDATE memory_file SET invalidated_at = :at WHERE assistant_id = :assistantId AND path = :path")
    suspend fun invalidate(assistantId: String, path: String, at: Long?): Int

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND source = :source AND (invalidated_at IS NULL OR invalidated_at = 0) ORDER BY path")
    fun getFilesBySourceFlow(assistantId: String, source: String): Flow<List<MemoryFileEntity>>
}
