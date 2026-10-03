/**
 * 工具矩阵**唯一写入口**（v4.8.87）。
 *
 * 为什么必须只有一处：上一版 UI 与模型各写一套（UI 用 `id.startsWith("$id/")` 找子区、
 * 用 `substringBeforeLast('/')` 找父区；模型那边又是另一套字符串拼接）。两套规则必然漂移，
 * 于是出现"删除回执成功但子区还在""父区不存在却建成了子区""同名不同判"这类假成功。
 *
 * 本文件只做三件事，且全部是**纯函数**（输入 Settings → 输出新 Settings + 回执）：
 *  1. 校验（重名、父区存在、防成环、核心件保护、兜底区保护）
 *  2. 施加（只动该动的字段）
 *  3. 回执（把**结果**说清楚：改了什么、影响谁）
 *
 * 调用方（UI / manage_zone）负责：
 *  · 用 `SettingsStore.updateWithResult` 原子读-改-写；
 *  · 写完**再读一次**校验 [Res.affectedId] 是否真的在位（这才是"真生效"的判据，
 *    回执绝不允许由"意图"生成 —— 假成功就是那么来的）。
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.rikkahub.data.datastore.Settings

object ZoneOps {
    /** assign 的两个特殊目标：顶层直连 / 交回自动归类 */
    const val TARGET_TOP_LEVEL = "顶层"
    const val TARGET_AUTO = "自动"

    /**
     * 操作结果：新设置 + 面向用户的回执 + **写后校验谓词**。
     * 回执绝不允许由"意图"生成 —— 调用方写完必须读回真实设置，用 [verify] 判定是否真的生效；
     * 不生效就如实报告失败（上一版"回执说建好了其实没建"就是缺了这一步）。
     */
    data class Res(
        val settings: Settings,
        val message: String,
        /** 是否真的施加了改动（校验失败时 = false，settings 原样返回） */
        val ok: Boolean = true,
        val verify: ((Settings) -> Boolean)? = null,
    ) {
        /**
         * 事务内串联：两个操作在**同一次**读-改-写里完成（成员函数 —— 任何持有 Res 的地方直接 `acc then { }`）。
         *
         * 为什么必须有它：v4.8.88 之前 UI 把"改归属"和"改描述"拆成两次 `vm.updateSettings(快照)`，
         * 两次都从同一个旧快照出发 —— 后写把前写顶掉，用户看到"弹窗说移好了、实际没动"
         * （假成功根因）。任何"一次动作改多处"都必须用本组合器，禁止拆成多次整快照写。
         */
        infix fun then(next: (Settings) -> Res): Res {
            if (!ok) return this
            val r = next(settings)
            return Res(
                settings = r.settings,
                message = listOf(message, r.message).filter { it.isNotBlank() }.joinToString(" "),
                // ok = 「没有校验失败」。某一步"本来就已是目标状态"（无字段变化）不是失败 ——
                // 否则"移入区+清一个不存在的覆盖"会被误报成未生效（假失败的另一种形态）。
                ok = ok && r.ok,
                verify = { after -> (verify?.invoke(after) ?: true) && (r.verify?.invoke(after) ?: true) },
            )
        }
    }

    /** 校验失败 = 什么都没改（ok=false 是"没生效"的机器判据；回执文案照给） */
    private fun fail(s: Settings, msg: String) = Res(s, msg, ok = false, verify = null)

    // ═══════════ 不变式维护（只修不增） ═══════════

    /**
     * 归一化：把坏数据修成合法数据，**绝不新增工具区**（唯一例外：缺失的兜底区，
     * 它是"任何工具都有归属"这条不变量的载体）。
     * 幂等 —— 合法数据进出一模一样。
     */
    fun normalize(s: Settings): Settings {
        val seen = HashSet<String>()
        val cleaned = ArrayList<ToolZone>(s.toolZones.size)
        for (z in s.toolZones) {
            if (z.id.isBlank() || !seen.add(z.id)) continue
            cleaned.add(z)
        }
        if (!cleaned.any { it.id == FALLBACK_ZONE_ID }) {
            DEFAULT_TOOL_ZONES.firstOrNull { it.id == FALLBACK_ZONE_ID }?.let { cleaned.add(it) }
        }
        // 固定 id → 合法 id 集合；父引用必须存在、不能是自己、不能成环
        val ids = cleaned.mapTo(HashSet()) { it.id }
        val fixed = cleaned.map { z ->
            val name = z.name.ifBlank { z.id.substringAfterLast('/') }.trim()
            var pid = z.parentId?.takeIf { it.isNotBlank() }
            if (pid != null && (pid !in ids || pid == z.id)) pid = null
            // 防环：沿父链上溯，遇到自己即视为根
            var cursor = pid
            var guard = 0
            while (cursor != null && guard++ < 64) {
                if (cursor == z.id) { pid = null; break }
                cursor = cleaned.firstOrNull { it.id == cursor }?.parentId
            }
            z.copy(name = name.ifBlank { z.id.substringAfterLast('/') }, parentId = pid)
        }
        val validIds = fixed.mapTo(HashSet()) { it.id }
        val cleanLinks = s.toolZoneLinks.filterValues { it in validIds }
        val cleanHidden = s.hiddenZones.filter { it in validIds }.toSet()
        return if (fixed == s.toolZones && cleanLinks == s.toolZoneLinks && cleanHidden == s.hiddenZones) s
        else s.copy(toolZones = fixed, toolZoneLinks = cleanLinks, hiddenZones = cleanHidden)
    }

    // ═══════════ 区操作 ═══════════

    fun create(
        s: Settings,
        name: String,
        parentId: String? = null,
        title: String = "",
        description: String = "",
        keywords: List<String> = emptyList(),
    ): Res {
        val tree = ZoneTree(s.toolZones)
        val cleanName = name.trim().trim('/')
        if (cleanName.isBlank()) return fail(s, "创建失败：工具区名不能为空。")
        if ('/' in cleanName) return fail(s, "创建失败：名字里不能带 '/' —— 层级请用 parent 指定；若想建在路径下，请先建好父区。")
        val pid = parentId?.trim()?.takeIf { it.isNotBlank() }?.let { tree.resolve(it) }
        if (parentId != null && parentId.isNotBlank() && pid == null) {
            val cands = tree.resolveCandidates(parentId)
            return fail(
                s,
                if (cands.size > 1) "创建失败：父区「$parentId」有多个候选：" + cands.joinToString("、") { tree.label(it) }
                else "创建失败：父区「$parentId」不存在。可用根区：" + tree.roots().joinToString("、") { tree.label(it) },
            )
        }
        if (tree.nameTaken(pid, cleanName)) {
            val where = pid?.let { "「${tree.label(it)}」下" } ?: "根区"
            return fail(s, "创建失败：$where 已存在名为「$cleanName」的工具区（同级重名）。")
        }
        val zone = ToolZone(
            id = newZoneId(tree.ids),
            name = cleanName,
            parentId = pid,
            title = title.trim(),
            description = description,
            keywords = keywords.filter { it.isNotBlank() },
        )
        val next = s.copy(toolZones = s.toolZones + zone)
        val path = ZoneTree(next.toolZones).pathOf(zone.id)
        return Res(
            next,
            "已创建工具区「$path」。路径由层级派生，随时可改名/移动。",
            ok = next !== s,
            verify = { after -> ZoneTree(after.toolZones).get(zone.id) != null },
        )
    }

    /**
     * 改区：显示名 / 触发描述 / 触发条件 / **名字（改名）** / **父区（移动）**。
     * `move = true` 时 `parentId` 生效（null = 移到根）。
     */
    fun update(
        s: Settings,
        id: String,
        name: String? = null,
        parentId: String? = null,
        move: Boolean = false,
        title: String? = null,
        description: String? = null,
        keywords: List<String>? = null,
    ): Res {
        val tree = ZoneTree(s.toolZones)
        val zone = tree.get(id) ?: return fail(s, "更新失败：工具区不存在（id $id）。")
        val applied = ArrayList<String>(4)

        var newName = zone.name
        if (name != null) {
            val clean = name.trim().trim('/')
            if (clean.isBlank()) return fail(s, "更新失败：名字不能为空。")
            if ('/' in clean) return fail(s, "更新失败：名字里不能带 '/'。")
            val newParent = if (move) parentId else zone.parentId
            if (clean != zone.name && tree.nameTaken(newParent, clean, selfId = zone.id)) {
                return fail(s, "更新失败：同级已存在名为「$clean」的工具区。")
            }
            newName = clean
            applied.add("名字→$clean")
        }

        var newParent = zone.parentId
        if (move) {
            val pid = parentId?.trim()?.takeIf { it.isNotBlank() }?.let { tree.resolve(it) }
            if (parentId != null && parentId.isNotBlank() && pid == null) {
                return fail(s, "移动失败：目标父区「$parentId」不存在。")
            }
            if (pid == zone.id) return fail(s, "移动失败：不能把工具区移到自己下面。")
            if (pid != null && tree.isDescendant(pid, zone.id)) return fail(s, "移动失败：不能移到自己的子孙区下面。")
            if (pid != null && tree.nameTaken(pid, newName, selfId = zone.id)) {
                return fail(s, "移动失败：目标处已有同名工具区「$newName」。")
            }
            newParent = pid
            applied.add(if (pid == null) "移到根区" else "移到「${tree.label(pid)}」")
        }

        if (title != null) applied.add("显示名")
        if (description != null) applied.add("触发描述")
        if (keywords != null) applied.add("触发条件")
        if (applied.isEmpty()) return fail(s, "未提供要修改的字段（name/parent/title/description/keywords 至少一个）。")

        val next = s.copy(
            toolZones = s.toolZones.map { z ->
                if (z.id != id) z else z.copy(
                    name = newName,
                    parentId = newParent,
                    title = title?.trim() ?: z.title,
                    description = description ?: z.description,
                    keywords = keywords?.filter { it.isNotBlank() } ?: z.keywords,
                )
            }
        )
        val newPath = ZoneTree(next.toolZones).pathOf(id)
        val expectName = newName
        val expectParent = newParent
        val expectTitle = title?.trim()
        val expectDesc = description
        val expectKw = keywords?.filter { it.isNotBlank() }
        return Res(
            next,
            "已更新工具区「$newPath」：${applied.joinToString("、")}。（只改传入的字段。）",
            ok = next !== s,
            verify = { after ->
                val z = ZoneTree(after.toolZones).get(id)
                z != null && z.name == expectName && z.parentId == expectParent &&
                    (expectTitle == null || z.title == expectTitle) &&
                    (expectDesc == null || z.description == expectDesc) &&
                    (expectKw == null || z.keywords == expectKw)
            },
        )
    }

    /**
     * 删除**这一个**区（子区自动上移一级，不连带删除 —— 一条规则，可预期）。
     * 手动归属到被删区的工具交回自动归类；隐藏标记一并清除。
     */
    fun delete(s: Settings, id: String): Res {
        val tree = ZoneTree(s.toolZones)
        val zone = tree.get(id) ?: return fail(s, "删除失败：工具区不存在。")
        if (id == FALLBACK_ZONE_ID) return fail(s, "「$FALLBACK_ZONE_ID」是兜底工具区，不能删除（未归类工具的归宿）。")
        val children = tree.childrenOf(id)
        val newParent = zone.parentId?.takeIf { it in tree.ids && it != id }
        var moved = 0
        val next = s.copy(
            toolZones = s.toolZones.filter { it.id != id }.map { z ->
                if (z.parentId == id) { moved++; z.copy(parentId = newParent) } else z
            },
            toolZoneLinks = s.toolZoneLinks.filterValues { it != id },
            hiddenZones = s.hiddenZones - id,
        )
        val where = if (newParent != null) "上移到「${tree.label(newParent)}」" else "上移到根区"
        return Res(
            next,
            buildString {
                append("已删除工具区「${tree.label(id)}」。")
                if (children.isNotEmpty()) append("其 ${children.size} 个子区$where（子区不会被连带删除）。")
                append("归属到该区的手动挂载已交回自动归类。")
            },
            ok = next !== s,
            verify = { after -> ZoneTree(after.toolZones).get(id) == null },
        )
    }

    /**
     * 工具归属：`target` = 工具区（id/名字/路径均可）| [TARGET_TOP_LEVEL] | [TARGET_AUTO]。
     * 核心件（[CORE_MATRIX_TOOLS]）恒在顶层，不接受改归属。
     */
    fun assign(s: Settings, toolName: String, target: String): Res {
        val tool = toolName.trim()
        if (tool.isBlank()) return fail(s, "归属失败：工具名为空。")
        if (tool in CORE_MATRIX_TOOLS) {
            return fail(s, "「$tool」是工具矩阵核心件，必须留在顶层（移出后模型将无法加载任何工具区）。")
        }
        val tree = ZoneTree(s.toolZones)
        val isTop = target.trim() == TARGET_TOP_LEVEL
        val isAuto = target.trim() == TARGET_AUTO
        val zoneId = if (isTop || isAuto) null else tree.resolve(target.trim())
        if (!isTop && !isAuto && zoneId == null) {
            val cands = tree.resolveCandidates(target)
            return Res(
                s,
                if (cands.size > 1) "归属失败：「$target」有多个候选：" + cands.joinToString("、") { tree.label(it) }
                else "归属失败：工具区「$target」不存在。",
            )
        }
        val wasTop = tool in me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf(s)
        val next = s.copy(
            toolZoneLinks = when {
                isAuto -> s.toolZoneLinks - tool
                isTop -> s.toolZoneLinks - tool
                else -> s.toolZoneLinks + (tool to zoneId!!)
            },
            topLevelAdditions = if (isTop) s.topLevelAdditions + tool else s.topLevelAdditions - tool,
            topLevelRemovals = when {
                isTop || isAuto -> s.topLevelRemovals - tool
                wasTop -> s.topLevelRemovals + tool
                else -> s.topLevelRemovals
            },
        )
        val msg = when {
            isTop -> "已把「$tool」移回顶层直连（始终注入请求体）。"
            isAuto -> "已把「$tool」的手动归属清除，交回自动归类。"
            else -> "已把「$tool」归入工具区「${tree.label(zoneId!!)}」（模型经 invoke_tools 加载该区后可见）。"
        }
        return Res(
            next,
            msg,
            ok = next !== s,
            verify = { after ->
                when {
                    isTop -> tool in me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf(after)
                    isAuto -> tool !in after.toolZoneLinks
                    else -> after.toolZoneLinks[tool] == zoneId
                }
            },
        )
    }
    // ═══════════ 事务组合器与矩阵其它写（全部走同一事务口径） ═══════════

    /** 工具描述覆盖（与归属同一事务）；description 空 = 恢复原始描述 */
    fun setToolDescription(s: Settings, tool: String, description: String?): Res {
        val clean = description?.trim()?.takeIf { it.isNotBlank() }
        val map = LinkedHashMap(s.toolDescriptionOverrides)
        if (clean == null) map.remove(tool) else map[tool] = clean
        val next = s.copy(toolDescriptionOverrides = map)
        return Res(
            next,
            when {
                clean == null && tool !in s.toolDescriptionOverrides -> "「$tool」描述本就是原始状态。"
                clean == null -> "已恢复「$tool」的原始描述。"
                else -> "已更新「$tool」的描述。"
            },
            ok = next !== s,
            verify = { after -> after.toolDescriptionOverrides[tool] == clean },
        )
    }

    /** 工具别名覆盖（与归属同一事务）；alias 空 = 清除别名 */
    fun setToolAlias(s: Settings, tool: String, alias: String?): Res {
        val clean = alias?.trim().orEmpty()
        if (clean.isNotEmpty() && !clean.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) {
            return fail(s, "别名不合法（只允许字母/数字/下划线/连字符）：$clean")
        }
        val map = LinkedHashMap(s.toolNameOverrides)
        if (clean.isBlank()) map.remove(tool) else map[tool] = clean
        val next = s.copy(toolNameOverrides = map)
        return Res(
            next,
            when {
                clean.isBlank() && tool !in s.toolNameOverrides -> "「$tool」本就没有别名。"
                clean.isBlank() -> "已清除「$tool」的别名。"
                s.toolNameOverrides[tool] == clean -> "「$tool」别名本来就是 $clean。"
                else -> "已把「$tool」的别名设为 $clean。"
            },
            ok = next !== s,
            verify = { after -> after.toolNameOverrides[tool] == clean.takeIf { it.isNotBlank() } },
        )
    }

    /** 工具区显示/隐藏（隐藏 = 不进地图省 token，仍可加载；与其它矩阵写同一事务口径） */
    fun setHidden(s: Settings, id: String, hidden: Boolean): Res {
        val tree = ZoneTree(s.toolZones)
        if (tree.get(id) == null) return fail(s, "隐藏失败：工具区不存在。")
        val next = s.copy(hiddenZones = if (hidden) s.hiddenZones + id else s.hiddenZones - id)
        return Res(
            next,
            if (hidden) "已隐藏工具区「${tree.label(id)}」（不出现在地图里，仍可加载使用）。"
            else "已取消隐藏「${tree.label(id)}」。",
            ok = next !== s,
            verify = { after -> (id in after.hiddenZones) == hidden },
        )
    }

}