package me.rerere.ai.core

/**
 * 4.0.0 重写工程 · 模块 2: 流式三阶段 watchdog (原 ChatCompletionsAPI 与
 * ClaudeProvider 各自内联 ~60 行逐字复制的同一套状态机 → 单一实现双族共用)。
 *
 * 三阶段状态机 (v3.11.13 定版, "多尝试, 少静默"):
 *   阶段1 header 建连/响应头前 25s — 网关冷启动/挂起快速断开 → 上层快速重试
 *   阶段2 first-event 已连接未出首事件 150s — 上游思考, 重试无意义, 耐心等
 *   阶段3 stream 流出中 90s(opencode)/120s(其他) — 流间隙上限
 *
 * 行为等价不变量:
 * - 每 15s tick 检查一次
 * - closeMsg 三条文案逐字保留 (是 GenerationHandler 重试链判据输入,
 *   "平台连接无响应"/"生成无有效数据超时" 字样驱动 Init/Stream 分流)
 * - 超时 close(IOException) 并退出循环
 */
class StreamWatchdog(
    private val isOpencode: Boolean,
    private val headerReceived: java.util.concurrent.atomic.AtomicBoolean,
    private val hasData: java.util.concurrent.atomic.AtomicBoolean,
    private val lastEventAt: java.util.concurrent.atomic.AtomicLong,
    private val onTimeout: (java.io.IOException) -> Unit,
) {
    // v3.11.35: header 判死 25s — 吸收网关冷启动典型 10-20s
    val headerLimitMs = 25_000L
    val firstEventLimitMs = 150_000L
    val streamLimitMs = if (isOpencode) 90_000L else 120_000L

    data class Phase(val name: String, val limitMs: Long, val closeMsg: String)

    fun currentPhase(): Phase = when {
        hasData.get() -> Phase("stream", streamLimitMs,
            "生成无有效数据超时 (${streamLimitMs / 1000}s): 平台断流或卡死")
        headerReceived.get() -> Phase("first-event", firstEventLimitMs,
            "生成无有效数据超时 (${firstEventLimitMs / 1000}s): 上游思考或排队中无输出")
        else -> Phase("header", headerLimitMs,
            "平台连接无响应 (${headerLimitMs / 1000}s): 网关冷启动或挂起")
    }

    /** 单次检查 (供协程循环调用); 超时返回触发文案, 未超时返回 null */
    fun tick(): String? {
        val idleMs = System.currentTimeMillis() - lastEventAt.get()
        val phase = currentPhase()
        return if (idleMs > phase.limitMs) {
            val msg = phase.closeMsg
            onTimeout(java.io.IOException(msg))
            msg
        } else null
    }

    companion object {
        const val TICK_INTERVAL_MS = 15_000L

        /** 流阶段上限 (秒, 供日志展示) */
        fun streamLimitSec(isOpencode: Boolean): Long = (if (isOpencode) 90_000L else 120_000L) / 1000

        /**
         * 标准协程 watchdog 循环 — 两 Provider 原内联 launch 块的统一形态。
         * 超时后 break 退出 (与旧实现一致: 一次判死即关流)。
         */
        fun launchIn(
            scope: kotlinx.coroutines.CoroutineScope,
            watchdog: StreamWatchdog,
            onTimeoutLog: (String) -> Unit,
        ): kotlinx.coroutines.Job = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(TICK_INTERVAL_MS)
                val fired = watchdog.tick()
                if (fired != null) {
                    onTimeoutLog(fired)
                    break
                }
            }
        }
    }
}

/**
 * 4.0.0 重写工程 · 模块 2b: 2013 降级重试判定 (原 ClaudeProvider 两处
 * 内联判定 → 单一函数)。MiniMax 等严格校验上游间歇性 invalid params,
 * 首次 400+2013 用最简请求体重试一次。
 */
object MinimalBodyRetry {
    const val MARKER = "2013"

    /** 是否触发降级: HTTP 400 + 响应体含 2013 + 尚未降级过 (单次机会) */
    fun shouldDegrade(httpCode: Int?, bodyContains2013: Boolean, alreadyAttempted: Boolean): Boolean =
        !alreadyAttempted && httpCode == 400 && bodyContains2013
}
