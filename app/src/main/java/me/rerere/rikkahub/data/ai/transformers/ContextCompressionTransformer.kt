package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.compression.ToolOutputCompressor

/**
 * 工具输出压缩器。
 * 只压缩工具执行后的 output, 不碰用户/助手/system 消息。
 */
class ContextCompressionTransformer : InputMessageTransformer {

    companion object {
        private const val TAG = "ContextCompress"
        private const val MIN_CHARS = 500
    }

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        var hit = false

        val result = messages.map { message ->
            if (message.role == me.rerere.ai.core.MessageRole.SYSTEM) return@map message

            val toolParts = message.parts.filterIsInstance<UIMessagePart.Tool>()
            if (toolParts.isEmpty()) return@map message

            var modified = false
            val newParts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || !part.isExecuted) return@map part

                val (newOutput, ok) = compressToolOutput(part.toolName, part.output)
                if (ok) { modified = true; hit = true; part.copy(output = newOutput) }
                else part
            }
            if (modified) message.copy(parts = newParts) else message
        }

        if (hit) Log.w(TAG, "*** COMPRESSED ***")
        return result
    }

    private fun compressToolOutput(
        toolName: String,
        output: List<UIMessagePart>,
    ): Pair<List<UIMessagePart>, Boolean> {
        var did = false
        val parts = output.map { part ->
            if (part !is UIMessagePart.Text || part.text.length < MIN_CHARS) part
            else {
                val c = ToolOutputCompressor.compress(toolName, part.text)
                if (c != null) { did = true; Log.d(TAG, "$toolName ${part.text.length}->${c.length}"); UIMessagePart.Text(text = c) }
                else part
            }
        }
        return parts to did
    }
}
