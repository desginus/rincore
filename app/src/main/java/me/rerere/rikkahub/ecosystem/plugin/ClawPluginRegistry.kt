package me.rerere.rikkahub.ecosystem.plugin


/* ───【自研】PluginManager.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

object ClawPluginRegistry {
    private const val TAG = "PluginManager"
    private var context: Context? = null
    private var pluginsDir: File? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    fun initialize(ctx: Context) {
        context = ctx.applicationContext
        pluginsDir = File(ctx.filesDir, "ecosystem/plugins")
        pluginsDir?.mkdirs()
        refresh()
    }

    fun refresh() {
        val dir = pluginsDir ?: return
        val result = mutableListOf<PluginInfo>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
            val manifest = readManifest(pluginDir)
            if (manifest != null) {
                result.add(PluginInfo(manifest, pluginDir))
            }
        }
        _plugins.value = result
        Log.i(TAG, "Loaded " + result.size + " plugins")
    }

    private fun readManifest(dir: File): PluginManifest? {
        // v3.6.110: 加 .claude-plugin/plugin.json — Claude Code 标准布局
        // (此前不支持, 插件页看不到 plugin_install 装的插件)
        val candidates = listOf(
            "plugin.json",
            ".claude-plugin/plugin.json",
            "marketplace.json",
            "manifest.json",
            "_parsed.json",
        )
        for (name in candidates) {
            val file = File(dir, name)
            if (file.isFile) {
                return try {
                    json.decodeFromString<PluginManifest>(file.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse: " + file.absolutePath)
                    null
                }
            }
        }
        return null
    }

    fun installFromZip(zipFile: File): String? {
        val dir = pluginsDir ?: return "not initialized"
        try {
            val pluginName = zipFile.nameWithoutExtension
            val targetDir = File(dir, pluginName)
            targetDir.mkdirs()
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        entryFile.outputStream().use { os -> zis.copyTo(os) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            refresh()
            return pluginName
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: " + e.message)
            return null
        }
    }

    fun getSkillRoots(): List<File> {
        return getSkillRootsWithNames().map { it.second }
    }

    /** v3.6.105: 插件名 → skills 根 (技能名前缀用 manifest 名, 不用临时下载目录名) */
    fun getSkillRootsWithNames(): List<Pair<String, File>> {
        val dir = pluginsDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory && File(it, "skills").isDirectory }
            ?.map { pluginDir ->
                val manifestName = readManifest(pluginDir)?.name?.takeIf { it.isNotBlank() } ?: pluginDir.name
                manifestName to File(pluginDir, "skills")
            }
            ?: emptyList()
    }

@Serializable
data class PluginManifest(
    val name: String = "",
    val version: String = "0.0.0",
    val description: String = "",
    val author: String = "",
    val repository: String = "",
)

data class PluginInfo(
    val manifest: PluginManifest,
    val directory: File,
)

    fun installFromParsed(pluginName: String, parsed: ClaudePluginParser.ParsedPlugin, targetDir: File) {
        targetDir.mkdirs()
        // v3.6.116: 写真实 plugin.json (完整元数据) — 修复: 此前只写 _parsed.json
        // (不含 name/description/version 的完整清单), readManifest 不认 → 插件
        // 永远进不了列表 → 设置页插件列表空
        runCatching {
            File(targetDir, "plugin.json").writeText(
                json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    kotlinx.serialization.json.buildJsonObject {
                        put("name", parsed.manifest.name.ifBlank { pluginName })
                        put("version", parsed.manifest.version.ifBlank { "0.0.0" })
                        put("description", parsed.manifest.description)
                        put("skillCount", parsed.skills.size)
                        put("commandCount", parsed.commands.size)
                        put("mcpCount", parsed.mcpServers.size)
                    },
                )
            )
        }
        refresh()
    }

    /** v3.6.110: 同步插件技能根到技能系统 (前缀 <插件名>__, 与其他 Skill 隔离) */
    fun syncSkillRoots(skillManager: me.rerere.rikkahub.data.files.SkillManager) {
        runCatching {
            val clawRoots = getSkillRootsWithNames().map { (pluginName, skillsDir) ->
                me.rerere.rikkahub.data.files.SkillManager.ExtraSkillRoot(
                    prefix = "${pluginName.replace(Regex("[^a-zA-Z0-9_-]"), "_")}__",
                    root = skillsDir,
                )
            }
            val others = skillManager.extraRootsSnapshot()
                .filter { root -> clawRoots.none { it.prefix == root.prefix } }
            skillManager.setExtraSkillRoots(others + clawRoots)
            Log.i(TAG, "claw plugin skill roots synced: ${clawRoots.size}")
        }.onFailure { Log.e(TAG, "sync skill roots failed", it) }
    }

    /** v3.6.110: 迁移 v3.6.109 误落 /skills 的插件技能 (前缀目录移回插件目录) */
    fun migrateLegacySkills(skillsDir: File) {
        runCatching {
            getSkillRootsWithNames().forEach { (pluginName, pluginSkillsRoot) ->
                val prefix = "${pluginName.replace(Regex("[^a-zA-Z0-9_-]"), "_")}__"
                skillsDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith(prefix) }
                    ?.forEach { legacy ->
                        val target = File(pluginSkillsRoot, legacy.name.removePrefix(prefix))
                        if (!target.exists()) {
                            legacy.copyRecursively(target, overwrite = false)
                            legacy.deleteRecursively()
                            Log.i(TAG, "migrated legacy skill ${legacy.name} -> plugin dir")
                        }
                    }
            }
        }.onFailure { Log.e(TAG, "migrate legacy skills failed", it) }
    }

    // v3.6.112: 插件身份保留 — 插件技能经 plugin__<插件名>__<技能> 工具读取
    // (插件域), 不再拆包成 skill__ 工具混入技能系统
    private val pluginSkillToolsCache = mutableMapOf<String, List<me.rerere.ai.core.Tool>>()

    fun createPluginSkillTools(): List<me.rerere.ai.core.Tool> {
        val result = mutableListOf<me.rerere.ai.core.Tool>()
        for ((pluginName, skillsRoot) in getSkillRootsWithNames()) {
            val safePluginName = pluginName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            skillsRoot.listFiles()
                ?.filter { it.isDirectory && File(it, "SKILL.md").isFile }
                ?.forEach { skillDir ->
                    val skillName = skillDir.name
                    val toolName = "plugin__${safePluginName}__${skillName}"
                    val skillFile = File(skillDir, "SKILL.md")
                    result.add(
                        me.rerere.ai.core.Tool(
                            name = toolName,
                            description = runCatching {
                                me.rerere.rikkahub.data.files.SkillFrontmatterParser
                                    .parse(skillFile.readText())["description"] ?: ""
                            }.getOrDefault("插件技能: $skillName (来自插件 $pluginName)"),
                            parameters = {
                                me.rerere.ai.core.InputSchema.Obj(
                                    properties = buildJsonObject {
                                        put(
                                            "path",
                                            buildJsonObject {
                                                put("type", "string")
                                                put("description", "技能目录内的相对文件路径 (可选, 留空返回 SKILL.md 正文)")
                                            },
                                        )
                                    },
                                    required = emptyList(),
                                )
                            },
                            execute = { input ->
                                // v3.6.118: 删除后工具表残留时友好提示 (不再 FileNotFound)
                                if (!skillFile.exists()) {
                                    return@Tool listOf(me.rerere.ai.ui.UIMessagePart.Text(
                                        "插件技能已删除: $toolName (插件 $pluginName 已被移除)"
                                    ))
                                }
                                val obj = input as? JsonObject
                                val path = obj?.get("path")?.jsonPrimitive?.content
                                val body = if (path.isNullOrBlank()) {
                                    me.rerere.rikkahub.data.files.SkillFrontmatterParser.extractBody(skillFile.readText())
                                } else {
                                    val target = me.rerere.rikkahub.data.files.SkillPaths.resolveSkillFile(skillDir, path)
                                        ?: return@Tool listOf(me.rerere.ai.ui.UIMessagePart.Text("Path '$path' is outside the skill directory"))
                                    target.readText()
                                }
                                listOf(me.rerere.ai.ui.UIMessagePart.Text(body))
                            },
                        )
                    )
                }
        }
        return result
    }

    private val registeredBridgeCommands = mutableSetOf<String>()

    /** v3.6.117: 删除插件 (目录删除 + 桥接记录清理 + settings 服务器移除 + 刷新) */
    suspend fun removePlugin(
        pluginName: String,
        settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
    ) {
        val dir = pluginsDir ?: return
        val pluginDir = dir.listFiles()?.find { pluginDir ->
            val m = readManifest(pluginDir)
            (m?.name?.ifBlank { null } ?: pluginDir.name) == pluginName
        } ?: return
        val safeName = pluginName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        registeredBridgeCommands.removeAll { it.startsWith("${safeName}|") }
        settingsStore.update { s ->
            s.copy(
                mcpServers = s.mcpServers.filter { cfg ->
                    !cfg.commonOptions.name.startsWith("plugin__${safeName}")
                },
            )
        }
        pluginDir.deleteRecursively()
        refresh()
        Log.i(TAG, "plugin removed: $pluginName")
    }

    fun getInstalledMcpServers(): List<ClaudePluginParser.McpServerDef> {
        val dir = pluginsDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { ClaudePluginParser.parseMcpJson(it) }
            ?: emptyList()
    }
}
