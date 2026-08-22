package me.rerere.rikkahub.data.ai


/* ───【自研】CallTracer.kt — 原版无此文件 (v3.8.34 整段重写)
 * 来源: RinCore 自研新增
 * 职责: 消息处理全链路追踪。每一轮消息处理 = 一个会话,
 *       会话 ID = 精确时间戳, 事件实时落盘 (LogSessionStore, 最多 10 轮)。
 * 对比旧实现: 旧版只存最近一条且纯内存 (重启即丢); 新版每轮独立归档。
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.log.LogSessionStore

/**
 * 消息处理全链路追踪。
 * 记录每一轮消息从预处理到 API 调用到后处理的完整运行流程, 并持久化归档。
 *
 * UI 通过 [traceFlow] 订阅当前活动轮次, 通过 LogSessionStore.sessionsFlow
 * 订阅全部已归档轮次。
 */
object CallTracer {
    private const val TAG = "CallTracer"
    private val mutex = Mutex()

    data class TraceEvent(
        val elapsedMs: Long,
        val phase: String,
        val step: String,
        val detail: String,
        val metrics: Map<String, String> = emptyMap(),
    )

    // 当前活动轮次 (内存实时)
    private var events = mutableListOf<TraceEvent>()
    private val _traceFlow = MutableStateFlow<List<TraceEvent>>(emptyList())
    val traceFlow: StateFlow<List<TraceEvent>> = _traceFlow.asStateFlow()

    private var startTime: Long = 0L
    private var sessionId: String = ""

    // 最近一次轮次 ID (非挂起, 供 ErrorCard 等 UI 直接读取; 跨线程可见)
    @Volatile
    private var lastSessionId: String = ""

    @Volatile
    var isActive = false
        private set

    /** 最近一次轮次的时间戳 ID (UI 展示用) */
    fun getTraceId(): String = lastSessionId

    /**
     * 开始新一轮追踪。轮次 ID 由 LogSessionStore 按精确时间戳生成,
     * 该轮会话立即持久化 (active 态), 异常退出也不丢失。
     */
    suspend fun startTrace(id: String = "") {
        Log.i(TAG, "=== TRACE START ===")
        sessionId = LogSessionStore.startSession()
        lastSessionId = sessionId
        startTime = System.currentTimeMillis()
        events = mutableListOf()
        isActive = true
        val initEvent = TraceEvent(
            elapsedMs = 0,
            phase = "INIT",
            step = "trace_start",
            detail = "Trace ID: $sessionId",
        )
        events.add(initEvent)
        _traceFlow.value = events.toList()
        LogSessionStore.appendEvent(
            sessionId = sessionId,
            event = LogSessionStore.LogSessionEvent(
                ts = startTime,
                phase = initEvent.phase,
                step = initEvent.step,
                detail = initEvent.detail,
            ),
        )
    }

    suspend fun event(phase: String, step: String, detail: String, metrics: Map<String, String> = emptyMap()) {
        if (!isActive) return
        mutex.withLock {
            val e = TraceEvent(
                elapsedMs = System.currentTimeMillis() - startTime,
                phase = phase,
                step = step,
                detail = detail,
                metrics = metrics,
            )
            events.add(e)
            _traceFlow.value = events.toList()
            LogSessionStore.appendEvent(
                sessionId = sessionId,
                event = LogSessionStore.LogSessionEvent(
                    ts = startTime + e.elapsedMs,
                    phase = phase,
                    step = step,
                    detail = detail,
                    metrics = metrics,
                ),
            )
            Log.d(TAG, "[+${e.elapsedMs}ms] ${e.phase}/${e.step}: ${e.detail}")
        }
    }

    suspend fun finishTrace() {
        mutex.withLock {
            if (!isActive) return
            val totalMs = System.currentTimeMillis() - startTime
            // TraceEvent 实参先求值 (此时 trace_end 未入列), 总数 = events.size + 1
            val e = TraceEvent(
                elapsedMs = totalMs,
                phase = "FINISH",
                step = "trace_end",
                detail = "Total: ${totalMs}ms, ${events.size + 1} events",
            )
            events.add(e)
            _traceFlow.value = events.toList()
            isActive = false
            LogSessionStore.appendEvent(
                sessionId = sessionId,
                event = LogSessionStore.LogSessionEvent(
                    ts = startTime + totalMs,
                    phase = e.phase,
                    step = e.step,
                    detail = e.detail,
                ),
            )
            LogSessionStore.finishSession(sessionId)
            Log.i(TAG, "=== TRACE END: ${totalMs}ms, ${events.size} events (session=$sessionId) ===")
        }
    }

    /**
     * 兜底收尾: 会话仍 active 时强制结束并落盘。
     * 调用位置: 生成流程 onCompletion (成功/失败/取消全路径)。
     */
    suspend fun finishTraceIfActive() {
        if (!isActive) return
        Log.i(TAG, "finishTraceIfActive: closing open session $sessionId")
        finishTrace()
    }

    suspend fun getSessionId(): String = sessionId
}