package me.rerere.rikkahub.data.ai.protocol

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageProtocolTest {

    // ── ensureSystemFirst ──

    @Test
    fun `system first messages pass through unchanged`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("hello"),
        )
        val result = MessageProtocol.ensureSystemFirst(messages)
        assertEquals(messages, result)
    }

    @Test
    fun `empty messages get empty system created`() {
        val result = MessageProtocol.ensureSystemFirst(emptyList())
        assertEquals(1, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
    }

    @Test
    fun `non-system first message is structurally repaired`() {
        val messages = listOf(
            UIMessage.user("stray user text"),
            UIMessage.assistant("reply"),
        )
        val result = MessageProtocol.ensureSystemFirst(messages)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals(3, result.size)
        assertTrue(getText(result[0]).contains("stray user text"))
        // 原 user 消息被保留（文本已并入 system，非文本 parts 留在原消息）
        assertEquals(MessageRole.USER, result[1].role)
    }

    // ── sanitizeToolSequence ──

    @Test
    fun `paired tool sequence passes through unchanged`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("please use tool"),
            assistantWithToolCall("call-1", "search_web"),
            userWithToolResult("call-1"),
        )
        val result = MessageProtocol.sanitizeToolSequence(messages)
        assertEquals(4, result.size)
    }

    @Test
    fun `orphan tool call is removed`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("please use tool"),
            assistantWithToolCall("orphan-1", "search_web"),
        )
        val result = MessageProtocol.sanitizeToolSequence(messages)
        assertEquals(3, result.size) // assistant 消息无 parts → 整条移除
        assertTrue(result.none { it.parts.any { p -> p is UIMessagePart.ToolCall } })
    }

    @Test
    fun `orphan tool call with text keeps text part only`() {
        val messages = listOf(
            UIMessage.system("sys"),
            assistantWithToolCallAndText("orphan-2", "search_web", "I will search"),
        )
        val result = MessageProtocol.sanitizeToolSequence(messages)
        assertEquals(2, result.size)
        val kept = result[1].parts
        assertTrue(kept.none { it is UIMessagePart.ToolCall })
        assertTrue(kept.any { it is UIMessagePart.Text })
    }

    @Test
    fun `orphan tool result is removed`() {
        val messages = listOf(
            UIMessage.system("sys"),
            userWithToolResult("no-call"),
        )
        val result = MessageProtocol.sanitizeToolSequence(messages)
        assertEquals(2, result.size)
        assertTrue(result[1].parts.none { it is UIMessagePart.Tool })
    }

    @Test
    fun `cascade cleanup reaches stable state`() {
        // 孤儿 tool_call → 移除 → assistant 空 → 移除 → 后续 tool_result 变孤儿 → 移除
        val messages = listOf(
            UIMessage.system("sys"),
            assistantWithToolCall("a", "search_web"),
            userWithToolResult("a"),
            assistantWithToolCall("b", "search_web"), // b 无结果 — 孤儿
        )
        val result = MessageProtocol.sanitizeToolSequence(messages)
        assertTrue(result.none { msg ->
            msg.parts.any { it is UIMessagePart.ToolCall || it is UIMessagePart.Tool }
        })
    }

    // ── enforce ──

    @Test
    fun `enforce is idempotent on compliant messages`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("hello"),
        )
        val once = MessageProtocol.enforce(messages)
        val twice = MessageProtocol.enforce(once)
        assertEquals(once, twice)
    }

    // ── helpers ──

    private fun getText(msg: UIMessage): String =
        msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }

    private fun assistantWithToolCall(id: String, name: String): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.ToolCall(id, name, "{}")),
        )

    private fun assistantWithToolCallAndText(id: String, name: String, text: String): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(text),
                UIMessagePart.ToolCall(id, name, "{}"),
            ),
        )

    private fun userWithToolResult(id: String): UIMessage =
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Tool(id, "search_web", "{}")),
        )
}
