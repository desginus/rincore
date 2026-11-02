package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v5.3.34 ★「快速/极致」选模型依据的回归护栏。
 *
 * 这些用例锁死的是**语义**（不是实现细节）：
 *  · 没有样本时不能瞎猜（返回 null，让调用方用池首）；
 *  · 有样本时选**最快**的那个；
 *  · 只比较有样本的候选 —— 不能让「一条偶然的快样本」把从未测过的模型永久排除，
 *    也不能让「没测过的模型」凭空胜出；
 *  · 样本窗口滚动（网络变了，旧样本不该永远主导）；
 *  · 异常值不污染统计。
 *
 * 历史背景：v5.1.4~v5.3.33 的 ModelRacer 用 `GET {baseUrl}/models` 探针选模型，
 * 探的是元数据端点而不是推理速度，同 baseUrl 多模型时更是打同一个 URL（纯竞态噪声）。
 * 本轮改为基于真实首字耗时统计，这组测试保证该语义不被后续改动悄悄破坏。
 */
class ModelLatencyStatsTest {

    @Before
    fun setUp() {
        ModelLatencyStats.clear()
    }

    @Test
    fun `no samples means no winner`() {
        val a = Uuid.random()
        val b = Uuid.random()
        assertNull(ModelLatencyStats.fastest(listOf(a, b)))
    }

    @Test
    fun `single candidate never wins`() {
        val a = Uuid.random()
        repeat(3) { ModelLatencyStats.record(a, 100L) }
        assertNull(ModelLatencyStats.fastest(listOf(a)))
    }

    @Test
    fun `faster model wins`() {
        val fast = Uuid.random()
        val slow = Uuid.random()
        repeat(3) { ModelLatencyStats.record(fast, 120L) }
        repeat(3) { ModelLatencyStats.record(slow, 900L) }
        assertEquals(fast, ModelLatencyStats.fastest(listOf(fast, slow)))
        // 顺序无关
        assertEquals(fast, ModelLatencyStats.fastest(listOf(slow, fast)))
    }

    @Test
    fun `only sampled candidates are compared`() {
        val never = Uuid.random()
        val sampled = Uuid.random()
        repeat(2) { ModelLatencyStats.record(sampled, 500L) }
        // never 没有任何样本 → 不参与比较，选有数据的那个
        assertEquals(sampled, ModelLatencyStats.fastest(listOf(never, sampled)))
    }

    @Test
    fun `sliding window keeps recent samples`() {
        val m = Uuid.random()
        val other = Uuid.random()
        // 先 5 次慢（窗口会被填满）
        repeat(5) { ModelLatencyStats.record(m, 1_000L) }
        // 再 5 次快 → 慢样本应全部被挤出窗口
        repeat(5) { ModelLatencyStats.record(m, 100L) }
        repeat(3) { ModelLatencyStats.record(other, 200L) }

        assertEquals(100L, ModelLatencyStats.average(m))
        assertEquals(m, ModelLatencyStats.fastest(listOf(m, other)))
    }

    @Test
    fun `outlier samples are ignored`() {
        val m = Uuid.random()
        ModelLatencyStats.record(m, 0L)        // 非法
        ModelLatencyStats.record(m, -5L)       // 非法
        ModelLatencyStats.record(m, 400_000L)  // 超过 5 分钟上限，非法
        assertNull(ModelLatencyStats.average(m))
    }

    @Test
    fun `average is computed over retained samples`() {
        val m = Uuid.random()
        ModelLatencyStats.record(m, 200L)
        ModelLatencyStats.record(m, 400L)
        assertEquals(300L, ModelLatencyStats.average(m))
    }

    @Test
    fun `clear resets everything`() {
        val a = Uuid.random()
        val b = Uuid.random()
        repeat(3) {
            ModelLatencyStats.record(a, 100L)
            ModelLatencyStats.record(b, 300L)
        }
        assertEquals(a, ModelLatencyStats.fastest(listOf(a, b)))
        ModelLatencyStats.clear()
        assertNull(ModelLatencyStats.fastest(listOf(a, b)))
        assertNull(ModelLatencyStats.average(a))
    }
}
