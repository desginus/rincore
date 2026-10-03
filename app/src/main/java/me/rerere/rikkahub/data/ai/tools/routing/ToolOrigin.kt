/* 【域 C·工具系统】 — 工具来源标签 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

/**
 * v4.8.92: 工具来源 —— Skill / MCP / 插件 / 系统 / 本地 的**唯一定义**。
 *
 * 背景（用户反馈）：「工具矩阵里每个区都显示工具数，但看不出那些条目是技能还是 MCP，
 * Skill 和 MCP 没有统一」。本文件把"来源判定"收口成一个函数：矩阵 UI、/@ 选择器、
 * 工具列表等一切展示口径都调它 —— 同一种标签体系，技能与 MCP 从此在界面上等价可见。
 */
enum class ToolOrigin(val label: String) {
    SKILL("技能"),
    MCP("MCP"),
    PLUGIN("插件"),
    SYSTEM("系统"),
    LOCAL("本地"),
}

/** 由工具名判定来源（前缀规则与 ZoneRouter.prefixRules 同源口径）。 */
fun toolOriginOf(name: String): ToolOrigin = when {
    name.startsWith("skill__") || name.startsWith("skill_") -> ToolOrigin.SKILL
    name.startsWith("mcp__plugin__") -> ToolOrigin.PLUGIN
    name.startsWith("mcp__") -> ToolOrigin.MCP
    name.startsWith("plugin__") || name.startsWith("operit__") || name.startsWith("clawhub_") -> ToolOrigin.PLUGIN
    name.startsWith("workspace_") ||
        name in CORE_MATRIX_TOOLS ||
        name in setOf(
            "manage_mcp_servers", "plugin_install", "task_tool",
            "read_image", "upload_fetch", "memory_tool", "ask_user",
        ) -> ToolOrigin.SYSTEM
    else -> ToolOrigin.LOCAL
}
