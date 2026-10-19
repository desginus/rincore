package me.rerere.ai.core

/* ───【自研】WatchdogPolicy.kt | 原版 2.5.1 无此文件
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 4.0.0 重写工程 · 模块 2: 流式三阶段 watchdog (原 ChatCompletionsAPI 与
 * ClaudeProvider 各自内联 ~60 行逐字复制的同一套状态机 → 单一实现双族共用)。
 *
 * 阶段状态机 (v3.11.13 定版, "多尝试, 少静默"; v5.0.12 思考安全化改版):
 *   阶段1 header 建连/响应头前 60s — 网关冷启动/挂起快速断开 → 上层快速重试
 *   阶段2 first-event 已连接未出首事件 480s — 上游思考/排队, 重试无意义, 耐心等
 *     (v5.0.12: 150s → 480s。用户实证: 思考链被网关缓冲时长思考在 150s 被判死 →
 *      静默重试重放 → 再判死 = "对话建立时长思考必被中断" 的根因之一。480s <
 *      readTimeout 600s, 保证判死走本路径而不是 SocketTimeout 误判发起阶段)
 *   阶段3 thinking 思考已开始未见正文 480s — 思考链 delta 到过但中途静默
 *     (v5.0.12 新增: reasoning delta 刷新过空闲标记后, 中途静默是模型在算,
 *      不是断流; 正文一旦出现立即回到阶段4)
 *   阶段4 stream 正文流出中 150s (v4.8.125 统一; 原 90/120s) — 流间隙上限
 *     (正文间隙 >150s 是真断流, 走无缝续写/重试链, 本版不动)
 *
 * 行为等价不变量:
 * - 每 15s tick 检查一次
 * - closeMsg 三条文案逐字保留 (是 GenerationHandler 重试链判据输入,
 *   "平台连接无响应"/"生成无有效数据超时" 字样驱动 Init/Stream 分流)
 * - 超时 close(IOException) 并退出循环
 */
class StreamWatchdog(
    // v4.8.125: 保留入参 (调用面兼容; 流阶段上限已统一 150s, 不再按族区分)
    isOpencode: Boolean,
    private val headerReceived: java.util.concurrent.atomic.AtomicBoolean,
    private val hasData: java.util.concurrent.atomic.AtomicBoolean,
    private val lastEventAt: java.util.concurrent.atomic.AtomicLong,
    private val onTimeout: (java.io.IOException) -> Unit,
    // v5.0.12: 思考阶段感知 (可选; 不传 = 思考阶段不可识别, 保持旧行为)
    // reasoningSeen: 思考链 delta 已到过; contentSeen: 正文 delta 已到过
    private val reasoningSeen: java.util.concurrent.atomic.AtomicBoolean? = null,
    private val contentSeen: java.util.concurrent.atomic.AtomicBoolean? = null,
) {
    // v3.11.35: header 判死 25s — 吸收网关冷启动典型 10-20s
    // v4.3.2 (BUG11): 25s 误杀大上下文慢网关 — 用户实证: 428 工具 schema + 工具
    // 结果回传的请求, glm 网关 TTFT 30s+ (处理工具 schema 集群), 25s 判死 → 回滚
    // 重试 → 又 25s → 重试链耗尽 → "数据彻底卡死"表象。60s 吸收重负载 TTFT;
    // 真死网关 60s 后仍走重试链, 最终报错不受影响。
    val headerLimitMs = 60_000L
    // v5.0.12: first-event 150s → 480s — 150s 是"长思考必被中断"的元凶之一
    // (设计注释本写明"上游思考, 重试无意义, 耐心等", 150s 与该承诺矛盾)。
    // 必须 < readTimeout(600s), 否则 OkHttp SocketTimeout 抢先触发会被误分类
    // 为发起阶段 (可见"网络连接失败"且走 6 次链, 每次再等满 10 分钟)。
    val firstEventLimitMs = 480_000L
    // v4.8.125 (用户实证: "输出途中莫名其妙中断, 重试很久"): 流阶段上限 90/120s → 150s 统一。
    // 旧 90s (OC 通道) 对慢上游 (长思考/间歇静默 — keepalive 不刷新空闲标记) 误杀面
    // 偏大; "误杀 → 重试重放 → 再误杀" 正是"断连 + 恢复慢"的放大器。真死连接由
    // readTimeout (600s, v5.0.12 对齐上游) 兜底。正文流出后的间隙判死保持耐心档
    // 但不放大 — 那是真断流域, 走无缝续写/重试链。
    val streamLimitMs = 150_000L
    // v5.0.12: 思考阶段上限 — 思考链 delta 出现过、正文未出现时的静默容忍。
    // 与 first-event 同档 (480s): 两者都是"上游在算, 杀了就是全部重来"。
    val thinkingLimitMs = 480_000L

    data class Phase(val name: String, val limitMs: Long, val closeMsg: String)

    // v5.0.12: 判据尾串常量 — GenerationHandler 重试分类按尾串区分
    // 思考类超时 (小预算) 与正文流断流 (18 次无缝续写链)。
    companion object {
        const val TICK_INTERVAL_MS = 15_000L
        const val TAIL_FIRST_EVENT = "上游思考或排队中无输出"
        const val TAIL_THINKING = "思考阶段长时间静默"
        const val TAIL_STREAM_BREAK = "平台断流或卡死"

        /** 流阶段上限 (秒, 供日志展示) — v4.8.125: 统一 150s */
        fun streamLimitSec(isOpencode: Boolean): Long = 150L

        /**
         * 标准协程 watchdog 循环 — 两 Provider 原内联 launch 块的统一形态。
         * 超时后 break 退出 (与旧实现一致: 一次判死即关流)。
         */
        fun launchIn(
            scope: CoroutineScope,
            watchdog: StreamWatchdog,
            onTimeoutLog: (String) -> Unit,
        ): kotlinx.coroutines.Job = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MS)
                val fired = watchdog.tick()
                if (fired != null) {
                    onTimeoutLog(fired)
                    break
                }
            }
        }
    }

    fun currentPhase(): Phase {
        val contentSeen = contentSeen?.get() == true
        val reasoningSeen = reasoningSeen?.get() == true
        return when {
            hasData.get() && contentSeen -> Phase("stream", streamLimitMs,
                "生成无有效数据超时 (${streamLimitMs / 1000}s): $TAIL_STREAM_BREAK")
            hasData.get() && reasoningSeen -> Phase("thinking", thinkingLimitMs,
                "生成无有效数据超时 (${thinkingLimitMs / 1000}s): $TAIL_THINKING或上游卡死")
            // 有数据但调用方未接思考标记 (旧调用面) — 保持正文流档, 行为不变
            hasData.get() -> Phase("stream", streamLimitMs,
                "生成无有效数据超时 (${streamLimitMs / 1000}s): $TAIL_STREAM_BREAK")
            headerReceived.get() -> Phase("first-event", firstEventLimitMs,
                "生成无有效数据超时 (${firstEventLimitMs / 1000}s): $TAIL_FIRST_EVENT")
            else -> Phase("header", headerLimitMs,
                "平台连接无响应 (${headerLimitMs / 1000}s): 网关冷启动或挂起")
        }
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
