package me.rerere.rikkahub.data.files

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
    }

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // v3.6.12: 技能扫描加固 — 单文件解析失败只跳过该技能 (不全缺);
    // 整体扫描失败 (IO) 用上次成功缓存 — 防止 tools 数组偶发缺技能 → 请求前缀断裂
    private var cachedSkills: List<SkillMetadata>? = null

    // v3.6.85: DeepSeek Harness (DSH) 插件生态兼容 — 额外技能根
    // (workspace 的 .dsh/skills 与 .agents/skills), DSH 技能名加 dsh__ 前缀
    @Volatile
    private var extraSkillRoots: List<File> = emptyList()

    /** 设置 DSH 技能根 (workspace 变化/启动时由 WorkspaceRepository 刷新) */
    fun setExtraSkillRoots(roots: List<File>) {
        extraSkillRoots = roots.distinct()
        invalidateSkillsCache()
    }

    fun listSkills(): List<SkillMetadata> {
        return try {
            val skillsDir = getSkillsDir()
            val result = skillsDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir ->
                    try {
                        val skillFile = dir.resolve("SKILL.md")
                        if (!skillFile.exists()) null else parseSkillFile(skillFile, dir)
                    } catch (_: Exception) {
                        null
                    }
                }
                ?: emptyList()
            cachedSkills = result + scanDshSkills()
            cachedSkills ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "listSkills scan failed: ${e.message}, using cached ${cachedSkills?.size ?: 0}")
            cachedSkills ?: emptyList()
        }
    }

    /** 扫描 DSH 技能 (bundle 格式: <root>/<name>/SKILL.md, 与 DSH 官方发现格式一致) */
    private fun scanDshSkills(): List<SkillMetadata> {
        val skills = mutableListOf<SkillMetadata>()
        for (root in extraSkillRoots) {
            if (!root.isDirectory) continue
            root.listFiles()?.forEach { entry ->
                try {
                    if (!entry.isDirectory) return@forEach
                    val skillFile = entry.resolve("SKILL.md")
                    if (!skillFile.exists()) return@forEach
                    parseDshSkillFile(skillFile, entry.name)?.let { skills.add(it) }
                } catch (_: Exception) {
                    // 单插件失败只跳过该插件
                }
            }
        }
        return skills
    }

    private fun parseDshSkillFile(file: File, fallbackName: String): SkillMetadata? {
        return runCatching {
            val content = file.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = "dsh__$name",
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = emptyList(),
                skillDir = file.parentFile ?: return null,
            )
        }.getOrNull()
    }

    /** 技能增删后清缓存 (安装/删除/导入时调用) */
    fun invalidateSkillsCache() {
        cachedSkills = null
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return SkillFrontmatterParser.extractBody(skillFile.readText())
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        invalidateSkillsCache() // v3.6.12: 保存后清扫描缓存
        // v3.6.85: 禁止以 dsh__ 前缀创建技能 (该前缀保留给 DSH 只读源)
        if (name.startsWith("dsh__")) return null
        // 通过原子写入(staging + rename)落盘，避免直接 mkdirs 失败时
        // writeText 抛出 FileNotFoundException 导致崩溃
        if (!saveSkillFileBytesAtomically(name, mapOf("SKILL.md" to content.toByteArray()))) {
            return null
        }
        val skillDir = resolveSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"), skillDir)
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        invalidateSkillsCache() // v3.6.12: 增删后清扫描缓存
        // v3.6.85: DSH 技能为只读源 (workspace 插件目录), 禁止通过技能管理删除
        if (name.startsWith("dsh__")) return@withContext false
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    },
                    // 孤儿清理: skill 删除后, toolDomainOverrides 中 skill:名 挂载条目一并清除
                    toolDomainOverrides = settings.toolDomainOverrides.filterKeys { it != "skill:$name" }
                )
            }
        }
        deleted
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = listSkills()
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.update { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun getSkillDir(skillName: String): File? = resolveSkillDir(skillName)

    fun saveSkillFile(skillName: String, relativePath: String, content: String): Boolean {
        invalidateSkillsCache() // v3.6.12: 保存后清扫描缓存
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        target.parentFile?.mkdirs()
        target.writeText(content)
        return true
    }

    fun saveSkillFilesAtomically(skillName: String, files: Map<String, String>): Boolean {
        invalidateSkillsCache() // v3.6.12: 保存后清扫描缓存
        return saveSkillFileBytesAtomically(
            skillName = skillName,
            files = files.mapValues { it.value.toByteArray() },
        )
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean {
        val skillsDir = getSkillsDir()
        val targetDir = resolveSkillDir(skillName) ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        var backupDir: File? = null

        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                target.parentFile?.mkdirs()
                target.writeBytes(content)
            }

            if (!stagingDir.resolve("SKILL.md").exists()) return false

            if (targetDir.exists()) {
                backupDir = createTempSkillDir(skillsDir, skillName, "backup") ?: return false
                if (!targetDir.renameTo(backupDir)) return false
            }

            if (!stagingDir.renameTo(targetDir)) {
                if (backupDir != null && !targetDir.exists()) {
                    backupDir.renameTo(targetDir)
                }
                return false
            }

            backupDir?.deleteRecursively()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFilesAtomically: Failed to save $skillName", e)
            if (backupDir != null && !targetDir.exists()) {
                backupDir.renameTo(targetDir)
            }
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            if (backupDir?.exists() == true && targetDir.exists()) {
                backupDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): Boolean {
        val skillDir = resolveSkillDir(skillName) ?: return false
        val target = SkillPaths.resolveSkillFile(skillDir, relativePath) ?: return false
        return target.delete()
    }

    fun resolveSkillFile(skillName: String, relativePath: String): File? {
        val skillDir = resolveSkillDir(skillName) ?: return null
        return SkillPaths.resolveSkillFile(skillDir, relativePath)
    }

    private fun resolveSkillDir(skillName: String): File? {
        if (skillName.startsWith("dsh__")) {
            val rawName = skillName.removePrefix("dsh__")
            for (root in extraSkillRoots) {
                val dir = root.resolve(rawName)
                if (dir.isDirectory && dir.resolve("SKILL.md").exists()) return dir
            }
            return null
        }
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$skillName.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun parseSkillFile(skillFile: File, skillDir: File): SkillMetadata? {
        return runCatching {
            val content = skillFile.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: return null
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = name,
                description = description,
                compatibility = frontmatter["compatibility"],
                allowedTools = frontmatter["allowed-tools"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                skillDir = skillDir,
            )
        }.getOrElse {
            Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}", it)
            null
        }
    }
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
    val allowedTools: List<String> = emptyList(),
    val skillDir: File,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
