/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

/* ───【原版对齐】ChatDrawerVM.kt | 差异 ±31 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/


import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.utils.toLocalString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val favoriteDAO: me.rerere.rikkahub.data.db.dao.FavoriteDAO,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    // v3.8.15: 清理聊天内容 — 删除早于 cutoff 的非置顶无收藏对话
    fun cleanupConversations(cutoffEpochMs: Long, onDone: (removed: Int, skipped: Int) -> Unit) {
        viewModelScope.launch {
            val candidates = conversationRepo.getConversationsEligibleForCleanup(cutoffEpochMs)
            var removed = 0
            var skipped = 0
            candidates.forEach { conv ->
                val hasFavorite = runCatching {
                    favoriteDAO.getFavoriteNodeIdsOfConversation(conv.id.toString()).isNotEmpty()
                }.getOrDefault(false)
                if (hasFavorite) {
                    skipped++
                    return@forEach
                }
                runCatching { conversationRepo.deleteConversation(conv) }
                    .onSuccess { removed++ }
                    .onFailure { skipped++ }
            }
            onDone(removed, skipped)
        }
    }

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    // 当前选中的项目包，null 表示「聊天」视图。
    // 4.8.26: SavedStateHandle 持久化 — Activity 重建 (切后台被系统回收/配置变化)
    // 后选区不丢失 (用户实证: 切后台回来从项目包跳回聊天); 全新启动无 saved
    // state, 仍默认「聊天」。
    private val _selectedFolderId = MutableStateFlow(
        savedStateHandle.get<String>("selectedFolderId")
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    )
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // v4.8.58: 项目包点击统计 (智能排序数据源) — Settings 持久化 JSON (重启不丢)
    val packClickStatsJson: StateFlow<String> = settingsStore.settingsFlow
        .map { it.projectPackClickStats }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // v4.8.62 (用户定版): 项目包绿色任务标记 — 有正在执行任务的对话的项目包集合。
    // 任务流变化 (开始/结束) 与目录变化即刷新; 包内核查走会话内存快照 (权威)。
    val packsWithRunning: StateFlow<Set<Uuid>> = combine(
        chatService.getConversationJobs(),
        folders,
    ) { _, foldersNow ->
        foldersNow.map { it.id }.filter { chatService.hasGeneratingConversationInFolder(it) }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(assistantIdFlow, _selectedFolderId) { assistantId, folderId ->
            assistantId to folderId
        }
            .flatMapLatest { (assistantId, folderId) ->
                if (folderId == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(folderId)
                }
            }
            .map { pagingData ->
                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                        when {
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                } else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 4.8.27: 同步全局选区状态 (跨 VM 只读 — ChatVM 发送时归属同步用)
        ProjectPackSelection.selectedFolderId.value = _selectedFolderId.value
        // v4.8.62: 全新进入 (无恢复选区) → 运行落地逻辑
        if (_selectedFolderId.value == null) runLandingCheck()
        // 助手切换时重置项目包选区，回到「聊天」视图，
        // 避免继续显示上一个助手项目包内的会话（项目包是助手内分组）。
        // 4.8.26: drop(1) — 跳过首次发射 (VM 创建/重建时的当前值), 仅响应
        // 助手"切换"事件; 原实现首次发射也重置, 致 Activity 重建后选区丢失。
        viewModelScope.launch {
            assistantIdFlow.drop(1).collect {
                _selectedFolderId.value = null
                savedStateHandle["selectedFolderId"] = null
                ProjectPackSelection.selectedFolderId.value = null
                // v4.8.62: 助手切换 → 对新助手运行落地逻辑
                runLandingCheck()
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    fun selectFolder(folderId: Uuid?) {
        applySelection(folderId)
        // v4.8.58: 智能排序 — 真实记录点击 (仅项目包; 「聊天」不计)
        if (folderId != null) recordPackClick(folderId)
    }

    /** v4.8.62: 选区应用 (与点击统计分离 — 落地逻辑复用, 非用户点击不计) */
    private fun applySelection(folderId: Uuid?) {
        _selectedFolderId.value = folderId
        savedStateHandle["selectedFolderId"] = folderId?.toString()
        ProjectPackSelection.selectedFolderId.value = folderId
    }

    /** v4.8.58/63: 记录一次项目包点击 (真统计) — 追加时间戳 + 过期剪枝。
     *  v4.8.63 修复: 不再以"当时列表"做目录存在性删除 (瞬时空列表曾全量误删);
     *  目录存在性删除统一下放到周期剪枝 (以全助手全量目录为全集)。 */
    fun recordPackClick(folderId: Uuid) {
        viewModelScope.launch {
            runCatching {
                settingsStore.update { s ->
                    s.copy(projectPackClickStats = bumpPackStats(s.projectPackClickStats, folderId))
                }
            }
        }
    }

    /** v4.8.58/63: 周期剪枝入口 — 目录变更/进入抽屉时调用。
     *  v4.8.63 修复: 目录全集 = 全部助手的全部项目包 (此前用当前助手目录 →
     *  切换助手时误删其他助手的历史统计 → 排序静默回退, 用户感知"没有持久化");
     *  至有过期/僵尸条目才写回, 无变化零写入 (不打扰 settings 修订号)。 */
    fun prunePackStats() {
        viewModelScope.launch {
            runCatching {
                val validIds = folderRepo.getAllFolderIds()
                val current = settingsStore.settingsFlow.first().projectPackClickStats
                val pruned = prunePackStatsJson(current, validIds)
                if (pruned != current) {
                    settingsStore.update { s ->
                        if (s.projectPackClickStats == pruned) s
                        else s.copy(projectPackClickStats = pruned)
                    }
                }
            }
        }
    }

    // v4.8.62 (用户定版): 进入助手时的落地逻辑 —
    //   ① 存在未归类(聊天)对话 或 无项目包 → 一切正常 (不干预);
    //   ② 无未归类对话且存在项目包: 唯一项目包有正在执行任务的对话 → 直接选中
    //      该项目包 (显示其对话列表); 否则 → 进入项目包选择 (抽屉展开态)。
    // 触发: 全新进入 (无恢复选区) 与助手切换; Activity 重建不重复触发。
    private val _landingExpand = MutableStateFlow<Boolean?>(null)
    val landingExpand: StateFlow<Boolean?> = _landingExpand.asStateFlow()

    private fun runLandingCheck() {
        viewModelScope.launch {
            runCatching {
                val assistantId = assistantIdFlow.first()
                val foldersNow = folderRepo.getFoldersOfAssistant(assistantId).first()
                if (foldersNow.isEmpty()) {
                    _landingExpand.value = null
                    return@runCatching
                }
                val unfiled = conversationRepo.countUnfiledConversationsOfAssistant(assistantId)
                if (unfiled > 0) {
                    _landingExpand.value = null
                    return@runCatching
                }
                val running = foldersNow.filter { chatService.hasGeneratingConversationInFolder(it.id) }
                if (running.size == 1) {
                    applySelection(running[0].id) // 非用户点击, 不计智能排序统计
                    _landingExpand.value = false
                } else {
                    _landingExpand.value = true
                }
            }
        }
    }

    fun createFolder(name: String, cwd: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed, cwd)
        }
    }

    /** 4.8.24: 更新项目包 CWD (项目包锚定目录)。 */
    fun updateFolderCwd(folderId: Uuid, cwd: String?) {
        viewModelScope.launch {
            folderRepo.updateCwd(folderId, cwd)
        }
    }

    /**
     * v4.8.77: 项目包移出为助手 — 新助手继承源助手全部配置, 名称=项目包名,
     * CWD=包 CWD (未设置则随源助手 — 转移前后 CWD 绑定不变);
     * 包内全部对话转移为新助手对话记录, 原助手不再保留该项目包。
     * 生成中的包拒绝操作 (与删除同守卫)。
     */
    fun extractFolderAsAssistant(folder: Folder): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folder.id)) return false
        viewModelScope.launch {
            val settings = settingsStore.settingsFlow.first()
            val source = settings.getAssistantById(folder.assistantId) ?: settings.getCurrentAssistant()
            val newAssistant = source.copy(
                id = Uuid.random(),
                name = folder.name,
                workspaceCwd = folder.cwd ?: source.workspaceCwd,
            )
            settingsStore.update(
                settings.copy(assistants = settings.assistants + newAssistant)
            )
            chatService.extractFolderAsAssistant(folder.id, newAssistant.id)
            if (_selectedFolderId.value == folder.id) {
                _selectedFolderId.value = null
                savedStateHandle["selectedFolderId"] = null
                ProjectPackSelection.selectedFolderId.value = null
            }
        }
        return true
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
                savedStateHandle["selectedFolderId"] = null
                ProjectPackSelection.selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}

/* ===================== v4.8.58/63: 项目包点击统计与智能排序 ===================== */

/** v4.8.63 (用户定版): 统计窗口 — "最多 3 天内的数据"按北京时间 (Asia/Shanghai)
 *  自然日计: 保留 今天 + 前 2 天的全部点击 (cutoff = 前天 00:00 北京时间)。
 *  数据源 = Settings 持久化 (DataStore projectPackClickStats), 非内存缓存。 */
private val PACK_STATS_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Shanghai")

private fun packStatsCutoff(nowMs: Long): Long =
    java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), PACK_STATS_ZONE)
        .toLocalDate()
        .minusDays(2)
        .atStartOfDay(PACK_STATS_ZONE)
        .toInstant()
        .toEpochMilli()

/** 解析统计 JSON: {"<folderUuid>":[epochMs,...]} — 容错, 坏数据返回空表 */
internal fun parsePackStats(json: String): MutableMap<String, MutableList<Long>> {
    if (json.isBlank()) return mutableMapOf()
    return runCatching {
        val obj = Json.parseToJsonElement(json) as? JsonObject ?: return mutableMapOf()
        val out = mutableMapOf<String, MutableList<Long>>()
        for ((k, v) in obj) {
            val arr = v as? kotlinx.serialization.json.JsonArray ?: continue
            val list = arr.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }.toMutableList()
            if (list.isNotEmpty()) out[k] = list
        }
        out
    }.getOrDefault(mutableMapOf())
}

internal fun encodePackStats(map: Map<String, List<Long>>): String {
    if (map.isEmpty()) return ""
    return buildJsonObject {
        for ((k, list) in map) {
            put(k, buildJsonArray { list.forEach { add(JsonPrimitive(it)) } })
        }
    }.toString()
}

/** 剪枝 (编码前): 过期事件删除 (北京时间 3 天窗口) + 已删目录条目移除 + 空条目移除。
 *  v4.8.63: validFolderIds 为空 = "目录集合未知" (瞬时态) — 绝不按存在性删数据;
 *  传入非空时必须是"全部助手的全量目录" (跨助手不误删)。 */
private fun pruneParsedStats(
    json: String,
    validFolderIds: Set<Uuid>,
    nowMs: Long,
): MutableMap<String, MutableList<Long>> {
    val cutoff = packStatsCutoff(nowMs)
    val out = mutableMapOf<String, MutableList<Long>>()
    for ((k, list) in parsePackStats(json)) {
        val kept = list.filter { it >= cutoff }
        if (kept.isEmpty()) continue
        if (validFolderIds.isNotEmpty()) {
            val id = runCatching { Uuid.parse(k) }.getOrNull() ?: continue
            if (id !in validFolderIds) continue
        }
        out[k] = kept.sorted().toMutableList()
    }
    return out
}

/** 点击记录: 过期剪枝 (纯时间窗口) + 追加当前时间戳。
 *  v4.8.63: 不做目录存在性删除 (防瞬时列表误删); 该职责归周期剪枝 (全量目录全集)。 */
internal fun bumpPackStats(
    currentJson: String,
    folderId: Uuid,
    nowMs: Long = System.currentTimeMillis(),
): String {
    val parsed = pruneParsedStats(currentJson, emptySet(), nowMs)
    val key = folderId.toString()
    val list = parsed[key] ?: mutableListOf()
    list.add(nowMs)
    parsed[key] = list
    return encodePackStats(parsed)
}

/** 剪枝入口 (无变化也返回等值字符串, 调用方据此判断是否写回) */
internal fun prunePackStatsJson(
    currentJson: String,
    validFolderIds: Set<Uuid>,
    nowMs: Long = System.currentTimeMillis(),
): String = encodePackStats(pruneParsedStats(currentJson, validFolderIds, nowMs))

/**
 * v4.8.58/63 (用户定版): 项目包智能推荐排序。
 * 合成顺序: ① 本次选中的项目包 (走选区缓存, 不再计算) →
 *         ② 最近 3 次被点击的项目包 (事件级去重, 每个项目包最多计一次) →
 *         ③ 3 天内 (北京时间自然日窗口) 被点击次数降序 (并列取最近一次更晚者) →
 *         ④ 其余保持默认顺序 (稳定排序)。
 * 数据源: Settings 持久化 (DataStore projectPackClickStats — 非内存缓存);
 * 只含 3 天窗口内事件 (过期在写入/剪枝时真删)。
 */
internal fun rankProjectPacks(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    statsJson: String,
    nowMs: Long = System.currentTimeMillis(),
): List<Folder> {
    if (folders.size <= 1) return folders
    val cutoff = packStatsCutoff(nowMs)
    val existing = folders.map { it.id }.toSet()
    data class Ev(val id: Uuid, val ts: Long)
    val events = parsePackStats(statsJson).entries.flatMap { (k, list) ->
        val id = runCatching { Uuid.parse(k) }.getOrNull() ?: return@flatMap emptyList()
        list.filter { it >= cutoff }.map { Ev(id, it) }
    }.sortedByDescending { it.ts }

    // ② 最近 3 次 (去重)
    val tier2 = LinkedHashSet<Uuid>()
    for (e in events) {
        if (e.id in existing) tier2.add(e.id)
        if (tier2.size >= 3) break
    }
    // ③ 3 天频次 (tie: 最近一次更晚优先)
    val counts = HashMap<Uuid, Int>()
    val lastTs = HashMap<Uuid, Long>()
    for (e in events) {
        counts[e.id] = (counts[e.id] ?: 0) + 1
        if ((lastTs[e.id] ?: 0L) < e.ts) lastTs[e.id] = e.ts
    }
    val tier3 = folders.map { it.id }
        .filter { (counts[it] ?: 0) > 0 }
        .sortedWith(
            compareByDescending<Uuid> { counts[it] ?: 0 }
                .thenByDescending { lastTs[it] ?: 0L }
        )

    // 合成: 去重放置, 未命中者按默认顺序稳定排在后面
    val order = LinkedHashMap<Uuid, Int>()
    fun place(id: Uuid) {
        if (id !in order) order[id] = order.size
    }
    selectedFolderId?.let { place(it) }   // ① 本次选中 (缓存)
    tier2.forEach { place(it) }           // ② 最近 3 次
    tier3.forEach { place(it) }           // ③ 3 天频次
    return folders.sortedBy { order[it.id] ?: Int.MAX_VALUE }
}

