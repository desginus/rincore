/**
 * 工具矩阵管理工具 — 模块: A. 传输链 / tools
 *
 * v4.8.83 重写（取代 DomainTools.kt 的 manage_domain/delete_domain 等历史补丁堆）:
 *  - 全部操作共用同一套「工具区」模型（[ToolZone]，id 即身份），不再有内置/自定义分支；
 *  - 删除是真删除（含后代区 + 归属迁移 + 元数据清理），返回值与实际效果永远一致；
 *  - id 创建后不可变 —— 从根上消灭双叠路径 / 幽灵区 / 假成功一整类 bug；
 *  - 所有写操作走 SettingsStore.updateWithResult（互斥读-改-写，并发安全）。
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.routing.FALLBACK_ZONE_ID
import me.rerere.rikkahub.data.ai.tools.routing.ToolZone
import me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter
import me.rerere.rikkahub.data.ai.tools.routing.zonePathOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TOP_LEVEL_TARGET = "顶层"

fun createZoneTools(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool> = { emptyList() },
): List<Tool> = listOf(
    searchZonesTool(settingsStore, toolPoolProvider),
    manageZoneTool(settingsStore, toolPoolProvider),
    listZonesTool(settingsStore, toolPoolProvider),
    moveToolToZoneTool(settingsStore, toolPoolProvider),
)

/** 由当前设置构造路由器 —— 模型侧与 UI 侧唯一的构造口径 */
fun zoneRouterOf(settings: Settings): ZoneRouter = ZoneRouter(
    zones = settings.toolZones,
    links = settings.toolZoneLinks,
    hiddenZones = settings.hiddenZones,
    topLevelTools = topLevelToolSetOf(settings),
)

/**
 * 按关键词反查工具区 —— 匹配区名、显示名、触发描述、触发条件与区内工具名。
 */
private fun searchZonesTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "search_zones",
    description = "按关键词或标签反查工具区位置。匹配工具区的名称、触发描述、触发条件与区内工具名（如：比价、定时、MCP、Skill）。返回全部匹配结果，无数量上限。不确定工具在哪个工具区时使用。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "关键词或标签，如：比价、定时、MCP、Skill")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "可选。类别过滤：mcp（含 MCP 工具的工具区）/ skill（含 Skill 工具的工具区）")
                })
            },
            required = listOf("query")
        )
    },
    needsApproval = { false },
    execute = { input ->
        val query = input.jsonObject["query"]?.jsonPrimitive?.content?.trim() ?: error("query is required")
        val typeFilter = input.jsonObject["type"]?.jsonPrimitive?.content?.trim()?.lowercase()

        val tools = toolPoolProvider()
        val router = zoneRouterOf(settingsStore.settingsFlow.value)
        val map = router.zoneMap(tools)

        fun toolsInZone(id: String): List<String> = map.classified[id].orEmpty().map { it.name }

        val q = query.lowercase()
        val matched = map.allIds.filter { it in map.visibleIds }.filter { id ->
            val haystack = buildString {
                append(id).append(' ')
                append(router.displayNameOf(id)).append(' ')
                append(router.descriptionOf(id)).append(' ')
                append(router.keywordsOf(id).joinToString(" ")).append(' ')
                append(toolsInZone(id).joinToString(" "))
            }.lowercase()
            haystack.contains(q)
        }.filter { id ->
            when (typeFilter) {
                "mcp" -> toolsInZone(id).any { it.startsWith("mcp__") }
                "skill" -> toolsInZone(id).any { it.startsWith("skill_") || it == "use_skill" }
                else -> true
            }
        }

        if (matched.isEmpty()) {
            val hint = if (typeFilter != null) " (过滤: $typeFilter)" else ""
            listOf(UIMessagePart.Text("未找到匹配 '$query'$hint 的工具区。调 `invoke_tools(\"帮助\")` 查看全部工具区。"))
        } else {
            val lines = matched.map { id ->
                val desc = router.descriptionOf(id)
                val kws = router.keywordsOf(id)
                val descPart = if (desc.isBlank()) "" else "[触发描述] $desc"
                val kwPart = if (kws.isEmpty()) "" else "[触发条件] ${kws.joinToString(" ")}"
                "- `${router.label(id)}` — ${listOf(descPart, kwPart).filter { it.isNotEmpty() }.joinToString(" ")}"
            }
            listOf(UIMessagePart.Text("匹配 '$query' 的工具区 (${lines.size} 个):\n" + lines.joinToString("\n")))
        }
    },
)

/**
 * 工具区增删改。id（完整路径）创建后不可变 —— 需要换路径就删掉重建。
 */
private fun manageZoneTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "manage_zone",
    description = "【仅当用户明确要求创建/删除/修改工具区时调用, 不要主动调用】管理工具区：create(创建) / delete(真删除) / update(改显示名、触发描述、触发条件)。工具区无内置与自定义之分, 一律同等可增删改。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "操作类型: create / delete / update")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具区名或完整路径，如 '我的工具' 或 '搜索/自定义子区'（create 时可含父路径；其余操作填已有区名）")
                })
                put("parent", buildJsonObject {
                    put("type", "string")
                    put("description", "父工具区(可选)。仅 create 使用；name 已含 '/' 时可省")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "触发描述(可选): 该工具区的功能解释")
                })
                put("keywords", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "触发条件(可选): 关键词列表, 用于自动归类与被 search_zones 反查")
                })
                put("display_name", buildJsonObject {
                    put("type", "string")
                    put("description", "显示名(可选)。不填则显示路径末段")
                })
            },
            required = listOf("action", "name")
        )
    },
    execute = { input ->
        val action = input.jsonObject["action"]?.jsonPrimitive?.content?.lowercase() ?: error("action required")
        val rawName = input.jsonObject["name"]?.jsonPrimitive?.content ?: error("name required")
        val parent = input.jsonObject["parent"]?.jsonPrimitive?.content
        val description = input.jsonObject["description"]?.jsonPrimitive?.content
        val keywords = input.jsonObject["keywords"]?.jsonArray?.map { it.jsonPrimitive.content }
        val displayName = input.jsonObject["display_name"]?.jsonPrimitive?.content

        val msg = when (action) {
            "create" -> {
                val id = zonePathOf(parent, rawName)
                require(id.isNotBlank()) { "工具区名不能为空" }
                settingsStore.updateWithResult { s ->
                    if (s.toolZones.any { it.id == id }) {
                        s to "工具区 '$id' 已存在（没有重复创建）。"
                    } else {
                        val zone = ToolZone(
                            id = id,
                            title = displayName.orEmpty().trim(),
                            description = description.orEmpty(),
                            keywords = keywords ?: emptyList(),
                        )
                        s.copy(toolZones = s.toolZones + zone) to
                            "已创建工具区 '$id'。它现在就在工具矩阵里（即使暂时没有工具也不会消失），" +
                            "可把工具移进来，或调 search_zones 反查。"
                    }
                }
            }

            "delete" -> {
                settingsStore.updateWithResult { s ->
                    val router = zoneRouterOf(s)
                    val id = router.resolve(rawName)
                        ?: return@updateWithResult s to "工具区 '$rawName' 不存在。"
                    if (id == FALLBACK_ZONE_ID) {
                        return@updateWithResult s to "「$FALLBACK_ZONE_ID」是兜底工具区，不能删除（它是未归类工具的归宿）。"
                    }
                    // 真删除 —— 区的元数据就长在区对象里，随对象一起消失
                    val doomed = setOf(id) + s.toolZones.filter { it.id.startsWith("$id/") }.map { it.id }.toSet()
                    val parentZone = s.toolZones.firstOrNull { it.id == id }?.id?.substringBeforeLast('/', "")
                        .orEmpty()
                    val target = parentZone.takeIf { it.isNotBlank() && s.toolZones.any { z -> z.id == it } }
                    var migrated = 0
                    val newLinks = s.toolZoneLinks.mapNotNull { (tool, zone) ->
                        when {
                            zone !in doomed -> tool to zone
                            target != null -> { migrated++; tool to target }
                            else -> null // 没有父区 → 交回自动归类
                        }
                    }.toMap()
                    val newZones = s.toolZones.filter { it.id !in doomed }
                    val newHidden = s.hiddenZones - doomed
                    s.copy(toolZones = newZones, toolZoneLinks = newLinks, hiddenZones = newHidden) to buildString {
                        append("已删除工具区 '").append(id).append("'")
                        if (doomed.size > 1) append("（含子区 ").append(doomed.size - 1).append(" 个）")
                        append("。")
                        append(
                            when {
                                migrated == 0 -> "该区没有手动归属的工具。"
                                target != null -> "$migrated 个工具的归属已迁移到父区 '$target'（不会被打散）。"
                                else -> "该区没有父区，$migrated 个工具已交回自动归类。"
                            }
                        )
                    }
                }
            }

            "update" -> {
                settingsStore.updateWithResult { s ->
                    val router = zoneRouterOf(s)
                    val id = router.resolve(rawName) ?: return@updateWithResult s to "工具区 '$rawName' 不存在。"
                    val applied = mutableListOf<String>()
                    val zones = s.toolZones.map { z ->
                        if (z.id != id) z else z.copy(
                            title = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: z.title,
                            description = description ?: z.description,
                            keywords = keywords ?: z.keywords,
                        )
                    }
                    if (displayName != null) applied += "显示名"
                    if (description != null) applied += "触发描述"
                    if (keywords != null) applied += "触发条件"
                    val dup = s.toolZones.distinctBy { it.id }.size != s.toolZones.size
                    s.copy(toolZones = zones) to
                        (if (applied.isEmpty()) "工具区 '$id': 未提供要修改的字段。" else "已更新工具区 '$id': ${applied.joinToString("、")}。") +
                        if (dup) " (检测到重复 id, 已按首个生效)" else ""
                }
            }

            else -> error("未知操作: $action。支持: create, delete, update")
        }
        me.rerere.rikkahub.data.ai.CallTracer.event("OK", "manage_zone", "$action $rawName → $msg")
        listOf(UIMessagePart.Text(msg))
    },
)

/** 列出全部工具区（与工具矩阵地图/UI 同源） */
private fun listZonesTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "list_zones",
    description = "列出所有工具区及其工具数量（与系统提示/Invoke Tools/设置页完全同源）",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {}, required = listOf<String>())
    },
    execute = {
        val tools = toolPoolProvider()
        val router = zoneRouterOf(settingsStore.settingsFlow.value)
        val map = router.zoneMap(tools)
        val total = map.counts.values.sum()
        val result = buildString {
            appendLine("可用工具区 (共 ${map.counts.size} 个, ${map.roots.size} 个顶级):")
            for (root in map.roots) {
                if (root !in map.visibleIds) continue
                val direct = map.counts[root] ?: 0
                val subtree = map.subtreeCounts[root] ?: direct
                val subNote = if (map.children[root].orEmpty().isNotEmpty() && subtree != direct) "（含子区共 $subtree 个）" else ""
                appendLine("- ${router.label(root)} [$direct 个工具]$subNote${kwSuffix(router.keywordsOf(root))}")
                for (child in map.children[root].orEmpty()) {
                    if (child !in map.visibleIds) continue
                    appendLine("  - ${router.label(child)} [${map.counts[child] ?: 0} 个工具]${kwSuffix(router.keywordsOf(child))}")
                }
            }
            appendLine()
            appendLine("顶层直连工具 (${map.topLevel.size} 个, 始终可用, 不占工具区): ${map.topLevel.map { it.name }.sorted().joinToString("、")}")
            appendLine("共 $total 个工具参与工具区归类。调 invoke_tools(\"工具区名\") 查看区内工具；所有工具均可直接调用。")
        }
        listOf(UIMessagePart.Text(result))
    },
)

private fun kwSuffix(keywords: List<String>): String =
    if (keywords.isEmpty()) "" else " [触发条件] ${keywords.joinToString(" ")}"

/**
 * 移动工具归属 —— 目标可以是任意工具区，也可以是「顶层」（始终注入、不参与归类）。
 * 这是工具归属的唯一入口（UI 与模型同一套语义）。
 */
private fun moveToolToZoneTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "move_tool_to_zone",
    description = "【仅当用户明确要求移动工具/技能到某工具区或顶层时调用, 不要主动调用】把工具或 Skill 的归属改到指定工具区（target_zone 填 \"$TOP_LEVEL_TARGET\" 则移回顶层直连）。注意：使用任何工具都无需先移动它 —— 直接调用即可。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具名称，或已启用 Skill 的名称")
                })
                put("target_zone", buildJsonObject {
                    put("type", "string")
                    put("description", "目标工具区名或完整路径（如 '搜索/搜索引擎'），或 \"$TOP_LEVEL_TARGET\"")
                })
            },
            required = listOf("tool_name", "target_zone")
        )
    },
    execute = { input ->
        val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.content ?: error("tool_name required")
        val targetRaw = input.jsonObject["target_zone"]?.jsonPrimitive?.content ?: error("target_zone required")
        val pool = toolPoolProvider()
        val settings = settingsStore.settingsFlow.value
        val router = zoneRouterOf(settings)

        val toTopLevel = targetRaw.trim() == TOP_LEVEL_TARGET || targetRaw.trim().equals("top", ignoreCase = true)
        val targetZone = if (toTopLevel) null else router.resolve(targetRaw)

        // 存在性 / 有效性校验 —— 不合法直接失败，绝不假成功
        val skillNames = pool.filter { it.name.startsWith("skill__") }.map { it.name.removePrefix("skill__") }.toSet()
        val toolNames = pool.map { it.name }.toSet()
        val isSkill = toolName in skillNames || toolName.startsWith("skill_") || toolName.startsWith("skill:")
        val problem: String? = when {
            !toTopLevel && targetZone == null ->
                "无效目标工具区 '$targetRaw'（不存在）。可用: " +
                    router.zoneMap(pool).allIds.joinToString("、") + "、" + TOP_LEVEL_TARGET +
                    "。（提示：本工具仅调整工具归属；使用任何工具无需先移动它，直接调用即可。）"
            !isSkill && toolName !in toolNames ->
                "工具 '$toolName' 不存在。可用工具: ${toolNames.sorted().take(30).joinToString("、")}" +
                    (if (toolNames.size > 30) " 等${toolNames.size}个" else "") +
                    "。若为 Skill，请确认其已启用: ${skillNames.sorted().take(20).joinToString("、")}"
            else -> null
        }

        if (problem != null) {
            me.rerere.rikkahub.data.ai.CallTracer.event("WARN", "move_tool_to_zone", problem.take(60))
            listOf(UIMessagePart.Text(problem))
        } else {
            val linkKey = if (isSkill) {
                "skill:" + toolName.removePrefix("skill__").removePrefix("skill_").removePrefix("skill:")
            } else toolName

            val msg = settingsStore.updateWithResult { s ->
                if (toTopLevel) {
                    s.copy(
                        toolZoneLinks = s.toolZoneLinks - linkKey,
                        topLevelAdditions = s.topLevelAdditions + toolName,
                        topLevelRemovals = s.topLevelRemovals - toolName,
                    ) to "已把 '$toolName' 移回顶层直连（始终注入请求体, 不参与工具区归类）。"
                } else {
                    s.copy(
                        toolZoneLinks = s.toolZoneLinks + (linkKey to targetZone!!),
                        topLevelAdditions = s.topLevelAdditions - toolName,
                        topLevelRemovals = s.topLevelRemovals + toolName.takeIf { it in topLevelToolSetOf(s) }.orEmpty(),
                    ) to if (isSkill) {
                        "已将 Skill '$toolName' 挂载到工具区 '$targetZone'。调用时经 invoke_tools(\"$targetZone\") 可见。"
                    } else {
                        "已将工具 '$toolName' 移动到工具区 '$targetZone'。"
                    }
                }
            }
            me.rerere.rikkahub.data.ai.CallTracer.event("OK", "move_tool_to_zone", "$toolName → $targetRaw")
            listOf(UIMessagePart.Text(msg))
        }
    },
)
