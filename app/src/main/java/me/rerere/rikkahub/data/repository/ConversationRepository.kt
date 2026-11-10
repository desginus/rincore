/* 【域 I·数据存储】 — 仓库层 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.repository

/* ───【原版对齐】ConversationRepository.kt | 差异 ±17 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/


import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid
import android.util.Log

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val TAG = "ConversationRepository"
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    /** ★v5.3.69：上次落库的节点内容指纹（只服务流式增写；结构性写入会重置）。 */
    private class NodeFingerprintCache(val hashes: Map<String, Int>)
    private val nodeFingerprintCache = ConcurrentHashMap<String, NodeFingerprintCache>()
    private val conversationWriteLocks = ConcurrentHashMap<String, Mutex>()
    private fun writeLockOf(id: String): Mutex =
        conversationWriteLocks.computeIfAbsent(id) { Mutex() }

    /** ★v5.3.70：流式落库延迟的 FTS 变化（收尾统一 flush，避免每秒重新分词）。 */
    private val pendingFtsNodes = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingFtsRemoved = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingFtsFull = ConcurrentHashMap.newKeySet<String>()

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    /** v4.8.186: 收件箱 — 引用该子串(如 "upload/<上传码>")的对话 ID 轻查。 */
    suspend fun conversationIdsReferencing(needle: String): List<String> =
        messageNodeDAO.conversationIdsContaining(needle)

    /** v4.8.186: 收件箱 — 按对话 ID 直取标题 (String 形态, 免 Uuid 解析)。 */
    suspend fun conversationTitleByIdRaw(conversationId: String): String? =
        conversationDAO.getConversationById(conversationId)?.title

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    /** v4.8.62: 未归类对话计数 (落地逻辑判定; 与分页查询同口径) */
    /** v4.8.77: 项目包移出为助手 — 包内全部对话批量转移给新助手。 */
    suspend fun reassignFolderConversations(folderId: Uuid, assistantId: Uuid) {
        conversationDAO.reassignFolderConversations(folderId.toString(), assistantId.toString())
    }

    suspend fun countUnfiledConversationsOfAssistant(assistantId: Uuid): Int =
        conversationDAO.countUnfiledConversationsOfAssistant(assistantId.toString())

    // v5.0.16 做梦范围=项目包：包内对话 id（不含子代理会话；存储为 TEXT，解析回 Uuid）
    suspend fun getConversationIdsOfFolder(folderId: Uuid): List<Uuid> =
        conversationDAO.getConversationIdStringsOfFolder(folderId.toString())
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }

    /** v5.0.67：该助手名下所有归入项目包的对话 id（记忆页项目包视图用） */
    suspend fun getConversationIdsInFolders(assistantId: Uuid): List<Uuid> =
        conversationDAO.getConversationIdsInFolders(assistantId.toString())
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    // v3.8.15: 清理聊天内容候选 (非置顶 + 早于 cutoff) — 完整转换供删除
    suspend fun getConversationsEligibleForCleanup(cutoffEpochMs: Long): List<Conversation> {
        return conversationDAO.getCleanupCandidates(cutoffEpochMs)
            .map { conversationEntityToConversation(it, emptyList()) }
    }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            // ★v5.3.69：从 DB 读出即播种指纹 —— 打开长对话后第一条消息的流式落库
            // 直接走增量，不必先做一次「全表删 + 全量插 + FTS 重建」。
            nodeFingerprintCache.putIfAbsent(
                entity.id,
                NodeFingerprintCache(nodeFingerprints(conversation.messageNodes)),
            )
            conversation
        } else null
    }

    /** v5.0.95：消息节点数量 —— 用于"加载结果为空"的一致性校验（DB 有节点却读出 0 条 = 加载失败）。 */
    suspend fun countMessageNodes(conversationId: Uuid): Int =
        messageNodeDAO.countByConversation(conversationId.toString())

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    // v4.8.64 (2.5.5 移植): 数据恢复页 — 聊天记录中的助手分布 (含已从设置消失的助手)
    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    /**
     * 插入新会话。
     *
     * ★v5.3.55 **幂等化** —— 用户实测崩溃：
     * `UNIQUE constraint failed: ConversationEntity.id (code 1555)`，
     * 弹出位置是「自动生成标题失败」。调用链：
     * `generateTitle` → `getConversationById`（拿到行）→ `saveConversation` →
     * `existsConversationById` 判 false → 本函数 `insert` → 撞主键。
     *
     * 根因**不是某个调用点写错**，而是「先查存在、再插入」本身就是非原子操作：
     *  - `saveConversation` 的 `saveLock` 只覆盖它自己；
     *  - `HistoryVM.restoreConversation` / `BackupVM` / `DebugVM` /
     *    `SubAgentEngine` / `CronJobWorker` 都**直接调本函数**，不走那把锁；
     *  - Room 的 `existsById` 是独立读事务，与随后的写之间天然存在窗口。
     * 于是同一个 id 在「查完 → 写进去」之间被另一条路径插入，就会撞主键；
     * 异常又被 `generateTitle` 的 `runCatching` 捕获，表现成「标题生成失败」。
     *
     * 修法：不为某个并发者打补丁，而是**让插入在重复发生时安全收敛** ——
     * 捕获主键冲突后转 update（语义即「这条会话已存在，把内容对齐」），
     * 等价于数据库层的 upsert。**非主键冲突一律原样抛出**，不掩盖真问题。
     */
    suspend fun insertConversation(conversation: Conversation) {
        requireNoBase64(conversation.messageNodes)
        try {
            database.withTransaction {
                conversationDAO.insert(
                    conversationToConversationEntity(conversation)
                )
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            }
        } catch (e: Exception) {
            if (!e.isPrimaryKeyConflict()) throw e
            Log.w(
                "ConversationRepository",
                "insertConversation: PK already exists id=${conversation.id} " +
                    "→ 幂等转 update（并发插入 / 重复恢复 / 标题生成与落库撞窗）",
            )
            // updateConversation 自带「空节点不清空历史」「骤减拒绝」两道守卫，
            // 直接复用它，避免在这里再造一套写节点语义（也不重复刷 FTS）。
            updateConversation(conversation)
            return
        }
        messageFtsManager.indexConversation(conversation)
        val insertedId = conversation.id.toString()
        // ★v5.3.69：插入即落库完当前节点，播种指纹，下一次流式落库可直接走增量。
        nodeFingerprintCache[insertedId] =
            NodeFingerprintCache(nodeFingerprints(conversation.messageNodes))
        // ★v5.3.70：插入是全量索引，清掉任何延迟记账，避免收尾重复分词。
        pendingFtsNodes.remove(insertedId)
        pendingFtsRemoved.remove(insertedId)
        pendingFtsFull.remove(insertedId)
    }

    /**
     * ★v5.3.55：判断异常是否为「主键/唯一约束冲突」。
     *
     * 设备上抛的是 `android.database.sqlite.SQLiteConstraintException`，但 SQLite
     * 驱动有多种实现（framework / requery），**类名不同、message 一致**，
     * 因此按 **cause 链逐层看「类名 + message」**，而不是只 catch 某一个具体类
     * （否则换机型/换驱动就失效，正是这类崩溃只在部分设备出现的原因）。
     *
     * **只认 UNIQUE / PRIMARY KEY**：外键、非空、CHECK 约束都不在此列 ——
     * 那些是真正的数据问题，必须原样抛出，不能被「幂等」悄悄吞掉。
     */
    private fun Throwable.isPrimaryKeyConflict(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 8) {
            val name = current.javaClass.simpleName
            val msg = current.message.orEmpty()
            val looksLikeConstraint =
                name.contains("Constraint", ignoreCase = true) ||
                    msg.contains("constraint", ignoreCase = true)
            val isKeyCollision =
                msg.contains("UNIQUE constraint failed", ignoreCase = true) ||
                    msg.contains("PRIMARY KEY", ignoreCase = true)
            if (looksLikeConstraint && isKeyCollision) return true
            current = current.cause
            depth++
        }
        return false
    }

    /**
     * 更新会话（整对象）。
     *
     * @param allowShrink ★v5.3.56：允许「消息节点显著变少」的**结构性变更**。
     *
     * 为什么需要它（真 P0）：下方 v5.0.24 的「骤减拒绝」守卫用 `incoming < existing / 2`
     * 判定数据丢失特征 —— 但**压缩上下文天然就是这个形状**（200 条 → 摘要 + 最近 10 条）。
     * 于是压缩结果被这条守卫拦在 `message_node` 表之外：只写了元数据、没写节点；
     * 而内存已被 `saveConversation` 改成压缩后的样子 → 校验看到内存命中即「成功」，
     * 用户当下也看到摘要，**但重启/重新加载后原消息全部回来**。
     *
     * 语义：只有调用方**明确声明这是结构性替换**（压缩 / 导入制定状态）时才为 true，
     * 默认 false 保持原有防丢数据保护。
     */
    suspend fun updateConversation(
        conversation: Conversation,
        allowShrink: Boolean = false,
        indexFts: Boolean = true,
    ) {
        val conversationId = conversation.id.toString()
        // ★v5.3.69：同一对话的落库串行化，保证「读指纹 → 写增量 → 更新指纹」不会互相踩。
        writeLockOf(conversationId).withLock {
            updateConversationLocked(conversation, allowShrink, indexFts)
        }
    }

    /**
     * ★v5.3.69：按节点指纹增量写（只 upsert 变化节点）。
     * ★v5.3.70：FTS 分词从「每次流式落库」改为「可延迟到本轮收尾」—— 流式期每秒
     * 对增长中的消息重新分词是长对话卡顿的最后一个固定成本；延迟后每轮只分词一次。
     */
    private suspend fun updateConversationLocked(
        conversation: Conversation,
        allowShrink: Boolean,
        indexFts: Boolean,
    ) {
        val conversationId = conversation.id.toString()
        var plan = NodeWritePlan(fullRewrite = true, changed = emptyList(), removed = emptySet())
        var nodesWritten = false
        var appliedIncremental = false
        var cacheToStore: NodeFingerprintCache? = null

        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // v5.0.19 (B201 数据事故修复): 空消息列表绝不清空已落库节点。
            val existing = messageNodeDAO.countByConversation(conversationId)
            val incoming = conversation.messageNodes.size
            // v5.0.24: 非空但骤减（少于已有一半）一律视为数据丢失特征，拒绝写节点。
            val suspiciousShrink = existing > 0 && incoming > 0 && incoming < existing / 2
            if (suspiciousShrink && !allowShrink) {
                Log.w(
                    TAG,
                    "updateConversation: REFUSE suspicious shrink ${conversation.id} ($existing -> $incoming) — metadata only"
                )
            } else if (conversation.messageNodes.isNotEmpty() || existing == 0) {
                val incomingHashes = nodeFingerprints(conversation.messageNodes)
                if (allowShrink) {
                    // 结构性替换：全量重写（压缩/恢复位点），并重置指纹
                    requireNoBase64(conversation.messageNodes)
                    messageNodeDAO.deleteByConversation(conversationId)
                    saveMessageNodes(conversationId, conversation.messageNodes)
                    nodesWritten = true
                } else {
                    val cached = nodeFingerprintCache[conversationId]
                    plan = planNodeWrite(cached?.hashes, conversation.messageNodes, incomingHashes)
                    if (plan.fullRewrite) {
                        requireNoBase64(conversation.messageNodes)
                        messageNodeDAO.deleteByConversation(conversationId)
                        saveMessageNodes(conversationId, conversation.messageNodes)
                        nodesWritten = true
                    } else {
                        if (plan.changed.isNotEmpty()) {
                            // v5.3.70：只校验真正要写的节点（旧实现每次落库都全量扫）。
                            requireNoBase64(plan.changed.map { it.value })
                            messageNodeDAO.insertAll(
                                plan.changed.map { (index, node) -> node.toEntity(conversationId, index) }
                            )
                            nodesWritten = true
                        }
                        if (plan.removed.isNotEmpty()) {
                            messageNodeDAO.deleteByIds(plan.removed.toList())
                            nodesWritten = true
                        }
                        appliedIncremental = true
                    }
                }
                cacheToStore = NodeFingerprintCache(hashes = incomingHashes)
            }
        }
        // ★只在事务提交成功后更新指纹：事务回滚时缓存若先更新，下一次同内容写入会被
        // 误判为「已落库」而跳过 —— 那才是真正的数据丢失。
        cacheToStore?.let { nodeFingerprintCache[conversationId] = it }

        if (indexFts) {
            flushPendingFts(conversation, plan, nodesWritten, appliedIncremental)
        } else if (nodesWritten) {
            deferFts(conversationId, plan, appliedIncremental)
        }
    }

    /** 把延迟的 FTS 变化与本次变化合并，一次写入。 */
    private suspend fun flushPendingFts(
        conversation: Conversation,
        plan: NodeWritePlan,
        nodesWritten: Boolean,
        appliedIncremental: Boolean,
    ) {
        val conversationId = conversation.id.toString()
        val pendingNodes = pendingFtsNodes.remove(conversationId)
        val pendingRemoved = pendingFtsRemoved.remove(conversationId)
        val fullNeeded = pendingFtsFull.remove(conversationId)

        // 防御：空壳更新不得吞掉待索引变化（否则延迟的节点永远不会再进 FTS）。
        // 正常的空对话不会走到这里（上层有空壳总闸），这是最后一道保险。
        if (conversation.messageNodes.isEmpty()) {
            pendingNodes?.let { pendingFtsNodes[conversationId] = it }
            pendingRemoved?.let { pendingFtsRemoved[conversationId] = it }
            if (fullNeeded) pendingFtsFull.add(conversationId)
            return
        }

        if (fullNeeded || (nodesWritten && !appliedIncremental)) {
            if (conversation.messageNodes.isNotEmpty()) {
                messageFtsManager.indexConversation(conversation)
            }
            return
        }

        // 一次建索引，避免 pending × 全表的 firstOrNull（O(pending×n)）。
        val currentById = conversation.messageNodes.associateBy { it.id.toString() }
        val toIndex = LinkedHashMap<String, MessageNode>()
        plan.changed.forEach { (_, node) -> toIndex[node.id.toString()] = node }
        pendingNodes?.forEach { nodeId ->
            currentById[nodeId]?.let { toIndex[nodeId] = it }
        }
        if (toIndex.isNotEmpty()) {
            messageFtsManager.indexNodes(conversation, toIndex.values.toList())
        }

        val toDelete = LinkedHashSet<String>(plan.removed)
        pendingRemoved?.let { toDelete.addAll(it) }
        // 节点可能先被延迟记账、后来又被重新加回（分支操作）—— 存在的不删。
        toDelete.removeAll(currentById.keys)
        if (toDelete.isNotEmpty()) {
            messageFtsManager.deleteNodes(conversationId, toDelete)
        }
    }

    /** 流式期间只记账，不在热路径上分词。 */
    private fun deferFts(conversationId: String, plan: NodeWritePlan, appliedIncremental: Boolean) {
        if (!appliedIncremental) {
            pendingFtsFull.add(conversationId)
            pendingFtsNodes.remove(conversationId)
            pendingFtsRemoved.remove(conversationId)
            return
        }
        if (plan.changed.isNotEmpty()) {
            val set = pendingFtsNodes.computeIfAbsent(conversationId) { mutableSetOf() }
            plan.changed.forEach { set.add(it.value.id.toString()) }
        }
        if (plan.removed.isNotEmpty()) {
            val set = pendingFtsRemoved.computeIfAbsent(conversationId) { mutableSetOf() }
            set.addAll(plan.removed)
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
        val deletedId = conversation.id.toString()
        nodeFingerprintCache.remove(deletedId)
        pendingFtsNodes.remove(deletedId)
        pendingFtsRemoved.remove(deletedId)
        pendingFtsFull.remove(deletedId)
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        filesManager.deleteChatFiles(fullConversation.files)
        // v4.8.185 (审计重写): 对话媒体快照随对话删除 (files/chat-media/<对话ID>/)
        filesManager.deleteConversationChatMedia(conversation.id.toString())
        // v4.8.92: 单对话记忆随对话删除（该记忆只在对应对话可见，对话没了即销毁）
        // v5.3.13 ★★最后一条**直写旧表**的路径：这里原本调 `database.memoryDao()`
        // （`memoryentity`），绕过 MemoryRepository 单独写第二张表 —— 已改为统一表。
        runCatching {
            database.memoryFileDao().deleteFilesOfConversation(conversation.id.toString())
        }
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.let { listOf(it.toString()) })

    /** 只在 [assistantIds] 这些助手的对话里搜索 */
    suspend fun searchMessagesOfAssistants(keyword: String, assistantIds: Set<Uuid>) =
        messageFtsManager.search(keyword, MessageSearchSort.RELEVANCE, assistantIds.map { it.toString() })

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    /**
     * ★v5.3.70：base64 图片禁止落库的校验，从「每次整对象映射」移到「真正写节点的边界」。
     * 旧实现放在 conversationToConversationEntity 里 —— 每次流式落库（1s）都要扫全部
     * messageNodes × messages × parts；现在只在写变化的节点 / 全量重写时校验。
     */
    private fun requireNoBase64(nodes: List<MessageNode>) {
        require(nodes.none { node -> node.messages.any { it.hasBase64Part() } }) {
            "Base64 images are not allowed in the database"
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            compressRetentions = JsonInstant.encodeToString(conversation.compressRetentions),
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            // legacy 数据壳: v4.5.23 起 CWD 为助手级 (Assistant.workspaceCwd), 本列仅数据兼容
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            compressRetentions = runCatching {
                JsonInstant.decodeFromString<List<me.rerere.rikkahub.data.model.CompressRetention>>(
                    conversationEntity.compressRetentions
                )
            }.getOrDefault(emptyList()),
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null }, // legacy 数据壳 (v4.5.23 助手级接管)
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !(getConversationById(conversationId)?.isPinned ?: false)
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        val loaded: List<MessageNode> = database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    // v5.0.95 ★历史遗留陷阱（用户 2026-10-25 实测「切到任何对话都没内容、
                    // 重试无效、重启才恢复」的根因之一）：
                    // 旧实现 offset += 64 直接把**整页**丢掉 —— 一条超 CursorWindow 的
                    // blob 会让 64 条消息一起消失；若第一页就抛，loadMessageNodes 返回
                    // **空列表** → 会话被标记 READY 的空壳 → UI 显示空对话（星轨）、
                    // initialize 因 READY 永久跳过、重试无效，只能重启重建 session。
                    // 现在改为**逐条降级**：只放弃真正读不出的那一条，其余照常加载。
                    Log.w(TAG, "blob too big in page offset=$offset, degrade per-row", e)
                    val recovered = mutableListOf<MessageNodeEntity>()
                    for (i in offset until offset + pageSize) {
                        val single = try {
                            messageNodeDAO.getNodesOfConversationPaged(conversationId, 1, i)
                        } catch (_: SQLiteBlobTooBigException) {
                            emptyList()
                        }
                        recovered += single
                    }
                    if (recovered.isEmpty()) {
                        // 整页都不可读：推进一页继续（绝不再多吞数据）
                        offset += pageSize
                        continue
                    }
                    recovered
                }
                // v5.0.95 ★IllegalStateException 不再吞：它不是"某条数据坏"，而是
                // 事务失效 / 连接池关闭 / 数据库状态错误。吞掉 = 返回空对话 + READY
                // 空壳 = 用户看到的"切到任何对话都没内容、重启才恢复"。
                // 抛出后 initialize 会把 phase 置为 FAILED，下次进入自动重试。
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                // v5.0.95：固定推进 pageSize（降级分支返回的 recovered 可能少于 64 条，
                // 按 page.size 推进会与数据库行号错位、重复读取）
                offset += pageSize
            }
            nodes
        }
        // v5.0.95 ★空结果一致性校验：DB 里明明有节点却加载成 0 条 → 明确失败，
        // 让 initialize 把 phase 置为 FAILED（下次进入自动重试），
        // **绝不当成"空对话"标记 READY**（那会让 UI 永久空、只能重启）。
        if (loaded.isEmpty()) {
            val total = runCatching { messageNodeDAO.countByConversation(conversationId) }.getOrDefault(0)
            if (total > 0) {
                error("loadMessageNodes(conversationId=$conversationId) 返回 0 条，但 DB 有 $total 条")
            }
        }
        return loaded
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        messageNodeDAO.insertAll(
            nodes.mapIndexed { index, node -> node.toEntity(conversationId, index) }
        )
    }

    private fun MessageNode.toEntity(conversationId: String, index: Int) = MessageNodeEntity(
        id = id.toString(),
        conversationId = conversationId,
        nodeIndex = index,
        messages = JsonInstant.encodeToString(messages),
        selectIndex = selectIndex,
    )
}

/**
 * ★v5.3.69：节点增量写计划（纯函数，可单测）。
 * fullRewrite=true 表示调用方必须整段重写（首次 / 结构性替换 / 空缓存）。
 */
internal data class NodeWritePlan(
    val fullRewrite: Boolean,
    val changed: List<IndexedValue<MessageNode>>,
    val removed: Set<String>,
)

internal fun nodeFingerprint(node: MessageNode, index: Int): Int {
    var h = 17
    h = 31 * h + node.id.hashCode()
    h = 31 * h + index
    h = 31 * h + node.selectIndex
    h = 31 * h + node.messages.hashCode()
    return h
}

internal fun nodeFingerprints(nodes: List<MessageNode>): Map<String, Int> {
    val out = HashMap<String, Int>(nodes.size)
    nodes.forEachIndexed { index, node -> out[node.id.toString()] = nodeFingerprint(node, index) }
    return out
}

internal fun planNodeWrite(cachedHashes: Map<String, Int>?, nodes: List<MessageNode>): NodeWritePlan =
    planNodeWrite(cachedHashes, nodes, nodeFingerprints(nodes))

/**
 * ★v5.3.70：接收已算好的指纹，避免「判定变化」与「写缓存」各算一遍（每次落库省一次 O(n) 哈希）。
 */
internal fun planNodeWrite(
    cachedHashes: Map<String, Int>?,
    nodes: List<MessageNode>,
    incomingHashes: Map<String, Int>,
): NodeWritePlan {
    if (cachedHashes == null || cachedHashes.isEmpty() || nodes.isEmpty()) {
        return NodeWritePlan(fullRewrite = true, changed = emptyList(), removed = emptySet())
    }
    val changed = ArrayList<IndexedValue<MessageNode>>()
    nodes.forEachIndexed { index, node ->
        if (cachedHashes[node.id.toString()] != incomingHashes[node.id.toString()]) {
            changed += IndexedValue(index, node)
        }
    }
    return NodeWritePlan(
        fullRewrite = false,
        changed = changed,
        removed = cachedHashes.keys - incomingHashes.keys,
    )
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
