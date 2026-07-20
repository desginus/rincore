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
    private val hiddenDomains: Set<String> = emptySet(),
    private val removedBuiltinDomains: Set<String> = emptySet(),
) {

    fun classifyTool(tool: Tool): String {
        overrides[tool.name]?.let { return it }
        if (tool.name == "use_domain") return "system"

        // MCP 工具集：同一服务器工具数 > 阈值则启用子域
        if (tool.name.startsWith("mcp__")) {
            // 这里只做标记，实际子域分类在 classifyAll 统一处理
            // 先按功能分类，classifyAll 后会合并且子域化
            val text = "${tool.name} ${tool.description}".lowercase()
            for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
            for ((domain, keywords) in customKeywords) { if (keywords.any { text.contains(it) }) return domain }
            val builtin = ToolDomain.classify(tool)?.label ?: "uncategorized"
            return "mcp_raw:$builtin" // 临时标记，后续合并
        }

        val text = "${tool.name} ${tool.description}".lowercase()
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
        for ((domain, keywords) in customKeywords) { if (keywords.any { text.contains(it) }) return domain }
        return ToolDomain.classify(tool)?.label ?: "uncategorized"
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        val raw = tools.groupBy { classifyTool(it) }
        val result = mutableMapOf<String, MutableList<Tool>>()

        // 第一步：找出所有大型 MCP 工具集
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

        // 第二步：大型 MCP 工具集启用子域
        for ((serverName, serverTools) in mcpGroups) {
            if (serverTools.size >= MCP_SUBDOMAIN_THRESHOLD) {
                // 按子域分类
                val subDomains = serverTools.groupBy { classifyMcpSubdomain(it.name, it.description) }
                for ((sub, subTools) in subDomains) {
                    result["$serverName/$sub"] = subTools.toMutableList()
                }
            } else {
                // 低于阈值则合并回功能域
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
        // 子域描述
        val sub = domain.substringAfterLast("/")
        if (sub.isNotEmpty() && sub != domain) {
            return when (sub) {
                "创建" -> "创建物体、场景、配置"
                "查询" -> "查询状态、属性、参数"
                "设置" -> "修改配置、更新参数"
                "删除" -> "删除物体、清除数据"
                "计算" -> "运行模拟、执行计算"
                "数据" -> "导入导出、保存读取"
                "其他" -> "其他操作"
                else -> ToolDomain.entries.find { it.label == sub }?.triggerDescription ?: sub
            }
        }
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
        val allDomains = classified.keys.filter { it != "system" && isValidDomain(it) }.sorted()
        // 按一级场景分组
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
            appendLine("**加载方式**：`use_domain(\"场景名\")` 或 `use_domain(\"场景/子场景\")`。路径越深，加载的工具越少越精准。")
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
                val rootTools = classified.filterKeys { it == root || (it == "uncategorized").not() && it.startsWith("$root/") }.flatMap { it.value }
                val total = rootTools.size
                val subCount = subs.count { it != root && isValidDomain(it) }
                val desc = getTriggerDescription(root)

                // 找出子场景中最常用的几个用于展示
                val leafSubs = subs.filter { it != root && isValidDomain(it) }.sorted()
                val firstLeaf = leafSubs.firstOrNull()
                val sample = classified[firstLeaf]?.take(2)?.map { it.name }?.joinToString("、") ?: ""

                if (subCount > 0) {
                    appendLine("| `$root` | $desc | ${total}个工具 · ${subCount}个子场景 |")
                    if (firstLeaf != null && sample.isNotEmpty()) {
                        // 展示一个子场景示例
                    }
                    // 展示子场景
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
            appendLine("跨类别任务可多次调用 use_domain。不确定时调 `use_domain(\"帮助\")` 查看完整列表。")
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
                        // 层级加载：检查是否精确匹配子域，否则父域匹配所有子域
                        val matchKeys = if (rawName in classified) {
                            listOf(rawName)
                        } else {
                            classified.keys.filter { it.startsWith("$rawName/") || it == rawName }
                        }
                        if (matchKeys.isEmpty()) {
                            val avail = classified.keys.filter { it != "system" }.sorted()
                            listOf(UIMessagePart.Text("未知: '$rawName'。可用: ${avail.joinToString("、")}"))
                        } else {
                            val dTools = matchKeys.flatMap { classified[it].orEmpty() }
                            loadedDomains.addAll(matchKeys)
                            val names = dTools.map { it.name }
                            val hasSkill = dTools.any { it.name == "use_skill" }
                            val sn = if (hasSkill && skillListText != null) "\n\n$skillListText" else ""
                            val label = if (matchKeys.size > 1) "「$rawName」及其${matchKeys.size-1}个子域" else "「$rawName」"
                            listOf(UIMessagePart.Text("已加载$label。${names.size}个工具: ${names.joinToString("、")}。$sn"))
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

    fun classifyPreview(name: String, description: String): String {
        overrides[name]?.let { return it }
        if (name == "use_domain") return "system"
        val text = "${name} ${description}".lowercase()
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
        for ((domain, keywords) in customKeywords) { if (keywords.any { text.contains(it) }) return domain }
        return ToolDomain.entries.find { dom -> dom.matchKeywords.any { text.contains(it) } }?.label ?: "uncategorized"
    }
}
