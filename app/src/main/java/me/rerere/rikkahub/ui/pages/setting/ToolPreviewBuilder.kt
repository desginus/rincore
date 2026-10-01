/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

/** 设置页展示用的工具条目（name + description） */
data class ToolPreview(val name: String, val description: String)

/**
 * 视图工具池 —— 与模型侧完全同源（buildAssistantToolPool），再按 settings 的顶层集合
 * 剔除顶层直连工具。工具矩阵页 / 工具列表 / 对照页 全部消费它，保证「UI 计数 == 模型侧口径」。
 */
fun buildPreviewTools(
    settings: Settings,
    localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools,
    skillManager: me.rerere.rikkahub.data.files.SkillManager,
    mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager,
    conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
    settingsStore: SettingsStore,
    workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
    operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider? = null,
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
        )
    } catch (_: Exception) {
        emptyList()
    }
    val topLevel = topLevelToolSetOf(settings)
    return pool.filter { it.name !in topLevel }.map { ToolPreview(it.name, it.description) }
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
