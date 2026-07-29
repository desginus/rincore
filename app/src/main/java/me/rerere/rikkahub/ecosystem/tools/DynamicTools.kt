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
        description = "Dynamically add an MCP server connection. Args: {name, url, transport: sse|streamable_http}",
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
                val url = obj["url"]?.jsonPrimitive?.content
                    ?: return@Tool listOf(UIMessagePart.Text("Missing: url"))
                val transport = obj["transport"]?.jsonPrimitive?.content ?: "streamable_http"

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
                    "MCP server added: $name ($transport)\n$url\nConnecting... tools will be available shortly."
                ))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Failed to add MCP server: ${e.message}"))
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

    private fun installFromGitHub(repoPath: String): List<UIMessagePart> {
        val parts = repoPath.split("/", limit = 4)
        val owner = parts.getOrNull(0) ?: return listOf(UIMessagePart.Text("Invalid format"))
        val repo = parts.getOrNull(1) ?: return listOf(UIMessagePart.Text("Invalid format"))
        val branch = parts.getOrNull(2) ?: "main"
        val path = parts.getOrNull(3) ?: ""

        val apiUrl = if (path.isNotEmpty()) {
            "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch"
        } else {
            "https://api.github.com/search/code?q=filename:SKILL.md+repo:$owner/$repo"
        }

        val content = fetchUrl(apiUrl)
        if (content.startsWith("ERROR:")) {
            return listOf(UIMessagePart.Text("GitHub fetch failed: $content"))
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

    private fun installFromClawHub(slug: String): List<UIMessagePart> {
        val apiUrl = "https://clawhub.ai/api/skills/$slug"
        val content = fetchUrl(apiUrl)
        if (content.startsWith("ERROR:")) {
            return listOf(UIMessagePart.Text("ClawHub query failed: $content"))
        }

        val skillName = slug.split("/").last().lowercase().replace(Regex("[^a-z0-9]"), "-")
        val skillDir = File(ecosystemWorkspaceRoot, "skills/$skillName")
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(content.take(10000))

        return listOf(UIMessagePart.Text(
            "Installed: $skillName (from ClawHub: $slug)\nPath: ${skillDir.absolutePath}"
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
