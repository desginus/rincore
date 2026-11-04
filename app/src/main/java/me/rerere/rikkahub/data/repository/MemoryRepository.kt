/* 【域 I·数据存储】 — 仓库层 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.repository

/* ───【原版对齐】MemoryRepository.kt | 基线 2.5.1 + v4.8.92 单对话记忆扩展
 * 5.0.0 并树：并入上游 3.0.0 记忆 2.0「文件式记忆库」API（memory_file 表 + 乐观锁写），
 * 与我方「记忆行式」API（MemoryEntity + 单对话记忆）双面共存：
 *  · 文件式 = 上游记忆库（MemoryConsolidator / MemoryFileVM / ChatRequestFactory 消费）
 *  · 行式   = 我方 2.5.x 记忆 + v4.8.92 单对话记忆（MemoryTools / AssistantMemoryPage 消费）
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.MemoryFileDAO
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
/**
 * v5.3.13 ★★**唯一存储的最终形态**。
 *
 * 历史：曾同时依赖 `MemoryDAO`（旧表 `memoryentity`）与 `MemoryFileDAO`（`memory_file`）——
 * 这就是「两套方案」的代码来源：同一件事有两个入口、两张表、两套删除语义。
 * 现在构造函数只有一个 DAO；`MemoryDAO` 仍作为 Room DAO 存在（仅因为
 * `memoryentity` 表在 v38 已建、删实体需要再走一次迁移），但**本类不再引用它**，
 * 全库也没有任何其他代码读写旧表 —— 结构上只剩一套。
 */
class MemoryRepository(
    private val memoryFileDAO: MemoryFileDAO,
) {
    companion object {
        const val GLOBAL_MEMORY_ID = "__global__"
        const val NEW_FILE_VERSION = "new"

        /** v5.3.14：挂载层级（与 Assistant.manualMemoryMount / dreamMemoryMount 同口径） */
        const val MOUNT_ASSISTANT_ONLY = 1
        const val MOUNT_FOLDER = 2
        const val MOUNT_CURRENT_CONVERSATION = 3
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

    /**
     * v5.3.19 ★**多形态读取**。
     *
     * 旧实现只按 `normalizePath()` 后的精确 path 查（一定带前导 `/`），而 DB 里可能存着
     * v5.3.7 迁移生成的**无前导斜杠** path。后果是「这条记忆明明在列表里，但按 path 查不到」，
     * 进而让 `update()` 把它当成新文件写入 → 同一主题变成两条（记忆重复的真实来源之一）。
     */
    suspend fun getFile(assistantId: String, path: String): MemoryFile? =
        getFileAny(assistantId, path)?.takeIf { it.invalidatedAt == null || it.invalidatedAt == 0L }

    /**
     * v5.3.45 ★★**判存专用**（不过滤软删）。
     *
     * `getFile` 是公开 API，语义必须等于「读一条**可见**记忆」—— 旧实现直接返回未过滤的结果，
     * 任何调用方都会在用户删除后读到一条"不存在于任何列表"的记忆（本轮用真实源码实跑时
     * 就是靠这条断言发现的：`删除后不可读` 失败）。
     *
     * 只有「写入前判断这个 path 是否已存在」需要看见已失效条目 —— 因为要把
     * `invalidatedAt` 读出来判断是否复活，以及把老形态 path 归一化迁移。
     * 这个需求收敛到本方法，公开面不再暴露它。
     */
    private suspend fun getFileAny(assistantId: String, path: String): MemoryFile? {
        val candidates = pathCandidates(path)
        if (candidates.isEmpty()) return null
        return memoryFileDAO.getFilesAny(assistantId, candidates).firstOrNull()?.toMemoryFile()
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

    /**
     * v5.3.19 ★★**删除 = 软删**（全链路唯一语义）。
     *
     * 旧实现对模型侧 `memory_delete` 走 `DELETE FROM`（物理删除），而 UI 删除走
     * `invalidateFile`（软删）—— 同一个动作两套语义：模型删掉的记忆**无法恢复、无法追溯**，
     * 用户删掉的反而是软删。更糟的是它按 normalizePath 单形态匹配，迁移来的老 path 删不掉。
     *
     * 现在：软删（写 `invalidated_at`）+ 多形态匹配。「真物理删除」只保留在
     * [purgeFile] 与级联清理（删助手 / 删对话）里使用。
     */
    suspend fun deleteFile(
        assistantId: String,
        path: String,
        ifVersion: String? = null,
    ): MemoryWriteResult = writeLock.withLock {
        // 判存用 any 版：已删除的条目也要看见，才能让「重复删除」给出确定结果而不是误报不存在
        val current = getFileAny(assistantId, path) ?: return MemoryWriteResult.Conflict(null)
        if (ifVersion != null && ifVersion != current.version) {
            return MemoryWriteResult.Conflict(current)
        }
        val candidates = pathCandidates(path)
        val rows = memoryFileDAO.invalidateAny(assistantId, candidates, System.currentTimeMillis())
        if (rows == 0) {
            Log.w("MemoryRepository", "deleteFile: no row matched id=$assistantId path=$path")
            return MemoryWriteResult.Conflict(null)
        }
        MemoryWriteResult.Success(null)
    }

    /** 物理删除（仅用于「用户明确要彻底清掉」或级联清理；正常删除一律用 [deleteFile]）。 */
    suspend fun purgeFile(assistantId: String, path: String) = writeLock.withLock {
        val candidates = pathCandidates(path)
        if (candidates.isNotEmpty()) memoryFileDAO.deleteFileAny(assistantId, candidates)
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
        // v5.3.19 ★两个修复：
        //  (a) 走多形态候选（旧版 `normalizePath` 单形态，对迁移来的老 path 恒 0 行命中）；
        //  (b) 返回**真实影响行数**（旧版返回 `existing.path.isNotEmpty()`，恒 true =
        //      无论改没改成功都说成功，用户看到"归属改完退出重进又回去了")。
        val candidates = pathCandidates(path)
        if (candidates.isEmpty()) return@withLock false
        val fid = folderId?.takeIf { it.isNotBlank() }
        val cid = conversationId?.takeIf { it.isNotBlank() }
        val finalCid = cid
        val finalFid = if (finalCid != null) null else fid
        val rows = memoryFileDAO.updateScopeAny(memoryId, candidates, finalFid, finalCid, System.currentTimeMillis())
        if (rows == 0) {
            Log.w("MemoryRepository", "updateScope: no row matched id=$memoryId path=$path candidates=$candidates")
        }
        rows > 0
    }

    /** 改来源（主动 / 做梦）。 */
    suspend fun updateSource(memoryId: String, path: String, source: String): Boolean =
        writeLock.withLock {
            // v5.3.19：多形态 + 真实影响行数（旧版恒 true，改不动也说成功）
            val candidates = pathCandidates(path)
            if (candidates.isEmpty()) return@withLock false
            val rows = memoryFileDAO.updateSourceAny(memoryId, candidates, source, System.currentTimeMillis())
            if (rows == 0) {
                Log.w("MemoryRepository", "updateSource: no row matched id=$memoryId path=$path")
            }
            rows > 0
        }

    /** 软删（失效标记）：不再注入、不再展示，但留痕。 */
    /**
     * v5.3.12 ★★删除 = **软删（失效标记）+ 读侧过滤**，两者必须成对。
     *
     * 返回 `true` 表示**真的有这一条并且状态被改了**（DAO 影响行数 > 0）。
     * 调用方必须据此给用户反馈 —— 旧实现无条件返回 true，用户点了删除、
     * 实际一条都没命中（path 不匹配 / memoryId 用错桶）时也不会有任何提示。
     */
    /**
     * v5.3.14 ★★历史 path 形态候选（「以前的记忆删不掉」的根治）。
     *
     * v5.3.7 的迁移用 `memoryPathOf()` 生成 path，**不带前导 "/"**（`"标题.md"`），
     * 而 `normalizePath()` 一律补成 `"/标题.md"` —— 两者永不相等，于是所有按
     * 精确 path 匹配的 UPDATE / DELETE 对迁移过来的老记忆**全部 0 行命中**，
     * 且旧实现静默返回，用户看到的正是「老记忆看得到、删不掉」。
     *
     * 现在统一走这个候选集合：原样 / 去空白 / 补斜杠 / 去斜杠 / 规范化结果，
     * 无论 DB 里存的是哪种写法都能命中。**不改 DB 数据**（避免动 path 带来的连锁）。
     */
    private fun pathCandidates(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        val normalized = MemoryFile.normalizePath(trimmed)
        return listOfNotNull(
            raw,
            trimmed,
            withSlash,
            withSlash.removePrefix("/"),
            normalized,
            normalized?.removePrefix("/"),
        ).filter { it.isNotBlank() }.distinct()
    }

    suspend fun invalidateFile(memoryId: String, path: String, invalidated: Boolean = true): Boolean =
        writeLock.withLock {
            val candidates = pathCandidates(path)
            if (candidates.isEmpty()) return@withLock false
            val rows = memoryFileDAO.invalidateAny(
                memoryId,
                candidates,
                if (invalidated) System.currentTimeMillis() else null,
            )
            if (rows == 0) {
                Log.w("MemoryRepository", "invalidateFile: no row matched id=$memoryId path=$path candidates=$candidates")
            }
            rows > 0
        }

    /**
     * **注入判据（唯一实现）** —— 一条记忆在给定对话里是否应进入模型上下文。
     *
     *  - 助手级：任何对话都可见
     *  - 项目包级：当前对话属于该项目包时可见
     *  - 单对话级：仅本对话，或同项目包内其它对话（包内互通）可见
     */
    /**
     * v5.3.14 ★★挂载层级（用户报「挂载逻辑好像有问题」的真根因）。
     *
     * `Assistant.manualMemoryMount` / `dreamMemoryMount` 从 v5.0.86 起就存在于 UI 与数据类，
     * 但 **v5.3.7 重写注入链时丢了对它们的消费** —— 用户在设置里选「仅助手级 / 仅当前对话」
     * 对模型收到的内容**毫无影响**（纯 UI 摆设）。这里把判据接回注入链。
     *
     * 档位：1 = 仅助手级（`layer == ASSISTANT`）
     *      2 = 项目包内（= 既有全量行为，默认值，保持向后兼容）
     *      3 = 仅当前对话（助手级 + **本对话**的单对话记忆；不含同包其它对话）
     */
    fun visibleForInjection(
        memory: MemoryFile,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
        mountLevel: Int = MOUNT_FOLDER,
    ): Boolean {
        // 挂载层级先于归属判据：1/3 档会掐掉「层」上的可见性
        when (mountLevel) {
            MOUNT_ASSISTANT_ONLY -> if (memory.layer != MemoryLayer.ASSISTANT) return false
            MOUNT_CURRENT_CONVERSATION -> {
                val isAssistantLevel = memory.layer == MemoryLayer.ASSISTANT
                val isThisConversation = memory.layer == MemoryLayer.CONVERSATION &&
                    memory.conversationId != null && memory.conversationId == conversationId
                if (!isAssistantLevel && !isThisConversation) return false
            }
            // MOUNT_FOLDER（默认）不做额外裁剪，走下面的归属判据
        }
        return when (memory.layer) {
            MemoryLayer.ASSISTANT -> true
            MemoryLayer.FOLDER -> folderId != null && memory.folderId == folderId
            MemoryLayer.CONVERSATION ->
                memory.conversationId == conversationId || memory.conversationId in siblingConversationIds
        }
    }

    /**
     * 注入用：一次查出该上下文可见的全部记忆（已经是唯一存储，无需跨表拼装）。
     *
     * v5.3.14：按**来源**分别应用挂载层级 —— 主动写入用 [manualMount]，
     * 做梦整理用 [dreamMount]（这正是设置页那两组 Chip 的语义）。
     */
    suspend fun memoriesForInjection(
        memoryId: String,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
        manualMount: Int = MOUNT_FOLDER,
        dreamMount: Int = MOUNT_FOLDER,
    ): List<MemoryFile> = getFiles(memoryId)
        .filter { file ->
            val mount = if (file.isDream) dreamMount else manualMount
            visibleForInjection(file, folderId, conversationId, siblingConversationIds, mount)
        }

    /**
     * v5.3.19 ★★**工具层的越权读取防线**。
     *
     * 旧实现：`memory_read` / `memory_list` 直接用 `getFile(assistantId, path)` /
     * `getFiles(assistantId)` —— **只按记忆库过滤，不按归属过滤**。于是模型在
     * 项目包 A 的对话里可以 `memory_read` 到项目包 B 的记忆、甚至**别的对话的单对话级记忆**
     * （注入链明明已经按归属裁剪过，工具层却能绕过）。
     *
     * 现在工具层统一走这里：先按注入判据（含 mountLevel）算出当前上下文可见集合，再按 path 匹配。
     */
    suspend fun readVisibleFile(
        memoryId: String,
        path: String,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
        manualMount: Int = MOUNT_FOLDER,
        dreamMount: Int = MOUNT_FOLDER,
    ): MemoryFile? {
        val candidates = pathCandidates(path).toSet()
        if (candidates.isEmpty()) return null
        return memoriesForInjection(
            memoryId = memoryId,
            folderId = folderId,
            conversationId = conversationId,
            siblingConversationIds = siblingConversationIds,
            manualMount = manualMount,
            dreamMount = dreamMount,
        ).firstOrNull { it.path in candidates }
    }

    /**
     * v5.3.20 ★**批量可见读取**（性能修复）。
     *
     * 旧工具层写法是 `paths.associateWith { readVisibleFile(it) }` —— 而 `readVisibleFile`
     * 内部每次都调 `memoriesForInjection`（= 一次 `getFiles` 全表 + 逐条判据）。
     * `MAX_READ_PATHS = 20` 时就是 **一次 memory_read 触发 20 次全表扫描**；
     * 记忆库上千条时表现为「模型读几个文件就卡一下」，且并发工具会叠加。
     *
     * 现在一次取可见集合、建 path 索引，再对每个入参 path 走多形态候选匹配。
     * 返回的 key 是**调用方传入的原样 path**（可能不带前导 `/`），value 是命中的真实文件。
     */
    suspend fun readVisibleFiles(
        memoryId: String,
        paths: List<String>,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
        manualMount: Int = MOUNT_FOLDER,
        dreamMount: Int = MOUNT_FOLDER,
    ): Map<String, MemoryFile> {
        if (paths.isEmpty()) return emptyMap()
        val byPath = memoriesForInjection(
            memoryId = memoryId,
            folderId = folderId,
            conversationId = conversationId,
            siblingConversationIds = siblingConversationIds,
            manualMount = manualMount,
            dreamMount = dreamMount,
        ).associateBy { it.path }
        val result = LinkedHashMap<String, MemoryFile>(paths.size)
        for (raw in paths) {
            val hit = pathCandidates(raw).firstNotNullOfOrNull { byPath[it] } ?: continue
            result[raw] = hit
        }
        return result
    }

    /** v5.3.19：工具层 `memory_list` 用 —— 当前上下文可见的全部记忆。 */
    suspend fun listVisibleFiles(
        memoryId: String,
        folderId: String?,
        conversationId: String?,
        siblingConversationIds: Collection<String> = emptyList(),
        manualMount: Int = MOUNT_FOLDER,
        dreamMount: Int = MOUNT_FOLDER,
    ): List<MemoryFile> = memoriesForInjection(
        memoryId = memoryId,
        folderId = folderId,
        conversationId = conversationId,
        siblingConversationIds = siblingConversationIds,
        manualMount = manualMount,
        dreamMount = dreamMount,
    )

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
        // v5.3.19 ★多形态判存：DB 里可能存着迁移生成的无前导斜杠 path。
        // 旧实现只按 normalized 查 → 老记忆被当成"不存在" → 编辑变成新建 → 同一主题两条。
        val candidates = pathCandidates(path)
        val currentEntity = memoryFileDAO.getFilesAny(assistantId, candidates).firstOrNull()
        val current = currentEntity?.toMemoryFile()
        val versionMatches = when (ifVersion) {
            null -> current != null || allowCreate
            NEW_FILE_VERSION -> current == null && allowCreate
            else -> current?.version == ifVersion
        }
        if (!versionMatches) return MemoryWriteResult.Conflict(current)

        // ═══ v5.3.45 ★★已删除的条目：**不复活，但必须明确报错** ═══
        //
        // 语义以 v5.3.12 为准（既有测试 `deleting a dream memory ... later writes do not
        // resurrect it` 锁死）：**用户明确删掉的内容，模型/做梦整理不得擅自写回来** ——
        // 那条 ifVersion 断言守的是用户意图，不是实现细节。
        //
        // 但旧实现是**静默**保留 `invalidatedAt`：内容写进去了、`writeFile` 返回 Success、
        // 记忆页却看不到 —— 用户视角「模型说记住了，我却找不到」，模型视角「写成功了」。
        // 两边都以为成功，实际什么都没发生（v5.3.4/v5.3.5 报的现象之一）。
        //
        // 现在：命中已删除条目 → **明确拒绝**，让模型知道该换 path 还是请用户确认恢复。
        // 恢复仍走显式入口（`invalidateFile(..., invalidated = false)`）。
        val wasInvalidated = currentEntity?.invalidatedAt?.let { it != 0L } == true
        if (wasInvalidated) {
            return MemoryWriteResult.Rejected(
                "$normalized was deleted by the user; it will not come back automatically. " +
                    "Write it under a different path, or ask the user to restore it."
            )
        }
        val content = when (val result = edit(current?.content.orEmpty())) {
            is Edit.Reject -> return MemoryWriteResult.Rejected(result.reason)
            is Edit.Content -> result.content
        }
        // v5.3.22 ★**补回原版 3.1.1 的判据**（我们移植时写成了无条件拦截）：
        //   `size > MAX_BYTES && size > current?.content?.size ?: 0`
        // 原版注释写得很清楚：「只拦会让文件变大的超限写入：迁移来的旧记忆可能本来就超限，
        // 得允许一点点删小」。我们无条件拦 = **超限的历史记忆连删内容都被拒**，
        // 用户视角就是「这条记忆怎么改都改不动」，而唯一的解法恰恰是让它变小。
        val newSize = content.toByteArray().size
        val oldSize = current?.content?.toByteArray()?.size ?: 0
        if (newSize > MemoryFile.MAX_BYTES && newSize > oldSize) {
            return MemoryWriteResult.Rejected(
                "$normalized would exceed the ${MemoryFile.MAX_BYTES / 1024}KB limit. " +
                    "Merge duplicates and drop outdated details instead of adding more."
            )
        }
        // ═══ v5.3.19 ★★跨范围覆盖保护（越权写的唯一入口就在这里）═══
        //
        // 主键是 `(assistant_id, path)` —— **同一助手内不同归属不能有同名 path**。
        // 于是「项目包 A 里写 /topics/x.md」如果助手级已有同名文件，Upsert 会直接把内容
        // 覆盖到**助手级那条**上（归属保持不变）→ 包级写入串成"全员可见"。
        // 反过来「助手级上下文写 /topics/x.md」也会覆盖包级的私有记忆。
        // 这里显式拒绝跨范围覆盖，宁可让模型换一个路径（错误信息会告诉它）。
        val existing = current
        if (existing != null) {
            val inConv = conversationId?.takeIf { it.isNotBlank() }
            val inFolder = if (inConv != null) null else folderId?.takeIf { it.isNotBlank() }
            val inScope = inConv ?: inFolder
            val exScope = existing.conversationId ?: existing.folderId
            val crossScope = if (inScope == null) exScope != null else exScope != inScope
            if (crossScope) {
                val where = when {
                    exScope == null -> "助手级"
                    existing.conversationId != null -> "某个单对话"
                    else -> "某个项目包"
                }
                return MemoryWriteResult.Rejected(
                    "$normalized already exists in a different scope（$where）。" +
                        "Pick a different path instead of overwriting another scope's memory."
                )
            }
        }
        // v5.3.7：归属与来源 —— 已存在的条目保留原值（追加内容不改归属），新条目采用入参
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
            // allowCreate=true（整体写入）→ 复活；其余情况保留原标记（已被上面的守卫拦住）
            invalidatedAt = if (allowCreate) null else existing?.invalidatedAt,
        )
        // v5.3.19 ★老 path 归一化迁移：DB 里那条如果是不带前导斜杠的旧形态，
        // 直接 upsert 会**新增一行**（主键含 path）→ 同一主题两条。这里先把旧形态行删掉。
        if (currentEntity != null && currentEntity.path != normalized) {
            memoryFileDAO.deleteFileAny(assistantId, listOf(currentEntity.path))
        }
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

    // v5.3.13：`MemoryEntity.toModel()` 已删除 —— 旧表实体不再参与任何数据转换，
    // 统一表的 `MemoryFile.toAssistantMemory()` 是唯一 DTO 适配点。


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
    // v5.3.13：改为统一表派生 —— 与 `memoriesForInjection` 同一数据源、同一归属判据。
    fun getFolderMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        getFilesFlow(assistantId).map { list ->
            list.filter { it.layer == MemoryLayer.FOLDER }.map { it.toAssistantMemory() }
        }

    // v5.3.13 ★彻底统一：以下三个查询（getMemoriesOfFolderFlow / getMemoriesOfFolder /
    // getMemoriesOfConversation）**全库 0 调用点**，且直接读旧表 `memoryentity` ——
    // 已删除。需要按归属查询时用 `getFiles(...)` 再按 `MemoryFile.layer` 过滤。

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
    // v5.3.13 ★★彻底统一（用户问「为什么会有两套方案」）：`syncDreamMemories` 及
    // `invalidateSupersededDreams` / `invalidateSupersededManual` 已**整段删除**。
    // 它们是 v5.0.67 时代「文件式 + 条目式双写」的产物：把 dream 产物镜像进第二张表、
    // 再靠主题相似度去删旧副本。v5.3.7 起 `memory_file` 自身就带 source/归属，
    // 「同主题旧副本」由 `writeFile` 的 path 唯一性与软删自然保证，不需要也不允许镜像。
    // 需要「编辑即取代」时，用统一门面的 `invalidateFile`（软删）+ `writeFile`（同 path 覆盖）。

    /** 某助手（或全局桶）名下的全部单对话记忆（管理页「单对话记忆」视图用） */
    fun getConversationMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        getFilesFlow(assistantId).map { list ->
            list.filter { it.layer == MemoryLayer.CONVERSATION }.map { it.toAssistantMemory() }
        }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        // v5.3.13：统一表（含软删条目一并物理清掉 —— 助手删除时无需留痕）
        memoryFileDAO.deleteFilesOfAssistant(assistantId)
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
    // v5.3.13：旧的 `updateScope(id: Long, ...)`（按 memoryentity 主键改归属）已删除；
    // 归属修改统一走上面的 `updateScope(memoryId, path, folderId, conversationId)`。


    // ═══════════ 二·B、旧条目式写操作 → 统一表适配（v5.3.8）═══════════
    //
    // 这些方法历史上读写 `memoryentity`（第二张表 = 记忆分叉的根源）。现在它们全部
    // 落到 `memory_file`：**id 只是 path 的派生标识**（path.hashCode），真实键是 path。
    // Operit 脚本运行时（memory.create/update/delete/move/link）零改动即自动统一。

    /** 按 [AssistantMemory.id]（= path.hashCode）在指定记忆库里找出真实 path。 */
    private suspend fun resolvePathById(assistantId: String, id: Long): String? {
        // v5.3.19 ★`id` 是 `path.hashCode()` 派生的，理论上可能**冲突**（不同 path 同 hash）。
        // 冲突时取哪条都是错的，所以这里显式记录，避免"改了一条不相干的记忆"无从排查。
        val matches = memoryFileDAO.getFilesOfAssistant(assistantId)
            .filter { it.path.hashCode().toLong() == id }
        if (matches.size > 1) {
            Log.w("MemoryRepository", "resolvePathById: hash collision for id=$id -> ${matches.map { it.path }}")
        }
        return matches.firstOrNull()?.path
    }

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
        // v5.3.19 ★★★三处修复（旧实现会「点了新建什么都没发生」）：
        //  (a) `pathFromContent` 产出的 path **不带前导斜杠**，而 `normalizePath` 一律补 `/` →
        //      旧代码先用不带斜杠的 path 查重（永远查不到），再以 NEW_FILE_VERSION 写入
        //      （内部按带斜杠的 path 判存 → 命中旧条目）→ 返回 Conflict；
        //  (b) 旧代码**完全不看 writeFile 的返回值** → Conflict 被静默吞掉，然后
        //      `getFile(不带斜杠)` 又 normalize 出去读到了**旧的那一条**并当成功返回 ——
        //      用户视角「新建记忆没反应/变成了另一条」；
        //  (c) 冲突后缀用 `System.currentTimeMillis().takeLast(6)`，同一毫秒内并发会重复。
        val basePath = me.rerere.rikkahub.data.model.pathFromContent(content)
            .let { if (it.startsWith("/")) it else "/$it" }
        var attempt = 0
        while (true) {
            val path = if (attempt == 0) basePath else basePath.removeSuffix(".md") + "-$attempt.md"
            when (
                val result = writeFile(
                    assistantId = assistantId,
                    path = path,
                    content = content,
                    ifVersion = NEW_FILE_VERSION,
                    source = if (source == "dream") "dream" else "active",
                    folderId = normalizedFolderId,
                    conversationId = normalizedConversationId,
                )
            ) {
                is MemoryWriteResult.Success ->
                    return result.file?.toAssistantMemory()
                        ?: error("记忆写入失败（回读为空）: $path")

                is MemoryWriteResult.Rejected -> error(result.reason)
                is MemoryWriteResult.Conflict -> {
                    attempt++
                    if (attempt > 50) error("记忆写入失败：同名路径冲突过多（$basePath）")
                }
            }
        }
    }

    suspend fun copyMemories(fromAssistantId: String, toAssistantId: String) {
        val files = getFiles(fromAssistantId)
        if (files.isEmpty()) return
        files.forEach { file ->
            // v5.3.19：检查结果 —— 目标助手已有同 path 时会 Conflict，旧实现静默跳过（复制"少了几条"）
            when (
                val r = writeFile(
                    assistantId = toAssistantId,
                    path = file.path,
                    content = file.content,
                    ifVersion = NEW_FILE_VERSION,
                    source = file.source,
                    folderId = file.folderId,
                    conversationId = null, // 跨助手复制：对话归属不能带过去（对话属于源助手）
                )
            ) {
                is MemoryWriteResult.Success -> Unit
                is MemoryWriteResult.Conflict ->
                    Log.w("MemoryRepository", "copyMemories: skip existing path=${file.path}")
                is MemoryWriteResult.Rejected ->
                    Log.w("MemoryRepository", "copyMemories: rejected ${file.path}: ${r.reason}")
            }
        }
    }

    suspend fun deleteMemory(id: Long) {
        // v5.3.13 ★统一删除语义：这里原本是**物理删除**（DELETE FROM），而 UI 走的是
        // `invalidateFile` 软删 —— 同一个"删除"动作两套语义（硬删无法追溯、软删还能被
        // 误判为没删掉）。现在统一为**软删**：写 `invalidated_at`，读侧（DAO）过滤，
        // 需要恢复时 `invalidateFile(..., invalidated = false)` 即可。
        val path = resolvePathById(GLOBAL_MEMORY_ID, id) ?: return
        invalidateFile(GLOBAL_MEMORY_ID, path)
    }

    /** v4.8.92: 带守卫的删除（模型侧用）—— 其它对话的单对话记忆不可被删除。 */
    suspend fun deleteMemoryChecked(
        id: Long,
        allowedAssistantId: String,
        currentConversationId: String?,
    ) {
        // v5.0.85：模型侧「删除」= 软删（失效留痕，不再注入后续会话）
        // v5.3.45 ★改走 `invalidateFile`（多形态）—— 旧实现直调 DAO 的 `invalidate`（单形态），
        // 对 v5.3.7 迁移来的**无前导斜杠老 path** 恒 0 行命中 → 模型说"已删除"但条目还在。
        // 函数体因此改成 suspend 返回 Boolean（调用方原本就是 suspend 上下文）。
        val path = resolvePathById(allowedAssistantId, id) ?: error("记忆 #$id 不存在")
        if (!invalidateFile(allowedAssistantId, path)) error("记忆 #$id 删除失败（路径未命中）")
    }

    // v5.3.13：`guardMemoryAccess(entity: MemoryEntity, ...)` 已删除 ——
    // 它唯一的语义是「跨助手不可访问」，而统一表的 `resolvePathById(assistantId, id)`
    // 在别的助手的库里根本找不到这个 id，**结构上已经保证了这条边界**，无需再判一次。
    // ═══ v5.3.23 ★旧格式记忆（迁移产物）的统计与清理 ═══
    //
    // 这一类记忆没有 frontmatter，内容是 v5.0.x 的版本发布流水账；它们**不再参与注入**
    // （见 `buildMemoryPrompt` 的 `<legacy_entries>`），只在记忆页可见。
    // 用户确认后可以一次性物理删除 —— 这是唯一允许的「物理删除整批」入口，
    // 与日常的单条软删（`invalidateFile`）分属两种语义，不要混用。

    /** 当前记忆库里「旧格式」记忆的条数（用于 UI 显示可清理量）。 */
    suspend fun countLegacyFiles(memoryId: String): Int = memoryFileDAO.countLegacyFiles(memoryId)

    /** 物理删除当前记忆库里的全部「旧格式」记忆，返回删除行数。 */
    suspend fun purgeLegacyFiles(memoryId: String): Int = withContext(Dispatchers.IO) {
        memoryFileDAO.deleteLegacyFiles(memoryId)
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

/**
 * v5.3.22 ★**补回原版 3.1.1 的边界处理**（移植 3.0.0 时丢了）。
 *
 * 原版 `MemoryRepository.kt:186-200` 的 `replaceAt`：删掉的是**完整一行**时，
 * 连相邻的那个换行一起去掉，不留空行。我们此前只剩一行 substring 拼接 ——
 * 模型用 `memory_str_replace(old_str="整行文本", new_str="")` 删条目时，
 * 每删一次就在文件里留一个空白行，记忆文件越用越稀。
 */
private fun String.replaceAt(index: Int, length: Int, replacement: String): String {
    var start = index
    var end = index + length
    if (replacement.isEmpty()) {
        val atLineStart = start == 0 || this[start - 1] == '\n'
        val atLineEnd = end == this.length || this[end] == '\n'
        if (atLineStart && atLineEnd) {
            if (end < this.length) {
                end++
            } else if (start > 0) {
                start--
            }
        }
    }
    return substring(0, start) + replacement + substring(end)
}

