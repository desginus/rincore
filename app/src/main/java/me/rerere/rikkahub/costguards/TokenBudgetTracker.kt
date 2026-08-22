package me.rerere.rikkahub.costguards


/* ───【自研】TokenBudgetTracker.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.model.Conversation

/**
 * Pure-function token aggregator over a [Conversation]. Walks the currently-
 * selected branch, sums every non-null [TokenUsage], and reports the running totals.
 *
 * Read-only — never mutates conversation or assistant settings.
 */
object TokenBudgetTracker {

    data class Totals(
        val inputTokens: Long,
        val outputTokens: Long,
        val totalTokens: Long,
        val perMessageMax: Long,
        val messageCount: Int,
    )

    enum class BudgetStatus {
        UNDER_SOFT,
        WARN,
        OVER_HARD,
        NO_BUDGET,
    }

    data class Snapshot(
        val totals: Totals,
        val softCap: Int?,
        val hardCap: Int?,
        val status: BudgetStatus,
    )

    fun aggregate(conversation: Conversation): Totals {
        var input = 0L
        var output = 0L
        var total = 0L
        var perMax = 0L
        var count = 0
        // v3.8.43: 口径修正 — 每条 assistant 的 usage.promptTokens 都是"该轮完整
        // 上下文"而非增量, 逐条求和会数倍虚高 (用户实测: 实际十几K 显示六十几K)。
        // 正确语义: input = 最近一轮 promptTokens (当前上下文真实大小);
        // output = 全部轮次 completionTokens 累计 (真实生成量); total = input + output。
        var lastInput = 0L
        var lastCached = 0L
        for (node in conversation.messageNodes) {
            val msg = node.messages.getOrNull(node.selectIndex) ?: continue
            val usage = msg.usage ?: continue
            if (usage.promptTokens > 0) {
                lastInput = usage.promptTokens.toLong()
                lastCached = usage.cachedTokens.toLong()
            }
            output += usage.completionTokens.toLong()
            val totalThis = (usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)).toLong()
            if (totalThis > perMax) perMax = totalThis
            count++
        }
        input = lastInput
        total = input + output
        return Totals(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
            perMessageMax = perMax,
            messageCount = count,
        )
    }

    fun classify(totals: Totals, softCap: Int?, hardCap: Int?): BudgetStatus {
        if (softCap == null && hardCap == null) return BudgetStatus.NO_BUDGET
        if (hardCap != null && totals.totalTokens >= hardCap) return BudgetStatus.OVER_HARD
        if (softCap != null && totals.totalTokens >= softCap) return BudgetStatus.WARN
        return BudgetStatus.UNDER_SOFT
    }

    fun snapshot(conversation: Conversation, softCap: Int?, hardCap: Int?): Snapshot {
        val totals = aggregate(conversation)
        return Snapshot(totals = totals, softCap = softCap, hardCap = hardCap,
            status = classify(totals, softCap, hardCap))
    }
}
