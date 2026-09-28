/* 【域 D·工作区沙箱】 — 文件/技能 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.files


/* ───【原版对齐】SkillManager | 差异 +110 行
 * 来源: 原版移植 + 自研 (技能管理增强)
 * 差异: 额外技能根 (dsh__/plugin__ 只读源, v3.6.85-88)、扫描缓存
 *       加固 (v3.6.12)、原子写入
 * ───────────────────────────────────────────────────────────────*/
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

    // v4.8.64 (2.5.5 移植): 内置技能解压 (assets → filesDir/builtin_skills), 每进程只检查一次
    private val builtinLock = Any()

    @Volatile
    private var builtinExtracted = false

    fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // v4.8.64 (2.5.5 移植): 内置技能目录
    fun getBuiltinSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.BUILTIN_SKILLS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 确保内置技能已从 assets 解压到 [getBuiltinSkillsDir]，每个进程只检查一次。
     */
    fun ensureBuiltinSkillsExtracted() {
        if (builtinExtracted) return
        synchronized(builtinLock) {
            if (builtinExtracted) return
            runCatching {
                BuiltinSkills.extractIfNeeded(context, getBuiltinSkillsDir())
            }.onFailure {
                Log.w(TAG, "ensureBuiltinSkillsExtracted: Failed to extract builtin skills", it)
            }
            builtinExtracted = true
        }
    }

    // v3.6.12: 技能扫描加固 — 单文件解析失败只跳过该技能 (不全缺);
    // 整体扫描失败 (IO) 用上次成功缓存 — 防止 tools 数组偶发缺技能 → 请求前缀断裂
    private var cachedSkills: List<SkillMetadata>? = null
    // v4.8.32 (性能): 技能正文缓存 (mtime 键) — readSkillBody 原为每次同步读盘,
    // 被生成链每轮 step 调用 (forcedSkills 注入), 长工具循环下反复主线程 IO。
    private val skillBodyCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    // v3.6.85: DeepSeek Harness (DSH) 插件生态兼容 — 额外技能根
    // (workspace 的 .dsh/skills 与 .agents/skills), 技能名加前缀
    // v3.6.86: 泛化 — 前缀随根配置 (dsh__ 技能源 / plugin__ 插件目录)
    @Volatile
    private var extraSkillRoots: List<ExtraSkillRoot> = emptyList()

    /** 设置额外技能根 (workspace 变化/启动时由 WorkspaceRepository 刷新) */
    fun setExtraSkillRoots(roots: List<ExtraSkillRoot>) {
        extraSkillRoots = roots.distinct()
        invalidateSkillsCache()
    }

    data class ExtraSkillRoot(
        val prefix: String,   // 技能名前缀, 如 dsh__ / plugin__
        val root: File,       // 扫描根, 根下每个目录视为一个技能
    )

    /** 当前额外根快照 (PluginManager 合并注入用) */
    fun extraRootsSnapshot(): List<ExtraSkillRoot> = extraSkillRoots

    fun listSkills(): List<SkillMetadata> {
        return try {
            // v4.8.64 (2.5.5 移植): 用户技能 + 额外根 (DSH/插件) + 内置技能 —
            // 同名时用户侧 (含额外根) 覆盖内置; 内置技能只读 (builtin=true)。
            val local = listSkillsIn(getSkillsDir(), builtin = false) + scanDshSkills()
            val localNames = local.mapTo(HashSet()) { it.name }
            val result = local + listBuiltinSkills().filter { it.name !in localNames }
            cachedSkills = result
            result
        } catch (e: Exception) {
            Log.w(TAG, "listSkills scan failed: ${e.message}, using cached ${cachedSkills?.size ?: 0}")
            cachedSkills ?: emptyList()
        }
    }

    /** 列出某根下的技能 (跳过隐藏目录 — 原子写入残留的 .<name>.staging 等; SKILL.md 缺失跳过) — 2.5.5 */
    private fun listSkillsIn(root: File, builtin: Boolean): List<SkillMetadata> {
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                try {
                    val skillFile = dir.resolve("SKILL.md")
                    if (!skillFile.exists()) null else parseSkillFile(skillFile, dir, builtin)
                } catch (_: Exception) {
                    null
                }
            }
            ?: emptyList()
    }

    private fun listBuiltinSkills(): List<SkillMetadata> {
        ensureBuiltinSkillsExtracted()
        return listSkillsIn(getBuiltinSkillsDir(), builtin = true)
    }

    /** v4.8.64 (2.5.5 移植): 按名查找 (含内置; 详情页只读判定用) */
    fun findSkill(name: String): SkillMetadata? = listSkills().firstOrNull { it.name == name }

    /** 扫描额外根技能 (bundle 格式: <root>/<name>/SKILL.md, 与 DSH 官方发现格式一致) */
    private fun scanDshSkills(): List<SkillMetadata> {
        val skills = mutableListOf<SkillMetadata>()
        for ((prefix, root) in extraSkillRoots) {
            if (!root.isDirectory) continue
            root.listFiles()?.forEach { entry ->
                try {
                    if (!entry.isDirectory) return@forEach
                    val skillFile = entry.resolve("SKILL.md")
                    if (!skillFile.exists()) return@forEach
                    parseExtraSkillFile(skillFile, entry.name, prefix)?.let { skills.add(it) }
                } catch (_: Exception) {
                    // 单插件失败只跳过该插件
                }
            }
        }
        return skills
    }

    private fun parseExtraSkillFile(file: File, fallbackName: String, prefix: String): SkillMetadata? {
        return runCatching {
            val content = file.readText()
            val frontmatter = SkillFrontmatterParser.parse(content)
            val name = frontmatter["name"]?.takeIf { it.isNotBlank() } ?: fallbackName
            val description = frontmatter["description"]?.takeIf { it.isNotBlank() } ?: return null
            SkillMetadata(
                name = "$prefix$name",
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
        skillBodyCache.clear()
    }

    fun readSkillBody(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        // v4.8.32: mtime 键缓存 — 文件未变时零 IO (生成链每轮调用点)
        val mtime = skillFile.lastModified()
        val cached = skillBodyCache[skillName]
        if (cached != null && cached.first == mtime) return cached.second
        val body = SkillFrontmatterParser.extractBody(skillFile.readText())
        skillBodyCache[skillName] = mtime to body
        return body
    }

    fun readSkillContent(skillName: String): String? {
        val skillFile = resolveSkillDir(skillName)?.resolve("SKILL.md") ?: return null
        if (!skillFile.exists()) return null
        return skillFile.readText()
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        invalidateSkillsCache() // v3.6.12: 保存后清扫描缓存
        // v3.6.85/86: 禁止以额外源前缀创建技能 (保留给只读源)
        if (extraSkillRoots.any { name.startsWith(it.prefix) }) return null
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
        // v3.6.85/86: 额外源技能 (dsh__/plugin__) 为只读, 禁止通过技能管理删除
        if (extraSkillRoots.any { name.startsWith(it.prefix) }) return@withContext false
        val skillDir = resolveSkillDir(name) ?: return@withContext false
        // v4.8.64 (2.5.5 移植): 目录不存在时 deleteRecursively 也返回 true, 需提前拦截,
        // 避免误清理内置技能的启用状态
        if (!skillDir.exists()) return@withContext false
        val deleted = skillDir.deleteRecursively()
        if (deleted) {
            // v4.8.64 (2.5.5 移植): 删除的是覆盖内置技能的同名用户技能时, 内置技能会重新
            // 生效 — 保留启用状态与域挂载 (仅无内置兜底时才清理)
            val shadowsBuiltin = listBuiltinSkills().any { it.name == name }
            settingsStore.update { settings ->
                settings.copy(
                    assistants = if (shadowsBuiltin) {
                        settings.assistants
                    } else {
                        settings.assistants.map { assistant ->
                            if (assistant.enabledSkills.contains(name)) {
                                assistant.copy(enabledSkills = assistant.enabledSkills - name)
                            } else {
                                assistant
                            }
                        }
                    },
                    // 孤儿清理: skill 删除后, toolDomainOverrides 中 skill:名 挂载条目一并清除
                    toolDomainOverrides = if (shadowsBuiltin) {
                        settings.toolDomainOverrides
                    } else {
                        settings.toolDomainOverrides.filterKeys { it != "skill:$name" }
                    }
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
        val parent = target.parentFile ?: return false
        // v4.8.64 (2.5.5 移植): 先写同目录临时文件再 rename 覆盖, 避免写到一半失败时损坏原文件;
        // IO 异常 (如 mkdirs 失败导致 FileNotFoundException) 转为返回 false, 不向调用方抛出
        val tempFile = parent.resolve(".${target.name}.tmp")
        return try {
            if (!parent.exists() && !parent.mkdirs()) return false
            tempFile.writeText(content)
            tempFile.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            false
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
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
        for ((prefix, root) in extraSkillRoots) {
            if (skillName.startsWith(prefix)) {
                val rawName = skillName.removePrefix(prefix)
                val dir = root.resolve(rawName)
                if (dir.isDirectory && dir.resolve("SKILL.md").exists()) return dir
                return null
            }
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

    private fun parseSkillFile(skillFile: File, skillDir: File, builtin: Boolean = false): SkillMetadata? {
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
                builtin = builtin,
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
    /** v4.8.64 (2.5.5 移植): 内置技能 (assets 解压), 只读 */
    val builtin: Boolean = false,
) {
    val skillFile: File get() = skillDir.resolve("SKILL.md")
}
