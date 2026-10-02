/**
 * 工具矩阵管理工具 — 模块: A. 传输链 / tools
 *
 * 工具矩阵对模型只暴露**两个**入口：`invoke_tools`（加载/反查，见 ZoneRouter）与本文件的
 * `manage_zone`（管理）。本文件**不含任何写规则** —— 全部委托 [ZoneOps]，
 * 这样 UI 与模型走的是同一套校验与同一套语义（上一版两边各写一套，正是 bug 温床）。
 *
 * 三条硬约束（v4.8.87）：
 *  1. **写后校验**：每个操作都带 [ZoneOps.Res.verify] 谓词；写完读回真实设置判定，
 *     不通过就如实报"未真正生效"——回执永远由**事实**生成，不由意图生成。
 *  2. **技能归属键 = 完整工具名**（`skill__<净化名>`）：模型给裸技能名时用与
 *     SkillManager 同一个净化函数归一，绝不出现"两个键指同一个技能"。
 *  3. **核心件保护**：`invoke_tools` / `manage_zone` 恒在顶层。
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
import me.rerere.rikkahub.data.ai.tools.routing.ZoneOps
import me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter
import me.rerere.rikkahub.data.ai.tools.routing.ZoneTree
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

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

/**
 * 原子施加 + **写后校验**：校验不过就如实报告（这就是"假成功"的根治点）。
 *
 * v4.8.89: 校验对象从"重读 settingsFlow"改为"**刚施加的值**" —— updateWithResult 返回时
 * 内存与磁盘都已落定，重读流会被任何并发写夹层误判成"未通过"（用户实证的假失败）。
 */
private suspend fun runZoneOp(settingsStore: SettingsStore, op: (Settings) -> ZoneOps.Res): String {
    val res = settingsStore.updateWithResult { s -> val r = op(s); r.settings to r }
    val verified = res.verify?.invoke(res.settings) ?: true
    return when {
        !res.ok -> "操作未生效：${res.message}"                    // 校验失败：原因已在文案里
        !verified -> "操作未生效（写后校验未通过）：${res.message}"
        else -> res.message
    }
}

private fun manageZoneTool(
    settingsStore: SettingsStore,
    toolPoolProvider: () -> List<Tool>,
) = Tool(
    name = "manage_zone",
    description = "【仅当用户明确要求管理工具矩阵时调用, 不要主动调用】工具矩阵的唯一管理入口。\n" +
        "action: create(新建工具区) / update(改名与备注) / move(移动到别的父区) / delete(删除工具区) / assign(调整工具归属)。\n" +
        "可以做: 建区与子区; 改名/移动(路径自动跟随, 身份不变); 改显示名、触发描述、触发条件; " +
        "删区(其子区自动上移一级, 不连带删除); 把工具或技能归入某个区、移回顶层直连、或交回自动归类。\n" +
        "不可以做: 改工具区身份(改名/移动不需要重建); 删除兜底区「$FALLBACK_ZONE_ID」; " +
        "移动或删除工具本身(工具由助手配置/MCP/技能决定); 把 ${CORE_MATRIX_TOOLS.joinToString(" / ")} 移出顶层。\n" +
        "注意: 使用任何工具都无需先移动它 —— 直接调用即可。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "create / update / move / delete / assign")
                })
                put("zone", buildJsonObject {
                    put("type", "string")
                    put("description", "目标工具区：id、段名、完整路径或显示名均可(update/move/delete 用)")
                })
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具区段名：create 时为新区名；update/move 时为新名字(改名)")
                })
                put("parent", buildJsonObject {
                    put("type", "string")
                    put("description", "父工具区(create 时指定建在哪；move 时指定移到哪，留空=移到根区)")
                })
                put("display_name", buildJsonObject {
                    put("type", "string")
                    put("description", "显示名(可选)。留空则用段名")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put("description", "触发描述 = 这个区是干什么的(可选)")
                })
                put("keywords", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "触发条件 = 关键词(可选)。用于自动归类与反查")
                })
                put("tool_name", buildJsonObject {
                    put("type", "string")
                    put("description", "工具名，或已启用 Skill 的名字/工具名(仅 assign)")
                })
                put("target_zone", buildJsonObject {
                    put("type", "string")
                    put("description", "归属目标(仅 assign): 工具区(id/名/路径)、\"${ZoneOps.TARGET_TOP_LEVEL}\"、或 \"${ZoneOps.TARGET_AUTO}\"(交回自动归类)")
                })
            },
            required = listOf("action")
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val action = obj["action"]?.jsonPrimitive?.content?.trim()?.lowercase() ?: error("action required")
        val zoneAddr = obj["zone"]?.jsonPrimitive?.content?.trim()
        val name = obj["name"]?.jsonPrimitive?.content
        val parent = obj["parent"]?.jsonPrimitive?.content?.trim()
        val displayName = obj["display_name"]?.jsonPrimitive?.content
        val description = obj["description"]?.jsonPrimitive?.content
        val keywords = obj["keywords"]?.jsonArray?.map { it.jsonPrimitive.content }
        val toolNameRaw = obj["tool_name"]?.jsonPrimitive?.content?.trim()
        val targetZone = obj["target_zone"]?.jsonPrimitive?.content?.trim()

        val msg: String = when (action) {
            "create" -> {
                val zoneName = (name ?: zoneAddr).orEmpty().trim()
                runZoneOp(settingsStore) { s ->
                    ZoneOps.create(
                        s,
                        name = zoneName,
                        parentId = parent,
                        title = displayName.orEmpty(),
                        description = description.orEmpty(),
                        keywords = keywords.orEmpty(),
                    )
                }
            }

            "update" -> {
                if (zoneAddr.isNullOrBlank()) "update 需要 zone（目标工具区）"
                else if (name == null && displayName == null && description == null && keywords == null) {
                    "update 没给要改的字段：name / display_name / description / keywords 至少一个"
                } else runZoneOp(settingsStore) { s ->
                    val id = ZoneTree(s.toolZones).resolve(zoneAddr)
                        ?: return@runZoneOp ZoneOps.Res(s, unknownZone(s, zoneAddr))
                    ZoneOps.update(s, id, name = name, title = displayName, description = description, keywords = keywords)
                }
            }

            "move" -> {
                if (zoneAddr.isNullOrBlank()) "move 需要 zone（目标工具区）"
                else runZoneOp(settingsStore) { s ->
                    val id = ZoneTree(s.toolZones).resolve(zoneAddr)
                        ?: return@runZoneOp ZoneOps.Res(s, unknownZone(s, zoneAddr))
                    ZoneOps.update(s, id, name = name, parentId = parent, move = true)
                }
            }

            "delete" -> {
                if (zoneAddr.isNullOrBlank()) "delete 需要 zone（目标工具区）"
                else runZoneOp(settingsStore) { s ->
                    val id = ZoneTree(s.toolZones).resolve(zoneAddr)
                        ?: return@runZoneOp ZoneOps.Res(s, unknownZone(s, zoneAddr))
                    ZoneOps.delete(s, id)
                }
            }

            "assign" -> {
                val pool = toolPoolProvider()
                val names = pool.map { it.name }.toSet()
                val raw = toolNameRaw.orEmpty()
                // 裸技能名 → 与 SkillManager 同一套净化，得到**完整工具名**（唯一归属键）
                val tool = when {
                    raw in names -> raw
                    else -> sanitizeSkillToolName(raw).takeIf { it in names } ?: raw
                }
                val target = targetZone.orEmpty()
                when {
                    raw.isBlank() || target.isBlank() -> "assign 需要 tool_name 与 target_zone"
                    tool !in names -> buildString {
                        append("工具「$raw」不存在于当前工具池。")
                        val skills = pool.filter { it.name.startsWith("skill__") }.map { it.name.removePrefix("skill__") }
                        if (skills.isNotEmpty()) append("已启用技能：${skills.sorted().take(20).joinToString("、")}。")
                        append("可先调 invoke_tools(\"帮助\") 查看可用工具。")
                    }
                    else -> runZoneOp(settingsStore) { s -> ZoneOps.assign(s, tool, target) }
                }
            }

            else -> "未知 action: $action。支持: create / update / move / delete / assign"
        }
        me.rerere.rikkahub.data.ai.CallTracer.event("OK", "manage_zone", "$action → ${msg.take(120)}")
        listOf(UIMessagePart.Text(msg))
    },
)

/** 未命中/多义时的可操作提示（候选清单来自区树，绝不猜） */
private fun unknownZone(s: Settings, addr: String): String {
    val tree = ZoneTree(s.toolZones)
    val cands = tree.resolveCandidates(addr)
    return if (cands.size > 1) {
        "「$addr」有 ${cands.size} 个候选，请用完整路径指定其一：" + cands.joinToString("、") { tree.label(it) }
    } else {
        "工具区「$addr」不存在。可用根区：" + tree.roots().joinToString("、") { tree.label(it) } +
            "(可用 invoke_tools(\"帮助\") 查看全部)"
    }
}
