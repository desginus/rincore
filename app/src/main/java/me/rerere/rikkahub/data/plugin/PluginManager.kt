package me.rerere.rikkahub.data.plugin


/* ───【自研】PluginManager.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager

/**
 * 插件管理器 (v3.6.86) — 「插件」新格式。
 *
 * 一个插件 = 一个目录 (workspace files 区 .plugins/<插件名>/)：
 *   - plugin.yaml: 插件声明 (name / description / command)
 *   - SKILL.md: 技能指令 (可选, 与本地 Skill 同格式, 正常读取)
 *   - 桥接脚本: command 指定的进程 (python3/node 等), 经 workspace 沙箱
 *     launchProcess 常驻启动, STDIO 走 MCP JSON-RPC — 与 STDIO MCP 工具同道理
 *
 * 调用插件时: Skill 部分由 skill__plugin__<名> 工具正常读取; 工具部分
 * 由桥接进程 tools/list 返回, 注册为 mcp__plugin__<名>__<工具>。
 */
class PluginManager(
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
    private val mcpManager: McpManager,
) {
    companion object {
        private const val TAG = "PluginManager"
        const val PLUGINS_DIR = ".plugins"
    }

    data class PluginDef(
        val name: String,
        val description: String,
        val dir: File,
        val workspaceId: String,
        val command: String,
        val hasSkill: Boolean,
    )

    @Volatile
    private var plugins: List<PluginDef> = emptyList()

    /** 已注册桥接的标识 (插件名 + command) — 重复 refresh 不重启进程 */
    @Volatile
    private var registeredBridges: Set<String> = emptySet()

    /** 桥接 id 缓存 — 运行期内同插件同 id (重连状态稳定) */
    @Volatile
    private var bridgeIds: Map<String, Uuid> = emptyMap()

    /** 扫描并注册 workspace 插件 (启动时调用) */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching {
            val found = workspaceRepository.getAllWorkspaces().flatMap { ws ->
                val pluginsRoot = File(workspaceManager.filesDir(ws.root), PLUGINS_DIR)
                scanPlugins(pluginsRoot, ws.id, ws.root)
            }
            plugins = found
            // v3.6.88: 插件技能独立系统 — 不再注入 Skill 技能根 (插件技能经
            // plugin__<名>__skill 工具读取, 与 Skill 列表彻底分离)
            // 桥接注册: command 非空的插件经 workspace 启动 MCP
            for (plugin in found) {
                if (plugin.command.isNotBlank()) ensureBridge(plugin)
            }
        }.onFailure {
            Log.e(TAG, "refresh failed: ${it.message}")
        }
    }

    private fun scanPlugins(root: File, workspaceId: String, workspaceRoot: String): List<PluginDef> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()?.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            parsePluginDir(dir, workspaceId)
        } ?: emptyList()
    }

    private fun parsePluginDir(dir: File, workspaceId: String): PluginDef? {
        return runCatching {
            val yamlFile = dir.resolve("plugin.yaml")
            val name = if (yamlFile.exists()) {
                SkillFrontmatterParser.parse(yamlFile.readText())["name"]
                    ?.takeIf { it.isNotBlank() } ?: dir.name
            } else dir.name
            val description = if (yamlFile.exists()) {
                SkillFrontmatterParser.parse(yamlFile.readText())["description"]
                    ?.takeIf { it.isNotBlank() } ?: ""
            } else ""
            val command = if (yamlFile.exists()) {
                SkillFrontmatterParser.parse(yamlFile.readText())["command"]
                    ?.takeIf { it.isNotBlank() } ?: ""
            } else ""
            PluginDef(
                name = name,
                description = description,
                dir = dir,
                workspaceId = workspaceId,
                command = command,
                hasSkill = dir.resolve("SKILL.md").exists(),
            )
        }.getOrNull()
    }

    private suspend fun ensureBridge(plugin: PluginDef) {
        val bridgeKey = "${plugin.name}|${plugin.workspaceId}|${plugin.command}"
        if (bridgeKey in registeredBridges) return
        // 运行期内稳定 id: 首次注册生成并缓存, 重复 refresh 复用
        val serverId = bridgeIds[bridgeKey] ?: Uuid.random().also {
            bridgeIds = bridgeIds + (bridgeKey to it)
        }
        val config = McpServerConfig.StdioTransportServer(
            id = serverId,
            commonOptions = McpCommonOptions(name = "plugin__${plugin.name}", enable = true),
            command = plugin.command,
            viaWorkspace = true,
            workspaceId = plugin.workspaceId,
        )
        runCatching {
            mcpManager.addClient(config)
            registeredBridges = registeredBridges + bridgeKey
            Log.i(TAG, "plugin bridge registered: ${plugin.name} via workspace ${plugin.workspaceId}")
        }.onFailure {
            Log.e(TAG, "plugin bridge failed: ${plugin.name}: ${it.message}")
        }
    }

    fun pluginsSnapshot(): List<PluginDef> = plugins

    /** 插件 UI 信息 (设置页插件列表用) */
    data class PluginUiInfo(
        val name: String,
        val description: String,
        val hasSkill: Boolean,
        val hasBridge: Boolean,
        val bridgeStatus: String,
    )

    fun pluginsUiSnapshot(): List<PluginUiInfo> {
        val ownPlugins = plugins.map { p ->
            val bridgeKey = "${p.name}|${p.workspaceId}|${p.command}"
            val status = if (p.command.isBlank()) {
                "纯技能"
            } else {
                val id = bridgeIds[bridgeKey]
                val st = if (id == null) null else mcpManager.syncingStatus.value[id]
                when (st) {
                    null -> "待注册"
                    is me.rerere.rikkahub.data.ai.mcp.McpStatus.Idle -> "待连接"
                    is me.rerere.rikkahub.data.ai.mcp.McpStatus.Connecting -> "连接中"
                    is me.rerere.rikkahub.data.ai.mcp.McpStatus.Connected -> "已连接"
                    is me.rerere.rikkahub.data.ai.mcp.McpStatus.Reconnecting -> "重连中"
                    is me.rerere.rikkahub.data.ai.mcp.McpStatus.Error -> "错误"
                    is me.rerere.rikkahub.data.ai.mcp.McpStatus.NeedsAuthorization -> "待授权"
                    else -> "未知"
                }
            }
            PluginUiInfo(
                name = p.name,
                description = p.description,
                hasSkill = p.hasSkill,
                hasBridge = p.command.isNotBlank(),
                bridgeStatus = status,
            )
        }
        // v3.6.105: 合并 ClawHub plugin_install 安装的插件 (ecosystem/plugins)
        // — 修复: 安装成功但插件页看不到 (此前只扫 .plugins 格式)
        val clawPlugins = runCatching {
            me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry.plugins.value.map { info ->
                PluginUiInfo(
                    name = info.manifest.name.ifBlank { info.directory.name },
                    description = info.manifest.description,
                    hasSkill = java.io.File(info.directory, "skills").isDirectory,
                    hasBridge = false,
                    bridgeStatus = "已安装（ClawHub）",
                )
            }
        }.getOrDefault(emptyList())
        return (ownPlugins + clawPlugins).distinctBy { it.name }
    }

    /**
     * 插件技能工具 (独立插件系统) — plugin__<名>__skill 读取插件 SKILL.md。
     * 与 Skill 列表完全分离, 不并入技能域。
     */
    fun createPluginTools(): List<Tool> {
        return plugins.filter { it.hasSkill }.map { plugin ->
            Tool(
                name = "plugin__${plugin.name}__skill",
                description = buildString {
                    append("Load the skill instructions of plugin '${plugin.name}'.")
                    if (plugin.description.isNotBlank()) append(" ${plugin.description}")
                },
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("path", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional relative path to a file inside the plugin directory. Omit to read the default SKILL.md instructions."
                                )
                            })
                        },
                        required = emptyList(),
                    )
                },
                execute = { args ->
                    val jsonObject = (args as? kotlinx.serialization.json.JsonObject) ?: kotlinx.serialization.json.buildJsonObject { }
                    val path = jsonObject["path"]?.let {
                        (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                    }?.takeIf { it.isNotBlank() }
                    if (path == null) {
                        val skillFile = plugin.dir.resolve("SKILL.md")
                        require(skillFile.exists()) { "Plugin '${plugin.name}' has no SKILL.md" }
                        listOf(UIMessagePart.Text(SkillFrontmatterParser.extractBody(skillFile.readText())))
                    } else {
                        val target = plugin.dir.resolve(path).canonicalFile
                        require(target.path.startsWith(plugin.dir.canonicalFile.path + "/")) {
                            "Path '$path' is outside the plugin directory"
                        }
                        require(target.exists()) { "File '$path' not found in plugin '${plugin.name}'" }
                        listOf(UIMessagePart.Text(target.readText()))
                    }
                },
            )
        }
    }
}
