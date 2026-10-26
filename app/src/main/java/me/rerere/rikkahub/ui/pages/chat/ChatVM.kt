package me.rerere.rikkahub.ui.pages.chat


/* ───【域 A·对话核心】ChatVM.kt
 * 职责: 对话 VM — 发送/编辑/删除/压缩/重新生成/恢复位点
 * 常用改动: 发送逻辑 → handleMessageSend (v4.8.123: 压缩期可发送, 排队至压缩完成后自动发出); 压缩 → handleCompressContext/cancelCompress; 恢复 → restoreCompressAt
 * 问题定位: 发送无响应/压缩异常/编辑丢失 → 本文件
 * 基线: 原版移植 + 自研 (settings 投影) | 差异 +56 行 | 地图: docs/APP_MAP.md §A | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.rerere.rikkahub.data.firebase.StubAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.data.datastore.DraftStore
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flatMapLatest
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.util.Log
import kotlinx.coroutines.flow.filter

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    folderId: Uuid?,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: StubAnalytics = StubAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
    private val folderRepository: FolderRepository,
    private val draftStore: DraftStore,
    // v5.0.78：进程级 scope（预热 fire-and-forget 用；VM 销毁不中断预热）
    private val appScope: me.rerere.rikkahub.AppScope,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    // v4.8.59: 空对话"开启时归属"快照 — 对话打开那一刻的项目包选区。
    // 发送时优先采用: 在项目包 A 下打开的空对话始终归 A (此后切换选区不改写
    // 归属 — 用户实证根修); 在「聊天」下打开则快照为空 → 发送时跟随当前
    // 选区 (v4.8.26 流程保留: 选项目包→直接发消息→归该项目包)。
    private var packAtOpen: Uuid? = null

    // 4.8.24 项目包 CWD — 对话所属项目包的 cwd (项目包锚定; null = 未设置/聊天默认)
    // v4.8.73: 改订阅项目包行 flow — 原实现仅在会话流重发射时一次性重读, 导致
    // 设置/重置项目包 CWD 后 UI 无反应 (重置 = 写 null, 永不刷新 → "无法取消重置")。
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val conversationFolderCwd: StateFlow<String?> = conversation
        .map { it.folderId }
        .distinctUntilChanged()
        .flatMapLatest { fid ->
            if (fid == null) kotlinx.coroutines.flow.flowOf(null)
            else folderRepository.getFolderFlow(fid).map { it?.cwd }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部
    // v4.7.23: 分享/深链一次性输入参数消费标记 — 首个组合消费后置位。
    // 防止页面重建 (抽屉→设置→返回) 时重复消费 back stack 的 files/text,
    // 导致"已完成发送的分享文档幽灵重现"。
    var shareArgsConsumed by mutableStateOf(false)

    /**
     * v5.0.96 ★「输入栏凭空出现从没见过的文字」修法之一：
     * 分享回灌（其他应用把文本分享进 RinCore）的内容**不写入草稿**。
     * 旧行为：回灌 → 1s 防抖 → 存进 DraftStore → 用户切走/重启后再进这个对话，
     * 那段他从没手打过的文字就从草稿里冒出来（看起来就是"缓存文本串进输入框"）。
     * 一次性标记：只跳过紧随回灌的那一次防抖保存，用户后续手动输入照常保存。
     */
    @Volatile
    var suppressNextDraftSave: Boolean = false

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // v5.1.9：生成结束 → 若存在排队压缩则自动执行
        viewModelScope.launch {
            snapshotFlow { chatService.isConversationGenerating(_conversationId) }
                .distinctUntilChanged()
                .collect { generating ->
                    if (!generating) {
                        pendingCompression?.let { req ->
                            pendingCompression = null
                            handleCompressContext(
                                req.additionalPrompt, req.targetTokens, req.keepRecentMessages
                            )
                        }
                    }
                }
        }
        // v5.0.95 ★删除这里的 addConversationReference：
        // 它与下面的 pinConversationReference 是**同一件事**（都 = manager.acquire），
        // 而 onCleared 只 release 一次 → refCount 永久 +1 泄漏 → session 永不回收
        // （长期运行内存增长，且"空闲回收"形同虚设）。
        // 唯一引用点 = pinConversationReference / removeConversationReference 配对。

        // v4.8.70: 会话草稿持久化 — VM 重建/进程重启后恢复 (仅输入为空时注入);
        // 输入变化 1s 防抖落盘 (collectLatest 取消式, 连续输入只落最终值); onCleared 兜底 flush
        viewModelScope.launch {
            val draft = draftStore.load(_conversationId)
            if (draft.isNotEmpty() && inputState.textContent.text.isBlank() &&
                inputState.messageContent.isEmpty() && !inputState.isEditing()
            ) {
                inputState.setMessageText(draft)
            }
        }
        viewModelScope.launch {
            snapshotFlow { inputState.textContent.text.toString() }
                .collectLatest { text ->
                    // v5.0.72: 每次变化先同步非 Compose 镜像（onCleared 只认它）
                    inputState.syncMirror()
                    if (text.isEmpty()) return@collectLatest
                    // v5.0.96：分享回灌的那一次不落草稿（见 suppressNextDraftSave 注释）
                    if (suppressNextDraftSave) {
                        suppressNextDraftSave = false
                        return@collectLatest
                    }
                    delay(1000)
                    draftStore.save(_conversationId, text)
                }
        }
        // v5.0.78 ★触发式沙箱预热（用户指令"改为每次开始在输入框中输入的时候进行一次"）：
        // 触发点 = "从空到非空"的**输入沿**（distinctUntilChanged，不逐键触发）；
        // 预热器内部再带 60s 防抖窗（proot 走热是秒级 IO，频繁重复毫无意义，60s 内合并）。
        // 旧启动预热（App.onCreate）已删 —— 启动期抢 IO，用户实测拖慢启动且收益有限。
        viewModelScope.launch {
            // v5.0.78 ★触发式沙箱预热（用户指令"改为每次开始在输入框中输入的时候进行一次"）
            snapshotFlow { inputState.latestText.isNotEmpty() }
                .distinctUntilChanged()
                .collect { hasText ->
                    if (hasText) { /* v5.0.92: 预热已改到输入栏打开时（见 warmProviderConnection） */ }
                }
        }
        // v5.0.83：持有会话引用（消 pin/unpin）—— 前台期间 session 绝不被空闲回收，
        // flow 绑定稳定；离开页面（onCleared）由 removeConversationReference 配对释放。
        chatService.pinConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            // v5.0.95 ★初始化必须有兜底：旧实现是裸 launch —— 一旦 initialize 抛异常，
            // 这条加载链就静默断掉（没有日志、没有重试），UI 永远停在空壳星轨页。
            runCatching { chatService.initializeConversation(_conversationId, folderId) }
                .onFailure { e ->
                    Log.e(TAG, "initializeConversation(${_conversationId}) failed, retry once", e)
                    kotlinx.coroutines.delay(600)
                    runCatching { chatService.initializeConversation(_conversationId, folderId) }
                        .onFailure { e2 ->
                            Log.e(TAG, "initializeConversation(${_conversationId}) retry failed", e2)
                        }
                }
        }

        // v5.0.95 ★空态自愈（一次性）：会话被判定已加载却 0 条消息，而 DB 明确有节点
        // → 强制重载一次。这是"READY 空壳永久生效、只能重启"这条历史死结的兜底闭环。
        viewModelScope.launch {
            var tried = false
            conversation
                .map { it.messageNodes.isEmpty() && !it.newConversation }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    if (tried) return@collect
                    tried = true
                    kotlinx.coroutines.delay(700)
                    val total = runCatching { conversationRepo.countMessageNodes(_conversationId) }
                        .getOrDefault(0)
                    if (total > 0) {
                        Log.w(TAG, "empty-state self-heal: DB has $total nodes → force reload")
                        runCatching { chatService.reloadConversation(_conversationId) }
                            .onFailure { e -> Log.e(TAG, "self-heal reload failed", e) }
                    }
                }
        }

        // v4.8.59: 捕获"开启时归属" (仅一次; 待 DB 加载完成判定可靠 — 加载
        // 窗口期的空初始值不可作判据, 与 v4.8.27 双重守卫同思路)
        viewModelScope.launch {
            val conv = conversation.first { chatService.isConversationInitialized(it.id) }
            if (conv.messageNodes.isEmpty() && conv.folderId == null) {
                packAtOpen = ProjectPackSelection.selectedFolderId.value
            }
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    /** v5.0.72：发送成功后**显式**作废草稿（唯一合法的删除时机之一） */
    fun clearDraft() {
        draftStore.saveNow(_conversationId, "")
        viewModelScope.launch { draftStore.clear(_conversationId) }
    }

    override fun onCleared() {
        // v5.0.72 ★不再读 TextFieldState（Compose 快照状态在非 Compose 上下文不可靠，
        // 旧实现读到空串 → DraftStore.save("") 曾等价 clear → 用户草稿被自己删掉）。
        // 改为读 ChatInputState.latestText 这份纯 Kotlin 镜像；空串本身也不会再删草稿。
        draftStore.saveNow(_conversationId, inputState.latestText)
        voiceSession.stop()
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索
    val enableWebSearch = settings.map {
        it.enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ==== 上游 3.0.0 ChatPage 接口面（v5.0.16：ChatPage 整件采用上游原文，VM 面补齐）====
    // 会话视角下的助手：会话开始后，模型、思考级别、搜索等以会话上固定的配置为准
    val assistant: StateFlow<Assistant> = combine(settings, conversation) { s, c ->
        s.getAssistantOf(c)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value.getAssistantOf(conversation.value))

    fun selectMessageNode(nodeId: Uuid, selectIndex: Int) {
        viewModelScope.launch {
            runCatching { chatService.selectMessageNode(_conversationId, nodeId, selectIndex) }
        }
    }

    fun updateConversationSystemPrompt(systemPrompt: String?) {
        viewModelScope.launch {
            updateConversation(conversation.value.copy(customSystemPrompt = systemPrompt))
            saveConversationAsync()
        }
    }

    fun updateDraftFolder(folderId: Uuid?) {
        viewModelScope.launch {
            // v5.0.19 (B201 数据事故根因修复):
            // 旧实现 = conversation.value.copy(folderId) + saveConversationAsync()。
            // 打开对话页时 LaunchedEffect(selectedFolderId) 立即触发，此刻 session 还是
            // 空壳（Conversation.ofId，messageNodes 为空），落库经
            // ConversationRepository.updateConversation 的 deleteByConversation
            // 把该对话全部历史消息永久删除（= 用户报告的"消息被吞成新消息"）。
            // 修复：等 DB 加载完成后再写内存归属；不在此处落库（归属随发送/元数据保存写入）。
            chatService.awaitConversationLoaded(_conversationId)
            if (!chatService.isConversationInitialized(_conversationId)) return@launch
            chatService.updateConversationState(_conversationId) { current ->
                if (current.folderId == folderId) current else current.copy(folderId = folderId)
            }
        }
    }

    // 搜索恒开启 = 本地搜索（v5.0.15 用户定版：开关 UI 已移除，无切换面）
    @Suppress("UNUSED_PARAMETER")
    fun updateSearch(enableWebSearch: Boolean, builtInSearch: Boolean) = Unit

    fun updateAssistant(updated: Assistant) {
        updateSettings(
            settings.value.copy(
                assistants = settings.value.assistants.map { a -> if (a.id == updated.id) updated else a }
            )
        )
    }

    fun updateWorkspaceCwd(workspaceCwd: String?) {
        viewModelScope.launch {
            updateConversation(conversation.value.copy(workspaceCwd = workspaceCwd))
            saveConversationAsync()
        }
    }

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 4.1.0: 消息发送队列 (2.5.0 移植)
    val messageQueue = chatService.getMessageQueueFlow(_conversationId)

    fun removeQueuedMessage(id: Uuid) = chatService.removeQueuedMessage(_conversationId, id)

    fun beginEditQueuedMessage(id: Uuid) = chatService.beginEditQueuedMessage(_conversationId, id)

    fun finishEditQueuedMessage(id: Uuid, parts: List<UIMessagePart>?) =
        chatService.finishEditQueuedMessage(_conversationId, id, parts)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    /**
     * v3.11.30: 延迟自动回复开关 — 立即同步写内存 (消除 toggle→发送 的
     * flow 更新竞态, 修复"有概率拦截失败"), 磁盘持久化异步进行。
     */
    fun toggleDeferAutoReply(checked: Boolean) {
        val current = settingsStore.settingsFlow.value
        if (current.deferAutoReply == checked) return
        settingsStore.updateSync(current.copy(deferAutoReply = checked))
        viewModelScope.launch {
            runCatching { settingsStore.update(settingsStore.settingsFlow.value) }
        }
    }

    // 更新设置
    /** 上游 3.0.0 页面用 lambda 形态（读-改-写原子）。 */
    fun updateSettings(fn: (Settings) -> Settings): Job {
        return updateSettings(fn(settingsStore.settingsFlow.value))
    }

    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    /** 上游 3.0.0 页面用单参形态（会话级模型切换）。 */
    fun setChatModel(model: Model) {
        viewModelScope.launch {
            val conv = conversation.value
            val cfg = conv.config ?: ConversationConfig()
            updateConversation(conv.copy(config = cfg.copy(chatModelId = model.id)))
            saveConversationAsync()
        }
    }

    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                // v5.1.5 ★核验发现：选真实模型必须**同时清零路由**，
                                // 否则 UI 显示新模型、实际仍走快速/极致池（用户会以为没生效）。
                                modelRouting = 0,
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    /**
     * v5.0.92 ★预热入口（用户定案：每次打开输入栏就开始预热）。
     *
     * 委托既有 `ProviderWarmup.warmCurrentChatModel()`：
     *  - 它走 `ConnectionWarmer.ensureProviderKeepAliveAuto`，自动选池
     *    （opencode.ai / api.commandcode.ai → 与主请求同一个 `opencodeClient` 长保活池）；
     *  - 幂等：同 baseUrl 已有心跳 job 直接返回，不会重复占进程；
     *  - 首 ping 立即执行（"先 ping 后计时"），不是等 60s。
     *
     * 绝不在发送时调用：生成前预热会与主请求同 key 串行化（v3.12.6/v4.1.3 已证伪）。
     */
    fun warmProviderConnection() {
        me.rerere.rikkahub.service.ProviderWarmup.warmCurrentChatModel()
    }

    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        // v5.0.92 ★发送时**不再**预热：v4.1.3 已实证"生成前预热"与主请求同 key 会被
        // 网关串行化，预热挂在慢网关上时主请求反而排队等它（v3.12.6 用户实测）。
        // 连接新鲜度由 ConnectionWarmer 的 60s 常驻心跳 + 输入栏打开时的即时刷新保证。
        // v5.0.90 ★沙箱预热改在"发送之后"（用户实证：旧输入沿预热与首字请求抢 IO，
        // 预热没什么用还越等越久）。此时 LLM 请求已发出，proot/officecli 走热与
        // 服务端 TTFB 并行，只为"首个工具调用"服务，不再影响首字。
        me.rerere.rikkahub.service.SandboxPrewarmer.warmOnTypingStartAsync(appScope)
        // v4.8.123 (用户定版): 压缩期可发送 — 与模型输出期同款排队"等待中"
        // (派发门在压缩期挂起; 压缩完成由 ChatService.runCompression 统一派发)
        analytics.logEvent("ai_send_message", null)

        // v3.6.13: 延迟自动回复 — 开启时发送不触发模型回复 (消息排队,
        // 关闭后发消息触发; 解决消息未发完模型打断回复)
        // v3.11.30: 双保险 — 开关切换已同步写内存 (见 toggleDeferAutoReply),
        // 这里再读一次 flow.value 保证最新值。仍读旧值期间不可能出现:
        // updateSync 在主线程同步完成, 同线程后续消息必见新值。
        val effectiveAnswer = if (settingsStore.settingsFlow.value.deferAutoReply) false else answer
        // 4.8.27: 空对话归属同步 — 发送时执行 (判定可靠; 根治 v4.8.26 加载
        // 窗口期误判: 误判会抢先写 session 致 DB 加载被丢弃)。
        // 守卫: 会话须已完成 DB 加载 (isConversationInitialized) — 加载中
        // 的 state 是空对话初始值, 不可据此判定归属 (极端边缘防护)。
        // 归属更新完成后才入队发送, 保证生成链读取正确 folderId (effectiveWorkspaceCwd)。
        val conv = conversation.value
        // v4.8.59: 归属目标 — 空对话优先"开启时快照" (在项目包下打开的空对话
        // 始终归该项目包, 之后切换选区不改写 — 用户实证根修); 快照为空 (聊天
        // 下打开) 或已有归属时维持既有逻辑 (当前选区 / 已定归属不改)。
        val targetFolder = if (conv.folderId == null) {
            // v5.0.97 ★归属校验（根修二）：选区是全局单例，可能残留上一个助手的项目包。
            // 只有"归属未知"或"确属当前助手"的包才采用，否则视为未选包（落到助手级）。
            val candidate = packAtOpen ?: ProjectPackSelection.selectedFolderId.value
            candidate?.takeIf { folderId ->
                ProjectPackSelection.ownerOf(folderId)?.let { it == conv.assistantId } ?: true
            }
        } else {
            conv.folderId
        }
        val shouldSyncFolder = chatService.isConversationInitialized(conv.id) &&
            conv.messageNodes.isEmpty() && conv.folderId != targetFolder
        if (shouldSyncFolder) {
            viewModelScope.launch {
                chatService.moveConversationToFolder(conv.id, targetFolder)
                chatService.sendMessage(_conversationId, content, effectiveAnswer)
            }
        } else {
            chatService.sendMessage(_conversationId, content, effectiveAnswer)
        }
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        // v4.5.16: 延时自动回复拦截补全 — 编辑消息此前绕过 deferAutoReply
        // gate 立即触发重新生成 (开关开着模型照样回复, 用户感知"拦截不
        // 彻底")。开启时仅保存编辑版本, 不自动生成。
        val deferAutoReply = settingsStore.settingsFlow.value.deferAutoReply

        viewModelScope.launch {
            // 1. 追加新版本 (旧版本保留, 123 按钮可切换)
            chatService.editMessage(_conversationId, messageId, parts)
            // 2. 直接触发重新生成 — 编辑完成即开始, 无需再点重新生成。
            //    编辑后追加的新版本 id 与 messageId 不同, 须在全量消息中定位
            //    (currentMessages 只含每节点当前选中消息, 找不到旧 id)
            val edited = conversation.value.messageNodes
                .flatMap { it.messages }
                .find { it.id == messageId } ?: return@launch
            // v3.5.53: 仅编辑 user 消息触发重新生成 — assistant 回复编辑只保存
            if (edited.role == me.rerere.ai.core.MessageRole.USER && !deferAutoReply) {
                chatService.regenerateAtMessage(_conversationId, edited)
            }
        }
    }

    // v4.8.6: 压缩进行中状态 — 页内提示用 (压缩弹窗关闭后"后台运行"语义下,
    // 用户回来仍可见压缩仍在进行; 只随本 VM (本对话) 存在, 切走其他页面自由)
    private val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    // v4.8.8: 压缩任务引用 — 供页内提示条取消
    private var compressJob: kotlinx.coroutines.Job? = null

    /** 上游 3.0.0 页面用单参形态（CompressContextDialog 上游逐字调用点）。
     *  targetTokens=4000（我方默认档）、keepRecentMessages=0（自动推荐保留）。 */
    fun handleCompressContext(additionalPrompt: String): Job =
        handleCompressContext(additionalPrompt, 4000, 0)

    /** v5.1.9（用户要求）：压缩任务的排队请求 */
    data class PendingCompression(
        val additionalPrompt: String,
        val targetTokens: Int,
        val keepRecentMessages: Int,
    )

    /** v5.1.9：生成中的压缩不报错，改为排队；生成结束后自动执行 */
    var pendingCompression by androidx.compose.runtime.mutableStateOf<PendingCompression?>(null)
        private set

    /** v5.1.9：取消排队的压缩 */
    fun cancelPendingCompression() {
        pendingCompression = null
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        // v5.1.9（用户要求）★原来生成中直接拒绝（v4.8.32 用户定版）；
        // 现在改为**排队等候**：输出结束自动执行（排队 UI 在输入框上方，玻璃同材质）。
        if (chatService.isConversationGenerating(_conversationId)) {
            pendingCompression = PendingCompression(additionalPrompt, targetTokens, keepRecentMessages)
            return viewModelScope.launch { /* 已入队，生成结束后执行 */ }
        }
        val job = viewModelScope.launch {
            _compressing.value = true
            try {
                chatService.runCompression(
                    _conversationId,
                    conversation.value,
                    additionalPrompt,
                    targetTokens,
                    keepRecentMessages
                ).onFailure {
                    chatService.addError(it, title = context.getString(R.string.error_title_compress_conversation))
                }
            } finally {
                _compressing.value = false
                compressJob = null
            }
        }
        compressJob = job
        return job
    }

    /** v4.8.8: 取消进行中的压缩 (页内提示条取消按钮) */
    fun cancelCompress() {
        compressJob?.cancel()
        compressJob = null
        _compressing.value = false
    }

    // v3.8.13: 从留存位点恢复 (旧单次撤销改为位点管理)
    fun restoreCompressAt(index: Int) {
        viewModelScope.launch {
            val conv = conversation.value
            if (conv.compressRetentions.isNotEmpty() || conv.compressedContext != null) {
                val migrated = if (conv.compressRetentions.isEmpty()) conv.migrateLegacyCompress() else conv
                chatService.saveConversation(_conversationId, migrated.restoreCompressAt(index))
            }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            // v5.1.7 ★删除链路原来 failIfMissing=false → 找不到消息时**静默什么都不做**
            // （用户点了删除毫无反应）。现在失败必须说出来。
            val result = runCatching { chatService.deleteMessage(_conversationId, message) }
            result.exceptionOrNull()?.let { e ->
                chatService.addError(
                    error = e,
                    conversationId = _conversationId,
                    title = "删除消息失败",
                )
            }
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        // v5.0.76：落库以 session 内存态为唯一权威（persistConversationState 锁内读、锁内写）。
        // 不再把 UI 层 flow 的快照对象交给 saveConversation —— 传播延迟下的旧快照
        // 会把刚 apply 的归属/消息覆盖回旧值（切助手后记录消失的根因）。
        viewModelScope.launch {
            chatService.persistConversationState(_conversationId)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id)
        }
    }


    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            // v5.0.24 空对话修复（v4.8.148 教训）：未入库的空会话 getConversationById 返回 null，
            // 旧实现直接 return → 双星轨点击「点亮但没切换」。改为：DB 缺失时以内存态为基线。
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: conversation
            // 文件夹是助手内分组，切换助手后原文件夹在新助手下不可见，需清空归属避免会话丢失
            val updatedConversation = conversationFull.copy(
                assistantId = targetAssistantId,
                folderId = null,
            )
            if (conversation.id == _conversationId) {
                // 空会话不入库：只更新内存态；发送时随机制落库
                chatService.updateConversationState(_conversationId) { updatedConversation }
                settingsStore.updateAssistant(targetAssistantId)
            } else {
                conversationRepo.updateConversation(updatedConversation)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
