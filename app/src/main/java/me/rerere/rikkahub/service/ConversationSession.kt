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
    suspend fun initialize(load: suspend () -> Conversation) {
        initializationMutex.withLock {
            synchronized(stateLock) {
                if (initialized || _phase.value == SessionPhase.LOADING) return
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
    fun apply(update: (Conversation) -> Conversation): Conversation = synchronized(stateLock) {
        if (_phase.value == SessionPhase.LOADING) {
            pendingUpdates.addLast(update)
            return _state.value
        }
        val next = update(_state.value)
        _state.value = next
        initialized = true
        next
    }

    /** 整对象写入（保留兼容；内部同为 stateLock 原子）。 */
    fun updateConversation(conversation: Conversation) {
        require(conversation.id == id)
        synchronized(stateLock) {
            _state.value = conversation
            initialized = true
            if (_phase.value != SessionPhase.LOADING) {
                _phase.value = SessionPhase.READY
            }
        }
    }

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

    // 原子引用计数
    private val refCount = AtomicInteger(0)

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

    internal fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
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

