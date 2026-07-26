package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compression.JsonCompressor
import me.rerere.rikkahub.data.ai.compression.TextCompressor
import me.rerere.rikkahub.data.ai.compression.ToolOutputCompressor

/**
 * Headroom 风格上下文压缩器 — InputMessageTransformer。
 *
 * 在消息发送到 LLM 之前对长内容进行压缩:
 * - JSON 输出: 提取 schema + 首尾样本
 * - 工具输出: 按工具类型选择最优策略
 * - 长文本: 提取式摘要
 *
 * 保护机制:
 * - 不压缩 system 消息 (保护前缀缓存)
 * - 保护最近 N 条消息 (保留对话连续性)
 * - 过短内容不压缩 (阈值 500 chars)
 */
class ContextCompressionTransformer : InputMessageTransformer {

    companion object {
        private const val TAG = "ContextCompression"
        private const val PROTECT_RECENT_MESSAGES = 6
        private const val MIN_CHARS_TO_COMPRESS = 500
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val totalMessages = messages.size
        if (totalMessages <= PROTECT_RECENT_MESSAGES) return messages

        var charsSaved = 0L
        val compressed = messages.mapIndexed { index, message ->
            // 保护机制:
            // 1. System 消息不压缩 (影响前缀缓存)
            // 2. 最近 N 条消息不压缩
            if (message.role == me.rerere.ai.core.MessageRole.SYSTEM ||
                index >= totalMessages - PROTECT_RECENT_MESSAGES
            ) {
                return@mapIndexed message
            }

            val (newParts, saved) = compressMessage(message)
            charsSaved += saved
            if (newParts != null) {
                message.copy(parts = newParts)
            } else {
                message
            }
        }

        if (charsSaved > 0) {
            val totalChars = messages.sumOf { it.toText().length }
            Log.i(TAG, "Compression saved ~$charsSaved chars (${charsSaved * 100 / totalChars.coerceAtLeast(1)}%)")
        }

        return compressed
    }

    /**
     * 压缩单条消息的所有 parts。
     * 返回 (压缩后 parts, 节省字符数)。不需要压缩时返回 (null, 0)。
     */
    private fun compressMessage(message: UIMessage): Pair<List<UIMessagePart>?, Long> {
        var totalSaved = 0L
        var anyCompressed = false

        val compressedParts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val text = part.text
                    if (text.length < MIN_CHARS_TO_COMPRESS) return@map part

                    // 尝试 JSON 压缩
                    val jsonCompressed = JsonCompressor.compress(text)
                    if (jsonCompressed != null) {
                        totalSaved += (text.length - jsonCompressed.length)
                        anyCompressed = true
                        return@map UIMessagePart.Text(text = jsonCompressed)
                    }

                    // 尝试文本压缩
                    val textCompressed = TextCompressor.compress(text)
                    if (textCompressed != null) {
                        totalSaved += (text.length - textCompressed.length)
                        anyCompressed = true
                        return@map UIMessagePart.Text(text = textCompressed)
                    }

                    part
                }

                is UIMessagePart.Tool -> {
                    if (!part.isExecuted) return@map part

                    // 压缩工具输出中的每个 Text part
                    val (newOutput, saved) = compressToolOutput(part.toolName, part.output)
                    totalSaved += saved
                    if (newOutput != null) {
                        anyCompressed = true
                        part.copy(output = newOutput)
                    } else {
                        part
                    }
                }

                else -> part
            }
        }

        return if (anyCompressed) compressedParts to totalSaved
        else null to 0
    }

    /**
     * 压缩工具输出 parts 列表。
     */
    private fun compressToolOutput(
        toolName: String,
        output: List<UIMessagePart>,
    ): Pair<List<UIMessagePart>?, Long> {
        var totalSaved = 0L
        var anyCompressed = false

        val compressedOutput = output.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val text = part.text
                    if (text.length < MIN_CHARS_TO_COMPRESS) return@map part

                    // 工具输出专用压缩
                    val toolCompressed = ToolOutputCompressor.compress(toolName, text)
                    if (toolCompressed != null) {
                        totalSaved += (text.length - toolCompressed.length)
                        anyCompressed = true
                        UIMessagePart.Text(text = toolCompressed)
                    } else {
                        part
                    }
                }
                else -> part
            }
        }

        return if (anyCompressed) compressedOutput to totalSaved
        else null to 0
    }
}
