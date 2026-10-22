/* 【域 E·设置体系】 — 页面 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.assistant.detail

/* ───【原版对齐】AssistantDetailVM.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    /** 我方记忆 1.0：助手级记忆条目（「记忆」Tab 列表与管理用；与写入桶同口径）。 */
    val assistantMemories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    /** v5.0.25：助手名下全部项目包记忆（记忆页「项目包」范围） */
    val folderMemories = assistant
        .map { it.id }
        .flatMapLatest { id -> memoryRepository.getFolderMemoriesOfAssistantFlow(id.toString()) }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    /** v4.8.92 / v5.0.25：助手名下全部单对话记忆（记忆页「单对话」范围） */
    val conversationMemories = assistant
        .flatMapLatest { currentAssistant ->
            val scopeId = if (currentAssistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.getConversationMemoriesOfAssistantFlow(scopeId)
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    /**
     * v5.0.67 ★用户定案：「项目包记忆」不是独立存储层，而是
     * 「该对话 + 归属同一项目包的所有对话」的**对话级记忆集合**。
     * 这里给出该助手名下所有归入项目包的对话 id，供记忆页 FOLDER 视图过滤。
     */
    val folderConversationIds: StateFlow<Set<String>> = assistant
        .flatMapLatest { current ->
            flow {
                val ids = runCatching {
                    conversationRepository.getConversationIdsInFolders(current.id)
                }.getOrDefault(emptyList())
                emit(ids.map { it.toString() }.toSet())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptySet()
        )

    /** 上游 3.0.0 记忆 2.0：Markdown 记忆文件库（FILES Tab）。 */
    val memories = assistant
        .flatMapLatest { currentAssistant ->
            memoryRepository.getFilesFlow(memoryIdOf(currentAssistant))
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    /** 上游 3.0.0：记忆整理（"做梦"）调度状态。 */
    val memoryConsolidation: StateFlow<MemoryConsolidationStatus> = assistant
        .flatMapLatest { currentAssistant ->
            memoryConsolidationScheduler.statusOf(memoryIdOf(currentAssistant))
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly,
            initialValue = MemoryConsolidationStatus()
        )

    /** v4.8.92: 对话标题（来源标签/分组头展示；已删除对话返回 null）。 */
    suspend fun conversationTitle(conversationId: String): String? = runCatching {
        conversationRepository.getConversationById(Uuid.parse(conversationId))?.title
    }.getOrNull()

    /** v4.8.92: 创建单对话记忆时的归属对话候选。 */
    suspend fun recentConversations() = runCatching {
        conversationRepository.getRecentConversations(assistantId, limit = 50)
    }.getOrDefault(emptyList())

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistantTags = tags
                )
            )
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    /** v4.8.121: 显示设置写入口 (「代码块内自动渲染」等全局 displaySetting 开关) */
    fun updateDisplaySetting(displaySetting: me.rerere.rikkahub.data.datastore.DisplaySetting) {
        viewModelScope.launch {
            val current = settings.value
            settingsStore.update(
                settings = current.copy(
                    displaySetting = displaySetting
                )
            )
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            val settings = settings.value
            val validTagIds = settings.assistantTags.map { it.id }.toSet()

            // 清理 assistant 中的无效 tag id
            val cleanedAssistants = settings.assistants.map { assistant ->
                val validTags = assistant.tags.filter { tagId ->
                    validTagIds.contains(tagId)
                }
                if (validTags.size != assistant.tags.size) {
                    assistant.copy(tags = validTags)
                } else {
                    assistant
                }
            }

            // 获取清理后的 assistant 中使用的 tag id
            val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

            // 清理未使用的 tags
            val cleanedTags = settings.assistantTags.filter { tag ->
                usedTagIds.contains(tag.id)
            }

            // 检查是否需要更新
            val needUpdateAssistants = cleanedAssistants != settings.assistants
            val needUpdateTags = cleanedTags.size != settings.assistantTags.size

            if (needUpdateAssistants || needUpdateTags) {
                settingsStore.update(
                    settings = settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            checkAvatarDelete(old = it, new = assistant) // 删除旧头像
                            checkBackgroundDelete(old = it, new = assistant) // 删除旧背景
                            assistant
                        } else {
                            it
                        }
                    })
            )
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val memoryAssistantId = if (assistant.value.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistantId.toString()
            }
            memoryRepository.addMemory(
                assistantId = memoryAssistantId,
                content = memory.content,
                // v4.8.92: 单对话记忆 —— 在「单对话记忆」视图创建时带归属对话；
                // 助手级记忆记录来源对话（UI 小标签）。
                conversationId = memory.conversationId,
                sourceConversationId = memory.conversationId ?: memory.sourceConversationId,
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.updateContent(id = memory.id, content = memory.content)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }

    // 助手还没加载出来时 assistant 是个占位对象，记忆库要按页面拿到的 assistantId 来定位
    private fun memoryIdOf(assistant: Assistant) = if (assistant.useGlobalMemory) {
        MemoryRepository.GLOBAL_MEMORY_ID
    } else {
        assistantId.toString()
    }

    /** 当前助手读写的记忆库 */
    val memoryId: String get() = memoryIdOf(assistant.value)

    /** 整理所有待整理的对话，并把整个记忆库清理一遍（上游 3.0.0） */
    fun organizeMemory() {
        memoryConsolidationScheduler.organize(memoryId)
    }

    /** 上游 3.0.0：删除记忆文件（与 AssistantMemory 版重载并存） */
    fun deleteMemory(memory: MemoryFile) {
        viewModelScope.launch {
            memoryRepository.deleteFile(memoryIdOf(assistant.value), memory.path)
        }
    }

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
