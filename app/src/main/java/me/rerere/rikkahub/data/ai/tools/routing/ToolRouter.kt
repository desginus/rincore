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

        val name = tool.name
        return when {
            name.startsWith("mcp__") -> {
                val parts = name.split("__")
                if (parts.size >= 3) "mcp:${parts[1]}" else "mcp"
            }
            name == "use_skill" -> "skills"
            name == "use_domain" -> "system"
            else -> ToolDomain.classify(tool)?.label ?: "uncategorized"
        }
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        return tools.groupBy { classifyTool(it) }
    }

    fun getTriggerDescription(domain: String): String {
        customDescriptions[domain]?.let { return it }
        return ToolDomain.entries.find { it.label == domain }?.triggerDescription
            ?: "miscellaneous operations not covered by other domains"
    }

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

            for (domain in builtinDomains) {
                val domainTools = classified[domain].orEmpty()
                if (domainTools.isEmpty()) continue
                val trigger = getTriggerDescription(domain)
                appendLine("[$domain] Call use_domain(\"$domain\") when the user wants to: $trigger")
            }

            if (mcpDomains.isNotEmpty()) {
                appendLine()
                appendLine("[mcp] External MCP services. Call use_domain(\"mcp:serviceName\") for their tools.")
                for (domain in mcpDomains) {
                    val serverName = domain.removePrefix("mcp:")
                    val toolCount = classified[domain]?.size ?: 0
                    appendLine("       $serverName ($toolCount tools)")
                }
            }

            if (hasSkills) {
                appendLine()
                appendLine("[skills] Call use_domain(\"skills\") to see available specialized capabilities.")
            }

            appendLine()
            appendLine("You may call use_domain multiple times if the task spans multiple domains.")
            appendLine("If unsure which domain to use, call use_domain(\"help\") for a full list.")
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
            description = "Load tools from a domain. Call this BEFORE using any domain-specific tools. " +
                "Available domains are listed in system prompt. Call use_domain(\"help\") to list all domains.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Domain name, e.g. file, web, mcp:github, skills")
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = { input ->
                val rawDomainName = input.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")

                when {
                    rawDomainName.equals("help", ignoreCase = true) -> {
                        listOf(UIMessagePart.Text(router.buildHelpText(allTools)))
                    }
                    rawDomainName.equals("skills", ignoreCase = true) && skillListText != null -> {
                        loadedDomains.add("skills")
                        Log.i(TAG, "Domain loaded: skills")
                        listOf(UIMessagePart.Text(skillListText))
                    }
                    else -> {
                        val classified = router.classifyAll(allTools)
                        val domainName = classified.keys
                            .filter { it != "system" }
                            .find { it.equals(rawDomainName, ignoreCase = true) }

                        if (domainName == null) {
                            val available = classified.keys
                                .filter { it != "system" }
                                .sorted()
                            listOf(UIMessagePart.Text(
                                "Unknown domain: '$rawDomainName'. Available: ${available.joinToString(", ")}."
                            ))
                        } else {
                            val domainTools = classified[domainName].orEmpty()
                            loadedDomains.add(domainName)
                            val toolNames = domainTools.map { it.name }
                            Log.i(TAG, "Domain loaded: $domainName (${toolNames.size} tools)")
                            listOf(UIMessagePart.Text(
                                "Domain '$domainName' loaded. ${toolNames.size} tools: ${toolNames.joinToString(", ")}."
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

    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        return allTools.filter { classifyTool(it) == domainName }
    }
}
