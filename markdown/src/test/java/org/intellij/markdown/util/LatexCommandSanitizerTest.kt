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
    // v5.3.16 用例同源（v5.3.18 改用 BACKSLASH 常量拼接，语义见上）
    private val USER_PIECEWISE = PIECEWISE_CTRL

    @Test
    fun `piecewise cases formula control space is normalized`() {
        val out = clean(USER_PIECEWISE)
        assertFalse("控制空格（单反斜杠+空格）必须被归一化: $out", out.contains(BACKSLASH + " "))
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

    // ══════════════════════════════════════════════════════════════════════
    // v5.3.18 ★★★「5.0.12 能渲染、后来渲染不了」的真根因回归
    //
    // v5.3.1 的 stripLatexSpacing 用 `Regex.replace(regex, "\\\\")` —— 替换串里的 `\`
    // 被当转义符 → `\\[4pt]` 变成**单个 `\`** → 紧跟换行 → jlatexmath 报
    // `Unknown symbol '\n'` → 整条公式 ParseException → 0×0 空白。
    // 下面这组锁死：间距参数被剥掉，但**换行前的反斜杠必须仍是两个**。
    // ══════════════════════════════════════════════════════════════════════

    private fun assertKeepsDoubleBackslashBeforeNewline(out: String) {
        var idx = out.indexOf('\n')
        while (idx >= 0) {
            if (idx >= 1 && out[idx - 1] == '\\') {
                assertTrue(
                    "换行前必须是两个反斜杠（否则 jlatexmath 报 Unknown symbol），实际片段=" +
                        out.substring(maxOf(0, idx - 6), minOf(out.length, idx + 2)),
                    idx >= 2 && out[idx - 2] == '\\',
                )
            }
            idx = out.indexOf('\n', idx + 1)
        }
    }

    @Test
    fun `spacing param is stripped but keeps double backslash`() {
        val out = clean("a \\\\[4pt]\nb")
        assertFalse("间距参数必须消失: $out", out.contains("[4pt]"))
        assertTrue("必须保留换行: $out", out.contains("\n"))
        assertKeepsDoubleBackslashBeforeNewline(out)
        assertTrue("必须正好是两个反斜杠: $out", out.contains("\\\\\n"))
    }

    @Test
    fun `multiple spacing units all stripped correctly`() {
        for (unit in listOf("2pt", "4pt", "6pt", "1mm", "0.5em", "3ex", "10mu", "0.2cm", "0.1in")) {
            val out = clean("x \\\\[$unit]\ny")
            assertFalse("[$unit] 必须被剥离: $out", out.contains("[$unit]"))
            assertKeepsDoubleBackslashBeforeNewline(out)
        }
    }

    @Test
    fun `spacing param inline with content on same line is untouched`() {
        // 不是换行参数（前面不是两个反斜杠）→ 原样保留
        val src = "a [4pt] b"
        assertEquals(src, clean(src))
    }

    // ── 用户实测的四条分段函数（v5.3.18 形态断言）──

    // ★ v5.3.18：全部改用 \u005C 常量拼接 —— 反斜杠数量一目了然，不再依赖多层转义：
    //   单个 = \u005C（LaTeX 命令前缀 / 控制空格），两个 = \u005C\u005C（LaTeX 换行）。
    private val BACKSLASH = "\u005C"

    /** 用户实测的四条分段函数（控制空格版：单反斜杠 + 空格） */
    private val PIECEWISE_CTRL = "F(x,y)=\n" +
        BACKSLASH + "begin{cases}\n" +
        "0, & x<1 " + BACKSLASH + " " + BACKSLASH + "text{或}" + BACKSLASH + " y<1, " + BACKSLASH.repeat(2) + "[4pt]\n" +
        "0, & 1" + BACKSLASH + "le x<2," + BACKSLASH + " 1" + BACKSLASH + "le y<2, " + BACKSLASH.repeat(2) + "[4pt]\n" +
        BACKSLASH + "dfrac13, & 1" + BACKSLASH + "le x<2," + BACKSLASH + " y" + BACKSLASH + "ge 2, " + BACKSLASH.repeat(2) + "[4pt]\n" +
        BACKSLASH + "dfrac13, & x" + BACKSLASH + "ge 2," + BACKSLASH + " 1" + BACKSLASH + "le y<2, " + BACKSLASH.repeat(2) + "[4pt]\n" +
        "1, & x" + BACKSLASH + "ge 2," + BACKSLASH + " y" + BACKSLASH + "ge 2.\n" +
        BACKSLASH + "end{cases}"


    @Test
    fun `user four piecewise formulas are fully normalized`() {
        val variants = listOf(
            PIECEWISE_CTRL,
            PIECEWISE_CTRL.replace("\\\\dfrac13, & 1\\\\le x<2,\\\\ 1\\\\le y<2", "\\\\dfrac19, & 1\\\\le x<2,\\\\ 1\\\\le y<2"),
            PIECEWISE_CTRL.replace("\\\\ \\\\text{或}\\\\ ", "\\\\text{ 或 }"),
            PIECEWISE_CTRL.replace("\\\\ \\\\text{或}\\\\ ", "\\\\text{ 或 }")
                .replace("\\\\dfrac13, & 1\\\\le x<2,\\\\ 1\\\\le y<2", "\\\\dfrac19, & 1\\\\le x<2,\\\\ 1\\\\le y<2"),
        )
        for ((i, src) in variants.withIndex()) {
            val out = clean(src)
            assertFalse("#$i 不得残留间距参数: $out", out.contains("[4pt]"))
            assertFalse("#$i 不得残留控制空格（单反斜杠+空格）: $out", out.contains(BACKSLASH + " "))
            assertFalse("#$i dfrac 单 token 必须展开: $out", Regex("""\\dfrac\d""").containsMatchIn(out))
            assertTrue("#$i cases 环境必须保留", out.contains("\\\\begin{cases}"))
            assertTrue("#$i 中文必须保留", out.contains("或"))
            assertKeepsDoubleBackslashBeforeNewline(out)
        }
    }

    @Test
    fun `piecewise formulas survive repeated sanitize (idempotent)`() {
        val once = clean(PIECEWISE_CTRL)
        val twice = clean(once)
        assertEquals("sanitize 必须幂等（重复处理不改变结果）", once, twice)
    }
}
