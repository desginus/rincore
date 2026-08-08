/**
 * 工具池构建 — 全信源统一 (v3.5.41)
 *
 * 用户要求: 客户端计数器 / 工具域分类管理 / 模型侧工具池 / Invoke Tools /
 * List Domains / 工具返回结果 全部同源。
 *
 * 此前: 域管理页 buildPreviewTools 为硬编码列表 (漏 search/conversation/
 * workspace 条件工具 + 生态/动态), 与模型侧 tools 数组差约 48 个 → 三套计数
 * (446/350+/398) 互不一致。
 *
 * 本函数为唯一工具池构建入口: ChatService (模型侧) 与 SettingDomainPage
 * (UI 预览) 共用, 输出完全一致 (配置驱动, 无运行时状态 — 缓存安全)。
 */
package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/** 全量工具池 — 模型侧与 UI 侧唯一数据源 (配置驱动, 无运行时状态) */
fun buildAssistantToolPool(
    settings: Settings,
    assistant: me.rerere.rikkahub.data.model.Assistant,
    localTools: LocalTools,
    skillManager: SkillManager,
    conversationRepo: ConversationRepository,
    mcpManager: McpManager,
    settingsStore: SettingsStore,
    workspaceTools: List<Tool> = emptyList(),      // 由调用方注入 (suspend 环境查状态)
    extraDynamicTools: List<Tool>? = null,          // null = 默认 DynamicTools.all()
    conversationId: String = "",
    workspaceCwd: String? = null,
): List<Tool> = buildList {
    if (settings.enableWebSearch) {
        addAll(createSearchTools(settings))
    }
    addAll(localTools.getTools(
        assistant.localTools,
        ToolInvocationContext(
            callerAssistantId = assistant.id.toString(),
            callerConversationId = conversationId,
            isHeadless = false,
        ),
    ))
    if (assistant.enableRecentChatsReference) {
        addAll(createConversationTools(conversationRepo, assistant.id))
    }
    addAll(workspaceTools)
    // 多生态系统指令工具
    addAll(me.rerere.rikkahub.ecosystem.EcosystemManager.getEnabledTools())
    // 动态工具 (MCP 连接 / Marketplace 安装)
    addAll(extraDynamicTools ?: me.rerere.rikkahub.ecosystem.tools.DynamicTools.all())
    if (assistant.enabledSkills.isNotEmpty()) {
        addAll(
            createSkillTools(
                enabledSkills = assistant.enabledSkills,
                allSkills = skillManager.listSkills(),
            )
        )
    }
    // AI 域管理工具 — 单一源头: list/search/move 的 execute 实时构建
    // 与模型侧完全同源的完整工具池 (此前 knownToolNames 默认空集 → List
    // Domains 返回 0 / Search Domains 分类空 — v3.5.43 根治)
    if (assistant.useLayeredTools) {
        addAll(createDomainTools(settingsStore) {
            val s = settingsStore.settingsFlow.value
            val a = s.getCurrentAssistant()
            buildAssistantToolPool(
                settings = s,
                assistant = a,
                localTools = localTools,
                skillManager = skillManager,
                conversationRepo = conversationRepo,
                mcpManager = mcpManager,
                settingsStore = settingsStore,
            )
        })
    }
    // MCP 工具 (静态化声明 — 配置决定)
    addAll(me.rerere.rikkahub.ecosystem.tools.DynamicTools.getMcpTools())
}.distinctBy { it.name }
