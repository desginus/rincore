package me.rerere.rikkahub.data.ai.protocol

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 消息协议层 — 发送前的结构性保证。
 *
 * 这是传输链路的固定环节（非补丁）: 无论 transformers / 注入 / 其他环节
 * 如何修改消息, 协议层在发送前保证最终结构合规（严格端点要求）:
 *   1. 首条消息必须为 system
 *   2. tool_call 必须与其 tool 结果配对（无孤儿、无残留）
 *
 * 协议层是幂等的: 对合规消息零修改, 对违规消息结构性修复。
 */
object MessageProtocol {
    private const val TAG = "MessageProtocol"

    /** 发送前协议强制 — 管线最后一环（幂等） */
    fun enforce(messages: List<UIMessage>): List<UIMessage> {
        var result = messages
        result = ensureSystemFirst(result)
        result = sanitizeToolSequence(result)
        return result
    }

    /** 首条必须 system — 无 system 时创建空 system；首条非 system 时合并文本并前置 */
    fun ensureSystemFirst(messages: List<UIMessage>): List<UIMessage> {
        if (messages.isEmpty()) return listOf(UIMessage.system(""))
        if (messages.first().role == MessageRole.SYSTEM) return messages

        // 首条非 system（transforms/注入异常产物）— 结构性修复: 文本并入 system, 非文本 parts 保留在原消息
        val first = messages.first()
        val systemText = first.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
        val nonTextParts = first.parts.filterNot { it is UIMessagePart.Text }
        val system = when {
            systemText.isBlank() && nonTextParts.isEmpty() -> UIMessage.system("")
            systemText.isBlank() -> UIMessage.system("").copy(parts = nonTextParts)
            else -> UIMessage.system(systemText).copy(
                parts = listOf(UIMessagePart.Text(systemText)) + nonTextParts
            )
        }
        Log.w(TAG, "ensureSystemFirst: 首条非 system（role=${first.role}）— 已结构性修复")
        return listOf(system) + messages.drop(1)
    }

    /** tool 序列配对清洗 — 孤儿 tool_call / 残留 tool 结果移除, 循环至稳定 */
    fun sanitizeToolSequence(messages: List<UIMessage>): List<UIMessage> {
        var current = messages
        var changed = true
        var guard = 0
        while (changed && guard < 8) {
            changed = false
            guard++

            val callIds = mutableSetOf<String>()
            val resultIds = mutableSetOf<String>()
            current.forEach { msg ->
                msg.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.ToolCall -> callIds.add(part.toolCallId)
                        is UIMessagePart.Tool -> resultIds.add(part.toolCallId)
                        is UIMessagePart.ToolResult -> resultIds.add(part.toolCallId)
                        else -> {}
                    }
                }
            }
            val orphanCalls = callIds - resultIds
            val orphanResults = resultIds - callIds

            val cleaned = current.mapNotNull { msg ->
                when (msg.role) {
                    MessageRole.ASSISTANT -> {
                        val kept = msg.parts.filterNot {
                            it is UIMessagePart.ToolCall && it.toolCallId in orphanCalls
                        }
                        when {
                            kept.isEmpty() -> { changed = true; null }
                            kept.size != msg.parts.size -> { changed = true; msg.copy(parts = kept) }
                            else -> msg
                        }
                    }
                    MessageRole.USER -> {
                        // when 分支内智能转换 — (A || B) 联合类型无法直接访问 toolCallId
                        val kept = msg.parts.filterNot { part ->
                            when (part) {
                                is UIMessagePart.Tool -> part.toolCallId in orphanResults
                                is UIMessagePart.ToolResult -> part.toolCallId in orphanResults
                                else -> false
                            }
                        }
                        if (kept.size != msg.parts.size) { changed = true; msg.copy(parts = kept) }
                        else msg
                    }
                    else -> msg
                }
            }
            current = cleaned
        }
        if (guard >= 8) {
            Log.w(TAG, "sanitizeToolSequence: 达到循环上限（消息序列可能仍含孤儿）")
        }
        return current
    }
}
