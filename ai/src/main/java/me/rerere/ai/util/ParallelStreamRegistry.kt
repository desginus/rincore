/* 【域 B·AI 传输】 */
package me.rerere.ai.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * v4.8.117 (B140): 同 host 并行流登记 — 聚合网关完成门"并行上下文"判据的唯一事实源。
 *
 * 背景: 多对话并行时, 被上游竞争掐断的流关流形态 (无 [DONE]/finish_reason/usage 尾包,
 * 行完整, 尾部无截断特征) 与 ox 系"无信号收尾"通道的正常结束在信号层面完全相同 —
 * 唯一可靠判别信号 = "同 host 是否还有另一条流在跑"。本登记表提供该判据。
 *
 * 契约: enter/exit 必须严格配对。ChatCompletionsAPI 侧 enter 于 callbackFlow 块首,
 * exit 于 awaitClose 单点 (覆盖正常关闭/取消/重试链全部终结路径)。
 * 计数归零即移除键, 无内存滞留。
 */
object ParallelStreamRegistry {

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** 流开始 (host = baseUrl host) */
    fun enter(host: String) {
        counts.computeIfAbsent(host) { AtomicInteger(0) }.incrementAndGet()
    }

    /** 流终结 — 单点调用, 不得重复 */
    fun exit(host: String) {
        counts.computeIfPresent(host) { _, c ->
            if (c.decrementAndGet() <= 0) null else c
        }
    }

    /** 该 host 当前是否处于"并行" (计数 > 1, 含调用方自身) */
    fun isParallel(host: String): Boolean = (counts[host]?.get() ?: 0) > 1

    /** 仅测试/诊断用 */
    fun activeCount(host: String): Int = counts[host]?.get() ?: 0
}
