package me.rerere.rikkahub.data.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v5.3.35 ★「快速/极致」能力准入的回归护栏。
 *
 * 锁死的语义（用户实测事故的直接对应）：
 *  · **未声明 TOOL 能力的模型绝不能进候选** —— 否则它拿不到 tools 定义，
 *    却能看到历史里的工具调用，最终把工具调用写成正文（DeepSeek → 智谱 那次）；
 *  · 上下文窗口装不下当前历史的模型也不能进候选；
 *  · 合格集合里才比速度；粘性模型不慢太多就保持，避免每轮换一个脑子；
 *  · 失败过的模型要降权，成功后能回到候选。
 */
class ModelRoutingTest {

    @Before
    fun setUp() {
        ModelLatencyStats.clear()
    }

    private fun chatModel(
        name: String,
        abilities: List<ModelAbility> = listOf(ModelAbility.TOOL),
        contextLength: Int = 1_000_000,
        type: ModelType = ModelType.CHAT,
        output: List<Modality> = listOf(Modality.TEXT),
    ) = Model(
        modelId = name,
        displayName = name,
        type = type,
        abilities = abilities,
        contextLength = contextLength,
        outputModalities = output,
    )

    @Test
    fun `model without TOOL ability is rejected when tools are needed`() {
        val m = chatModel("glm", abilities = emptyList())
        val reason = ModelRouting.ineligibility(m, requireTools = true, estimatedTokens = 0)
        assertNotNull("未声明 TOOL 的模型必须被拒（这是工具调用变正文的根因）", reason)
    }

    @Test
    fun `model without TOOL ability is allowed when no tools needed`() {
        val m = chatModel("glm", abilities = emptyList())
        assertNull(ModelRouting.ineligibility(m, requireTools = false, estimatedTokens = 0))
    }

    @Test
    fun `model with TOOL ability passes`() {
        val m = chatModel("deepseek")
        assertNull(ModelRouting.ineligibility(m, requireTools = true, estimatedTokens = 5_000))
    }

    @Test
    fun `context window smaller than current history is rejected`() {
        val m = chatModel("small", contextLength = 32_000)
        assertNotNull(ModelRouting.ineligibility(m, requireTools = true, estimatedTokens = 64_000))
        // 刚好装得下 → 合格
        assertNull(ModelRouting.ineligibility(m, requireTools = true, estimatedTokens = 32_000))
    }

    @Test
    fun `non chat or non text models are rejected`() {
        val img = chatModel("img", type = ModelType.IMAGE)
        assertNotNull(ModelRouting.ineligibility(img, requireTools = false, estimatedTokens = 0))
        val noText = chatModel("notxt", output = emptyList())
        assertNotNull(ModelRouting.ineligibility(noText, requireTools = false, estimatedTokens = 0))
    }

    @Test
    fun `eligibleModels filters out incapable models`() {
        val ok = chatModel("deepseek")
        val noTool = chatModel("glm", abilities = emptyList())
        val tiny = chatModel("tiny", contextLength = 8_000)
        val result = ModelRouting.eligibleModels(
            listOf(ok, noTool, tiny),
            requireTools = true,
            estimatedTokens = 50_000,
        )
        assertEquals(listOf(ok), result)
    }

    @Test
    fun `pick returns the only eligible model`() {
        val m = chatModel("only")
        assertEquals(m, ModelRouting.pick(listOf(m), conversationId = "c1"))
    }

    @Test
    fun `pick keeps sticky model when it is not much slower`() {
        val a = chatModel("a")
        val b = chatModel("b")
        // a 有历史样本 400ms，b 300ms —— b 更快，但 a 是粘性
        repeat(3) { ModelLatencyStats.record(a.id, 400L) }
        repeat(3) { ModelLatencyStats.record(b.id, 300L) }
        ModelLatencyStats.markSticky("c1", a.id)
        assertEquals("粘性模型不慢于最优 1.5 倍 → 保持", a, ModelRouting.pick(listOf(a, b), "c1"))
    }

    @Test
    fun `pick switches away when sticky model is much slower`() {
        val a = chatModel("a")
        val b = chatModel("b")
        repeat(3) { ModelLatencyStats.record(a.id, 3_000L) }
        repeat(3) { ModelLatencyStats.record(b.id, 300L) }
        ModelLatencyStats.markSticky("c1", a.id)
        assertEquals("粘性模型慢 10 倍 → 切换", b, ModelRouting.pick(listOf(a, b), "c1"))
    }

    @Test
    fun `pick works without conversation id`() {
        val a = chatModel("a")
        val b = chatModel("b")
        repeat(3) { ModelLatencyStats.record(a.id, 900L) }
        repeat(3) { ModelLatencyStats.record(b.id, 200L) }
        assertEquals(b, ModelRouting.pick(listOf(a, b), conversationId = null))
    }

    @Test
    fun `failure penalty demotes a model and success clears it`() {
        val a = chatModel("a")
        val b = chatModel("b")
        repeat(3) { ModelLatencyStats.record(a.id, 200L) }
        repeat(3) { ModelLatencyStats.record(b.id, 500L) }
        // 无失败：a 更快
        assertEquals(a, ModelRouting.pick(listOf(a, b), null))
        // a 失败 1 次（+3000ms 罚分）→ b 胜出
        ModelLatencyStats.recordFailure(a.id)
        assertEquals(b, ModelRouting.pick(listOf(a, b), null))
        // a 成功后清零 → 回到候选
        ModelLatencyStats.clearFailure(a.id)
        assertEquals(a, ModelRouting.pick(listOf(a, b), null))
    }

    @Test
    fun `sticky id survives round trip`() {
        val m = chatModel("m")
        ModelLatencyStats.markSticky("conv-1", m.id)
        assertEquals(m.id, ModelLatencyStats.stickyOf("conv-1"))
        ModelLatencyStats.clearSticky("conv-1")
        assertNull(ModelLatencyStats.stickyOf("conv-1"))
    }
}
