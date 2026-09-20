package me.rerere.rikkahub.data.repository


/* ───【自研】增强记忆仓库 (v4.6.5 — 增强记忆工具与 RinCore 原生记忆系统的"交火"层)
 * 承载 extended_memory_tools 的全部操作: 节点的增删改移 + 链接(知识图谱)的增删改查。
 * 与原生 MemoryEntity (简易文本记忆) 并行: 本仓库的记忆经 getNodesForPrompt()
 * 转入原生记忆注入链 — 两条记忆线在对话上下文中原生合流。
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.db.dao.MemLinkDAO
import me.rerere.rikkahub.data.db.dao.MemNodeDAO
import me.rerere.rikkahub.data.db.entity.MemLinkEntity
import me.rerere.rikkahub.data.db.entity.MemNodeEntity
import me.rerere.rikkahub.data.model.AssistantMemory

class EnhancedMemoryRepository(
    private val nodeDao: MemNodeDAO,
    private val linkDao: MemLinkDAO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ── 节点 ───────────────────────────────────────────────
    suspend fun create(
        assistantId: String,
        title: String,
        content: String,
        contentType: String = "text",
        source: String = "",
        folderPath: String = "",
        tags: List<String> = emptyList(),
    ): MemNodeEntity? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        // 标题重名 → 拒绝 (与工具语义一致: 创建新节点)
        if (nodeDao.findByTitle(title, assistantId) != null) return@withContext null
        val now = System.currentTimeMillis()
        val entity = MemNodeEntity(
            assistantId = assistantId,
            title = title,
            content = content,
            contentType = contentType,
            source = source,
            folderPath = folderPath,
            tags = json.encodeToString(JsonArray.serializer(), JsonArray(tags.map { JsonPrimitive(it) })),
            createdAt = now,
            updatedAt = now,
        )
        val id = nodeDao.insert(entity)
        entity.copy(id = id)
    }

    suspend fun update(
        assistantId: String,
        oldTitle: String,
        newTitle: String,
        content: String?,
        contentType: String?,
        source: String?,
        folderPath: String?,
        tags: List<String>?,
    ): MemNodeEntity? = withContext(Dispatchers.IO) {
        val existing = nodeDao.findByTitle(oldTitle, assistantId) ?: return@withContext null
        val updated = existing.copy(
            title = newTitle.ifBlank { existing.title },
            content = content ?: existing.content,
            contentType = contentType ?: existing.contentType,
            source = source ?: existing.source,
            folderPath = folderPath ?: existing.folderPath,
            tags = tags?.let { json.encodeToString(JsonArray.serializer(), JsonArray(it.map { t -> JsonPrimitive(t) })) }
                ?: existing.tags,
            updatedAt = System.currentTimeMillis(),
        )
        nodeDao.update(updated)
        updated
    }

    suspend fun deleteByTitle(assistantId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val existing = nodeDao.findByTitle(title, assistantId) ?: return@withContext false
        nodeDao.deleteById(existing.id)
        true
    }

    /** 批量移动: titles 为空时按 sourceFolderPath 筛选全部; 返回移动数量 */
    suspend fun move(
        assistantId: String,
        titles: List<String>,
        sourceFolderPath: String?,
        targetFolderPath: String,
    ): Int = withContext(Dispatchers.IO) {
        val all = nodeDao.listForAssistant(assistantId)
        val targets = when {
            titles.isNotEmpty() -> all.filter { it.title in titles }
            !sourceFolderPath.isNullOrBlank() -> all.filter { it.folderPath == sourceFolderPath }
            else -> emptyList()
        }
        val now = System.currentTimeMillis()
        targets.forEach { nodeDao.updateFolder(it.id, targetFolderPath, now) }
        targets.size
    }

    suspend fun getNode(assistantId: String, title: String): MemNodeEntity? =
        withContext(Dispatchers.IO) { nodeDao.findByTitle(title, assistantId) }

    // ── 链接 (知识图谱) ────────────────────────────────────
    suspend fun link(
        assistantId: String,
        sourceTitle: String,
        targetTitle: String,
        linkType: String = "related",
        weight: Double = 1.0,
        description: String = "",
    ): MemLinkEntity? = withContext(Dispatchers.IO) {
        if (sourceTitle.isBlank() || targetTitle.isBlank()) return@withContext null
        if (linkDao.find(sourceTitle, targetTitle) != null) return@withContext null
        val entity = MemLinkEntity(
            assistantId = assistantId,
            sourceTitle = sourceTitle,
            targetTitle = targetTitle,
            linkType = linkType,
            weight = weight,
            description = description,
            createdAt = System.currentTimeMillis(),
        )
        val id = linkDao.insert(entity)
        entity.copy(id = id)
    }

    suspend fun queryLinks(
        assistantId: String,
        linkId: Long?,
        sourceTitle: String?,
        targetTitle: String?,
        linkType: String?,
        limit: Int = 100,
    ): List<MemLinkEntity> = withContext(Dispatchers.IO) {
        when {
            linkId != null -> listOfNotNull(linkDao.findById(linkId))
            else -> linkDao.listForAssistant(assistantId)
                .filter { sourceTitle.isNullOrBlank() || it.sourceTitle == sourceTitle }
                .filter { targetTitle.isNullOrBlank() || it.targetTitle == targetTitle }
                .filter { linkType.isNullOrBlank() || it.linkType == linkType }
                .take(limit.coerceIn(1, 500))
        }
    }

    suspend fun updateLink(
        assistantId: String,
        linkId: Long?,
        sourceTitle: String?,
        targetTitle: String?,
        linkType: String?,
        newLinkType: String,
        weight: Double,
        description: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = when {
            linkId != null -> linkDao.findById(linkId)
            !sourceTitle.isNullOrBlank() && !targetTitle.isNullOrBlank() -> linkDao.find(sourceTitle, targetTitle)
            else -> null
        } ?: return@withContext false
        linkDao.updateLink(existing.id, newLinkType.ifBlank { linkType ?: existing.linkType }, weight, description)
        true
    }

    suspend fun deleteLink(
        assistantId: String,
        linkId: Long?,
        sourceTitle: String?,
        targetTitle: String?,
        linkType: String?,
    ): Int = withContext(Dispatchers.IO) {
        val targets = when {
            linkId != null -> listOfNotNull(linkDao.findById(linkId))
            else -> linkDao.listForAssistant(assistantId)
                .filter { sourceTitle.isNullOrBlank() || it.sourceTitle == sourceTitle }
                .filter { targetTitle.isNullOrBlank() || it.targetTitle == targetTitle }
                .filter { linkType.isNullOrBlank() || it.linkType == linkType }
        }
        targets.forEach { linkDao.deleteById(it.id) }
        targets.size
    }

    // ── 注入链合流 (增强记忆 → 原生记忆 prompt) ─────────────
    /**
     * 把增强记忆节点转成原生 AssistantMemory 格式 (负数 id 防与原生冲突),
     * 由 ChatService 并入 memories 列表 — 原生记忆系统直接消费。
     */
    suspend fun getNodesForPrompt(assistantId: String): List<AssistantMemory> = withContext(Dispatchers.IO) {
        nodeDao.listForAssistant(assistantId).map { node ->
            val folder = if (node.folderPath.isNotBlank()) "· 文件夹: ${node.folderPath} " else ""
            AssistantMemory(
                id = -node.id,
                content = "【${node.title}】$folder\n${node.content}",
            )
        }
    }
}
