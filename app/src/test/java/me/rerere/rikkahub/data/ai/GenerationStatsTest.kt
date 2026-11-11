package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v5.3.73：tok/s 分母与多轮 usage 的回归护栏。
 *
 * 旧实现的两个错误：
 *  1. usage 用 merge（最后一步覆盖），不是 sum（多轮累计）；
 *  2. UI 用 createdAt→finishedAt（含工具执行/审批）当分母。
 */
class GenerationStatsTest {

    private fun msg(
        id: kotlin.uuid.Uuid = kotlin.uuid.Uuid.random(),
        usage: TokenUsage? = null,
        generationTimeMs: Long? = null,
        contextTokens: Int? = null,
    ) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
        usage = usage,
        generationTimeMs = generationTimeMs,
        contextTokens = contextTokens,
    )

    @Test
    fun `same assistant accumulates usage and generation time across tool steps`() {
        val base = msg(
            usage = TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
            generationTimeMs = 1_000L,
            contextTokens = 150,
        )
        val step = TokenUsage(promptTokens = 200, completionTokens = 30, totalTokens = 230)
        val updated = applyStepGenerationStats(
            messages = listOf(base),
            baseAssistant = base,
            baseUsage = base.usage,
            baseGenerationMs = 1_000L,
            baseContextTokens = 150,
            stepUsage = step,
            stepGenerationMs = 500L,
        ).single()

        assertEquals(280, updated.usage?.completionTokens ?: -1)
        assertEquals(300, updated.usage?.promptTokens ?: -1)
        assertEquals(1_500L, updated.generationTimeMs)
        assertEquals(230, updated.contextTokens)
    }

    @Test
    fun `new assistant starts fresh instead of inheriting previous step`() {
        val old = msg(
            usage = TokenUsage(promptTokens = 999, completionTokens = 999, totalTokens = 1998),
            generationTimeMs = 9_999L,
        )
        val fresh = msg()
        val step = TokenUsage(promptTokens = 10, completionTokens = 5, totalTokens = 15)
        val updated = applyStepGenerationStats(
            messages = listOf(old, fresh),
            baseAssistant = old,
            baseUsage = old.usage,
            baseGenerationMs = 9_999L,
            baseContextTokens = null,
            stepUsage = step,
            stepGenerationMs = 700L,
        ).last()

        assertEquals(5, updated.usage?.completionTokens ?: -1)
        assertEquals(10, updated.usage?.promptTokens ?: -1)
        assertEquals(700L, updated.generationTimeMs)
        assertEquals(15, updated.contextTokens)
    }

    @Test
    fun `missing step usage keeps base usage but still adds generation time`() {
        val base = msg(
            usage = TokenUsage(promptTokens = 7, completionTokens = 3, totalTokens = 10),
            generationTimeMs = 100L,
        )
        val updated = applyStepGenerationStats(
            messages = listOf(base),
            baseAssistant = base,
            baseUsage = base.usage,
            baseGenerationMs = 100L,
            baseContextTokens = 10,
            stepUsage = null,
            stepGenerationMs = 50L,
        ).single()

        assertEquals(3, updated.usage?.completionTokens ?: -1)
        assertEquals(150L, updated.generationTimeMs)
    }

    @Test
    fun `zero generation time becomes null so UI can fall back`() {
        val base = msg()
        val updated = applyStepGenerationStats(
            messages = listOf(base),
            baseAssistant = null,
            baseUsage = null,
            baseGenerationMs = 0L,
            baseContextTokens = null,
            stepUsage = null,
            stepGenerationMs = 0L,
        ).single()
        assertNull(updated.generationTimeMs)
    }

    @Test
    fun `no assistant message is left untouched`() {
        val user = UIMessage(role = MessageRole.USER, parts = emptyList())
        val result = applyStepGenerationStats(
            messages = listOf(user),
            baseAssistant = null,
            baseUsage = null,
            baseGenerationMs = 0L,
            baseContextTokens = null,
            stepUsage = TokenUsage(completionTokens = 1),
            stepGenerationMs = 10L,
        )
        assertSame(user, result.single())
    }
}
