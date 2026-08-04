package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseAPIMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(messages: List<UIMessage>): JsonArray {
        return api.buildMessages(messages)
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, listOf(UIMessage.user("hello")), params, stream)
    }

    private fun createReasoningParams(reasoningLevel: ReasoningLevel = ReasoningLevel.OFF): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should produce sequential function_call and output pairs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        // Verify each function_call is followed by its output (in pairs)
        val callIds = listOf("call_1", "call_2", "call_3")
        for (callId in callIds) {
            var callIndex = -1
            var outputIndex = -1
            for (i in result.indices) {
                val item = result[i].jsonObject
                if (item["type"]?.jsonPrimitive?.content == "function_call" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    callIndex = i
                }
                if (item["type"]?.jsonPrimitive?.content == "function_call_output" &&
                    item["call_id"]?.jsonPrimitive?.content == callId) {
                    outputIndex = i
                }
            }
            assertTrue("Should find function_call for $callId", callIndex >= 0)
            assertTrue("Should find function_call_output for $callId", outputIndex >= 0)
            assertEquals("Output should immediately follow call for $callId",
                callIndex + 1, outputIndex)
        }
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    // ==================== Helper Functions ====================

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}

    // ==================== reasoning 回传格式 (官方协议) ====================

    private fun assistantWithReasoning(reasoningText: String): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning(reasoning = reasoningText))
        )

    @Test
    fun `deepseek response api should pass reasoning as plain content`() {
        // DeepSeek 官方 Responses API: summary/encrypted_content 不支持,
        // reasoning 明文 content (reasoning_text) 必须回传
        val messages = listOf(
            UIMessage.system("sys"),
            assistantWithReasoning("思考内容"),
        )
        val input = api.buildMessages(
            messages,
            resolveResponseProviderCapabilities("api.deepseek.com"),
        )[0].jsonObject
        assertEquals("reasoning", input["type"]?.jsonPrimitive?.content)
        assertFalse("DeepSeek 不支持 summary", input.containsKey("summary"))
        assertFalse("DeepSeek 不支持 encrypted_content", input.containsKey("encrypted_content"))
        val content = input["content"]?.jsonArray
        assertTrue("明文 content 必须存在", content != null && content.size() > 0)
        assertEquals("reasoning_text", content!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("思考内容", content[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `openai response api should pass reasoning as summary`() {
        val messages = listOf(
            UIMessage.system("sys"),
            assistantWithReasoning("思考内容"),
        )
        val input = api.buildMessages(
            messages,
            resolveResponseProviderCapabilities("api.openai.com"),
        )[0].jsonObject
        assertEquals("reasoning", input["type"]?.jsonPrimitive?.content)
        val summary = input["summary"]?.jsonArray
        assertTrue("summary 必须存在", summary != null && summary.size() > 0)
        assertEquals("summary_text", summary!![0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    // ==================== 非流式响应解析 (DeepSeek 明文 reasoning) ====================

    private fun invokeParseResponseOutput(json: String): MessageChunk? {
        val method = ResponseAPI::class.java.getDeclaredMethod(
            "parseResponseOutput",
            kotlinx.serialization.json.JsonObject::class.java
        )
        method.isAccessible = true
        val jsonObject = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        return method.invoke(api, jsonObject) as MessageChunk?
    }

    @Test
    fun `non-stream response should parse deepseek plain reasoning content`() {
        // DeepSeek 非流式: reasoning item 用明文 content (reasoning_text), 无 summary
        val chunk = invokeParseResponseOutput(
            """{"id":"rsp_1","model":"deepseek-v4-flash","output":[
               {"type":"reasoning","id":"rs_1",
                "content":[{"type":"reasoning_text","text":"明文思考"}]},
               {"type":"message","content":[{"type":"output_text","text":"回答"}]}
            ]}"""
        )
        val parts = chunk!!.choices[0].message!!.parts
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(1, reasoning.size)
        assertEquals("明文思考", reasoning[0].reasoning)
        assertTrue(parts.any { it is UIMessagePart.Text && (it as UIMessagePart.Text).text == "回答" })
    }

    @Test
    fun `non-stream response should keep openai summary reasoning`() {
        // OpenAI 非流式: reasoning item 用 summary (summary_text)
        val chunk = invokeParseResponseOutput(
            """{"id":"rsp_2","model":"gpt-x","output":[
               {"type":"reasoning","id":"rs_2",
                "summary":[{"type":"summary_text","text":"摘要思考"}]},
               {"type":"message","content":[{"type":"output_text","text":"回答"}]}
            ]}"""
        )
        val parts = chunk!!.choices[0].message!!.parts
        val reasoning = parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(1, reasoning.size)
        assertEquals("摘要思考", reasoning[0].reasoning)
    }

    // ==================== 工具轮 reasoning 相邻 assistant 消息 (DeepSeek) ====================

    private fun assistantWithReasoningAndToolCall(reasoningText: String, callId: String): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = reasoningText),
                UIMessagePart.ToolCall(callId, "get_time_info", "{}"),
            ),
        )

    @Test
    fun `deepseek tool-call round should include adjacent assistant message`() {
        // DeepSeek 官方: reasoning 明文 "merged into the adjacent assistant message" —
        // 工具轮 (reasoning+function_call 无文本) 必须补 assistant 消息, 否则报
        // 'reasoning_text must be passed back'
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("现在几点"),
            assistantWithReasoningAndToolCall("工具轮思考", "call-1"),
            userWithToolResult("call-1"),
        )
        val input = api.buildMessages(
            messages,
            resolveResponseProviderCapabilities("api.deepseek.com"),
        )
        // 序列: reasoning → assistant(空 content) → function_call → function_call_output
        val reasoningIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }
        assertTrue("reasoning item 必须存在", reasoningIdx >= 0)
        val next = input[reasoningIdx + 1].jsonObject
        assertEquals("assistant", next["role"]?.jsonPrimitive?.content)
        assertEquals("", next["content"]?.jsonPrimitive?.contentOrNull)
        val callIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "function_call" }
        assertTrue("function_call 必须存在", callIdx >= 0)
    }

    @Test
    fun `openai tool-call round should not add empty assistant message`() {
        val messages = listOf(
            UIMessage.system("sys"),
            UIMessage.user("现在几点"),
            assistantWithReasoningAndToolCall("工具轮思考", "call-1"),
            userWithToolResult("call-1"),
        )
        val input = api.buildMessages(
            messages,
            resolveResponseProviderCapabilities("api.openai.com"),
        )
        // OpenAI 标准: reasoning item 后直接 function_call, 不补空 assistant 消息
        val reasoningIdx = input.indexOfFirst { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }
        val next = input[reasoningIdx + 1].jsonObject
        assertEquals("function_call", next["type"]?.jsonPrimitive?.content)
    }
