package me.rerere.ai.ui

import me.rerere.ai.core.TokenUsage

/* ───【自研】MessageChunkStreamAdapter.kt — 旧 MessageChunk → StreamChunk 桥接
 * v4.2.0 架构同构过渡层: Provider 接口已切换到原版 2.5.x 的
 * streamText(): Flow<StreamChunk> 形态, 但各 Provider 的内联解析仍产出
 * 旧 MessageChunk (v4.2.1 起逐 Provider 切换 StreamDecoder)。
 * 本适配器把 MessageChunk 重放为等价的 StreamChunk 事件序列:
 *   - 首见文本/思考/图片/工具 part 才发 Start (有状态, 每次冷流 collect 重建)
 *   - delta 语义与旧 handleMessageChunk 的 fold 合并一致 (增量拼接)
 *   - finishReason 到达时统一发 End 系列 + Finish + Usage
 * 行为等价性由 CI + 原版 stream-traces 回放测试矩阵保障。
 * ───────────────────────────────────────────────────────────────*/

class MessageChunkStreamAdapter {
    private var textStarted = false
    private var reasoningStarted = false
    private var imageStarted = false
    private val toolsSeen = LinkedHashSet<String>()

    fun adapt(chunk: MessageChunk): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        // usage-only 尾包 (choices 空) 也要桥接 — 旧路径 collect 层能拿到
        // chunk.usage, 桥接器丢弃会丢最终用量 (缓存诊断/统计)
        val out_usage = chunk.usage?.let { mutableListOf<StreamChunk>(StreamChunk.Usage(it)) } ?: mutableListOf()
        val choice = chunk.choices.firstOrNull()
        if (choice == null) return out_usage
        val delta = choice.delta ?: choice.message ?: return out_usage
        delta.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    if (!textStarted) {
                        out += StreamChunk.TextStart(id = "text", metadata = part.metadata)
                        textStarted = true
                    }
                    if (part.text.isNotEmpty()) {
                        out += StreamChunk.TextDelta(id = "text", text = part.text, metadata = part.metadata)
                    }
                }
                is UIMessagePart.Reasoning -> {
                    if (!reasoningStarted) {
                        out += StreamChunk.ReasoningStart(
                            id = "reasoning",
                            metadata = part.metadata,
                            reasoningType = ReasoningType.REASONING_TEXT,
                        )
                        reasoningStarted = true
                    }
                    if (part.reasoning.isNotEmpty()) {
                        out += StreamChunk.ReasoningDelta(
                            id = "reasoning",
                            text = part.reasoning,
                            metadata = part.metadata,
                        )
                    }
                }
                is UIMessagePart.Tool -> {
                    val firstSeen = part.toolCallId !in toolsSeen
                    if (firstSeen) {
                        out += StreamChunk.ToolCallStart(
                            id = part.toolCallId,
                            toolName = part.toolName,
                            metadata = part.metadata,
                        )
                        toolsSeen += part.toolCallId
                    }
                    // Start 已携带首见 toolName — 首 delta 的 Delta.toolNameDelta 必须为空
                    // (否则 handler 拼接两次翻倍); 非首见的 toolName 增量照常走 Delta。
                    // input 增量始终走 Delta (Start 不携带 input, 否则首 delta 入参丢字)
                    val nameDelta = if (firstSeen) "" else part.toolName
                    if (nameDelta.isNotEmpty() || part.input.isNotEmpty()) {
                        out += StreamChunk.ToolCallDelta(
                            id = part.toolCallId,
                            toolNameDelta = nameDelta,
                            inputDelta = part.input,
                            metadata = part.metadata,
                        )
                    }
                }
                is UIMessagePart.Image -> {
                    // 固定 id: url 是 base64 增量, 前缀每次变化不能当 id (会裂成多张图)
                    if (!imageStarted) {
                        out += StreamChunk.ImageStart(id = "image", metadata = part.metadata)
                        imageStarted = true
                    }
                    out += StreamChunk.ImageDelta(id = "image", data = part.url, metadata = part.metadata)
                }
                else -> {}
            }
        }
        if (delta.annotations.isNotEmpty()) {
            out += StreamChunk.Annotations(delta.annotations)
        }
        if (choice.finishReason != null) {
            if (textStarted) {
                out += StreamChunk.TextEnd(id = "text")
                textStarted = false
            }
            if (reasoningStarted) {
                out += StreamChunk.ReasoningEnd(id = "reasoning")
                reasoningStarted = false
            }
            if (imageStarted) {
                out += StreamChunk.ImageEnd(id = "image")
                imageStarted = false
            }
            toolsSeen.forEach { out += StreamChunk.ToolCallEnd(id = it) }
            toolsSeen.clear()
            out += StreamChunk.Finish(
                finishReason = choice.finishReason,
                responseId = chunk.id,
                model = chunk.model,
            )
        }
        return out + out_usage
    }
}

/** 非流式 (generateText) 快捷桥接: MessageChunk → TextGenerationResult */
fun MessageChunk.toTextGenerationResult(): me.rerere.ai.provider.TextGenerationResult {
    val choice = choices.firstOrNull()
    val message = choice?.message ?: choice?.delta ?: UIMessage.assistant("")
    return me.rerere.ai.provider.TextGenerationResult(
        id = id,
        model = model,
        message = message,
        finishReason = choice?.finishReason,
        usage = usage,
    )
}
