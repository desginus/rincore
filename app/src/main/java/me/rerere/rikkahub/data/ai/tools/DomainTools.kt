/**
 * 域管理工具 — 模块: A. 传输链 / tools
 *
 * 职责: manage_domain / delete_domain / list_domains 三个域管理 AI 工具。
 * 域操作事务化: 变更后场景地图自动同步 (ToolRouter 每步从 settings 重建)。
 *
 * 问题定位: 域操作工具异常/域变更不同步 → 查本文件 + ToolRouter
 */
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
 * Feature #2: AI 可创建/删除/重命名/更新域与子域
 * 提供程序化管理域/子域的能力
 *
 * 修复 (v3.3.8):
 * - 所有操作原子化 (SettingsStore.updateWithResult 互斥读-改-写), 修复并行 create 丢域 / rename+delete 竞态
 * - move 校验工具存在性 + 支持 skill 挂载 (toolDomainOverrides["skill:名"])
 * - create 支持复活已删除的内置域 (removedBuiltinDomains 移除)
 * - delete 不清理工具覆盖 — 子域删除时迁移到父域, 顶级域保留 (工具不被打散)
 * - 新增 update 操作 (改描述/关键词/显示名)
 */
fun createDomainTools(
    settingsStore: SettingsStore,
    knownToolNames: () -> Set<String> = { emptySet() }, // 全量工具名(含 MCP/动态), move 校验用
    knownSkillNames: () -> Set<String> = { emptySet() }, // 已启用 skill 名, move 挂载用
): List<Tool> {
    return listOf(
        createDomainTool(settingsStore),
        deleteDomainTool(settingsStore),
        listDomainsTool(settingsStore, knownToolNames, knownSkillNames),
    )
}

private fun createDomainTool(settingsStore: SettingsStore) = Tool(
    name = "manage_domain",
    description = "创建/删除/重命名/更新工具域或子域。操作后场景地图自动同步。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "操作类型: create(创建) / delete(删除) / rename(重命名) / update(更新描述、关键词、显示名)")
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
                    put("description", "域描述(可选)。update 操作可修改")
                })
                put("keywords", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "关键词列表(可选)，用于自动分类。update 操作可修改")
                })
                put("new_name", buildJsonObject {
                    put("type", "string")
                    put("description", "新域名(仅 rename 操作使用)")
                })
                put("display_name", buildJsonObject {
                    put("type", "string")
                    put("description", "显示名(可选)，update 操作可修改。不填则与域名一致")
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
        val newName = input.jsonObject["new_name"]?.jsonPrimitive?.content
        val displayName = input.jsonObject["display_name"]?.jsonPrimitive?.content

        when (action.lowercase()) {
            "create" -> {
                val msg = settingsStore.updateWithResult { settings ->
                    val builtinNames = me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }.toSet()
                    val existing = settings.customDomains.find { it.name == name }
                    when {
                        // 内置域名: 复活 (移除 removedBuiltinDomains) + 清理 customDomains 同名冲突
                        // 修复: 「技能」等内置域删除后"半存在"(配置层有/工具树无) — create 幂等复活
                        name in builtinNames -> {
                            settings.copy(
                                removedBuiltinDomains = settings.removedBuiltinDomains - name,
                                customDomains = settings.customDomains.filter { it.name != name && it.parent != name },
                            ) to "内置域 '$name' 已就绪（如曾被删除现已复活；同名自定义记录已清理）。场景地图已同步。"
                        }
                        existing != null -> settings to "域 '$name' 已存在"
                        // 复活已删除的内置域 (removedBuiltinDomains 移除) — 兜底
                        name in settings.removedBuiltinDomains -> {
                            settings.copy(
                                removedBuiltinDomains = settings.removedBuiltinDomains - name
                            ) to "已复活内置域 '$name'（原已删除，现恢复）。场景地图已同步。"
                        }
                        else -> {
                            val newDomain = CustomDomain(
                                name = name,
                                parent = parent,
                                description = description,
                                keywords = keywords,
                            )
                            settings.copy(customDomains = settings.customDomains + newDomain) to
                                "已创建域 '$name'${parent?.let { " (父域: $it)" } ?: " (顶级域)"}。场景地图已同步。"
                        }
                    }
                }
                listOf(UIMessagePart.Text(msg))
            }
            "delete" -> {
                val msg = settingsStore.updateWithResult { settings ->
                    val existing = settings.customDomains.find { it.name == name }
                    // 子域一并处理 (parent == name)
                    val childDomains = settings.customDomains.filter { it.parent == name }
                    // 工具覆盖: 不清理 — 子域删除时迁移到父域, 顶级域删除时保留(分类时目标无效回退前缀规则)
                    // 修复: 删除域不再打散工具 (曾致大量 move 修复)
                    val parentDomain = existing?.parent
                    val migratedOverrides = settings.toolDomainOverrides.mapValues { (_, v) ->
                        if (v == name || v.startsWith("$name/")) {
                            parentDomain ?: v
                        } else v
                    }
                    // 清理指向该域的元数据 (描述/关键词/显示名 — 这些是域的属性, 域没了应清)
                    fun targetsDomain(domain: String): Boolean =
                        domain == name || domain.startsWith("$name/")
                    val cleanedDescs = settings.customDomainDescriptions.filterKeys { !targetsDomain(it) }
                    val cleanedKeywords = settings.customDomainKeywords.filterKeys { !targetsDomain(it) }
                    val cleanedNames = settings.domainNameOverrides.filterKeys { !targetsDomain(it) }

                    if (existing == null) {
                        // 内置域: 永久移除 (不复活, 除非 create 同名)
                        val updated = settings.copy(
                            removedBuiltinDomains = settings.removedBuiltinDomains + name,
                            toolDomainOverrides = migratedOverrides,
                            customDomainDescriptions = cleanedDescs,
                            customDomainKeywords = cleanedKeywords,
                            domainNameOverrides = cleanedNames,
                        )
                        updated to "已删除内置域 '$name'。域内工具覆盖已保留/迁移，不会被打散。若此前已加载该域，请重新 invoke_tools(\"帮助\") 刷新场景地图。"
                    } else {
                        val updated = settings.copy(
                            customDomains = settings.customDomains.filter { it.name != name && it.parent != name },
                            toolDomainOverrides = migratedOverrides,
                            customDomainDescriptions = cleanedDescs,
                            customDomainKeywords = cleanedKeywords,
                            domainNameOverrides = cleanedNames,
                        )
                        val childInfo = if (childDomains.isNotEmpty())
                            "，同时删除子域 ${childDomains.joinToString("、") { it.name }}"
                        else ""
                        updated to "已删除域 '$name'$childInfo。域内工具覆盖已${parentDomain?.let { "迁移到父域 '$it'" } ?: "保留"}，不会被打散。若此前已加载该域，请重新 invoke_tools(\"帮助\") 刷新场景地图。"
                    }
                }
                listOf(UIMessagePart.Text(msg))
            }
            "rename" -> {
                val msg = settingsStore.updateWithResult { settings ->
                    if (newName.isNullOrBlank()) return@updateWithResult settings to "rename 需要 new_name 参数"
                    val existing = settings.customDomains.find { it.name == name }
                        ?: return@updateWithResult settings to "自定义域 '$name' 不存在。内置域不支持重命名。"
                    if (settings.customDomains.any { it.name == newName }) {
                        return@updateWithResult settings to "域 '$newName' 已存在"
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

                    settings.copy(
                        customDomains = newDomains,
                        toolDomainOverrides = newOverrides,
                        customDomainDescriptions = newDescs,
                        customDomainKeywords = newKeywords,
                        domainNameOverrides = newNames,
                    ) to "已重命名域 '$name' → '$newName'。挂载映射、描述、关键词、子域父级均已迁移。"
                }
                listOf(UIMessagePart.Text(msg))
            }
            "update" -> {
                // 修复: 描述/触发词/显示名可改 — 无需重建域 (重建曾触发工具打散)
                val msg = settingsStore.updateWithResult { settings ->
                    val existing = settings.customDomains.find { it.name == name }
                    if (existing == null) {
                        return@updateWithResult settings to "自定义域 '$name' 不存在。内置域的描述/关键词由系统定义，可用 domainNameOverrides 修改显示名。"
                    }
                    val updatedDomains = settings.customDomains.map { d ->
                        if (d.name == name) {
                            d.copy(
                                description = description.ifEmpty { d.description },
                                keywords = if (keywords.isEmpty()) d.keywords else keywords,
                            )
                        } else d
                    }
                    var updated = settings.copy(customDomains = updatedDomains)
                    if (!displayName.isNullOrBlank()) {
                        updated = updated.copy(
                            domainNameOverrides = updated.domainNameOverrides + (name to displayName)
                        )
                    } else if (displayName != null) {
                        // display_name 显式传空串 = 清除显示名
                        updated = updated.copy(
                            domainNameOverrides = updated.domainNameOverrides - name
                        )
                    }
                    updated to "已更新域 '$name'：${if (description.isNotEmpty()) "描述, " else ""}${if (keywords.isNotEmpty()) "关键词, " else ""}${if (displayName != null) "显示名" else ""}。"
                }
                listOf(UIMessagePart.Text(msg))
            }
            else -> listOf(UIMessagePart.Text("未知操作: $action。支持: create, delete, rename, update"))
        }
    }
)

private fun deleteDomainTool(settingsStore: SettingsStore) = Tool(
    name = "list_domains",
    description = "列出所有可用域及其触发信息",
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
        // 与 ToolRouter.isValidDomain 过滤一致: 完整路径 + 根域级联 (支持子域级删除/隐藏)
        fun visible(domain: String): Boolean {
            val root = domain.split("/").first()
            return domain !in removedSet && domain !in hiddenSet &&
                root !in removedSet && root !in hiddenSet
        }
        val domains = settings.customDomains.filter { visible(it.name) && (it.parent == null || visible(it.parent!!)) }
        val builtin = me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }.filter { visible(it) }

        val result = buildString {
            appendLine("内置域 (${builtin.size}个):")
            builtin.forEach { d ->
                val builtinEntry = me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.find { it.label == d }
                if (builtinEntry != null) {
                    val kwText = if (builtinEntry.matchKeywords.isEmpty()) "" else
                        " [触发: ${builtinEntry.matchKeywords.take(8).joinToString("、")}" +
                        (if (builtinEntry.matchKeywords.size > 8) " 等${builtinEntry.matchKeywords.size}个" else "") + "]"
                    appendLine("- $d: ${builtinEntry.triggerDescription}$kwText")
                } else {
                    appendLine("- $d")
                }
            }
            appendLine()
            appendLine("自定义域 (${domains.size}个):")
            domains.forEach { d ->
                val parentInfo = d.parent?.let { " (父: $it)" } ?: ""
                appendLine("- ${d.name}$parentInfo: ${d.description}")
                if (d.keywords.isNotEmpty()) {
                    appendLine("  触发: ${d.keywords.take(8).joinToString("、")}" +
                        (if (d.keywords.size > 8) " 等${d.keywords.size}个" else ""))
                }
            }
        }
        listOf(UIMessagePart.Text(result))
    }
)

private fun listDomainsTool(
    settingsStore: SettingsStore,
    knownToolNames: () -> Set<String>,
    knownSkillNames: () -> Set<String>,
) = Tool(
    name = "move_tool_to_domain",
    description = "将工具或 Skill 移动到指定域。移动后该工具/技能在目标域内可见，调用时经 invoke_tools 加载该域即可。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具名称，或已启用 Skill 的名称")
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
        // 校验目标域有效性 — 完整路径 + 根域级联 (与 ToolRouter.isValidDomain 一致)
        fun visible(domain: String): Boolean {
            val root = domain.split("/").first()
            return domain !in settings.hiddenDomains && domain !in settings.removedBuiltinDomains &&
                root !in settings.hiddenDomains && root !in settings.removedBuiltinDomains
        }
        val allValid = (me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }.toSet()
            + settings.customDomains.map { it.name }.toSet())
            .filter { visible(it) }
            .toSet()
        if (targetDomain !in allValid) {
            listOf(UIMessagePart.Text(
                "无效目标域 '$targetDomain'。" +
                "该域可能已被删除或隐藏。可用域: ${allValid.sorted().joinToString("、")}"
            ))
        } else {
            val skills = knownSkillNames()
            val tools = knownToolNames()
            // 修复: move 校验存在性 — 不存在返回失败而非假成功
            val isSkill = toolName in skills
            if (!isSkill && toolName !in tools && !toolName.startsWith("skill_") && toolName != "use_skill") {
                listOf(UIMessagePart.Text(
                    "工具 '$toolName' 不存在。可用工具: ${tools.sorted().take(30).joinToString("、")}${if (tools.size > 30) " 等${tools.size}个" else ""}。" +
                    "若为 Skill，请确认其已启用: ${skills.sorted().take(20).joinToString("、")}"
                ))
            } else {
                // 原子写入: skill 挂载用 "skill:名" 键 (避免与工具名冲突, 且 invoke_tools 可识别)
                val overrideKey = if (isSkill) "skill:$toolName" else toolName
                val msg = settingsStore.updateWithResult { cur ->
                    cur.copy(
                        toolDomainOverrides = cur.toolDomainOverrides + (overrideKey to targetDomain)
                    ) to (if (isSkill) "已将 Skill '$toolName' 挂载到域 '$targetDomain'。调用时经 invoke_tools(\"$targetDomain\") 可见。" else "已将工具 '$toolName' 移动到域 '$targetDomain'")
                }
                listOf(UIMessagePart.Text(msg))
            }
        }
    }
)
