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
import me.rerere.rikkahub.data.model.toAssistantMemory
import me.rerere.rikkahub.data.model.MemoryLayer

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

    // v5.3.7 ★统一记忆：过滤软删（invalidated_at 非空的条目不再展示/注入）
    fun getFilesFlow(assistantId: String): Flow<List<MemoryFile>> =
        memoryFileDAO.getFilesOfAssistantFlow(assistantId).map { entities ->
            entities.filter { it.invalidatedAt == null || it.invalidatedAt == 0L }.map { it.toMemoryFile() }
        }

    suspend fun getFiles(assistantId: String): List<MemoryFile> =
        memoryFileDAO.getFilesOfAssistant(assistantId)
            .filter { it.invalidatedAt == null || it.invalidatedAt == 0L }
            .map { it.toMemoryFile() }

    suspend fun getFile(assistantId: String, path: String): MemoryFile? {
        val normalized = MemoryFile.normalizePath(path) ?: return null
        return memoryFileDAO.getFile(assistantId, normalized)?.toMemoryFile()
    }

    /**
     * 新建或整体覆盖。
     *
     * v5.3.7 ★统一记忆：写入时同时确定这条记忆的两个维度 ——
     *  - [source]：`active`=主动写入 / `dream`=做梦整理（用户定案的两个小类）
     *  - [folderId] / [conversationId]：归属（三者互斥；都为空=助手级）
     * 已存在的文件**保留原归属**（除非显式传了非空值），避免模型追加内容时把
     * 一条项目包级记忆意外降级成助手级。
     */
    suspend fun writeFile(
        assistantId: String,
        path: String,
        content: String,
        ifVersion: String? = null,
        source: String = "active",
        folderId: String? = null,
        conversationId: String? = null,
    ): MemoryWriteResult = update(
        assistantId = assistantId,
        path = path,
        ifVersion = ifVersion,
        allowCreate = true,
        source = source,
        folderId = folderId,
        conversationId = conversationId,
    ) { Edit.Content(content) }

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

    // ═══════════ 一·B、统一记忆门面（v5.3.7）═══════════
    //
    // 用户定案的唯一模型：**记忆只有一个存储类别**，两个来源小类（主动 / 做梦）
    // × 三个归属 tag（助手级 / 项目包级 / 单对话级）。UI 与模型注入都只调这里，
    // 不再有任何"文件式 / 条目式"的第二套语义。

    /** 改归属（三 tag）。folderId 与 conversationId **互斥**：传了对话就清包，反之亦然。 */
    suspend fun updateScope(
        memoryId: String,
        path: String,
        folderId: String?,
        conversationId: String?,
    ): Boolean = writeLock.withLock {
        val normalized = MemoryFile.normalizePath(path) ?: return@withLock false
        val existing = memoryFileDAO.getFile(memoryId, normalized) ?: return@withLock false
        val fid = folderId?.takeIf { it.isNotBlank() }
        val cid = conversationId?.takeIf { it.isNotBlank() }
        val finalCid = cid
        val finalFid = if (finalCid != null) null else fid
        memoryFileDAO.updateScope(memoryId, normalized, finalFid, finalCid, System.currentTimeMillis())
        existing.path.isNotEmpty()
    }

    /** 改来源（主动 / 做梦）。 */
    suspend fun updateSource(memoryId: String, path: String, source: String): Boolean =
        writeLock.withLock {
            val normalized = MemoryFile.normalizePath(path) ?: return@withLock false
            memoryFileDAO.updateSource(memoryId, normalized, source, System.currentTimeMillis())
            true
        }

    /** 软删（失效标记）：不再注入、不再展示，但留痕。 */
    suspend fun invalidateFile(memoryId: String, path: String, invalidated: Boolean = true): Boolean =
        writeLock.withLock {
            val normalized = MemoryFile.normalizePath(path) ?: return@withLock false
            memoryFileDAO.invalidate(memoryId, normalized, if (invalidated) System.currentTimeMillis() else null)
            true
        }

    /**
     * **注入判据（唯一实现）** —— 一条记忆在给定对话里是否应进入模型上下文。
     *
     *  - 助手级：任何对话都可见
     *  - 项目包级：当前对话属于该项目包时可见
     *  - 单对话级：仅本对话，或同项目包内其它对话（包内互通）可见
     */
    fun visibleForInjection(
        memory: MemoryFile,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
    ): Boolean = when (memory.layer) {
        MemoryLayer.ASSISTANT -> true
        MemoryLayer.FOLDER -> folderId != null && memory.folderId == folderId
        MemoryLayer.CONVERSATION ->
            memory.conversationId == conversationId || memory.conversationId in siblingConversationIds
    }

    /** 注入用：一次查出该上下文可见的全部记忆（已经是唯一存储，无需跨表拼装）。 */
    suspend fun memoriesForInjection(
        memoryId: String,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
    ): List<MemoryFile> = getFiles(memoryId)
        .filter { visibleForInjection(it, folderId, conversationId, siblingConversationIds) }

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
        source: String = "active",
        folderId: String? = null,
        conversationId: String? = null,
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
        // v5.3.7：归属与来源 —— 已存在的条目保留原值（追加内容不改归属），新条目采用入参
        val existing = current
        val finalConversationId = existing?.conversationId ?: conversationId?.takeIf { it.isNotBlank() }
        val finalFolderId = if (finalConversationId != null) {
            null
        } else {
            existing?.folderId ?: folderId?.takeIf { it.isNotBlank() }
        }
        val finalSource = existing?.source ?: source
        val entity = MemoryFileEntity(
            assistantId = assistantId,
            path = normalized,
            content = content,
            updatedAt = System.currentTimeMillis(),
            source = finalSource,
            folderId = finalFolderId,
            conversationId = finalConversationId,
            invalidatedAt = existing?.invalidatedAt,
        )
        memoryFileDAO.upsertFile(entity)
        MemoryWriteResult.Success(entity.toMemoryFile())
    }

    private fun invalidPath(path: String) = MemoryWriteResult.Rejected(
        "Invalid path \"$path\". Use an absolute path ending in .md, e.g. /topics/food.md."
    )

    // ═══════════ 二、行式记忆（我方 2.5.x + v4.8.92 单对话记忆） ═══════════
    //
    // v5.3.9 ★★用户定案「记忆只有一个存储类别」的收口说明：
    // 本节**所有公开 API 都已是统一表（memory_file）的适配层**（v5.3.8），
    // 但下面仍有若干**已无调用点**的旧实现（直接操作 memoryDAO / memoryentity）：
    //   getMemoriesOfFolderFlow / getMemoriesOfFolder / getMemoriesOfConversation /
    //   getMemoriesOfConversations / syncDreamMemories / invalidateSupersededDreams /
    //   invalidateSupersededManual / updateScope(id: Long) / MemoryEntity.toModel
    // 它们**不再被任何代码调用**（v5.3.7 删了注入链与 UI 的调用、v5.3.9 删了做梦镜像调用）。
    // 保留它们只为降低一次性大删的编译风险；**新增代码一律不得调用它们** ——
    // 需要读写记忆，只用本节上半部分的统一门面（getFiles/writeFile/updateScope(path)/invalidateFile）。
    // 下一轮可以整段删除（删除时同步移除 `memoryDAO` 依赖与 `MemoryEntity`/`MemoryDAO` 文件）。

    private fun MemoryEntity.toModel(): AssistantMemory = AssistantMemory(
        id = id,
        content = content,
        conversationId = conversationId,
        sourceConversationId = sourceConversationId,
        folderId = folderId,
        source = source,
    )

    // ═══ v5.3.8 ★★统一记忆收口：以下旧「条目式」API 全部改为 **统一表（memory_file）的
    //     适配层**，签名不变（Operit 脚本运行时与少量 UI 仍在调用），但读写的不再是
    //     `memoryentity` —— 结构上不可能再出现第二张记忆表/第二条读写路径。
    //     AssistantMemory.id 由 path 稳定派生（path 才是唯一真实键）。

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        getFilesFlow(assistantId).map { list -> list.map { it.toAssistantMemory() } }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> =
        getFiles(assistantId).map { it.toAssistantMemory() }

    fun getGlobalMemoriesFlow(): Flow<List<AssistantMemory>> =
        getFilesFlow(GLOBAL_MEMORY_ID).map { list -> list.map { it.toAssistantMemory() } }

    suspend fun getGlobalMemories(): List<AssistantMemory> =
        getFiles(GLOBAL_MEMORY_ID).map { it.toAssistantMemory() }

    // ── v4.8.92: 单对话记忆 ──

    fun getMemoriesOfConversationFlow(conversationId: String): Flow<List<AssistantMemory>> =
        memoryFileDAO.getFilesOfConversationFlow(conversationId)
            .map { entities -> entities.map { it.toMemoryFile().toAssistantMemory() } }

    // v5.0.25: 助手名下全部项目包记忆（UI 分组）
    fun getFolderMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getFolderMemoriesOfAssistantFlow(assistantId)
            .map { entities -> entities.map { it.toModel() } }

    // v5.0.24: 项目包记忆
    fun getMemoriesOfFolderFlow(folderId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfFolderFlow(folderId).map { entities -> entities.map { it.toModel() } }

    suspend fun getMemoriesOfFolder(folderId: String, assistantId: String? = null): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfFolder(folderId, assistantId).map { it.toModel() }
    }

    suspend fun getMemoriesOfConversation(conversationId: String, assistantId: String? = null): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfConversation(conversationId, assistantId).map { it.toModel() }
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
            // v5.1.13 ★跨 scope 重复治理（用户报"整个记忆系统存在大量重复"）：
            // 同一主题在对话层/包层/助手层各生成过一次，而旧删除只精确匹配
            // (assistantId, folderId, conversationId) 三元组 → 换 scope 后旧的永不删，
            // 每层各留一份 = 同一主题堆成 N 条。这里按**主题**清掉其它 scope 的旧副本。
            val currentTitles = files.map { it.title.trim() }.filter { it.isNotBlank() }.toSet()
            if (currentTitles.isNotEmpty()) {
                val stale = memoryDAO.getActiveDreamMemories(assistantId).filter { entity ->
                    entryTitleOf(entity.content) in currentTitles &&
                        (entity.folderId != folderId || entity.conversationId != conversationId)
                }
                if (stale.isNotEmpty()) {
                    memoryDAO.deleteMemoriesByIds(stale.map { it.id })
                }
            }
        }
        // v5.0.87：同主题已有主动条目（非做梦）时，不再生成做梦副本 ——
        // 否则模型会同时收到"修改后的主动条目 + 修改前的做梦条目"（用户亲见的现象）。
        val manualTopics = runCatching {
            memoryDAO.getActiveNonDreamMemories(assistantId).map { normalizeTopic(memoryTopicKey(it.content)) }.toSet()
        }.getOrDefault(emptySet())
        files.forEach { file ->
            if (file.content.isBlank()) return@forEach
            val fileTopic = normalizeTopic(
                memoryTopicKey(if (file.title.isBlank()) file.content else "【${file.title}】"),
            )
            if (fileTopic.isNotBlank() && fileTopic in manualTopics) return@forEach
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
    /**
     * v5.0.87（用户亲见：修改一段记忆后，模型那里同时出现修改前与修改后的版本）：
     * 同主题**旧做梦条目**在主动条目被写入/编辑后自动失效 —— 修改即取代，旧口径不再注入。
     * 主题键 = 标题（【title】）或首行，空白/标点归一化后比对。
     */
    private suspend fun invalidateSupersededDreams(assistantId: String, content: String) {
        val topic = normalizeTopic(memoryTopicKey(content))
        if (topic.isBlank()) return
        runCatching {
            memoryDAO.getActiveDreamMemories(assistantId)
                .filter { normalizeTopic(memoryTopicKey(it.content)) == topic }
                .forEach { memoryDAO.invalidateMemory(it.id, System.currentTimeMillis()) }
        }
    }

    /** v5.0.87：同助手 + 同归属层级 + 同主题的旧主动条目 → 失效（新写入即取代，用户定案"修改后旧记录删掉"） */
    private suspend fun invalidateSupersededManual(
        assistantId: String,
        created: AssistantMemory,
        content: String,
    ) {
        val topic = normalizeTopic(memoryTopicKey(content))
        if (topic.isBlank()) return
        runCatching {
            memoryDAO.getActiveNonDreamMemories(assistantId)
                .filter { it.id != created.id }
                .filter { it.folderId == created.folderId && it.conversationId == created.conversationId }
                .filter { normalizeTopic(memoryTopicKey(it.content)) == topic }
                .forEach { memoryDAO.invalidateMemory(it.id, System.currentTimeMillis()) }
        }
    }

    private fun memoryTopicKey(content: String): String {
        val t = entryTitleOf(content).ifBlank { content.lineSequence().firstOrNull().orEmpty() }
        return t.take(80)
    }

    private fun normalizeTopic(raw: String): String =
        raw.filterNot { it.isWhitespace() || it in "【】[]（）()：:，,。.、" }
            .lowercase()
            .take(40)

    private fun entryTitleOf(content: String): String =
        if (content.startsWith("【")) content.substringBefore('】').removePrefix("【").trim() else ""

    /** v5.0.67：一批对话的对话级记忆（项目包互通：同包所有对话的记忆互相可见） */
    suspend fun getMemoriesOfConversations(conversationIds: List<String>, assistantId: String? = null): List<AssistantMemory> {
        if (conversationIds.isEmpty()) return emptyList()
        return memoryDAO.getMemoriesOfConversations(conversationIds, assistantId).map { it.toModel() }
    }

    /** 某助手（或全局桶）名下的全部单对话记忆（管理页「单对话记忆」视图用） */
    fun getConversationMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        getFilesFlow(assistantId).map { list ->
            list.filter { it.layer == MemoryLayer.CONVERSATION }.map { it.toAssistantMemory() }
        }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
    }

    /** v4.8.92: 对话删除时的级联清理（单对话记忆随对话销毁） */
    suspend fun deleteMemoriesOfConversation(conversationId: String) {
        // v5.3.8：对话删除时级联清理统一表里的对话级记忆（与条目式时代语义一致）
        memoryFileDAO.deleteFilesOfConversation(conversationId)
    }

    /**
     * v5.1.13 ★只改归属（folderId / conversationId），不动内容。
     * 规则：folderId 与 conversationId **互斥** —— 挂到项目包必须清空对话归属，
     * 挂到单对话必须清空包归属，否则该条目不会被任何一条分层查询命中 = 用户视角消失。
     */
    suspend fun updateScope(id: Long, folderId: String?, conversationId: String?): AssistantMemory {
        val old = memoryDAO.getMemoryById(id) ?: error("Memory record #$id not found")
        val at = System.currentTimeMillis()
        val normalizedFolder = if (conversationId != null) null else folderId
        memoryDAO.updateScope(id, normalizedFolder, conversationId, at)
        return old.copy(
            folderId = normalizedFolder,
            conversationId = conversationId,
            updatedAt = at,
        ).toModel()
    }

    // ═══════════ 二·B、旧条目式写操作 → 统一表适配（v5.3.8）═══════════
    //
    // 这些方法历史上读写 `memoryentity`（第二张表 = 记忆分叉的根源）。现在它们全部
    // 落到 `memory_file`：**id 只是 path 的派生标识**（path.hashCode），真实键是 path。
    // Operit 脚本运行时（memory.create/update/delete/move/link）零改动即自动统一。

    /** 按 [AssistantMemory.id]（= path.hashCode）在指定记忆库里找出真实 path。 */
    private suspend fun resolvePathById(assistantId: String, id: Long): String? =
        memoryFileDAO.getFilesOfAssistant(assistantId)
            .firstOrNull { it.path.hashCode().toLong() == id }
            ?.path

    suspend fun updateContent(id: Long, content: String): AssistantMemory {
        val path = resolvePathById(GLOBAL_MEMORY_ID, id)
            ?: error("Memory record #$id not found")
        writeFile(GLOBAL_MEMORY_ID, path, content)
        return getFile(GLOBAL_MEMORY_ID, path)?.toAssistantMemory()
            ?: error("Memory record #$id could not be re-read")
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
        // v5.0.85：跨对话编辑已放开，仅保留「跨助手不可访问」硬边界（由 resolvePathById
        // 的 assistantId 参数天然保证 —— 在别的助手的库里根本找不到这个 id）。
        val path = resolvePathById(allowedAssistantId, id)
            ?: error("记忆 #$id 不存在")
        writeFile(allowedAssistantId, path, content)
        return getFile(allowedAssistantId, path)?.toAssistantMemory()
            ?: error("记忆 #$id 读取失败")
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        conversationId: String? = null,
        sourceConversationId: String? = null,
        folderId: String? = null,
        source: String = "manual",
    ): AssistantMemory {
        // v5.1.14 ★统一写入判据：归属**互斥** —— 挂了单对话就不再是项目包记忆
        val normalizedConversationId = conversationId
        val normalizedFolderId = if (normalizedConversationId != null) null else folderId
        // v5.3.8：路径由正文首行派生（可读），同名冲突加时间后缀保证唯一
        var path = me.rerere.rikkahub.data.model.pathFromContent(content)
        if (memoryFileDAO.getFile(assistantId, path) != null) {
            path = path.removeSuffix(".md") + "-" + System.currentTimeMillis().toString().takeLast(6) + ".md"
        }
        writeFile(
            assistantId = assistantId,
            path = path,
            content = content,
            ifVersion = NEW_FILE_VERSION,
            source = if (source == "dream") "dream" else "active",
            folderId = normalizedFolderId,
            conversationId = normalizedConversationId,
        )
        return getFile(assistantId, path)?.toAssistantMemory()
            ?: error("记忆写入失败: $path")
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val files = getFiles(fromAssistantId)
        if (files.isEmpty()) return
        files.forEach { file ->
            writeFile(
                assistantId = toAssistantId,
                path = file.path,
                content = file.content,
                ifVersion = NEW_FILE_VERSION,
                source = file.source,
                folderId = file.folderId,
                conversationId = null, // 跨助手复制：对话归属不能带过去（对话属于源助手）
            )
        }
    }

    suspend fun deleteMemory(id: Long) {
        val path = resolvePathById(GLOBAL_MEMORY_ID, id) ?: return
        memoryFileDAO.deleteFile(GLOBAL_MEMORY_ID, path)
    }

    /** v4.8.92: 带守卫的删除（模型侧用）—— 其它对话的单对话记忆不可被删除。 */
    suspend fun deleteMemoryChecked(
        id: Long,
        allowedAssistantId: String,
        currentConversationId: String?,
    ) {
        // v5.0.85：模型侧「删除」= 软删（失效留痕，不再注入后续会话）
        val path = resolvePathById(allowedAssistantId, id) ?: error("记忆 #$id 不存在")
        memoryFileDAO.invalidate(allowedAssistantId, path, System.currentTimeMillis())
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

private fun MemoryFileEntity.toMemoryFile() = MemoryFile(
    path = path,
    content = content,
    updatedAt = updatedAt,
    source = source,
    folderId = folderId,
    conversationId = conversationId,
    invalidatedAt = invalidatedAt,
)

private fun String.replaceAt(index: Int, length: Int, replacement: String): String =
    substring(0, index) + replacement + substring(index + length)
