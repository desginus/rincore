/**
 * 动态工具 — 模块: D. 生态与技能
 *
 * 职责: 生态工具 (mcp_connect/plugin_install 等 5 个) + MCP 运行时工具注入。
 * 懒加载 (v3.5.3): MCP 工具经 getMcpTools 合并到域池, 不直接注入函数定义。
 * sanitize 与 ChatService.sanitizeMcpName 保持一致 (去重关键)。
 *
 * 问题定位: MCP 工具不出现/冷启动 token 高 → 查本文件 + GenerationHandler
 */
package me.rerere.rikkahub.ecosystem.tools


/* ───【自研】DynamicTools.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.ecosystem.EcosystemManager
import me.rerere.rikkahub.ecosystem.plugin.ClaudePluginParser
import java.io.ByteArrayInputStream
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.zip.ZipInputStream
import android.util.Log
import kotlin.text.Charsets
import kotlin.uuid.Uuid

object DynamicTools {
    private const val TAG = "DynamicTools"
    // v3.6.94: OkHttp 走系统代理 (fake-ip 环境) — HttpURLConnection 不走
    // 系统代理导致 clawhub_install 直连失败 (用户实测 198.18.x 保留段)
    private val httpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(120)) // v3.6.118: 大 ZIP 下载不稳定 (原 30s)
            .followRedirects(true)
            .followSslRedirects(true)
        // v3.6.95: 显式代理 (fake-ip/VPN 环境) — 设置页 Ecosystem 配置 host:port
        val proxy = runCatching { me.rerere.rikkahub.ecosystem.EcosystemManager.getClawhubProxy() }.getOrDefault("")
        if (proxy.isNotBlank()) {
            runCatching {
                val host = proxy.substringBefore(":").trim()
                val port = proxy.substringAfter(":", "").trim().toIntOrNull() ?: 0
                if (host.isNotEmpty() && port in 1..65535) {
                    builder.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
                }
            }
        }
        builder.build()
    }
    private var mcpManager: McpManager? = null
    private var settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore? = null
    private var ecosystemWorkspaceRoot: String = ""
    // ClawHub 安装的 skill 落此目录 — 与 Agent Skills(SkillManager.getSkillsDir) 同一目录,
    // 修复: 此前落 ecosystem 私有目录, proot/use_skill 不可见
    private var skillsRoot: String = ""
    /** v3.6.110: 技能落盘后回调 (Claw 插件技能根同步 — 安装后热生效) */
    var onSkillsChanged: (() -> Unit)? = null

    fun initialize(mcp: McpManager, workspaceRoot: String, skillsRoot: String = "", settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore? = null) {
        mcpManager = mcp
        this.settingsStore = settingsStore
        ecosystemWorkspaceRoot = workspaceRoot
        this.skillsRoot = skillsRoot.ifEmpty { File(workspaceRoot, "skills").absolutePath }
    }

    fun all(): List<Tool> {
        val tools = listOf(
            createMcpConnectTool(),
            createClawhubInstallTool(),
            createClawhubSearchTool(),
            createPluginInstallTool(),
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
                        // Android 侧无 python3 (error=2) — 默认经 workspace 沙箱启动
                        // (沙箱内有 Python/Node 运行时), workspaceId 取当前助手配置
                        val workspaceId = settingsStore?.settingsFlow?.value
                            ?.getCurrentAssistant()?.workspaceId
                        if (workspaceId == null) {
                            return@Tool listOf(UIMessagePart.Text(
                                "stdio 模式需要 workspace: 当前助手未设置工作区。\n" +
                                "请在助手设置中选择工作区, 或改用 UI 新建 STDIO 服务器并填写 Workspace ID。"
                            ))
                        }
                        val config = McpServerConfig.StdioTransportServer(
                            id = Uuid.random(),
                            commonOptions = McpCommonOptions(name = name),
                            command = command,
                            viaWorkspace = true,
                            workspaceId = workspaceId.toString(),
                        )
                        mcp.addClient(config)
                        persistServer(config)
                        listOf(UIMessagePart.Text(
                            "MCP server (stdio) spawned: $name\n" +
                            "Command: $command\n" +
                            "已通过工作区(${workspaceId.toString().take(8)})启动并绑定当前助手 — 工具下一步可用。"
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
                        persistServer(config)
                        listOf(UIMessagePart.Text(
                            "MCP server added: $name ($transport)\n$url\n已持久化并绑定当前助手。"
                        ))
                    }
                }
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("Failed: ${e.message}"))
            }
        },
    )

    /**
     * 持久化 mcp_connect 注册的服务器到配置并绑定当前助手 —
     * 对齐 UI 新建路径 (settings.mcpServers): 客户端列表可见 + 重启保留 + 工具进池。
     * 此前仅 addClient (会话级运行时) → UI 不可见/重启丢失/工具不绑定助手。
     */
    private suspend fun persistServer(config: McpServerConfig) {
        val store = settingsStore ?: return
        val settings = store.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        store.update(
            settings.copy(
                mcpServers = settings.mcpServers.filter { it.commonOptions.name != config.commonOptions.name } + config,
                assistants = settings.assistants.map { a ->
                    if (a.id == assistant.id) a.copy(mcpServers = a.mcpServers + config.id) else a
                }
            )
        )
        Log.i(TAG, "persistServer: ${config.commonOptions.name} persisted + bound to assistant")
    }

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
                        // v3.6.93: 唯一临时名 — 固定名会覆盖上次下载 (误装旧包)
                        val tmpFile = File(ecosystemWorkspaceRoot, "_plugin_download_${System.currentTimeMillis()}.zip")
                        tmpFile.writeBytes(bytes.first)
                        tmpFile
                    }
                    else -> return@Tool listOf(UIMessagePart.Text("Need zipFile or url"))
                }

                val parsed = try {
                    ClaudePluginParser.parsePluginZip(fileToParse)
                } catch (e: Exception) {
                    return@Tool listOf(UIMessagePart.Text("Failed to parse plugin ZIP: ${e.message}"))
                }
                if (parsed == null) {
                    return@Tool listOf(UIMessagePart.Text("Failed to parse plugin ZIP: 解析失败 (详见日志)"))
                }

                val (name, plugin) = parsed
                // v3.6.117: 目录用真实插件名 (非下载临时时间戳名), 重复安装覆盖旧版
                val realPluginName = plugin.manifest.name.ifBlank { name }
                    .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val targetDir = File(ecosystemWorkspaceRoot, "plugins/$realPluginName")
                // 重复安装: 清旧内容 (覆盖安装语义)
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()

                // 安装 skills — v3.6.110: 落插件目录 (插件是插件, 技能分开存放),
                // 注册走额外技能根 (前缀 <插件名>__), 技能工具名自动带前缀,
                // 与其他 Skill 隔离 — 不混入 /skills
                plugin.skills.forEach { skill ->
                    val skillDir = File(targetDir, "skills/${skill.name}")
                    skillDir.mkdirs()
                    File(skillDir, "SKILL.md").writeText(skill.content)
                }
                // v3.6.110: 安装后立即同步技能根 (不等重启) — 注册链热生效
                runCatching { onSkillsChanged?.invoke() }

                // 安装 commands
                plugin.commands.forEach { cmd ->
                    val cmdDir = File(targetDir, "commands")
                    cmdDir.mkdirs()
                    File(cmdDir, "${cmd.name}.md").writeText(cmd.content)
                }
                // v3.6.118: 写 plugin.json 元数据 + 刷新插件列表 — 修复: 此前
                // installFromParsed 从未被调用, plugin.json 从未写入, 插件页恒空
                me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry
                    .installFromParsed(realPluginName, plugin, targetDir)

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

                writeLockEntry(realPluginName, "plugin:$realPluginName", plugin.manifest.version)

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
                // v3.6.118: 清理下载临时文件 (url 通道的 _plugin_download_*.zip)
                if (downloadUrl != null) runCatching { fileToParse.delete() }
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

        // v3.6.97: 直连失败自动探测常见本地代理端口 (fake-ip/VPN 环境)
        var result = fetchUrlAsBytes(apiUrl)
        if (result == null) {
            for (port in listOf(7890, 1080, 8118, 10809, 7897)) {
                val proxied = fetchUrlAsBytesWithProxy(apiUrl, "127.0.0.1", port)
                if (proxied != null) {
                    Log.i(TAG, "clawhub via proxy 127.0.0.1:$port")
                    result = proxied
                    break
                }
            }
        }
        if (result == null) {
            return listOf(UIMessagePart.Text(
                "ClawHub: network error for $skillSlug。\n" +
                "已尝试直连与常见本地代理端口 (7890/1080/8118/10809/7897) 均失败。\n" +
                "可在 设置 → 生态 配置 HTTP 代理 (host:port) 后重启, 或改用 github:owner/repo 源。"
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

    private suspend fun installFromGitHub(repoPath: String): List<UIMessagePart> {
        val parts = repoPath.split("/", limit = 4)
        val owner = parts.getOrNull(0) ?: return listOf(UIMessagePart.Text("Invalid GitHub path"))
        val repo = parts.getOrNull(1) ?: return listOf(UIMessagePart.Text("Invalid GitHub path"))
        val branch = parts.getOrNull(2) ?: "main"
        val subPath = parts.getOrNull(3) ?: ""

        val token = EcosystemManager.getGitHubToken()
        val hasToken = token.isNotEmpty()

        // v3.6.97: 无 token 且无子路径时用 codeload ZIP 下载 (无需认证) —
        // 此前走 search/code API 无 token 必 401/403, github 源实际不可用
        if (!hasToken && subPath.isEmpty()) {
            val zipUrl = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch"
            val zipResult = fetchUrlAsBytes(zipUrl)
            if (zipResult == null) {
                return listOf(UIMessagePart.Text(
                    "GitHub: 下载失败 ($zipUrl)。\n" +
                    "可先在 设置 → 生态 配置 GitHub token, 或改用 url: 参数直链安装。"
                ))
            }
            return installZipContent(zipResult.first, repo)
        }

        val apiUrl = if (subPath.isNotEmpty()) {
            "https://raw.githubusercontent.com/$owner/$repo/$branch/$subPath"
        } else {
            "https://api.github.com/repos/$owner/$repo/contents/"
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
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "RinCore/3.6")
                .header("Accept", "application/zip, text/plain, application/json")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val ct = resp.header("Content-Type") ?: ""
                Pair(bytes, ct.contains("zip") || urlStr.contains("download"))
            }
        } catch (_: Exception) { null }
    }

    /** v3.6.109: frontmatter name 改写为 <插件名>__<技能名> (与其他 Skill 分开) */
    private fun rewriteSkillFrontmatterName(content: String, newName: String): String {
        val lines = content.lines().toMutableList()
        var inFrontmatter = false
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line == "---") {
                if (!inFrontmatter) { inFrontmatter = true; continue }
                break
            }
            if (inFrontmatter && line.startsWith("name:")) {
                lines[i] = lines[i].replaceRange(
                    lines[i].indexOf("name:") + 5,
                    lines[i].length,
                    " $newName",
                )
                break
            }
        }
        return lines.joinToString("\n")
    }

    private fun fetchUrlAsBytesWithProxy(urlStr: String, host: String, port: Int): Pair<ByteArray, Boolean>? {
        return try {
            val proxyClient = OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(true)
                .followSslRedirects(true)
                .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
                .build()
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "RinCore/3.6")
                .header("Accept", "application/zip, text/plain, application/json")
                .build()
            proxyClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                val ct = resp.header("Content-Type") ?: ""
                Pair(bytes, ct.contains("zip") || urlStr.contains("download"))
            }
        } catch (_: Exception) { null }
    }

    private fun fetchUrl(urlStr: String, token: String = ""): String {
        return try {
            val builder = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "RinCore/3.6")
                .header("Accept", "application/json, text/plain, application/vnd.github.v3+json")
            if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
            httpClient.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() ?: ""
                else "ERROR: HTTP ${resp.code}"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
