package me.rerere.rikkahub.data.ai.tools.routing

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.CustomDomain

private const val TAG = "ToolRouter"

class ToolRouter(
    private val overrides: Map<String, String> = emptyMap(),
    private val customDescriptions: Map<String, String> = emptyMap(),
    private val customDomains: List<CustomDomain> = emptyList(),
    private val customKeywords: Map<String, List<String>> = emptyMap(),
) {

    fun classifyTool(tool: Tool): String {
        // 1. 手动覆盖(最高优先级)
        overrides[tool.name]?.let { return it }
        if (tool.name == "use_domain") return "system"

        val text = "${tool.name} ${tool.description}".lowercase()

        // 2. 自定义域关键词匹配
        for (cd in customDomains) {
            if (cd.keywords.any { text.contains(it) }) return cd.name
        }

        // 3. 自定义关键词覆盖内置域
        for ((domain, keywords) in customKeywords) {
            if (keywords.any { text.contains(it) }) return domain
        }

        // 4. 内置域关键词
        return ToolDomain.classify(tool)?.label ?: "uncategorized"
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        return tools.groupBy { classifyTool(it) }
    }

    fun getTriggerDescription(domain: String): String {
        customDescriptions[domain]?.let { return it }
        ToolDomain.entries.find { it.label == domain }?.triggerDescription?.let { return it }
        customDomains.find { it.name == domain }?.description?.let { return it }
        return "其他操作"
    }

    fun getKeywords(domain: String): List<String> {
        customKeywords[domain]?.let { return it }
        customDomains.find { it.name == domain }?.keywords?.let { return it }
        return ToolDomain.entries.find { it.label == domain }?.matchKeywords ?: emptyList()
    }

    fun buildLayer1(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        val domains = classified.keys.filter { it != "system" }.sorted()

        return buildString {
            appendLine("<capability_routing>")
            appendLine("你只有一个初始工具: use_domain(name)。按需加载其他工具。")
            appendLine()
            appendLine("你的环境：完整的 Linux 工作区(sandbox rootfs)，/workspace 下文件持久化，可执行命令、读写编译文件。")
            appendLine()
            appendLine("匹配用户任务选择对应类别调用 use_domain(\"名称\")：")
            appendLine()

            for (domain in domains) {
                val domainTools = classified[domain].orEmpty()
                if (domainTools.isEmpty()) continue
                val desc = getTriggerDescription(domain)
                appendLine("  [${domain}] $desc → 调用 use_domain(\"${domain}\")")
            }

            appendLine()
            appendLine("跨类别任务可多次调用 use_domain。不确定时调 use_domain(\"帮助\")。")
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
            description = "按类别加载工具。调用后才能使用对应类别的工具。" +
                "可用类别见 system prompt 中的 <capability_routing> 部分。" +
                "调用 use_domain(\"帮助\") 列出所有类别。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "类别名称")
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { input ->
                val rawName = input.jsonObject["name"]?.jsonPrimitive?.content ?: error("name required")
                when {
                    rawName == "帮助" || rawName.equals("help", ignoreCase = true) ->
                        listOf(UIMessagePart.Text(router.buildHelpText(allTools)))
                    else -> {
                        val classified = router.classifyAll(allTools)
                        val dn = classified.keys.filter { it != "system" }.find { it == rawName }
                        if (dn == null) {
                            listOf(UIMessagePart.Text("未知类别: '$rawName'。可用: ${classified.keys.filter{it!="system"}.sorted().joinToString("、")}"))
                        } else {
                            val dTools = classified[dn].orEmpty()
                            loadedDomains.add(dn)
                            val names = dTools.map { it.name }
                            val sn = if (dTools.any { it.name == "use_skill" } && skillListText != null) "\n\n$skillListText" else ""
                            listOf(UIMessagePart.Text("已加载「$dn」。${names.size}个工具: ${names.joinToString("、")}。$sn"))
                        }
                    }
                }
            }
        )
    }

    private fun buildHelpText(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        return buildString {
            appendLine("全部类别:")
            for ((d, dts) in classified.toSortedMap()) {
                if (d == "system") continue
                val s = dts.take(2).map { it.name }.joinToString("、")
                appendLine("  [$d] ${dts.size}个 ($s${if (dts.size>2)"…" else ""})")
            }
            appendLine(); appendLine("调 use_domain(\"类别名\") 加载。")
        }
    }

    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        return allTools.filter { classifyTool(it) == domainName }
    }

    /** 用于 UI 预览：根据名称和描述对单个工具进行分类 */
    fun classifyPreview(name: String, description: String): String {
        overrides[name]?.let { return it }
        if (name == "use_domain") return "system"
        val text = "${name} ${description}".lowercase()
        for (cd in customDomains) {
            if (cd.keywords.any { text.contains(it) }) return cd.name
        }
        for ((domain, keywords) in customKeywords) {
            if (keywords.any { text.contains(it) }) return domain
        }
        return ToolDomain.entries.find { dom ->
            dom.matchKeywords.any { text.contains(it) }
        }?.label ?: "uncategorized"
    }
}
