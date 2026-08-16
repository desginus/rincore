package me.rerere.rikkahub.data.ai


/* ───【自研】CallTracer.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 消息处理全链路追踪。
 * 记录最近一条消息从预处理到 API 调用到后处理的完整运行流程。
 *
 * UI 通过 [traceFlow] 订阅实时更新.
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

    private var events = mutableListOf<TraceEvent>()
    private val _traceFlow = MutableStateFlow<List<TraceEvent>>(emptyList())
    val traceFlow: StateFlow<List<TraceEvent>> = _traceFlow.asStateFlow()

    private var startTime: Long = 0L
    private var traceId: String = ""

    @Volatile
    var isActive = false
        private set

    fun startTrace(id: String = "") {
        Log.i(TAG, "=== TRACE START: $id ===")
        startTime = System.currentTimeMillis()
        traceId = id
        events = mutableListOf()
        isActive = true
        events.add(TraceEvent(elapsedMs = 0, phase = "INIT", step = "trace_start", detail = "Trace ID: $id"))
        _traceFlow.value = events.toList()
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
            Log.d(TAG, "[+${e.elapsedMs}ms] ${e.phase}/${e.step}: ${e.detail}")
        }
    }

    suspend fun finishTrace() {
        mutex.withLock {
            val totalMs = System.currentTimeMillis() - startTime
            // TraceEvent 实参先求值 (此时 trace_end 未入列), 总数 = events.size + 1
            events.add(TraceEvent(
                elapsedMs = totalMs,
                phase = "FINISH",
                step = "trace_end",
                detail = "Total: ${totalMs}ms, ${events.size + 1} events",
            ))
            _traceFlow.value = events.toList()
            isActive = false
            Log.i(TAG, "=== TRACE END: ${totalMs}ms, ${events.size} events ===")
        }
    }

    fun getTraceId(): String = traceId
}
