package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.tools.routing.DEFAULT_TOP_LEVEL_TOOLS
import me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter
import me.rerere.rikkahub.data.ai.tools.routing.mergeBuiltinZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★v5.3.72：顶层工具面契约。
 *
 * 这一轮收敛的两个重复源：
 *  1. `workspace_search`（统一入口）与 `workspace_grep` / `workspace_glob` 三份并存；
 *  2. 工作区 8 个工具被 `workspace_` 前缀误归「系统」，而「系统」应是 manage_zone/MCP/插件。
 */
class ToolSurfaceTest {

    @Test
    fun `top level exposes one search entry and no duplicate admin tools`() {
        assertTrue("workspace_search" in DEFAULT_TOP_LEVEL_TOOLS)
        assertFalse("workspace_grep" in DEFAULT_TOP_LEVEL_TOOLS)
        assertFalse("workspace_glob" in DEFAULT_TOP_LEVEL_TOOLS)
        assertFalse("manage_mcp_servers" in DEFAULT_TOP_LEVEL_TOOLS)
        assertFalse("plugin_install" in DEFAULT_TOP_LEVEL_TOOLS)
    }

    @Test
    fun `workspace tools live under file control not system`() {
        val router = ZoneRouter(zones = mergeBuiltinZones(emptyList()))

        assertEquals("文件控制/读写", router.classify("workspace_read_file", ""))
        assertEquals("文件控制/读写", router.classify("workspace_write_file", ""))
        assertEquals("文件控制/读写", router.classify("workspace_edit_file", ""))
        assertEquals("文件控制/浏览", router.classify("workspace_show_file", ""))
        assertEquals("文件控制/搜索", router.classify("workspace_search", ""))
        assertEquals("文件控制", router.classify("workspace_shell", ""))
        assertEquals("文件控制", router.classify("workspace_job", ""))
    }

    @Test
    fun `admin tools remain in the system zone`() {
        val router = ZoneRouter(zones = mergeBuiltinZones(emptyList()))
        assertEquals("系统", router.classify("manage_mcp_servers", ""))
        assertEquals("系统", router.classify("plugin_install", ""))
        assertEquals("系统", router.classify("manage_zone", ""))
    }
}
