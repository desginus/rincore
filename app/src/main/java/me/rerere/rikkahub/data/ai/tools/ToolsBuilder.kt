/**
 * 工具池构建 — 全信源统一 (v3.5.41)
 *
 * 用户要求: 客户端计数器 / 工具矩阵管理 / 模型侧工具池 / Invoke Tools /
 * List Domains / 工具返回结果 全部同源。
 *
 * 此前: 域管理页 buildPreviewTools 为硬编码列表 (漏 search/conversation/
 * workspace 条件工具 + 生态/动态), 与模型侧 tools 数组差约 48 个 → 三套计数
 * (446/350+/398) 互不一致。
 *
 * 本函数为唯一工具池构建入口: ChatService (模型侧) 与 设置页工具矩阵/工具列表
 * (UI 预览) 共用, 输出完全一致 (配置驱动, 无运行时状态 — 缓存安全)。
 */
package me.rerere.rikkahub.data.ai.tools


/* ───【域 C·工具系统】ToolsBuilder.kt
 * 职责: 工具池构建 (全信源统一 — 客户端计数器/域管理/模型侧同源)
 * 常用改动: 工具注册 → buildPreviewTools; 域过滤 → filter
 * 问题定位: 工具计数不一致/工具不出现 → 本文件
 * 基线: 自研 (v3.5.41 统一) | 地图: docs/APP_MAP.md §C | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.ai.tools.createReadImageTool
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.routing.DEFAULT_TOP_LEVEL_TOOLS
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

/**
 * 顶层直连工具 —— 始终注入请求体、不参与工具区归类与统计的集合。
 *
 * v4.8.83 重写（取代旧的"框架工具 + 移出域管理 + 移进域"三套集合）:
 *   实际生效集合 = 出厂模板 `DEFAULT_TOP_LEVEL_TOOLS` + 用户提升 − 用户降级。
 * 模板是常量，所以后续版本新增的顶层工具会自动出现在老用户设备上；用户的显式
 * 提升/降级以小集合形式叠加 —— 一个开关，一个含义，注入链直接照此执行。
 */
fun topLevelToolSetOf(settings: Settings): Set<String> =
    (DEFAULT_TOP_LEVEL_TOOLS + settings.topLevelAdditions) - settings.topLevelRemovals

/** 视图工具池 — 全量池排除顶层直连工具（帮助 / 工具矩阵地图 / 列表 / 对照页 同口径） */
fun viewPoolOf(settings: Settings, pool: List<Tool>): List<Tool> =
    pool.filter { it.name !in topLevelToolSetOf(settings) }

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
    workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
    pluginManager: me.rerere.rikkahub.data.plugin.PluginManager? = null,
    filesRoot: java.io.File? = null,                // v4.3.7: read_image 白名单根 (context.filesDir), null=不注入
    operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider? = null, // v4.5.29 岔路口计划·阶段2: Operit 脚本工具
): List<Tool> = buildList {
    // v4.3.7: 图片预算闭环件 — 占位图按需重取 (默认 null 不注入, 仅 ChatService 主链路注入)
    filesRoot?.let { add(createReadImageTool(it)) }
    // v3.11.25: 任务清单工具 (Cherry Studio Agent 任务功能移植) — 框架工具, 静态
    add(createTaskTool())
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
    // workspace 工具: 配置驱动 (workspaceId 非空即注入) — 模型侧与 UI 侧
    // 完全一致 (v3.5.44 信源统一补漏: 此前由调用方注入, UI 侧缺失 → 总数差)
    if (assistant.workspaceId != null && workspaceRepository != null) {
        addAll(createWorkspaceToolsStatic(assistant.workspaceId.toString(), workspaceCwd, workspaceRepository))
    }
    addAll(workspaceTools)
    // 多生态系统指令工具
    addAll(me.rerere.rikkahub.ecosystem.EcosystemManager.getEnabledTools())
    // 动态工具 (MCP 连接 / Marketplace 安装)
    addAll(extraDynamicTools ?: me.rerere.rikkahub.ecosystem.tools.DynamicTools.all())
    // 技能全量 (v3.5.45): 全部已安装 Skill 生成独立工具 — 不按 enabledSkills 过滤,
    // 技能域/挂载域/帮助/对照 口径完全一致
    runCatching {
        val allSkills = skillManager.listSkills()
        if (allSkills.isNotEmpty()) {
            addAll(
                createSkillTools(
                    allSkills = allSkills,
                    // v3.10.4: 新助手按 enabledSkills 过滤 (默认空=不加载技能工具);
                    // 存量助手 (filterSkills=false) 全量兼容, 不破坏现有可用性
                    enabledSkills = if (assistant.filterSkills) assistant.enabledSkills else null,
                )
            )
        }
    }
    // v3.6.88: 插件独立系统 — 插件技能工具 plugin__<名>__skill 全量注入
    // (与 Skill 分离, 插件技能经独立工具读取, 插件桥接工具经 MCP 注入)
    runCatching {
        val pluginTools = pluginManager?.createPluginTools() ?: emptyList()
        if (pluginTools.isNotEmpty()) addAll(pluginTools)
    }
    // v3.6.112: ClawHub 插件 (ecosystem/plugins) — 插件技能工具
    // plugin__<插件名>__<技能> 注入 (插件域, 不拆包进技能系统)
    runCatching {
        val clawTools = me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry.createPluginSkillTools()
        if (clawTools.isNotEmpty()) addAll(clawTools)
    }
    // v4.5.29 岔路口计划·阶段2: Operit 脚本工具
    // operit__<包>__<工具> 注入 (插件域; 由已启用脚本的 METADATA 生成)
    runCatching {
        val operitTools = operitToolProvider?.createScriptTools() ?: emptyList()
        if (operitTools.isNotEmpty()) addAll(operitTools)
    }
    // 工具矩阵管理工具 — 单一源头: list/search/move 的 execute 实时构建与模型侧完全同源的完整工具池
    if (assistant.useLayeredTools) {
        addAll(createZoneTools(settingsStore) {
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
                workspaceRepository = workspaceRepository,
            )
        })
    }
    // MCP 工具 (静态化声明 — 配置决定)
    addAll(me.rerere.rikkahub.ecosystem.tools.DynamicTools.getMcpTools())
}.distinctBy { it.name }
    .sortedBy { it.name } // v3.5.58: 确定性排序 — 池顺序稳定, 域内工具顺序稳定
