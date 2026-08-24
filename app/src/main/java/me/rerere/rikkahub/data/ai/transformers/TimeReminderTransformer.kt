/**
 * 时间提醒转换器 — 模块: A. 传输链 / transformers
 *
 * 职责: 按消息固定时间戳注入当前时间 (缓存友好 — 不引入动态前缀)。
 * 注入策略: 基于消息时间戳, 保证 system 前缀稳定 → 缓存命中不被打断。
 *
 * 问题定位: 缓存断层与时间注入相关 → 查本文件注入位置与时戳取值
 */
package me.rerere.rikkahub.data.ai.transformers


/* ───【原版对齐】TimeReminderTransformer.kt | 差异 ±25 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.toJavaInstant

private const val TIME_GAP_THRESHOLD_SECONDS = 3600L // 1 小时

/**
 * 时间提醒注入转换器
 *
 * 在时间间隔较大的消息之前自动注入 <time_reminder>，帮助 AI 了解对话的时间间隔
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        return applyTimeReminder(messages)
    }
}

internal fun applyTimeReminder(messages: List<UIMessage>): List<UIMessage> {
    // v3.6.63: 幂等 — 已注入过时间提醒则直接返回, 不再重复插入。
    // 根因: 工具循环每个 step 都重新执行本转换器, 之前每次对第一条 USER 重新插
    // <time_reminder> USER 消息 → windowStart 每轮后移 2 → history 每轮 +2
    // → 降维压缩包每轮增量追加 → 缓存断在压缩包 (16.9K 后停)。
    // v3.10.13: 合并模式 (根治 Console Go 400) — 之前插入独立 <time_reminder>
    // USER 消息 → 消息序列出现连续 user → Anthropic 角色交替硬校验 →
    // invalid params 400 (2013)。reminder 文本合并进目标 USER 消息文本开头,
    // 消息数量/角色序列不变; 原版无此 transformer 故从未触发。
    val hasReminder = messages.any { msg ->
        msg.role == MessageRole.USER && msg.toText().contains("<time_reminder>")
    }
    if (hasReminder) return messages
    val result = mutableListOf<UIMessage>()
    val tz = TimeZone.currentSystemDefault()

    var firstUserFound = false
    for (i in messages.indices) {
        val current = messages[i]
        if (current.role == MessageRole.USER) {
            val currInstant = current.createdAt.toInstant(tz)
            if (!firstUserFound) {
                firstUserFound = true
                result.add(mergeTimeReminder(current, null, currInstant))
            } else {
                val previous = messages[i - 1]
                val prevInstant = previous.createdAt.toInstant(tz)
                val gapSeconds = (currInstant - prevInstant).inWholeSeconds

                if (gapSeconds > TIME_GAP_THRESHOLD_SECONDS) {
                    result.add(mergeTimeReminder(current, gapSeconds, currInstant))
                } else {
                    result.add(current)
                }
            }
        } else {
            result.add(current)
        }
    }

    return result
}

/** v3.10.13: reminder 文本合并进消息第一个 Text part (无 Text part 则前插) */
private fun mergeTimeReminder(
    message: UIMessage,
    gapSeconds: Long?,
    instant: Instant,
): UIMessage {
    val reminder = buildTimeReminderContent(gapSeconds, instant)
    val parts = message.parts
    val textIdx = parts.indexOfFirst { it is me.rerere.ai.ui.UIMessagePart.Text }
    val newParts = if (textIdx >= 0) {
        val t = parts[textIdx] as me.rerere.ai.ui.UIMessagePart.Text
        parts.toMutableList().apply { this[textIdx] = t.copy(text = reminder + t.text) }
    } else {
        listOf(me.rerere.ai.ui.UIMessagePart.Text(reminder)) + parts
    }
    return message.copy(parts = newParts)
}

private fun buildTimeReminderContent(gapSeconds: Long?, instant: Instant): String {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    return if (gapSeconds != null) {
        val gapText = formatGap(gapSeconds)
        "<time_reminder>Current time: $dayOfWeek, $timeStr ($gapText since last message)</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $timeStr</time_reminder>"
    }
}

private fun formatGap(seconds: Long): String {
    return when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }
}
