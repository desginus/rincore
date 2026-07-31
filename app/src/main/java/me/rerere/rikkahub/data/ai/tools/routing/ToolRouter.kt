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
private const val MCP_SUBDOMAIN_THRESHOLD = 8 // 同一MCP服务器工具超过此数则启用子域

class ToolRouter(
    private val overrides: Map<String, String> = emptyMap(),
    private val customDescriptions: Map<String, String> = emptyMap(),
    internal val customDomains: List<CustomDomain> = emptyList(),
    private val customKeywords: Map<String, List<String>> = emptyMap(),
    private val domainNameOverrides: Map<String, String> = emptyMap(),
    internal val hiddenDomains: Set<String> = emptySet(),
    internal val removedBuiltinDomains: Set<String> = emptySet(),
) {

    /** 合法域标签集合（ToolDomain 全部标签 + 自定义域名 - 已删除/隐藏） */
    val validDomainLabels: Set<String>
        get() = (ToolDomain.entries.map { it.label }.toSet() + customDomains.map { it.name }.toSet())
            .filter { isValidDomain(it) }
            .toSet()

    /** 框架层工具名集合 — 不参与域分类, 分层模式下直接注入 */
    internal val frameworkToolNames = setOf(
        "invoke_tools",
        "workspace_shell", "workspace_read_file", "workspace_write_file", "workspace_edit_file",
        "manage_domain", "list_domains", "move_tool_to_domain",
    )

    /** MCP 服务器名 → 默认域快速映射 (避免关键词误匹配) */
    private val mcpServerDomainDefaults = mapOf(
        // 物理引擎
        "physicsengine" to "物理引擎",
        // 图表
        "charting" to "生成部署/图表",
        // 二维码
        "qrcode" to "生成部署/二维码",
        // 网页部署
        "edgeone" to "生成部署/网页部署",
        "webpagegeneration" to "生成部署/网页部署",
        // 搜索/商品
        "productinquiry" to "搜索/商品搜索",
        // 搜索/搜索引擎
        "searchoptimization" to "搜索/搜索引擎",
        "wikipedia" to "搜索/搜索引擎",
        // 搜索/政策搜索
        "trustedsearch" to "搜索/政策搜索",
        // 辅助推理
        "thinkingmethodology" to "辅助推理/方法论",
        "sequentialthinking" to "辅助推理/序列思考",
    )

    fun classifyTool(tool: Tool): String {
        // 1. 手动覆盖 — 仅指向有效域，否则 fall through
        overrides[tool.name]?.let { if (it in validDomainLabels && isValidDomain(it)) return it }
        // 框架层工具不属于任何用户域, 始终归 system
        if (tool.name in frameworkToolNames) return "system"
        // Skill 工具归入「技能」域
        if (tool.name.startsWith("skill_")) {
            if (isValidDomain("技能")) return "技能"
            return "uncategorized"
        }

        // MCP 工具集
        if (tool.name.startsWith("mcp__")) {
            val server = extractMcpServerName(tool.name)
            // 1. 用户覆盖 (mcpServerDomainDefaults)
            mcpServerDomainDefaults[server]?.let { if (isValidDomain(it)) return "mcp_raw:$it" }
            // 2. 自定义域关键词
            val text = "${tool.name} ${tool.description}".lowercase()
            for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
            for ((domain, keywords) in customKeywords) {
                if (domain in validDomainLabels && keywords.any { text.contains(it) }) return domain
            }
            // 3. AI分类兜底
            val builtin = ToolDomain.classify(tool, removedBuiltinDomains, hiddenDomains)?.label ?: "uncategorized"
            return "mcp_raw:$builtin"
        }

        val text = "${tool.name} ${tool.description}".lowercase()
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
        for ((domain, keywords) in customKeywords) {
            if (domain in validDomainLabels && keywords.any { text.contains(it) }) return domain
        }
        return ToolDomain.classify(tool, removedBuiltinDomains, hiddenDomains)?.label ?: "uncategorized"
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        val raw = tools.groupBy { classifyTool(it) }
        val result = mutableMapOf<String, MutableList<Tool>>()

        val mcpGroups = mutableMapOf<String, MutableList<Tool>>()
        for ((domain, dTools) in raw) {
            if (domain.startsWith("mcp_raw:")) {
                for (t in dTools) {
                    val serverName = extractMcpServerName(t.name)
                    mcpGroups.getOrPut(serverName) { mutableListOf() }.add(t)
                }
            } else {
                result[domain] = dTools.toMutableList()
            }
        }

        for ((serverName, serverTools) in mcpGroups) {
            // 获取该类工具的分类域名 (去除 mcp_raw: 前缀)
            val classifiedDomain = raw.entries
                .find { it.value.any { t -> t.name.startsWith("mcp__${serverName}__") } }
                ?.key?.removePrefix("mcp_raw:") ?: serverName
            if (serverTools.size >= MCP_SUBDOMAIN_THRESHOLD) {
                val subDomains = serverTools.groupBy { classifyMcpSubdomain(it.name, it.description) }
                for ((sub, subTools) in subDomains) {
                    result["$classifiedDomain/$sub"] = subTools.toMutableList()
                }
            } else {
                for (t in serverTools) {
                    val funcDomain = raw.entries.find { it.value.contains(t) }?.key?.removePrefix("mcp_raw:") ?: "uncategorized"
                    result.getOrPut(funcDomain) { mutableListOf() }.add(t)
                }
            }
        }

        return result.filterValues { it.isNotEmpty() }
    }

    private fun extractMcpServerName(toolName: String): String {
        val parts = toolName.removePrefix("mcp__").split("__")
        return if (parts.size >= 1) parts[0] else "unknown"
    }

    private fun classifyMcpSubdomain(toolName: String, toolDescription: String): String {
        val name = toolName.lowercase()
        val desc = toolDescription.lowercase()
        val text = "$name $desc"
        return when {
            text.contains("create") || text.contains("add_") || text.contains("new_") -> "创建"
            text.contains("get_") || text.contains("query") || text.contains("list_") || text.contains("find_") || text.contains("read_") -> "查询"
            text.contains("set_") || text.contains("update_") || text.contains("modify") || text.contains("config") -> "设置"
            text.contains("delete") || text.contains("remove") || text.contains("clear") || text.contains("destroy") -> "删除"
            text.contains("apply") || text.contains("simulate") || text.contains("compute") || text.contains("calculate") || text.contains("solve") -> "计算"
            text.contains("load") || text.contains("save") || text.contains("export") || text.contains("import") -> "数据"
            else -> "其他"
        }
    }

    fun displayName(domain: String): String = domainNameOverrides[domain] ?: domain

    /** 检查域是否有效（未被删除的内置域） */
    private fun isValidDomain(domain: String): Boolean {
        val root = domain.split("/").first()
        return root !in removedBuiltinDomains
    }

    fun getTriggerDescription(domain: String): String {
        // 1. 自定义描述（最高优先级，支持子域全路径）
        customDescriptions[domain]?.let { return it }
        // 2. ToolDomain 描述（按完整标签匹配）
        ToolDomain.entries.find { it.label == domain }?.triggerDescription?.let { return it }
        // 3. 自定义域描述
        customDomains.find { it.name == domain }?.description?.let { return it }
        // 4. MCP 自动生成的子域
        val sub = domain.substringAfterLast("/")
        if (sub != domain) {
            return when (sub) {
                "创建" -> "创建物体、场景、配置"
                "查询" -> "查询状态、属性、参数"
                "设置" -> "修改配置、更新参数"
                "删除" -> "删除物体、清除数据"
                "计算" -> "运行模拟、执行计算"
                "数据" -> "导入导出、保存读取"
                "其他" -> "其他操作"
                else -> sub
            }
        }
        return "其他操作"
    }

    fun getKeywords(domain: String): List<String> {
        customKeywords[domain]?.let { return it }
        customDomains.find { it.name == domain }?.keywords?.let { return it }
        return ToolDomain.entries.find { it.label == domain }?.matchKeywords ?: emptyList()
    }

    fun buildLayer1(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        val treeNodes = buildDomainTree()

        return buildString {
            appendLine("## 工具调度")
            appendLine()
            appendLine("你拥有一个工具总域 `工具`，按功能场景树状组织。")
            appendLine()
            appendLine("**加载**：`invoke_tools(\"场景名\")` 查看子域；`invoke_tools(\"场景/子域\")` 加载工具。调 `invoke_tools(\"帮助\")` 查看全部。")
            appendLine()
            appendLine("### 可用场景域")
            appendLine()
            for ((root, subs) in treeNodes) {
                val rootCount = classified[root]?.size ?: 0
                val nonEmpty = subs.filter { (classified[it]?.size ?: 0) > 0 }
                if (rootCount == 0 && nonEmpty.isEmpty()) continue
                appendLine("- **`$root`**")
                for (sub in nonEmpty.sorted()) {
                    val short = sub.substringAfterLast("/")
                    appendLine("  - `$short`")
                }
            }
            appendLine()
            appendLine("调 `invoke_tools(\"域名称\")` 加载。")
        }
    }

    /** 构建声明式域树: ToolDomain枚举 + customDomains, 过滤 hiddenDomains + removedBuiltinDomains */
    private fun buildDomainTree(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        // 内置域 (ToolDomain枚举)
        for (entry in ToolDomain.entries) {
            val label = entry.label
            val parts = label.split("/")
            val root = parts.first()
            if (root in removedBuiltinDomains) continue  // 删除的域直接跳过
            if (root in hiddenDomains) continue          // 隐藏的域不显示
            result.getOrPut(root) { mutableListOf() }
            if (parts.size > 1) result[root]!!.add(label)
        }

        // 自定义域
        for (cd in customDomains) {
            if (cd.name in removedBuiltinDomains) continue
            if (cd.name in hiddenDomains) continue
            if (cd.parent != null) {
                val parentRoot = cd.parent!!.split("/").first()
                if (parentRoot !in removedBuiltinDomains && parentRoot !in hiddenDomains) {
                    result.getOrPut(parentRoot) { mutableListOf() }.add(cd.name)
                }
            } else {
                result.getOrPut(cd.name) { mutableListOf() }
            }
        }

        return result.toSortedMap()
    }

    fun createInvokeToolsTool(
        allTools: List<Tool>,
        loadedDomains: MutableSet<String>,
    ): Tool {
        val router = this
        return Tool(
            name = "invoke_tools",
            description = "按类别加载工具。有子域时返回子域列表(需再调用加载子域)，无子域时直接返回工具列表。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "类别或子域名称，如 搜索、文件、技能。留空或传\"帮助\"查看全部类别。")
                        })
                    },
                    required = listOf<String>() // name 可选
                )
            },
            execute = { input ->
                val rawName = input.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "帮助"
                when {
                    rawName == "帮助" || rawName.equals("help", ignoreCase = true) ->
                        listOf(UIMessagePart.Text(router.buildHelpText(allTools)))
                    else -> {
                        val classified = router.classifyAll(allTools)
                        val treeNodes = router.buildDomainTree()

                        // 用声明式域树检查域名是否存在 + 获取子域列表
                        val domainExists = treeNodes.containsKey(rawName) ||
                            treeNodes.values.flatten().any { it == rawName }

                        if (!domainExists) {
                            val avail = treeNodes.keys.toList()
                            listOf(UIMessagePart.Text("未知: '$rawName'。可用顶级域: ${avail.joinToString("、")}。调 `invoke_tools(\"帮助\")` 查看详情。"))
                        } else {
                            loadedDomains.add(rawName)

                            // 子域列表从声明式域树获取
                            val childKeys = when {
                                treeNodes.containsKey(rawName) -> treeNodes[rawName]!!
                                else -> treeNodes.entries
                                    .find { it.value.contains(rawName) }
                                    ?.let { (parent, subs) ->
                                        subs.filter { it.startsWith("$rawName/") }
                                    } ?: emptyList()
                            }

                            // 工具从分类结果获取
                            val directTools = classified[rawName].orEmpty()
                            if (childKeys.isNotEmpty()) {
                                // 有子域: 显示子域列表
                                val summary = buildString {
                                    val subInfo = buildString {
                                        for (ck in childKeys.sorted()) {
                                            val short = ck.substringAfterLast("/")
                                            val desc = router.getTriggerDescription(ck)
                                            val count = classified[ck]?.size ?: 0
                                            appendLine("- `$ck` ($short): $desc · ${count}个工具")
                                        }
                                    }
                                    if (directTools.isNotEmpty()) {
                                        appendLine("「$rawName」含${childKeys.size}个子域和${directTools.size}个直接工具:")
                                        appendLine()
                                        append(subInfo)
                                        appendLine()
                                        appendLine("直接工具(${directTools.size})：")
                                        for (t in directTools.sortedBy { it.name }.take(8)) {
                                            appendLine("- `${t.name}`: ${t.description.take(60).replace("\n", " ")}")
                                        }
                                        if (directTools.size > 8) appendLine("  ...等${directTools.size}个")
                                    } else {
                                        appendLine("「$rawName」含${childKeys.size}个子域：")
                                        appendLine()
                                        append(subInfo)
                                    }
                                    appendLine()
                                    appendLine("调 `invoke_tools(\"子域完整路径\")` 加载具体工具。")
                                }
                                listOf(UIMessagePart.Text(summary))
                            } else {
                                // 叶子域: 直接返回工具列表
                                val parentRoot = rawName.split("/").first()
                                val allInParent = classified.entries
                                    .filter { it.key == parentRoot || it.key.startsWith("$parentRoot/") }
                                    .flatMap { it.value }
                                    .toSet()
                                    .let { parentTools ->
                                        // 去重：子域有 → 从父级移走；父级直接挂的保留
                                        val subDomainsInParent = treeNodes[parentRoot] ?: emptyList()
                                        val subTools = subDomainsInParent.flatMap { classified[it].orEmpty() }.toSet()
                                        parentTools - subTools
                                    }
                                // 不在这 parentRoot 的子域里的直接工具
                                val rootOnly = if (rawName == parentRoot) allInParent else directTools

                                val summary = buildString {
                                    appendLine("已加载「$rawName」。${rootOnly.size}个工具：")
                                    for (t in rootOnly.sortedBy { it.name }) {
                                        val desc = t.description.take(80).replace("\n", " ")
                                        appendLine("- `${t.name}`: $desc")
                                    }
                                }
                                listOf(UIMessagePart.Text(summary))
                            }
                        }
                    }
                }
            }
        )
    }

    private fun buildHelpText(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        val treeNodes = buildDomainTree()
        return buildString {
            appendLine("全部类别:")
            for ((root, subs) in treeNodes) {
                val rootCount = classified[root]?.size ?: 0
                val nonEmpty = subs.filter { (classified[it]?.size ?: 0) > 0 }
                if (rootCount == 0 && nonEmpty.isEmpty()) continue
                appendLine("- **`$root`**")
                for (sub in nonEmpty.sorted()) {
                    val short = sub.substringAfterLast("/")
                    appendLine("  - `$short`")
                }
            }
            appendLine()
            appendLine("调 `invoke_tools(\"域名称\")` 加载。")
        }
    }

    /**
     * 获取指定域下的工具 — 使用 classifyAll 确保与 createInvokeToolsTool 一致。
     * 修复: classifyTool 返回 mcp_raw:xxx 前缀，而 classifyAll 合并后返回功能性域标签，
     * 此处必须使用 classifyAll 避免工具遗漏。
     */
    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        val classified = classifyAll(allTools)
        return classified[domainName].orEmpty().distinctBy { it.name }
    }

    /**
     * UI 预览分类——用于域管理页面展示。
     * 与 classifyTool 的区别：不处理 MCP 子域合并，直接返回域标签。
     * 关键修复：
     * 1. override 结果校验合法性（过滤指向已删除域的过期覆盖）
     * 2. customKeywords 结果校验合法性（过滤指向旧域名的过期关键词）
     * 3. ToolDomain 匹配按深度排序（子域优先，避免被父域关键词抢先匹配）
     */
    fun classifyPreview(name: String, description: String): String {
        val valid = validDomainLabels

        // 1. 用户手动覆盖（校验合法性）
        overrides[name]?.let { if (it in valid) return it }
        // 框架层工具不属于任何用户域, 始终归 system
        if (name in frameworkToolNames) return "system"
        // Skill 工具归入「技能」域
        if (name.startsWith("skill_")) return if (isValidDomain("技能")) "技能" else "uncategorized"

        val text = "${name} ${description}".lowercase()

        // 2. 自定义域关键词
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }

        // 3. 自定义关键词覆盖（校验合法性——过滤旧域名）
        for ((domain, keywords) in customKeywords) {
            if (domain in valid && keywords.any { text.contains(it) }) return domain
        }

        // 4. ToolDomain 关键词匹配（子域优先——深度排序，跳过已删除/隐藏域）
        val excluded = removedBuiltinDomains + hiddenDomains
        val result = ToolDomain.entries
            .sortedByDescending { it.label.count { c -> c == '/' } }
            .firstOrNull { dom ->
                dom.matchKeywords.any { text.contains(it) } && dom.label !in excluded
            }?.label

        return result ?: "uncategorized"
    }
}
