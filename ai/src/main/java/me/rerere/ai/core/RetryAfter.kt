package me.rerere.ai.core

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * v5.0.61：**尊重服务器重试建议**（`Retry-After`）。
 *
 * 之前 429/503 我们一律按自己的退避序列闷头重试 —— 服务器明明告诉你"等 30 秒"，
 * 我们 0.5 秒就再去撞一次，社区实锤的"越炸越狠"就是这么来的。
 * OpenAI / Anthropic SDK 都读这个头（上限 120s），Codex 连预算耗尽后仍遵守服务器的 deadline。
 *
 * 解析两种合法形态（RFC 7231）：
 *  - 秒数：`Retry-After: 30`
 *  - HTTP-date：`Retry-After: Wed, 21 Oct 2026 07:28:00 GMT`
 *
 * 一律封顶 [MAX_MS]，避免上游给个荒谬值把对话挂死。
 */
object RetryAfter {
    /** 与 OpenAI SDK 的 MAX_RETRY_AFTER_DELAY 对齐 */
    const val MAX_MS = 120_000L

    /** 解析失败/不存在 → null（调用方回退到本地退避序列） */
    fun parse(header: String?): Long? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // ① 秒数
        raw.toLongOrNull()?.let { sec ->
            return (sec * 1000L).coerceIn(0L, MAX_MS)
        }

        // ② HTTP-date
        return runCatching {
            val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (at.toEpochMilli() - System.currentTimeMillis()).coerceIn(0L, MAX_MS)
        }.getOrNull()
    }
}

/**
 * v5.0.61：带服务器重试建议的 IO 异常。
 *
 * 由 Provider 在 429/503（以及任何带 `Retry-After` 头的失败）抛出；
 * `GenerationHandler` 捕获后把这个时长交给 `RetryDecision`，优先于本地退避。
 *
 * 仍是 [IOException] 子类 → 不需要改任何重试链的 catch 类型。
 */
class RetryAfterException(
    /** 服务器建议的等待时长（已封顶 [RetryAfter.MAX_MS]，未解析到时不构造本类） */
    val retryAfterMs: Long,
    val httpCode: Int,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
