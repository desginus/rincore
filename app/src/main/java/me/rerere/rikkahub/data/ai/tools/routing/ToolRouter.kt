/**
 * 工具域路由 — 模块: A. 传输链 / tools/routing
 *
 * 职责: 域模型构建/工具分类/层1概览生成/invoke_tools 元工具/按域加载工具。
 * 三位一体: UI 域管理 / list_domains / Prompt 概览 同源于本类 (每步从 settings 重建)。
 *
 * 架构 (v3.5.39 重写 — 告别补丁石山):
 * 1. 单一事实源: domainSource() 产出全部域 (内置枚举 + 规范化自定义域 + 技能子域),
 *    所有视图 (layer1/help/invoke_tools/list_domains/UI/搜索) 一律从它派生。
 * 2. 域标识唯一化: normalizedFullPath() — name 永远取最后段, 杜绝双重叠加;
 *    旧数据 (name 含父路径) 由 SettingsStore 迁移拆分。
 * 3. 统一寻址: resolveDomain() 兼容完整路径/短名/显示名/双叠路径 — 寻址不断链。
 * 4. 防幽灵: 子域父级必须真实存在于 domainSource, 不存在则丢弃。
 * 5. 缓存安全: layer1 只依赖静态配置 (配置决定, 无运行时数据);
 *    tools 数组稳定 (invoke_tools 子树一次性加载)。
 */
package me.rerere.rikkahub.data.ai.tools.routing

import android.util.Log
import me.rerere.rikkahub.data.ai.tools.sanitizeSkillToolName
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

    // ═══════════ 1. 域模型 — 单一事实源 ═══════════

    /** 域描述 — path 为全链路唯一域标识 (规范化完整路径, 如 搜索/搜索引擎) */
    data class DomainInfo(
        val path: String,
        val parent: String?,
        val displayName: String,
        val description: String,
        val keywords: List<String>,
        val builtin: Boolean,
    ) {
        val shortName: String get() = path.substringAfterLast("/")
    }

    /** 任意输入 → 规范化域路径: 完整路径 / 短名 / 显示名 / 双叠路径 全部兼容 */
    fun resolveDomain(input: String): String? {
        if (input.isBlank()) return null
        val all = domainSource()
        // 1. 精确完整路径
        all.firstOrNull { it.path == input }?.let { return it.path }
        // 2. 短名 (最后一段)
        all.firstOrNull { it.shortName == input }?.let { return it.path }
        // 3. 显示名覆盖
        all.firstOrNull { it.displayName == input }?.let { return it.path }
        // 4. 双叠路径规范化 (历史遗留: 搜索/搜索/搜索引擎 → 搜索/搜索引擎)
        all.firstOrNull { d ->
            d.path.split("/").lastOrNull() == input.substringAfterLast("/") &&
                input.split("/").distinct().size == d.path.split("/").distinct().size
        }?.let { return it.path }
        return null
    }

    /** 全部可见域 — 单一事实源 (内置枚举 + 规范化自定义 + 技能子域派生) */
    fun domainSource(): List<DomainInfo> {
        val result = mutableListOf<DomainInfo>()

        // 内置域 (ToolDomain 枚举)
        for (td in ToolDomain.entries) {
            if (!isValidDomain(td.label)) continue
            result += DomainInfo(
                path = td.label,
                parent = td.parent,
                displayName = domainNameOverrides[td.label] ?: td.label,
                description = td.triggerDescription,
                keywords = td.matchKeywords,
                builtin = true,
            )
        }

        // 自定义域已移除 (用户决策 v3.5.40) — 不再进入域体系。
        // 旧配置数据忽略 (无害残留), 未分类工具统一落入「未分类」父域。

        // 技能子域 — 由工具名结构化派生 (与 classifyByName 同源)
        // 由 buildDomainTree(tools) 注入 (需要工具池), 此处仅确保技能根域存在
        return result.sortedBy { it.path }
    }

    /** 域树 (父 → 子列表) — 视图层唯一来源, 全部视图从这里派生 */
    private fun buildDomainTree(tools: List<Tool>? = null): Map<String, List<String>> {
        val infos = domainSource().toMutableList()

        // 技能子域: 从工具名派生 (skill__名 → 技能/名)
        if (tools != null) {
            val skillNames = tools.mapNotNull { t ->
                when {
                    t.name.startsWith("skill__") -> t.name.removePrefix("skill__")
                    t.name.startsWith("skill:") -> t.name.removePrefix("skill:")
                    else -> null
                }
            }.filter { it.isNotBlank() }.distinct().sorted()
            for (s in skillNames) {
                val sub = "技能/$s"
                if (isValidDomain(sub) && infos.none { it.path == sub }) {
                    infos += DomainInfo(
                        path = sub,
                        parent = "技能",
                        displayName = s,
                        description = "Skill 能力模块",
                        keywords = emptyList(),
                        builtin = false,
                    )
                }
            }
        }

        val result = mutableMapOf<String, MutableList<String>>()
        val knownPaths = infos.map { it.path }.toSet()
        for (info in infos) {
            if (info.parent == null) {
                result.getOrPut(info.path) { mutableListOf() }
            } else if (info.parent in knownPaths) {
                val subs = result.getOrPut(info.parent) { mutableListOf() }
                if (info.path !in subs) subs.add(info.path)
            }
        }
        return result.toSortedMap()
    }

    /** 合法域标签集合 — override 校验用 */
    val validDomainLabels: Set<String>
        get() = domainSource().map { it.path }
            .filter { isValidDomain(it) }
            .toSet()

    // ═══════════ 2. 分类 ═══════════

    private val metaToolNames = setOf("invoke_tools", "search_domains")

    /** 单个域注入的最大关键词数量, 超出显示 "等N个" */
    private val MAX_KEYWORDS_INJECT = 8

    /** 系统级工具名称前缀 — 精确匹配, 避免被关键词误分类 */
    private val SYSTEM_TOOL_PREFIXES = listOf(
        "manage_domain", "list_domains", "move_tool_to_domain",
        "mcp_connect", "clawhub_", "plugin_install", "skills_lock", "list_ecosystem_tools",
        "get_battery_status",
    )

    /** MCP 服务器名 → 默认域快速映射 (避免关键词误匹配) */
    private val mcpServerDomainDefaults = mapOf(
        "firecrawl" to "搜索",
        "exa" to "搜索",
        "tavily" to "搜索",
        "brave" to "搜索",
        "duckduckgo" to "搜索",
        "serper" to "搜索",
        "serpapi" to "搜索",
        "physicsengine" to "物理引擎",
        "charting" to "生成部署/图表",
        "qrcode" to "生成部署/二维码",
        "edgeone" to "生成部署/网页部署",
        "webpagegeneration" to "生成部署/网页部署",
        "productinquiry" to "搜索/商品搜索",
        "searchoptimization" to "搜索/搜索引擎",
        "wikipedia" to "搜索/搜索引擎",
        "trustedsearch" to "搜索/政策搜索",
        "thinkingmethodology" to "辅助推理/方法论",
        "sequentialthinking" to "辅助推理/序列思考",
    )

    fun classifyTool(tool: Tool): String = classifyByName(tool.name, tool.description)

    /**
     * 统一分类逻辑 (UI 与模型侧共用) — 名称结构化 + 手动覆盖 + 关键词兜底。
     * 分类结果一律为规范化完整路径 (与 domainSource 同源)。
     */
    fun classifyByName(name: String, description: String): String {
        // 0. 元工具不分类
        if (name in metaToolNames) return "system"

        val valid = validDomainLabels

        // 1. 手动覆盖 — 指向有效域 (含技能子域: root 有效即放行)
        overrides[name]?.let {
            val ok = it in valid || (it.startsWith("技能/") && isValidDomain("技能"))
            if (ok && isValidDomain(it)) return it
        }

        // 2. Skill 工具 — 名称结构化: 第一字段类别(skill), 第二字段分类字段(skill 名)
        if (name.startsWith("skill__") || name.startsWith("skill_") || name.startsWith("skill:")) {
            val skillName = name.removePrefix("skill__").removePrefix("skill_").removePrefix("skill:")
            val sub = "技能/$skillName"
            if (skillName.isNotBlank() && isValidDomain(sub)) return sub
            return if (isValidDomain("技能")) "技能" else "方法域"
        }
        if (name == "use_skill") {
            return if (isValidDomain("技能")) "技能" else "方法域"
        }

        // 3. 系统级工具 — 前缀精确匹配
        if (SYSTEM_TOOL_PREFIXES.any { name.startsWith(it) }) {
            return if (isValidDomain("系统")) "系统" else "方法域"
        }

        // 3.5 Memory 工具
        if (name == "memory_tool") {
            return if (isValidDomain("对话工具/记忆")) "对话工具/记忆" else "方法域"
        }

        // 4. MCP 工具 — 服务器名映射 → 关键词兜底 (名称分类作辅助)
        //    映射域必须有效 (未删/未隐藏), 否则落入关键词/未分类 — 工具不丢失
        if (name.startsWith("mcp__")) {
            val server = extractMcpServerName(name)
            mcpServerDomainDefaults[server]?.let { if (isValidDomain(it)) return it }
        }

        // 5. 关键词匹配 (自定义域已移除 — 用户决策 v3.5.40; 仅内置域关键词)
        val text = "${name} ${description}".lowercase()

        // 6. 内置域关键词兜底 (根域级联过滤)
        val excluded = removedBuiltinDomains + hiddenDomains
        val result = ToolDomain.entries
            .sortedByDescending { it.label.count { c -> c == '/' } }
            .firstOrNull { dom ->
                val root = dom.label.split("/").first()
                dom.label !in excluded && root !in excluded &&
                    dom.matchKeywords.any { text.contains(it) }
            }?.label
        // 未成功分类的工具 → 统一落入「未分类」父域 (用户决策 v3.5.40)
        return result ?: if (isValidDomain("未分类")) "未分类" else "方法域"
    }

    fun classifyAll(tools: List<Tool>): Map<String, List<Tool>> {
        return tools.groupBy { classifyTool(it) }
            .filterValues { it.isNotEmpty() }
    }

    private fun extractMcpServerName(toolName: String): String {
        val parts = toolName.removePrefix("mcp__").split("__")
        return if (parts.isNotEmpty()) parts[0].lowercase() else "unknown"
    }

    fun displayName(domain: String): String = domainNameOverrides[domain] ?: domain.substringAfterLast("/")

    /** 检查域是否有效（未被删除/隐藏）— 支持子域级删除/隐藏 (完整路径 + 根域级联) */
    private fun isValidDomain(domain: String): Boolean {
        val root = domain.split("/").first()
        if (domain in removedBuiltinDomains || domain in hiddenDomains) return false
        return root !in removedBuiltinDomains && root !in hiddenDomains
    }

    /** 公开可见性判断 */
    fun isDomainVisible(domain: String): Boolean = isValidDomain(domain)

    fun getTriggerDescription(domain: String): String {
        customDescriptions[domain]?.let { return it }
        ToolDomain.entries.find { it.label == domain }?.triggerDescription?.let { return it }
        customDomains.find { it.normalizedFullPath() == domain }?.description?.let { return it }
        return domain.substringAfterLast("/")
    }

    fun getKeywords(domain: String): List<String> {
        customKeywords[domain]?.let { return it }
        customDomains.find { it.normalizedFullPath() == domain }?.keywords?.let { return it }
        return ToolDomain.entries.find { it.label == domain }?.matchKeywords ?: emptyList()
    }

    // ═══════════ 3. 统一视图 — 全部视图 (layer1/help/invoke_tools/list_domains/UI) 唯一数据源 ═══════════

    /** 统一域视图 — 工具分类结果 + 域树 单一组合, 所有消费者只认这一个结构 */
    data class UnifiedDomainView(
        val tree: Map<String, List<String>>,   // 根 → 可见子域 (空壳已过滤)
        val counts: Map<String, Int>,          // 域路径 → 工具数
        val classified: Map<String, List<Tool>>, // 域路径 → 工具列表
    )

    /** 构建统一视图 — 唯一实现, 四处视图 (系统提示/Invoke Tools/List Domains/UI) 全部消费 */
    fun unifiedDomainView(tools: List<Tool>): UnifiedDomainView {
        val classified = classifyAll(tools)
        val counts = classified.mapValues { it.value.size }
        val tree = buildDomainTree(tools).toMutableMap()
        // 容错 (v3.5.45): classified 的域不在树 → 补入 (分类结果绝不丢失)。
        // 此前缺失域的工具直接消失 (如 133 个工具丢失)。
        val treePaths = tree.keys.toMutableSet().apply { addAll(tree.values.flatten()) }
        for (domain in classified.keys) {
            if (domain !in treePaths) {
                val root = domain.split("/").first()
                if (domain.contains("/")) {
                    if (root !in tree) tree[root] = mutableListOf()
                    val subs = tree[root]!!
                    if (domain !in subs) subs.add(domain)
                } else {
                    if (domain !in tree) tree[domain] = mutableListOf()
                }
            }
        }
        // 空壳过滤: 子域无工具不显示; 根域无工具且无非空子域不显示
        val filteredTree = tree.mapValues { (_, subs) ->
            subs.filter { (counts[it] ?: 0) > 0 }
        }.filter { (root, subs) ->
            (counts[root] ?: 0) > 0 || subs.isNotEmpty()
        }.toSortedMap()
        return UnifiedDomainView(filteredTree, counts, classified)
    }


    /** 域基本信息注入格式: 显示名 + 触发描述 + 触发条件(关键词) */
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
        val nameText = if (display == domain.substringAfterLast("/")) "`$domain`" else "`$domain`（显示名: $display）"
        return "$indent**$nameText** — $desc$kwText"
    }

    /**
     * 层1概览 — 缓存稳定版。输出只依赖静态配置 (域树/显示名/触发描述/触发条件),
     * 不含任何运行时数据 (工具数/状态 → invoke_tools 帮助, 消息层)。
     */
    fun buildLayer1(tools: List<Tool>): String {
        // 统一视图 — 与 Invoke Tools/List Domains/UI 完全同源
        val view = unifiedDomainView(tools)

        return buildString {
            appendLine("## 工具调度")
            appendLine()
            appendLine("你拥有一个工具总域 `工具`，按功能场景树状组织。每个域含：显示名称、触发描述、触发条件。")
            appendLine()
            appendLine("**使用**：所有工具已直接可用，无需加载。`invoke_tools(\"场景名\")` 查看子域与工具详情；`search_domains(关键词)` 反查工具位置；`invoke_tools(\"帮助\")` 查看全部。")
            appendLine()
            appendLine("### 可用场景域")
            appendLine()
            for ((root, subs) in view.tree) {
                appendLine(domainInfo(root))
                for (sub in subs) {
                    appendLine(domainInfo(sub, "  "))
                }
            }
            appendLine()
            appendLine("不确定工具在哪个域时，用 `search_domains(关键词)` 反查，或调 `invoke_tools(\"帮助\")` 查看全部。")
        }
    }

    fun createInvokeToolsTool(
        allTools: List<Tool>,
        loadedDomains: MutableSet<String>,
        skills: List<Pair<String, String>> = emptyList(), // skill 名 to 描述 (由 invoke_tools 返回, 不进 system/tools)
    ): Tool {
        val router = this
        return Tool(
            name = "invoke_tools",
            description = "按类别查看工具与子域。有子域时返回子域列表(需再调用查看子域)，无子域时直接返回工具列表。所有工具均可直接调用，无需加载。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "类别或子域完整路径（如 搜索/搜索引擎），显示名或短名也可。留空或传\"帮助\"查看全部类别。")
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
                        // 统一视图 — 与 layer1/List Domains/UI 完全同源
                        val view = router.unifiedDomainView(allTools)
                        val classified = view.classified
                        val treeNodes = view.tree

                        // 统一寻址: 完整路径/短名/显示名/双叠路径 → 规范化路径
                        val finalName = router.resolveDomain(rawName) ?: rawName

                        // 用声明式域树检查域名是否存在
                        val domainExists = treeNodes.containsKey(finalName) ||
                            treeNodes.values.flatten().any { it == finalName }

                        if (!domainExists) {
                            val avail = treeNodes.keys.toList()
                            listOf(UIMessagePart.Text("未知: '$rawName'。可用顶级域: ${avail.joinToString("、")}。调 `invoke_tools(\"帮助\")` 查看详情。"))
                        } else {
                            // 已加载也返回最新完整摘要 (loadedDomains.add 幂等, tools 不变 → 缓存稳定)
                            loadedDomains.add(finalName)

                            // 子域列表从声明式域树获取
                            val childKeys = when {
                                treeNodes.containsKey(finalName) -> treeNodes[finalName]!!
                                else -> treeNodes.entries
                                    .find { it.value.contains(finalName) }
                                    ?.let { (_, subs) ->
                                        subs.filter { it.startsWith("$finalName/") }
                                    } ?: emptyList()
                            }

                            // 工具从分类结果获取
                            val directTools = classified[finalName].orEmpty()
                            if (childKeys.isNotEmpty()) {
                                // 深度缓存优化: 有子域时一次性加载父域 + 全部子域工具。
                                // 此前模型需逐个子域 invoke_tools → tools 数组每轮变化 →
                                // 请求体前缀持续断裂 → 前期缓存阶梯化。一次到位后整棵子树
                                // 工具直接可用, 后续轮次 tools 数组稳定。
                                loadedDomains.add(finalName)
                                childKeys.forEach { loadedDomains.add(it) }
                                // 有子域: 显示子域列表 (已全部自动加载)
                                val summary = buildString {
                                    val subInfo = buildString {
                                        for (ck in childKeys.sorted()) {
                                            val short = ck.substringAfterLast("/")
                                            val display = router.displayName(ck)
                                            val nameText = if (display == short) ck else "$ck（$display）"
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
                                        appendLine("「$finalName」含${childKeys.size}个子域及直接工具（已全部加载，可直接调用）:")
                                        appendLine()
                                        append(subInfo)
                                        appendLine()
                                        appendLine("直接工具：")
                                        for (t in directTools.sortedBy { it.name }.take(8)) {
                                            appendLine("- `${t.name}`: ${t.description.take(60).replace("\\n", " ")}")
                                        }
                                    } else {
                                        appendLine("「$finalName」含${childKeys.size}个子域（已全部加载，可直接调用）：")
                                        appendLine()
                                        append(subInfo)
                                    }
                                    appendLine()
                                    appendLine("子域标注了触发描述与触发条件(关键词)，据此判断工具位置。所有工具均可直接调用。")
                                }
                                listOf(UIMessagePart.Text(summary))
                            } else {
                                // 叶子域: 直接返回工具列表
                                val parentRoot = finalName.split("/").first()
                                val allInParent = classified.entries
                                    .filter { it.key == parentRoot || it.key.startsWith("$parentRoot/") }
                                    .flatMap { it.value }
                                    .toSet()
                                    .let { parentTools ->
                                        val subDomainsInParent = treeNodes[parentRoot] ?: emptyList()
                                        val subTools = subDomainsInParent.flatMap { classified[it].orEmpty() }.toSet()
                                        parentTools - subTools
                                    }
                                val rootOnly = if (finalName == parentRoot) allInParent else directTools

                                val summary = buildString {
                                    if (rootOnly.isEmpty()) {
                                        appendLine("「$finalName」当前无可用工具。")
                                        appendLine("可尝试 `invoke_tools(\"帮助\")` 查看其他域。")
                                    } else {
                                        appendLine("「$finalName」可用工具（均可直接调用）：")
                                        for (t in rootOnly.sortedBy { it.name }) {
                                            val desc = t.description.take(80).replace("\n", " ")
                                            appendLine("- `${t.name}`: $desc")
                                        }
                                        // 技能域: 附加已启用 skill 列表 (skill__<name> 工具已直接可用)
                                        if (rootOnly.any { it.name == "use_skill" }) {
                                            appendLine()
                                            appendLine("可用 Skills（`skill__<name>` 工具已直接可用，无需加载）:")
                                            if (skills.isEmpty()) {
                                                appendLine("  （当前没有已启用的 skill）")
                                            } else {
                                                for ((sname, sdesc) in skills) {
                                                    appendLine("- `skill_${sanitizeSkillToolName(sname)}`: ${sdesc.take(120).replace("\n", " ")}")
                                                }
                                            }
                                        }
                                    }
                                    // 无条件输出 skill 挂载 (修复: 纯技能域(无 MCP 工具)也渲染挂载的 Skills)
                                    val mountedSkills = overrides.entries
                                        .filter { it.key.startsWith("skill:") && it.value == finalName }
                                        .map { it.key.removePrefix("skill:") }
                                    if (mountedSkills.isNotEmpty()) {
                                        appendLine()
                                        appendLine("挂载到本域的 Skills（`skill__<name>` 工具已直接可用）:")
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

    fun buildHelpText(tools: List<Tool>): String {
        // 统一视图 — 与 layer1/Invoke Tools/UI 完全同源
        val view = unifiedDomainView(tools)
        return buildString {
            appendLine("工具池共 ${tools.size} 个工具（${view.classified.size} 个域）：")
            for ((root, subs) in view.tree) {
                appendLine(domainInfo(root) + countSuffix(root, view.counts))
                for (sub in subs) {
                    appendLine(domainInfo(sub, "  ") + countSuffix(sub, view.counts))
                }
            }
            appendLine()
            appendLine("调 `invoke_tools(\"域名称\")` 加载该域工具；工具加载后直接调用，跨轮保持。")
        }
    }

    private fun countSuffix(domain: String, counts: Map<String, Int>): String {
        val n = counts[domain] ?: 0
        return if (n > 0) " [${n}个工具]" else ""
    }

    /**
     * 获取指定域下的工具 — 使用 classifyAll 确保与 createInvokeToolsTool 一致。
     * 短名/双叠路径兼容 (统一寻址)。
     */
    fun getDomainTools(domainName: String, allTools: List<Tool>): List<Tool> {
        val classified = classifyAll(allTools)
        val resolved = resolveDomain(domainName) ?: domainName
        return classified[resolved].orEmpty().distinctBy { it.name }
    }

    /**
     * UI 预览分类——用于域管理页面展示。与 classifyTool 一致 (统一分类逻辑)。
     */
    fun classifyPreview(name: String, description: String): String = classifyByName(name, description)
}

/** 自定义域完整路径 — 全模块统一数据源 (UI/系统提示/Invoke Tools/List Domains 同源)。
 *  规范化: name 永远取最后一段 (短名) — 旧数据可能含完整父路径 (create 允许传
 *  '搜索/自定义子域'), 避免 parent + name 双重叠加。 */
internal fun CustomDomain.normalizedFullPath(): String {
    val namePart = name.substringAfterLast("/")
    return parent?.let { "$it/$namePart" } ?: name
}
