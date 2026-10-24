/* 【域 I·数据存储】 — 仓库层 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.repository

/* ───【原版对齐】MemoryRepository.kt | 基线 2.5.1 + v4.8.92 单对话记忆扩展
 * 5.0.0 并树：并入上游 3.0.0 记忆 2.0「文件式记忆库」API（memory_file 表 + 乐观锁写），
 * 与我方「记忆行式」API（MemoryEntity + 单对话记忆）双面共存：
 *  · 文件式 = 上游记忆库（MemoryConsolidator / MemoryFileVM / ChatRequestFactory 消费）
 *  · 行式   = 我方 2.5.x 记忆 + v4.8.92 单对话记忆（MemoryTools / AssistantMemoryPage 消费）
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryFileDAO
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryFile

sealed interface MemoryWriteResult {
    /** [file] 为 null 表示文件已被删除 */
    data class Success(val file: MemoryFile?) : MemoryWriteResult

    /** 版本对不上；[current] 是最新内容，为 null 表示文件不存在 */
    data class Conflict(val current: MemoryFile?) : MemoryWriteResult

    data class Rejected(val reason: String) : MemoryWriteResult
}

/**
 * 记忆仓库（双面）：
 * - 文件式记忆库（上游 3.0.0）：每个助手（或全局）一组 Markdown 文件，写操作带 ifVersion 乐观锁。
 * - 行式记忆（我方 2.5.x + v4.8.92 单对话记忆）：[AssistantMemory.conversationId] 非空 = 该记忆挂载到对应对话，
 *   只在它所属的对话里被注入；修改/删除走带守卫的 checked 方法；对话删除时级联销毁。
 */
class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val memoryFileDAO: MemoryFileDAO,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"
    }

    // ═══════════ 一、文件式记忆库（上游 3.0.0 记忆 2.0） ═══════════

    // 读取、校验版本、写入必须一起完成，否则两个写入方会互相覆盖
    private val writeLock = Mutex()

    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryFileDAO.getFilesOfAssistantFlow(assistantId).map { entities -> entities.map { it.toMemoryFile() } }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryFileDAO.getFilesOfAssistant(assistantId).map { it.toMemoryFile() }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryFileDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    /** 新建或整体覆盖 */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = update(assistantId, path, ifVersion, allowCreate = true) { Edit.Content(content) }

    /** 把唯一匹配的 [oldStr] 换成 [newStr]，[newStr] 为空即删除 */
    suspend fun replaceInFile(
        assistantId: String,
        path: String,
        oldStr: String,
        newStr: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        if (oldStr.isEmpty()) return@update Edit.Reject("old_str must not be empty.")
        val index = current.indexOf(oldStr)
        when {
            index < 0 -> Edit.Reject("old_str was not found in $path.")
            current.indexOf(oldStr, index + 1) >= 0 ->
                Edit.Reject("old_str matches more than one place in $path; include more surrounding text to make it unique.")
            else -> Edit.Content(current.replaceAt(index, oldStr.length, newStr))
        }
    }

    /** 在文件末尾追加一行 */
    suspend fun appendToFile(
        assistantId: String,
        path: String,
        line: String,
        ifVersion: String?,
    ): MemoryWriteResult = update(assistantId, path, ifVersion) { current ->
        val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        Edit.Content(current + separator + line.trimEnd('\n', '\r') + "\n")
    }

    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryFileDAO.getFile(assistantId, normalized)?.toMemoryFile()
        if (current == null || (ifVersion != null && ifVersion != current.version)) {
            return MemoryWriteResult.Conflict(current)
        }
        memoryFileDAO.deleteFile(assistantId, normalized)
        MemoryWriteResult.Success(null)
    }

    suspend fun deleteFilesOfAssistant(assistantId: String) {
        memoryFileDAO.deleteFilesOfAssistant(assistantId)
    }

    suspend fun copyFiles(fromAssistantId: String, toAssistantId: String) {
        val files = memoryFileDAO.getFilesOfAssistant(fromAssistantId)
        if (files.isEmpty()) return
        memoryFileDAO.upsertFiles(files.map { it.copy(assistantId = toAssistantId) })
    }

    private sealed interface Edit {
        data class Content(val content: String) : Edit
        data class Reject(val reason: String) : Edit
    }

    private suspend fun update(
        assistantId: String,
        path: String,
        ifVersion: String?,
        allowCreate: Boolean = false,
        edit: (current: String) -> Edit,
    ): MemoryWriteResult = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return invalidPath(path)
        val current = memoryFileDAO.getFile(assistantId, normalized)?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return MemoryWriteResult.Conflict(current)

        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return MemoryWriteResult.Rejected(result.reason)
            is Edit.Content -> result.content
        }
        if (content.toByteArray().size > MemoryFile.MAX_BYTES) {
            return MemoryWriteResult.Rejected(
                "$normalized would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
                    "Merge duplicates and drop outdated details instead of adding more."
            )
        }
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
        )
        memoryFileDAO.upsertFile(entity)
        MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )

    // ═══════════ 二、行式记忆（我方 2.5.x + v4.8.92 单对话记忆） ═══════════

    private fun MemoryEntity.toModel(): AssistantMemory = AssistantMemory(
        id = id,
        content = content,
        conversationId = conversationId,
        sourceConversationId = sourceConversationId,
        folderId = folderId,
        source = source,
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

    // v5.0.25: 助手名下全部项目包记忆（UI 分组）
    fun getFolderMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getFolderMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    // v5.0.24: 项目包记忆
    fun getMemoriesOfFolderFlow(folderId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfFolderFlow(folderId).map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfFolder(folderId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfFolder(folderId).map { it.toModel() }
    }

    suspend fun getMemoriesOfConversation(conversationId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfConversation(conversationId).map { it.toModel() }
    }

    /**
     * v5.0.67 ★用户定案的记忆二维语义（来源 × 存储）落地：
     * 做梦整理原本只写 `MemoryFile`（文件式，没有 scope 维度）→ 无法按
     * 「助手记忆/项目包记忆/单对话记忆」过滤。这里在每次整理结束后，把该 scope 下
     * 的 dream 条目**全量重建**（先删后按文件写），保证幂等且与记忆库一致。
     */
    /**
     * v5.0.82 ★隔离带重写（用户定案：范围=隔离带与沙箱）：
     * - [incremental]=true：[files] 是"本次做梦实际写过（touched）的记忆文件"，
     *   删除只精准移除这些标题对应的旧条目（不再全量删重建）—— 其它对话/其它包产生的
     *   记忆条目**绝不被复制或覆盖进本范围**；
     * - [incremental]=false：旧行为（全量删重建，仅遗留调用面兼容）。
     * - 三层归属**互斥**：助手级=两者 null；项目包=folderId 非空 conversationId null；
     *   对话=conversationId 非空 folderId null（不再出现"包+对话"双字段混合条目，
     *   同步前先把历史混合条目洗成对话层）。
     */
    suspend fun syncDreamMemories(
        assistantId: String,
        folderId: String?,
        conversationId: String?,
        files: List<MemoryFile>,
        incremental: Boolean = false,
    ) {
        // 历史混合层 dream 条目洗正（幂等）：归属以"最细层"为准
        memoryDAO.clearFolderOnMixedDreams(assistantId)
        if (incremental) {
            val touchedTitles = files.map { it.title }.filter { it.isNotBlank() }.toSet()
            if (touchedTitles.isEmpty()) return
            val scoped = memoryDAO.getDreamMemories(assistantId, folderId, conversationId)
            val deletable = scoped.filter { entryTitleOf(it.content) in touchedTitles }
            if (deletable.isNotEmpty()) {
                memoryDAO.deleteMemoriesByIds(deletable.map { it.id })
            }
        } else {
            memoryDAO.deleteDreamMemories(assistantId, folderId, conversationId)
        }
        files.forEach { file ->
            if (file.content.isBlank()) return@forEach
            addMemory(
                assistantId = assistantId,
                content = if (file.title.isBlank()) file.content else "【${file.title}】\n${file.content}",
                conversationId = conversationId,
                folderId = folderId,
                source = "dream",
            )
        }
    }

    /** dream 条目 content 的首行标题（addMemory 存储形态 = "【title】\ncontent"） */
    private fun entryTitleOf(content: String): String =
        if (content.startsWith("【")) content.substringBefore('】').removePrefix("【").trim() else ""

    /** v5.0.67：一批对话的对话级记忆（项目包互通：同包所有对话的记忆互相可见） */
    suspend fun getMemoriesOfConversations(conversationIds: List<String>): List<AssistantMemory> {
        if (conversationIds.isEmpty()) return emptyList()
        return memoryDAO.getMemoriesOfConversations(conversationIds).map { it.toModel() }
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
        val newMemory = old.copy(content = content, updatedAt = System.currentTimeMillis())
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
        val newMemory = old.copy(content = content, updatedAt = System.currentTimeMillis())
        memoryDAO.updateMemory(newMemory)
        return newMemory.toModel()
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        conversationId: String? = null,
        sourceConversationId: String? = null,
        folderId: String? = null,
        source: String = "manual",
    ): AssistantMemory {
        // v3.8.29: 记忆 ID 改为创建时间戳 YYMMDDHHMMSS (如 260820213001)，
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
            folderId = folderId,
            source = source,
        )
        memoryDAO.insertMemory(
            MemoryEntity(
                id = memory.id,
                assistantId = assistantId,
                content = memory.content,
                conversationId = memory.conversationId,
                sourceConversationId = memory.sourceConversationId,
                folderId = memory.folderId,
                source = memory.source,
                // v5.0.85（用户 B3）：创建即记录时间戳，便于判断新旧
                updatedAt = System.currentTimeMillis(),
            )
        )
        return memory
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val memories = memoryDAO.getMemoriesOfAssistant(fromAssistantId)
        if (memories.isEmpty()) return
        memoryDAO.insertMemories(
            memories.map { MemoryEntity(assistantId = toAssistantId, content = it.content) }
        )
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
        // v5.0.85（用户 B1/B3）：模型侧"删除"改为**软删（失效）** —— 旧口径被废弃后
        // 不再注入后续会话，但记录留痕（此前物理删除后无从追溯，用户也只能新增"作废"条目）。
        memoryDAO.invalidateMemory(id, System.currentTimeMillis())
    }

    @Suppress("UNUSED_PARAMETER")
    private fun guardMemoryAccess(
        entity: MemoryEntity,
        allowedAssistantId: String,
        currentConversationId: String?,
    ) {
        if (entity.assistantId != allowedAssistantId) {
            error("该记忆不属于当前上下文，无法访问")
        }
        // v5.0.85（用户 B1 亲见：改同主题旧记录被拒「该记忆属于另一个对话，无法访问」）：
        // **放开跨对话编辑/废弃** —— 同一助手内允许修正任意对话产生的旧口径；归属不变。
        // 仅保留"跨助手不可访问"这一条硬边界。
    }
}

/** 助手读写的记忆库：开启全局记忆后所有助手共用一份 */
val Assistant.memoryId: String
    get() = if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

private fun MemoryFileEntity.toMemoryFile() = MemoryFile(path = path, content = content, updatedAt = updatedAt)

private fun String.replaceAt(index: Int, length: Int, replacement: String): String =
    substring(0, index) + replacement + substring(index + length)
