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
    // v4.2.4: 无 id Tool delta 归属追踪 — CC 流带参工具调用的后续 delta 只带
    // arguments 增量 (id/name 仅首 delta 给出), toolCallId 为空串。旧 appendChunk
    // 的 blank-id 分支把它 merge 到最近一个 Tool; 若桥接器不追踪, 空 id 会被
    // firstSeen 误判为新工具 → 每段 arguments 增量都裂成独立 part, 输入永远
    // 只有首段 '{"' → 工具参数 JSON 解析 100% 失败 (带参工具全军覆没)。
    private var lastToolId: String? = null

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
                    // Start 延迟到首个有效载荷 (文本非空或带 metadata) — 空正文
                    // delta 不产生空 part, 与旧 fold 的 skip 空文本行为一致
                    if (!textStarted && (part.text.isNotEmpty() || part.metadata != null)) {
                        out += StreamChunk.TextStart(id = "text", metadata = part.metadata)
                        textStarted = true
                    }
                    if (textStarted && part.text.isNotEmpty()) {
                        out += StreamChunk.TextDelta(id = "text", text = part.text, metadata = part.metadata)
                    }
                }
                is UIMessagePart.Reasoning -> {
                    if (!reasoningStarted && (part.reasoning.isNotEmpty() || part.metadata != null)) {
                        out += StreamChunk.ReasoningStart(
                            id = "reasoning",
                            metadata = part.metadata,
                            reasoningType = ReasoningType.REASONING_TEXT,
                        )
                        reasoningStarted = true
                    }
                    if (reasoningStarted && part.reasoning.isNotEmpty()) {
                        out += StreamChunk.ReasoningDelta(
                            id = "reasoning",
                            text = part.reasoning,
                            metadata = part.metadata,
                        )
                    }
                }
                is UIMessagePart.Tool -> {
                    // 复刻旧 appendChunk 的 blank-id 归属语义: 无 id delta 归属
                    // 最近一个工具 (CC 增量流后续 delta 无 id/name)
                    val id = part.toolCallId.ifBlank { lastToolId }
                    if (id != null && id.isNotBlank()) {
                        val firstSeen = id !in toolsSeen
                        if (firstSeen) {
                            out += StreamChunk.ToolCallStart(
                                id = id,
                                toolName = part.toolName,
                                metadata = part.metadata,
                            )
                            toolsSeen += id
                        }
                        // Start 已携带首见 toolName — 首 delta 的 toolNameDelta 必须为空
                        // (否则 handler 拼接翻倍); 非首见增量照常走 Delta。
                        // input 增量始终走 Delta (Start 不携带 input, 否则首 delta 入参丢字)
                        val nameDelta = if (firstSeen) "" else part.toolName
                        if (nameDelta.isNotEmpty() || part.input.isNotEmpty()) {
                            out += StreamChunk.ToolCallDelta(
                                id = id,
                                toolNameDelta = nameDelta,
                                inputDelta = part.input,
                                metadata = part.metadata,
                            )
                        }
                        lastToolId = id
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
        // 流尾判定: 只有真实 finishReason 才触发 End/Finish 序列。
        // CC 通道的 delta choice 填默认 "unknown" (finishReason ?: "unknown"),
        // 旧 handleMessageChunk 不消费 finishReason 所以无感; 桥接器若把
        // "unknown" 当流尾, 每个 delta 都会 End+Finish → 每 token 裂一个 part、
        // 每块思考计时 0.0 秒 (finishedAt 每 delta 重置)。
        // grok (OpenCode Zen) 真流尾无 finish_reason, 走 "unknown" → 不收尾,
        // finishedAt 由 ChatService 兜底 finishReasoning() — 与旧路径一致。
        val finishReason = choice.finishReason
        val isRealFinish = finishReason != null && finishReason != "unknown"
        if (isRealFinish) {
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
            lastToolId = null
            out += StreamChunk.Finish(
                finishReason = finishReason,
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
