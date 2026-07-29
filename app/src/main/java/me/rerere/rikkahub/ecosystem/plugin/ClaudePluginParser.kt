package me.rerere.rikkahub.ecosystem.plugin

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

/**
 * Claude Code 插件格式解析器 (.claude-plugin/)。
 *
 * 支持:
 * - plugin.json / marketplace.json manifest
 * - skills/ — SKILL.md 技能
 * - commands/ — /command 定义
 * - hooks/ — 事件钩子声明
 * - .mcp.json — MCP 服务器声明
 * - .agents/ — 子代理定义
 */
object ClaudePluginParser {
    private const val TAG = "ClaudePluginParser"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ParsedPlugin(
        val manifest: PluginManifestData,
        val skills: List<SkillDef>,
        val commands: List<CommandDef>,
        val hooks: List<HookDef>,
        val mcpServers: List<McpServerDef>,
        val agents: List<AgentDef>,
    )

    @Serializable
    data class PluginManifestData(
        val name: String = "",
        val version: String = "0.0.0",
        val description: String = "",
        val author: String = "",
    )

    data class SkillDef(val name: String, val path: String, val content: String)
    data class CommandDef(val name: String, val description: String, val content: String)
    data class HookDef(val event: String, val action: String)
    data class McpServerDef(val name: String, val command: String, val url: String = "", val transport: String = "stdio")
    data class AgentDef(val name: String, val description: String, val config: JsonObject)

    /**
     * 解析一个 .claude-plugin/ 或 plugins/<name>/ 目录。
     */
    fun parsePluginDir(dir: File): ParsedPlugin {
        val manifest = readManifest(dir)
        val skills = parseSkills(dir)
        val commands = parseCommands(dir)
        val hooks = parseHooks(dir)
        val mcpServers = parseMcpJson(dir)
        val agents = parseAgents(dir)

        return ParsedPlugin(manifest, skills, commands, hooks, mcpServers, agents)
    }

    /**
     * 从 ZIP 文件解压并解析插件。
     */
    fun parsePluginZip(zipFile: File): Pair<String, ParsedPlugin>? {
        val pluginName = zipFile.nameWithoutExtension
        val tempDir = File(zipFile.parent, "_plugin_extract_$pluginName")
        tempDir.mkdirs()
        try {
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val target = File(tempDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            // 查找真正的插件根目录 (可能有嵌套)
            val pluginDir = findPluginRoot(tempDir) ?: tempDir
            val parsed = parsePluginDir(pluginDir)
            return Pair(pluginName, parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plugin ZIP: ${e.message}")
            return null
        }
    }

    private fun findPluginRoot(dir: File): File? {
        // 如果当前目录就有 plugin.json，就是根
        if (File(dir, "plugin.json").isFile || File(dir, "marketplace.json").isFile) return dir
        // 否则检查子目录
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findPluginRoot(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun readManifest(dir: File): PluginManifestData {
        val candidates = listOf("plugin.json", "marketplace.json", "manifest.json")
        for (name in candidates) {
            val file = File(dir, name)
            if (file.isFile) {
                return try {
                    json.decodeFromString<PluginManifestData>(file.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Manifest parse warning: ${e.message}")
                    PluginManifestData(name = dir.name)
                }
            }
        }
        return PluginManifestData(name = dir.name)
    }

    private fun parseSkills(dir: File): List<SkillDef> {
        val skillsDir = File(dir, "skills")
            .takeIf { it.isDirectory } ?: return emptyList()
        return skillsDir.listFiles()
            ?.filter { skillDir ->
                skillDir.isDirectory && File(skillDir, "SKILL.md").isFile
            }
            ?.map { skillDir ->
                val content = File(skillDir, "SKILL.md").readText()
                SkillDef(skillDir.name, skillDir.absolutePath, content)
            }
            ?: emptyList()
    }

    private fun parseCommands(dir: File): List<CommandDef> {
        val cmdsDir = File(dir, "commands")
            .takeIf { it.isDirectory } ?: return emptyList()
        return cmdsDir.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.map { file ->
                val content = file.readText()
                val desc = content.lines().firstOrNull { it.isNotBlank() }
                    ?.removePrefix("#")?.trim()?.take(80) ?: file.nameWithoutExtension
                CommandDef(file.nameWithoutExtension, desc, content)
            }
            ?: emptyList()
    }

    private fun parseHooks(dir: File): List<HookDef> {
        val hooksDir = File(dir, "hooks")
            .takeIf { it.isDirectory } ?: return emptyList()
        return hooksDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.flatMap { file ->
                try {
                    val arr = json.decodeFromString<List<JsonObject>>(file.readText())
                    arr.mapNotNull { obj ->
                        val event = obj["event"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val action = obj["action"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        HookDef(event, action)
                    }
                } catch (_: Exception) { emptyList() }
            }
            ?: emptyList()
    }

    fun parseMcpJson(dir: File): List<McpServerDef> {
        val mcpFile = File(dir, ".mcp.json")
            .takeIf { it.isFile } ?: return emptyList()
        return try {
            val obj = json.decodeFromString<JsonObject>(mcpFile.readText())
            val servers = obj["mcpServers"]?.jsonObject ?: return emptyList()
            servers.entries.map { (name, value) ->
                val cfg = value.jsonObject
                McpServerDef(
                    name = name,
                    command = cfg["command"]?.jsonPrimitive?.content ?: "",
                    url = cfg["url"]?.jsonPrimitive?.content ?: "",
                    transport = cfg["transport"]?.jsonPrimitive?.content ?: "stdio",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse .mcp.json: ${e.message}")
            emptyList()
        }
    }

    private fun parseAgents(dir: File): List<AgentDef> {
        val agentsDir = File(dir, ".agents")
            .takeIf { it.isDirectory } ?: return emptyList()
        return agentsDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val obj = json.decodeFromString<JsonObject>(file.readText())
                    AgentDef(
                        name = file.nameWithoutExtension,
                        description = obj["description"]?.jsonPrimitive?.content ?: "",
                        config = obj,
                    )
                } catch (_: Exception) { null }
            }
            ?: emptyList()
    }
}
