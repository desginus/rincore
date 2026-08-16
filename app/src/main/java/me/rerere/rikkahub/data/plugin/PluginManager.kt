package me.rerere.rikkahub.data.plugin

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
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
    private val skillManager: SkillManager,
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
    )

    @Volatile
    private var plugins: List<PluginDef> = emptyList()

    /** 已注册桥接的标识 (插件名 + command) — 重复 refresh 不重启进程 */
    @Volatile
    private var registeredBridges: Set<String> = emptySet()

    /** 扫描并注册 workspace 插件 (启动时调用) */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        runCatching {
            val found = workspaceRepository.getAllWorkspaces().flatMap { ws ->
                val pluginsRoot = File(workspaceManager.filesDir(ws.root), PLUGINS_DIR)
                scanPlugins(pluginsRoot, ws.id, ws.root)
            }
            plugins = found
            // 技能根注入: plugin__ 前缀, 根为 .plugins (SkillManager 扫每个插件目录的 SKILL.md)
            skillManager.setExtraSkillRoots(
                skillManager.extraRootsSnapshot().filter { it.prefix != "plugin__" } +
                    found.map { SkillManager.ExtraSkillRoot("plugin__", it.dir.parentFile ?: it.dir) }
                    .distinct()
            )
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
            PluginDef(name = name, description = description, dir = dir, workspaceId = workspaceId, command = command)
        }.getOrNull()
    }

    private suspend fun ensureBridge(plugin: PluginDef) {
        val bridgeKey = "${plugin.name}|${plugin.workspaceId}|${plugin.command}"
        if (bridgeKey in registeredBridges) return
        // 稳定 id: 插件名 + workspace 的哈希 — 重启后同插件同 id (重连状态/去重稳定)
        val serverId = stableUuid(bridgeKey)
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

    private fun stableUuid(key: String): Uuid {
        val h = key.hashCode()
        val hex = "%08x".format(h)
        return Uuid.fromString("$hex-0000-4000-8000-000000000000")
    }
}
