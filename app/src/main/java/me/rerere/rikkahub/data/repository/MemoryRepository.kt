/* 【域 I·数据存储】 — 仓库层 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.repository

/* ───【原版对齐】MemoryRepository.kt | 基线 2.5.1 + v4.8.92 单对话记忆扩展
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.model.AssistantMemory

/**
 * 记忆仓库。
 *
 * v4.8.92: 引入「单对话记忆」—— [AssistantMemory.conversationId] 非空 = 该记忆挂载到对应对话：
 *  · 只在**它所属的对话**里被注入（其它对话不可观测）；
 *  · 修改/删除走 [updateContentChecked]/[deleteMemoryChecked] 守卫（其它对话不可修改）；
 *  · 对话删除时由 [deleteMemoriesOfConversation] 一并销毁。
 * [AssistantMemory.sourceConversationId] 只作 UI 来源标签（助手级记忆"从哪个对话产生"）。
 */
class MemoryRepository(private val memoryDAO: MemoryDAO) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
    }

    private fun MemoryEntity.toModel(): AssistantMemory = AssistantMemory(
        id = id,
        content = content,
        conversationId = conversationId,
        sourceConversationId = sourceConversationId,
    )

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId).map { it.toModel() }
    }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(GLOBAL_MEMORY_ID)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getGlobalMemories(): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(GLOBAL_MEMORY_ID).map { it.toModel() }
    }

    // ── v4.8.92: 单对话记忆 ──

    fun getMemoriesOfConversationFlow(conversationId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfConversationFlow(conversationId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfConversation(conversationId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfConversation(conversationId).map { it.toModel() }
    }

    /** 某助手（或全局桶）名下的全部单对话记忆（管理页「单对话记忆」视图用） */
    fun getConversationMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getConversationMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    /** v4.8.92: 对话删除时的级联清理（单对话记忆随对话销毁） */
    suspend fun deleteMemoriesOfConversation(conversationId: String) {
        memoryDAO.deleteMemoriesOfConversation(conversationId)
    }

    suspend fun updateContent(id: Long, content: String): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val newMemory = old.copy(content = content)
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    /**
     * v4.8.92: 带守卫的修改（模型侧用）—— 其它对话的单对话记忆不可被观测/修改。
     * 守卫失败抛错，回执由错误信息生成。
     */
    suspend fun updateContentChecked(
        id: Long,
        content: String,
        allowedAssistantId: String,
        currentConversationId: String?,
    ): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("记忆 #$id 不存在")
        guardMemoryAccess(old, allowedAssistantId, currentConversationId)
        val newMemory = old.copy(content = content)
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        conversationId: String? = null,
        sourceConversationId: String? = null,
    ): AssistantMemory {
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
            conversationId = conversationId,
            sourceConversationId = sourceConversationId,
        )
        memoryDAO.insertMemory(
            MemoryEntity(
                id = memory.id,
                assistantId = assistantId,
                content = memory.content,
                conversationId = memory.conversationId,
                sourceConversationId = memory.sourceConversationId,
            )
        )
        return memory
    }

    suspend fun deleteMemory(id: Long) {
        memoryDAO.deleteMemory(id)
    }

    /** v4.8.92: 带守卫的删除（模型侧用）—— 其它对话的单对话记忆不可被删除。 */
    suspend fun deleteMemoryChecked(
        id: Long,
        allowedAssistantId: String,
        currentConversationId: String?,
    ) {
        val entity = memoryDAO.getMemoryById(id) ?: error("记忆 #$id 不存在")
        guardMemoryAccess(entity, allowedAssistantId, currentConversationId)
        memoryDAO.deleteMemory(id)
    }

    private fun guardMemoryAccess(
        entity: MemoryEntity,
        allowedAssistantId: String,
        currentConversationId: String?,
    ) {
        if (entity.assistantId != allowedAssistantId) {
            error("该记忆不属于当前上下文，无法访问")
        }
        if (entity.conversationId != null && entity.conversationId != currentConversationId) {
            error("该记忆属于另一个对话，无法访问（单对话记忆只在它所属的对话里可用）")
        }
    }
}
