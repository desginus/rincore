package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.StreamTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.0.58 新重试机制 —— 决策层全分支回归锁。
 *
 * `RetryDecision.decide` 是纯函数（jitter 可关），所以这里断言的是**确定值**。
 * 任何参数被后人改动都会让这些用例失败，避免"悄悄把 DataTimeout 改回 1 次"这类回归。
 */
class RetryPolicyTest {

    // ── 配额 ──

    @Test
    fun `三条链的配额`() {
        assertEquals(8, RetryPhase.Init().total)
        assertEquals(12, RetryPhase.Stream().total)
        assertEquals(3, RetryPhase.DataTimeout().total)
    }

    @Test
    fun `配额耗尽即终止`() {
        val init = RetryDecision.decide(
            StreamFailure.InitPhase(headerDead = false), RetryPhase.Init(used = 8), true
        )
        assertTrue(init is RetryVerdict.Abort)

        val stream = RetryDecision.decide(
            StreamFailure.StreamInterrupted, RetryPhase.Stream(used = 12), true
        )
        assertTrue(stream is RetryVerdict.Abort)

        val data = RetryDecision.decide(
            StreamFailure.DataTimeout, RetryPhase.DataTimeout(used = 3), true
        )
        assertTrue(data is RetryVerdict.Abort)
    }

    // ── 退避 ──

    @Test
    fun `指数退避序列 不抖动`() {
        // base 500ms，封顶 8000ms
        val init = RetryPhase.Init()
        assertEquals(500L, init.backoffMs(1, jitter = false))
        assertEquals(1_000L, init.backoffMs(2, jitter = false))
        assertEquals(2_000L, init.backoffMs(3, jitter = false))
        assertEquals(4_000L, init.backoffMs(4, jitter = false))
        assertEquals(8_000L, init.backoffMs(5, jitter = false))
        assertEquals(8_000L, init.backoffMs(8, jitter = false))
    }

    @Test
    fun `流中断退避同指数`() {
        assertEquals(500L, RetryPhase.Stream(used = 0).delayMs(jitter = false))
        assertEquals(1_000L, RetryPhase.Stream(used = 1).delayMs(jitter = false))
        assertEquals(8_000L, RetryPhase.Stream(used = 9).delayMs(jitter = false))
    }

    @Test
    fun `思考静默退避 2 5 12 秒`() {
        assertEquals(2_000L, RetryPhase.DataTimeout(used = 0).delayMs())
        assertEquals(5_000L, RetryPhase.DataTimeout(used = 1).delayMs())
        assertEquals(12_000L, RetryPhase.DataTimeout(used = 2).delayMs())
    }

    @Test
    fun `header 判死不等待`() {
        val v = RetryDecision.decide(
            StreamFailure.InitPhase(headerDead = true), RetryPhase.Init(used = 0), true, jitter = false
        )
        assertEquals(0L, (v as RetryVerdict.Retry).delayMs)
    }

    // ── 快速失败 ──

    @Test
    fun `连续秒失败把退避抬到 30 秒`() {
        val v = RetryDecision.decide(
            StreamFailure.StreamInterrupted,
            RetryPhase.Stream(used = 0),
            autoRetryEnabled = true,
            fastFailStreak = RetryDecision.FAST_FAIL_THRESHOLD,
            jitter = false,
        )
        assertEquals(RetryDecision.FAST_FAIL_MIN_DELAY_MS, (v as RetryVerdict.Retry).delayMs)
    }

    @Test
    fun `未达阈值的秒失败不改变退避`() {
        val v = RetryDecision.decide(
            StreamFailure.StreamInterrupted,
            RetryPhase.Stream(used = 0),
            autoRetryEnabled = true,
            fastFailStreak = RetryDecision.FAST_FAIL_THRESHOLD - 1,
            jitter = false,
        )
        assertEquals(500L, (v as RetryVerdict.Retry).delayMs)
    }

    @Test
    fun `思考静默不吃快速失败抬升`() {
        val v = RetryDecision.decide(
            StreamFailure.DataTimeout,
            RetryPhase.DataTimeout(used = 0),
            autoRetryEnabled = true,
            fastFailStreak = 99,
        )
        assertEquals(2_000L, (v as RetryVerdict.Retry).delayMs)
    }

    // ── 自动重试开关 ──

    @Test
    fun `关闭自动重试后三条链都直接终止`() {
        assertTrue(
            RetryDecision.decide(
                StreamFailure.InitPhase(false), RetryPhase.Init(), autoRetryEnabled = false
            ) is RetryVerdict.Abort
        )
        assertTrue(
            RetryDecision.decide(
                StreamFailure.StreamInterrupted, RetryPhase.Stream(), autoRetryEnabled = false
            ) is RetryVerdict.Abort
        )
        assertTrue(
            RetryDecision.decide(
                StreamFailure.DataTimeout, RetryPhase.DataTimeout(), autoRetryEnabled = false
            ) is RetryVerdict.Abort
        )
    }

    // ── 分类 ──

    @Test
    fun `结构化异常按 phase 分流`() {
        fun ex(phase: String) = StreamTimeoutException(phase, idleMs = 1_000, limitMs = 2_000, message = "x")

        assertTrue(StreamFailureClassifier.classify(ex("stream"), true) is StreamFailure.StreamInterrupted)
        assertTrue(StreamFailureClassifier.classify(ex("first-event"), false) is StreamFailure.DataTimeout)
        assertTrue(StreamFailureClassifier.classify(ex("thinking"), false) is StreamFailure.DataTimeout)

        val header = StreamFailureClassifier.classify(ex("header"), false)
        assertTrue(header is StreamFailure.InitPhase && header.headerDead)
    }

    @Test
    fun `普通 IOException 保守二分`() {
        val io = java.io.IOException("boom")
        assertTrue(StreamFailureClassifier.classify(io, receivedAnyData = false) is StreamFailure.InitPhase)
        assertTrue(StreamFailureClassifier.classify(io, receivedAnyData = true) is StreamFailure.StreamInterrupted)
    }

    @Test
    fun `旧文案兜底仍可用`() {
        val dataTimeout = java.io.IOException("生成无有效数据超时 (480s): 上游思考或排队中无输出")
        assertTrue(StreamFailureClassifier.classify(dataTimeout, false) is StreamFailure.DataTimeout)

        val tailBreak = java.io.IOException("生成无有效数据超时 (150s): 平台断流或卡死")
        assertTrue(StreamFailureClassifier.classify(tailBreak, true) is StreamFailure.StreamInterrupted)
    }
}
