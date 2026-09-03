package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * v3.13.7: Command Code 图片兼容适配 (CC 专属, opt-in)
 *
 * 根因: OpenAI 规范 role=tool 消息的 content 仅支持 text。RinCore 的
 * 工具结果以 UIMessagePart.Tool (output 含 Image part) 挂在消息 parts
 * 里, 请求组装时 ChatCompletionsAPI 把它拆成 role=tool 消息并把图片
 * 塞进 content (非标准结构) — CC 严格兼容层对此静默挂起 (无 header
 * 无报错 → 25s 判死 4 次重试)。DeepSeek/OpenCode 网关宽容所以无感。
 * Cherry Studio (AI SDK) 把工具结果图片重定位为紧随的 user 消息图片
 * (规范合法), 因此同端点同模型直发图正常。
 *
 * v3.13.6 教训: 曾按 msg.role==TOOL 找目标 — 但 RinCore 消息列表里
 * 根本没有 TOOL role 消息 (工具结果是 Tool part), transformer 恒不
 * 命中, 修了等于没修。
 *
 * 修复: 遍历消息 parts, 把 Tool.output 中的 Image 抽出为紧随其后的
 * user 消息图片, Tool.output 只留 text (空则占位防 content 空数组)。
 * 仅当开启"Command Code 图片兼容"且 key 为 user_ 时生效。
 */
object CCImageCompatTransformer : InputMessageTransformer {
    private const val TAG = "CCImageCompat"

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.settings.ccImageCompat) return messages
        // 仅 Command Code 通道生效 (user_ key); 关闭或 OpenCode 通道保持原状
        if (!ctx.settings.opencodeApiKey.startsWith("user_", ignoreCase = true)) return messages

        // v3.13.7: tool result 图片重定位 — 图片在 UIMessagePart.Tool.output
        // 里 (挂在消息 parts, 不存在 role=TOOL 消息; v3.13.6 找错目标恒不
        // 命中)。请求组装时 Tool.output 会被拆成 role=tool 消息且图片直接
        // 塞入 content — OpenAI 规范 tool content 仅支持 text, CC 严格
        // 兼容层挂起。修复: 抽出 Tool.output 中的 Image → 紧随其后的 user
        // 消息图片 (对齐 AI SDK 标准行为), Tool.output 只留 text。
        var relocated = 0
        val out = buildList {
            messages.forEach { msg ->
                if (msg.parts.none { it is UIMessagePart.Tool && it.output.any { p -> p is UIMessagePart.Image } }) {
                    add(msg)
                    return@forEach
                }
                var pendingImages: List<UIMessagePart.Image> = emptyList()
                val newParts = msg.parts.map { part ->
                    if (part !is UIMessagePart.Tool) return@map part
                    val imgs = part.output.filterIsInstance<UIMessagePart.Image>()
                    if (imgs.isEmpty()) return@map part
                    relocated += imgs.size
                    val textParts = part.output.filterIsInstance<UIMessagePart.Text>()
                    pendingImages = pendingImages + imgs
                    // output 抽图后留文本 (空则占位, 防 role=tool content 空数组)
                    part.copy(output = textParts.ifEmpty {
                        listOf(UIMessagePart.Text("(图片结果已另行提供)"))
                    })
                }
                add(msg.copy(parts = newParts))
                if (pendingImages.isNotEmpty()) {
                    add(
                        UIMessage(
                            role = me.rerere.ai.core.MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Text("[工具返回的图片]"),
                                *pendingImages.toTypedArray(),
                            ),
                        )
                    )
                }
            }
        }
        if (relocated > 0) Log.i(TAG, "relocated tool-result images: $relocated")
        return out
    }
}
