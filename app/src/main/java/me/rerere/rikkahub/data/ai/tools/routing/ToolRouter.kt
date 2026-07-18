package me.rerere.rikkahub.data.ai.tools.routing

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val TAG = "ToolRouter"

class ToolRouter(
    private val overrides: Map<String, String> = emptyMap(),
    private val customDescriptions: Map<String, String> = emptyMap(),
) {

    fun classifyTool(tool: Tool): String {
        overrides[tool.name]?.let { return it }
        if (tool.name == "use_domain") return "system"
        return ToolDomain.classify(tool)?.label ?: "uncategorized"
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        return tools.groupBy { classifyTool(it) }
    }

    fun getTriggerDescription(domain: String): String {
        customDescriptions[domain]?.let { return it }
        return ToolDomain.entries.find { it.label == domain }?.triggerDescription
            ?: "其他未分类操作"
    }

    fun buildLayer1(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        val domains = classified.keys.filter { it != "system" }.sorted()

        return buildString {
            appendLine("<capability_routing>")
            appendLine("你初始只有一个工具可用：use_domain(名称)。其他工具按需加载。")
            appendLine()
            appendLine("根据用户的任务匹配对应类别，然后调用 use_domain(\"名称\")：")
            appendLine()

            for (domain in domains) {
                val domainTools = classified[domain].orEmpty()
                if (domainTools.isEmpty()) continue
                val trigger = getTriggerDescription(domain)
                appendLine("[$domain] 当用户需要$trigger时，调用 use_domain(\"$domain\")")
            }

            appendLine()
            appendLine("一个任务涉及多个类别时可以多次调用 use_domain。")
            appendLine("不确定类别时，调用 use_domain(\"帮助\") 查看完整列表。")
            appendLine("</capability_routing>")
        }
    }

    fun createUseDomainTool(
        allTools: List<Tool>,
        loadedDomains: MutableSet<String>,
        skillListText: String? = null,
    ): Tool {
        val router = this
        return Tool(
            name = "use_domain",
            description = "按类别加载工具。调用此工具后才能使用对应类别的工具。" +
                "可用类别见 system prompt 中的 <capability_routing> 部分。" +
                "调用 use_domain(\"帮助\") 可列出所有类别。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "类别名称，如 搜索、文件、日历、AI 等")
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { input ->
                val rawName = input.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")

                when {
                    rawName == "帮助" || rawName.equals("help", ignoreCase = true) -> {
                        listOf(UIMessagePart.Text(router.buildHelpText(allTools)))
                    }
                    else -> {
                        val classified = router.classifyAll(allTools)
                        val domainName = classified.keys
                            .filter { it != "system" }
                            .find { it == rawName }

                        if (domainName == null) {
                            val available = classified.keys
                                .filter { it != "system" }
                                .sorted()
                            listOf(UIMessagePart.Text(
                                "未知类别：'$rawName'。可用类别：${available.joinToString("、")}。"
                            ))
                        } else {
                            val domainTools = classified[domainName].orEmpty()
                            loadedDomains.add(domainName)
                            val toolNames = domainTools.map { it.name }
                            Log.i(TAG, "Domain loaded: $domainName (${toolNames.size} tools)")

                            // 特殊：加载AI类时，如果包含use_skill，返回技能列表
                            val hasUseSkill = domainTools.any { it.name == "use_skill" }
                            val skillNote = if (hasUseSkill && skillListText != null) {
                                "\n\n$skillListText"
                            } else ""

                            listOf(UIMessagePart.Text(
                                "类别「$domainName」已加载。${toolNames.size} 个工具：${toolNames.joinToString("、")}。$skillNote"
                            ))
                        }
                    }
                }
            }
        )
    }

    private fun buildHelpText(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        return buildString {
            appendLine("可用类别：")
            for ((domain, domainTools) in classified.toSortedMap()) {
                if (domain == "system") continue
                val sample = domainTools.take(2).map { it.name }.joinToString("、")
                appendLine("  [$domain] ${domainTools.size}个工具 ($sample${if (domainTools.size > 2) "…" else ""})")
            }
            appendLine()
            appendLine("调用 use_domain(\"类别名\") 加载对应工具。")
        }
    }

    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        return allTools.filter { classifyTool(it) == domainName }
    }
}
