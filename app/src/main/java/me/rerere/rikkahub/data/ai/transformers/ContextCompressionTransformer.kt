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
        // 检查是否启用
        if (!ctx.assistant.enableMemory) return messages

        val totalMessages = messages.size
        if (totalMessages <= PROTECT_RECENT_MESSAGES) return messages

        var tokensSaved = 0L
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
            tokensSaved += saved
            if (newParts != null) {
                message.copy(parts = newParts)
            } else {
                message
            }
        }

        if (tokensSaved > 0) {
            Log.i(TAG, "Compression saved ~$tokensSaved chars (${tokensSaved * 100 / (tokensSaved + messages.sumOf { it.toText().length }.coerceAtLeast(1))}%)")
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
                        return@map UIMessagePart.Text(
                            text = jsonCompressed,
                            metadata = part.metadata
                        )
                    }

                    // 尝试文本压缩
                    val textCompressed = TextCompressor.compress(text)
                    if (textCompressed != null) {
                        totalSaved += (text.length - textCompressed.length)
                        anyCompressed = true
                        return@map UIMessagePart.Text(
                            text = textCompressed,
                            metadata = part.metadata
                        )
                    }

                    part
                }

                is UIMessagePart.Tool -> {
                    if (!part.isExecuted || part.output.isNullOrBlank()) return@map part
                    val output = part.output

                    // 工具输出专用压缩
                    val toolCompressed = ToolOutputCompressor.compress(part.name, output)
                    if (toolCompressed != null) {
                        totalSaved += (output.length - toolCompressed.length)
                        anyCompressed = true
                        return@map part.copy(output = toolCompressed)
                    }

                    part
                }

                else -> part
            }
        }

        return if (anyCompressed) compressedParts to totalSaved
        else null to 0
    }
}
