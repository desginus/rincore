package me.rerere.rikkahub.service.warm

/* 【域 A·对话核心】WarmPipeline.kt
 * 职责: 预热管线 (铺展瞬时高负载) — 启动预热 / 会话进入预热 / 滚动补热
 * 原则: 只填充解析缓存, 绝不改变任何可见渲染状态 (渲染连续性零破坏);
 *       分时切片批间让渡; 生成中即时避让 (不抢占流式 CPU)。
 * 常用改动: 预热范围/节奏 → 本文件常量; 新预热类型 → 新增 warmXxx
 * 基线: v4.8.50 自研 (取代 v4.8.1-4.8.42 的进入时批量预热形态)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.richtext.warmMarkdownCache
import org.koin.core.context.GlobalContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/**
 * 预热管线 v1 (v4.8.50, 铺展架构 — 用户定版):
 * ① 启动预热 — 进程启动 2.5s 后, 对最近对话的最新 N 条正文做分时解析;
 * ② 会话进入预热 — 进入对话后, 对当前视区周边 ±WINDOW 条分时预热;
 * ③ 滚动补热 — 滚动停稳后对新位置重新铺展 (collectLatest 覆盖旧请求)。
 *
 * 只写 MarkdownParseCache: 界面首帧仍按原版形态同步命中/解析, 可见行为零变化;
 * 单位成本 = 单条正文解析, 每 BATCH 条让渡, 全链单飞 (Mutex)。
 */
object WarmPipeline {

    private const val WINDOW = 80
    private const val STARTUP_MESSAGES = 160
    private const val BATCH = 12
    private const val BATCH_GAP_MS = 35L
    private const val STARTUP_DELAY_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val gate = Mutex()

    private data class WarmRequest(val conversation: Conversation, val center: Int)

    private val requests = MutableSharedFlow<WarmRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 应用启动时调用 (幂等)。 */
    fun startOnAppStart() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { consumeRequests() }
        scope.launch {
            delay(STARTUP_DELAY_MS)
            runCatching { warmRecentConversation() }
                .onFailure { android.util.Log.w("WarmPipeline", "startup warm skipped", it) }
        }
    }

    /** 会话进入/滚动停稳时调用: 预热 [center] 周边 (单位 = 消息节点索引)。 */
    fun warmAround(conversation: Conversation, center: Int) {
        if (conversation.messageNodes.isEmpty()) return
        requests.tryEmit(WarmRequest(conversation, center))
    }

    private suspend fun consumeRequests() {
        requests.collectLatest { req ->
            gate.withLock {
                runCatching { warmAroundSliced(req.conversation, req.center) }
                    .onFailure { android.util.Log.w("WarmPipeline", "conversation warm skipped", it) }
            }
        }
    }

    private suspend fun warmRecentConversation() = gate.withLock {
        val settings = GlobalContext.get().get<SettingsStore>().settingsFlow.value
        val assistantId = settings.getCurrentAssistant().id
        val repo = GlobalContext.get().get<ConversationRepository>()
        val conv = repo.getRecentConversations(assistantId, limit = 1).firstOrNull() ?: return@withLock
        val size = conv.messageNodes.size
        if (size == 0) return@withLock
        val order = ArrayList<Int>(minOf(size, STARTUP_MESSAGES))
        var i = size - 1
        while (i >= 0 && order.size < STARTUP_MESSAGES) {
            order.add(i)
            i--
        }
        warmSliced(conv, order)
    }

    private suspend fun warmAroundSliced(conversation: Conversation, centerRaw: Int) {
        val size = conversation.messageNodes.size
        if (size == 0) return
        val center = centerRaw.coerceIn(0, size - 1)
        val order = ArrayList<Int>(minOf(size, WINDOW * 2 + 1))
        order.add(center)
        for (d in 1..WINDOW) {
            if (order.size >= size) break
            val a = center - d
            if (a >= 0) order.add(a)
            val b = center + d
            if (b < size) order.add(b)
        }
        warmSliced(conversation, order)
    }

    /** 分时执行: 逐条预热正文; 每 BATCH 条让渡一次; 生成中即时退出 (避让流式)。 */
    private suspend fun warmSliced(conversation: Conversation, order: List<Int>) {
        var worked = 0
        for (idx in order) {
            if (worked == 0 && isGenerating(conversation.id)) return
            val node = conversation.messageNodes.getOrNull(idx) ?: continue
            val message = node.messages.getOrNull(node.selectIndex) ?: continue
            for (part in message.parts) {
                if (part is UIMessagePart.Text && part.text.isNotBlank()) {
                    warmMarkdownCache(part.text)
                }
            }
            worked++
            if (worked >= BATCH) {
                worked = 0
                delay(BATCH_GAP_MS)
            }
        }
    }

    private fun isGenerating(conversationId: Uuid): Boolean = runCatching {
        GlobalContext.get().get<ChatService>().isConversationGenerating(conversationId)
    }.getOrDefault(false)
}
