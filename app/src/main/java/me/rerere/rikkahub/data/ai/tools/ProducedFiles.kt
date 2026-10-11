/* 【域 B·AI 工具】 — 本对话产出登记 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * v4.8.121 (用户定版「本对话产出」): 本对话中模型产出/修改过的文件记录。
 *
 * 记录点 = GenerationHandler 工具执行阶段3 (保序记账) 对**写类工作区工具**成功
 * 结果做登记: workspace_write_file / workspace_edit_file。持久化于 Settings
 * (DataStore), 按对话分组, 新→旧排列; 每对话上限 80 条, 全局保留最近 200 个
 * 对话 (超限按每对话最新时间裁剪)。UI = 速览页「本对话产出」胶囊窗列表,
 * 点击经 openWorkspaceLink 直接打开 (FileProvider → 系统应用)。
 */
@Serializable
data class ProducedFile(
    /** 工具写入路径 (通常 /workspace/...; 根沙箱绝对路径) */
    val path: String,
    /** 产出工具名 */
    val tool: String,
    /** 最近一次产出时间 (epoch ms) */
    val atMs: Long,
)

object ProducedFiles {
    private const val MAX_PER_CONVERSATION = 80
    private const val MAX_CONVERSATIONS = 200

    /** 写类工具 → 其参数中的路径键 */
    private val PATH_ARG: Map<String, String> = mapOf(
        "workspace_write_file" to "path",
        "workspace_edit_file" to "path",
    )

    /** 从工具名 + 参数提取产出文件路径; 非写类/无路径返回 null */
    fun extractPath(toolName: String, args: JsonObject?): String? {
        val key = PATH_ARG[toolName] ?: return null
        val raw = (args?.get(key) as? JsonPrimitive)?.content?.trim().orEmpty()
        return raw.ifEmpty { null }
    }

    /** 幂等登记: 同路径去重取最新 (新→旧), 每对话上限 80, 全局保留最近 200 对话 */
    fun record(
        current: Map<String, List<ProducedFile>>,
        conversationId: String,
        toolName: String,
        path: String,
        nowMs: Long,
    ): Map<String, List<ProducedFile>> {
        val existing = current[conversationId].orEmpty()
        val updated = buildList {
            add(ProducedFile(path = path, tool = toolName, atMs = nowMs))
            existing.forEach { if (it.path != path) add(it) }
        }.take(MAX_PER_CONVERSATION)

        val next = current.toMutableMap()
        next[conversationId] = updated
        if (next.size <= MAX_CONVERSATIONS) return next

        return next.entries
            .sortedByDescending { (_, v) -> v.maxOfOrNull { it.atMs } ?: 0L }
            .take(MAX_CONVERSATIONS)
            .associate { it.key to it.value }
    }
}
