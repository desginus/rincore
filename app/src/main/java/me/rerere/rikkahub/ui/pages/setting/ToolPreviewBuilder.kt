/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.ai.tools.MemoryToolNames
import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

/** 设置页展示用的工具条目（name + description） */
data class ToolPreview(val name: String, val description: String)

/**
 * 全量工具清单 —— 与模型侧完全同源（buildAssistantToolPool）。
 *
 * v4.8.85 修正：**这里不再过滤顶层工具**。此前在构建处就滤掉顶层工具，导致
 * 「工具矩阵页的顶层直连段」永远为空、「工具列表」里根本看不到框架工具 ——
 * 用户因此"决定不了哪些工具是框架工具"（想移也找不到）。
 *
 * 顶层 / 工具区的切分只有一处：[me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter]
 * （zoneMap 内部按顶层集合剔除并统计）。设置页一律拿全量清单，交给同一处切分。
 */
fun buildToolList(
    settings: Settings,
    localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools,
    skillManager: me.rerere.rikkahub.data.files.SkillManager,
    mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager,
    conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
    settingsStore: SettingsStore,
    workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
    operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider? = null,
    // v4.8.90: 与模型侧同口径 —— 传 filesDir 后 read_image / upload_fetch 也出现在 UI 清单,
    // 否则 UI 工具池比模型侧少这两个工具（对照页与区计数天然对不上）
    filesRoot: java.io.File? = null,
): List<ToolPreview> {
    val assistant = settings.getCurrentAssistant()
    val pool = try {
        buildAssistantToolPool(
            settings = settings,
            assistant = assistant,
            localTools = localTools,
            skillManager = skillManager,
            conversationRepo = conversationRepo,
            mcpManager = mcpManager,
            settingsStore = settingsStore,
            workspaceRepository = workspaceRepository,
            operitToolProvider = operitToolProvider,
            filesRoot = filesRoot,
        )
    } catch (e: Exception) {
        // v5.3.26 ★不再静默：池构建失败时矩阵会只剩硬编码的记忆六工具，
        // 用户看到的是「工具全没了」而不是「构建失败」——必须留痕。
        android.util.Log.w("ToolPreviewBuilder", "buildAssistantToolPool failed; tool list will be partial", e)
        emptyList()
    }
    // ═══ v5.3.25 ★★「工具矩阵里找不到记忆工具」的真因就在这里 ═══
    //
    // 本函数是**工具矩阵 / 工具列表 / 输入框 @ 选择器的唯一数据源**，而它复用的
    // `buildAssistantToolPool` **从不包含记忆六工具** —— 记忆工具是在
    // `GenerationHandler` 里按 `assistant.enableMemory` 单独 addAll 的
    // （grep buildMemoryTools 在 ToolsBuilder.kt 的计数是 0）。
    // 于是模型侧有六个记忆工具，UI 的工具矩阵里一个都没有 → 用户「找不到记忆工具」。
    //
    // 这里按**同一个开关**补上六条预览；归类交给 `ZoneRouter`（v5.3.22 起
    // `name.startsWith("memory_")` → 「对话工具/记忆」区），不需要再单独登记。
    val memoryPreviews = if (assistant.enableMemory) {
        listOf(
            ToolPreview(MemoryToolNames.READ, "Read one or more memory files; returns each file's content and version."),
            ToolPreview(MemoryToolNames.WRITE, "Create a memory file or overwrite it entirely."),
            ToolPreview(MemoryToolNames.STR_REPLACE, "Replace one exact occurrence of text in a memory file."),
            ToolPreview(MemoryToolNames.APPEND, "Append a line to the end of a memory file."),
            ToolPreview(MemoryToolNames.DELETE, "Delete a memory file (soft delete, recoverable)."),
            ToolPreview(MemoryToolNames.LIST, "List memory files with size and last update time."),
        )
    } else {
        emptyList()
    }
    return (pool.map { ToolPreview(it.name, it.description) } + memoryPreviews)
        .distinctBy { it.name }
}

/** ToolPreview → 真实 Tool 壳（仅用于交给 ZoneRouter 归类，不执行） */
fun List<ToolPreview>.asShellTools(): List<me.rerere.ai.core.Tool> = map {
    me.rerere.ai.core.Tool(
        name = it.name,
        description = it.description,
        parameters = { me.rerere.ai.core.InputSchema.Obj(kotlinx.serialization.json.buildJsonObject {}) },
        execute = { listOf(me.rerere.ai.ui.UIMessagePart.Text("")) },
    )
}
