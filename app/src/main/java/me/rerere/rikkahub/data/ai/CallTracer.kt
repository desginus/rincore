/* 【域 B·AI 传输】 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai


/* ───【自研】CallTracer.kt — 原版无此文件 (v3.8.34 整段重写; v4.8.118 键控化)
 * 来源: RinCore 自研新增
 * 职责: 消息处理全链路追踪。每一轮消息处理 = 一个会话,
 *       会话 ID = 精确时间戳, 事件实时落盘 (LogSessionStore, 最多 10 轮)。
 *
 * v4.8.118 (B140 深挖): 并行串扰根治 — 旧实现为全局单槽 (sessionId/events/
 * startTime 各一份): 两个对话并行生成时, 第二个 startTrace 直接覆盖第一个的
 * 状态、先结束者还会把对方的 LogSessionStore 会话错误关档 —— 并行场景的
 * 运行日志互相污染 (并行 bug 难以诊断的原因之一)。
 * 现改为协程上下文键控: 生成会话经 withContext(TraceKey(conversationId)) 注入
 * 身份, start/event/finish 全部按 key 隔离; 无 key 上下文归入 __unkeyed__ 桶
 * (与旧版"无活动 trace 时丢弃"等效)。调用点零改动 — event() 自动从
 * currentCoroutineContext 取键。
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.log.LogSessionStore

/**
 * v4.8.118: 追踪身份键 — 生成会话经 withContext(TraceKey(id)) 注入;
 * id 约定 = conversationId.toString() (每对话一条独立追踪)。
 */
class TraceKey(val id: String) : kotlin.coroutines.AbstractCoroutineContextElement(TraceKey) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<TraceKey>
}

object CallTracer {
    private const val TAG = "CallTracer"
    private const val UNKEYED = "__unkeyed__"
    private val mutex = Mutex()

    data class TraceEvent(
        val elapsedMs: Long,
        val phase: String,
        val step: String,
        val detail: String,
        val metrics: Map<String, String> = emptyMap(),
    )

    private class TraceState(val sessionId: String, val startTime: Long) {
        @Volatile
        var active = true
        val events = mutableListOf<TraceEvent>()
    }

    // v4.8.118: 按追踪键隔离 (并行会话互不串扰, 各自独立落盘/关档)
    private val traces = java.util.concurrent.ConcurrentHashMap<String, TraceState>()

    private val _traceFlow = MutableStateFlow<List<TraceEvent>>(emptyList())
    val traceFlow: StateFlow<List<TraceEvent>> = _traceFlow.asStateFlow()

    // 最近一次轮次 ID (非挂起, 供 ErrorCard 等 UI 直接读取; 跨线程可见)
    @Volatile
    private var lastSessionId: String = ""

    @Volatile
    var isActive = false
        private set

    private suspend fun currentKey(): String =
        kotlinx.coroutines.currentCoroutineContext()[TraceKey]?.id ?: UNKEYED

    /** 最近一次轮次的时间戳 ID (UI 展示用) */
    fun getTraceId(): String = lastSessionId

    /**
     * 开始新一轮追踪。轮次 ID 由 LogSessionStore 按精确时间戳生成,
     * 该轮会话立即持久化 (active 态), 异常退出也不丢失。
     * v4.8.118: 按当前协程 TraceKey 隔离 — 并行会话各自独立。
     */
    suspend fun startTrace(id: String = "") {
        val key = currentKey()
        mutex.withLock {
            Log.i(TAG, "=== TRACE START ($key) ===")
            val sessionId = LogSessionStore.startSession()
            lastSessionId = sessionId
            val state = TraceState(sessionId, System.currentTimeMillis())
            traces[key] = state
            isActive = true
            val initEvent = TraceEvent(
                elapsedMs = 0,
                phase = "INIT",
                step = "trace_start",
                detail = "Trace ID: $sessionId",
            )
            state.events.add(initEvent)
            _traceFlow.value = state.events.toList()
            LogSessionStore.appendEvent(
                sessionId = sessionId,
                event = LogSessionStore.LogSessionEvent(
                    ts = state.startTime,
                    phase = initEvent.phase,
                    step = initEvent.step,
                    detail = initEvent.detail,
                ),
            )
        }
    }

    suspend fun event(phase: String, step: String, detail: String, metrics: Map<String, String> = emptyMap()) {
        val key = currentKey()
        mutex.withLock {
            val state = traces[key] ?: return
            if (!state.active) return
            val e = TraceEvent(
                elapsedMs = System.currentTimeMillis() - state.startTime,
                phase = phase,
                step = step,
                detail = detail,
                metrics = metrics,
            )
            state.events.add(e)
            _traceFlow.value = state.events.toList()
            LogSessionStore.appendEvent(
                sessionId = state.sessionId,
                event = LogSessionStore.LogSessionEvent(
                    ts = state.startTime + e.elapsedMs,
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
        val key = currentKey()
        mutex.withLock {
            val state = traces[key] ?: return
            if (!state.active) return
            val totalMs = System.currentTimeMillis() - state.startTime
            // TraceEvent 实参先求值 (此时 trace_end 未入列), 总数 = events.size + 1
            val e = TraceEvent(
                elapsedMs = totalMs,
                phase = "FINISH",
                step = "trace_end",
                detail = "Total: ${totalMs}ms, ${state.events.size + 1} events",
            )
            state.events.add(e)
            _traceFlow.value = state.events.toList()
            state.active = false
            LogSessionStore.appendEvent(
                sessionId = state.sessionId,
                event = LogSessionStore.LogSessionEvent(
                    ts = state.startTime + totalMs,
                    phase = e.phase,
                    step = e.step,
                    detail = e.detail,
                ),
            )
            LogSessionStore.finishSession(state.sessionId)
            traces.remove(key)
            isActive = traces.isNotEmpty()
            Log.i(TAG, "=== TRACE END ($key): ${totalMs}ms, ${state.events.size} events (session=${state.sessionId}) ===")
        }
    }

    /**
     * 兜底收尾: 本键位会话仍 active 时强制结束并落盘。
     * 调用位置: 生成流程 onCompletion (成功/失败/取消全路径)。
     * v4.8.118: 只收尾当前协程键位 — 并行时互不误关。
     */
    suspend fun finishTraceIfActive() {
        val key = currentKey()
        if (traces[key]?.active != true) return
        Log.i(TAG, "finishTraceIfActive: closing open session for key=$key")
        finishTrace()
    }

    suspend fun getSessionId(): String = traces[currentKey()]?.sessionId ?: ""
}
