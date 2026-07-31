package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
                put("new_name", buildJsonObject {
                    put("type", "string")
                    put("description", "新域名(仅 rename 操作使用)")
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
                // 子域一并处理 (parent == name 或 parent 以 name/ 开头)
                val childDomains = settings.customDomains.filter { it.parent == name }
                // 清理所有指向该域及其子域的覆盖
                fun targetsDomain(domain: String): Boolean =
                    domain == name || domain.startsWith("$name/")
                val cleanedOverrides = settings.toolDomainOverrides.filterValues { !targetsDomain(it) }
                val cleanedDescs = settings.customDomainDescriptions.filterKeys { !targetsDomain(it) }
                val cleanedKeywords = settings.customDomainKeywords.filterKeys { !targetsDomain(it) }
                val cleanedNames = settings.domainNameOverrides.filterKeys { !targetsDomain(it) }

                if (existing == null) {
                    // 内置域: 永久移除 (不复活)
                    val removed = settings.removedBuiltinDomains + name
                    val updated = settings.copy(
                        removedBuiltinDomains = removed,
                        toolDomainOverrides = cleanedOverrides,
                        customDomainDescriptions = cleanedDescs,
                        customDomainKeywords = cleanedKeywords,
                        domainNameOverrides = cleanedNames,
                    )
                    settingsStore.update(updated)
                    listOf(UIMessagePart.Text(
                        "已删除内置域 '$name'，相关配置已清理。域内工具按前缀规则自动重分类。"
                    ))
                } else {
                    val updated = settings.copy(
                        customDomains = settings.customDomains.filter { it.name != name && it.parent != name },
                        toolDomainOverrides = cleanedOverrides,
                        customDomainDescriptions = cleanedDescs,
                        customDomainKeywords = cleanedKeywords,
                        domainNameOverrides = cleanedNames,
                    )
                    settingsStore.update(updated)
                    val childInfo = if (childDomains.isNotEmpty())
                        "，同时删除子域 ${childDomains.joinToString("、") { it.name }}"
                    else ""
                    listOf(UIMessagePart.Text(
                        "已删除域 '$name'$childInfo。域内工具的挂载覆盖已清理，将按前缀规则/关键词自动重分类。"
                    ))
                }
            }
            "rename" -> {
                val newName = input.jsonObject["new_name"]?.jsonPrimitive?.content
                    ?: return@Tool listOf(UIMessagePart.Text("rename 需要 new_name 参数"))
                val existing = settings.customDomains.find { it.name == name }
                    ?: return@Tool listOf(UIMessagePart.Text("自定义域 '$name' 不存在。内置域不支持重命名。"))
                if (settings.customDomains.any { it.name == newName }) {
                    return@Tool listOf(UIMessagePart.Text("域 '$newName' 已存在"))
                }
                // 1. 域本身改名 + 子域 parent 随迁
                val newDomains = settings.customDomains.map { d ->
                    when {
                        d.name == name -> d.copy(name = newName)
                        d.parent == name -> d.copy(parent = newName)
                        else -> d
                    }
                }
                // 2. 迁移所有指向旧域的配置 (含子域路径前缀)
                fun remapKey(key: String): String =
                    if (key == name) newName
                    else if (key.startsWith("$name/")) newName + key.removePrefix(name)
                    else key
                val newOverrides = settings.toolDomainOverrides.mapValues { (_, v) ->
                    if (v == name || v.startsWith("$name/")) remapKey(v) else v
                }
                val newDescs = settings.customDomainDescriptions.mapKeys { (k, _) -> remapKey(k) }
                val newKeywords = settings.customDomainKeywords.mapKeys { (k, _) -> remapKey(k) }
                val newNames = settings.domainNameOverrides.mapKeys { (k, _) -> remapKey(k) }

                val updated = settings.copy(
                    customDomains = newDomains,
                    toolDomainOverrides = newOverrides,
                    customDomainDescriptions = newDescs,
                    customDomainKeywords = newKeywords,
                    domainNameOverrides = newNames,
                )
                settingsStore.update(updated)
                listOf(UIMessagePart.Text(
                    "已重命名域 '$name' → '$newName'。挂载映射、描述、关键词、子域父级均已迁移。"
                ))
            }
            else -> listOf(UIMessagePart.Text("未知操作: $action。支持: create, delete, rename"))
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
        val removedSet = settings.removedBuiltinDomains
        val hiddenSet = settings.hiddenDomains
        // 与 buildDomainTree 过滤一致: 按根域过滤
        val domains = settings.customDomains.filter {
            val root = it.name.split("/").first()
            root !in removedSet && root !in hiddenSet &&
            (it.parent == null || it.parent!!.split("/").first() !in removedSet && it.parent!!.split("/").first() !in hiddenSet)
        }
        val builtin = me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }
            .filter { it.split("/").first() !in hiddenSet && it.split("/").first() !in removedSet }
        
        val result = buildString {
            appendLine("内置域 (${builtin.size}个):")
            builtin.forEach { d ->
                appendLine("- $d")
            }
            appendLine()
            appendLine("自定义域 (${domains.size}个):")
            domains.forEach { d ->
                val parentInfo = d.parent?.let { " (父: $it)" } ?: ""
                appendLine("- ${d.name}$parentInfo: ${d.description}")
                if (d.keywords.isNotEmpty()) {
                    appendLine("  关键词: ${d.keywords.joinToString(", ")}")
                }
            }
            if (settings.removedBuiltinDomains.isNotEmpty()) {
                appendLine()
                appendLine("已删除域: ${settings.removedBuiltinDomains.joinToString(", ")}")
            }
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
        // 校验目标域有效性
        val allValid = (me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }.toSet()
            + settings.customDomains.map { it.name }.toSet())
            .filter { it !in settings.hiddenDomains && it !in settings.removedBuiltinDomains }
            .toSet()
        val root = targetDomain.split("/").first()
        if (root !in allValid) {
            listOf(UIMessagePart.Text(
                "无效目标域 '$targetDomain'。" +
                "该域可能已被删除或隐藏。可用域: ${allValid.sorted().joinToString("、")}"
            ))
        } else {
            val updated = settings.copy(
                toolDomainOverrides = settings.toolDomainOverrides + (toolName to targetDomain)
            )
            settingsStore.update(updated)
            listOf(UIMessagePart.Text("已将工具 '$toolName' 移动到域 '$targetDomain'"))
        }
    }
)
