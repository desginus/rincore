package me.rerere.rikkahub.data.operit.importer


/* ───【自研】岔路口计划·阶段3 — Operit Skill 导入器
 * skill_v2: GitHub repo → skills/<name>/SKILL.md (标准 SKILL.md, 与 RinCore 同构)
 * legacy:  Operit 专属 JSON 形态 → 诚实降级 (暂不支持)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

object OperitSkillImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class ImportResult(
        val importedSkills: List<String>,
        val skippedReason: String? = null,
    )

    /**
     * 从 GitHub repo 导入技能: 下载 zip → 寻找 SKILL.md → 存入 RinCore 技能体系。
     * @param repoUrl source.url (e.g. https://github.com/owner/repo)
     */
    suspend fun importFromRepo(
        client: OkHttpClient,
        repoUrl: String,
        skillManager: SkillManager,
        tempRoot: File,
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (owner, repo) = parseGitHubRepo(repoUrl)
                ?: error("不是有效的 GitHub 仓库地址: $repoUrl")

            val branch = resolveDefaultBranch(client, owner, repo)
            val zipBytes = downloadRepoZip(client, owner, repo, branch)
                ?: error("下载仓库失败: $owner/$repo@$branch")

            val extractDir = File(tempRoot, "skill_import_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                unzip(zipBytes, extractDir)
                val skillDirs = findSkillDirs(extractDir)
                if (skillDirs.isEmpty()) {
                    return@runCatching ImportResult(
                        importedSkills = emptyList(),
                        skippedReason = "未找到 SKILL.md — 该技能可能为 Operit 专属 legacy 格式, 暂不支持导入",
                    )
                }
                val imported = mutableListOf<String>()
                for (skillDir in skillDirs) {
                    val skillMd = File(skillDir, "SKILL.md")
                    val content = skillMd.readText()
                    val frontmatter = SkillFrontmatterParser.parse(content)
                    val name = frontmatter["name"]?.takeIf { it.isNotBlank() }
                        ?: skillDir.name.ifBlank { "operit_skill_${System.currentTimeMillis()}" }
                    val files = collectFiles(skillDir)
                    if (skillManager.saveSkillFilesAtomically(name, files)) {
                        imported.add(name)
                    }
                }
                ImportResult(importedSkills = imported.distinct())
            } finally {
                extractDir.deleteRecursively()
            }
        }
    }

    private fun parseGitHubRepo(url: String): Pair<String, String>? {
        val cleaned = url.trim().removeSuffix(".git").trimEnd('/')
        val regex = Regex("""https?://github\.com/([^/]+)/([^/]+)""")
        val m = regex.find(cleaned) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    private fun resolveDefaultBranch(client: OkHttpClient, owner: String, repo: String): String {
        return runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .get()
                .header("User-Agent", "RinCore/Operit-Import")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("GitHub API HTTP ${resp.code}")
                val obj = json.parseToJsonElement(body).jsonObject
                obj["default_branch"]?.jsonPrimitive?.content ?: "main"
            }
        }.getOrDefault("main")
    }

    private fun downloadRepoZip(client: OkHttpClient, owner: String, repo: String, branch: String): ByteArray? {
        // codeload 直连 zip (避免 API 频率限制)
        val url = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch"
        val request = Request.Builder().url(url).get().header("User-Agent", "RinCore/Operit-Import").build()
        // 重试链 (github 偶发拒连自愈)
        repeat(3) { attempt ->
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val len = resp.body?.contentLength() ?: -1L
                        if (len > MAX_ZIP_BYTES) error("repo zip 过大 ($len bytes) — 拒绝下载")
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.size > MAX_ZIP_BYTES) error("repo zip 过大 — 拒绝")
                        return bytes
                    }
                }
            }
            if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
        }
        return null
    }

    private fun unzip(bytes: ByteArray, destDir: File) {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                // 防 zip slip (v4.5.31: 补绝对路径检查 — Java File(parent, "/abs")
                // 会丢弃 parent 指向绝对路径)
                if (!name.contains("..") && !name.startsWith("/") && !name.startsWith("\\")) {
                    val target = File(destDir, name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 找所有含 SKILL.md 的目录 (深度限制 5 层, 避免误扫) */
    private fun findSkillDirs(root: File): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File, depth: Int) {
            if (depth > 5) return
            val children = dir.listFiles() ?: return
            if (File(dir, "SKILL.md").isFile) {
                result.add(dir)
                return // 找到技能根后不再深入 (子目录文件随技能一起导入)
            }
            children.forEach { child ->
                if (child.isDirectory && !child.name.startsWith(".")) walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return result
    }

    /** 收集技能目录下所有文件 (相对路径 → 内容) */
    private fun collectFiles(skillDir: File): Map<String, String> {
        val files = mutableMapOf<String, String>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    walk(child)
                } else {
                    val rel = child.relativeTo(skillDir).path.replace('\\', '/')
                    val text = runCatching { child.readText() }.getOrNull() ?: continue
                    if (text.length <= MAX_FILE_CHARS) files[rel] = text
                }
            }
        }
        walk(skillDir)
        return files
    }

    private const val MAX_FILE_CHARS = 512 * 1024
    private const val MAX_ZIP_BYTES = 20L * 1024 * 1024
}
