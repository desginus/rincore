package me.rerere.ai.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AggregateCloseEvidenceTest {

    @Before
    fun setUp() {
        AggregateCloseEvidence.resetForTest()
    }

    @Test
    fun unseenChannel_isNotUsageCapable() {
        assertFalse(AggregateCloseEvidence.isUsageCapable("api.commandcode.ai", "glm-5.3-flash"))
    }

    @Test
    fun observedUsageFrame_marksCapable_keyedByHostAndModel() {
        AggregateCloseEvidence.markUsageCapable("api.commandcode.ai", "glm-5.3-flash")
        assertTrue(AggregateCloseEvidence.isUsageCapable("api.commandcode.ai", "glm-5.3-flash"))
        // host 隔离
        assertFalse(AggregateCloseEvidence.isUsageCapable("opencode.ai", "glm-5.3-flash"))
        // model 隔离 (同 host 其它模型不受影响 — ox 系无信号通道保持零误伤)
        assertFalse(AggregateCloseEvidence.isUsageCapable("api.commandcode.ai", "ox-alpha-free"))
    }

    @Test
    fun blankInputs_ignored() {
        AggregateCloseEvidence.markUsageCapable("", "m")
        AggregateCloseEvidence.markUsageCapable("h", "")
        assertFalse(AggregateCloseEvidence.isUsageCapable("", "m"))
        assertFalse(AggregateCloseEvidence.isUsageCapable("h", ""))
    }

    @Test
    fun multipleModels_accumulateIndependently() {
        AggregateCloseEvidence.markUsageCapable("api.commandcode.ai", "deepseek-flash")
        AggregateCloseEvidence.markUsageCapable("api.commandcode.ai", "glm-5.3-flash")
        assertTrue(AggregateCloseEvidence.isUsageCapable("api.commandcode.ai", "deepseek-flash"))
        assertTrue(AggregateCloseEvidence.isUsageCapable("api.commandcode.ai", "glm-5.3-flash"))
    }
}
