package com.wangzan101.mika.core.record

/**
 * 一个对话里各轮合起来用了多少 token,按每轮的运行小结([RunMetrics])算,口径和「查看过程」顶上那一行同一个
 * ([RunMetrics.tokens]:主循环每次回复报的输入加输出)。
 *
 * [missing] 是没算全的轮次:小结还没写好、旧到被清掉了(只留最近 [RunMetricsStore.DEFAULT_KEEP] 份),
 * 或有的回复服务商没报用量;这时界面写「至少」,不估算。
 */
data class ConversationTokens(val tokens: Long, val turns: Int, val missing: Int) {
    val partial: Boolean get() = missing > 0

    companion object {
        /** 一轮的 token;没有小结、或一个也没报(全是 0)时为 null,界面不写「0 Token」。 */
        fun turn(m: RunMetrics?): Long? = m?.tokens?.takeIf { it > 0 }

        /** [runIds] 是这个对话里已经结束的各轮(同一个只算一次);一个数也没有时为 null,界面不显示。 */
        fun of(runIds: List<String>, metrics: Map<String, RunMetrics>): ConversationTokens? {
            val ids = runIds.distinct()
            var sum = 0L
            var missing = 0
            for (id in ids) {
                val m = metrics[id]
                if (m == null) { missing++; continue }
                sum += m.tokens
                if (m.callsWithoutUsage > 0) missing++
            }
            if (sum <= 0) return null
            return ConversationTokens(sum, ids.size, missing)
        }
    }
}
