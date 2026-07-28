package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 消息处理全链路追踪。
 * 记录最近一条消息从预处理到 API 调用到后处理的完整运行流程。
 */
object CallTracer {
    private const val TAG = "CallTracer"
    private val mutex = Mutex()

    data class TraceEvent(
        val elapsedMs: Long,      // 距 trace 开始的毫秒数
        val phase: String,        // 阶段名
        val step: String,         // 具体步骤
        val detail: String,       // 运行细节
        val metrics: Map<String, String> = emptyMap(),
    )

    private var events = mutableListOf<TraceEvent>()
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
        event("INIT", "trace_start", "Trace ID: $id")
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
            Log.d(TAG, "[+${e.elapsedMs}ms] ${e.phase}/${e.step}: ${e.detail}")
        }
    }

    suspend fun finishTrace() {
        mutex.withLock {
            val totalMs = System.currentTimeMillis() - startTime
            events.add(TraceEvent(
                elapsedMs = totalMs,
                phase = "FINISH",
                step = "trace_end",
                detail = "Total: ${totalMs}ms, ${events.size} events",
            ))
            isActive = false
            Log.i(TAG, "=== TRACE END: ${totalMs}ms, ${events.size} events ===")
        }
    }

    fun getTrace(): List<TraceEvent> = events.toList()

    fun getTraceId(): String = traceId
}
