package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

@Dao
interface MemoryFileDAO {
    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId ORDER BY path")
    suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity>

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
    suspend fun invalidate(assistantId: String, path: String, at: Long?)

    @Query("SELECT * FROM memory_file WHERE assistant_id = :assistantId AND source = :source AND (invalidated_at IS NULL OR invalidated_at = 0) ORDER BY path")
    fun getFilesBySourceFlow(assistantId: String, source: String): Flow<List<MemoryFileEntity>>
}
