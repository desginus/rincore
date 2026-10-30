package me.rerere.rikkahub.data.repository

/* v5.3.10：统一记忆后，MemoryRepository 的旧条目式方法已无调用点；
 * 文件式测试只用到 memory_file 这一侧的 DAO，因此 legacy DAO 用最小可编译替身。
 * 任何真实调用都会抛 NotImplementedError —— 这正是想要的：测试必须走统一表。 */
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity

internal class FakeLegacyMemoryDao : MemoryDAO {
    override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> = TODO()
    override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> = TODO()
    override fun getMemoriesOfFolderFlow(folderId: String): Flow<List<MemoryEntity>> = TODO()
    override suspend fun getMemoriesOfFolder(folderId: String, assistantId: String? = null): List<MemoryEntity> = TODO()
    override fun getFolderMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> = TODO()
    override fun getMemoriesOfConversationFlow(conversationId: String): Flow<List<MemoryEntity>> = TODO()
    override suspend fun getMemoriesOfConversation(conversationId: String, assistantId: String? = null): List<MemoryEntity> = TODO()
    override fun getConversationMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> = TODO()
    override suspend fun getMemoriesOfConversations(conversationIds: List<String>, assistantId: String? = null): List<MemoryEntity> = TODO()
    override suspend fun deleteDreamMemories(assistantId: String, folderId: String?, conversationId: String?) = TODO()
    override suspend fun getDreamMemories(assistantId: String, folderId: String?, conversationId: String?): List<MemoryEntity> = TODO()
    override suspend fun deleteMemoriesByIds(ids: List<Long>) = TODO()
    override suspend fun clearFolderOnMixedDreams(assistantId: String) = TODO()
    override suspend fun getActiveDreamMemories(assistantId: String): List<MemoryEntity> = TODO()
    override suspend fun getActiveNonDreamMemories(assistantId: String): List<MemoryEntity> = TODO()
    override suspend fun invalidateMemory(id: Long, at: Long) = TODO()
    override suspend fun restoreMemory(id: Long, at: Long) = TODO()
    override suspend fun deleteMemoriesOfConversation(conversationId: String) = TODO()
    override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = TODO()
    override suspend fun getAllMemories(): List<MemoryEntity> = TODO()
    override suspend fun getMemoryById(id: Long): MemoryEntity? = TODO()
    override suspend fun insertMemory(memory: MemoryEntity): Long = TODO()
    override suspend fun insertMemories(memories: List<MemoryEntity>) = TODO()
    override suspend fun updateMemory(memory: MemoryEntity) = TODO()
    override suspend fun updateScope(id: Long, folderId: String?, conversationId: String?, at: Long) = TODO()
    override suspend fun deleteMemory(id: Long) = TODO()
    override suspend fun deleteMemoriesOfAssistant(assistantId: String) = TODO()
}
