/* ───【域 A·对话核心】ConversationSession.kt
 * 职责: 单会话运行时 (Job/消息队列/状态机/原子状态写入)
 * 常用改动: 会话状态 → state/setJob; 中断 → cancel
 * 问题定位: 流式输出被旧快照覆盖/状态丢失/切换后消息消失 → 本文件
 * 基线: v5.0.76 全量重写（不再对齐旧版逐字——旧版竞态是结构性的）
 * 检修点: phase 状态流 / apply 排队日志 / 初始化重放计数
 * ───────────────────────────────────────────────────────────────*/
package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

// v4.8.35: 会话空闲回收 5s → 30 分钟 (用户定版: 保活类时间大幅延长)。
// 正在生成的会话不回收 (!isGenerating 守卫保留)。
private const val IDLE_TIMEOUT_MS = 30 * 60_000L

/**
 * v5.0.76 检修点：会话生命周期阶段。
 *
 * NEW     空壳（尚未从 DB 加载，也未被外部写入初始化）
 * LOADING DB 加载进行中 —— 期间 [apply] 的修改全部**排队**，绝不写 state
 * READY   数据就绪（DB 权威数据已在内存，或外部已整对象初始化）
 * FAILED  DB 加载失败 —— 读取方应看到明确错误态，而不是空壳装作正常
 */
enum class SessionPhase { NEW, LOADING, READY, FAILED }

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
) {
    private val TAG = "ConversationSession"

    // 会话状态：唯一权威在 _state；外部一律经 [apply] / [updateConversation] 修改
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()

    // v5.0.76 检修点：生命周期可观测
    private val _phase = MutableStateFlow(SessionPhase.NEW)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    val messageQueue = MessageQueue()

    // v5.3.2（3.1.1 对齐）：悬浮窗需要的两个可观测点
    /** 本轮回复消息 id（由生成链在创建 assistant 消息后写入） */
    val replyMessageId = MutableStateFlow<Uuid?>(null)
    /** 本轮生成开始时间（setJob 时写入，用于悬浮窗只显示"这一轮"的错误） */
    val generationStartedAt = MutableStateFlow<Long?>(null)

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // state 锁：apply / initialize / updateConversation 共用，保证读写原子
    private val stateLock = Any()

    // LOADING 期间排队的修改（load 完成后按序重放）
    private val pendingUpdates = ArrayDeque<(Conversation) -> Conversation>()

    // 初始化互斥（并发 initialize 只跑一次 load）
    private val initializationMutex = Mutex()
    private val metadataMutex = Mutex()

    @Volatile
    private var initialized = false

    /**
     * v5.0.76 重写：DB 加载与并发写入的时序保证。
     *
     * 旧实现的结构性缺陷：
     *   load() 是挂起点，加载期间任何 `updateConversation`（外部整对象、
     *   归属同步、流式 chunk）都会把 `initialized` 置 true →
     *   load 返回后 `if (!initialized)` 判假 → **DB 权威数据被永久丢弃**，
     *   会话停留在空壳 → 用户看到"聊天记录全部消失"（重启才恢复）。
     *
     * 新保证：
     *   1. LOADING 期间所有修改**排队不写**（apply 进 pendingUpdates）；
     *   2. load 结果**必定**进入内存（DB 权威），随后按序重放排队修改；
     *   3. load 失败 → phase=FAILED 并抛出（由调用方 toast），**绝不静默吞掉**。
     */
    suspend fun initialize(load: suspend () -> Conversation, force: Boolean = false) {
        initializationMutex.withLock {
            // v5.0.83：只以 READY 为"已加载"判据（initialized 字段不再由空壳置真）；
            // 另一并发调用者会在 Mutex 上等待首个加载完成，返回时 phase=READY 直接跳过。
            //
            // v5.0.95 ★`force=true` 的存在理由（用户 2026-10-25 实测 bug）：
            // 一旦某次 load 因底层异常返回了**空壳**并被标记 READY，旧的"READY 即返回"
            // 会让这个空壳**永久生效** —— UI 永远是初始星轨页、重试无效、只能重启。
            // 现在上层（ChatService）检测到"内存为空但 DB 有节点"时用 force 重载。
            synchronized(stateLock) {
                // v5.0.96：判据改为 initialized（与 isReady 单一事实源），
                // 不再用 phase —— 避免"phase 与 initialized 不一致"再造出空壳死结。
                if (!force && initialized) return
                _phase.value = SessionPhase.LOADING
            }
            val loaded: Conversation = try {
                load()
            } catch (e: CancellationException) {
                synchronized(stateLock) { _phase.value = SessionPhase.FAILED }
                Log.w(TAG, "initialize cancelled id=$id — phase=FAILED")
                throw e
            } catch (e: Throwable) {
                synchronized(stateLock) { _phase.value = SessionPhase.FAILED }
                Log.e(TAG, "initialize FAILED id=$id: ${e.message}", e)
                throw IllegalStateException("会话数据加载失败", e)
            }
            val replayed = synchronized(stateLock) {
                _state.value = loaded
                initialized = true
                _phase.value = SessionPhase.READY
                val pending = pendingUpdates.toList()
                pendingUpdates.clear()
                var value = _state.value
                pending.forEach { upd -> value = upd(value) }
                _state.value = value
                pending.size
            }
            if (replayed > 0) {
                // 检修点：加载窗口期内排队了多少次修改（正常 0~2 次；多=高频写入路径）
                Log.d(TAG, "initialize id=$id loaded + replayed $replayed pending updates")
            }
        }
    }

    /**
     * v5.0.76：**原子读改写** —— 一切"基于当前状态改一个字段"的写入的唯一入口。
     *
     * 替代旧模式 `state.value → update → updateConversation(result)`（外部读快照
     * 与写回之间存在竞态窗口，是流式落库覆盖归属/消息的结构性来源）。
     * LOADING 期间自动排队，load 完成后按序重放。
     */
    /**
     * v5.0.83 ★★结构性修复（数据丢失级 bug）：
     *
     * 旧实现的致命缺陷：`NEW`（空壳 session，initial = Conversation.ofId 空对话）阶段
     * 也走写 state 分支并 `initialized = true` → 空壳被任何一次 apply 污染成"已加载"
     * → `initialize()` 开头 `if (initialized) return` → **DB 权威数据永不加载**
     * → 会话永久空壳 → `sendQueuedMessage` 读到空壳、拼出"空壳+1条消息"→ 落库 **覆盖历史**
     * （= 用户实测"重启后两个对话内容全部消失"）。
     *
     * 新语义：**只有 READY 才能写 state**。NEW/LOADING/FAILED 一律排队，等 initialize
     * 完成后按序重放。空壳永远不可能冒充"已加载"。
     */
    fun apply(update: (Conversation) -> Conversation): Conversation = synchronized(stateLock) {
        // v5.0.96 ★★★从最基础重写（用户："不要打补丁，哪里有问题直接重写"）。
        //
        // 历史两起事故是一对矛盾，根源都是"用队列去占位"：
        //   A. v5.0.76 事故：load 期间写入把 initialized 置真 → DB 权威数据被丢弃 → 会话变空。
        //   B. v5.0.95 事故：为避免 A，改成"非 READY 一律排队不写" → 空壳被钉成 READY →
        //      UI 永久空（切任何对话都没内容、重试无效、只能重启）。
        //
        // 正解（写入立即生效 + 重放日志）：
        //   1. **任何时刻都写 _state**（UI 立刻看到数据，不存在"空壳占位"这个状态）；
        //   2. 若加载还没完成，同时把这次修改记入 replay 日志；
        //   3. initialize 拿到 DB 数据后，以 **DB 为基底重放** 这些修改 →
        //      A（丢 DB）与 B（钉空壳）同时不可能发生。
        if (!initialized) {
            pendingUpdates.addLast(update)
        }
        val next = update(_state.value)
        _state.value = next
        next
    }

    /** 整对象写入：v5.0.96 起**任何时刻立即生效**（与 apply 同语义），仅保留空壳护闸。 */
    fun updateConversation(conversation: Conversation) {
        require(conversation.id == id)
        synchronized(stateLock) {
            // ★数据事故总闸（唯一保留的守卫，且不依赖 phase）：
            // 空 messageNodes 不许覆盖非空 state（清空对话必须走显式路径）
            if (conversation.messageNodes.isEmpty() && _state.value.messageNodes.isNotEmpty()) {
                Log.e(TAG, "updateConversation BLOCKED empty overwrite id=$id (state=${_state.value.messageNodes.size} nodes)")
                return
            }
            if (!initialized) {
                pendingUpdates.addLast { conversation }
            }
            _state.value = conversation
        }
    }

    /**
     * v5.0.96：就绪 = **有可派发的权威数据**（不再用 phase 当门禁）。
     * v5.0.99 ★修正 v5.0.96 的过宽判据：**"内存里有消息"绝不等于"已加载"** ——
     * 空壳 session 被写入 1 条消息时，若算作就绪，dispatch 会立刻派发，
     * sendQueuedMessage 就会拿这 1 条消息当权威落库，**覆盖 DB 里的完整历史**
     * （v5.0.83 同族事故的复发窗口）。
     * 现在只认两种真正的"就绪"：DB 已加载 / 这是一个 DB 里本来就不存在的新对话。
     * 未就绪时消息留在队列，由 dispatch 的自愈（phase != LOADING → 重试加载）续上。
     */
    val isReady: Boolean
        get() = initialized || _state.value.newConversation

    /** 4.8.27: 是否已完成 DB 加载 — 发送路径归属同步守卫。 */
    fun isInitialized(): Boolean = initialized

    // 2.5.3: 元数据先应用到最新内存状态；落库只更新对应列，不能用旧消息快照覆盖流式输出。
    internal suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            val updated = apply(update)
            persist(updated)
        }
    }

    // 2.5.3: 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    suspend fun finishGeneration(save: suspend (Conversation) -> Unit): Conversation =
        withContext(NonCancellable) {
            val conversation = apply { current ->
                current.copy(
                    messageNodes = current.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now(),
                )
            }
            save(conversation)
            conversation
        }

    // 引用计数（原子防抖）
    private val refCount = AtomicInteger(0)

    /**
     * v5.0.83：衰减到 0 触发空闲回收计时；**clamp ≥0** —— UI（ChatVM）此前从不 acquire
     * 却在 onCleared release，旧实现 0 → -1 产生负计数（回收判定虽宽容但计数语义崩坏）。
     */
    internal fun release(): Int {
        val after = refCount.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (after == 0) scheduleIdleCheck()
        return after
    }

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    /**
     * v4.8.123 (用户定版): 压缩进行中标记 — 队列派发门据此把新消息挂起"等待中"。
     * 由 ChatService.runCompression 设置, 结束(成功/失败/取消)的 finally 清除并统一派发。
     */
    @Volatile
    var isCompressing: Boolean = false

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    internal fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        if (job != null) generationStartedAt.value = System.currentTimeMillis()
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // 排队协程未进入 body 即被取消时, 级联取消前驱
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    // v5.3.3：本轮结束即清空开始时间 —— 否则下一轮开始前它仍是上一轮的值，
                    // 悬浮窗"只显示本轮错误"的判据会把旧错误当成本轮。
                    generationStartedAt.value = null
                    replyMessageId.value = null
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        // v5.0.55 ★用户实证「按停止没反应、要多按几遍」的根因：
        // 原实现在 NonCancellable 里**无上限**等待 previous.join()；只要 previous 卡在
        // 不响应取消的调用（同步 JS eval / 阻塞 IO / 网络），取消链就永远走不完。
        // 改为最多等 2 秒（给正常 job 一点收尾时间），超时立即向上抛取消。
        withContext(NonCancellable) {
            kotlinx.coroutines.withTimeoutOrNull(2_000) { previous?.join() }
        }
        throw e
    }
}

