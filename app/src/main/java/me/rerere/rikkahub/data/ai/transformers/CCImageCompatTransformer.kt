package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * v3.13.6: Command Code 图片兼容适配 (CC 专属, opt-in)
 *
 * 根因: OpenAI 规范 role=tool 消息的 content 仅支持 text; RinCore 在
 * tool 结果里直接塞 image_url (非标准结构), CC 严格兼容层对此静默
 * 挂起 (无 header 无报错 → 25s 判死 4 次重试)。DeepSeek/OpenCode
 * 网关宽容处理所以无感。Cherry Studio (AI SDK) 同场景把工具结果
 * 图片重定位为紧随的 user 消息图片 (规范合法), 因此同端点同模型
 * 直发图正常 — 客户端请求体结构差异是唯一变量。
 *
 * 修复: tool 消息中的 Image part 抽出为紧随其后的 user 消息图片,
 * 对齐 AI SDK 标准行为。仅当用户开启"Command Code 图片兼容"且
 * 当前 key 为 user_ 时生效; 关闭或 OpenCode 通道保持原状。
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

        // v3.13.6: tool result 图片重定位 — OpenAI 规范 role=tool 的
        // content 仅支持 text; RinCore 曾直接在 tool 消息里塞 image_url
        // (非标准), CC 严格兼容层挂起 (DeepSeek/OpenCode 宽容所以无感)。
        // Cherry Studio (AI SDK) 同场景把图片重定位为紧随的 user 消息。
        // 修复: 抽出 tool 消息中的 Image part → 追加为紧随的 user 消息。
        var relocated = 0
        val out = buildList {
            messages.forEach { msg ->
                if (msg.parts.none { it is UIMessagePart.Image }) {
                    add(msg)
                    return@forEach
                }
                if (msg.role != me.rerere.ai.core.MessageRole.TOOL) {
                    add(msg)
                    return@forEach
                }
                val imgs = msg.parts.filterIsInstance<UIMessagePart.Image>()
                if (imgs.isEmpty()) {
                    add(msg)
                    return@forEach
                }
                relocated += imgs.size
                add(msg.copy(parts = msg.parts.filter { it !is UIMessagePart.Image }))
                add(
                    UIMessage(
                        role = me.rerere.ai.core.MessageRole.USER,
                        parts = listOf(
                            UIMessagePart.Text("[工具返回的图片]"),
                            *imgs.toTypedArray(),
                        ),
                    )
                )
            }
        }
        if (relocated > 0) Log.i(TAG, "relocated tool-result images: $relocated")
        return out
    }
