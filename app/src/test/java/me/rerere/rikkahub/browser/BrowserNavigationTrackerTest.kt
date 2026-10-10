/* 【域 H·语音搜索】 | 地图: docs/APP_MAP.md §H */
package me.rerere.rikkahub.browser


/* ───【自研】BrowserNavigationTrackerTest — v4.8.116 导航状态机单测
 * B139 结算判据的不变量: 开始→完成→静默 三段齐备才允许返回。
 * ───────────────────────────────────────────────────────────────*/
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTrackerTest {

    @Test
    fun settle_requiresStartFinishAndIdle() {
        val t = BrowserNavigationTracker()
        t.onRequest() // 模拟一次资源请求 (T0)
        val t0 = t.lastRequestAtMs
        // 没有任何导航开始: generation == afterGen → 不结算
        assertFalse(t.isSettled(afterGen = 0, idleMs = 500, nowMs = t0 + 10_000))
        // 导航开始 (gen 0→1): 未 finish → 不结算且 in-flight
        t.onPageStarted()
        assertEquals(1L, t.generation)
        assertFalse(t.isSettled(0, 500, t0 + 10_000))
        assertTrue(t.hasInFlightNav())
        // finish 但网络未静默 → 不结算
        t.onPageFinished()
        assertFalse(t.isSettled(0, 500, t0 + 100))
        // finish + 静默窗口已过 → 结算
        assertTrue(t.isSettled(0, 500, t0 + 600))
        // afterGen 已是当前代 (没有"新"导航) → 永不结算
        assertFalse(t.isSettled(1, 0, t0 + 60_000))
    }

    @Test
    fun finishWithoutStartDoesNotMarkSettled() {
        val t = BrowserNavigationTracker()
        t.onPageFinished() // 异常防御: 无 start 的 finish 不应产生已完成代
        assertEquals(0L, t.finishedGeneration)
        assertFalse(t.isSettled(0, 0, System.currentTimeMillis()))
    }

    @Test
    fun mainFrameError_recorded_and_clearedPerHop() {
        val t = BrowserNavigationTracker()
        t.onPageStarted()
        t.onMainFrameError(-118, "net::ERR_CONNECTION_TIMED_OUT")
        assertEquals(-118, t.mainFrameErrorCode)
        assertEquals("net::ERR_CONNECTION_TIMED_OUT", t.mainFrameErrorDesc)
        // 重定向/下一跳 onPageStarted 清错误 (子帧错误也不该残留)
        t.onPageStarted()
        assertNull(t.mainFrameErrorCode)
        assertNull(t.mainFrameErrorDesc)
    }

    @Test
    fun httpError_stickyUntilNextHop() {
        val t = BrowserNavigationTracker()
        t.onPageStarted()
        t.onMainFrameHttpError(503)
        assertEquals(503, t.mainFrameHttpCode)
        t.onMainFrameHttpError(500) // 同一代只记首个
        assertEquals(503, t.mainFrameHttpCode)
        t.onPageStarted()
        assertNull(t.mainFrameHttpCode)
    }

    @Test
    fun redirectChain_generationMonotonic() {
        val t = BrowserNavigationTracker()
        t.onPageStarted() // gen1
        t.onPageFinished()
        t.onPageStarted() // gen2 (重定向)
        t.onPageFinished()
        assertEquals(2L, t.generation)
        assertEquals(2L, t.finishedGeneration)
        assertTrue(t.isSettled(1, 0, System.currentTimeMillis())) // 相对 gen1 已结算
        assertFalse(t.hasInFlightNav())
    }
}
