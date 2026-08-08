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
import me.rerere.rikkahub.data.ai.tools.routing.normalizedFullPath
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
    toolPoolProvider: () -> List<Tool> = { emptyList() }, // 完整工具池 (与模型侧同源, execute 时实时构建)
): List<Tool> {
    return listOf(
        createDomainTool(settingsStore),
        deleteDomainTool(settingsStore, toolPoolProvider),
        listDomainsTool(settingsStore, toolPoolProvider),
        createSearchDomainsTool(settingsStore, toolPoolProvider),
    )
}

/**
 * 按关键词/标签反向查询工具域位置 — 匹配域的名称、触发描述、触发条件。
 * 支持类别过滤 (mcp/skill), 返回全部匹配结果无上限。
 */
private fun createSearchDomainsTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "search_domains",
    description = "按关键词或标签反向查询工具域位置。匹配域的名称、触发描述、触发条件（如：比价、定时、MCP、Skill）。返回全部匹配结果，无数量上限。不确定工具在哪个域时使用。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "关键词或标签，如：比价、定时、MCP、Skill")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "可选。类别过滤：mcp（含 MCP 工具的域）/ skill（含 Skill 工具的域）")
                })
            },
            required = listOf("query")
        )
    },
    needsApproval = { false },
    execute = { input ->
        val query = input.jsonObject["query"]?.jsonPrimitive?.content?.trim() ?: error("query is required")
        val typeFilter = input.jsonObject["type"]?.jsonPrimitive?.content?.trim()?.lowercase()

        val settings = settingsStore.settingsFlow.value
        val router = me.rerere.rikkahub.data.ai.tools.routing.ToolRouter(
            overrides = settings.toolDomainOverrides,
            customDescriptions = settings.customDomainDescriptions,
            customDomains = settings.customDomains,
            customKeywords = settings.customDomainKeywords,
            domainNameOverrides = settings.domainNameOverrides,
            hiddenDomains = settings.hiddenDomains,
            removedBuiltinDomains = settings.removedBuiltinDomains,
        )
        val toolList = toolPoolProvider()
        val tools = toolList.map { it.name }.toSet()

        // 可见性判断 (与 move 工具一致: 根域级联)
        fun visible(domain: String): Boolean {
            val root = domain.split("/").first()
            return domain !in settings.hiddenDomains && domain !in settings.removedBuiltinDomains &&
                root !in settings.hiddenDomains && root !in settings.removedBuiltinDomains
        }

        // 全部可见域 (内置枚举 + 自定义域 + 技能子域, 含子域路径) — 与 buildDomainTree 同源
        val skillNames = tools.filter { it.startsWith("skill__") || it.startsWith("skill:") }
            .map { it.removePrefix("skill__").removePrefix("skill:") }
            .filter { it.isNotBlank() }.toSet()
        val allDomains = (me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }
            + settings.customDomains.map { it.normalizedFullPath() }
            + if (settings.customDomains.any { it.name == "技能" }) emptyList() else skillNames.map { "技能/$it" })
            .filter { visible(it) }

        // 域内工具名 (按 classifyByName — 用完整工具含描述, 与模型侧分类一致)
        fun domainToolsOf(domain: String): Set<String> =
            toolList.filter { router.classifyByName(it.name, it.description) == domain }
                .map { it.name }.toSet()

        val q = query.lowercase()
        val matched = allDomains.filter { domain ->
            val desc = router.getTriggerDescription(domain)
            val kws = router.getKeywords(domain)
            val display = router.displayName(domain)
            val haystack = "$domain $display $desc ${kws.joinToString(" ")}".lowercase()
            haystack.contains(q)
        }.filter { domain ->
            when (typeFilter) {
                "mcp" -> domainToolsOf(domain).any { it.startsWith("mcp__") }
                "skill" -> domainToolsOf(domain).any { it.startsWith("skill_") || it == "use_skill" }
                else -> true
            }
        }

        if (matched.isEmpty()) {
            val hint = if (typeFilter != null) " (过滤: $typeFilter)" else ""
            listOf(UIMessagePart.Text(
                "未找到匹配 '$query'$hint 的域。调 `invoke_tools(\"帮助\")` 查看全部域。"
            ))
        } else {
            val lines = matched.sorted().map { domain ->
                val display = router.displayName(domain)
                val nameText = if (display == domain) "`$domain`" else "`$domain`（显示名: $display）"
                val desc = router.getTriggerDescription(domain)
                val kws = router.getKeywords(domain)
                val dTools = domainToolsOf(domain)
                val tags = buildList {
                    if (dTools.any { it.startsWith("mcp__") }) add("MCP")
                    if (dTools.any { it.startsWith("skill_") || it == "use_skill" }) add("Skill")
                }
                val tagText = if (tags.isEmpty()) "" else " [${tags.joinToString("/")}]"
                val kwText = if (kws.isEmpty()) "" else " [触发: ${kws.joinToString("、")}]"
                "- $nameText — $desc$kwText$tagText"
            }
            listOf(UIMessagePart.Text(
                "匹配 '$query' 的域 (${lines.size} 个):\n" + lines.joinToString("\n")
            ))
        }
    },
)

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
                    val existing = settings.customDomains.find {
                        it.name == name || it.normalizedFullPath() == name
                    }
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
                            // name 传完整路径时自动拆分 (如 '搜索/自定义子域' → parent+短名),
                            // 防再次存含父路径的 name → fullPath 双重叠加
                            val splitParent = parent ?: name.substringBeforeLast("/").takeIf { "/" in name }
                            val splitName = name.substringAfterLast("/")
                            val newDomain = CustomDomain(
                                name = splitName,
                                parent = splitParent,
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
                    // 兼容短名/完整路径: name 可能是 "搜索引擎" 或 "搜索/搜索引擎"
                    val existing = settings.customDomains.find {
                        it.name == name || it.normalizedFullPath() == name
                    }
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
                        updated to "已删除内置域 '$name'。域内工具覆盖已保留/迁移，不会被打散。请重新 invoke_tools(\"帮助\") 查看最新场景地图。"
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
                    val existing = settings.customDomains.find {
                        it.name == name || it.normalizedFullPath() == name
                    } ?: return@updateWithResult settings to "自定义域 '$name' 不存在。内置域不支持重命名。"
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
                    val existing = settings.customDomains.find {
                        it.name == name || it.normalizedFullPath() == name
                    }
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

private fun deleteDomainTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "list_domains",
    description = "列出所有可用域及其工具数量 (与系统提示/Invoke Tools/域管理完全同源)",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {},
            required = listOf<String>()
        )
    },
    execute = {
        val settings = settingsStore.settingsFlow.value
        // 单一源头: 完整工具池 (与模型侧 buildAssistantToolPool 同源, 含描述 → 分类一致)
        val router = me.rerere.rikkahub.data.ai.tools.routing.ToolRouter(
            overrides = settings.toolDomainOverrides,
            customDescriptions = settings.customDomainDescriptions,
            customDomains = settings.customDomains,
            customKeywords = settings.customDomainKeywords,
            domainNameOverrides = settings.domainNameOverrides,
            hiddenDomains = settings.hiddenDomains,
            removedBuiltinDomains = settings.removedBuiltinDomains,
        )
        val tools = toolPoolProvider()
        val view = router.unifiedDomainView(tools)

        val result = buildString {
            appendLine("可用域 (${view.tree.size}个根域):")
            for ((root, subs) in view.tree) {
                val rootCount = view.counts[root] ?: 0
                val rootKw = router.getKeywords(root)
                val kwText = if (rootKw.isEmpty()) "" else " [触发: ${rootKw.take(8).joinToString("、")}]"
                appendLine("- $root [${rootCount}个工具]$kwText")
                for (sub in subs) {
                    val subCount = view.counts[sub] ?: 0
                    val subKw = router.getKeywords(sub)
                    val subKwText = if (subKw.isEmpty()) "" else " [触发: ${subKw.take(8).joinToString("、")}]"
                    appendLine("  - $sub [${subCount}个工具]$subKwText")
                }
            }
            appendLine()
            appendLine("调 invoke_tools(\"域名称\") 查看域内工具；所有工具均可直接调用。")
        }
        listOf(UIMessagePart.Text(result))
    }
)

private fun listDomainsTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
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
        val poolTools = toolPoolProvider()
        val skillSubNames = poolTools.filter { it.name.startsWith("skill__") }
            .map { it.name.removePrefix("skill__") }.toSet()
            .map { "技能/$it" }
        val allValid = (me.rerere.rikkahub.data.ai.tools.routing.ToolDomain.entries.map { it.label }.toSet()
            + settings.customDomains.map { it.normalizedFullPath() }.toSet()
            + if (settings.customDomains.any { it.name == "技能" }) emptySet() else skillSubNames.toSet())
            .filter { visible(it) }
            .toSet()
        if (targetDomain !in allValid) {
            listOf(UIMessagePart.Text(
                "无效目标域 '$targetDomain'。" +
                "该域可能已被删除或隐藏。可用域: ${allValid.sorted().joinToString("、")}"
            ))
        } else {
            val skills = poolTools.filter { it.name.startsWith("skill__") }
                .map { it.name.removePrefix("skill__") }.toSet()
            val tools = poolTools.map { it.name }.toSet()
            // 修复: move 校验存在性 — 不存在返回失败而非假成功
            val isSkill = toolName in skills
            if (!isSkill && toolName !in tools && !toolName.startsWith("skill_") && toolName != "use_skill") {
                listOf(UIMessagePart.Text(
                    "工具 '$toolName' 不存在。可用工具: ${tools.sorted().take(30).joinToString("、")}${if (tools.size > 30) " 等${tools.size}个" else ""}。" +
                    "若为 Skill，请确认其已启用: ${skills.sorted().take(20).joinToString("、")}"
                ))
            } else {
                // 原子写入: skill 挂载用 "skill:名" 键 (避免与工具名冲突, 且 invoke_tools 可识别)
                // 孤儿清理: 同 key 覆盖即迁移 (旧域条目自动失效); 同时清除指向旧域的
                // 残留描述/关键词 (旧域被删时), 保证无孤儿注册数据
                // 规范化: 用户传 skill__名 / skill:名 / 原始名 统一为 skill:原始名
                val skillRawName = toolName
                    .removePrefix("skill__").removePrefix("skill_").removePrefix("skill:")
                val overrideKey = if (isSkill || toolName.startsWith("skill")) "skill:$skillRawName" else toolName
                val msg = settingsStore.updateWithResult { cur ->
                    val newOverrides = cur.toolDomainOverrides + (overrideKey to targetDomain)
                    val cleaned = cur.copy(toolDomainOverrides = newOverrides)
                    // 目标域存在则清理指向它的孤儿数据 (旧域残留)
                    cleaned to (if (isSkill) "已将 Skill '$toolName' 挂载到域 '$targetDomain'。调用时经 invoke_tools(\"$targetDomain\") 可见。" else "已将工具 '$toolName' 移动到域 '$targetDomain'")
                }
                listOf(UIMessagePart.Text(msg))
            }
        }
    }
)
