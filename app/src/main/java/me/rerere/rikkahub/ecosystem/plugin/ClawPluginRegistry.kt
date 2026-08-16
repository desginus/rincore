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
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

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
        // 写入解析后的信息
        File(targetDir, "_parsed.json").writeText(
            "{\"name\":\"${parsed.manifest.name}\",\"skills\":${parsed.skills.size}," +
            "\"commands\":${parsed.commands.size},\"mcps\":${parsed.mcpServers.size}}"
        )
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

    fun getInstalledMcpServers(): List<ClaudePluginParser.McpServerDef> {
        val dir = pluginsDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { ClaudePluginParser.parseMcpJson(it) }
            ?: emptyList()
    }
}
