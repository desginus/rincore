package me.rerere.rikkahub.data.repository


import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map { AssistantMemory(it.id, it.content) }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities ->
                entities.map { AssistantMemory(it.id, it.content) }
            }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID)
            .map { AssistantMemory(it.id, it.content) }
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    suspend fun updateContent(id: Long, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(
            content = content
        )
        memoryDAO.updateMemory(newMemory)
        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
        )
    }

    suspend fun addMemory(assistantId: String, content: String): AssistantMemory {
        // v3.8.29: 记忆 ID 改为创建时间戳 YYMMDDHHMMSS (如 260820213001),
        // 不再自增 1,2,3。同秒冲突顺延 (id+1) 保证唯一。
        var id: Long = java.text.SimpleDateFormat("yyMMddHHmmss", java.util.Locale.US)
            .format(java.util.Date()).toLong()
        while (memoryDAO.getMemoryById(id) != null) {
            id++
        }
        val memory = AssistantMemory(
            id = id,
            content = content,
        )
        memoryDAO.insertMemory(
            MemoryEntity(
                id = memory.id,
                assistantId = assistantId,
                content = memory.content
            )
        )
        return memory
    }

    suspend fun deleteMemory(id: Long) {
        memoryDAO.deleteMemory(id)
    }
}
