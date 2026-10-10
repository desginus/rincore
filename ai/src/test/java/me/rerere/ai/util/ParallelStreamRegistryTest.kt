package me.rerere.ai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelStreamRegistryTest {

    @Test
    fun enterExit_countsAndParallelVerdict() {
        val host = "test-enter-exit.example"
        assertFalse(ParallelStreamRegistry.isParallel(host))
        ParallelStreamRegistry.enter(host)
        // 单流 = 非并行 (含自身计数 1)
        assertEquals(1, ParallelStreamRegistry.activeCount(host))
        assertFalse(ParallelStreamRegistry.isParallel(host))
        // 第二条流进入 = 并行
        ParallelStreamRegistry.enter(host)
        assertEquals(2, ParallelStreamRegistry.activeCount(host))
        assertTrue(ParallelStreamRegistry.isParallel(host))
        ParallelStreamRegistry.exit(host)
        assertFalse(ParallelStreamRegistry.isParallel(host))
        ParallelStreamRegistry.exit(host)
        // 归零即移除, 后续查询不残留
        assertEquals(0, ParallelStreamRegistry.activeCount(host))
        assertFalse(ParallelStreamRegistry.isParallel(host))
    }

    @Test
    fun exitWithoutEnter_isHarmless() {
        val host = "test-exit-only.example"
        ParallelStreamRegistry.exit(host)
        assertEquals(0, ParallelStreamRegistry.activeCount(host))
    }

    @Test
    fun hostsAreIsolated() {
        val a = "test-host-a.example"
        val b = "test-host-b.example"
        ParallelStreamRegistry.enter(a)
        ParallelStreamRegistry.enter(a)
        ParallelStreamRegistry.enter(b)
        // 并行判定按 host 隔离: a 并行, b 单流
        assertTrue(ParallelStreamRegistry.isParallel(a))
        assertFalse(ParallelStreamRegistry.isParallel(b))
        ParallelStreamRegistry.exit(a)
        ParallelStreamRegistry.exit(a)
        ParallelStreamRegistry.exit(b)
    }
}
