package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.dao.MemoryFileDAO
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity

/**
 * v5.3.10：修正为**只实现 `MemoryFileDAO`**。
 *
 * 历史问题：它曾写成 `: MemoryDAO`（那是旧的条目式表接口）却实现了文件式方法 ——
 * 早在 v5.3.7 统一记忆后就已经编译不过，但因为 CI 从不运行 `app` 模块的单元测试，
 * 这个断裂一直没被发现（见 build.yml 只跑 `:app:compileResearchKotlin`）。
 */
internal class FakeMemoryDAO : MemoryFileDAO {
    private val files = MutableStateFlow<List<MemoryFileEntity>>(emptyList())

    override fun getFilesOfAssistantFlow(assistantId: String): Flow<List<MemoryFileEntity>> =
        files.map { all -> all.filter { it.assistantId == assistantId }.sortedBy { it.path } }

    override suspend fun getFilesOfAssistant(assistantId: String): List<MemoryFileEntity> =
        files.value.filter { it.assistantId == assistantId }.sortedBy { it.path }

    override suspend fun getFile(assistantId: String, path: String): MemoryFileEntity? =
        files.value.find { it.assistantId == assistantId && it.path == path }

    override suspend fun upsertFile(file: MemoryFileEntity) = upsertFiles(listOf(file))

    override suspend fun upsertFiles(files: List<MemoryFileEntity>) {
        val replaced = files.map { it.assistantId to it.path }.toSet()
        this.files.value = this.files.value.filterNot { (it.assistantId to it.path) in replaced } + files
    }

    override suspend fun deleteFile(assistantId: String, path: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId && it.path == path }
    }

    override suspend fun deleteFilesOfAssistant(assistantId: String) {
        files.value = files.value.filterNot { it.assistantId == assistantId }
    }

    // ═══ v5.3.7 统一记忆新增：来源 / 归属 / 软删 / 对话查询 ═══

    override suspend fun updateScope(
        assistantId: String,
        path: String,
        folderId: String?,
        conversationId: String?,
        at: Long,
    ) {
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path == path) {
                it.copy(folderId = folderId, conversationId = conversationId, updatedAt = at)
            } else {
                it
            }
        }
    }

    override suspend fun updateSource(assistantId: String, path: String, source: String, at: Long) {
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path == path) {
                it.copy(source = source, updatedAt = at)
            } else {
                it
            }
        }
    }

    override suspend fun invalidate(assistantId: String, path: String, at: Long?): Int {
        var hit = 0
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path == path) {
                hit++
                it.copy(invalidatedAt = at)
            } else {
                it
            }
        }
        return hit
    }

    override fun getFilesBySourceFlow(assistantId: String, source: String): Flow<List<MemoryFileEntity>> =
        files.map { all ->
            all.filter {
                it.assistantId == assistantId && it.source == source &&
                    (it.invalidatedAt == null || it.invalidatedAt == 0L)
            }.sortedBy { it.path }
        }

    override fun getFilesOfConversationFlow(conversationId: String): Flow<List<MemoryFileEntity>> =
        files.map { all ->
            all.filter {
                it.conversationId == conversationId && (it.invalidatedAt == null || it.invalidatedAt == 0L)
            }.sortedBy { it.path }
        }

    override suspend fun getFilesOfConversation(conversationId: String): List<MemoryFileEntity> =
        files.value.filter {
            it.conversationId == conversationId && (it.invalidatedAt == null || it.invalidatedAt == 0L)
        }.sortedBy { it.path }

    override suspend fun deleteFilesOfConversation(conversationId: String) {
        files.value = files.value.filterNot { it.conversationId == conversationId }
    }

    // ═══ v5.3.14 多形态 path 匹配（历史数据兼容）═══

    override suspend fun invalidateAny(assistantId: String, paths: List<String>, at: Long?): Int {
        var hit = 0
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path in paths) {
                hit++
                it.copy(invalidatedAt = at)
            } else {
                it
            }
        }
        return hit
    }

    override suspend fun deleteFileAny(assistantId: String, paths: List<String>) {
        files.value = files.value.filterNot { it.assistantId == assistantId && it.path in paths }
    }

    override suspend fun updateScopeAny(
        assistantId: String,
        paths: List<String>,
        folderId: String?,
        conversationId: String?,
        at: Long,
    ): Int {
        var hit = 0
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path in paths) {
                hit++
                it.copy(folderId = folderId, conversationId = conversationId, updatedAt = at)
            } else {
                it
            }
        }
        return hit
    }

    override suspend fun updateContentAny(
        assistantId: String,
        paths: List<String>,
        content: String,
        at: Long,
    ): Int {
        var hit = 0
        files.value = files.value.map {
            if (it.assistantId == assistantId && it.path in paths) {
                hit++
                it.copy(content = content, updatedAt = at)
            } else {
                it
            }
        }
        return hit
    }

}
