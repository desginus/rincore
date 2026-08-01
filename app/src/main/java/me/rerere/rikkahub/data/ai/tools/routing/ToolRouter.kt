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

    /** invoke_tools 自身不参与分类 */
    private val metaToolNames = setOf("invoke_tools")

    /** 单个域注入的最大关键词数量, 超出显示 "等N个" */
    private val MAX_KEYWORDS_INJECT = 8

    /** 系统级工具名称前缀 — 精确匹配, 避免被关键词误分类 (如 clawhub_search → 系统, 而非 搜索) */
    private val SYSTEM_TOOL_PREFIXES = listOf(
        "manage_domain", "list_domains", "move_tool_to_domain",
        "mcp_connect", "clawhub_", "plugin_install", "skills_lock", "list_ecosystem_tools",
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
        // 0. invoke_tools 自身不分类
        if (tool.name in metaToolNames) return "system"
        // 1. 手动覆盖 — 仅指向有效域，否则 fall through
        overrides[tool.name]?.let { if (it in validDomainLabels && isValidDomain(it)) return it }
        // 2. Skill 工具归入「技能」域
        if (tool.name.startsWith("skill_")) {
            return if (isValidDomain("技能")) "技能" else "方法域"
        }
        // 3. 系统级工具 — 按名称前缀精确匹配, 优先于关键词避免误分类
        //    (如 clawhub_search 不应被 "search" 关键词拉入「搜索」域)
        if (SYSTEM_TOOL_PREFIXES.any { tool.name.startsWith(it) }) {
            return if (isValidDomain("系统")) "系统" else "方法域"
        }

        // 4. MCP 工具 — 服务器名映射 → 关键词 → AI兜底
        if (tool.name.startsWith("mcp__")) {
            val server = extractMcpServerName(tool.name)
            mcpServerDomainDefaults[server]?.let { if (isValidDomain(it)) return it }
        }

        // 5. 关键词匹配 (自定义域 → 自定义关键词覆盖 → 内置域)
        val text = "${tool.name} ${tool.description}".lowercase()
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
        for ((domain, keywords) in customKeywords) {
            if (domain in validDomainLabels && keywords.any { text.contains(it) }) return domain
        }

        // 6. 内置域关键词兜底
        return ToolDomain.classify(tool, removedBuiltinDomains, hiddenDomains)?.label ?: "方法域"
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        return tools.groupBy { classifyTool(it) }
            .filterValues { it.isNotEmpty() }
    }

    private fun extractMcpServerName(toolName: String): String {
        val parts = toolName.removePrefix("mcp__").split("__")
        return if (parts.size >= 1) parts[0].lowercase() else "unknown"
    }

    fun displayName(domain: String): String = domainNameOverrides[domain] ?: domain

    /** 检查域是否有效（未被删除/隐藏） — 与 buildDomainTree 过滤逻辑一致 */
    private fun isValidDomain(domain: String): Boolean {
        val root = domain.split("/").first()
        return root !in removedBuiltinDomains && root !in hiddenDomains
    }

    fun getTriggerDescription(domain: String): String {
        customDescriptions[domain]?.let { return it }
        ToolDomain.entries.find { it.label == domain }?.triggerDescription?.let { return it }
        customDomains.find { it.name == domain }?.description?.let { return it }
        return domain.substringAfterLast("/")
    }

    fun getKeywords(domain: String): List<String> {
        customKeywords[domain]?.let { return it }
        customDomains.find { it.name == domain }?.keywords?.let { return it }
        return ToolDomain.entries.find { it.label == domain }?.matchKeywords ?: emptyList()
    }

    /**
     * 域基本信息注入格式：显示名称 + 触发描述 + 触发条件(关键词)
     * 关键词超过 MAX_KEYWORDS_INJECT 时取前几个 + 计数，避免膨胀
     */
    private fun domainInfo(domain: String, toolCount: Int? = null, indent: String = ""): String {
        val display = displayName(domain)
        val desc = getTriggerDescription(domain)
        val keywords = getKeywords(domain)
        val kwText = if (keywords.isEmpty()) {
            ""
        } else {
            val shown = keywords.take(MAX_KEYWORDS_INJECT)
            val rest = keywords.size - shown.size
            val suffix = if (rest > 0) " 等${keywords.size}个" else ""
            " [触发: ${shown.joinToString("、")}$suffix]"
        }
        val countText = toolCount?.let { " · ${it}工具" } ?: ""
        return "$indent**`$display`** — $desc$kwText$countText"
    }

    fun buildLayer1(tools: List<Tool>): String {
        val classified = classifyAll(tools)
        val treeNodes = buildDomainTree()

        return buildString {
            appendLine("## 工具调度")
            appendLine()
            appendLine("你拥有一个工具总域 `工具`，按功能场景树状组织。每个域含：显示名称、触发描述、触发条件。")
            appendLine()
            appendLine("**加载**：`invoke_tools(\"场景名\")` 查看子域；`invoke_tools(\"场景/子域\")` 加载工具。调 `invoke_tools(\"帮助\")` 查看全部。")
            appendLine()
            appendLine("### 可用场景域")
            appendLine()
            for ((root, subs) in treeNodes) {
                val rootCount = classified[root]?.size ?: 0
                // 全空域(根+所有子域都0工具)也注入，标注 0 工具，避免模型按 UI 猜测
                appendLine(domainInfo(root, rootCount))
                for (sub in subs.sorted()) {
                    val subCount = classified[sub]?.size ?: 0
                    appendLine(domainInfo(sub, subCount, "  "))
                }
            }
            appendLine()
            appendLine("工具数标注为 0 的域为空壳，无需加载。调 `invoke_tools(\"域名称\")` 加载。不确定时调 `invoke_tools(\"帮助\")`。")
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
                                            val keywords = router.getKeywords(ck)
                                            val kwText = if (keywords.isEmpty()) "" else {
                                                val shown = keywords.take(8)
                                                val rest = keywords.size - shown.size
                                                val suffix = if (rest > 0) " 等${keywords.size}个" else ""
                                                " [触发: ${shown.joinToString("、")}$suffix]"
                                            }
                                            appendLine("- **`$ck`** ($short): $desc · ${count}个工具$kwText")
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
                                    appendLine("子域标注了触发描述与触发条件(关键词)，据此判断是否加载。调 `invoke_tools(\"子域完整路径\")` 加载具体工具。")
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
                appendLine(domainInfo(root, rootCount))
                for (sub in subs.sorted()) {
                    val subCount = classified[sub]?.size ?: 0
                    appendLine(domainInfo(sub, subCount, "  "))
                }
            }
            appendLine()
            appendLine("调 `invoke_tools(\"域名称\")` 加载。")
        }
    }

    /**
     * 获取指定域下的工具 — 使用 classifyAll 确保与 createInvokeToolsTool 一致。
     */
    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        return classifyAll(allTools)[domainName].orEmpty().distinctBy { it.name }
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

        // 0. invoke_tools 自身不分类
        if (name in metaToolNames) return "system"
        // 1. 用户手动覆盖（校验合法性）
        overrides[name]?.let { if (it in valid) return it }
        // 2. Skill 工具归入「技能」域
        if (name.startsWith("skill_")) return if (isValidDomain("技能")) "技能" else "方法域"
        // 3. 系统级工具 — 按名称前缀精确匹配
        if (SYSTEM_TOOL_PREFIXES.any { name.startsWith(it) }) {
            return if (isValidDomain("系统")) "系统" else "方法域"
        }

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

        return result ?: "方法域"
    }
}
