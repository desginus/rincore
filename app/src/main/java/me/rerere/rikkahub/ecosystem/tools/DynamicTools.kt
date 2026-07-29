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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Charsets
import kotlin.uuid.Uuid

object DynamicTools {
    private var mcpManager: McpManager? = null
    private var ecosystemWorkspaceRoot: String = ""

    fun initialize(mcp: McpManager, workspaceRoot: String) {
        mcpManager = mcp
        ecosystemWorkspaceRoot = workspaceRoot
    }

    fun all(): List<Tool> = listOf(
        createMcpConnectTool(),
        createClawhubInstallTool(),
    )

    private fun createMcpConnectTool(): Tool = Tool(
        name = "mcp_connect",
        description = "Dynamically add an MCP server. Args: {name, url, transport: sse|streamable_http|stdio, command: shell command for stdio mode}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { true },
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
                            ?: return@Tool listOf(UIMessagePart.Text("stdio mode requires: command (shell command to launch MCP server)"))
                        listOf(
                            UIMessagePart.Text(
                                "stdio MCP mode: Launch the server first with:\n" +
                                "  $command &\n" +
                                "Then connect with mcp_connect using streamable_http or sse.\n" +
                                "stdio subprocess management is handled via workspace_shell."
                            )
                        )
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

    private fun createClawhubInstallTool(): Tool = Tool(
        name = "clawhub_install",
        description = "Install a skill from ClawHub or GitHub. Args: {slug: @owner/name or github:owner/repo/path}",
        systemPrompt = { _, _ -> "" },
        needsApproval = { true },
        execute = { input: JsonElement ->
            try {
                val obj = input as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Invalid args"))
                val slug = obj["slug"]?.jsonPrimitive?.content
                    ?: return@Tool listOf(UIMessagePart.Text("Missing: slug"))

                val result = when {
                    slug.startsWith("github:") -> installFromGitHub(slug.removePrefix("github:"))
                    slug.startsWith("@") -> installFromClawHub(slug)
                    else -> listOf(UIMessagePart.Text(
                        "Unsupported slug format. Use @owner/name or github:owner/repo/path"
                    ))
                }
                EcosystemManager.refresh()
                result
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Install failed: ${e.message}"))
            }
        },
    )

    private fun installFromClawHub(slug: String): List<UIMessagePart> {
        // 解析 @owner/name 格式
        val (owner, skillSlug) = if (slug.startsWith("@")) {
            val parts = slug.removePrefix("@").split("/", limit = 2)
            Pair(parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: parts.getOrNull(0) ?: "")
        } else {
            Pair(null, slug)
        }

        // ClawHub download API: GET /api/v1/download?slug={slug}&ownerHandle={owner}
        val apiUrl = buildString {
            append("https://clawhub.ai/api/v1/download")
            append("?slug=$skillSlug")
            if (owner != null) append("&ownerHandle=$owner")
        }

        val result = fetchUrlAsBytes(apiUrl)
        if (result == null) {
            return listOf(UIMessagePart.Text("ClawHub query failed: network error for $skillSlug"))
        }

        val skillDir = File(ecosystemWorkspaceRoot, "skills/$skillSlug")
        skillDir.mkdirs()

        // 如果是 ZIP 响应, 解压到技能目录
        val (content, isZip) = result
        if (isZip) {
            // ZIP — 尝试解压 (简单实现：保存为 zip 并提示)
            val zipFile = File(skillDir, "_download.zip")
            zipFile.writeBytes(content)
            listOf(
                UIMessagePart.Text(
                    "Downloaded: $slug (ZIP archive)\n" +
                    "Saved to: ${zipFile.absolutePath}\n" +
                    "Use workspace_shell to unzip: unzip ${zipFile.absolutePath} -d ${skillDir.absolutePath}"
                )
            )
        } else {
            // 纯文本 — 直接作为 SKILL.md
            val text = String(content, Charsets.UTF_8)
            File(skillDir, "SKILL.md").writeText(text.take(50000))
            listOf(UIMessagePart.Text(
                "Installed: $skillSlug (from ClawHub: $slug)\nPath: ${skillDir.absolutePath}"
            ))
        }
    }

    private fun fetchUrlAsBytes(urlStr: String): Pair<ByteArray, Boolean>? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "RinCore/3.3")
            conn.setRequestProperty("Accept", "application/zip, text/plain, application/json")
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.connect()
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.readBytes()
                val contentType = conn.contentType ?: ""
                val isZip = contentType.contains("zip") || urlStr.contains("download")
                Pair(bytes, isZip)
            } else {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText()?.take(500)
                } catch (_: Exception) { "" }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun installFromGitHub(repoPath: String): List<UIMessagePart> {
        val parts = repoPath.split("/", limit = 4)
        val owner = parts.getOrNull(0) ?: return listOf(UIMessagePart.Text("Invalid GitHub path"))
        val repo = parts.getOrNull(1) ?: return listOf(UIMessagePart.Text("Invalid GitHub path"))
        val branch = parts.getOrNull(2) ?: "main"
        val subPath = parts.getOrNull(3) ?: ""

        // GitHub raw content API
        val apiUrl = if (subPath.isNotEmpty()) {
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$subPath"
        } else {
            // Search for SKILL.md in repo
            "https://api.github.com/search/code?q=filename:SKILL.md+repo:$owner/$repo"
        }

        val content = fetchUrl(apiUrl)
        if (content.startsWith("ERROR:")) {
            return listOf(UIMessagePart.Text("GitHub fetch failed: $content\nTip: Ensure repo is public and path is correct."))
        }

        val skillName = repo.lowercase().replace(Regex("[^a-z0-9]"), "-")
        val skillDir = File(ecosystemWorkspaceRoot, "skills/$skillName")
        skillDir.mkdirs()

        File(skillDir, "SKILL.md").writeText(
            "---\nname: $skillName\ndescription: Skill from $owner/$repo\n---\n\n${content.take(10000)}"
        )

        return listOf(UIMessagePart.Text(
            "Installed: $skillName (from github:$owner/$repo)\nPath: ${skillDir.absolutePath}"
        ))
    }

    private fun fetchUrl(urlStr: String): String {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "RinCore/3.2")
            conn.setRequestProperty("Accept", "application/json, text/plain")
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.connect()
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                "ERROR: HTTP ${conn.responseCode}"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
