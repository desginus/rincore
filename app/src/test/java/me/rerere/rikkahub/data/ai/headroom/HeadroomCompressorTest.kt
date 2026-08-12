package me.rerere.rikkahub.data.ai.headroom

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HeadroomCompressor summarizeHistory 测试 (v3.6.42 重写)
 *
 * 验证目标:
 *  1. 确定性: 同输入必同输出 (缓存率铁律)
 *  2. 压缩率: 上限 65% (保留 ≥35%, 用户明确要求)
 *  3. 总结结构: 一段连贯文本 (含轮数/用户关注/助手结论/工具)
 *  4. 信息留存: 用户意图与助手结论保留
 *  5. 边界: 空/单轮/纯工具消息不崩
 */
class HeadroomCompressorTest {

    private fun user(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistant(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun tool(name: String, output: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = name,
                input = "{}",
                output = listOf(UIMessagePart.Text(output)),
            )
        ),
    )

    @Test
    fun `确定性_同输入两次总结输出一致`() {
        val history = buildHistory(3)
        val a = HeadroomCompressor.summarizeHistory(history)
        val b = HeadroomCompressor.summarizeHistory(history)
        assertEquals("确定性失败 — 总结不稳定会破坏缓存", a, b)
    }

    @Test
    fun `压缩率_上限65保留至少35`() {
        val history = buildHistory(4)
        val packed = HeadroomCompressor.summarizeHistory(history)
        val raw = history.sumOf { msg ->
            msg.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
        }
        val packedLen = packed.parts.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
        val keepRatio = packedLen.toFloat() / raw
        assertTrue("压缩后保留率 ${keepRatio} 应 ≥ 0.35", keepRatio >= 0.35f)
        assertTrue("压缩率应 ≤ 0.65", keepRatio <= 0.65f)
    }

    @Test
    fun `总结结构_含轮数用户关注助手结论`() {
        val history = buildHistory(2)
        val packed = HeadroomCompressor.summarizeHistory(history)
        val text = packed.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
        assertTrue("应含轮数标记", text.contains("共 2 轮"))
        assertTrue("应含用户关注", text.contains("用户关注"))
        assertTrue("应含助手结论", text.contains("助手结论"))
        assertTrue("应含涉及工具", text.contains("涉及工具"))
    }

    @Test
    fun `信息留存_用户意图与助手结论保留`() {
        val history = buildHistory(1)
        val packed = HeadroomCompressor.summarizeHistory(history)
        val text = packed.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
        assertTrue("用户意图应保留", text.contains("分析项目"))
        assertTrue("助手结论应保留", text.contains("优化"))
    }

    @Test
    fun `边界_空历史不崩`() {
        val packed = HeadroomCompressor.summarizeHistory(emptyList())
        val text = packed.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
        assertTrue("空历史应生成占位总结", text.isNotBlank())
    }

    @Test
    fun `边界_单条用户消息不崩`() {
        val packed = HeadroomCompressor.summarizeHistory(listOf(user("你好")))
        val text = packed.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
        assertTrue("单消息应生成总结", text.contains("你好"))
    }

    private fun buildHistory(rounds: Int): List<UIMessage> {
        val list = mutableListOf<UIMessage>()
        for (r in 1..rounds) {
            list.add(user("第 $r 轮: 请分析项目架构，重点说明模块职责边界和优化建议。"))
            list.add(assistant("第 $r 轮回复: 经过分析，架构分为数据层业务层表现层，发现耦合过高，建议分三步优化。"))
            list.add(tool("search", """[{"id":$r,"path":"/data/f$r.txt","size":1000}]"""))
        }
        return list
    }
}
