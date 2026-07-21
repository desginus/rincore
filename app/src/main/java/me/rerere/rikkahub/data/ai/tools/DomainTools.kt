package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.CustomDomain
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Feature #2: AI 可创建与删除区域/子域
 * 提供程序化管理域/子域的能力
 */
fun createDomainTools(
    settingsStore: SettingsStore,
): List<Tool> {
    return listOf(
        createDomainTool(settingsStore),
        deleteDomainTool(settingsStore),
        listDomainsTool(settingsStore),
    )
}

private fun createDomainTool(settingsStore: SettingsStore) = Tool(
    name = "manage_domain",
    description = "创建或删除工具域/子域。操作后场景地图自动同步。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "操作类型: create(创建) 或 delete(删除)")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "域名，如 '我的工具' 或 '搜索/自定义子域'")
                })
                put("parent", buildJsonObject {
                    put("type", "string")
                    put("description", "父域名(可选)，如 '搜索'。不填则为顶级域")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "域描述(可选)")
                })
                put("keywords", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "关键词列表(可选)，用于自动分类")
                })
            },
            required = listOf("action", "name")
        )
    },
    execute = { input ->
        val action = input.jsonObject["action"]?.jsonPrimitive?.content ?: error("action required")
        val name = input.jsonObject["name"]?.jsonPrimitive?.content ?: error("name required")
        val parent = input.jsonObject["parent"]?.jsonPrimitive?.content
        val description = input.jsonObject["description"]?.jsonPrimitive?.content ?: ""
        val keywords = input.jsonObject["keywords"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        val settings = settingsStore.settingsFlow.value

        when (action.lowercase()) {
            "create" -> {
                val existing = settings.customDomains.find { it.name == name }
                if (existing != null) {
                    listOf(UIMessagePart.Text("域 '$name' 已存在"))
                } else {
                    val newDomain = CustomDomain(
                        name = name,
                        parent = parent,
                        description = description,
                        keywords = keywords,
                    )
                    val updated = settings.copy(
                        customDomains = settings.customDomains + newDomain
                    )
                    settingsStore.update(updated)
                    val parentInfo = parent?.let { " (父域: $it)" } ?: " (顶级域)"
                    listOf(UIMessagePart.Text("已创建域 '$name'$parentInfo。场景地图已同步。"))
                }
            }
            "delete" -> {
                val existing = settings.customDomains.find { it.name == name }
                if (existing == null) {
                    // 尝试隐藏内置域
                    val builtinHidden = settings.hiddenDomains + name
                    val updated = settings.copy(hiddenDomains = builtinHidden)
                    settingsStore.update(updated)
                    listOf(UIMessagePart.Text("已隐藏内置域 '$name'。场景地图已同步。"))
                } else {
                    // 删除自定义域，同时清理相关覆盖
                    val cleanedOverrides = settings.toolDomainOverrides.filterValues { it != name }
                    val cleanedDescs = settings.customDomainDescriptions.filterKeys { it != name }
                    val cleanedKeywords = settings.customDomainKeywords.filterKeys { it != name }
                    val cleanedNames = settings.domainNameOverrides.filterKeys { it != name }
                    
                    val updated = settings.copy(
                        customDomains = settings.customDomains.filter { it.name != name },
                        toolDomainOverrides = cleanedOverrides,
                        customDomainDescriptions = cleanedDescs,
                        customDomainKeywords = cleanedKeywords,
                        domainNameOverrides = cleanedNames,
                    )
                    settingsStore.update(updated)
                    listOf(UIMessagePart.Text("已删除域 '$name' 及相关配置。场景地图已同步。"))
                }
            }
            else -> listOf(UIMessagePart.Text("未知操作: $action。支持: create, delete"))
        }
    }
)

private fun deleteDomainTool(settingsStore: SettingsStore) = Tool(
    name = "list_domains",
    description = "列出所有可用域及其工具数量",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {},
            required = listOf<String>()
        )
    },
    execute = {
        val settings = settingsStore.settingsFlow.value
        val domains = settings.customDomains
        
        val result = buildString {
            appendLine("自定义域 (${domains.size}个):")
            domains.forEach { d ->
                val parentInfo = d.parent?.let { " (父: $it)" } ?: ""
                appendLine("- ${d.name}$parentInfo: ${d.description}")
                if (d.keywords.isNotEmpty()) {
                    appendLine("  关键词: ${d.keywords.joinToString(", ")}")
                }
            }
            appendLine()
            appendLine("已隐藏域: ${settings.hiddenDomains.joinToString(", ").ifEmpty { "无" }}")
        }
        listOf(UIMessagePart.Text(result))
    }
)

private fun listDomainsTool(settingsStore: SettingsStore) = Tool(
    name = "move_tool_to_domain",
    description = "将工具移动到指定域",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具名称")
                })
                put("target_domain", buildJsonObject {
                    put("type", "string")
                    put("description", "目标域名，如 '搜索/搜索引擎'")
                })
            },
            required = listOf("tool_name", "target_domain")
        )
    },
    execute = { input ->
        val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.content ?: error("tool_name required")
        val targetDomain = input.jsonObject["target_domain"]?.jsonPrimitive?.content ?: error("target_domain required")

        val settings = settingsStore.settingsFlow.value
        val updated = settings.copy(
            toolDomainOverrides = settings.toolDomainOverrides + (toolName to targetDomain)
        )
        settingsStore.update(updated)
        listOf(UIMessagePart.Text("已将工具 '$toolName' 移动到域 '$targetDomain'"))
    }
)
