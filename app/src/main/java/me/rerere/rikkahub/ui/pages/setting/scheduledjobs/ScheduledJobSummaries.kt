package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun summariseSchedule(job: ScheduledJobEntity): String = when (job.scheduleType) {
    "once" -> {
        val ms = job.atUnixMs ?: return "once (no time set)"
        "once at ${formatAbsoluteTime(ms)}"
    }
    "cron" -> {
        val expr = job.cronExpression?.trim().orEmpty()
        prettyCron(expr) ?: "custom: $expr"
    }
    else -> job.scheduleType
}

private fun formatAbsoluteTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val ageMs = ms - now
    val sameDayCutoff = 24L * 60 * 60 * 1000
    return if (ageMs in 0..sameDayCutoff) {
        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(ms))
    } else {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    }
}

private fun prettyCron(expr: String): String? {
    val e = expr.trim()
    if (e.isEmpty()) return null
    when (e.lowercase()) {
        "@hourly" -> return "every hour"
        "@daily", "@midnight" -> return "every day at 00:00"
        "@weekly" -> return "every Sunday at 00:00"
        "@monthly" -> return "first of every month at 00:00"
        "@yearly", "@annually" -> return "every Jan 1 at 00:00"
    }
    if (e.startsWith("@every", ignoreCase = true)) {
        val rest = e.substring("@every".length).trim()
        return "every $rest"
    }
    val parts = e.split(Regex("\\s+"))
    if (parts.size != 5) return null
    val (minute, hour, dom, month, dow) = parts.let { listOf(it[0], it[1], it[2], it[3], it[4]) }

    if (hour == "*" && dom == "*" && month == "*" && dow == "*" && minute.matches(Regex("\\*/\\d+"))) {
        val n = minute.removePrefix("*/").toIntOrNull() ?: return null
        return if (n == 1) "every minute" else "every $n min"
    }
    if (minute == "0" && dom == "*" && month == "*" && dow == "*" && hour.matches(Regex("\\*/\\d+"))) {
        val n = hour.removePrefix("*/").toIntOrNull() ?: return null
        return if (n == 1) "every hour" else "every $n hours"
    }
    if (dom == "*" && month == "*" && minute.toIntOrNull() in 0..59 && hour.toIntOrNull() in 0..23) {
        val hh = hour.toInt()
        val mm = minute.toInt()
        val time = "%02d:%02d".format(hh, mm)
        if (dow == "*") return "every day at $time"
        val days = parseDows(dow) ?: return null
        if (days.size in 1..6) return "every ${days.joinToString(", ")} at $time"
    }
    return null
}

private val DOW_NAMES = mapOf(
    "0" to "Sun", "7" to "Sun", "1" to "Mon", "2" to "Tue",
    "3" to "Wed", "4" to "Thu", "5" to "Fri", "6" to "Sat",
    "SUN" to "Sun", "MON" to "Mon", "TUE" to "Tue",
    "WED" to "Wed", "THU" to "Thu", "FRI" to "Fri", "SAT" to "Sat",
)

private fun parseDows(spec: String): List<String>? {
    if (spec.contains("/") || spec.contains("-")) return null
    val parts = spec.split(",").map { it.trim().uppercase() }
    val names = parts.map { DOW_NAMES[it] ?: return null }
    return names
}

fun modeLabel(job: ScheduledJobEntity): String = when (job.mode) {
    "llm" -> "LLM-driven"
    "direct" -> "direct (no LLM)"
    else -> job.mode
}

fun formatAbsoluteForDetail(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
