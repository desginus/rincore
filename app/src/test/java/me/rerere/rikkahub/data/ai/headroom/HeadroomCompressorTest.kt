package me.rerere.rikkahub.data.ai.headroom

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HeadroomCompressor 本地测试 (v3.6.23)
 *
 * 验证目标:
 *  1. 确定性: 同输入必同输出 (缓存率铁律)
 *  2. 幂等: 压缩后再压缩不变 (前缀稳定)
 *  3. 有效性: 真实工具输出压缩率 (JSON 数组大降)
 *  4. 信息留存: 错误项/首尾项保留 (采样不丢关键)
 *  5. 边界: 空/小/非 JSON/纯文本不崩
 */
class HeadroomCompressorTest {

    // ── 确定性 (缓存铁律) ──────────────────────────────────────

    @Test
    fun `确定性_同输入两次压缩输出一致`() {
        val content = buildLargeJsonArray(50)
        val a = HeadroomCompressor.crush(content)
        val b = HeadroomCompressor.crush(content)
        assertEquals("确定性失败 — 压缩结果不稳定会破坏缓存", a, b)
    }

    @Test
    fun `幂等_已压缩内容再压不变`() {
        val content = buildLargeJsonArray(60)
        val once = HeadroomCompressor.crush(content)
        val twice = HeadroomCompressor.crush(once)
        assertEquals("幂等失败 — 重复压缩会破坏前缀稳定", once, twice)
    }

    // ── 有效性 (内容有效压缩) ──────────────────────────────────

    @Test
    fun `压缩率_50项JSON数组显著压缩`() {
        val content = buildLargeJsonArray(50)
        val crushed = HeadroomCompressor.crush(content)
        val ratio = 1.0 - crushed.length.toDouble() / content.length
        println("JSON 数组 50 项: ${content.length} → ${crushed.length} 字符, 压缩率 ${"%.1f".format(ratio * 100)}%")
        assertTrue("压缩率不足 (${"%.1f".format(ratio * 100)}% < 40%)", ratio >= 0.40)
    }

    @Test
    fun `压缩率_重复行日志合并`() {
        val content = buildString {
            repeat(20) { append("2026-08-11 10:00:00 INFO processing item\n") }
            append("2026-08-11 10:00:01 ERROR timeout on request\n")
        }
        val crushed = HeadroomCompressor.crush(content)
        val ratio = 1.0 - crushed.length.toDouble() / content.length
        println("重复日志: ${content.length} → ${crushed.length} 字符, 压缩率 ${"%.1f".format(ratio * 100)}%")
        assertTrue("重复行未合并", crushed.contains("×20"))
        assertTrue("错误行丢失", crushed.contains("ERROR"))
        assertTrue("日志压缩无效 (${"%.1f".format(ratio * 100)}%)", ratio >= 0.50)
    }

    @Test
    fun `信息留存_错误项与首尾项保留`() {
        // 60 项, 第 30 项是错误, 采样后错误必须保留
        val items = (0 until 60).map { i ->
            if (i == 30) """{"id":$i,"status":"ERROR","msg":"connection refused"}"""
            else """{"id":$i,"status":"ok","msg":"done"}"""
        }
        val content = "[" + items.joinToString(",") + "]"
        val crushed = HeadroomCompressor.crush(content)
        assertTrue("错误项丢失 — 信息留存失败", crushed.contains("connection refused"))
        assertTrue("首项丢失", crushed.contains("\"id\":0"))
        assertTrue("尾项丢失", crushed.contains("\"id\":59"))
    }

    @Test
    fun `压缩率_常量字段提取`() {
        // 30 项全同 type=file, 只有 name 不同 — 常量提取后大降
        val items = (0 until 30).map { i ->
            """{"type":"file","name":"doc_$i.txt","size":1024}"""
        }
        val content = "[" + items.joinToString(",") + "]"
        val crushed = HeadroomCompressor.crush(content)
        val ratio = 1.0 - crushed.length.toDouble() / content.length
        println("常量字段: ${content.length} → ${crushed.length} 字符, 压缩率 ${"%.1f".format(ratio * 100)}%")
        assertTrue("常量字段未提取 (${"%.1f".format(ratio * 100)}%)", ratio >= 0.30)
    }

    // ── 边界 (不崩) ───────────────────────────────────────────

    @Test
    fun `边界_空字符串不变`() {
        assertEquals("", HeadroomCompressor.crush(""))
    }

    @Test
    fun `边界_小内容不压缩`() {
        val small = """[{"a":1},{"a":2}]"""
        assertEquals(small, HeadroomCompressor.crush(small))
    }

    @Test
    fun `边界_纯文本不变_或无损紧凑`() {
        val text = "正常对话内容，没有工具输出。"
        val crushed = HeadroomCompressor.crush(text)
        assertEquals(text, crushed)
    }

    @Test
    fun `边界_嵌套JSON不崩`() {
        val nested = buildString {
            append("[")
            repeat(10) { i ->
                if (i > 0) append(",")
                append("""{"id":$i,"data":{"nested":[1,2,3],"deep":{"x":true}}}""")
            }
            append("]")
        }
        val crushed = HeadroomCompressor.crush(nested)
        assertTrue("嵌套 JSON 解析崩溃", crushed.isNotEmpty())
    }

    @Test
    fun `消息级_工具输出压缩_文本消息不动`() {
        val toolMsg = UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ToolCall(
                    toolCallId = "call_1",
                    toolName = "search",
                    arguments = "{}",
                ),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "search",
                    input = "{}",
                    output = listOf(UIMessagePart.Text(buildLargeJsonArray(40))),
                ),
            ),
        )
        val textMsg = UIMessage(
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text("这是重要的用户消息，不能被压缩")),
        )
        val result = HeadroomCompressor.compress(listOf(textMsg, toolMsg))
        val resultText = result[0].parts.first() as UIMessagePart.Text
        assertEquals("用户文本消息不应被压缩", "这是重要的用户消息，不能被压缩", resultText.text)
        val toolPart = result[1].parts.filterIsInstance<UIMessagePart.Tool>().first()
        val outText = toolPart.output.first() as UIMessagePart.Text
        assertNotEquals("工具输出未压缩", buildLargeJsonArray(40), outText.text)
        assertTrue("工具输出未带压缩标记", outText.text.contains("[Headroom-压缩"))
    }

    // ── 辅助 ─────────────────────────────────────────────────

    private fun buildLargeJsonArray(count: Int): String {
        return buildString {
            append("[")
            repeat(count) { i ->
                if (i > 0) append(",")
                append(
                    """{"id":$i,"name":"item_$i","path":"/data/files/item_$i.txt","size":${1024 + i},"tags":["a","b","c"],"desc":"description number $i for testing"}"""
                )
            }
            append("]")
        }
    }
}
