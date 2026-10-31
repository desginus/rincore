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

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.15 ★★★ 用户实测 1~9 回归：双线伸展箭头族（\xRightarrow 家族）
    //
    // 缺陷：块级 $$ 模式下 \xRightarrow 解析失败 → jlatexmath 不抛异常而是产出
    // 0 尺寸 drawable → 渲染端 drawable != null 走正常分支 → Canvas 0×0 → 空白无提示。
    // 修复：在 sanitize() 内把双线族归一化到 \Rightarrow + \overset/\underset。
    // 这组用例锁死「双线族永不以原样进渲染器」。
    // ══════════════════════════════════════════════════════════════════════
    private fun clean(latex: String) = LatexCommandSanitizer.sanitize(latex)

    private fun assertNoUnsupported(latex: String) {
        val hit = LatexCommandSanitizer.unsupportedMacros(latex)
        assertTrue("不应命中不支持名单，实际=$hit", hit.isEmpty())
    }

    @Test
    fun `case1 inline xRightarrow is normalized`() {
        val out = clean("a \\xRightarrow{b} c")
        assertFalse("不得残留 \\xRightarrow: $out", out.contains("\\xRightarrow"))
        assertTrue("必须换成双线 \\Rightarrow: $out", out.contains("\\Rightarrow"))
        assertTrue("标签必须保留: $out", out.contains("\\overset{b}"))
        assertNoUnsupported(out)
    }

    @Test
    fun `case2 block xRightarrow is normalized`() {
        val out = clean("a \\xRightarrow{b} c")
        assertFalse(out.contains("\\xRightarrow"))
        assertTrue(out.contains("\\overset{b}{\\Rightarrow}"))
    }

    @Test
    fun `case3 xrightarrow is untouched`() {
        assertEquals("a \\xrightarrow{b} c", clean("a \\xrightarrow{b} c"))
    }

    @Test
    fun `case4 stackrel over Rightarrow is untouched`() {
        assertEquals("a \\stackrel{b}{\\Rightarrow} c", clean("a \\stackrel{b}{\\Rightarrow} c"))
    }

    @Test
    fun `case5 bare Rightarrow is untouched`() {
        assertEquals("a \\Rightarrow b", clean("a \\Rightarrow b"))
    }

    @Test
    fun `case6 underbrace is untouched`() {
        assertEquals("\\underbrace{x+y}_{z}", clean("\\underbrace{x+y}_{z}"))
    }

    @Test
    fun `case7 text with chinese is untouched`() {
        assertEquals("\\text{全部情况，逐格}", clean("\\text{全部情况，逐格}"))
    }

    @Test
    fun `case8 long formula with Rightarrow only is untouched`() {
        val src = "\\frac{a}{b}\\Rightarrow\\frac{c}{d}\\Rightarrow\\frac{e}{f}" +
            "\\Rightarrow\\frac{g}{h}\\Rightarrow\\frac{i}{j}"
        assertEquals(src, clean(src))
    }

    @Test
    fun `case9 double line arrow family never reaches the renderer`() {
        listOf(
            "a \\xRightarrow{b} c",
            "a \\xLeftarrow{b} c",
            "a \\xLeftrightarrow{b} c",
            "a \\implies b",
            "a \\impliedby b",
            "a \\iff b",
        ).forEach { input ->
            val out = clean(input)
            assertFalse("双线族必须归一化，漏了: $input -> $out", out.contains("\\x"))
            assertNoUnsupported(out)
        }
    }

    @Test
    fun `xRightarrow with optional below label maps to underset`() {
        val out = clean("a \\xRightarrow[below]{above} c")
        assertTrue(out.contains("\\underset{below}"))
        assertTrue(out.contains("\\overset{above}"))
        assertFalse(out.contains("\\xRightarrow"))
    }

    @Test
    fun `xRightarrow without arguments becomes bare double arrow`() {
        val out = clean("a \\xRightarrow c")
        assertFalse(out.contains("\\xRightarrow"))
        assertTrue(out.contains("\\Rightarrow"))
    }

    @Test
    fun `unsupported macro detection`() {
        assertTrue(LatexCommandSanitizer.unsupportedMacros("\\ce{H2O}").contains("\\ce"))
        assertTrue(LatexCommandSanitizer.unsupportedMacros("x \\cancel{y}").contains("\\cancel"))
        assertTrue(LatexCommandSanitizer.unsupportedMacros("a \\Rightarrow b").isEmpty())
    }

    @Test
    fun `macro prefix does not misfire`() {
        assertTrue("\\cell 不是 \\ce", LatexCommandSanitizer.unsupportedMacros("\\cell").isEmpty())
        val out = clean("\\xRightarrows")
        assertTrue("\\xRightarrows 不得被拆解", out.contains("\\xRightarrows"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.16 ★★★用户实测：分段函数 `\begin{cases}` 整条渲染失败（0×0 空白）
    //
    // 这两个公式与上一轮 \xRightarrow 的失败**不是同一个原因**（那族已归一化）。
    // 本组锁定本轮找到的两个 jlatexmath 盲区：
    //   ① 控制空格 `\ `（反斜杠+空格）→ 归一为 `\,`
    //   ② 单 token 分数参数 `\dfrac13` → 补成 `\dfrac{1}{3}`
    // ══════════════════════════════════════════════════════════════════════
    private val USER_PIECEWISE = "F(x,y)=\n" +
        "\\begin{cases}\n" +
        "0, & x<1 \\ \\text{或}\\ y<1, \\\\[4pt]\n" +
        "0, & 1\\le x<2,\\ 1\\le y<2, \\\\[4pt]\n" +
        "\\dfrac13, & 1\\le x<2,\\ y\\ge 2, \\\\[4pt]\n" +
        "\\dfrac13, & x\\ge 2,\\ 1\\le y<2, \\\\[4pt]\n" +
        "1, & x\\ge 2,\\ y\\ge 2.\n" +
        "\\end{cases}"

    @Test
    fun `piecewise cases formula control space is normalized`() {
        val out = clean(USER_PIECEWISE)
        assertFalse("控制空格 `\\ ` 必须被归一化: $out", out.contains("\\ "))
        assertTrue("控制空格应变成 thin space \\,", out.contains("\\,\\text{或}"))
    }

    @Test
    fun `piecewise cases formula frac shorthand is expanded`() {
        val out = clean(USER_PIECEWISE)
        assertFalse("\\dfrac13 必须补花括号: $out", out.contains("\\dfrac13"))
        assertTrue("应为 \\dfrac{1}{3}", out.contains("\\dfrac{1}{3}"))
    }

    @Test
    fun `piecewise cases formula keeps cases environment and alignment`() {
        val out = clean(USER_PIECEWISE)
        assertTrue("cases 环境必须保留", out.contains("\\begin{cases}"))
        assertTrue("\\end{cases} 必须保留", out.contains("\\end{cases}"))
        assertTrue("行内对齐符 & 必须保留", out.contains("&"))
        assertTrue("换行必须保留", out.contains("\\\\"))
        assertTrue("中文 text 必须保留", out.contains("\\text{或}"))
        assertTrue("\\le / \\ge 必须保留", out.contains("\\le") && out.contains("\\ge"))
    }

    @Test
    fun `frac shorthand with nested groups is untouched`() {
        // 已是规范写法 → 原样不动
        val src = "\\dfrac{x+1}{y-1}"
        assertEquals(src, clean(src))
        val nested = "\\frac{\\sqrt{2}}{3}"
        assertEquals(nested, clean(nested))
    }

    @Test
    fun `frac shorthand handles command tokens`() {
        val out = clean("\\dfrac\\alpha\\beta")
        assertTrue("命令 token 也要补花括号: $out", out.contains("\\dfrac{\\alpha}{\\beta}"))
    }

    @Test
    fun `double backslash newline is not treated as control space`() {
        val out = clean("a \\\\ b")
        assertEquals("\\\\ 必须保持（不能被当成控制空格）", "a \\\\ b", out)
    }
}
