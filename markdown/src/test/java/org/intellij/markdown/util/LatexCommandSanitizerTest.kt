package org.intellij.markdown.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.8.128 (用户实证: "中间那个公式渲染错误 \boxed{} 和 \tag{}"): \tag 家族
 * 整条击杀防护 — 编号/元命令清洗回归门禁。实证: jlatexmath 对
 * \tag/\label/\nonumber/\notag/\begin{equation} 零容忍 (一个即整条 ParseException
 * → 渲染回落原文); \boxed/\boldsymbol/\quad 原生支持 (零触碰)。
 */
class LatexCommandSanitizerTest {

    @Test
    fun tagBecomesVisibleQuadParen() {
        assertEquals(
            "\\alpha=\\sqrt3\\,\\omega^2\\quad(1)",
            LatexCommandSanitizer.sanitize("\\alpha=\\sqrt3\\,\\omega^2\\tag{1}"),
        )
    }

    @Test
    fun userReportedFormulaKeepsBoxedAndConvertsTag() {
        val src = "\\tan60^\\circ=\\frac{a_\\tau}{a_n}=\\frac{R\\alpha}{R\\omega^2}=" +
            "\\frac{\\alpha}{\\omega^2}\\;\\Longrightarrow\\;\\boxed{\\alpha=\\sqrt3\\,\\omega^2}\\tag{1}"
        val out = LatexCommandSanitizer.sanitize(src)
        assertFalse("tag 必须被清洗", out.contains("\\tag"))
        assertTrue("boxed 必须原样保留", out.contains("\\boxed{\\alpha=\\sqrt3\\,\\omega^2}"))
        assertTrue("编号需保留可见 (quad 括号)", out.endsWith("\\quad(1)"))
    }

    @Test
    fun tagStarVariantIsConverted() {
        assertEquals("x\\quad(A)", LatexCommandSanitizer.sanitize("x\\tag*{A}"))
    }

    @Test
    fun tagWithNestedBracesAndEscapesIsMatched() {
        assertEquals(
            "x\\quad(\\text{a\\{b\\}})",
            LatexCommandSanitizer.sanitize("x\\tag{\\text{a\\{b\\}}}"),
        )
    }

    @Test
    fun unclosedTagBraceIsLeftUntouched() {
        val src = "x\\tag{1"
        assertEquals(src, LatexCommandSanitizer.sanitize(src))
    }

    @Test
    fun labelIsRemoved() {
        assertEquals("x y", LatexCommandSanitizer.sanitize("x\\label{eq:1} y"))
    }

    @Test
    fun nonumberAndNotagAreRemoved() {
        assertEquals("x y", LatexCommandSanitizer.sanitize("x\\nonumber y"))
        assertEquals("x y", LatexCommandSanitizer.sanitize("x\\notag y"))
    }

    @Test
    fun wordPrefixesAreNotDamaged() {
        assertEquals("\\notagx", LatexCommandSanitizer.sanitize("\\notagx"))
        assertEquals("\\bmx", LatexCommandSanitizer.sanitize("\\bmx"))
        assertEquals("\\tags", LatexCommandSanitizer.sanitize("\\tags"))
    }

    @Test
    fun equationEnvironmentWrappersAreStripped() {
        assertEquals(" x ", LatexCommandSanitizer.sanitize("\\begin{equation} x \\end{equation}"))
        assertEquals(" x ", LatexCommandSanitizer.sanitize("\\begin{equation*} x \\end{equation*}"))
    }

    @Test
    fun bmAliasIsNormalized() {
        assertEquals("\\boldsymbol{v}", LatexCommandSanitizer.sanitize("\\bm{v}"))
        assertEquals("\\boldsymbol{v}", LatexCommandSanitizer.sanitize("\\boldsymbol{v}"))
    }

    @Test
    fun plainFormulaIsUntouched() {
        val src = "\\frac{a}{b}=\\sqrt3\\,\\omega^2"
        assertEquals(src, LatexCommandSanitizer.sanitize(src))
    }

    @Test
    fun sanitizeIsIdempotent() {
        val once = LatexCommandSanitizer.sanitize(
            "\\bm{v}\\tag{1}\\label{a}\\begin{equation}x\\end{equation}\\nonumber"
        )
        assertEquals(once, LatexCommandSanitizer.sanitize(once))
    }
}
