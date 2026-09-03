package me.rerere.rikkahub.data.usage

import java.time.Instant

/**
 * v3.13.2: 用量小卡统一数据层 — OpenCode 与 Command Code 密钥在
 * 卡片/多密钥视图下同构渲染 (四环: 5h/周/月/重置), 仅数据来源不同。
 * 跨族密钥按 key 前缀分流到对应 Api 查询后统一转本结构。
 */
data class UsageMiniCardData(
    val p5: Int?,
    val pw: Int?,
    val pm: Int?,
    val resetElapsedPct: Int,
    val resetRemainingMs: Long?,   // null = 无重置信息
    val resetWindowMs: Long,
)

fun openCodeMiniCard(u: UsageApi.UsageResult): UsageMiniCardData {
    val now = System.currentTimeMillis()
    data class W(val resetsAt: String?, val windowMs: Long)
    val nearest = listOf(
        W(u.rolling.resetsAt, 5L * 3_600_000),
        W(u.weekly.resetsAt, 7L * 24 * 3_600_000),
        W(u.monthly.resetsAt, 30L * 24 * 3_600_000),
    ).mapNotNull { w ->
        val ts = w.resetsAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return@mapNotNull null
        if (ts > now) w to ts else null
    }.minByOrNull { (_, ts) -> ts - now }
    val elapsed = if (nearest == null) 0f else {
        val (w, ts) = nearest
        ((now - (ts - w.windowMs)).coerceAtLeast(0L)).toFloat() / w.windowMs * 100f
    }.coerceIn(0f, 100f)
    return UsageMiniCardData(
        p5 = u.rolling.percent,
        pw = u.weekly.percent,
        pm = u.monthly.percent,
        resetElapsedPct = elapsed.toInt(),
        resetRemainingMs = nearest?.let { (_, ts) -> ts - now },
        resetWindowMs = nearest?.first?.windowMs ?: (5L * 3_600_000),
    )
}

fun commandCodeMiniCard(u: CommandCodeUsageApi.CommandCodeUsageResult): UsageMiniCardData {
    val now = System.currentTimeMillis()
    data class W(val resetsAtMs: Long?, val windowMs: Long)
    val nearest = listOf(
        W(u.fiveHour?.resetAtMs, 5L * 3_600_000),
        W(u.weekly?.resetAtMs, 7L * 24 * 3_600_000),
        W(u.currentPeriodEnd?.let {
            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        }, 30L * 24 * 3_600_000),
    ).mapNotNull { w ->
        val ts = w.resetsAtMs ?: return@mapNotNull null
        if (ts > now) w to ts else null
    }.minByOrNull { (_, ts) -> ts - now }
    val elapsed = if (nearest == null) 0f else {
        val (w, ts) = nearest
        ((now - (ts - w.windowMs)).coerceAtLeast(0L)).toFloat() / w.windowMs * 100f
    }.coerceIn(0f, 100f)
    return UsageMiniCardData(
        p5 = u.fiveHour?.percent,
        pw = u.weekly?.percent,
        pm = u.monthlyUsedPercent,
        resetElapsedPct = elapsed.toInt(),
        resetRemainingMs = nearest?.let { (_, ts) -> ts - now },
        resetWindowMs = nearest?.first?.windowMs ?: (5L * 3_600_000),
    )
}

/** 剩余时间进制文本 (24h 进位天, 7 天进位周, 对齐 OpenCode formatRemaining) */
fun formatRemainingMs(remainMs: Long?): String {
    if (remainMs == null) return "无重置信息"
    val remain = remainMs.coerceAtLeast(0L)
    val days = remain / 86_400_000
    val hours = (remain % 86_400_000) / 3_600_000
    val mins = (remain % 3_600_000) / 60_000
    return when {
        days >= 7 -> {
            val weeks = days / 7
            val rd = days % 7
            if (rd > 0) "${weeks}周 ${rd}天 后重置" else "${weeks}周 后重置"
        }
        days > 0 -> "${days}天 ${hours}小时 后重置"
        hours > 0 -> "${hours}小时 ${mins}分钟 后重置"
        else -> "${mins}分钟 后重置"
    }
}
