/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

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
    return pool.map { ToolPreview(it.name, it.description) }
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
