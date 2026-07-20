package me.rerere.rikkahub.data.ai.tools.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

object ToolClassifier {

    /** 动态生成提示词——始终与 ToolDomain 枚举同步，杜绝旧域名残留 */
    val DEFAULT_PROMPT: String = buildString {
        appendLine("你是一个工具分类助手。根据工具的功能，将工具分配到合适的场景分类中。")
        appendLine()
        appendLine("## 可用场景（树状层级，路径越深越精准）：")
        appendLine()
        for (td in ToolDomain.entries.filter { it.parent == null }) {
            appendLine("${td.label} — ${td.triggerDescription}")
            for (sub in ToolDomain.entries.filter { it.parent == td.label }) {
                appendLine("  ${sub.label} — ${sub.triggerDescription}")
            }
        }
        appendLine()
        appendLine("## 分类规则：")
        appendLine("- 按工具功能分类，不是按技术来源分类")
        appendLine("- 忽略工具名前缀（如 mcp__xxx），只看功能")
        appendLine("- 不确定时选上层场景")
        appendLine("- 路径格式：一级场景 或 一级/子场景")
        appendLine("- 只能使用上面列出的场景名称，不要创造新名称")
        appendLine()
        appendLine("## 任务：")
        appendLine("对以下工具列表进行分类，返回 JSON 格式。只返回 JSON，不要其他内容。")
        appendLine("{\"tool_name\":\"场景路径\", ...}")
        appendLine()
        appendLine("工具列表：")
        appendLine("{{TOOLS}}")
    }.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun classify(
        tools: List<Pair<String, String>>,
        model: Model,
        @Suppress("UNCHECKED_CAST")
        provider: Provider<ProviderSetting>,
        providerSetting: ProviderSetting,
        customPrompt: String = "",
    ): Result<Map<String, String>> {
        val prompt = (if (customPrompt.isNotBlank()) customPrompt else DEFAULT_PROMPT)
            .replace("{{TOOLS}}", tools.joinToString("\n") { (n, d) -> "- $n: ${d.take(200)}" })

        return try {
            val messages = listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt)))
            )
            val chunk = provider.generateText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.1f,
                ),
            )
            val fullText = chunk.choices.firstOrNull()?.message?.parts
                ?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text } ?: ""
            val jsonStr = extractJson(fullText)
            Result.success(parseResult(jsonStr))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end < 0 || start >= end) return "{}"
        return text.substring(start, end + 1)
    }

    private fun parseResult(jsonStr: String): Map<String, String> {
        return try {
            json.parseToJsonElement(jsonStr).jsonObject.mapValues { it.value.jsonPrimitive.content }
        } catch (_: Exception) { emptyMap() }
    }
}
