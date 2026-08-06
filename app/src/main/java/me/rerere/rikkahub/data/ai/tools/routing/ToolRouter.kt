/**
 * 工具域路由 — 模块: A. 传输链 / tools/routing
 *
 * 职责: 域树构建/工具分类/层1概览生成/invoke_tools 元工具/按域加载工具。
 * 三位一体: UI 域管理 / list_domains / Prompt 概览 同源于本类 (每步从 settings 重建)。
 *
 * 问题定位: 工具不显示/域混乱/invoke_tools 行为异常 → 查本文件 + ToolDomain
 */
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
        "get_battery_status",
    )

    /** MCP 服务器名 → 默认域快速映射 (避免关键词误匹配) */
    private val mcpServerDomainDefaults = mapOf(
        // 爬虫/搜索类 MCP (常见误分: firecrawl/exa 曾被内置域关键词拉到编程/用户交互)
        "firecrawl" to "搜索",
        "exa" to "搜索",
        "tavily" to "搜索",
        "brave" to "搜索",
        "duckduckgo" to "搜索",
        "serper" to "搜索",
        "serpapi" to "搜索",
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

    fun classifyTool(tool: Tool): String = classifyByName(tool.name, tool.description)

    /**
     * 统一分类逻辑 (UI 与模型侧共用) — 名称+描述, 避免 UI(classifyPreview)与模型(classifyTool)分叉
     */
    fun classifyByName(name: String, description: String): String {
        // 0. invoke_tools 自身不分类
        if (name in metaToolNames) return "system"
        // 1. 手动覆盖 — 仅指向有效域，否则 fall through
        overrides[name]?.let { if (it in validDomainLabels && isValidDomain(it)) return it }
        // 2. Skill 工具归入「技能」域 (use_skill 是 skill 体系的统一入口; skill: 前缀 = skill 挂载条目)
        if (name == "use_skill" || name.startsWith("skill_") || name.startsWith("skill:")) {
            return if (isValidDomain("技能")) "技能" else "方法域"
        }
        // 3. 系统级工具 — 按名称前缀精确匹配, 优先于关键词避免误分类
        //    (如 clawhub_search 不应被 "search" 关键词拉入「搜索」域)
        if (SYSTEM_TOOL_PREFIXES.any { name.startsWith(it) }) {
            return if (isValidDomain("系统")) "系统" else "方法域"
        }
        // 3.5 Memory 工具 — 归「对话工具/记忆」域 (不受 enableMemory 开关影响分类)
        if (name == "memory_tool") {
            return if (isValidDomain("对话工具/记忆")) "对话工具/记忆" else "方法域"
        }

        // 4. MCP 工具 — 服务器名映射 → 关键词 → AI兜底
        if (name.startsWith("mcp__")) {
            val server = extractMcpServerName(name)
            mcpServerDomainDefaults[server]?.let { if (isValidDomain(it)) return it }
        }

        // 5. 关键词匹配 (自定义域 → 自定义关键词覆盖 → 内置域)
        val text = "${name} ${description}".lowercase()
        for (cd in customDomains) { if (cd.keywords.any { text.contains(it) }) return cd.name }
        for ((domain, keywords) in customKeywords) {
            if (domain in validDomainLabels && keywords.any { text.contains(it) }) return domain
        }

        // 6. 内置域关键词兜底 (根域级联过滤: 根域已删/隐藏 → 子域不可用)
        val excluded = removedBuiltinDomains + hiddenDomains
        val result = ToolDomain.entries
            .sortedByDescending { it.label.count { c -> c == '/' } }
            .firstOrNull { dom ->
                val root = dom.label.split("/").first()
                dom.label !in excluded && root !in excluded &&
                    dom.matchKeywords.any { text.contains(it) }
            }?.label
        return result ?: "方法域"
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

    /** 检查域是否有效（未被删除/隐藏） — 支持子域级删除/隐藏 (完整路径 + 根域级联) */
    private fun isValidDomain(domain: String): Boolean {
        val root = domain.split("/").first()
        // 子域级: 完整路径在 removed/hidden 中 → 不可见
        if (domain in removedBuiltinDomains || domain in hiddenDomains) return false
        // 根域级: 根被删除/隐藏 → 级联其全部子域
        return root !in removedBuiltinDomains && root !in hiddenDomains
    }

    /** 公开可见性判断 — 供 UI/管理工具使用 (与 isValidDomain 同一逻辑) */
    fun isDomainVisible(domain: String): Boolean = isValidDomain(domain)

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
    private fun domainInfo(domain: String, indent: String = ""): String {
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
        // 路径名为主键（可直接用于 invoke_tools 加载），显示名不同时附注
        val nameText = if (display == domain) "`$domain`" else "`$domain`（显示名: $display）"
        return "$indent**$nameText** — $desc$kwText"
    }

    /**
     * 域地图 — 缓存稳定版。
     *
     * 输出只依赖静态配置（域树/显示名/触发描述/触发条件），**不包含工具数**：
     * 工具数依赖运行时工具池（MCP 连接状态），嵌入 system 会导致 MCP 任何波动
     * 都改变 system 文本 → 国内模型（DeepSeek/Qwen）前缀缓存整体失效。
     * 工具数由 invoke_tools 返回（消息层，不影响缓存）。
     */
    fun buildLayer1(tools: List<Tool>): String {
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
                appendLine(domainInfo(root))
                for (sub in subs.sorted()) {
                    appendLine(domainInfo(sub, "  "))
                }
            }
            appendLine()
            appendLine("加载域后其工具保持可用，跨请求不会丢失。若任务需要多个域的工具，请一次加载齐所需域（每次加载新域会使一次请求的缓存失效，加载齐后保持稳定）。")
            appendLine()
            appendLine("调 `invoke_tools(\"域名称\")` 加载。不确定时调 `invoke_tools(\"帮助\")`。")
        }
    }

    /** 构建声明式域树: ToolDomain枚举 + customDomains, 过滤 hiddenDomains + removedBuiltinDomains (含子域级) */
    private fun buildDomainTree(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()

        // 内置域 (ToolDomain枚举)
        for (entry in ToolDomain.entries) {
            val label = entry.label
            if (!isValidDomain(label)) continue  // 根域或子域被删除/隐藏都跳过
            val parts = label.split("/")
            val root = parts.first()
            result.getOrPut(root) { mutableListOf() }
            if (parts.size > 1) result[root]!!.add(label)
        }

        // 自定义域
        for (cd in customDomains) {
            if (!isValidDomain(cd.name)) continue
            if (cd.parent != null) {
                if (isValidDomain(cd.parent)) {
                    result.getOrPut(cd.parent) { mutableListOf() }.add(cd.name)
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
        skills: List<Pair<String, String>> = emptyList(), // skill 名 to 描述 (由 invoke_tools 返回, 不进 system/tools)
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
                            put("description", "类别或子域完整路径（如 搜索/搜索引擎），显示名也可。留空或传\"帮助\"查看全部类别。")
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

                        // 显示名 → 路径名 反查: 模型可能直接复制帮助地图上的显示名调用
                        // (domainNameOverrides 配置了显示名覆盖时, 加载仍按路径名解析)
                        val resolvedName = router.domainNameOverrides.entries
                            .firstOrNull { it.value == rawName }
                            ?.key ?: rawName

                        // 用声明式域树检查域名是否存在 + 获取子域列表
                        val domainExists = treeNodes.containsKey(resolvedName) ||
                            treeNodes.values.flatten().any { it == resolvedName }

                        if (!domainExists) {
                            val avail = treeNodes.keys.toList()
                            listOf(UIMessagePart.Text("未知: '$rawName'。可用顶级域: ${avail.joinToString("、")}。调 `invoke_tools(\"帮助\")` 查看详情。"))
                        } else {
                            // 已加载也返回最新完整摘要 (loadedDomains.add 幂等, tools 不变 → 缓存稳定;
                            //  挂载/工具变更后 invoke_tools 同域即可刷新, 无需新会话)
                            loadedDomains.add(resolvedName)

                            // 子域列表从声明式域树获取
                            val childKeys = when {
                                treeNodes.containsKey(resolvedName) -> treeNodes[resolvedName]!!
                                else -> treeNodes.entries
                                    .find { it.value.contains(resolvedName) }
                                    ?.let { (parent, subs) ->
                                        subs.filter { it.startsWith("$resolvedName/") }
                                    } ?: emptyList()
                            }

                            // 工具从分类结果获取
                            val directTools = classified[resolvedName].orEmpty()
                            if (childKeys.isNotEmpty()) {
                                // 有子域: 显示子域列表
                                val summary = buildString {
                                    val subInfo = buildString {
                                        for (ck in childKeys.sorted()) {
                                            val short = ck.substringAfterLast("/")
                                            val display = router.displayName(ck)
                                            val nameText = if (display == ck) ck else "$ck（$display）"
                                            val desc = router.getTriggerDescription(ck)
                                            val keywords = router.getKeywords(ck)
                                            val kwText = if (keywords.isEmpty()) "" else {
                                                val shown = keywords.take(8)
                                                val rest = keywords.size - shown.size
                                                val suffix = if (rest > 0) " 等${keywords.size}个" else ""
                                                " [触发: ${shown.joinToString("、")}$suffix]"
                                            }
                                            appendLine("- **`$nameText`** ($short): $desc$kwText")
                                        }
                                    }
                                    if (directTools.isNotEmpty()) {
                                        appendLine("「$resolvedName」含${childKeys.size}个子域及直接工具:")
                                        appendLine()
                                        append(subInfo)
                                        appendLine()
                                        appendLine("直接工具：")
                                        for (t in directTools.sortedBy { it.name }.take(8)) {
                                            appendLine("- `${t.name}`: ${t.description.take(60).replace("\\n", " ")}")
                                        }
                                    } else {
                                        appendLine("「$resolvedName」含${childKeys.size}个子域：")
                                        appendLine()
                                        append(subInfo)
                                    }
                                    appendLine()
                                    appendLine("子域标注了触发描述与触发条件(关键词)，据此判断是否加载。调 `invoke_tools(\\\"子域完整路径\\\")` 加载具体工具。")
                                }
                                listOf(UIMessagePart.Text(summary))
                            } else {
                                // 叶子域: 直接返回工具列表
                                val parentRoot = resolvedName.split("/").first()
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
                                val rootOnly = if (resolvedName == parentRoot) allInParent else directTools

                                val summary = buildString {
                                    if (rootOnly.isEmpty()) {
                                        appendLine("已加载「$resolvedName」，但该域当前无可用工具。")
                                        appendLine("可尝试 `invoke_tools(\"帮助\")` 查看其他域。")
                                    } else {
                                        appendLine("已加载「$resolvedName」。可用工具：")
                                        for (t in rootOnly.sortedBy { it.name }) {
                                            val desc = t.description.take(80).replace("\n", " ")
                                            appendLine("- `${t.name}`: $desc")
                                        }
                                        // 技能域: 附加已启用 skill 列表 (由 invoke_tools 返回, 模型通过 use_skill 调用)
                                        if (rootOnly.any { it.name == "use_skill" }) {
                                            appendLine()
                                            appendLine("可用 Skills（通过 `use_skill` 加载其指令后调用）:")
                                            if (skills.isEmpty()) {
                                                appendLine("  （当前没有已启用的 skill）")
                                            } else {
                                                for ((sname, sdesc) in skills) {
                                                    appendLine("- `$sname`: ${sdesc.take(120).replace("\n", " ")}")
                                                }
                                            }
                                            appendLine()
                                            appendLine("调 `use_skill(name=\"skill名\")` 加载 skill 的 SKILL.md 指令。")
                                        }
                                    }
                                    // 无条件输出 skill 挂载 (修复: 纯技能域(无 MCP 工具)也渲染挂载的 Skills)
                                    val mountedSkills = overrides.entries
                                        .filter { it.key.startsWith("skill:") && it.value == resolvedName }
                                        .map { it.key.removePrefix("skill:") }
                                    if (mountedSkills.isNotEmpty()) {
                                        appendLine()
                                        appendLine("挂载到本域的 Skills（通过 `use_skill` 加载其指令后调用）:")
                                        for (sname in mountedSkills.sorted()) {
                                            val sdesc = skills.find { it.first == sname }?.second ?: ""
                                            appendLine("- `$sname`: ${sdesc.take(100).replace("\n", " ")}")
                                        }
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
        val treeNodes = buildDomainTree()
        return buildString {
            appendLine("全部类别:")
            for ((root, subs) in treeNodes) {
                appendLine(domainInfo(root))
                for (sub in subs.sorted()) {
                    appendLine(domainInfo(sub, "  "))
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
    fun classifyPreview(name: String, description: String): String = classifyByName(name, description)
}
