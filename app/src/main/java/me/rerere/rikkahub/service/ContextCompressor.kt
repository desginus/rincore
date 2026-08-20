package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.math.abs

/**
 * v3.8.28: 上下文压缩边界智能判定 — 以"对话轮"为单位定位保留边界。
 *
 * 原则 (用户明确定义):
 * 1. 压缩以轮(回合)为单位: 一轮 = 一条 user 输入 + 其 assistant 回复(可含工具调用)。
 *    轮数少(如两轮共 4 条)时直接保留最新一轮, 不依赖 token。
 * 2. token 仅用于历史长(轮数多)时精细定位: 从最新轮往旧累计上下文 token,
 *    累计达到 60% 的轮作为边界, 四舍五入到最近整轮。
 * 3. 压缩必须真实发生且不伤及最新内容: 保留最近 k 轮 (1 <= k <= 轮数-1) —
 *    始终至少压缩一轮, 最新一轮内容必保留(绝不压缩刚发出的消息)。
 * 4. UI 按"保留最近消息数量(条数)"显示推荐值, 供用户手动微调。
 */
object ContextCompressor {

    /** 轻量 token 估算: 非 ASCII(CJK)≈1 token/字符, ASCII≈1 token/4字符。仅用于边界定位, 无需精确。 */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 1
        var ascii = 0
        var nonAscii = 0
        for (ch in text) {
            if (ch.code < 128) ascii++ else nonAscii++
        }
        return maxOf(1, nonAscii + ascii / 4)
    }

    private fun textOf(m: UIMessage): String = m.summaryAsText(maxLength = Int.MAX_VALUE)

    /**
     * 按轮分组: 每条 user 与其后 assistant(含工具消息)为一轮。
     * 复杂情况处理:
     * - 连续 user 未回复: 归同一未完成轮 (用户一次输入多段)
     * - 孤立 assistant/tool(无前置 user): 自成一轮, 保持原样不破坏
     */
    fun splitIntoRounds(messages: List<UIMessage>): List<List<UIMessage>> {
        val rounds = mutableListOf<MutableList<UIMessage>>()
        var cur: MutableList<UIMessage>? = null
        for (m in messages) {
            if (m.role == MessageRole.USER) {
                // 新 user 且当前轮已被 assistant 回复过 → 关闭当前轮开新轮
                if (cur != null && cur.any { it.role == MessageRole.ASSISTANT }) {
                    rounds.add(cur)
                    cur = mutableListOf()
                }
                if (cur == null) cur = mutableListOf()
            } else if (cur == null) {
                cur = mutableListOf()
            }
            cur!!.add(m)
        }
        cur?.takeIf { it.isNotEmpty() }?.let { rounds.add(it) }
        return rounds
    }

    /**
     * 推荐保留条数 (条数, 供 UI 显示与微调):
     * - 轮数不足(<=1) → 0 (无可压缩空间)
     * - 从最新轮往旧累计 token, 首个达到 60% 上下文 token 的轮数为候选 k,
     *   四舍五入(k-1 轮累计更接近目标则回退), 最终 k ∈ [1, 轮数-1]
     * - 返回保留最近 k 轮的总消息条数; 最后保留的完整轮 = 最新 k 轮
     */
    fun recommendedKeepMessages(messages: List<UIMessage>): Int {
        if (messages.size < 2) return 0
        val rounds = splitIntoRounds(messages)
        if (rounds.size <= 1) return 0
        val roundTokens = rounds.map { r -> r.sumOf { estimateTokens(textOf(it)) }.toDouble() }
        val total = roundTokens.sum()
        if (total <= 0) return maxOf(1, rounds.last().size)
        val target = total * 0.6
        var acc = 0.0
        var k = 0
        while (k < rounds.size && acc < target) {
            acc += roundTokens[rounds.size - 1 - k]
            k++
        }
        if (k > 1) {
            val prevAcc = acc - roundTokens[rounds.size - k]
            if (abs(prevAcc - target) < abs(acc - target)) k--
        }
        k = k.coerceIn(1, rounds.size - 1)
        return rounds.takeLast(k).sumOf { it.size }
    }
}
