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

    /** 合法域标签集合（ToolDomain 全部标签 + 自定义域名） */
    val validDomainLabels: Set<String>
        get() = ToolDomain.entries.map { it.label }.toSet() + customDomains.map { it.name }.toSet()

    fun classifyTool(tool: Tool): String {
        overrides[tool.name]?.let { if (it in validDomainLabels) return it }
        // 框架层工具不属于任何用户域, 始终归 system
        if (tool.name == "invoke_tools" || tool.name.startsWith("memory_")) return "system"

        // MCP 工具集：同一服务器工具数 > 阈值则启用子域
        if (tool.name.startsWith("mcp__")) {
            val text = "${tool.name} ${tool.description}".lowercase()
            for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
            for ((domain, keywords) in customKeywords) { if (keywords.any { text.contains(it) } && domain in validDomainLabels) return domain }
            val builtin = ToolDomain.classify(tool)?.label ?: "uncategorized"
            return "mcp_raw:$builtin" // 临时标记，后续合并
        }

        val text = "${tool.name} ${tool.description}".lowercase()
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
        for ((domain, keywords) in customKeywords) { if (keywords.any { text.contains(it) } && domain in validDomainLabels) return domain }
        return ToolDomain.classify(tool)?.label ?: "uncategorized"
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
            if (serverTools.size >= MCP_SUBDOMAIN_THRESHOLD) {
                val subDomains = serverTools.groupBy { classifyMcpSubdomain(it.name, it.description) }
                for ((sub, subTools) in subDomains) {
                    result["$serverName/$sub"] = subTools.toMutableList()
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
        if (root in removedBuiltinDomains) return false
        return domain !in hiddenDomains
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
        val allDomains = classified.keys.filter { it != "system" && isValidDomain(it) }.sorted()
        val topMap = mutableMapOf<String, MutableList<String>>()
        for (d in allDomains) {
            val root = d.split("/").first()
            topMap.getOrPut(root) { mutableListOf() }.add(d)
        }

        return buildString {
            appendLine("## 工具调度")
            appendLine()
            appendLine("你拥有一个工具总域 `工具`，包含完成各类任务所需的全部工具，按功能场景树状组织。")
            appendLine()
            appendLine("**加载方式**：`invoke_tools(\"场景名\")` 或 `invoke_tools(\"场景/子场景\")`。路径越深，加载的工具越少越精准。")
            appendLine()
            appendLine("### 调度原则")
            appendLine()
            appendLine("**积极调用，工具优先。** 你不是一个只能说话的模型——你有手（搜索/浏览器）、有眼（读取文件/截图）、有计算能力（物理引擎/JS执行）。能用工具解决的，不靠猜测。需要什么，加载什么。")
            appendLine()
            appendLine("**按需加载，用完即走。** 判断任务需要什么场景，精确加载。不需要囤积工具——上下文是你的工作台，只放当前要用的。场景不对就换，路径不够深就再下一层。")
            appendLine()
            appendLine("**不确定时向上看。** 不知道具体该加载哪个子场景？加载上层节点。比如不确定物理问题属于运动学还是动力学，加载 `计算/物理`，所有物理子场景的工具都会出现。")
            appendLine()
            appendLine("**复杂任务组合加载。** \"搜数据然后画图\"→ 先加载搜索工具搜到数据，再加载图表工具画图。\"打开网页填表提交\"→ 一次性加载浏览器工具。")
            appendLine()
            appendLine("### 场景地图")
            appendLine()

            for ((root, subs) in topMap.toSortedMap()) {
                val rootTools = classified.filterKeys { it == root || it.startsWith("$root/") }.flatMap { it.value }
                val total = rootTools.size
                val subCount = subs.count { it != root && isValidDomain(it) }
                val desc = getTriggerDescription(root)

                val leafSubs = subs.filter { it != root && isValidDomain(it) }.sorted()

                if (subCount > 0) {
                    appendLine("| `$root` | $desc | ${total}个工具 · ${subCount}个子场景 |")
                    leafSubs.take(4).forEach { sub ->
                        val subTotal = classified[sub]?.size ?: 0
                        val sDesc = getTriggerDescription(sub)
                        appendLine("| 　└ `$sub` | $sDesc | ${subTotal}个 |")
                    }
                    if (leafSubs.size > 4) appendLine("| 　└ … | 还有${leafSubs.size - 4}个子场景 | |")
                } else {
                    appendLine("| `$root` | $desc | ${total}个工具 |")
                }
            }

            appendLine()
            appendLine("跨类别任务可多次调用 invoke_tools。不确定时调 `invoke_tools(\"帮助\")` 查看完整列表。")

            // 列出所有 Skill 工具（帮助模型判断何时加载对应域）
            val skillTools = tools.filter { it.name.startsWith("skill_") }
            if (skillTools.isNotEmpty()) {
                appendLine()
                appendLine("### 可用技能")
                appendLine()
                for (s in skillTools) {
                    appendLine("- `${s.name}`: ${s.description.take(100)}")
                }
                appendLine()
                appendLine("技能工具已按功能归入对应域。调用 `invoke_tools(\"域名\")` 加载后即可直接使用。")
            }
        }
    }

    fun createInvokeToolsTool(
        allTools: List<Tool>,
        loadedDomains: MutableSet<String>,
    ): Tool {
        val router = this
        return Tool(
            name = "invoke_tools",
            description = "按类别加载工具。支持层级加载：父域加载所有子域，子域只加载自身。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "类别或子域名称，如 搜索、文件、物理引擎/创建")
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
                        // 严格按请求加载: rawName 本身或其下的所有子域
                        val matchKeys = classified.keys.filter { it == rawName || it.startsWith("$rawName/") }
                        if (matchKeys.isEmpty()) {
                            val avail = classified.keys.filter { it != "system" }.sorted()
                            listOf(UIMessagePart.Text("未知: '$rawName'。可用: ${avail.joinToString("、")}"))
                        } else {
                            val dTools = matchKeys.flatMap { classified[it].orEmpty() }
                            // 只记录用户请求的域路径, getDomainTools 会自动展开子域
                            loadedDomains.add(rawName)
                            val names = dTools.map { it.name }
                            val label = if (matchKeys.size > 1) "「$rawName」及其${matchKeys.size-1}个子域" else "「$rawName」"
                            val summary = buildString {
                                appendLine("已加载$label。${names.size}个工具:")
                                for (t in dTools.sortedBy { it.name }) {
                                    val desc = t.description.take(80).replace("\n", " ")
                                    appendLine("- `${t.name}`: $desc")
                                }
                            }
                            listOf(UIMessagePart.Text(summary))
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
            appendLine(); appendLine("调 invoke_tools(\"类别名\") 加载。")
        }
    }

    /**
     * 获取指定域下的工具 — 使用 classifyAll 确保与 createInvokeToolsTool 一致。
     * 修复: classifyTool 返回 mcp_raw:xxx 前缀，而 classifyAll 合并后返回功能性域标签，
     * 此处必须使用 classifyAll 避免工具遗漏。
     */
    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        val classified = classifyAll(allTools)
        return classified.filterKeys { it == domainName || it.startsWith("$domainName/") }
            .flatMap { it.value }
            .distinctBy { it.name }
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
        if (name == "invoke_tools" || name.startsWith("memory_")) return "system"

        val text = "${name} ${description}".lowercase()

        // 2. 自定义域关键词
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }

        // 3. 自定义关键词覆盖（校验合法性——过滤旧域名）
        for ((domain, keywords) in customKeywords) {
            if (domain in valid && keywords.any { text.contains(it) }) return domain
        }

        // 4. ToolDomain 关键词匹配（子域优先——深度排序）
        val result = ToolDomain.entries
            .sortedByDescending { it.label.count { c -> c == '/' } }
            .firstOrNull { dom -> dom.matchKeywords.any { text.contains(it) } }?.label

        return result ?: "uncategorized"
    }
}
