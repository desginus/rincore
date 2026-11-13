package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.sum
import me.rerere.ai.ui.UIMessage

/**
 * ★v5.3.73：把「本步真实生成统计」并到当前 assistant 消息上。
 *
 * - usage：同一轮工具调用共用一条 assistant 消息，各步 usage 必须 **sum**；
 *          `merge` 是同一个响应内多份快照的去重，不能用于跨步骤。
 * - generationTimeMs：只累计每一步「首个 chunk → 流结束」的时长，
 *          不含 TTFB、工具执行、审批等待 —— 这才是 tok/s 的分母。
 * - contextTokens：只取最后一次请求的 prompt+completion，供上下文占用环使用。
 *
 * 纯函数，放这里是为了能被 JVM 单测直接锁死（GenerationHandler 内部无法单测）。
 */
internal fun applyStepGenerationStats(
    messages: List<UIMessage>,
    baseAssistant: UIMessage?,
    baseUsage: TokenUsage?,
    baseGenerationMs: Long,
    baseContextTokens: Int?,
    stepUsage: TokenUsage?,
    stepGenerationMs: Long,
): List<UIMessage> {
    val index = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (index < 0) return messages
    val current = messages[index]
    val sameAssistant = baseAssistant != null && baseAssistant.id == current.id
    val cumulativeUsage = when {
        sameAssistant && stepUsage != null -> baseUsage.sum(stepUsage)
        sameAssistant -> baseUsage
        else -> stepUsage ?: current.usage
    }
    val generationMs = (if (sameAssistant) baseGenerationMs else 0L) + stepGenerationMs
    val contextTokens = stepUsage?.let { it.promptTokens + it.completionTokens }
        ?: if (sameAssistant) baseContextTokens else current.contextTokens
    val updated = current.copy(
        usage = cumulativeUsage,
        lastUsage = stepUsage ?: if (sameAssistant) current.lastUsage else current.lastUsage,
        stepCount = if (stepUsage != null) {
            (if (sameAssistant) current.stepCount ?: 0 else 0) + 1
        } else {
            current.stepCount
        },
        generationTimeMs = generationMs.takeIf { it > 0 },
        contextTokens = contextTokens,
    )
    return messages.toMutableList().also { it[index] = updated }
}
