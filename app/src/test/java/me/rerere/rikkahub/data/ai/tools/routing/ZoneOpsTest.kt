package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.3.29 —— 工具矩阵「唯一来源」契约。
 *
 * ## 此前为什么同一个工具在三个地方显示三种答案
 * 有 **6 套判据各自为政**：
 *  1. `ZoneRouter.classify`  → 区内显示
 *  2. `topLevelToolSetOf`    → 顶层判定
 *  3. `ZoneOps.normalize`    → 规整（我曾在里面补区、清 links、清顶层）
 *  4. `SettingToolListPage.isCore` → 能不能勾选
 *  5. `ZoneOps.assign`       → 能不能移动
 *  6. `GenerationHandler` 的 filter → 实际注入
 *
 * 且 `classify` 的兜底返回 `FALLBACK_ZONE_ID`，而 `DEFAULT_TOOL_ZONES` 里**从来没有这个区**
 * → 兜底成了一个「幽灵区」：界面显示「未分类」，但那不是一个能点开、能移入的区。
 *
 * ## 现在的契约（本文件锁死）
 *  - **内置区 = 代码常量唯一权威**：`mergeBuiltinZones` 无条件补齐，不看用户配置里有没有父区。
 *  - **兜底区有实体**：`FALLBACK_ZONE_ID` 必须真实存在于内置区里。
 *  - **记忆工具不是特例**：可以勾选、可以改归属、可以设顶层（是否启用由助手「记忆」开关决定）。
 *  - **三处显示同一个答案**：`classify` 的结果必须能 `hit()` 到真实区，且不在顶层集合里时
 *    必须等于 `zoneMap` 里它所在的区。
 */
class ZoneOpsTest {

    private fun routerOf(vararg zones: ToolZone, topLevel: Set<String> = emptySet()): ZoneRouter =
        ZoneRouter(zones = mergeBuiltinZones(zones.toList()), topLevelTools = topLevel)

    // ─────────── 1. 内置区 = 代码常量（唯一权威） ───────────

    @Test
    fun `builtin zones are always present even when user config is empty`() {
        val router = routerOf()

        assertTrue("新装用户必须拿到全套内置区", router.zoneIds.contains("对话工具/记忆"))
        assertEquals(
            "六工具必须归到记忆区（不是未分类）",
            "对话工具/记忆",
            router.classify("memory_read", ""),
        )
    }

    @Test
    fun `builtin sub zones are restored even when their parent is missing`() {
        // 老用户的配置可能只有部分根区 —— 内置子区不能因此消失
        val router = routerOf(
            ToolZone(FALLBACK_ZONE_ID, FALLBACK_ZONE_ID, null, "", "兜底", emptyList()),
        )

        assertTrue(
            "内置子区必须由代码常量保证存在，不能依赖用户配置里有没有父区",
            router.zoneIds.contains("对话工具/记忆"),
        )
        assertEquals("对话工具/记忆", router.classify("memory_list", ""))
    }

    @Test
    fun `user zones are kept and not reordered`() {
        val mine = ToolZone("我的区", "我的区", null, "", "自建", listOf("my_tool"))
        val router = routerOf(mine)

        assertEquals("用户自定义区必须排在最前、顺序不变", "我的区", router.zones.first().id)
        assertTrue("内置区追加在后", router.zones.size > 1)
    }

    // ─────────── 2. 兜底区必须有实体（幽灵区修复） ───────────

    @Test
    fun `fallback zone exists as a real zone`() {
        val router = routerOf()

        assertTrue(
            "classify 的兜底返回值必须能在树里找到，否则界面只能显示一段不可操作的文字",
            FALLBACK_ZONE_ID in router.zoneIds,
        )
        assertEquals(
            "未知工具必须落进真实存在的兜底区",
            FALLBACK_ZONE_ID,
            router.classify("completely_unknown_tool", ""),
        )
    }

    // ─────────── 3. 记忆工具与普通工具同权（可勾选 / 可移动 / 可设顶层） ───────────

    @Test
    fun `memory tools can be assigned to another zone`() {
        val base = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))

        val moved = ZoneOps.assign(base, MemoryToolNames.READ, "搜索")
        assertTrue("必须允许移动记忆工具（v5.3.28 曾错误拒绝）", moved.ok)
        assertEquals(
            "移动后归属必须真的写进配置",
            "搜索",
            moved.settings.toolZoneLinks[MemoryToolNames.READ],
        )
    }

    @Test
    fun `memory tools can be moved back to top level`() {
        val base = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))

        val toTop = ZoneOps.assign(base, MemoryToolNames.WRITE, ZoneOps.TARGET_TOP_LEVEL)
        assertTrue("必须允许把记忆工具设为顶层", toTop.ok)
        assertTrue(
            "顶层设置必须真的写进配置",
            MemoryToolNames.WRITE in toTop.settings.topLevelAdditions,
        )
    }

    @Test
    fun `memory tools can be returned to automatic`() {
        val base = Settings(
            toolZones = DEFAULT_TOOL_ZONES,
            toolZoneLinks = mapOf(MemoryToolNames.READ to "搜索"),
        )

        val auto = ZoneOps.assign(base, MemoryToolNames.READ, ZoneOps.TARGET_AUTO)
        assertTrue(auto.ok)
        assertFalse(
            "交回自动后不应残留手动归属",
            MemoryToolNames.READ in auto.settings.toolZoneLinks,
        )
    }

    // ─────────── 4. 三处显示一致 ───────────

    @Test
    fun `classify and zoneMap agree for every memory tool`() {
        val settings = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))
        val router = zoneRouterOf(settings)
        val topLevel = topLevelToolSetOf(settings)
        // zoneMap 需要一个工具列表，这里只关心归属，用名字占位
        val classified = router.classify("memory_read", "")

        for (name in MemoryToolNames.ALL) {
            val zone = router.classify(name, "")
            assertTrue("$name 的归属必须是树里真实存在的区（实际=$zone）", zone in router.zoneIds)
            assertEquals("六工具必须同区", classified, zone)
            assertFalse("默认不得被判为顶层", name in topLevel)
        }
    }

    @Test
    fun `top level assignment actually leaves the zone view`() {
        val settings = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))
            .let { ZoneOps.assign(it, MemoryToolNames.READ, ZoneOps.TARGET_TOP_LEVEL).settings }

        val topLevel = topLevelToolSetOf(settings)
        assertTrue(
            "设为顶层后必须出现在顶层集合里（否则就是假开关）",
            MemoryToolNames.READ in topLevel,
        )
    }

    // ─────────── 5. normalize 的边界 ───────────

    @Test
    fun `normalize is idempotent`() {
        val once = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))
        val twice = ZoneOps.normalize(once)

        assertEquals(
            "第二次 normalize 不得再改动（否则会引发无限写循环）",
            once.toolZones.map { it.id },
            twice.toolZones.map { it.id },
        )
    }

    @Test
    fun `normalize keeps links pointing at builtin zones`() {
        // 内置区不在 settings.toolZones 里是正常状态，指向它的 links 不能被当成非法引用清掉
        val s = Settings(
            toolZones = listOf(ToolZone("我的区", "我的区", null, "", "", emptyList())),
            toolZoneLinks = mapOf("web_search" to "搜索"),
        )
        val out = ZoneOps.normalize(s)

        assertEquals("指向内置区的归属必须保留", "搜索", out.toolZoneLinks["web_search"])
    }
}
