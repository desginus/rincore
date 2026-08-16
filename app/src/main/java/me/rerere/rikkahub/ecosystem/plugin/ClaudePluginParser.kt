package me.rerere.rikkahub.ecosystem.plugin


/* ───【自研】ClaudePluginParser.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
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
        // v3.6.95: dir 可能是 .claude-plugin 子目录 (findPluginRoot 返回) —
        // Claude Code 标准布局的 skills/commands/hooks 在插件根 (父目录),
        // 解析时把父目录一并作为搜索根, 否则 Skills 恒为 0
        val pluginRoot = dir.parentFile
        val manifest = readManifest(dir)
        val skills = parseSkills(pluginRoot ?: dir)
        val commands = parseCommands(pluginRoot ?: dir)
        val hooks = parseHooks(pluginRoot ?: dir)
        val mcpServers = parseMcpJson(pluginRoot ?: dir)
        val agents = parseAgents(pluginRoot ?: dir)

        return ParsedPlugin(manifest, skills, commands, hooks, mcpServers, agents)
    }

    /**
     * 从 ZIP 文件解压并解析插件。
     */
    fun parsePluginZip(zipFile: File): Pair<String, ParsedPlugin>? {
        // v3.6.94: 文件不存在单独报错 — 此前与"无 manifest"同一错误信息,
        // 沙箱路径不通时被误判为"统一拒绝/未读取文件"
        if (!zipFile.exists()) {
            Log.e(TAG, "ZIP file not found: ${zipFile.absolutePath}")
            throw IllegalStateException("ZIP 文件不存在: ${zipFile.absolutePath} (zipFile 参数须为设备文件系统路径, 沙箱/workspace 路径请改用 url 参数下载)")
        }
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
            // v3.6.93: 无 manifest 的 ZIP 明确判失败 — 此前退回解压根导致
            // "假成功" (任意仓库 ZIP 都报安装成功, 内容恒为空)
            val pluginDir = findPluginRoot(tempDir)
            if (pluginDir == null) {
                Log.e(TAG, "Not a valid plugin ZIP: no plugin.json/marketplace.json found in ${zipFile.name}")
                throw IllegalStateException("不是有效的插件包: ${zipFile.name} 内未找到 plugin.json / .claude-plugin/plugin.json / marketplace.json")
            }
            val parsed = parsePluginDir(pluginDir)
            return Pair(pluginName, parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plugin ZIP: ${e.message}")
            return null
        } finally {
            // v3.6.118: 清理临时解压目录 (此前每次安装残留 ~500 项)
            runCatching { tempDir.deleteRecursively() }
        }
    }

    private fun findPluginRoot(dir: File): File? {
        // 当前目录
        if (File(dir, "plugin.json").isFile || File(dir, "marketplace.json").isFile) return dir
        // .claude-plugin/ 子目录 (Claude Code 标准布局)
        val cdir = File(dir, ".claude-plugin")
        if (cdir.isDirectory && (File(cdir, "plugin.json").isFile || File(cdir, "marketplace.json").isFile)) return cdir
        // 递归子目录
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) { val f = findPluginRoot(child); if (f != null) return f }
        }
        return null
    }

    private fun readManifest(dir: File): PluginManifestData {
        val candidates = listOf("plugin.json", "marketplace.json", "manifest.json")
        // 优先检查 .claude-plugin/ 子目录
        val searchDirs = listOfNotNull(dir, File(dir, ".claude-plugin").takeIf { it.isDirectory })
        for (sd in searchDirs) {
            for (name in candidates) {
                val file = File(sd, name)
                if (file.isFile) {
                    return try { json.decodeFromString<PluginManifestData>(file.readText()) }
                    catch (e: Exception) { PluginManifestData(name = sd.name) }
                }
            }
        }
        return PluginManifestData(name = dir.name)
    }

    private fun parseSkills(dir: File): List<SkillDef> {
        // 支持三种布局: skills/ | .claude-plugin/skills/ | plugins/<name>/skills/
        val searchRoots = mutableListOf(dir)
        File(dir, ".claude-plugin").takeIf { it.isDirectory }?.let { searchRoots.add(it) }
        File(dir, "plugins").takeIf { it.isDirectory }?.listFiles()?.filter { it.isDirectory }?.let { searchRoots.addAll(it) }

        return searchRoots.flatMap { root ->
            File(root, "skills").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.map { SkillDef(it.name, it.absolutePath, File(it, "SKILL.md").readText()) }
                ?: emptyList()
        }.distinctBy { it.name }
    }

    private fun parseCommands(dir: File): List<CommandDef> {
        val roots = listOfNotNull(dir, File(dir, ".claude-plugin").takeIf { it.isDirectory })
        return roots.flatMap { root ->
            File(root, "commands").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isFile && it.extension == "md" }
                ?.map { file ->
                    val ct = file.readText()
                    val desc = ct.lines().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim()?.take(80) ?: file.nameWithoutExtension
                    CommandDef(file.nameWithoutExtension, desc, ct)
                } ?: emptyList()
        }.distinctBy { it.name }
    }

    private fun parseHooks(dir: File): List<HookDef> {
        val roots = listOfNotNull(dir, File(dir, ".claude-plugin").takeIf { it.isDirectory })
        return roots.flatMap { root ->
            File(root, "hooks").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.flatMap { file ->
                    try {
                        json.decodeFromString<List<JsonObject>>(file.readText()).mapNotNull { obj ->
                            HookDef(obj["event"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                                    obj["action"]?.jsonPrimitive?.content ?: return@mapNotNull null)
                        }
                    } catch (_: Exception) { emptyList() }
                } ?: emptyList()
        }
    }

    fun parseMcpJson(dir: File): List<McpServerDef> {
        val roots = listOfNotNull(dir, File(dir, ".claude-plugin").takeIf { it.isDirectory })
        for (root in roots) {
            val mcpFile = File(root, ".mcp.json").takeIf { it.isFile } ?: continue
            return try {
                val obj = json.decodeFromString<JsonObject>(mcpFile.readText())
                (obj["mcpServers"]?.jsonObject ?: return emptyList()).entries.map { (name, value) ->
                    val cfg = value.jsonObject
                    McpServerDef(name,
                        cfg["command"]?.jsonPrimitive?.content ?: "",
                        cfg["url"]?.jsonPrimitive?.content ?: "",
                        cfg["transport"]?.jsonPrimitive?.content ?: "stdio")
                }
            } catch (e: Exception) { emptyList() }
        }
        return emptyList()
    }

    private fun parseAgents(dir: File): List<AgentDef> {
        val roots = listOfNotNull(dir, File(dir, ".claude-plugin").takeIf { it.isDirectory })
        return roots.flatMap { root ->
            File(root, ".agents").takeIf { it.isDirectory }?.listFiles()
                ?.filter { it.isFile && it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val obj = json.decodeFromString<JsonObject>(file.readText())
                        AgentDef(file.nameWithoutExtension, obj["description"]?.jsonPrimitive?.content ?: "", obj)
                    } catch (_: Exception) { null }
                } ?: emptyList()
        }
    }
}
