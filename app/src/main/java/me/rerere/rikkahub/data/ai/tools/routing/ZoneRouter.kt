/**
 * 工具矩阵路由 — 模块: A. 传输链 / tools/routing
 *
 * 唯一职责：把「工具区声明 + 工具池」这一对输入，变成全部消费方共用的**一个**视图。
 *
 * 架构铁律（本文件即全部实现，没有第二套逻辑分支）:
 *  1. **单一事实源**：`zoneMap()` 是唯一视图生产者。工具矩阵地图（系统提示）/ 帮助 /
 *     invoke_tools / 搜索 / UI / 对照页 —— 全部从它派生。
 *  2. **区身份 = id（完整路径）**。没有 parent 字段、没有 normalizedFullPath、
 *     没有「短名/全路径/显示名/双叠路径」四套归一化 —— 寻址只有一处 `resolveCandidates()`。
 *  3. **声明即存在**：工具区只要被声明就出现在视图里（0 工具也出现），绝不蒸发。
 *  4. **不变量**：Σ(各区直接工具数) + 顶层直连工具数 == 工具池总数。
 *     兜底区 `未分类` 保证任何工具都有归属，快照可自校验（见 [selfCheck]）。
 *  5. **两个入口**：`invoke_tools`（加载 / 反查）与 `manage_zone`（管理）。其余一律不做。
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

class ZoneRouter(
    /** 全部工具区声明（顺序 = 呈现顺序；用户自建区追加在尾部） */
    val zones: List<ToolZone> = emptyList(),
    /** 工具名 / `skill:<名>` → 工具区 id 的手动归属（空/失效 = 自动归类） */
    val links: Map<String, String> = emptyMap(),
    /** 隐藏的工具区：不出现在「每轮必发」的地图里，但帮助/搜索仍可查到、仍可加载 */
    val hiddenZones: Set<String> = emptySet(),
    /** 顶层直连工具集合（始终注入请求体，不参与工具区归类与统计） */
    val topLevelTools: Set<String> = emptySet(),
) {
    // ═══════════ 1. 区集合 — 声明即存在 ═══════════

    /** 去重 + 兜底区保证。兜底区永远在，保证「任何工具都有归属」。 */
    private val declared: List<ToolZone> = run {
        val clean = zones.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (clean.any { it.id == FALLBACK_ZONE_ID }) clean
        else clean + DEFAULT_TOOL_ZONES.first { it.id == FALLBACK_ZONE_ID }
    }

    private val byId: Map<String, ToolZone> = declared.associateBy { it.id }

    fun zone(id: String): ToolZone? = byId[id]

    /** 全部区 id */
    val zoneIds: Set<String> get() = byId.keys

    /** 出现在「每轮必发的地图」里的区 id（隐藏区不在其中） */
    val visibleZoneIds: Set<String> get() = byId.keys.filter { it !in hiddenZones }.toSet()

    /**
     * 统一寻址（严格唯一）：精确 id → 精确显示名 → 精确短名 → 末段唯一匹配。
     * 同时剥离展示后缀 `路径（显示名）`——模型从地图复制后带括号。
     * **多义一律不猜**：重名时返回 null，由调用方用 [resolveCandidates] 给出候选。
     */
    fun resolveCandidates(input: String?): List<String> {
        if (input.isNullOrBlank()) return emptyList()
        val cleaned = input
            .substringBefore("（").substringBefore("(").substringBefore("[")
            .trim().trim('/')
        if (cleaned.isBlank()) return emptyList()
        byId[cleaned]?.let { return listOf(it.id) }
        val byShort = declared.filter { it.shortName == cleaned }.map { it.id }
        if (byShort.isNotEmpty()) return byShort
        val byDisplay = declared.filter { it.displayName == cleaned }.map { it.id }
        if (byDisplay.isNotEmpty()) return byDisplay
        return declared.filter { it.id.endsWith("/$cleaned") }.map { it.id }
    }

    /** 唯一命中才返回；多义/未命中均返回 null */
    fun resolve(input: String?): String? = resolveCandidates(input).singleOrNull()

    // ═══════════ 2. 归类 ═══════════

    /** 结构化前缀 → 工具区 id（工具矩阵自身的元工具 + 生态工具） */
    private val prefixRules = listOf(
        "manage_zone" to "系统",
        "manage_mcp_servers" to "系统",
        "invoke_tools" to "系统",
        "clawhub_" to "系统",
        "plugin_install" to "系统",
        "workspace_" to "系统",
        "get_battery_status" to "系统",
        "skill__" to "技能",
        "skill_" to "技能",
        "skill:" to "技能",
        "plugin__" to "插件",
        "mcp__plugin__" to "插件",
        "operit__" to "插件",
    )

    /** MCP 服务器名 → 出厂工具区 id（避免服务器名关键词误匹配） */
    private val mcpServerZoneDefaults = mapOf(
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
    )

    /** 命中即返回（区必须已声明，否则视为未命中） */
    private fun hit(id: String): String? = if (id in byId) id else null

    /** 手动归属键：工具名本身，或 skill 挂载键 `skill:<名>` */
    fun linkedZone(name: String): String? {
        links[name]?.let { hit(it) }?.let { return it }
        if (name.startsWith("skill")) {
            val skillName = name.removePrefix("skill__").removePrefix("skill_").removePrefix("skill:")
            links["skill:$skillName"]?.let { hit(it) }?.let { return it }
        }
        return null
    }

    fun classifyTool(tool: Tool): String = classify(tool.name, tool.description)

    /**
     * 归类（UI 预览与模型侧共用同一实现）。
     * 顺序：手动归属 → 结构化前缀 → MCP 服务器 → 关键词 → 兜底区。
     * 只按**工具名**做关键词匹配（描述里的 search/query/find 等词曾把
     * get_media_status/calendar_query/open_file 误归搜索区）。
     */
    fun classify(name: String, description: String): String {
        linkedZone(name)?.let { return it }
        for ((prefix, zoneId) in prefixRules) {
            if (name.startsWith(prefix)) hit(zoneId)?.let { return it }
        }
        if (name == "memory_tool") hit("对话工具/记忆")?.let { return it }
        if (name.startsWith("mcp__")) {
            val server = name.removePrefix("mcp__").split("__").firstOrNull().orEmpty().lowercase()
            mcpServerZoneDefaults[server]?.let { hit(it) }?.let { return it }
            return keywordZone(server) ?: FALLBACK_ZONE_ID
        }
        return keywordZone(name.lowercase()) ?: FALLBACK_ZONE_ID
    }

    /** 关键词归类 — 只认已声明的区；命中多个时按「更深层优先」（子区优先于父区）。 */
    private fun keywordZone(text: String): String? = declared
        .filter { it.keywords.isNotEmpty() }
        .sortedByDescending { it.id.count { c -> c == '/' } }
        .firstOrNull { z -> z.keywords.any { kw -> kw.isNotBlank() && text.contains(kw.lowercase()) } }
        ?.id

    // ═══════════ 3. 统一视图 — 全部消费方的唯一数据源 ═══════════

    data class ZoneMap(
        /** 工具区 id → 直接工具（不含顶层工具；含 0 工具的空区 key） */
        val classified: Map<String, List<Tool>>,
        /** 根区 id（声明顺序） */
        val roots: List<String>,
        /** 根区 → 子区 id（声明顺序） */
        val children: Map<String, List<String>>,
        /** 区 → 直接工具数 */
        val counts: Map<String, Int>,
        /** 根区 → 子树工具总数 */
        val subtreeCounts: Map<String, Int>,
        /** 顶层直连工具（不参与归类/统计） */
        val topLevel: List<Tool>,
        /** 出现在地图里的区（隐藏区已剔除） */
        val visibleIds: Set<String>,
    ) {
        /** 全部区 id（根 + 子，声明顺序） */
        val allIds: List<String> get() = roots.flatMap { listOf(it) + children[it].orEmpty() }
    }

    fun zoneMap(tools: List<Tool>): ZoneMap {
        val unique = tools.distinctBy { it.name }
        val topLevel = unique.filter { it.name in topLevelTools }
        val rest = unique.filter { it.name !in topLevelTools }

        val grouped = rest.groupBy { classifyTool(it) }.toMutableMap()
        // 声明即存在：每个已声明区都有 key（可能空列表）——空区不再蒸发
        for (z in declared) grouped.getOrPut(z.id) { emptyList() }

        val counts = grouped.mapValues { it.value.size }
        val roots = mutableListOf<String>()
        val children = mutableMapOf<String, MutableList<String>>()
        for (z in declared) {
            val parent = z.parentId
            if (parent == null || parent !in byId) {
                roots.add(z.id)
            } else {
                children.getOrPut(parent) { mutableListOf() }.add(z.id)
            }
        }
        val subtreeCounts = roots.associateWith { root ->
            (counts[root] ?: 0) + children[root].orEmpty().sumOf { counts[it] ?: 0 }
        }
        return ZoneMap(
            classified = grouped,
            roots = roots,
            children = children,
            counts = counts,
            subtreeCounts = subtreeCounts,
            topLevel = topLevel,
            visibleIds = visibleZoneIds,
        )
    }

    /** 自校验 —— Σ(区直接工具数) + 顶层工具数 == 工具池总数。对照页用它判定信源是否分裂。 */
    fun selfCheck(map: ZoneMap, pool: List<Tool>): Boolean =
        map.counts.values.sum() + map.topLevel.size == pool.distinctBy { it.name }.size

    fun label(id: String): String = byId[id]?.label ?: id

    fun descriptionOf(id: String): String = byId[id]?.description.orEmpty()

    fun keywordsOf(id: String): List<String> = byId[id]?.keywords.orEmpty()

    fun displayNameOf(id: String): String = byId[id]?.displayName ?: id.substringAfterLast('/')

    fun toolsOf(id: String, tools: List<Tool>): List<Tool> =
        tools.distinctBy { it.name }
            .filter { it.name !in topLevelTools }
            .filter { classifyTool(it) == id }
            .sortedBy { it.name }

    // ═══════════ 4. 反查（关键词 → 工具区） ═══════════

    /** 一条搜索命中：工具区 + 命中的工具（可为空） */
    data class ZoneHit(val id: String, val matchedTools: List<String>)

    /**
     * 关键词反查工具区 —— 匹配区名/显示名/触发描述/触发条件，以及**区内工具的名与描述**
     * （后者是主要发现通道：模型多半记得"有个能截图/比价的工具"，而不是工具名）。
     */
    fun searchZones(tools: List<Tool>, query: String, typeFilter: String? = null): List<ZoneHit> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()
        val map = zoneMap(tools)
        return map.allIds.mapNotNull { id ->
            val zoneTools = map.classified[id].orEmpty()
            val metaHit = buildString {
                append(id).append(' ')
                append(displayNameOf(id)).append(' ')
                append(descriptionOf(id)).append(' ')
                append(keywordsOf(id).joinToString(" "))
            }.lowercase().contains(q)
            val toolHits = zoneTools.filter { t ->
                t.name.lowercase().contains(q) || t.description.lowercase().contains(q)
            }.map { it.name }
            val hit = metaHit || toolHits.isNotEmpty()
            val typeOk = when (typeFilter) {
                "mcp" -> zoneTools.any { it.name.startsWith("mcp__") }
                "skill" -> zoneTools.any { it.name.startsWith("skill_") || it.name == "use_skill" }
                else -> true
            }
            if (hit && typeOk) ZoneHit(id, toolHits) else null
        }
    }

    // ═══════════ 5. 呈现 — 地图 / 帮助 / invoke_tools ═══════════

    /** 单区元信息行：`**`路径（显示名）`** — [触发描述] … [触发条件] …` */
    private fun zoneLine(zone: ToolZone, indent: String = "", mark: String = ""): String {
        val descPart = if (zone.description.isBlank()) "" else "[触发描述] ${zone.description}"
        val kwPart = if (zone.keywords.isEmpty()) "" else "[触发条件] ${zone.keywords.joinToString(" ")}"
        val meta = listOf(descPart, kwPart).filter { it.isNotEmpty() }.joinToString(" ")
        val head = "$indent**`${zone.label}`**$mark"
        return if (meta.isEmpty()) head else "$head — $meta"
    }

    /**
     * 工具矩阵地图（系统提示层 1）。只依赖静态配置（区声明），不含任何运行时数据 ——
     * 工具数/状态一律由 invoke_tools 在消息层给出，保证请求前缀稳定（缓存不炸）。
     */
    fun buildMatrixMap(tools: List<Tool>): String {
        val map = zoneMap(tools)
        val visible = map.roots.filter { it in map.visibleIds }
        return buildString {
            appendLine("## 工具调度")
            appendLine()
            appendLine("你拥有一个工具矩阵 `工具`，按功能场景树状组织。每个工具区含：显示名称、触发描述、触发条件。")
            appendLine()
            appendLine("**使用**：`invoke_tools(\"工具区名\")` 加载该区工具（加载后直接调用、跨轮保持，无需任何前置操作）；不确定在哪时直接 `invoke_tools(\"关键词\")` 反查；`invoke_tools(\"帮助\")` 看全部。")
            appendLine("**调整**：需要新建/删除工具区、改备注与触发条件、调整工具归属时用 `manage_zone`；但**使用任何工具都不需要先移动它**。")
            appendLine()
            appendLine("### 可用工具区")
            appendLine()
            for (root in visible) {
                byId[root]?.let { appendLine(zoneLine(it)) }
                for (child in map.children[root].orEmpty()) {
                    if (child in map.visibleIds) byId[child]?.let { appendLine(zoneLine(it, "  ")) }
                }
            }
            appendLine()
            appendLine("找不到就 `invoke_tools(\"关键词\")` 反查，或 `invoke_tools(\"帮助\")` 查看全部工具区。")
        }
    }

    /**
     * invoke_tools("帮助") 内容 —— 与地图同源，另附各区工具数。
     * **含隐藏工具区**（标 [已隐藏]）：地图为省 token 只发可见区，但隐藏区必须仍可被发现与加载，
     * 否则「隐藏」会退化成「工具失联」。
     */
    fun buildHelpText(tools: List<Tool>): String {
        val map = zoneMap(tools)
        val total = tools.distinctBy { it.name }.count { it.name !in topLevelTools }
        val hiddenCount = map.counts.keys.count { it in hiddenZones }
        return buildString {
            appendLine(
                "工具池共 $total 个工具（${map.counts.size} 个工具区" +
                    (if (hiddenCount > 0) "，其中 $hiddenCount 个已隐藏" else "") + "）："
            )
            for (root in map.roots) {
                val mark = if (root in hiddenZones) " [已隐藏]" else ""
                byId[root]?.let {
                    appendLine(zoneLine(it, mark = mark) + countSuffix(map.counts[root] ?: 0) + subtreeNote(map, root))
                }
                for (child in map.children[root].orEmpty()) {
                    val cm = if (child in hiddenZones) " [已隐藏]" else ""
                    byId[child]?.let { appendLine(zoneLine(it, "  ", cm) + countSuffix(map.counts[child] ?: 0)) }
                }
            }
            appendLine()
            if (hiddenCount > 0) appendLine("[已隐藏] 的工具区不出现在系统提示的地图里（省 token），但可以正常加载与使用。")
            appendLine("顶层直连工具 ${map.topLevel.size} 个（始终可用，不占工具区）：${map.topLevel.map { it.name }.sorted().joinToString("、")}")
            appendLine("调 `invoke_tools(\"工具区名\")` 加载该区工具；工具加载后直接调用，跨轮保持。")
        }
    }

    private fun subtreeNote(map: ZoneMap, root: String): String {
        val direct = map.counts[root] ?: 0
        val subtree = map.subtreeCounts[root] ?: direct
        return if (map.children[root].orEmpty().isNotEmpty() && subtree != direct) "（含子区共 $subtree 个）" else ""
    }

    private fun countSuffix(n: Int): String = " [$n 个工具]"

    /**
     * invoke_tools —— 工具矩阵的**唯一入口**（加载 + 反查）。
     * 有子区时一次性加载「区 + 全部子区」，令请求 tools 数组一次到位（避免逐子区加载
     * 造成前缀反复断裂、缓存阶梯化）。
     * 名称解析不到时不报死错，而是退化为关键词反查 —— 把"未知"变成"找到"。
     */
    fun createInvokeToolsTool(
        allTools: List<Tool>,
        loadedZones: MutableSet<String>,
        freshToolsProvider: (() -> List<Tool>)? = null,
    ): Tool {
        val router = this
        return Tool(
            name = "invoke_tools",
            description = "工具矩阵的唯一入口：传工具区名则加载该区（含子区）的工具，加载后可直接调用且跨轮保持；" +
                "传关键词则反查工具区（不确定工具在哪个区时用）；留空或传\"帮助\"查看全部工具区与工具数量。",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "工具区名/完整路径（如 搜索/搜索引擎）、关键词，或留空=帮助")
                        })
                    },
                    required = listOf<String>()
                )
            },
            execute = { input ->
                val rawName = input.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "帮助"
                // 实时工具池：会话内新装插件/新连 MCP 立即可见（而非消息开始时的快照）
                val live = freshToolsProvider?.invoke() ?: allTools
                if (rawName == "帮助" || rawName.equals("help", ignoreCase = true)) {
                    listOf(UIMessagePart.Text(router.buildHelpText(live)))
                } else {
                    val map = router.zoneMap(live)
                    val candidates = router.resolveCandidates(rawName)
                    when {
                        candidates.size == 1 -> listOf(UIMessagePart.Text(router.loadZone(candidates.first(), map, loadedZones)))
                        candidates.size > 1 -> listOf(
                            UIMessagePart.Text(
                                "'$rawName' 有 ${candidates.size} 个同名工具区，请用完整路径指定其一：" +
                                    candidates.joinToString("、") { router.label(it) }
                            )
                        )
                        else -> {
                            // 反查回退：把"未知"变成"找到"
                            val hits = router.searchZones(live, rawName).take(8)
                            if (hits.isEmpty()) {
                                listOf(
                                    UIMessagePart.Text(
                                        "未知: '$rawName'。可用工具区: ${map.roots.filter { it in map.visibleIds }.joinToString("、")}。" +
                                            "调 `invoke_tools(\"帮助\")` 查看详情。"
                                    )
                                )
                            } else {
                                val lines = hits.map { h ->
                                    val toolHint = if (h.matchedTools.isEmpty()) "" else
                                        " · 命中工具: " + h.matchedTools.take(6).joinToString("、") +
                                            (if (h.matchedTools.size > 6) " 等${h.matchedTools.size}个" else "")
                                    "- `${router.label(h.id)}` — ${router.descriptionOf(h.id).take(60)}$toolHint"
                                }
                                listOf(
                                    UIMessagePart.Text(
                                        "'$rawName' 不是工具区名。按关键词反查到 ${hits.size} 个工具区（用完整路径再调 invoke_tools 即加载）：\n" +
                                            lines.joinToString("\n")
                                    )
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    /** 加载一个工具区（含整棵子树），返回给模型的文本 */
    private fun loadZone(id: String, map: ZoneMap, loadedZones: MutableSet<String>): String {
        loadedZones.add(id)
        val children = map.children[id].orEmpty()
        if (children.isNotEmpty()) children.forEach { loadedZones.add(it) }
        val direct = map.classified[id].orEmpty()
        return buildString {
            if (children.isNotEmpty()) {
                appendLine("「${label(id)}」含${children.size}个子区及直接工具（已全部加载，可直接调用）:")
                appendLine()
                for (child in children) {
                    byId[child]?.let {
                        appendLine("- **`${it.label}`** — [触发描述] ${it.description} [触发条件] ${it.keywords.joinToString(" ")}")
                    }
                }
                if (direct.isNotEmpty()) {
                    appendLine()
                    appendLine("直接工具：")
                    for (t in direct.sortedBy { it.name }) appendLine(toolLine(t))
                }
                appendLine()
                appendLine("子区标注了触发描述与触发条件(关键词)，据此判断工具位置。所有工具均已注册、可直接调用 — 直接发出工具调用即可，无需移动/注册等任何前置操作。")
            } else {
                if (direct.isEmpty()) {
                    appendLine("「${label(id)}」当前无可用工具。")
                    appendLine("可尝试 `invoke_tools(\"帮助\")` 查看其他工具区。")
                } else {
                    appendLine("「${label(id)}」以下工具已注册、可直接调用（直接以工具调用形式发出工具名，无需任何前置操作）：")
                    for (t in direct.sortedBy { it.name }) appendLine(toolLine(t))
                }
            }
            val mounted = links.entries
                .filter { it.key.startsWith("skill:") && it.value == id }
                .map { it.key.removePrefix("skill:") }
            if (mounted.isNotEmpty()) {
                appendLine()
                appendLine("挂载到本工具区的 Skills（`skill__<name>` 工具已直接可用）:")
                mounted.sorted().forEach { appendLine("- `$it`") }
            }
        }
    }

    /** 工具行 —— 内联参数 schema：区工具定义不进请求 tools 数组时，这是模型获知参数的唯一通道 */
    private fun toolLine(t: Tool): String = buildString {
        append("- `${t.name}`: ${t.description.take(80).replace("\\n", " ")}")
        t.parameters()?.let { schema ->
            appendLine()
            append("  参数定义: " + Json.encodeToString(schema))
        }
    }
}
