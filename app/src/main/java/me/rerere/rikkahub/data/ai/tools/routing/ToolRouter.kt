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

/**
 * 工具路由引擎 — 统一管理内置工具、MCP 工具、Skill 工具的分层注入。
 *
 * 核心机制：
 * 1. [buildLayer1] 生成路由表（~800 tokens），替代全量工具描述（~17000 tokens）
 * 2. [createUseDomainTool] 创建 use_domain 工具，模型按需加载域
 * 3. 每步循环中根据 loadedDomains 动态构建 tools 列表
 *
 * 不需要修改任何现有 Tool 定义。新增工具自动被分类。
 */
object ToolRouter {

    /**
     * 对工具进行域分类。
     * - MCP 工具按服务器名分组（mcp:serverName）
     * - Skill 工具统一归入 skills 域
     * - 其他工具按 [ToolDomain] 语义匹配
     * - 未匹配的归入 uncategorized
     */
    fun classifyTool(tool: Tool): String {
        val name = tool.name
        return when {
            name.startsWith("mcp__") -> {
                val parts = name.split("__")
                if (parts.size >= 3) "mcp:${parts[1]}" else "mcp"
            }
            name == "use_skill" -> "skills"
            name == "use_domain" -> "system"  // 路由工具本身不参与域分类
            else -> ToolDomain.classify(tool)?.label ?: "uncategorized"
        }
    }

    /**
     * 获取所有工具的分类结果。
     */
    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        return tools.groupBy { classifyTool(it) }
    }

    /**
     * 生成 Layer1 路由表。
     * 约 800-1000 tokens，覆盖全部工具域。
     */
    fun buildLayer1(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        val builtinDomains = classified.keys
            .filter { !it.startsWith("mcp:") && it != "skills" && it != "system" }
            .sorted()
        val mcpDomains = classified.keys.filter { it.startsWith("mcp:") }.sorted()
        val hasSkills = "skills" in classified

        return buildString {
            appendLine("<capability_routing>")
            appendLine("You have ONE tool available initially: use_domain(name). All other tools are loaded on-demand.")
            appendLine()
            appendLine("Choose the domain that matches the USER'S TASK, then call use_domain(\"name\"):")
            appendLine()

            // 内置域（包括 uncategorized）
            for (domain in builtinDomains) {
                val domainTools = classified[domain].orEmpty()
                if (domainTools.isEmpty()) continue
                val trigger = ToolDomain.entries.find { it.label == domain }?.triggerDescription
                    ?: "miscellaneous operations not covered by other domains"
                appendLine("[$domain] Call use_domain(\"$domain\") when the user wants to: $trigger")
            }

            // MCP 域
            if (mcpDomains.isNotEmpty()) {
                appendLine()
                appendLine("[mcp] External MCP services. Call use_domain(\"mcp:serviceName\") for their tools.")
                for (domain in mcpDomains) {
                    val serverName = domain.removePrefix("mcp:")
                    val toolCount = classified[domain]?.size ?: 0
                    appendLine("       $serverName ($toolCount tools)")
                }
            }

            // Skills 域
            if (hasSkills) {
                appendLine()
                appendLine("[skills] Call use_domain(\"skills\") to see available specialized capabilities (document generation, web search, charts, etc).")
            }

            appendLine()
            appendLine("You may call use_domain multiple times if the task spans multiple domains.")
            appendLine("If unsure which domain to use, call use_domain(\"help\") for a full list.")
            appendLine("</capability_routing>")
        }
    }

    /**
     * 创建 use_domain 工具。
     *
     * @param allTools 全部工具列表（用于域解析）
     * @param loadedDomains 已加载域的集合（可变，execute 时修改）
     * @param skillListText Skill 列表文本（可选，从 use_skill.systemPrompt 预提取）
     */
    fun createUseDomainTool(
        allTools: List<Tool>,
        loadedDomains: MutableSet<String>,
        skillListText: String? = null,
    ): Tool {
        return Tool(
            name = "use_domain",
            description = "Load tools from a domain. Call this BEFORE using any domain-specific tools. " +
                "Available domains are listed in the <capability_routing> section. " +
                "Call use_domain(\"help\") to list all available domains.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The domain name to load (e.g. \"file\", \"web\", \"mcp:github\", \"skills\")")
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { input ->
                val domainName = input.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")

                when {
                    domainName == "help" -> {
                        listOf(UIMessagePart.Text(buildHelpText(allTools)))
                    }
                    domainName == "skills" && skillListText != null -> {
                        loadedDomains.add("skills")
                        Log.i(TAG, "Domain loaded: skills")
                        listOf(UIMessagePart.Text(skillListText))
                    }
                    else -> {
                        val classified = classifyAll(allTools)
                        val domainTools = classified[domainName].orEmpty()

                        if (domainTools.isEmpty()) {
                            // 错误自愈：返回可用域列表
                            val available = classified.keys
                                .filter { it != "system" }
                                .sorted()
                            listOf(UIMessagePart.Text(
                                "Unknown domain: '$domainName'. Available domains: ${available.joinToString(", ")}.\n" +
                                "Call use_domain(\"help\") for details."
                            ))
                        } else {
                            loadedDomains.add(domainName)
                            val toolNames = domainTools.map { it.name }
                            Log.i(TAG, "Domain loaded: $domainName (${toolNames.size} tools)")
                            listOf(UIMessagePart.Text(
                                "Domain '$domainName' loaded. ${toolNames.size} tools now available: ${toolNames.joinToString(", ")}.\n" +
                                "Call them directly in your next response."
                            ))
                        }
                    }
                }
            }
        )
    }

    /**
     * 生成帮助文本（所有域 + 工具数 + 示例工具名）。
     */
    private fun buildHelpText(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        return buildString {
            appendLine("Available domains:")
            for ((domain, domainTools) in classified.toSortedMap()) {
                if (domain == "system") continue
                val sample = domainTools.take(3).map { it.name }.joinToString(", ")
                val suffix = if (domainTools.size > 3) ", ..." else ""
                appendLine("  [$domain] ${domainTools.size} tools ($sample$suffix)")
            }
            appendLine()
            appendLine("Call use_domain(\"domain_name\") to load tools from a domain.")
        }
    }

    /**
     * 获取指定域的工具列表（用于动态构建 tools 参数）。
     */
    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        return allTools.filter { classifyTool(it) == domainName }
    }
}
