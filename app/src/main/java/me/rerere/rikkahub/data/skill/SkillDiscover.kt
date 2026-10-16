/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
package me.rerere.rikkahub.data.skill

/* ───【自研】SkillDiscover.kt — v4.8.145 技能「发现」移植 (Cherry Studio 手机版 v0.2.1)
 *
 * 两个提供来源 (对其 discover/SkillSourceFilter 原样):
 *   · CherryIN   https://skills.cherryin.ai — GET /api/skills?mobileSupport=supported&sort=popular
 *                &offset=&limit=20[&q=] 列表/搜索; GET /api/skills/{id}/download → 302 签名 URL (zip)
 *   · skills.sh  https://skills.sh — GET /api/search?q=&limit=20 搜索;
 *                GET /api/skills/all-time/0 热门; 条目 (owner/repo + skillId) 解析到 GitHub 技能目录
 *
 * 安装 = 拉包 → 校验 (对齐其 skillPackage.ts 约束: ≤200 文件 / ≤8MB 总 / ≤2MB 单文件 / 禁路径穿越)
 *        → 收敛公共根目录 → 定位 SKILL.md → SkillManager.saveSkillFileBytesAtomically (staging+rename)
 * GitHub 解析 = api.github.com (commits→HEAD sha → git/trees?recursive=1) + raw.githubusercontent.com 逐文件
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.files.SkillManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** 发现/安装链的可见错误 (message 直接可展示)。 */
class SkillDiscoverException(message: String) : Exception(message)

enum class SkillDiscoverSource { CHERRYIN, SKILLS_SH }

/** UI 统一行 (两来源混合展示)。 */
data class DiscoverSkillItem(
    val key: String,
    val name: String,
    val description: String,
    val sourceLabel: String,
    val installs: Long?,
    val installable: Boolean,
    val cherryinId: String? = null,
    val githubOwner: String? = null,
    val githubRepo: String? = null,
    val githubSkillName: String? = null,
)

@Serializable
data class LocalizedText(val en: String = "", val zh: String? = null) {
    fun best(): String = zh?.takeIf { it.isNotBlank() } ?: en
}

@Serializable
data class CherryinSkillDto(
    val id: String = "",
    val name: LocalizedText = LocalizedText(),
    val description: LocalizedText = LocalizedText(),
    val author: String = "",
    val version: String = "",
    val tags: List<String> = emptyList(),
    val downloads: Long = 0,
    val hasPackage: Boolean = false,
)

@Serializable
data class CherryinPaginationDto(val offset: Int = 0, val limit: Int = 20, val hasMore: Boolean = false)

@Serializable
data class CherryinPageDto(
    val items: List<CherryinSkillDto> = emptyList(),
    val pagination: CherryinPaginationDto = CherryinPaginationDto(),
)

@Serializable
data class SkillsShItemDto(
    val id: String = "",
    val source: String = "",
    val skillId: String = "",
    val name: String = "",
    val installs: Long = 0,
)

@Serializable
data class SkillsShListDto(val skills: List<SkillsShItemDto> = emptyList())

@Serializable
private data class GithubShaDto(val sha: String = "")

@Serializable
private data class GithubTreeEntryDto(val path: String = "", val type: String = "", val mode: String = "")

@Serializable
private data class GithubTreeDto(val tree: List<GithubTreeEntryDto> = emptyList())

object SkillDiscoverService {
    private const val CHERRYIN_BASE = "https://skills.cherryin.ai"
    private const val SKILLS_SH_BASE = "https://skills.sh"
    private const val GITHUB_API = "https://api.github.com"
    private const val RAW_GITHUB = "https://raw.githubusercontent.com"

    // 包约束 (对齐 CS skillPackage.ts)
    private const val MAX_FILES = 200
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
    private const val MAX_REPO_SKILLS = 20

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ───────────── HTTP 底层 ─────────────

    private suspend fun getBytes(url: String, maxBytes: Long, githubApi: Boolean = false): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "RinCore-SkillDiscover/1.0")
                .apply { if (githubApi) header("Accept", "application/vnd.github+json") }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw SkillDiscoverException(httpMessage(resp.code, githubApi))
                val body = resp.body ?: throw SkillDiscoverException("响应为空")
                if (body.contentLength() > maxBytes) {
                    throw SkillDiscoverException("资源过大（超过 ${maxBytes / 1024 / 1024}MB 上限）")
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > maxBytes) {
                            throw SkillDiscoverException("资源过大（超过 ${maxBytes / 1024 / 1024}MB 上限）")
                        }
                        out.write(buf, 0, n)
                    }
                }
                out.toByteArray()
            }
        }

    private suspend fun getText(url: String, githubApi: Boolean = false): String =
        String(getBytes(url, 4L * 1024 * 1024, githubApi), Charsets.UTF_8)

    private fun httpMessage(code: Int, githubApi: Boolean): String = when {
        githubApi && (code == 403 || code == 429) -> "GitHub 接口限流，请稍后重试"
        code == 404 -> "资源不存在（HTTP 404）"
        else -> "网络错误（HTTP $code）"
    }

    // ───────────── CherryIN (来源①) ─────────────

    /** 返回 (行, 下一页 offset 或 null)。 */
    suspend fun cherryinList(query: String?, offset: Int): Pair<List<DiscoverSkillItem>, Int?> {
        val q = query?.trim()?.takeIf { it.isNotBlank() }
        val url = buildString {
            append(CHERRYIN_BASE).append("/api/skills?mobileSupport=supported&sort=popular&offset=")
                .append(offset).append("&limit=20")
            if (q != null) append("&q=").append(URLEncoder.encode(q, "UTF-8"))
        }
        val page = json.decodeFromString<CherryinPageDto>(getText(url))
        val items = page.items.filter { it.id.isNotBlank() }.map { it.toDiscoverItem() }
        val next = if (page.pagination.hasMore) offset + page.pagination.limit else null
        return items to next
    }

    private fun CherryinSkillDto.toDiscoverItem() = DiscoverSkillItem(
        key = "cherryin:$id",
        name = name.best().ifBlank { id },
        description = description.best(),
        sourceLabel = if (author.isNotBlank()) "CherryIN · $author" else "CherryIN",
        installs = downloads,
        installable = hasPackage,
        cherryinId = id,
    )

    // ───────────── skills.sh (来源②) ─────────────

    suspend fun skillsShPopular(): List<DiscoverSkillItem> =
        parseSkillsSh(getText("$SKILLS_SH_BASE/api/skills/all-time/0"))

    suspend fun skillsShSearch(query: String): List<DiscoverSkillItem> =
        parseSkillsSh(
            getText("$SKILLS_SH_BASE/api/search?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=20")
        )

    private fun parseSkillsSh(text: String): List<DiscoverSkillItem> {
        val list = json.decodeFromString<SkillsShListDto>(text)
        return list.skills.flatMap { s ->
            val parts = s.source.split('/').filter { it.isNotBlank() }
            if (parts.size != 2 || s.skillId.isBlank()) emptyList()
            else listOf(
                DiscoverSkillItem(
                    key = "gh:${s.source}/${s.skillId}",
                    name = s.name.ifBlank { s.skillId },
                    description = "skills.sh · ${s.source}",
                    sourceLabel = s.source,
                    installs = s.installs,
                    installable = true,
                    githubOwner = parts[0],
                    githubRepo = parts[1],
                    githubSkillName = s.skillId,
                )
            )
        }
    }

    // ───────────── GitHub 解析 ─────────────

    private suspend fun resolveCommit(owner: String, repo: String, ref: String? = null): String {
        val r = ref?.takeIf { it.isNotBlank() } ?: "HEAD"
        val text = getText("$GITHUB_API/repos/$owner/$repo/commits/${URLEncoder.encode(r, "UTF-8")}", githubApi = true)
        val sha = json.decodeFromString<GithubShaDto>(text).sha
        if (sha.isBlank()) throw SkillDiscoverException("无法解析仓库提交")
        return sha
    }

    private suspend fun fetchTree(owner: String, repo: String, sha: String): List<GithubTreeEntryDto> {
        val text = getText("$GITHUB_API/repos/$owner/$repo/git/trees/$sha?recursive=1", githubApi = true)
        return json.decodeFromString<GithubTreeDto>(text).tree
    }

    private fun skillDirs(tree: List<GithubTreeEntryDto>): List<String> =
        tree.filter { it.type == "blob" && (it.path == "SKILL.md" || it.path.endsWith("/SKILL.md")) }
            .map { if (it.path == "SKILL.md") "" else it.path.removeSuffix("/SKILL.md") }
            .distinct()

    /** skills.sh / 仓库链接安装: 在仓库中找目标技能目录并安装。 */
    suspend fun installFromGithub(
        skillManager: SkillManager,
        owner: String,
        repo: String,
        skillName: String? = null,
    ): String {
        if (!validGithubPart(owner) || !validGithubPart(repo)) throw SkillDiscoverException("无效的 GitHub 仓库名")
        val sha = resolveCommit(owner, repo)
        return installFromGithubPinned(skillManager, owner, repo, sha, skillName, null)
    }

    private suspend fun installFromGithubPinned(
        skillManager: SkillManager,
        owner: String,
        repo: String,
        sha: String,
        skillName: String?,
        directoryOrNull: String?,
    ): String {
        val tree = fetchTree(owner, repo, sha)
        val dirs = skillDirs(tree)
        if (dirs.isEmpty()) throw SkillDiscoverException("该仓库中未找到技能（无 SKILL.md）")
        val target: String = when {
            directoryOrNull != null -> {
                if (dirs.none { it == directoryOrNull }) throw SkillDiscoverException("该目录下未找到 SKILL.md")
                directoryOrNull
            }
            skillName != null -> dirs.firstOrNull { it.substringAfterLast('/') == skillName || it == skillName }
                ?: throw SkillDiscoverException("仓库中未找到名为「$skillName」的技能")
            dirs.size == 1 -> dirs.first()
            dirs.size > MAX_REPO_SKILLS -> throw SkillDiscoverException("仓库含过多技能（>20），请使用具体技能链接")
            else -> throw SkillDiscoverException("仓库含 ${dirs.size} 个技能，请使用具体技能链接")
        }
        val prefix = if (target.isEmpty()) "" else "$target/"
        if (tree.any { it.path.startsWith(prefix) && (it.type == "commit" || it.mode == "120000") }) {
            throw SkillDiscoverException("技能包不可包含符号链接或子模块")
        }
        val blobs = tree.filter { it.type == "blob" && it.path.startsWith(prefix) }
        if (blobs.isEmpty()) throw SkillDiscoverException("技能目录在该版本无文件")
        if (blobs.size > MAX_FILES) throw SkillDiscoverException("技能包文件过多（>200）")
        var total = 0L
        val files = LinkedHashMap<String, ByteArray>()
        for (b in blobs) {
            val bytes = getBytes("$RAW_GITHUB/$owner/$repo/$sha/${encodePath(b.path)}", MAX_FILE_BYTES)
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw SkillDiscoverException("技能包过大（>8MB）")
            files[b.path.removePrefix(prefix)] = bytes
        }
        return finishInstall(
            skillManager = skillManager,
            rawFiles = files,
            fallbackName = target.substringAfterLast('/').ifBlank { repo },
        )
    }

    private fun encodePath(path: String) =
        path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun validGithubPart(p: String) =
        p.isNotBlank() && p != "." && p != ".." &&
            p.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    // ───────────── URL 安装 (GitHub / skills.sh) ─────────────

    /** 支持: github.com/{o}/{r} | /blob/{ref}/.../SKILL.md | /tree/{ref}/{dir} | skills.sh/{o}/{r}/{skill} */
    suspend fun installFromUrl(skillManager: SkillManager, raw: String): String {
        val uri = try {
            java.net.URI(raw.trim())
        } catch (_: Exception) {
            throw SkillDiscoverException("无效链接")
        }
        if (uri.scheme != "https") throw SkillDiscoverException("仅支持 https 链接")
        val parts = uri.path.split('/').filter { it.isNotBlank() }
            .map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        return when (host) {
            "skills.sh" -> {
                if (parts.size < 3) throw SkillDiscoverException("skills.sh 链接应形如 skills.sh/作者/仓库/技能名")
                installFromGithub(skillManager, parts[0], parts[1], parts[2])
            }
            "github.com" -> {
                if (parts.size < 2) throw SkillDiscoverException("GitHub 链接应指向仓库或 SKILL.md")
                val owner = parts[0]
                val repo = parts[1].removeSuffix(".git")
                when {
                    parts.size == 2 -> installFromGithub(skillManager, owner, repo)
                    parts.size >= 5 && parts[2] == "blob" -> {
                        val ref = parts[3]
                        val path = parts.drop(4).joinToString("/")
                        if (!path.endsWith("SKILL.md")) throw SkillDiscoverException("blob 链接需指向 SKILL.md")
                        val dir = path.removeSuffix("SKILL.md").removeSuffix("/")
                        val sha = resolveCommit(owner, repo, ref)
                        installFromGithubPinned(skillManager, owner, repo, sha, null, dir)
                    }
                    parts.size >= 4 && parts[2] == "tree" -> {
                        val ref = parts[3]
                        val dir = parts.drop(4).joinToString("/")
                        val sha = resolveCommit(owner, repo, ref)
                        installFromGithubPinned(skillManager, owner, repo, sha, null, dir)
                    }
                    else -> throw SkillDiscoverException("不支持的 GitHub 链接形态（仓库 / blob SKILL.md / tree 目录）")
                }
            }
            else -> throw SkillDiscoverException("暂不支持该来源（支持 GitHub / skills.sh 链接）")
        }
    }

    // ───────────── CherryIN 安装 ─────────────

    suspend fun installFromCherryin(skillManager: SkillManager, id: String, fallbackName: String): String {
        if (id.isBlank()) throw SkillDiscoverException("无效的技能 id")
        val zip = getBytes("$CHERRYIN_BASE/api/skills/${URLEncoder.encode(id, "UTF-8")}/download", MAX_ARCHIVE_BYTES)
        val files = withContext(Dispatchers.IO) { unzipPackage(zip) }
        return finishInstall(skillManager, files, fallbackName)
    }

    // ───────────── 安装核心 ─────────────

    private fun unzipPackage(zip: ByteArray): Map<String, ByteArray> {
        val raw = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) {
                    val name = sanitizeEntryPath(e.name)
                        ?: throw SkillDiscoverException("压缩包含非法路径：${e.name}")
                    if (raw.size >= MAX_FILES) throw SkillDiscoverException("技能包文件过多（>200）")
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        size += n
                        if (size > MAX_FILE_BYTES) throw SkillDiscoverException("单文件过大（>2MB）：$name")
                        out.write(buf, 0, n)
                    }
                    total += size
                    if (total > MAX_TOTAL_BYTES) throw SkillDiscoverException("技能包过大（>8MB）")
                    raw[name] = out.toByteArray()
                }
                zis.closeEntry()
            }
        }
        if (raw.isEmpty()) throw SkillDiscoverException("压缩包为空")
        return raw
    }

    private fun sanitizeEntryPath(path: String): String? {
        val p = path.replace('\\', '/')
        if (p.startsWith("/") || p.contains(':')) return null
        val segs = p.split('/').filter { it.isNotBlank() && it != "." }
        if (segs.isEmpty() || segs.any { it == ".." }) return null
        return segs.joinToString("/")
    }

    /** 收敛公共根目录 → 定位 SKILL.md → 原子入盘。 */
    private suspend fun finishInstall(
        skillManager: SkillManager,
        rawFiles: Map<String, ByteArray>,
        fallbackName: String,
    ): String {
        var files = rawFiles
        // 剥公共单根 (zip 常见 wrapper: "<skill>/..."; 循环剥到多根或根层出现文件为止)
        while (true) {
            val firstSegs = files.keys.map { it.substringBefore('/') }.distinct()
            if (firstSegs.size == 1 && files.keys.all { it.contains('/') }) {
                val root = firstSegs.first()
                files = files.entries.associate { it.key.removePrefix("$root/") to it.value }
            } else break
        }
        val mdDirs = files.keys.filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
            .map { if (it == "SKILL.md") "" else it.removeSuffix("/SKILL.md") }
            .distinct()
        if (mdDirs.isEmpty()) throw SkillDiscoverException("技能包缺少 SKILL.md")
        val root: String = if (mdDirs.size == 1) mdDirs.first() else {
            val hint = sanitizeSkillName(fallbackName)
            mdDirs.firstOrNull { it.substringAfterLast('/') == hint } ?: mdDirs.sorted().first()
        }
        val prefix = if (root.isEmpty()) "" else "$root/"
        val relative = LinkedHashMap<String, ByteArray>()
        for ((k, v) in files) {
            if (!k.startsWith(prefix)) continue
            relative[k.removePrefix(prefix)] = v
        }
        if (!relative.containsKey("SKILL.md")) throw SkillDiscoverException("技能包缺少 SKILL.md")
        val skillMd = String(relative.getValue("SKILL.md"), Charsets.UTF_8)
        val frontmatterName = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(skillMd)?.groupValues?.get(1)?.trim()?.trim('"', '\'')
        val name = sanitizeSkillName(
            frontmatterName?.takeIf { it.isNotBlank() }
                ?: root.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: fallbackName
        )
        if (name.isBlank()) throw SkillDiscoverException("无法确定技能名")
        if (name.startsWith("dsh__") || name.startsWith("plugin__")) {
            throw SkillDiscoverException("技能名使用了保留前缀（dsh__/plugin__）")
        }
        val ok = withContext(Dispatchers.IO) { skillManager.saveSkillFileBytesAtomically(name, relative) }
        if (!ok) throw SkillDiscoverException("写入技能目录失败")
        skillManager.invalidateSkillsCache()
        return name
    }

    private fun sanitizeSkillName(raw: String): String =
        raw.trim()
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
            .trim('.', '-', '_')
            .take(64)
}
