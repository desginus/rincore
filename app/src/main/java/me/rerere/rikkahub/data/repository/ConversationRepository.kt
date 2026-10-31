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
            conversationEntityToConversation(entity, nodes)
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

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        var nodesWritten = false
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // v5.0.19 (B201 数据事故修复): 空消息列表绝不清空已落库节点。
            // 事故链：ChatPage 打开即 updateDraftFolder(空壳) → saveConversation →
            // 此处 deleteByConversation + 写入 0 条 = 该对话消息全部永久删除。
            val existing = messageNodeDAO.countByConversation(conversation.id.toString())
            val incoming = conversation.messageNodes.size
            // v5.0.24 用户明令「不希望再看见任何形式的删除上下文」：
            // 第二道守卫 —— 非空但骤减（少于已有一半）一律视为数据丢失特征，拒绝写节点。
            // 正常编辑/重新生成/分支切换只会等量或小幅变化，不会腰斩。
            val suspiciousShrink = existing > 0 && incoming > 0 && incoming < existing / 2
            if (suspiciousShrink) {
                android.util.Log.w(
                    "ConversationRepository",
                    "updateConversation: REFUSE suspicious shrink ${conversation.id} ($existing -> $incoming) — metadata only"
                )
            } else if (conversation.messageNodes.isNotEmpty() || existing == 0) {
                // 删除旧的节点，插入新的节点
                messageNodeDAO.deleteByConversation(conversation.id.toString())
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                nodesWritten = true
            }
        }
        // v5.0.24: 节点未写入时不得用空壳重建索引（否则 FTS 也被清空）
        if (nodesWritten || conversation.messageNodes.isNotEmpty()) {
            messageFtsManager.indexConversation(conversation)
        }
    }

    suspend fun deleteConversation(conversation: Conversation) {
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

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
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
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
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
