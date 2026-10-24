/**
/* ───【技术债审计 v3.19.0】
 * 审计结论: 异常上报 (catch → addError → UI) ✓; 无主线程阻塞/GlobalScope ✓;
 * 前台服务生命周期 (setJob/startForeground/stopSelf) ✓。残余风险: 无。
 * ───────────────────────────────────────────────────────────────*/
 * 【域 A·对话核心】会话编排服务 (地图: docs/APP_MAP.md §A)
 * 职责: 工具池构建 + 生成编排(GenerationHandler) + 流式落盘 + 审批/通知 + 压缩执行
 * 常用改动: 发送 → sendMessage; 压缩 → compressConversation; 队列 → dispatchNextQueuedMessage
 * 问题定位: 回答丢失/切后台/工具不出现 → 本文件
 * 会话编排服务 — 模块: B. 会话与存储
 *
 * 职责:
 *  - 工具池构建: local/搜索/workspace/生态/动态/技能/域/MCP 全量收集
 *  - 生成编排: 调 GenerationHandler + 流式增量落盘 (v3.4.6 保留)
 *  - 审批流/通知/会话管理
 *
 * 来源: 继承原版 + 自研 (落盘/通知/审批)
 *
 * 问题定位: 回答丢失/切后台/工具不出现 → 查本模块
 */
package me.rerere.rikkahub.service


/* ───【原版对齐】ChatService ───────────────────────────────────────────
 * 原版: 有同文件 | RinCore 差异 +339 行
 * 来源: 原版移植 + 自研强化
 * 功能: 会话编排 — 生成入口/流式收集/落盘/通知/错误
 * 特点: 1. onCompletion 收尾必须 NonCancellable (取消态也落盘+通知);
 *        2. 落盘节流 1s (每 chunk 全量写 → 每秒一次);
 *        3. 中断 3s join (v3.5.24)
 * 逻辑: updateConversation 纯内存 (StateFlow) + checkFilesDelete
 * 与原版主要差异:
 *   1. 落盘节流/兜底落盘 (原版每 chunk 写)
 *   2. 错误上下文记录 (模型/档位/消息数/工具数)
 *   3. 非法 MCP 名不弹窗只剔除 (v3.6.96)
 * ────────────────────────────────────────────────────────────────────*/

import me.rerere.rikkahub.data.ai.MemoryConsolidationScheduler
import android.app.Application
import okhttp3.OkHttpClient
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.CallTracer
import me.rerere.rikkahub.data.ai.TraceKey
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageImageSnapshotTransformer
import me.rerere.rikkahub.data.ai.transformers.CCImageCompatTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.ToolMentionTransformer
import me.rerere.rikkahub.data.ai.transformers.UploadsListingTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.localFileUrls
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.EffectiveCwd
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    // v4.8.126 (OC 合规): 对话内辅助调用 (标题/建议/压缩) 显式携带本对话 session —
    // 官方要求 "stable session ID in x-opencode-session for each conversation"
    // (含辅助请求); 不传 = 保持既有随机 session (等价官方"无状态调用"形态)
    sessionId: String? = null,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
    sessionId = sessionId ?: java.util.UUID.randomUUID().toString(),
)

private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

internal fun forkConversationTitle(sourceTitle: String, existingTitles: Set<String>): String {
    // 源标题已带 (N) 后缀时递增序号，避免多次 fork 后叠加成 xxx(1)(1)(1)
    val suffix = forkTitleSuffixRegex.find(sourceTitle)
    val baseTitle = suffix?.let { sourceTitle.removeRange(it.range) } ?: sourceTitle
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        // v4.8.91: 本对话上传清单 —— 必须在 DocumentAsPromptTransformer 之前（文档 part 会被其转换/移除）
        UploadsListingTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        // v4.3.6 (BUG15): 死代码接线 — v3.13.7 的 tool 结果图片重定位修复从未
        // 注册进 transformer 链, 一直未生效 (内部自带 ccImageCompat+user_ key
        // 双重 opt-in 检查, 注册本身无副作用)
        CCImageCompatTransformer,
        // v4.8.90: 输入栏 /@ 插入的 `@工具名` → 系统提示级"精确调用"意图（无提及零注入）
        ToolMentionTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
        // v4.8.185 (审计重写): 渲染即固化 — 生成完成后把消息内图片地址复制为
        // 对话媒体快照 (files/chat-media/<对话>/) 并重写地址; 历史消息首轮自动回填。
        MessageImageSnapshotTransformer,
    )
}

class ChatService(
    private val context: Application,
    // v3.10.5: 发送前 OkHttp 级连接预热 (TTFT 专项)
    private val httpClient: OkHttpClient,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    // 5.0.0 并树：记忆整理调度（对话后自动整理想 — 上游 3.0.0）
    private val memoryConsolidationScheduler: MemoryConsolidationScheduler,
    private val pluginManager: me.rerere.rikkahub.data.plugin.PluginManager? = null,
    private val operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider? = null,
) {
    init {
        me.rerere.rikkahub.ecosystem.tools.DynamicTools.initialize(
            mcp = mcpManager,
            workspaceRoot = context.filesDir.resolve("ecosystem").absolutePath,
            skillsRoot = skillManager.getSkillsDir().absolutePath,
            settingsStore = settingsStore,
        )
        // v3.6.110/120: 插件安装后立即刷新插件列表 (热生效, 不等重启)。
        // v3.6.120 回滚: 不再自动 registerPluginBridges — 自动桥接在部分
        // 环境破坏 MCP 连接 (用户实测大部分 MCP 无法连接), 插件 MCP 桥接
        // 退回手动 mcp_connect (与普通 STDIO MCP 同通道)
        me.rerere.rikkahub.ecosystem.tools.DynamicTools.onSkillsChanged = {
            me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry.refresh()
        }
    }

    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理 (v4.7.2: 2.5.3 ConversationSessionManager 移植 — 生命周期/引用/状态流收口)
    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        createInitialConversation = { id ->
            val settings = settingsStore.settingsFlow.value
            Conversation.ofId(
                id = id,
                assistantId = settings.getCurrentAssistant().id,
            )
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        onSessionRemoved = memoryConsolidationScheduler::onConversationLeft,
    )

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching { sessionManager.cleanup() }

    // v5.0.68 ★数据安全：per-conversation 写锁。
    // 旧 `saveConversation` 是 suspend 函数，`existsConversationById` 是挂起点 ——
    // 读快照与整对象写回之间存在窗口，两个并发保存（流式落库 + 用户编辑 / 分支切换 /
    // 队列派发）会**后写覆盖先写**，这就是"切换后消息串了/少了"的数据层来源。
    // 用 per-conversation Mutex 串行化同一对话的保存；不同对话互不阻塞（不引入全局锁）。
    private val saveLocks = java.util.concurrent.ConcurrentHashMap<Uuid, kotlinx.coroutines.sync.Mutex>()
    /** v5.0.83：UI 读取队列流时的静态空回退（避免为读取而创建空壳 session） */
    private val emptyMessageQueueFlow: kotlinx.coroutines.flow.StateFlow<MessageQueueState> =
        kotlinx.coroutines.flow.MutableStateFlow(MessageQueueState())
    private fun saveLockFor(id: Uuid): kotlinx.coroutines.sync.Mutex =
        saveLocks.getOrPut(id) { kotlinx.coroutines.sync.Mutex() }

    // ---- Session 管理 (v4.7.2: 2.5.3 ConversationSessionManager 收口) ----

    private fun onSessionGenerationFinished(session: ConversationSession, cause: Throwable?) {
        if (cause != null) session.messageQueue.pause()
        if (session.state.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) {
            session.messageQueue.failReplyWaiters(context.getString(R.string.chat_page_voice_tool_approval))
        }
        appScope.launch { dispatchNextQueuedMessage(session.id) }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessionManager.release(conversationId)
    }

    /**
     * v5.0.83：UI 持有余会话引用（配对 release）。此前 ChatPage/ChatVM 从不 acquire，
     * refCount 恒 0 → 30 分钟空闲回收**连前台正在使用、还挂着 StateFlow 绑定的会话一起
     * 回收** → UI flow 绑定旧实例、新消息进新壳 → 气泡不出现 + 发送链路失灵（用户实测
     * "切换另一包发消息全乱 + 红叉无效 + 抽屉全空" 的顽固根因之一）。
     * pin 期间绝不回收；onCleared 配对 release 后进入 30min 空闲回收。
     */
    fun pinConversationReference(conversationId: Uuid) {
        sessionManager.acquire(conversationId)
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = sessionManager.launchWithSession(conversationId) { block() }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> =
        sessionManager.getProcessingStatusFlow(conversationId)

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> = sessionManager.getConversationJobs()

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid, folderId: Uuid? = null) {
        val session = sessionManager.getOrCreate(conversationId)
        // 若 session 已有数据 (已从 DB 加载过, 或正在生成中),
        // 不再用 DB 覆盖内存态, 防止切出切回时丢失未落库的回复。
        // 2.5.3: initialize 互斥一次加载 — 加载挂起期间写入的更新状态不会被覆盖
        session.initialize {
            val conversation = conversationRepo.getConversationById(conversationId)
            if (conversation != null) {
                settingsStore.updateAssistant(conversation.assistantId)
                conversation
            } else {
                // 新建对话, 并添加预设消息 (作为 load 返回值 — initialize 内部完成状态写入)
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                Conversation.ofId(
                    id = conversationId,
                    assistantId = assistant.id,
                    newConversation = true,
                    folderId = folderId,  // 当前焦点文件夹 → 新对话默认存储位置
                ).updateCurrentMessages(assistant.presetMessages)
            }
        }
        // v5.0.83：加载完成后主动派发排队中的消息（未就绪期间 dispatch 被挡住，队列不受损）
        dispatchNextQueuedMessage(conversationId)
    }

    // ---- 发送消息 ----

    /** Enqueue immediately; the result belongs to this item even after edits or later turns. */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = sessionManager.getOrCreate(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = sessionManager.getOrCreate(conversationId)
        // Hook Engine: onMessage 事件
        me.rerere.rikkahub.ecosystem.plugin.HookEngine.dispatch(
            me.rerere.rikkahub.ecosystem.plugin.HookEngine.HookEvent(
                type = "onMessage",
                payload = mapOf("conversationId" to conversationId.toString()),
            )
        )
        synchronized(session) {
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        // v5.0.83：绝不因 UI 读取而创建空壳 session（fallback = 静态空队列）
        sessionManager.get(conversationId)?.messageQueue?.state ?: emptyMessageQueueFlow

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessionManager.get(conversationId)?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessionManager.get(conversationId)?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessionManager.get(conversationId)?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences =
                    candidates.filter { conversationRepo.hasFileReference(it) }.toSet()
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessionManager.snapshot()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessionManager.get(conversationId) ?: return null
        // v5.0.83 ★绝不向空壳派发：未就绪时消息留在队列里，等 initializeConversation
        // 完成加载后主动派发（否则 sendQueuedMessage 会读到空壳 state 拼出"空壳+1条消息"落库）
        // v5.0.84 ★自愈补全：session 存在但处于 NEW（从未加载，典型场景=会话被回收后
        // backstack 复活的 VM 再次发送 / 新页 ChatVM 尚未跑到 initializeConversation）——
        // 后台补一次加载；完成后 initializeConversation 内的补派发会把队列续上。
        // 不再存在"消息永远躺在队列里无人派发"的路径。
        if (!session.isReady) {
            if (session.phase.value == SessionPhase.NEW) {
                appScope.launch {
                    runCatching { initializeConversation(conversationId) }
                }
            }
            Log.d(TAG, "dispatch: session not ready, queue kept id=$conversationId (self-heal armed)")
            return null
        }
        synchronized(session) {
            // A pending tool approval is still part of the current turn.
            // v4.8.75 (用户定版): ask_user 等候期豁免 — 模型通过 ask_user 等用户回答时,
            // 用户可直接发送消息 (消息即刻出队正常发送; 未回答的 ask_user 会在发送链首部
            // 由 finishInterruptedPendingTools 以 cancelled 落底, 协议安全)。
            // 其余待批工具 (真实审批) 仍阻塞队列 — 审批未了结的轮次不可被新消息插队。
            // v4.8.123 (用户定版): 压缩期新消息与模型输出期同款排队 — 压缩中不派发
            // (压缩结束在 ChatService.runCompression 的 finally 里统一派发)。
            if (session.getJob() != null || session.isCompressing || session.state.value.currentMessages.any { message ->
                    message.parts.any {
                        it is UIMessagePart.Tool && it.isPending && it.toolName != "ask_user"
                    }
                }) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        // 4.1.0: CoroutineStart.LAZY — setJob 内 job?.start() 启动, 队列/审批链
        // 可在 start 前挂起等待前驱 (RinCore 无 FGS acquire 机制, 用 LAZY 直通)
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                // v4.8.118 (B140): 追踪键控 — 本对话为独立追踪单元 (并行会话
                // 互不串扰: start/event/finish 全按 TraceKey 隔离)
                withContext(TraceKey(conversationId.toString())) {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) { context.getString(R.string.chat_page_voice_generation_failed) }
                    check(messages.none { message -> message.parts.any { it is UIMessagePart.Tool && it.isPending } }) {
                        context.getString(R.string.chat_page_voice_tool_approval)
                    }
                    val previousIds = currentConversation.currentMessages.map { it.id }.toSet()
                    messages.filter { it.id !in previousIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
                // Voice owns playback, including when its observer has already left the page.
                // The ordinary autoplay collector must not read a late voice reply again.
                if (queued.reply == null) _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                queued.reply?.completeExceptionally(e)
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) queued.reply?.completeExceptionally(cause)
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        val previousJob = session.getJob()

        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                // v4.8.118 (B140): 追踪键控 — 本对话独立追踪单元
                withContext(TraceKey(conversationId.toString())) {
                previousJob?.join()
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) = synchronized(sessionManager.getOrCreate(conversationId)) {
        val session = sessionManager.getOrCreate(conversationId)
        val previousJob = session.getJob()

        val hasOtherPendingTools = session.state.value.messageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                // v4.8.118 (B140): 追踪键控 — 本对话独立追踪单元
                withContext(TraceKey(conversationId.toString())) {
                me.rerere.rikkahub.data.ai.CallTracer.event(
                    "TOOL", "approval_${if (approved) "approved" else "denied"}",
                    "tool=$toolCallId reason=$reason answer=$answer"
                )
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // Ignore double taps and stale approvals for completed or inactive tools.
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        when {
                                            part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                                part.copy(approvalState = newApprovalState)
                                            }

                                            else -> part
                                        }
                                    }
                                )
                            }
                        )
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    // (4.8.12: WakeLock/WifiLock 保活四函数移至 GenerationKeepAlive.kt — 保活体系独立文件)

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        // v4.8.34: 当前 provider 心跳确保 — 工具执行期间连接禁止空闲冷却
        // (此前仅 opencode/CC host 有心跳; DeepSeek 等直连 provider 在长工具
        //  执行后连接变冷, "工具结束→恢复输出"多付连接重建/半死等待)。
        runCatching {
            val chatProvider = model.findProvider(settings.providers)
            val baseUrl = when (chatProvider) {
                is ProviderSetting.OpenAI -> chatProvider.baseUrl
                is ProviderSetting.Claude -> chatProvider.baseUrl
                is ProviderSetting.Google -> chatProvider.baseUrl
                else -> null
            }
            val apiKey = when (chatProvider) {
                is ProviderSetting.OpenAI -> chatProvider.apiKey
                is ProviderSetting.Claude -> chatProvider.apiKey
                is ProviderSetting.Google -> chatProvider.apiKey
                else -> null
            }
            if (!baseUrl.isNullOrBlank()) {
                // v4.8.74: OC/CC 使用长保活池 — 与主请求同池 (原实现恒用 primary,
                // OC/CC 心跳一直打错池 = 长池连接无人刷新, "工具后恢复慢"的另一半根因)
                val client = if (baseUrl.contains("opencode.ai") || baseUrl.contains("commandcode.ai")) {
                    me.rerere.ai.provider.ProviderManager.opencodeClient ?: httpClient
                } else {
                    httpClient
                }
                ConnectionWarmer.ensureProviderKeepAlive(
                    appScope = appScope,
                    client = client,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                )
            }
        }

        // v3.6.15: 生成保活 — 切后台时 CPU/网络读稳定 (onCompletion 释放)
        val genWakeLock = acquireGenWakeLock(context)
        // v4.7.18: WiFi 射频保活 (防息屏射频休眠断流; onCompletion 释放)
        val genWifiLock = acquireGenWifiLock(context)
        // 4.8.20: 前台服务保活 — 进程优先级提升 (切后台不被冻结/断网; 生成期间
        // 系统级持续性的唯一正确解 — WakeLock/WifiLock 只保 CPU/射频)
        GenerationForegroundService.acquire(context, conversationId.toString())

        // 4.1.3 TTFT: 生成前预热整体移除 — 两点结构性缺陷:
        //   a) 与主请求并发, 同 key 请求被网关串行化 (v3.12.6 用户实测),
        //      预热挂在慢网关上时主请求反而排队等它;
        //   b) 60s 节流 + 服务端 (CF 系) ~100s 空闲断连, 绝大多数发送时
        //      连接已死, 预热名存实亡。
        // 连接新鲜度由 ConnectionWarmer.startProviderKeepAlive 常驻心跳
        // (60s 周期, 同池, App 启动拉起) 保证, 任意时刻发送均为热连接。

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (settings.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // 4.8.24 项目包 CWD 解析 — 对话所属项目包 cwd 优先 (项目包锚定),
            // 否则助手级 CWD; 「聊天」(无项目包) = 全助手权限默认空间
            // v4.8.189 (L-A4): 解析收口 EffectiveCwd (全库唯一优先级判定点)。
            val effectiveWorkspaceCwd = EffectiveCwd.resolve(
                folderRepository, conversation.folderId, assistant.workspaceCwd,
            )

            // start generating
            val session = sessionManager.getOrCreate(conversationId)
            // v3.5.59 落盘节流计时 (流式期间每 2s 一次全量写)
            var lastStreamPersistMs = 0L
            generationHandler.generateText(
                settings = settings,
                model = model,
                // v3.20.0: 会话稳定 ID → x-opencode-session (OpenCode 官方强制)
                conversationId = conversationId,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = effectiveWorkspaceCwd,
                conversationLoadedDomains = conversation.loadedDomains,
                // v3.11.27: 子代理会话 ([Sub-agent] 标题) 不注入用户自定义 prompt
                skipAssistantPrompt = conversation.title.startsWith("[Sub-agent]"),
                toolPoolProvider = {
                    buildAssistantToolPool(
                        filesRoot = context.filesDir,
                        settings = settingsStore.settingsFlow.value,
                        // v4.8.118: 本对话的助手 (原为"当前焦点助手"——并行/多助手
                        // 场景下 invoke_tools 懒加载池会错位到焦点助手的工具与
                        // 工作区锚点; 主 tools 构建一直用的就是本对话助手)
                        assistant = assistant,
                        localTools = localTools,
                        skillManager = skillManager,
                        conversationRepo = conversationRepo,
                        mcpManager = mcpManager,
                        settingsStore = settingsStore,
                        conversationId = conversationId.toString(),
                        workspaceCwd = effectiveWorkspaceCwd,
                        workspaceRepository = workspaceRepository,
                        pluginManager = pluginManager,
                        operitToolProvider = operitToolProvider,
                    ).let { pool ->
                        // 与主构建同口径: 非法服务器名剔除
                        val invalidNames = mcpManager.getAllAvailableTools()
                            .map { it.second }.distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isEmpty()) pool
                        else pool.filter { tool -> invalidNames.none { bad -> tool.name.startsWith("mcp__${bad}__") } }
                    }
                },
                memories = run {
                    val base = if (assistant.useGlobalMemory) {
                        memoryRepository.getGlobalMemories()
                    } else {
                        memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                    }
                    // v5.0.24 三范围：助手级/全局（base） + 项目包 + 单对话
                    val folderMemories = conversation.folderId?.let { folderId ->
                        runCatching { memoryRepository.getMemoriesOfFolder(folderId.toString()) }
                            .getOrDefault(emptyList())
                    } ?: emptyList()
                    // v5.0.67 ★用户定案的「项目包记忆」真正语义：
                    // 项目包 = **该对话 + 归属同一项目包的所有对话** 的「仅对话内可见」记忆集合，
                    // 不是独立的存储层。旧实现只注入 folderId 层（若从未写入就恒为空），
                    // 同包其他对话的记忆**根本没有被注入** → "包内互通"名不副实。
                    val siblingMemories = conversation.folderId?.let { fid ->
                        runCatching {
                            val ids = conversationRepo.getConversationIdsOfFolder(fid).map { it.toString() }
                            memoryRepository.getMemoriesOfConversations(ids)
                        }.getOrDefault(emptyList())
                    } ?: emptyList()
                    val ownMemories = runCatching {
                        memoryRepository.getMemoriesOfConversation(conversation.id.toString())
                    }.getOrDefault(emptyList())
                    // 同包批量查询已包含本对话的记忆 → distinctBy 去重，避免 prompt 重复
                    (base + folderMemories + siblingMemories + ownMemories).distinctBy { it.id }
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                    add(me.rerere.rikkahub.ecosystem.tools.SlashCommandRouter)
                },
                outputTransformers = outputTransformers,
                // v4.8.34 (性能): 工具池构建移出主线程 — handleMessageComplete 在
                // AppScope(Main) 执行, buildAssistantToolPool 含 文件遍历/MCP 汇总/
                // 工具区归类/技能扫描 等纯构建 (发送瞬间主线程重活之一), 移 Default。
                tools = withContext(Dispatchers.Default) { buildAssistantToolPool(
                    filesRoot = context.filesDir,
                    settings = settings,
                    assistant = assistant,
                    localTools = localTools,
                    skillManager = skillManager,
                    conversationRepo = conversationRepo,
                    mcpManager = mcpManager,
                    settingsStore = settingsStore,
                    conversationId = conversationId.toString(),
                    workspaceCwd = effectiveWorkspaceCwd,
                    workspaceRepository = workspaceRepository,
                    pluginManager = pluginManager,
                    operitToolProvider = operitToolProvider,
                ) }.let { pool ->
                    // MCP 服务器名合法性检查 (对齐原逻辑)
                    // v3.6.96: 不弹窗阻塞 — 历史残留的非法名称服务器 (如测试遗留
                    // 含连字符名) 此前每次消息处理都弹"无效服务器名"错误, 一条
                    // 残留炸掉整个应用。改为: 非法名服务器的工具剔除 (不注入请求
                    // 体, 避免 API 400) + 日志警告, 用户在 MCP 管理里改名/删除即可。
                    val invalidNames = mcpManager.getAllAvailableTools()
                        .map { it.second }
                        .distinct()
                        .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                    if (invalidNames.isNotEmpty()) {
                        Log.w(TAG, "invalid MCP server names ignored (tools excluded): $invalidNames")
                        pool.filter { tool ->
                            invalidNames.none { bad -> tool.name.startsWith("mcp__${bad}__") }
                        }
                    } else pool
                }
                                .map { tool ->
                    settings.toolDescriptionOverrides[tool.name]?.let { tool.copy(description = it) } ?: tool
                }
                // v3.6.102: 工具改名 — 汉语名工具改为字母数字名 (模型识别 + API 合法性)
                .map { tool ->
                    val renamed = settings.toolNameOverrides[tool.name]
                        ?.takeIf { it.isNotBlank() && it.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' } }
                    if (renamed == null) tool else tool.copy(name = renamed)
                }
                .sortedBy { it.name },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                // 关键: 取消态 (用户停止/切后台) 下挂起调用会跳过 — 必须 NonCancellable
                // 否则 saveConversation/emit Ended 不执行 → 灵动岛不消失, 计时器不停
                withContext(NonCancellable) {
                    // v3.8.34: 兜底收尾运行日志轮次 (异常/中断路径也会结束会话并落盘)
                    CallTracer.finishTraceIfActive()
                    val updatedConversation = getConversationFlow(conversationId).value.copy(
                        messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                        updateAt = Instant.now()
                    )
                    updateConversation(conversationId, updatedConversation)

                    // 兜底落盘 (异常/流中断路径 — onSuccess 可能不执行)
                    saveConversation(conversationId, updatedConversation)

                    // v3.6.15: 生成结束释放 WakeLock (NonCancellable 内 — 取消态也执行)
                    releaseGenWakeLock(genWakeLock)
                    // v4.7.18: 释放 WifiLock
                    releaseGenWifiLock(genWifiLock)
                    // 4.8.20: 释放前台服务保活 (3s 防抖 — regenerate 瞬态不误停)
                    GenerationForegroundService.release(context, conversationId.toString())

                    // 生成结束：取消 Live Update 通知，后台时发送完成通知
                    appEventBus.emit(
                        AppEvent.ChatGenerationEnded(
                            conversationId = conversationId,
                            senderName = senderName,
                            contentPreview = updatedConversation.currentMessages.lastOrNull()
                                ?.toText()?.take(50)?.trim() ?: "",
                            // v4.8.88: 子代理会话的完成通知与主模型分流（引擎单独发子代理通知）
                            isSubAgent = updatedConversation.title.startsWith(
                                me.rerere.rikkahub.subagent.SUBAGENT_TITLE_PREFIX
                            ),
                        )
                    )
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        // v5.0.76 ★结构性重写：流式写入不再"外部读快照→整对象写回"。
                        // 旧模式（读 state → updateCurrentMessages → updateConversation）在
                        // 读与写之间是裸窗口：此刻用户切助手（归属 apply）或分支切换写入，
                        // 流式的旧快照会把对方整个覆盖掉（lost update = 切换后消息消失）。
                        // 新模式：session.apply 原子读改写；落库以 session.state 为唯一权威。
                        val session = sessionManager.getOrCreate(conversationId)
                        session.apply { it.updateCurrentMessages(chunk.messages) }

                        // v3.5.60 落盘节流 (1s): 每 chunk 全量写 → 每 1s 一次。
                        // 发热与落盘权衡: 丢失窗口 ≤1s (用户: 消息丢失是灾难性),
                        // IO 降 90%+, 收尾仍全量落盘。
                        val now = System.currentTimeMillis()
                        if (now - lastStreamPersistMs > 1_000) {
                            persistConversationState(conversationId)
                            lastStreamPersistMs = now
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                    is GenerationChunk.LoadedDomains -> {
                        // Feature #4: 持久化已加载的域到 Conversation
                        val updatedConversation = getConversationFlow(conversationId).value
                            .copy(loadedDomains = chunk.domains)
                        updateConversation(conversationId, updatedConversation)
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(
                AppEvent.ChatGenerationEnded(
                    conversationId,
                    senderName,
                    null,
                    isSubAgent = getConversationFlow(conversationId).value.title.startsWith(
                        me.rerere.rikkahub.subagent.SUBAGENT_TITLE_PREFIX
                    ),
                )
            )

            it.printStackTrace()
            // 错误上下文记录 (运行日志页可见) — 定位 'required settings preferences not received'
            // 等 DeepSeek 服务端偶发 400: 记录模型/thinking 档位/消息数/工具数, 复现时对照
            runCatching {
                val conv = getConversationFlow(conversationId).value
                val msgCount = conv.currentMessages.size
                val toolCount = conv.currentMessages.flatMap { m -> m.parts.filterIsInstance<UIMessagePart.Tool>() }.size
                val baseUrl = (model.findProvider(settings.providers) as? me.rerere.ai.provider.ProviderSetting.OpenAI)?.baseUrl ?: "?"
                me.rerere.rikkahub.data.ai.CallTracer.event(
                    "ERROR", "generation_failed",
                    "${it.message ?: it.javaClass.simpleName} | model=${model.displayName} " +
                        "baseUrl=$baseUrl msgs=$msgCount tools=$toolCount reasoning=${assistant.reasoningLevel.name}"
                )
            }
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
            // 5.0.0 并树：记忆整理登记（对话后自动整理；仅 enableMemory 助手）
            runCatching {
                val assistant = settingsStore.settingsFlow.value.assistants
                    .find { it.id == finalConversation.assistantId }
                if (assistant?.enableMemory == true && assistant.memoryDreamEnabled) {
                    // v5.0.16 做梦范围（0=整个助手 / 1=项目包内 / 2=当前对话）
                    val dreamScope = assistant.memoryDreamScope
                    val folderIds: Set<Uuid> = if (dreamScope == 1) {
                        finalConversation.folderId?.let { fid ->
                            runCatching { conversationRepo.getConversationIdsOfFolder(fid).toSet() }
                                .getOrDefault(emptySet())
                        } ?: emptySet()
                    } else emptySet()
                    memoryConsolidationScheduler.onTurnFinished(finalConversation, dreamScope, folderIds)
                }
            }.onFailure { Logging.log(TAG, "memory onTurnFinished failed: $it") }
        }
        // 强制停表 (顽固 bug 根治): 无论成功/失败/取消, 生成结束即停 reasoning 计时。
        // onCompletion 已停表但存在缝隙 (挂起路径), 此处兜底 — finishReasoning 幂等。
        runCatching {
            val conv = getConversationFlow(conversationId).value
            val needsStop = conv.messageNodes.any { node ->
                node.messages.any { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.Reasoning>().any { it.finishedAt == null }
                }
            }
            if (needsStop) {
                val updated = conv.copy(
                    messageNodes = conv.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    }
                )
                updateConversation(conversationId, updated)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.fastModelId)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_not_found))
            val provider = model.findProvider(settings.providers)
                ?: throw IllegalStateException(context.getString(R.string.error_fast_model_provider_not_found))

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel, sessionId = conversationId.toString()),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim().ifEmpty { "" })
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessionManager.get(conversationId)?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel, sessionId = conversationId.toString()),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessionManager.get(conversationId)?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    /**
     * v4.8.123 (用户定版): 压缩执行入口 (UI 调用) — 压缩期标记会话 (派发门据此
     * 把新消息挂起"等待中"), 结束(成功/失败/取消)必达的 finally 里清标记并派发
     * 排队消息 (压缩完成 → 排队消息自动发出)。
     */
    suspend fun runCompression(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 10
    ): Result<Unit> {
        val compressSession = sessionManager.get(conversationId)
        compressSession?.isCompressing = true
        return try {
            compressConversation(conversationId, conversation, additionalPrompt, targetTokens, keepRecentMessages)
        } finally {
            compressSession?.isCompressing = false
            dispatchNextQueuedMessage(conversationId)
        }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 10
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else {
            // v3.8.28: keep<=0 禁止全压缩 — 压缩必须至少保留最近内容
            // (绝不压缩刚发出的消息), 无可压缩空间时直接报不足
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            // v4.5.16: 背景任务一致性 — 压缩此前用默认 AUTO 档 (模型默认思考
            // 常开), 与标题/建议 (fastModelReasoningLevel) 不一致; 长上下文 +
            // 默认思考 = 非流式 3 分钟 readTimeout 挂起 (用户实测"一直正在
            // 压缩中直到漫长超时")。对齐 fastModelReasoningLevel 并记录请求
            // 参数, 挂起时日志可直接归因。
            val settingsNow = settingsStore.settingsFlow.first()
            val compressLevel = settingsNow.fastModelReasoningLevel
            android.util.Log.i("ChatService", "compressMessages: model=${model.modelId} level=$compressLevel chunk=${messages.size}msgs chars=${contentToCompress.length}")
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model, compressLevel, sessionId = conversationId.toString()),
            )

            return result.message.toText().trim().ifEmpty { throw IllegalStateException("Failed to generate compressed summary") }
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // 存档原始消息与压缩摘要 → 然后替换 messageNodes
        val savedNodes = conversation.messageNodes
        val summaryNodes = compressedSummaries.map { UIMessage.user(it).toMessageNode() }
        val newMessageNodes = buildList {
            addAll(summaryNodes)
            addAll(messagesToKeep.map { it.toMessageNode() })
        }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        ).addCompressRetention(savedNodes, summaryNodes)

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        // v5.0.83：不创建空壳（get）；未就绪时由 session 内部排队
        val session = sessionManager.get(conversationId) ?: return
        checkFilesDelete(conversation, session.state.value)
        session.updateConversation(conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        // v5.0.76：原子读改写；v5.0.83：**绝不创建空壳 session**（get 而非 getOrCreate），
        // 未就绪的 session 内部自动排队（见 ConversationSession.apply）。
        val session = sessionManager.get(conversationId)
        if (session == null) {
            Log.w(TAG, "updateConversationState: no active session, ignored id=$conversationId")
            return
        }
        session.apply(update)
    }

    /**
     * v5.0.76：以 **session 内存态为唯一权威** 落库（锁内读、锁内写）。
     *
     * 所有"把当前对话写进 DB"的场景都应走这里，而不是把 UI/生成链的快照对象
     * 交给 [saveConversation] —— 外部快照与 session 状态之间存在传播延迟，
     * 用它整对象写回会把刚 apply 的归属/消息覆盖回旧值（切换后记录消失的根因）。
     */
    suspend fun persistConversationState(conversationId: Uuid) {
        saveLockFor(conversationId).withLock {
            // v5.0.83：绝不创建空壳；未就绪则跳过错过的保存由后续信号补齐
            val session = sessionManager.get(conversationId)
            if (session == null || !session.isReady) {
                Log.w(TAG, "persistConversationState: session not ready, skip id=$conversationId")
                return@withLock
            }
            val snapshot = session.state.value
            val exists = conversationRepo.existsConversationById(conversationId)
            if (!exists && snapshot.title.isBlank() && snapshot.messageNodes.isEmpty()) {
                return@withLock // 新会话且为空时不保存
            }
            if (exists) {
                conversationRepo.updateConversation(snapshot)
            } else {
                conversationRepo.insertConversation(snapshot)
            }
        }
        dispatchNextQueuedMessage(conversationId)
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    /** 4.8.27: 会话是否已完成 DB 加载 (发送路径归属同步守卫)。 */
    fun isConversationInitialized(conversationId: Uuid): Boolean =
        sessionManager.get(conversationId)?.isReady == true

    /**
     * v5.0.19 (B201 数据事故): 等待对话完成 DB 加载。
     * 供"打开页面即写入归属/草稿"等早期写入方使用——未加载时的 session 是空壳
     * （messageNodes 为空），任何整对象落库都会把历史消息删光。
     */
    /**
     * v5.0.83：等待会话完成 DB 加载。返回 true = 已就绪可安全读写。
     * session 不存在时**绝不创建空壳**（调用方应通过 initializeConversation 建会话）。
     */
    suspend fun awaitConversationLoaded(conversationId: Uuid, timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val session = sessionManager.get(conversationId) ?: return false
            if (session.isReady) return true
            kotlinx.coroutines.delay(50)
        }
        return sessionManager.get(conversationId)?.isReady == true
    }

    /** 4.8.32: 该会话是否正在生成 (压缩入口守卫 — 生成中禁止压缩)。 */
    fun isConversationGenerating(conversationId: Uuid): Boolean =
        sessionManager.get(conversationId)?.isGenerating == true

    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessionManager.get(conversationId) != null) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessionManager.snapshot().any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessionManager.snapshot()
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    /**
     * v4.8.77: 项目包移出为助手 — 包内全部对话转为新助手对话记录。
     * 先同步活跃 session 内存态 (assistantId + folderId), 再库级批量转移, 最后删包 —
     * 否则活跃 session 后续整对象保存会写回旧 assistantId/folderId (同 deleteFolder 教训)。
     */
    suspend fun extractFolderAsAssistant(folderId: Uuid, newAssistantId: Uuid) {
        sessionManager.snapshot()
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(assistantId = newAssistantId, folderId = null) } }
        conversationRepo.reassignFolderConversations(folderId, newAssistantId)
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        // v4.8.19 性能: 快路径 — 文件清单未变时跳过全部扫描。流式期间每 50ms 的
        // updateConversation 绝大多数只改消息 (files 不变); 此时代数上可证
        // deletedFiles 必为空: deletedFiles = old.files - (new.files + queued),
        // 当 new.files == old.files 时差集恒空, 原扫描 (队列 flatMap + URI 转换)
        // 属纯浪费。文件删除仅发生在消息编辑/删除等显式路径 (files 会被更新)。
        if (newConversation.files == oldConversation.files) return
        val session = sessionManager.get(newConversation.id)
        val queuedFiles = (session?.messageQueue?.state?.value?.messages.orEmpty() +
                listOfNotNull(session?.submittingMessage))
            .flatMap { it.parts }.localFileUrls().map { it.toUri() }
        val newFiles = newConversation.files + queuedFiles
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        // v5.0.83 ★★★数据丢失根修（用户实测：并发生成+切换→重启后对话内容消失）：
        //
        // 旧实现三宗罪：
        //  ① awaitConversationLoaded(15s) 在 saveLock **内** → 每条保存持锁等 15s，N 条排队
        //     = 15s×N 卡死；而 finishGeneration 是 NonCancellable → 停止键（红叉）永远无效。
        //  ② 加载守卫判处决后仍用**调用方入参快照**写内存与 DB —— 入参可能来自空壳
        //     （sendQueuedMessage 读 session.state.value，未加载时=空对话）→ **空壳+1条消息覆盖历史**。
        //  ③ 任何 getOrCreate（14 处）+ apply 可把空壳污染成 initialized → DB 权威数据永不加载。
        //
        // 新契约：等待在**锁外**；写库权威=**session.state**；空 messageNodes 严禁覆盖非空历史。
        if (!awaitConversationLoaded(conversationId)) {
            Log.w(
                TAG,
                "saveConversation: skipped — session not ready (no shell creation), id=$conversationId",
            )
            return
        }
        saveLockFor(conversationId).withLock {
            val session = sessionManager.get(conversationId) ?: return@withLock
            if (!session.isReady) {
                Log.w(TAG, "saveConversation: skipped — session not READY, id=$conversationId")
                return@withLock
            }
            // ★数据事故总闸：空壳（空 messageNodes）永远不许覆盖非空历史（v5.0.19 B201 教训的制度化）
            if (conversation.messageNodes.isEmpty() && session.state.value.messageNodes.isNotEmpty()) {
                Log.e(
                    TAG,
                    "saveConversation: BLOCKED empty-shell overwrite id=$conversationId " +
                        "(incoming=0 nodes, session=${session.state.value.messageNodes.size} nodes)",
                )
                return@withLock
            }
            val exists = conversationRepo.existsConversationById(conversationId)
            if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
                return@withLock // 新会话且为空时不保存
            }
            // 入参合并进内存（原子 apply），随后以 session.state 为唯一权威落库
            session.apply { conversation }
            val snapshot = session.state.value
            if (!exists) {
                conversationRepo.insertConversation(snapshot)
            } else {
                conversationRepo.updateConversation(snapshot)
            }
        }

        // 删除消息或切换分支也可能解除工具审批阻塞，保存成功后重新检查队列。
        // 调度器仍会检查当前生成任务、待审批工具、暂停状态及编辑占位。
        // 注意：必须在锁外调用 —— dispatch 可能再次触发 saveConversation。
        dispatchNextQueuedMessage(conversationId)
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                persistConversationState(conversationId)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        // v5.0.60 ★「气泡分支切换丢内容」根修（lost update）：
        // 旧实现先在**函数开头**读取 conversation 快照，中间还夹着
        // `settingsStore.settingsFlow.first()`（真正的挂起点）—— 在这段窗口里
        // regenerateAtMessage / 流式落库都可能写入新状态，随后这里用**旧快照**
        // 整体覆盖，"第二遍内容"就没了（且切回也找不回，因为它从未落库）。
        // 现在：挂起点（取 settings / assistant）全部前移，写回改成
        // updateConversationState 的**原子读改写**（中间无挂起），落库也取刚写入的权威状态。
        val snapshot = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(snapshot.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        // 编辑语义: 在目标节点追加新版本 (不替换) — 旧版本保留,
        // 界面下方 123 按钮可切换查看之前的消息。截断与重新生成
        // 由 regenerateAtMessage 完成 (ChatVM.handleMessageEdit 已接)。
        updateConversationState(conversationId) { current ->
            val updatedNodes = current.messageNodes.map { node ->
                if (node.messages.none { it.id == messageId }) {
                    node
                } else {
                    edited = true
                    node.copy(
                        messages = node.messages + UIMessage(
                            role = node.role,
                            parts = processedParts,
                        ),
                        // 指向新版本 (size = old+1, 指向最后一条)
                        selectIndex = node.messages.size,
                    )
                }
            }
            if (edited) current.copy(messageNodes = updatedNodes) else current
        }

        if (!edited) return

        // 落库用**刚写入的权威状态**（锁内读 session.state），而不是函数开头的旧快照
        persistConversationState(conversationId)
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        // 2.5.3 移植 + 2.5.6 对齐: 分支沿用原对话标题并自动添加序号 (查重直到不冲突;
        // 源标题已带 (N) 后缀时递增而非叠加)
        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val forkTitle = forkConversationTitle(currentConversation.title, existingTitles)
        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            title = forkTitle,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
            // v4.8.63 (用户实证修复, bug-High): 分支必须继承原对话的文件夹归属 —
            // 此前漏传 folderId, 新分支永远掉进「默认聊天」而非原项目包
            folderId = currentConversation.folderId,
        )

        saveConversation(forkConversation.id, forkConversation)
        // 5.0.0 并树：fork 会话同步记忆整理登记
        runCatching { memoryConsolidationScheduler.onConversationForked(forkConversation) }
            .onFailure { Logging.log(TAG, "memory onConversationForked failed: $it") }
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        val jobs = synchronized(session) {
            session.messageQueue.pause()
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        // RinCore 自研: 立即停表 (finishReasoning) 不等收尾落盘; join 限 3s 防大对话
        // DB 写入阻塞 UI 响应, 收尾在后台继续。
        val current = getConversationFlow(conversationId).value
        val stopped = current.copy(
            messageNodes = current.messageNodes.map { node ->
                node.copy(messages = node.messages.map { it.finishReasoning() })
            },
            updateAt = Instant.now()
        )
        updateConversation(conversationId, stopped)
        runCatching { withTimeoutOrNull(3_000L) { jobs.forEach { it.join() } } }
        finishInterruptedPendingTools(conversationId)
        // 显式收尾: 取消灵动岛 (不依赖 onCompletion — 取消态可能跳过)
        appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, "assistant", null))
    }
}

