package org.intellij.markdown.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.8.122 (用户实证根因) 回归门禁: URL/地址域扫描器 —
 * 地址里的 '_'/'~' 不得被 app 层词内下划线保护/波浪号替换污染。
 * app 侧 preProcess 以本件判定"地址区一律原样"。
 */
class UrlRegionScannerTest {

    private fun ranges(text: String) = UrlRegionScanner.findProtectedRegions(text)

    private fun assertCovered(text: String, marker: String) {
        val pos = text.indexOf(marker)
        assertTrue("marker not found: $marker", pos >= 0)
        assertTrue("not covered: $text", ranges(text).any { pos in it })
    }

    @Test
    fun imageDestinationIsProtected() {
        val t = "![Y5](file:///data/data/me.rincore.app/files/ws/interior/shot_y5_1.png)"
        assertCovered(t, "shot_y5_1")
        assertCovered(t, "_1.png")
    }

    @Test
    fun angleDestinationIsProtected() {
        val t = "![a](</ws/图 1/pre_x_y.png>)"
        assertCovered(t, "pre_x_y")
    }

    @Test
    fun balancedParensDestinationIsProtected() {
        val t = "![a](/ws/f(1)_z.png)"
        assertCovered(t, "_z.png")
    }

    @Test
    fun autolinkIsProtected() {
        val t = "见 <https://ex.com/c_d~e> 结束"
        assertCovered(t, "c_d~e")
    }

    @Test
    fun bareUrlIsProtected() {
        val t = "见 https://ex.com/a_b 结束"
        assertCovered(t, "a_b")
        val t2 = "路径 file:///sdcard/x_y/z.png 结束"
        assertCovered(t2, "x_y")
    }

    @Test
    fun referenceDefinitionIsProtected() {
        val t = "[r]: https://ex.com/e_f\n\n[r] 结束"
        assertCovered(t, "e_f")
    }

    @Test
    fun plainProseIsNotProtected() {
        val t = "正文 xF_y 与 M_O, 无地址"
        assertTrue("prose must not be protected: ${ranges(t)}", ranges(t).isEmpty())
    }

    @Test
    fun cjkBoundaryStopsBareUrl() {
        // 裸 URL 在 CJK 边界停止 — 后面正文里的词内下划线仍应可被保护逻辑处理
        val t = "链接 https://ex.com/a_b 然后 xF_y 结束"
        val covered = ranges(t)
        assertTrue(covered.any { t.indexOf("a_b") in it })
        val prosePos = t.indexOf("xF_y")
        assertTrue(covered.none { prosePos in it })
    }
}
