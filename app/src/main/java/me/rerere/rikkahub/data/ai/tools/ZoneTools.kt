/**
 * 工具矩阵管理工具 — 模块: A. 传输链 / tools
 *
 * v4.8.84：工具矩阵对模型只暴露**两个**入口 —— `invoke_tools`（加载/反查，见 ZoneRouter）
 * 与 `manage_zone`（管理，本文件）。旧的三件套（search_zones / list_zones / move_tool_to_zone）
 * 全部并入这两个：顶层工具越少，每轮请求越省、模型选择越不容易出错。
 *
 * manage_zone 的四个动作 = 工具矩阵的全部写能力：
 *   create 新建工具区（可挂父区） / update 改显示名与备注(触发描述)/触发条件 /
 *   delete 删除（连带子区，手动归属自动迁移） / assign 调整工具归属（进区/回顶层/交回自动）
 * 全部走 SettingsStore.updateWithResult（互斥读-改-写，并发安全）。
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
import me.rerere.rikkahub.data.ai.tools.routing.CORE_MATRIX_TOOLS
import me.rerere.rikkahub.data.ai.tools.routing.FALLBACK_ZONE_ID
import me.rerere.rikkahub.data.ai.tools.routing.ToolZone
import me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter
import me.rerere.rikkahub.data.ai.tools.routing.zonePathOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

/** 归属目标：顶层直连 */
const val TARGET_TOP_LEVEL = "顶层"

/** 归属目标：交回自动归类（清除手动归属） */
const val TARGET_AUTO = "自动"

/** 由当前设置构造路由器 —— 模型侧与 UI 侧唯一的构造口径 */
fun zoneRouterOf(settings: Settings): ZoneRouter = ZoneRouter(
    zones = settings.toolZones,
    links = settings.toolZoneLinks,
    hiddenZones = settings.hiddenZones,
    topLevelTools = topLevelToolSetOf(settings),
)

fun createZoneTools(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool> = { emptyList() },
): List<Tool> = listOf(manageZoneTool(settingsStore, toolPoolProvider))

private fun manageZoneTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "manage_zone",
    description = "【仅当用户明确要求管理工具矩阵时调用, 不要主动调用】" +
        "工具矩阵的唯一管理入口。action: create(新建工具区/子区) / update(改显示名、触发描述、触发条件) / " +
        "delete(删除工具区) / assign(调整工具归属)。\n" +
        "可以做: 建区与子区; 改区的备注(触发描述)与触发条件; 删区(连带其子区, 区内手动归属的工具自动迁到父区); " +
        "把工具或 Skill 归入某个区、移回顶层直连、或交回自动归类。\n" +
        "不可以做: 改工具区的路径(id 创建后不可变, 要换路径请删掉重建); 删除兜底区「$FALLBACK_ZONE_ID」; " +
        "移动或删除工具本身(工具由助手配置/MCP/技能决定); 把 ${CORE_MATRIX_TOOLS.joinToString(" / ")} 移出顶层。\n" +
        "注意: 使用任何工具都无需先移动它 —— 直接调用即可。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "create / update / delete / assign")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具区名或完整路径。create/update/delete 使用。如 '我的引擎' 或 '搜索/我的引擎'")
                })
                put("parent", buildJsonObject {
                    put("type", "string")
                    put("description", "父工具区(可选, 仅 create)。name 已含 '/' 时可省")
                })
                put("display_name", buildJsonObject {
                    put("type", "string")
                    put("description", "显示名(可选)。留空回落到路径末段")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "触发描述 = 这个区是干什么的(可选, create/update)")
                })
                put("keywords", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "触发条件 = 关键词(可选, create/update)。用于自动归类与反查")
                })
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具名或已启用 Skill 名(仅 assign)")
                })
                put("target_zone", buildJsonObject {
                    put("type", "string")
                    put("description", "归属目标(仅 assign): 工具区名/完整路径、\"$TARGET_TOP_LEVEL\"、或 \"$TARGET_AUTO\"(交回自动归类)")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val action = input.jsonObject["action"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: error("action required")
        val rawName = input.jsonObject["name"]?.jsonPrimitive?.content?.trim()
        val parent = input.jsonObject["parent"]?.jsonPrimitive?.content?.trim()
        val displayName = input.jsonObject["display_name"]?.jsonPrimitive?.content
        val description = input.jsonObject["description"]?.jsonPrimitive?.content
        val keywords = input.jsonObject["keywords"]?.jsonArray?.map { it.jsonPrimitive.content }
        val toolName = input.jsonObject["tool_name"]?.jsonPrimitive?.content?.trim()
        val targetZone = input.jsonObject["target_zone"]?.jsonPrimitive?.content?.trim()

        /** 让错误信息永远可操作：歧义给候选、未命中给可用清单 */
        fun unknownZone(router: ZoneRouter, raw: String): String {
            val cands = router.resolveCandidates(raw)
            return if (cands.size > 1) {
                "'$raw' 有 ${cands.size} 个同名工具区，请用完整路径指定其一: " +
                    cands.joinToString("、") { router.label(it) }
            } else {
                "'$raw' 不存在（工具区名/完整路径）。可用: " +
                    router.zoneIds.sorted().take(24).joinToString("、") +
                    "。调 `invoke_tools(\"帮助\")` 查看全部。"
            }
        }

        val msg: String = when (action) {
            "create" -> {
                val explicit = rawName.orEmpty().trim().trim('/')
                require(explicit.isNotBlank()) { "create 需要 name（工具区名或完整路径）" }
                val id = if (parent.isNullOrBlank()) explicit else zonePathOf(parent, explicit.substringAfterLast('/'))
                val parentId = id.substringBeforeLast('/', "").takeIf { it.isNotBlank() }
                settingsStore.updateWithResult { s ->
                    val router = zoneRouterOf(s)
                    when {
                        router.zone(id) != null -> s to "工具区 '${router.label(id)}' 已存在（未重复创建）。"
                        parentId != null && router.zone(parentId) == null -> s to
                            "父工具区 '$parentId' 不存在 —— 请先创建它，或不传 parent 直接建顶级区。"
                        else -> s.copy(
                            toolZones = s.toolZones + ToolZone(
                                id = id,
                                title = displayName.orEmpty().trim(),
                                description = description.orEmpty(),
                                keywords = keywords?.filter { it.isNotBlank() } ?: emptyList(),
                            )
                        ) to "已创建工具区 '$id'（路径即 id，创建后不可改）。它已在工具矩阵里 —— 即使暂无工具也不会消失；" +
                            "可用 `manage_zone(action=\"assign\", tool_name=..., target_zone=\"$id\")` 把工具放进来。"
                    }
                }
            }

            "update" -> {
                val raw = rawName.orEmpty()
                require(raw.isNotBlank()) { "update 需要 name" }
                if (displayName == null && description == null && keywords == null) {
                    "未提供要修改的字段：display_name / description / keywords 至少给一个。"
                } else settingsStore.updateWithResult { s ->
                    val router = zoneRouterOf(s)
                    val id = router.resolve(raw) ?: return@updateWithResult s to unknownZone(router, raw)
                    val applied = buildList {
                        if (displayName != null) add("显示名")
                        if (description != null) add("触发描述")
                        if (keywords != null) add("触发条件")
                    }
                    s.copy(
                        toolZones = s.toolZones.map { z ->
                            if (z.id != id) z else z.copy(
                                title = displayName?.trim() ?: z.title,
                                description = description ?: z.description,
                                keywords = keywords?.filter { it.isNotBlank() } ?: z.keywords,
                            )
                        }
                    ) to "已更新工具区 '${router.label(id)}'：${applied.joinToString("、")}。" +
                        "（只改传入的字段，其余保持不变。）"
                }
            }

            "delete" -> {
                val raw = rawName.orEmpty()
                require(raw.isNotBlank()) { "delete 需要 name" }
                settingsStore.updateWithResult { s ->
                    val router = zoneRouterOf(s)
                    val id = router.resolve(raw) ?: return@updateWithResult s to unknownZone(router, raw)
                    if (id == FALLBACK_ZONE_ID) {
                        return@updateWithResult s to "「$FALLBACK_ZONE_ID」是兜底工具区，不能删除（未归类工具的归宿）。"
                    }
                    val doomed = setOf(id) + s.toolZones.filter { it.id.startsWith("$id/") }.map { it.id }.toSet()
                    val parentId = id.substringBeforeLast('/', "").takeIf { it.isNotBlank() && s.toolZones.any { z -> z.id == it } }
                    var migrated = 0
                    val newLinks = s.toolZoneLinks.mapNotNull { (tool, zone) ->
                        when {
                            zone !in doomed -> tool to zone
                            parentId != null -> { migrated++; tool to parentId }
                            else -> null
                        }
                    }.toMap()
                    s.copy(
                        toolZones = s.toolZones.filter { it.id !in doomed },
                        toolZoneLinks = newLinks,
                        hiddenZones = s.hiddenZones - doomed,
                    ) to buildString {
                        append("已删除工具区 '$id'")
                        if (doomed.size > 1) append("（含子区 ${doomed.size - 1} 个）")
                        append("。该区的备注/触发条件一并删除，不可恢复。")
                        append(
                            when {
                                migrated == 0 -> "它没有手动归属的工具。"
                                parentId != null -> "$migrated 个工具的归属已迁移到父区 '$parentId'（不会被打散）。"
                                else -> "它没有父区，$migrated 个工具已交回自动归类。"
                            }
                        )
                    }
                }
            }

            "assign" -> {
                val tool = toolName.orEmpty()
                val target = targetZone.orEmpty()
                require(tool.isNotBlank() && target.isNotBlank()) { "assign 需要 tool_name 与 target_zone" }
                val pool = toolPoolProvider()
                val routerNow = zoneRouterOf(settingsStore.settingsFlow.value)
                val toolNames = pool.map { it.name }.toSet()
                val skillNames = pool.filter { it.name.startsWith("skill__") }.map { it.name.removePrefix("skill__") }.toSet()
                val isSkill = tool in skillNames || tool.startsWith("skill_") || tool.startsWith("skill:")
                val toTop = target == TARGET_TOP_LEVEL
                val toAuto = target == TARGET_AUTO
                val targetId = if (toTop || toAuto) null else routerNow.resolve(target)

                when {
                    !isSkill && tool !in toolNames ->
                        "工具 '$tool' 不存在。可用工具: ${toolNames.sorted().take(30).joinToString("、")}" +
                            (if (toolNames.size > 30) " 等${toolNames.size}个" else "") +
                            "。若为 Skill，请确认已启用: ${skillNames.sorted().take(20).joinToString("、")}"
                    !toTop && !toAuto && targetId == null -> unknownZone(routerNow, target)
                    tool in CORE_MATRIX_TOOLS -> "'$tool' 是工具矩阵的核心件，必须留在顶层（移出后模型将无法加载任何工具区）。"
                    else -> settingsStore.updateWithResult { s ->
                        val linkKey = if (isSkill) {
                            "skill:" + tool.removePrefix("skill__").removePrefix("skill_").removePrefix("skill:")
                        } else tool
                        val wasTopLevel = tool in topLevelToolSetOf(s)
                        val newLinks = if (toAuto) s.toolZoneLinks - linkKey else s.toolZoneLinks + (linkKey to (targetId ?: target))
                        val newAdd = if (toTop) s.topLevelAdditions + tool else s.topLevelAdditions - tool
                        val newRemove = when {
                            toTop || toAuto -> s.topLevelRemovals - tool
                            wasTopLevel -> s.topLevelRemovals + tool
                            else -> s.topLevelRemovals
                        }
                        s.copy(toolZoneLinks = newLinks, topLevelAdditions = newAdd, topLevelRemovals = newRemove) to
                            when {
                                toTop -> "已把 '$tool' 移回顶层直连（始终注入请求体，不参与工具区归类）。"
                                toAuto -> "已把 '$tool' 的手动归属清除，交回自动归类。"
                                isSkill -> "已将 Skill '$tool' 挂载到工具区 '${routerNow.label(targetId!!)}'。"
                                else -> "已将工具 '$tool' 归入工具区 '${routerNow.label(targetId!!)}'。"
                            }
                    }
                }
            }

            else -> "未知 action: $action。支持: create / update / delete / assign"
        }
        me.rerere.rikkahub.data.ai.CallTracer.event("OK", "manage_zone", "$action → ${msg.take(120)}")
        listOf(UIMessagePart.Text(msg))
    },
)
