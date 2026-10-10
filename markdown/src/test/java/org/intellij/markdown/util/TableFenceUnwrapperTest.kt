package org.intellij.markdown.util

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4.8.120 渲染链增强 (codex 对齐): ```md/```markdown 围栏表格解包回归门禁。
 * 保守语义四条: 仅 md/markdown 围栏; 体内须有表头+分隔行; 其他围栏零触碰;
 * 未闭合围栏原样回收 (流式降级安全)。
 */
class TableFenceUnwrapperTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))

    private fun has(node: ASTNode, type: String): Boolean {
        if (node.type.toString() == type) return true
        return node.children.any { has(it, type) }
    }

    private fun parseHas(text: String, type: String): Boolean =
        has(parser.buildMarkdownTreeFromString(text), type)

    @Test
    fun markdownFenceWithTableIsUnwrappedAndParsesAsTable() {
        val src = "前文\n\n```markdown\n| 名称 | 公式 |\n|---|---|\n| 圆 | \$S=\\pi r^2\$ |\n```\n\n后文"
        val out = TableFenceUnwrapper.unwrap(src)
        assertFalse("fence markers must be removed", out.contains("```"))
        assertTrue("surrounding text preserved", out.contains("前文") && out.contains("后文"))
        assertTrue("must parse as TABLE", parseHas(out, "Markdown:TABLE"))
        assertTrue("cell formula must stay inline math", parseHas(out, "Markdown:INLINE_MATH"))
        assertFalse("must not parse as code fence", parseHas(out, "Markdown:CODE_FENCE"))
    }

    @Test
    fun mdShortFenceAndCaseInsensitiveInfoAreUnwrapped() {
        val src = "```MD\n| a | b |\n|:--|--:|\n| 1 | 2 |\n```"
        val out = TableFenceUnwrapper.unwrap(src)
        assertFalse(out.contains("```"))
        assertTrue(parseHas(out, "Markdown:TABLE"))
    }

    @Test
    fun markdownFenceWithoutTableIsKept() {
        val src = "```markdown\n# 只是标题\n正文\n```"
        val out = TableFenceUnwrapper.unwrap(src)
        assertTrue("non-table markdown fence must stay", out.contains("```markdown"))
        assertFalse(parseHas(out, "Markdown:TABLE"))
    }

    @Test
    fun otherLanguageFenceIsUntouched() {
        val src = "```rust\n| a | b |\n|---|---|\n```"
        val out = TableFenceUnwrapper.unwrap(src)
        assertTrue(out.contains("```rust"))
        assertFalse("must stay a code fence", parseHas(out, "Markdown:TABLE"))
        assertTrue(parseHas(out, "Markdown:CODE_FENCE"))
    }

    @Test
    fun unclosedMdFenceDegradesToCode() {
        val src = "```md\n| a | b |\n|---|---|\n| 1 | 2 |"
        val out = TableFenceUnwrapper.unwrap(src)
        assertTrue("unclosed fence must be re-emitted as-is", out.contains("```md"))
    }

    @Test
    fun tildeMdFenceIsUnwrapped() {
        val src = "~~~markdown\n| x | y |\n|---|---|\n| 1 | 2 |\n~~~"
        val out = TableFenceUnwrapper.unwrap(src)
        assertFalse(out.contains("~~~"))
        assertTrue(parseHas(out, "Markdown:TABLE"))
    }

    @Test
    fun noFenceFastPathReturnsSameInstance() {
        val src = "普通文本 **加粗** \$x\$"
        val out = TableFenceUnwrapper.unwrap(src)
        assertTrue(src === out)
    }
}
