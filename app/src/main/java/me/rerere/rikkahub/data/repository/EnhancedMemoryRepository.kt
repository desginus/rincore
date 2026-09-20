package me.rerere.rikkahub.data.repository


/* ───【自研】增强记忆仓库 (v4.6.6 文件存储版)
 * v4.6.5 曾用 Room 表实现, 因升级启动崩溃 (迁移链风险) 紧急回退 —
 * 改为 JSON 文件存储: 接口完全不变, 桥/JS/注入链零改动。
 * 个人记忆量级下文件存储完全够用, 且天然不参与 DB 迁移链 (零启动风险)。
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.model.AssistantMemory

@Serializable
data class MemNode(
    val id: Long = 0,
    val assistantId: String = "",
    val title: String = "",
    val content: String = "",
    val contentType: String = "text",
    val source: String = "",
    val folderPath: String = "",
    val tags: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class MemLink(
    val id: Long = 0,
    val assistantId: String = "",
    val sourceTitle: String = "",
    val targetTitle: String = "",
    val linkType: String = "related",
    val weight: Double = 1.0,
    val description: String = "",
    val createdAt: Long = 0,
)

internal data class MemStore(
    val nodes: List<MemNode> = emptyList(),
    val links: List<MemLink> = emptyList(),
    val nextNodeId: Long = 1,
    val nextLinkId: Long = 1,
)

class EnhancedMemoryRepository(
    private val storeDir: File,
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file: File by lazy {
        storeDir.apply { mkdirs() }
        File(storeDir, "store.json")
    }
    private val lock = Any()

    private fun load(): MemStore = synchronized(lock) {
        if (!file.exists()) return MemStore()
        runCatching {
            json.decodeFromString(MemStore.serializer(), file.readText())
        }.onFailure { Log.w(TAG, "load store failed, resetting: ${it.message}") }
            .getOrDefault(MemStore())
    }

    private fun save(store: MemStore) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(MemStore.serializer(), store))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "save store failed: ${it.message}") }
    }

    private fun dump(store: MemStore) = synchronized(lock) { save(store) }

    // ── 节点 ───────────────────────────────────────────────
    suspend fun create(
        assistantId: String,
        title: String,
        content: String,
        contentType: String = "text",
        source: String = "",
        folderPath: String = "",
        tags: List<String> = emptyList(),
    ): MemNode? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        synchronized(lock) {
            val store = load()
            if (store.nodes.any { it.title == title }) return@withContext null
            val now = System.currentTimeMillis()
            val node = MemNode(
                id = store.nextNodeId,
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
            dump(store.copy(nodes = store.nodes + node, nextNodeId = store.nextNodeId + 1))
            node
        }
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
    ): MemNode? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val store = load()
            val existing = store.nodes.firstOrNull { it.title == oldTitle } ?: return@withContext null
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
            dump(store.copy(nodes = store.nodes.map { if (it.id == existing.id) updated else it }))
            updated
        }
    }

    suspend fun deleteByTitle(assistantId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val store = load()
            val target = store.nodes.firstOrNull { it.title == title } ?: return@withContext false
            dump(
                store.copy(
                    nodes = store.nodes.filterNot { it.id == target.id },
                    links = store.links.filterNot { it.sourceTitle == title || it.targetTitle == title },
                )
            )
            true
        }
    }

    /** 批量移动: titles 为空时按 sourceFolderPath 筛选全部; 返回移动数量 */
    suspend fun move(
        assistantId: String,
        titles: List<String>,
        sourceFolderPath: String?,
        targetFolderPath: String,
    ): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val store = load()
            val targets = when {
                titles.isNotEmpty() -> store.nodes.filter { it.title in titles }
                !sourceFolderPath.isNullOrBlank() -> store.nodes.filter { it.folderPath == sourceFolderPath }
                else -> emptyList()
            }
            if (targets.isEmpty()) return@withContext 0
            val ids = targets.map { it.id }.toSet()
            val now = System.currentTimeMillis()
            dump(store.copy(nodes = store.nodes.map { if (it.id in ids) it.copy(folderPath = targetFolderPath, updatedAt = now) else it }))
            targets.size
        }
    }

    suspend fun getNode(assistantId: String, title: String): MemNode? =
        withContext(Dispatchers.IO) { load().nodes.firstOrNull { it.title == title } }

    // ── 链接 (知识图谱) ────────────────────────────────────
    suspend fun link(
        assistantId: String,
        sourceTitle: String,
        targetTitle: String,
        linkType: String = "related",
        weight: Double = 1.0,
        description: String = "",
    ): MemLink? = withContext(Dispatchers.IO) {
        if (sourceTitle.isBlank() || targetTitle.isBlank()) return@withContext null
        synchronized(lock) {
            val store = load()
            if (store.links.any { it.sourceTitle == sourceTitle && it.targetTitle == targetTitle }) return@withContext null
            val entity = MemLink(
                id = store.nextLinkId,
                assistantId = assistantId,
                sourceTitle = sourceTitle,
                targetTitle = targetTitle,
                linkType = linkType,
                weight = weight,
                description = description,
                createdAt = System.currentTimeMillis(),
            )
            dump(store.copy(links = store.links + entity, nextLinkId = store.nextLinkId + 1))
            entity
        }
    }

    suspend fun queryLinks(
        assistantId: String,
        linkId: Long?,
        sourceTitle: String?,
        targetTitle: String?,
        linkType: String?,
        limit: Int = 100,
    ): List<MemLink> = withContext(Dispatchers.IO) {
        val all = load().links
        when {
            linkId != null -> all.filter { it.id == linkId }
            else -> all
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
        synchronized(lock) {
            val store = load()
            val existing = when {
                linkId != null -> store.links.firstOrNull { it.id == linkId }
                !sourceTitle.isNullOrBlank() && !targetTitle.isNullOrBlank() ->
                    store.links.firstOrNull { it.sourceTitle == sourceTitle && it.targetTitle == targetTitle }
                else -> null
            } ?: return@withContext false
            val updated = existing.copy(
                linkType = newLinkType.ifBlank { linkType ?: existing.linkType },
                weight = weight,
                description = description,
            )
            dump(store.copy(links = store.links.map { if (it.id == existing.id) updated else it }))
            true
        }
    }

    suspend fun deleteLink(
        assistantId: String,
        linkId: Long?,
        sourceTitle: String?,
        targetTitle: String?,
        linkType: String?,
    ): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val store = load()
            val targets = when {
                linkId != null -> store.links.filter { it.id == linkId }
                else -> store.links
                    .filter { sourceTitle.isNullOrBlank() || it.sourceTitle == sourceTitle }
                    .filter { targetTitle.isNullOrBlank() || it.targetTitle == targetTitle }
                    .filter { linkType.isNullOrBlank() || it.linkType == linkType }
            }
            if (targets.isEmpty()) return@withContext 0
            val ids = targets.map { it.id }.toSet()
            dump(store.copy(links = store.links.filterNot { it.id in ids }))
            targets.size
        }
    }

    // ── 注入链合流 (增强记忆 → 原生记忆 prompt) ─────────────
    suspend fun getNodesForPrompt(assistantId: String): List<AssistantMemory> = withContext(Dispatchers.IO) {
        load().nodes.map { node ->
            val folder = if (node.folderPath.isNotBlank()) "· 文件夹: ${node.folderPath} " else ""
            AssistantMemory(
                id = -node.id,
                content = "【${node.title}】$folder\n${node.content}",
            )
        }
    }

    private companion object {
        const val TAG = "EnhancedMemory"
    }
}
