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

    // ═══ v5.3.14 ★★历史 path 形态兼容（「以前的记忆删不掉」的真根因）═══
    //
    // v5.3.7 的 MIGRATION_37_38 用 `memoryPathOf()` 生成 path，**没有前导 "/"**
    //（`"标题.md"`）；而 `MemoryFile.normalizePath()` 一律补成 `"/标题.md"`。
    // 于是所有按 path 精确匹配的 UPDATE / DELETE 对**迁移过来的老记忆全部 0 行命中**，
    // 表现就是「老记忆看得到、改不了、删不掉」（且旧实现静默返回）。
    // 下面两个用 `IN (:paths)` 的方法由 Repository 传入多种形态（原样 / 有无斜杠 / 规范化），
    // 无论 DB 里存的是哪种写法都能命中。
    @Query("UPDATE memory_file SET invalidated_at = :at WHERE assistant_id = :assistantId AND path IN (:paths)")
    suspend fun invalidateAny(assistantId: String, paths: List<String>, at: Long?): Int

    @Query("DELETE FROM memory_file WHERE assistant_id = :assistantId AND path IN (:paths)")
    suspend fun deleteFileAny(assistantId: String, paths: List<String>)

    @Query("UPDATE memory_file SET folder_id = :folderId, conversation_id = :conversationId, updated_at = :at WHERE assistant_id = :assistantId AND path IN (:paths)")
    suspend fun updateScopeAny(assistantId: String, paths: List<String>, folderId: String?, conversationId: String?, at: Long): Int

    @Query("UPDATE memory_file SET content = :content, updated_at = :at WHERE assistant_id = :assistantId AND path IN (:paths)")
    suspend fun updateContentAny(assistantId: String, paths: List<String>, content: String, at: Long): Int
}
