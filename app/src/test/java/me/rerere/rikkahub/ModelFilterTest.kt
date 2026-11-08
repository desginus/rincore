package me.rerere.rikkahub

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.filterByKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★v5.3.61（对齐上游 3.1.2）：模型选择页的**多关键词搜索**。
 *
 * 旧实现是 `displayName.contains(整串, ignoreCase = true)` —— 输入「claude sonnet」
 * 会被当成一个必须存在的整串，永远搜不到；而且完全不搜 modelId（中转常有
 * displayName 被改成中文、真正的模型名只在 modelId 里的情况）。
 */
class ModelFilterTest {
    private val sonnet = Model(modelId = "relay-a/claude-sonnet", displayName = "relay-a/claude-sonnet")
    private val renamed = Model(modelId = "relay-b/claude-opus", displayName = "Opus")
    private val gpt = Model(modelId = "gpt-mini", displayName = "GPT Mini")
    private val models = listOf(sonnet, renamed, gpt)

    @Test
    fun `keywords match in any order`() {
        assertEquals(listOf(sonnet), models.filterByKeywords("claude sonnet"))
        assertEquals(listOf(sonnet), models.filterByKeywords("sonnet claude"))
    }

    @Test
    fun `all keywords must match`() {
        assertTrue(models.filterByKeywords("claude mini").isEmpty())
    }

    @Test
    fun `case and extra spaces are ignored`() {
        assertEquals(listOf(sonnet), models.filterByKeywords("  CLAUDE   sonnet  "))
    }

    @Test
    fun `renamed model is still found by model id`() {
        assertEquals(listOf(renamed), models.filterByKeywords("relay-b claude"))
    }

    @Test
    fun `keywords can match across model id and display name`() {
        assertEquals(listOf(renamed), models.filterByKeywords("relay-b opus"))
    }

    @Test
    fun `blank query keeps the list`() {
        assertEquals(models, models.filterByKeywords(""))
        assertEquals(models, models.filterByKeywords("   "))
    }
}
