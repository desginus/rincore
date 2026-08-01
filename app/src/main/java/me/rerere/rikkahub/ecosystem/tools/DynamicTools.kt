package me.rerere.rikkahub.ecosystem.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.ecosystem.EcosystemManager
import me.rerere.rikkahub.ecosystem.plugin.ClaudePluginParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import android.util.Log
import kotlin.text.Charsets
import kotlin.uuid.Uuid

object DynamicTools {
    private const val TAG = "DynamicTools"
    private var mcpManager: McpManager? = null
    private var ecosystemWorkspaceRoot: String = ""
    // ClawHub 安装的 skill 落此目录 — 与 Agent Skills(SkillManager.getSkillsDir) 同一目录,
    // 修复: 此前落 ecosystem 私有目录, proot/use_skill 不可见
    private var skillsRoot: String = ""

    fun initialize(mcp: McpManager, workspaceRoot: String, skillsRoot: String = "") {
        mcpManager = mcp
        ecosystemWorkspaceRoot = workspaceRoot
        this.skillsRoot = skillsRoot.ifEmpty { File(workspaceRoot, "skills").absolutePath }
    }

    fun all(): List<Tool> {
        val tools = listOf(
            createMcpConnectTool(),
            createClawhubInstallTool(),
            createClawhubSearchTool(),
            createPluginInstallTool(),
            createSkillsLockTool(),
        )
        Log.i(TAG, "DynamicTools.all() → ${tools.size} tools: ${tools.joinToString { it.name }}")
        return tools
    }

    /** 动态 MCP 工具 — 每个 step 都会重新获取，确保 mcp_connect 后立即可用。 */
    fun getMcpTools(): List<Tool> {
        val mcp = mcpManager ?: return emptyList()
        return mcp.getAllAvailableTools().map { (serverId, serverName, tool) ->
            Tool(
                name = "mcp__${sanitize(serverName)}__${sanitize(tool.name)}",
                description = tool.description ?: "",
                parameters = { tool.inputSchema },
                needsApproval = { tool.needsApproval },
                execute = { input ->
                    mcp.callTool(serverId, tool.name, if (input is kotlinx.serialization.json.JsonObject) input else kotlinx.serialization.json.JsonObject(emptyMap()))
                },
            )
        }
    }

    private fun sanitize(name: String): String {
        // 与 ChatService.sanitizeMcpName 保持一致 — 保留大小写, 确保 distinctBy 能去重
        val sanitized = name.map { c ->
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' -> c
                else -> "_"
            }
        }.joinToString("")
        return sanitized.replace(Regex("_+"), "_").trim('_').ifEmpty { "tool" }
    }

    // ═══ mcp_connect — P0 MCP 动态连接 ═══════════════════════

    private fun createMcpConnectTool(): Tool = Tool(
        name = "mcp_connect",
        description = "Dynamically add an MCP server. Args: {name, url, transport: sse|streamable_http|stdio, command: shell command for stdio mode}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { false },
        execute = { input: JsonElement ->
            val mcp = mcpManager
                ?: return@Tool listOf(UIMessagePart.Text("MCP manager not initialized"))
            try {
                val obj = input as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Invalid args, need JSON object"))
                val name = obj["name"]?.jsonPrimitive?.content
                    ?: return@Tool listOf(UIMessagePart.Text("Missing: name"))
                val transport = obj["transport"]?.jsonPrimitive?.content ?: "streamable_http"

                when (transport.lowercase()) {
                    "stdio" -> {
                        val command = obj["command"]?.jsonPrimitive?.content
                            ?: return@Tool listOf(UIMessagePart.Text("stdio mode requires: command"))
                        val config = McpServerConfig.StdioTransportServer(
                            id = Uuid.random(),
                            commonOptions = McpCommonOptions(name = name),
                            command = command,
                        )
                        mcp.addClient(config)
                        listOf(UIMessagePart.Text(
                            "MCP server (stdio) spawned: $name\n" +
                            "Command: $command\n" +
                            "Process managed by RinCore. Tools available next step."
                        ))
                    }
                    else -> {
                        val url = obj["url"]?.jsonPrimitive?.content
                            ?: return@Tool listOf(UIMessagePart.Text("Missing: url"))
                        val config = when (transport.lowercase()) {
                            "sse" -> McpServerConfig.SseTransportServer(
                                id = Uuid.random(),
                                commonOptions = McpCommonOptions(name = name),
                                url = url,
                            )
                            else -> McpServerConfig.StreamableHTTPServer(
                                id = Uuid.random(),
                                commonOptions = McpCommonOptions(name = name),
                                url = url,
                            )
                        }
                        mcp.addClient(config)
                        listOf(UIMessagePart.Text(
                            "MCP server added: $name ($transport)\n$url\nConnecting..."
                        ))
                    }
                }
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Failed: ${e.message}"))
            }
        },
    )

    // ═══ clawhub_install — P1 技能安装 + .mcp.json 自动连接 ═══

    private fun createClawhubInstallTool(): Tool = Tool(
        name = "clawhub_install",
        description = "Install a skill from ClawHub or GitHub. Args: {slug: @owner/name or github:owner/repo/path or url: direct URL}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { false },
        execute = { input: JsonElement ->
            try {
                val obj = input as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Invalid args"))
                val slug = obj["slug"]?.jsonPrimitive?.content
                val url = obj["url"]?.jsonPrimitive?.content

                val result = when {
                    url != null -> installFromUrl(url)
                    slug != null && slug.startsWith("github:") -> installFromGitHub(slug.removePrefix("github:"))
                    slug != null && slug.startsWith("@") -> installFromClawHub(slug)
                    slug != null -> installFromClawHubSlug(slug)
                    else -> listOf(UIMessagePart.Text("Use {slug: @owner/name} or {slug: github:owner/repo} or {url: ...}"))
                }
                EcosystemManager.refresh()
                result
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Install failed: ${e.message}"))
            }
        },
    )

    // ═══ clawhub_search — P1 ClawHub 搜索 ════════════════════

    private fun createClawhubSearchTool(): Tool = Tool(
        name = "clawhub_search",
        description = "Search ClawHub marketplace for skills. Args: {query: search term, limit: max results (default 10)}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { false },
        execute = { input: JsonElement ->
            try {
                val obj = input as? JsonObject ?: return@Tool listOf(UIMessagePart.Text("Invalid args"))
                val query = obj["query"]?.jsonPrimitive?.content ?: return@Tool listOf(UIMessagePart.Text("Missing: query"))
                val limit = obj["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10

                val apiUrl = "https://clawhub.ai/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
                val response = fetchUrl(apiUrl)

                if (response.startsWith("ERROR:")) {
                    return@Tool listOf(UIMessagePart.Text("ClawHub search failed: $response"))
                }

                listOf(UIMessagePart.Text(
                    "ClawHub search results for \"$query\":\n\n${response.take(4000)}" +
                    if (response.length > 4000) "\n...(truncated ${response.length}c)" else ""
                ))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Search failed: ${e.message}"))
            }
        },
    )

    // ═══ plugin_install — P2 插件安装 ════════════════════════

    private fun createPluginInstallTool(): Tool = Tool(
        name = "plugin_install",
        description = "Install a Claude Code / OpenClaw plugin from ZIP. Args: {zipFile: path to ZIP file, or url: URL to download}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { false },
        execute = { input: JsonElement ->
            try {
                val obj = input as? JsonObject ?: return@Tool listOf(UIMessagePart.Text("Invalid args"))
                val zipFile = obj["zipFile"]?.jsonPrimitive?.content
                val downloadUrl = obj["url"]?.jsonPrimitive?.content

                val fileToParse = when {
                    zipFile != null -> File(zipFile)
                    downloadUrl != null -> {
                        val bytes = fetchUrlAsBytes(downloadUrl)
                        if (bytes == null) return@Tool listOf(UIMessagePart.Text("Download failed"))
                        val tmpFile = File(ecosystemWorkspaceRoot, "_plugin_download.zip")
                        tmpFile.writeBytes(bytes.first)
                        tmpFile
                    }
                    else -> return@Tool listOf(UIMessagePart.Text("Need zipFile or url"))
                }

                val parsed = ClaudePluginParser.parsePluginZip(fileToParse)
                if (parsed == null) {
                    return@Tool listOf(UIMessagePart.Text("Failed to parse plugin ZIP"))
                }

                val (name, plugin) = parsed
                val targetDir = File(ecosystemWorkspaceRoot, "plugins/$name")
                targetDir.mkdirs()

                // 安装 skills
                plugin.skills.forEach { skill ->
                    val skillDir = File(targetDir, "skills/${skill.name}")
                    skillDir.mkdirs()
                    File(skillDir, "SKILL.md").writeText(skill.content)
                }

                // 安装 commands
                plugin.commands.forEach { cmd ->
                    val cmdDir = File(targetDir, "commands")
                    cmdDir.mkdirs()
                    File(cmdDir, "${cmd.name}.md").writeText(cmd.content)
                }

                // 自动连接 .mcp.json 声明的 MCP 服务器
                val mcpResults = mutableListOf<String>()
                plugin.mcpServers.forEach { mcpDef ->
                    val mcp = mcpManager
                    if (mcp != null && mcpDef.url.isNotEmpty()) {
                        val config = McpServerConfig.StreamableHTTPServer(
                            id = Uuid.random(),
                            commonOptions = McpCommonOptions(name = mcpDef.name),
                            url = mcpDef.url,
                        )
                        try {
                            mcp.addClient(config)
                            mcpResults.add("MCP connected: ${mcpDef.name}")
                        } catch (e: Exception) {
                            mcpResults.add("MCP failed: ${mcpDef.name} — ${e.message}")
                        }
                    } else if (mcpDef.command.isNotEmpty()) {
                        mcpResults.add("MCP (stdio): ${mcpDef.name} — launch with: ${mcpDef.command}")
                    }
                }

                writeLockEntry(name, "plugin:$name", plugin.manifest.version)

                val summary = buildString {
                    appendLine("Plugin installed: ${plugin.manifest.name} v${plugin.manifest.version}")
                    appendLine("Skills: ${plugin.skills.size} | Commands: ${plugin.commands.size}")
                    appendLine("Hooks: ${plugin.hooks.size} | Agents: ${plugin.agents.size}")
                    if (mcpResults.isNotEmpty()) {
                        appendLine("MCP servers:")
                        mcpResults.forEach { appendLine("  - $it") }
                    }
                    appendLine("Path: ${targetDir.absolutePath}")
                }

                EcosystemManager.refresh()
                listOf(UIMessagePart.Text(summary))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Plugin install failed: ${e.message}"))
            }
        },
    )

    // ═══ skills_lock — P1 锁定文件管理 ═══════════════════════

    private fun createSkillsLockTool(): Tool = Tool(
        name = "skills_lock",
        description = "List or manage installed skills. Args: {action: list|remove, name: skill name for remove}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { false },
        execute = { input: JsonElement ->
            val lockFile = File(ecosystemWorkspaceRoot, "skills-lock.json")
            val obj = input as? JsonObject
            val action = obj?.get("action")?.jsonPrimitive?.content ?: "list"

            when (action) {
                "list" -> {
                    if (!lockFile.isFile) {
                        return@Tool listOf(UIMessagePart.Text("No installed skills (skills-lock.json not found)"))
                    }
                    val content = lockFile.readText()
                    listOf(UIMessagePart.Text("Installed skills:\n\n$content"))
                }
                "remove" -> {
                    val name = obj?.get("name")?.jsonPrimitive?.content
                        ?: return@Tool listOf(UIMessagePart.Text("Missing: name"))
                    if (!lockFile.isFile) {
                        return@Tool listOf(UIMessagePart.Text("No lock file to remove from"))
                    }
                    // Simple remove: rewrite without the entry
                    val current = lockFile.readText()
                    val pattern = Regex("\"$name\"\\s*:\\s*\\{[^}]*\\},?")
                    val updated = pattern.replace(current, "").replace(",,", ",").replace("{,", "{").replace(",}", "}")
                    lockFile.writeText(updated)
                    listOf(UIMessagePart.Text("Removed: $name from skills-lock.json"))
                }
                else -> listOf(UIMessagePart.Text("Unknown action: $action. Use list or remove."))
            }
        },
    )

    // ═══ 内部实现 ════════════════════════════════════════════

    private suspend fun installFromUrl(url: String): List<UIMessagePart> {
        val result = fetchUrlAsBytes(url) ?: return listOf(UIMessagePart.Text("Download failed"))
        val (content, isZip) = result

        if (isZip) {
            return installZipContent(content, url.substringAfterLast("/").removeSuffix(".zip"))
        }

        val skillName = url.substringAfterLast("/").removeSuffix(".md")
        val skillDir = File(skillsRoot, skillName)
        skillDir.mkdirs()
        val text = String(content, Charsets.UTF_8)
        File(skillDir, "SKILL.md").writeText(text.take(50000))
        writeLockEntry(skillName, url, "latest")
        return listOf(UIMessagePart.Text("Installed: $skillName\nPath: ${skillDir.absolutePath}"))
    }

    private suspend fun installZipContent(zipBytes: ByteArray, name: String): List<UIMessagePart> {
        val targetDir = File(skillsRoot, name)
        targetDir.mkdirs()

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 查找 .mcp.json 并自动连接
        val mcpMsgs = mutableListOf<String>()
        val mcpFile = File(targetDir, ".mcp.json")
        if (mcpFile.isFile) {
            val mcpDefs = ClaudePluginParser.parseMcpJson(targetDir)
            val mcp = mcpManager
            mcpDefs.forEach { def ->
                if (mcp != null && def.url.isNotEmpty()) {
                    try {
                        val config = McpServerConfig.StreamableHTTPServer(
                            id = Uuid.random(),
                            commonOptions = McpCommonOptions(name = def.name),
                            url = def.url,
                        )
                        mcp.addClient(config)
                        mcpMsgs.add("MCP auto-connected: ${def.name}")
                    } catch (e: Exception) {
                        mcpMsgs.add("MCP failed: ${def.name} — ${e.message}")
                    }
                }
            }
        }

        writeLockEntry(name, "zip:$name", "latest")

        val msg = buildString {
            appendLine("Extracted: $name")
            appendLine("Path: ${targetDir.absolutePath}")
            if (mcpMsgs.isNotEmpty()) {
                appendLine("MCP servers:")
                mcpMsgs.forEach { appendLine("  - $it") }
            }
        }
        return listOf(UIMessagePart.Text(msg))
    }

    private suspend fun installFromClawHub(slug: String): List<UIMessagePart> {
        val (owner, skillSlug) = if (slug.startsWith("@")) {
            val parts = slug.removePrefix("@").split("/", limit = 2)
            Pair(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: parts.getOrNull(0) ?: "")
        } else {
            Pair(null, slug)
        }

        val apiUrl = buildString {
            append("https://clawhub.ai/api/v1/download")
            append("?slug=$skillSlug")
            if (owner != null) append("&ownerHandle=$owner")
        }

        val result = fetchUrlAsBytes(apiUrl)
        if (result == null) {
            return listOf(UIMessagePart.Text(
                "ClawHub: network error for $skillSlug. " +
                "Check DNS/proxy if 198.18.0.29 resolves. " +
                "Use github:owner/repo as fallback."
            ))
        }

        val (content, isZip) = result
        if (isZip) {
            return installZipContent(content, skillSlug)
        }

        val skillDir = File(ecosystemWorkspaceRoot, "skills/$skillSlug")
        skillDir.mkdirs()
        val text = String(content, Charsets.UTF_8)
        File(skillDir, "SKILL.md").writeText(text.take(50000))
        writeLockEntry(skillSlug, slug, "latest")
        return listOf(UIMessagePart.Text(
            "Installed: $skillSlug (from ClawHub: $slug)\nPath: ${skillDir.absolutePath}"
        ))
    }

    private suspend fun installFromClawHubSlug(slug: String): List<UIMessagePart> {
        return installFromClawHub(slug)
    }

    private fun installFromGitHub(repoPath: String): List<UIMessagePart> {
        val parts = repoPath.split("/", limit = 4)
        val owner = parts.getOrNull(0) ?: return listOf(UIMessagePart.Text("Invalid GitHub path"))
        val repo = parts.getOrNull(1) ?: return listOf(UIMessagePart.Text("Invalid GitHub path"))
        val branch = parts.getOrNull(2) ?: "main"
        val subPath = parts.getOrNull(3) ?: ""

        val token = EcosystemManager.getGitHubToken()
        val hasToken = token.isNotEmpty()

        val apiUrl = if (subPath.isNotEmpty()) {
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$subPath"
        } else if (hasToken) {
            "https://api.github.com/repos/$owner/$repo/contents/"
        } else {
            "https://api.github.com/search/code?q=filename:SKILL.md+repo:$owner/$repo"
        }

        val content = fetchUrl(apiUrl, token)
        if (content.startsWith("ERROR:")) {
            val tip = if (!hasToken)
                "Tip: add GitHub token at Settings > Ecosystem"
            else
                "Tip: check repo exists and token has repo/read scope"
            return listOf(UIMessagePart.Text("GitHub: $content\n$tip"))
        }

        val skillName = repo.lowercase().replace(Regex("[^a-z0-9]"), "-")
        val skillDir = File(ecosystemWorkspaceRoot, "skills/$skillName")
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(
            "---\nname: $skillName\ndescription: Skill from $owner/$repo\n---\n\n${content.take(10000)}"
        )
        writeLockEntry(skillName, "github:$owner/$repo", "latest")
        return listOf(UIMessagePart.Text(
            "Installed: $skillName (from github:$owner/$repo)\nPath: ${skillDir.absolutePath}"
        ))
    }

    private fun writeLockEntry(name: String, source: String, version: String) {
        val lockFile = File(ecosystemWorkspaceRoot, "skills-lock.json")
        val existing = if (lockFile.isFile) lockFile.readText().trim() else "{}"
        val entry = """"$name":{"source":"$source","version":"$version","installedAt":"${System.currentTimeMillis()}"}"""
        val updated = if (existing == "{}") "{$entry}" else existing.dropLast(1) + ",$entry}"
        lockFile.writeText(updated)
    }

    private fun fetchUrlAsBytes(urlStr: String): Pair<ByteArray, Boolean>? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "RinCore/3.4")
            conn.setRequestProperty("Accept", "application/zip, text/plain, application/json")
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.readBytes()
                val ct = conn.contentType ?: ""
                Pair(bytes, ct.contains("zip") || urlStr.contains("download"))
            } else null
        } catch (_: Exception) { null }
    }

    private fun fetchUrl(urlStr: String, token: String = ""): String {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "RinCore/3.4")
            conn.setRequestProperty("Accept", "application/json, text/plain, application/vnd.github.v3+json")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.connect()
            if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().readText()
            else "ERROR: HTTP ${conn.responseCode}"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
