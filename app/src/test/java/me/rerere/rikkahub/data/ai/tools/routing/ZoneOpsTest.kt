package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5.3.28 —— 锁死「记忆六工具在三处界面显示同一个归属」。
 *
 * 用户实测的三个说法：
 *   · 工具矩阵页 → 「未分类」
 *   · 操作页     → 「系统」
 *   · 移动位置   → 「归为顶层」
 *
 * 三个说法来自三种判据，根因有两层：
 *   1) `ZoneRouter` 直接用 `settings.toolZones`，而它只在升级时被 `ToolZoneMigration`
 *      播种过一次 —— **后加的内置区**（「对话工具/记忆」）永远进不了老用户配置，
 *      于是 `hit()` 返回 null，工具落到 FALLBACK_ZONE_ID（未分类）；
 *   2) 记忆工具还能被「移回顶层」写进 `topLevelAdditions`，而注入链会把它们剔除
 *      （`frameworkTools.filter { it.name !in MEMORY_TOOL_NAMES_ALL }`）→ 设置不生效，
 *      但 `topLevelToolSetOf` 仍把它算作顶层 → 矩阵把它从区里剔掉 → 显示「顶层」。
 *
 * 这些用例确保：**normalize 之后，六工具的归类对所有入口都是同一个答案**。
 */
class ZoneOpsTest {

    @Test
    fun `normalize seeds all builtin zones for a brand new user`() {
        val out = ZoneOps.normalize(Settings())

        assertTrue(
            "新装用户必须拿到「对话工具/记忆」",
            out.toolZones.any { it.id == "对话工具/记忆" },
        )
        assertEquals(
            "六工具必须归到记忆区（不是未分类）",
            "对话工具/记忆",
            ZoneRouter(zones = out.toolZones).classify("memory_read", ""),
        )
    }

    @Test
    fun `normalize restores builtin sub zones missing from an old config`() {
        // 老用户：当年只播过根区，后来新增的子区不在配置里
        val legacy = Settings(
            toolZones = listOf(
                ToolZone("对话工具", "对话工具", null, "", "子代理、记忆、时间", listOf("对话")),
                ToolZone(FALLBACK_ZONE_ID, FALLBACK_ZONE_ID, null, "", "兜底", emptyList()),
            ),
        )
        val out = ZoneOps.normalize(legacy)

        assertTrue(
            "父区存在时，缺失的内置子区必须补回来",
            out.toolZones.any { it.id == "对话工具/记忆" },
        )
        assertEquals(
            "对话工具/记忆",
            ZoneRouter(zones = out.toolZones).classify("memory_list", ""),
        )
    }

    @Test
    fun `normalize does not resurrect a root zone the user deleted`() {
        // 用户删掉整棵「对话工具」根区 —— 这是明确的删除意图
        val s = Settings(
            toolZones = listOf(
                ToolZone(FALLBACK_ZONE_ID, FALLBACK_ZONE_ID, null, "", "兜底", emptyList()),
                ToolZone("搜索", "搜索", null, "", "搜索类", listOf("搜索")),
            ),
        )
        val out = ZoneOps.normalize(s)

        assertFalse(
            "父区不存在时不得自动复活它的子区",
            out.toolZones.any { it.id == "对话工具/记忆" },
        )
    }

    @Test
    fun `normalize strips memory tools from top level residue`() {
        // 用户此前点过「移回顶层」→ topLevelAdditions 里残留（含旧名 memory_tool）
        val s = Settings(
            toolZones = DEFAULT_TOOL_ZONES,
            topLevelAdditions = setOf(MemoryToolNames.READ, "memory_tool", "web_search"),
        )
        val out = ZoneOps.normalize(s)

        assertFalse(
            "记忆工具不得留在顶层（设了也不生效，留着只会让界面显示「顶层」）",
            MemoryToolNames.READ in out.topLevelAdditions,
        )
        assertFalse("旧名也要清", "memory_tool" in out.topLevelAdditions)
        assertTrue("其它工具不受影响", "web_search" in out.topLevelAdditions)
    }

    @Test
    fun `assign rejects memory tools as a fake switch`() {
        val base = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))

        val toTop = ZoneOps.assign(base, MemoryToolNames.WRITE, ZoneOps.TARGET_TOP_LEVEL)
        assertEquals(
            "记忆工具不能被设成顶层（会写入不生效的配置）",
            base.topLevelAdditions,
            toTop.settings.topLevelAdditions,
        )

        val toZone = ZoneOps.assign(base, MemoryToolNames.WRITE, "搜索")
        assertEquals(
            "记忆工具不能被移到别的区",
            base.toolZoneLinks,
            toZone.settings.toolZoneLinks,
        )
    }

    @Test
    fun `all memory tools classify identically across every surface`() {
        val out = ZoneOps.normalize(Settings(toolZones = DEFAULT_TOOL_ZONES))
        val router = ZoneRouter(
            zones = out.toolZones,
            topLevelTools = topLevelToolSetOf(out),
        )
        val topLevel = topLevelToolSetOf(out)

        for (name in MemoryToolNames.ALL) {
            assertEquals(
                "矩阵归类必须一致: $name",
                "对话工具/记忆",
                router.classify(name, ""),
            )
            assertFalse(
                "不得被判为顶层直连: $name",
                name in topLevel,
            )
        }
    }

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
}
