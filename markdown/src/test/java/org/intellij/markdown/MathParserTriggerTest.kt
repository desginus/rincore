package org.intellij.markdown

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.8.119 渲染链升级: 数学解析触发矩阵回归门禁 (CI: :markdown:testDebugUnitTest)。
 *
 * 移植 rikkahub/markdown 上游最新数学模型后的行为锚点:
 *  · CJK 紧邻 `$...$` 必须触发 (旧模型要求两侧为空白/标点, 中文语境公式全灭
 *    —— "很多渲染没触发"的解析侧根因);
 *  · 货币对 `$5 和 $10` 必须拒绝 (闭合 $ 后接数字);
 *  · 转义 \$ 不得成对; 行内代码内的 $ 不得成对; 块级 $$ 正常。
 */
class MathParserTriggerTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))

    private fun parse(text: String): ASTNode = parser.buildMarkdownTreeFromString(text)

    private fun has(node: ASTNode, type: String): Boolean {
        if (node.type.toString() == type) return true
        return node.children.any { has(it, type) }
    }

    private fun assertHas(text: String, vararg types: String) =
        types.forEach { t -> assertTrue("expect <$t> in: $text", has(parse(text), t)) }

    private fun assertNotHas(text: String, vararg types: String) =
        types.forEach { t -> assertFalse("unexpected <$t> in: $text", has(parse(text), t)) }

    @Test
    fun cjkAdjacentInlineMathTriggers() {
        assertHas("中文\$a\$中文", "Markdown:INLINE_MATH")
        assertHas("其中\$x\$为解", "Markdown:INLINE_MATH")
        assertHas("解为\$x\$。", "Markdown:INLINE_MATH")
    }

    @Test
    fun spaceSeparatedInlineMathStillTriggers() {
        assertHas("公式为 \$x^2\$，求值。", "Markdown:INLINE_MATH")
        assertHas("(\$x\$) 与「\$y\$」", "Markdown:INLINE_MATH")
    }

    @Test
    fun currencyPairsRejected() {
        assertNotHas("价格是 \$5 和 \$10 元", "Markdown:INLINE_MATH")
        assertNotHas("价格 \$100 元", "Markdown:INLINE_MATH")
    }

    @Test
    fun blockMathTriggers() {
        assertHas("\$\$\nE=mc^2\n\$\$", "Markdown:BLOCK_MATH")
        assertHas("推导 \$\$E=mc^2\$\$ 成立", "Markdown:BLOCK_MATH")
    }

    @Test
    fun escapedDollarStaysLiteral() {
        assertNotHas("\\\$100 转义", "Markdown:INLINE_MATH", "Markdown:DOLLAR")
    }

    @Test
    fun tableCellMathTriggers() {
        assertHas("| 名称 | 公式 |\n|---|---|\n| 圆 | \$S=\\pi r^2\$ |", "Markdown:INLINE_MATH")
        assertHas("| 成本 | 说明 |\n|---|---|\n| 成本\$C\$高 | \$x\$后文 |", "Markdown:INLINE_MATH")
    }

    @Test
    fun nestedContextsTrigger() {
        assertHas("## 标题 \$x^2\$ 后缀", "Markdown:INLINE_MATH")
        assertHas("- 列表 \$x\$ 与 **粗**", "Markdown:INLINE_MATH", "Markdown:STRONG")
        assertHas("[链接 \$x\$](https://a.b)", "Markdown:INLINE_MATH")
        assertHas("[链接**粗**](https://a.b)", "Markdown:STRONG")
    }

    @Test
    fun inlineCodeContainment() {
        assertNotHas("代码 `价格 \$5` 结束", "Markdown:INLINE_MATH")
        assertNotHas("`a \$x\$ b`", "Markdown:INLINE_MATH")
    }

    @Test
    fun singleDollarWithDigitsStillMath() {
        // 与主流渲染器 (KaTeX auto-render) 对齐: 闭合 $ 前非空白且后非数字即可
        assertHas("\$5\$", "Markdown:INLINE_MATH")
    }
}
