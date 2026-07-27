package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compression.ToolOutputCompressor

/**
 * 工具输出压缩器。
 * 只压缩 Tool part 的 output，不动用户/助手/system 文本。
 */
class ContextCompressionTransformer : InputMessageTransformer {

    companion object {
        private const val TAG = "ContextCompress"
        private const val MIN_CHARS = 200
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {

        var compressedCount = 0
        var savedChars = 0L

        val result = messages.map { message ->
            if (message.role == me.rerere.ai.core.MessageRole.SYSTEM) return@map message

            val toolParts = message.parts.filterIsInstance<UIMessagePart.Tool>()
            if (toolParts.isEmpty()) return@map message

            var modified = false
            val newParts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || !part.isExecuted) return@map part

                val (newOutput, saved) = compressParts(part.toolName, part.output)
                if (saved > 0) {
                    modified = true
                    compressedCount++
                    savedChars += saved
                    part.copy(output = newOutput)
                } else part
            }
            if (modified) message.copy(parts = newParts) else message
        }

        if (compressedCount > 0) {
            val msg = "compressed $compressedCount tool outputs, saved $savedChars chars"
            Log.wtf(TAG, msg)
            // ctx.processingStatus removed per user request
        }

        return result
    }

    private fun compressParts(
        toolName: String,
        output: List<UIMessagePart>,
    ): Pair<List<UIMessagePart>, Long> {
        var saved = 0L
        val parts = output.map { part ->
            if (part !is UIMessagePart.Text || part.text.length < MIN_CHARS) part
            else {
                val compressed = ToolOutputCompressor.compress(toolName, part.text)
                if (compressed != null && compressed.length < part.text.length) {
                    saved += (part.text.length - compressed.length)
                    UIMessagePart.Text(text = compressed)
                } else part
            }
        }
        return parts to saved
    }
}
