package me.rerere.ai.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v5.0.58：空壳帧判据 —— "网关发空包保活 → 看门狗永不触发 → 用户干等"的回归锁。
 *
 * 这些用例对应真实网关行为（OpenAI / Anthropic / 常见中转）。
 */
class StreamProgressTest {

    private fun subst(data: String) = StreamProgress.hasSubstantiveDelta(data)

    @Test
    fun `空壳帧不算实质进度`() {
        assertEquals(false, subst("""{"choices":[{"delta":{}}]}"""))
        assertEquals(false, subst("""{"choices":[]}"""))
        assertEquals(false, subst("""{"usage":{"prompt_tokens":1,"completion_tokens":2}}"""))
        assertEquals(false, subst("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals(false, subst("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertEquals(false, subst("""{"type":"message_start","message":{"id":"m","role":"assistant"}}"""))
        assertEquals(false, subst("""{"type":"ping"}"""))
        assertEquals(false, subst(""))
        assertEquals(false, subst("   "))
    }

    @Test
    fun `正文与思考增量算实质进度`() {
        assertEquals(true, subst("""{"choices":[{"delta":{"content":"你好"}}]}"""))
        assertEquals(true, subst("""{"choices":[{"delta":{"reasoning_content":"想一下"}}]}"""))
        assertEquals(true, subst("""{"choices":[{"delta":{"reasoning":"思考"}}]}"""))
        assertEquals(true, subst("""{"choices":[{"delta":{"thinking":"思考"}}]}"""))
    }

    @Test
    fun `空值不算实质进度`() {
        assertEquals(false, subst("""{"choices":[{"delta":{"content":""}}]}"""))
        assertEquals(false, subst("""{"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(false, subst("""{"choices":[{"delta":{"reasoning_content":""}}]}"""))
    }

    @Test
    fun `工具调用参数算实质进度`() {
        assertEquals(true, subst("""{"choices":[{"delta":{"tool_calls":[{"index":0}]}}]}"""))
        assertEquals(false, subst("""{"choices":[{"delta":{"tool_calls":[]}}]}"""))
        // Anthropic：类型标记 + 参数实体
        assertEquals(
            true,
            subst("""{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{"}}""")
        )
        assertEquals(
            true,
            subst("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}""")
        )
        assertEquals(
            false,
            subst("""{"type":"content_block_delta","delta":{"type":"text_delta","text":""}}""")
        )
    }

    @Test
    fun `同一帧里空值后跟实质值仍算进度`() {
        assertEquals(true, subst("""{"delta":{"content":""},"x":{"content":"real"}}"""))
    }
}
