package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.JobCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.checkDifferent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val TAG = "McpManager"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val BASE_RECONNECT_DELAY_MS = 1000L
private const val MAX_RECONNECT_DELAY_MS = 30000L

// OAuth 相关常量
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L // 令牌到期前 60s 视为需要刷新
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    private val appEventBus: AppEventBus,
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES) // v3.6.0: 3min→5min 用户要求充分连接
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val client = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    private val oauthClient = McpOAuthClient(okHttpClient)

    private val clients: MutableMap<McpServerConfig, Client> = mutableMapOf()
    private val stdioProcesses = mutableMapOf<Uuid, Process>()
    private val reconnectJobs: MutableMap<Uuid, Job> = mutableMapOf()
    private val reconnectAttempts: MutableMap<Uuid, Int> = mutableMapOf()
    private val authorizationJobs: MutableMap<Uuid, Job> = mutableMapOf()
    val syncingStatus = MutableStateFlow<Map<Uuid, McpStatus>>(mapOf())

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .collect { mcpServerConfigs ->
                    runCatching {
                        Log.i(TAG, "update configs: $mcpServerConfigs")
                        val newConfigs = mcpServerConfigs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                        val currentConfigs = clients.keys.toList()
                        val (toAdd, toRemove) = currentConfigs.checkDifferent(
                            other = newConfigs,
                            eq = { a, b -> a.id == b.id }
                        )
                        Log.i(TAG, "to_add: $toAdd")
                        Log.i(TAG, "to_remove: $toRemove")
                        toAdd.forEach { cfg ->
                            appScope.launch {
                                runCatching { addClient(cfg) }
                                    .onFailure { it.printStackTrace() }
                            }
                        }
                        toRemove.forEach { cfg ->
                            appScope.launch { removeClient(cfg) }
                        }
                    }.onFailure {
                        it.printStackTrace()
                    }
                }
        }
    }

    fun getClient(config: McpServerConfig): Client? {
        return clients.entries.find { it.key.id == config.id }?.value
    }

    fun getAllAvailableTools(): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        // 工具声明静态化 — 仅由配置决定 (enable + assistant 绑定), 不受连接状态影响。
        // 根因: 服务器连接波动 → Error → 工具从数组消失 → tools 数组每轮变化 →
        // 请求体前缀断裂 → 缓存阶梯化 (用户环境 MCP 工具多且波动频繁)。
        // 失败在 callTool 时显式报错 (可见化保留)。
        return settings.mcpServers
            .filter {
                it.commonOptions.enable && it.id in assistant.mcpServers
            }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject): List<UIMessagePart> {
        // 工具声明已静态化, 连接状态在调用时检查 — 失败在此明确报错
        val status = syncingStatus.value[serverId]
        if (status is McpStatus.Error) {
            return listOf(
                UIMessagePart.Text(
                    "工具执行失败: MCP 服务器连接异常 (${status.message})。请检查服务器状态后重试。"
                )
            )
        }
        val entry = clients.entries.find { it.key.id == serverId }
        var client = entry?.value
            ?: return listOf(
                UIMessagePart.Text(
                    "工具执行失败: MCP 服务器未连接或连接已断开 (serverId=$serverId)。" +
                        "请检查 MCP 服务器状态后重试。"
                )
            )
        var config = entry.key

        // 调用前确保 OAuth 令牌新鲜。若发生刷新，已连接的 transport 仍携带过期令牌
        val freshConfig = ensureFreshToken(config)
        if (freshConfig.commonOptions.oauth?.accessToken != config.commonOptions.oauth?.accessToken) {
            Log.i(TAG, "callTool: token refreshed, reconnecting ${config.commonOptions.name}")
            addClient(freshConfig)
            val newEntry = clients.entries.find { it.key.id == serverId }
                ?: return listOf(UIMessagePart.Text("Failed to execute tool, because no such mcp client for the tool"))
            client = newEntry.value
            config = newEntry.key
        }

        Log.i(TAG, "callTool: $toolName / $args (server: ${config.commonOptions.name})")

        if (client.transport == null) client.connect(getTransport(config))
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = toolName,
                arguments = args,
            ),
        )
        // v3.6.21: SSE 会话断开 → SDK doClose → handlerScope.cancel() →
        // request 挂起抛 JobCancellationException ("Job was cancelled")。
        // 场景: 百炼 SSE 流 (GET /sse) 在工具调用期间断开 (空闲/网关), 质朴 HTTP 无此问题。
        // 处理: 用户停止 (不活跃) 传播; 生成活跃时重连 transport + 重试一次
        // (搜索类工具幂等可安全重试; 重连失败则抛原异常)。
        val result = try {
            client.callTool(request, options = RequestOptions(timeout = 300.seconds))
        } catch (e: JobCancellationException) {
            if (!kotlin.coroutines.coroutineContext.isActive) throw e
            Log.w(TAG, "callTool cancelled (SSE session dropped?), reconnecting & retry once: ${e.message}")
            e.printStackTrace()
            addClient(config) // 重连: removeClient + 新 Client + connect + sync
            val retryEntry = clients.entries.find { it.key.id == serverId }
                ?: throw e
            retryEntry.value.callTool(request, options = RequestOptions(timeout = 300.seconds))
        }
        return result.content.map {
            when(it) {
                is TextContent -> UIMessagePart.Text(it.text)
                is ImageContent -> convertImageContentToFilePart(it)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(it))
            }
        }
    }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val ext = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$ext",
            mimeType = image.mimeType,
        )
        val uri = filesManager.getFile(entity).toUri()
        Log.i(TAG, "convertImageContentToFilePart: saved mcp image to $uri")
        return UIMessagePart.Image(url = uri.toString())
    }

    private suspend fun getTransport(config: McpServerConfig): AbstractTransport = when (config) {
        is McpServerConfig.SseTransportServer -> {
            SseClientTransport(
                urlString = config.url,
                client = client,
                requestBuilder = {
                    headers.appendAll(StringValues.build {
                        config.resolveHeaders().forEach {
                            append(it.first, it.second)
                        }
                    })
                },
            )
        }

        is McpServerConfig.StreamableHTTPServer -> {
            StreamableHttpClientTransport(
                url = config.url,
                client = client,
                requestBuilder = {
                    headers.appendAll(StringValues.build {
                        config.resolveHeaders().forEach {
                            append(it.first, it.second)
                        }
                    })
                }
            )
        }

        is McpServerConfig.StdioTransportServer -> {
            check(config.command.isNotBlank()) { "stdio mode requires: command" }
            // 启动子进程, stdin/stdout 走 JSON-RPC, stderr 按严重级别转发
            // command 按空白拆分 (支持 'python3 /path/server.py' 单字段写法)
            val cmdParts = config.command.split(Regex("\\s+")).filter { it.isNotBlank() }
            val process = if (config.viaWorkspace) {
                // 通过 workspace 沙箱启动 — 沙箱内有 Python/Node 运行时,
                // 进程的 stdin/stdout 由本 Transport 接管 (proot 由 Android 侧启动, 流可桥接)
                val repo = workspaceRepository
                    ?: throw IllegalStateException("viaWorkspace stdio requires WorkspaceRepository")
                val p = runCatching {
                    repo.launchProcess(config.workspaceId, config.command, "")
                }.getOrElse { e ->
                    Log.e(TAG, "viaWorkspace launch failed: ${e.message}")
                    throw IllegalStateException("workspace 启动 MCP 服务器失败: ${e.message}", e)
                }
                if (p == null) throw IllegalStateException("workspace 启动失败: workspace 不存在或 proot 不可用")
                p
            } else {
                // 直接启动 (Android 原生可执行 / Java 服务器)。失败时 (如 python3
                // error=2 不存在) 自动回退 workspace 沙箱启动 — 沙箱内有运行时。
                val process2 = try {
                    ProcessBuilder(cmdParts + config.args).start()
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "direct stdio launch failed: ${e.message}, falling back to workspace")
                    val workspaceId = settingsStore.settingsFlow.value
                        .getCurrentAssistant().workspaceId
                    if (workspaceId == null) throw e  // 无 workspace 可回退
                    val wp = workspaceRepository?.launchProcess(workspaceId.toString(), config.command, "")
                        ?: throw e
                    // 回退成功后持久化 viaWorkspace — 后续启动直接走 workspace, 不再先失败一次
                    runCatching {
                        settingsStore.update { cur ->
                            cur.copy(mcpServers = cur.mcpServers.map { s ->
                                if (s.id == config.id && s is McpServerConfig.StdioTransportServer) {
                                    s.copy(viaWorkspace = true, workspaceId = workspaceId.toString())
                                } else s
                            })
                        }
                    }
                    wp
                }
                process2
            }
            stdioProcesses[config.id] = process
            StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
                error = process.errorStream.asSource().buffered(),
            ) { line ->
                when {
                    line.contains("error", ignoreCase = true) -> StdioClientTransport.StderrSeverity.FATAL
                    line.contains("warning", ignoreCase = true) -> StdioClientTransport.StderrSeverity.WARNING
                    else -> StdioClientTransport.StderrSeverity.INFO
                }
            }
        }
    }

    /** 合并用户自定义请求头与 OAuth Bearer 令牌。 */
    private fun McpServerConfig.resolveHeaders(): List<Pair<String, String>> {
        val base = commonOptions.headers
        val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
        val hasAuthHeader = base.any { it.first.equals("Authorization", ignoreCase = true) }
        return if (!token.isNullOrBlank() && !hasAuthHeader) {
            base + ("Authorization" to "Bearer $token")
        } else {
            base
        }
    }

    suspend fun addClient(configInput: McpServerConfig) = withContext(Dispatchers.IO) {
        val config = ensureFreshToken(configInput)
        cancelReconnect(config.id)
        reconnectAttempts[config.id] = 0

        // getTransport 可能抛异常 (stdio command 非法/进程启动失败) — 必须在
        // 错误处理内, 否则 removeClient 后中途退出 → clients 缺失而配置里
        // 工具仍在 → 调用报 'no such mcp client' (状态撕裂)
        val transport = try {
            getTransport(config)
        } catch (e: Exception) {
            Log.e(TAG, "addClient: getTransport failed for ${config.commonOptions.name}: ${e.message}", e)
            setStatus(config = config, status = McpStatus.Error(e.message ?: e.javaClass.name))
            return@withContext
        }
        removeClient(config) // Remove old entry after transport created successfully
        val client = Client(
            clientInfo = Implementation(
                name = config.commonOptions.name,
                version = "1.0",
            )
        )

        // 注册 transport 回调以支持自动重连
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
            val currentStatus = syncingStatus.value[config.id]
            // 只有在已连接状态下才触发重连，避免正常关闭时重连
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.message}")
            if (isSseStreamGiveUpError(error)) return@onError
            val currentStatus = syncingStatus.value[config.id]
            // 只有在已连接状态下才触发重连
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        clients[config] = client
        runCatching {
            setStatus(config = config, status = McpStatus.Connecting)
            client.connect(transport)
            sync(config)
            setStatus(config = config, status = McpStatus.Connected)
            reconnectAttempts[config.id] = 0 // 重置重连计数
            Log.i(TAG, "addClient: connected ${config.commonOptions.name}")
        }.onFailure {
            it.printStackTrace()
            if (needsAuthorization(config, it)) {
                setStatus(config = config, status = McpStatus.NeedsAuthorization)
            } else {
                setStatus(config = config, status = McpStatus.Error(it.message ?: it.javaClass.name))
            }
        }
    }

    private suspend fun sync(config: McpServerConfig) {
        val client = clients[config] ?: return

        setStatus(config = config, status = McpStatus.Connecting)

        // Update tools
        if (client.transport == null) {
            client.connect(getTransport(config))
        }
        val serverTools = client.listTools().tools
        Log.i(TAG, "sync: tools: $serverTools")

        // 从 MCP 服务器返回构建工具列表
        val resolvedTools = serverTools.map { st ->
            McpTool(
                name = st.name,
                description = st.description,
                enable = true,
                inputSchema = st.inputSchema.toSchema(),
            )
        }

        // 幂等化: 工具列表 (name/description/enable) 与现有配置相同则跳过写入 —
        // 避免无意义 settings 广播 → toolRouter 重建 → system/tools 变化 → 缓存失效
        val currentSettings = settingsStore.settingsFlow.value
        val existingIndex = currentSettings.mcpServers.indexOfFirst { it.id == config.id }
        val toolsUnchanged = existingIndex >= 0 && run {
            val existing = currentSettings.mcpServers[existingIndex].commonOptions.tools
            existing.size == resolvedTools.size &&
                existing.zip(resolvedTools).all { (a, b) ->
                    a.name == b.name && a.description == b.description && a.enable == b.enable
                }
        }

        if (!toolsUnchanged) {
            settingsStore.update { old ->
                val idx = old.mcpServers.indexOfFirst { it.id == config.id }
                if (idx >= 0) {
                    // 更新已有配置
                    old.copy(mcpServers = old.mcpServers.mapIndexed { i, sc ->
                        if (i != idx) sc
                        else sc.clone(commonOptions = sc.commonOptions.copy(tools = resolvedTools))
                    })
                } else {
                    // 动态添加: config + tools 写入 settings
                    val newConfig = config.clone(
                        commonOptions = config.commonOptions.copy(tools = resolvedTools, enable = true)
                    )
                    val newServers = old.mcpServers + newConfig
                    // 同时将 config.id 加入当前 assistant 白名单
                    val assistant = old.getCurrentAssistant()
                    val newAssistants = if (config.id !in assistant.mcpServers) {
                        old.assistants.map { a ->
                            if (a.id != assistant.id) a
                            else a.copy(mcpServers = a.mcpServers + config.id)
                        }
                    } else old.assistants
                    old.copy(mcpServers = newServers, assistants = newAssistants)
                }
            }
        } else {
            Log.i(TAG, "sync: tools unchanged for ${config.commonOptions.name}, skip settings write (cache-friendly)")
        }

        // 更新 clients 中的 config (携带最新工具)
        clients.remove(config)
        clients.put(
            config.clone(commonOptions = config.commonOptions.copy(tools = resolvedTools)),
            client
        )

        setStatus(config = config, status = McpStatus.Connected)
    }

    suspend fun syncAll() = withContext(Dispatchers.IO) {
        clients.keys.toList().forEach { config ->
            runCatching {
                sync(config)
            }.onFailure {
                it.printStackTrace()
                if (needsAuthorization(config, it)) {
                    setStatus(config, McpStatus.NeedsAuthorization)
                } else {
                    setStatus(config, McpStatus.Error(it.message ?: it.javaClass.name))
                }
            }
        }
    }

    suspend fun removeClient(config: McpServerConfig) = withContext(Dispatchers.IO) {
        cancelReconnect(config.id)
        val toRemove = clients.entries.filter { it.key.id == config.id }
        toRemove.forEach { entry ->
            runCatching {
                entry.value.close()
            }.onFailure {
                it.printStackTrace()
            }
            stdioProcesses.remove(entry.key.id)?.let { proc ->
                runCatching { proc.destroy() }.onFailure { it.printStackTrace() }
            }
            clients.remove(entry.key)
            syncingStatus.emit(syncingStatus.value.toMutableMap().apply { remove(entry.key.id) })
            Log.i(TAG, "removeClient: ${entry.key} / ${entry.key.commonOptions.name}")
        }
        reconnectAttempts.remove(config.id)
    }

    private fun scheduleReconnect(config: McpServerConfig) {
        val configId = config.id
        val currentAttempt = (reconnectAttempts[configId] ?: 0) + 1

        if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached for ${config.commonOptions.name}")
            appScope.launch {
                setStatus(config, McpStatus.Error("连接断开，已达最大重连次数"))
            }
            return
        }

        reconnectAttempts[configId] = currentAttempt

        // 取消之前的重连任务
        reconnectJobs[configId]?.cancel()

        // 计算指数退避延迟
        val delayMs = calculateBackoffDelay(currentAttempt)
        Log.i(TAG, "Scheduling reconnect for ${config.commonOptions.name}, attempt $currentAttempt/$MAX_RECONNECT_ATTEMPTS, delay ${delayMs}ms")

        reconnectJobs[configId] = appScope.launch {
            try {
                setStatus(config, McpStatus.Reconnecting(currentAttempt, MAX_RECONNECT_ATTEMPTS))
                delay(delayMs)

                // 检查配置是否仍然启用
                val currentConfig = settingsStore.settingsFlow.value.mcpServers
                    .find { it.id == configId && it.commonOptions.enable }

                if (currentConfig == null) {
                    Log.i(TAG, "Config disabled or removed, cancelling reconnect for ${config.commonOptions.name}")
                    return@launch
                }

                Log.i(TAG, "Attempting reconnect for ${config.commonOptions.name}")
                reconnectClient(currentConfig)
            } catch (e: CancellationException) {
                Log.i(TAG, "Reconnect cancelled for ${config.commonOptions.name}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reconnect failed for ${config.commonOptions.name}", e)
                // 继续尝试重连
                scheduleReconnect(config)
            }
        }
    }

    private fun cancelReconnect(configId: Uuid) {
        reconnectJobs[configId]?.cancel()
        reconnectJobs.remove(configId)
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        // 指数退避: baseDelay * 2^(attempt-1)，最大不超过 maxDelay
        val exponentialDelay = BASE_RECONNECT_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(10))
        return exponentialDelay.coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private suspend fun reconnectClient(configInput: McpServerConfig) = withContext(Dispatchers.IO) {
        val config = ensureFreshToken(configInput)
        // 先关闭旧客户端
        val oldEntry = clients.entries.find { it.key.id == config.id }
        if (oldEntry != null) {
            runCatching { oldEntry.value.close() }.onFailure { it.printStackTrace() }
            stdioProcesses.remove(oldEntry.key.id)?.let { proc ->
                runCatching { proc.destroy() }.onFailure { it.printStackTrace() }
            }
            clients.remove(oldEntry.key)
        }

        val transport = getTransport(config)
        val client = Client(
            clientInfo = Implementation(
                name = config.commonOptions.name,
                version = "1.0",
            )
        )

        // 注册回调
        transport.onClose {
            Log.i(TAG, "Transport closed for ${config.commonOptions.name}")
            val currentStatus = syncingStatus.value[config.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        transport.onError { error ->
            Log.e(TAG, "Transport error for ${config.commonOptions.name}: ${error.message}")
            if (isSseStreamGiveUpError(error)) return@onError
            val currentStatus = syncingStatus.value[config.id]
            if (currentStatus == McpStatus.Connected) {
                scheduleReconnect(config)
            }
        }

        clients[config] = client
        setStatus(config, McpStatus.Connecting)
        runCatching {
            client.connect(transport)
            sync(config)
        }.onSuccess {
            setStatus(config, McpStatus.Connected)
            reconnectAttempts[config.id] = 0 // 重置重连计数
            Log.i(TAG, "Reconnected successfully: ${config.commonOptions.name}")
        }.onFailure { e ->
            // 令牌失效/需要授权时停止重连，引导用户重新授权
            if (needsAuthorization(config, e)) {
                cancelReconnect(config.id)
                setStatus(config, McpStatus.NeedsAuthorization)
            } else {
                throw e
            }
        }
    }

    private suspend fun setStatus(config: McpServerConfig, status: McpStatus) {
        syncingStatus.emit(syncingStatus.value.toMutableMap().apply {
            put(config.id, status)
        })
    }

    fun getStatus(config: McpServerConfig): Flow<McpStatus> {
        return syncingStatus.map { it[config.id] ?: McpStatus.Idle }
    }

    // =====================================================================
    // OAuth 2.1 授权 (MCP 规范 2025-11-25)
    // =====================================================================

    /**
     * 发起 OAuth 授权流程：发现元数据 -> 动态注册 -> 浏览器授权 -> 交换令牌 -> 重新连接。
     * 通过 [Context] 打开 Custom Tab，用户完成后经 deep link 回调继续。
     */
    fun startAuthorization(config: McpServerConfig, context: Context) {
        // 若已有进行中的授权，先取消，避免并发的挂起协程互相覆盖状态
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            setStatus(config, McpStatus.Authorizing)
            runCatching { authorizeInternal(config, context.applicationContext) }
                .onFailure {
                    // 用户主动取消：状态由 cancelAuthorization 负责回退，这里不覆盖
                    if (it is CancellationException) return@onFailure
                    it.printStackTrace()
                    setStatus(config, McpStatus.Error(it.message ?: "OAuth authorization failed"))
                }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    /** 取消进行中的 OAuth 授权（用户中止），并回退到需要授权状态。 */
    fun cancelAuthorization(config: McpServerConfig) {
        authorizationJobs.remove(config.id)?.cancel()
        appScope.launch { setStatus(config, McpStatus.NeedsAuthorization) }
    }

    private suspend fun authorizeInternal(config: McpServerConfig, context: Context) =
        withContext(Dispatchers.IO) {
            val serverUrl = config.serverUrl
            require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }

            // 1. 发现受保护资源 & 授权服务器元数据
            val prm = oauthClient.discoverProtectedResource(serverUrl)
            val issuer = prm.authorizationServers.firstOrNull()
                ?: error("受保护资源未声明授权服务器")
            val asMeta = oauthClient.discoverAuthorizationServer(issuer)
            val authEndpoint = asMeta.authorizationEndpoint
                ?: error("授权服务器缺少 authorization_endpoint")
            val tokenEndpoint = asMeta.tokenEndpoint
                ?: error("授权服务器缺少 token_endpoint")

            // 2. 计算 scope
            val scope = config.commonOptions.oauth?.scope
                ?: prm.scopesSupported?.joinToString(" ")
                ?: asMeta.scopesSupported?.joinToString(" ")

            // 3. 客户端注册 (复用已注册的 client_id)
            val existing = config.commonOptions.oauth
            var clientId = existing?.clientId
            var clientSecret = existing?.clientSecret
            if (clientId.isNullOrBlank()) {
                val regEndpoint = asMeta.registrationEndpoint
                    ?: error("授权服务器不支持动态注册，且未预配置 client_id")
                val reg = oauthClient.registerClient(
                    registrationEndpoint = regEndpoint,
                    clientName = config.commonOptions.name,
                    redirectUri = MCP_OAUTH_REDIRECT_URI,
                    scope = scope,
                )
                clientId = reg.clientId
                clientSecret = reg.clientSecret
            }

            // 4. PKCE + state；持久化中间状态(端点/clientId)以便后续刷新
            val pkce = oauthClient.generatePkce()
            val state = oauthClient.generateState()
            val resource = McpOAuthClient.canonicalResource(serverUrl)

            persistOAuthState(
                config.id,
                (existing ?: McpOAuthState()).copy(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = asMeta.registrationEndpoint,
                    scope = scope,
                )
            )

            // 5. 打开浏览器授权
            val authUrl = oauthClient.buildAuthorizationUrl(
                authorizationEndpoint = authEndpoint,
                clientId = clientId,
                redirectUri = MCP_OAUTH_REDIRECT_URI,
                pkce = pkce,
                state = state,
                scope = scope,
                resource = resource,
            )
            // 6. 先建立回调订阅，再打开浏览器，避免快速回调在订阅生效前 emit 而丢失
            //    (AppEventBus 的 SharedFlow replay=0，无订阅者时的事件不会补发)
            val callback = coroutineScope {
                val subscribed = CompletableDeferred<Unit>()
                val awaitCallback = async {
                    withTimeoutOrNull(OAUTH_CALLBACK_TIMEOUT) {
                        appEventBus.events
                            .onSubscription { subscribed.complete(Unit) }
                            .filterIsInstance<AppEvent.McpOAuthCallback>()
                            .first { it.state == state }
                    }
                }
                subscribed.await() // 确保订阅已注册
                withContext(Dispatchers.Main) { launchOAuthAuthorization(context, authUrl) }
                awaitCallback.await()
            } ?: error("OAuth 授权超时")
            if (callback.error != null) error("授权失败: ${callback.error}")
            val code = callback.code ?: error("授权失败: 未返回授权码")

            // 7. 用授权码换取令牌
            val token = oauthClient.exchangeCode(
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                clientSecret = clientSecret,
                code = code,
                codeVerifier = pkce.verifier,
                redirectUri = MCP_OAUTH_REDIRECT_URI,
                resource = resource,
            )

            // 8. 持久化令牌
            persistOAuthState(
                config.id,
                McpOAuthState(
                    enabled = true,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = asMeta.registrationEndpoint,
                    scope = token.scope ?: scope,
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                )
            )

            // 9. 使用最新配置重新连接
            val freshConfig = settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
                ?: config
            addClient(freshConfig)
        }

    /** 清除某个 Server 的 OAuth 授权状态（登出）。 */
    suspend fun clearAuthorization(config: McpServerConfig) {
        persistOAuthState(config.id, null)
    }

    /** 若令牌即将过期且存在 refresh_token，则提前刷新并持久化，返回更新后的配置。 */
    private suspend fun ensureFreshToken(config: McpServerConfig): McpServerConfig {
        val oauth = config.commonOptions.oauth ?: return config
        if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return config
        val expired = oauth.expiresAt > 0 &&
            System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
        val needsRefresh = oauth.accessToken.isNullOrBlank() || expired
        if (!needsRefresh) return config

        val tokenEndpoint = oauth.tokenEndpoint ?: return config
        val clientId = oauth.clientId ?: return config
        return runCatching {
            val token = oauthClient.refreshToken(
                tokenEndpoint = tokenEndpoint,
                clientId = clientId,
                clientSecret = oauth.clientSecret,
                refreshToken = oauth.refreshToken,
                resource = McpOAuthClient.canonicalResource(config.serverUrl),
                scope = oauth.scope,
            )
            val updated = oauth.copy(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken ?: oauth.refreshToken,
                expiresAt = computeExpiry(token.expiresIn),
                scope = token.scope ?: oauth.scope,
            )
            persistOAuthState(config.id, updated)
            config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
        }.getOrElse {
            Log.w(TAG, "Token refresh failed for ${config.commonOptions.name}: ${it.message}")
            config // 刷新失败仍用旧令牌尝试，失败会转为 NeedsAuthorization
        }
    }

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    /**
     * 判断某次连接/同步失败是否应引导用户进行 OAuth 授权。
     *
     * 仅靠错误文本匹配 401/invalid_token 并不可靠：很多 MCP server 依赖用户手动填写
     * Authorization header，缺失时同样返回 401。因此在文本预筛之上进一步区分：
     * - 已开启 OAuth（此前授权过、令牌失效）→ 直接引导重新授权
     * - 用户手动配置了 Authorization header → 视为普通错误，尊重手动登录模式
     * - 其余情况 → 主动探测该 server 是否发布受保护资源元数据 (RFC 9728)，
     *   能发现才认为其支持 OAuth、需要授权
     */
    private suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!looksUnauthorized(error)) return false
        // 已开启 OAuth：令牌失效，直接引导重新授权
        if (config.commonOptions.oauth?.enabled == true) return true
        // 用户手动配置了 Authorization header：属于手动登录模式，header 无效是用户配置问题
        val hasManualAuth = config.commonOptions.headers.any {
            it.first.equals("Authorization", ignoreCase = true)
        }
        if (hasManualAuth) return false
        // 主动探测：仅当 server 发布了受保护资源元数据 (protected resource metadata) 时才支持 OAuth
        return runCatching { oauthClient.discoverProtectedResource(config.serverUrl) }
            .onFailure { Log.i(TAG, "OAuth probe failed for ${config.commonOptions.name}: ${it.message}") }
            .isSuccess
    }

    /**
     * 是否为 Streamable HTTP 的 SSE 通知流重试耗尽错误。
     *
     * StreamableHttpClientTransport 除 POST 请求/响应外，还会额外开一条 GET 的 SSE 长连接
     * 用于接收服务端主动推送。部分 server 不支持或会主动关闭该流，SDK 内部按退避重试若干次后
     * 放弃，并向 onError emit "Maximum reconnection attempts exceeded"。
     *
     * 此时 POST 通道仍然健康（listTools/callTool 均可用），不应据此重建整个客户端——否则每次
     * 整体重连都会成功并清零计数，同时又开启一条新 SSE 流再次失败，形成无限重连循环。
     */
    private fun isSseStreamGiveUpError(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return message.contains("Maximum reconnection attempts exceeded", ignoreCase = true)
    }

    /** 错误文本是否疑似未授权（HTTP 401 或 RFC 6750 定义的 OAuth token 错误）。 */
    private fun looksUnauthorized(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("invalid_token") ||
            message.contains("invalid access token") ||
            message.contains("missing or invalid")
    }
}

private fun ToolSchema.toSchema(): InputSchema {
    return InputSchema.Obj(properties = this.properties ?: JsonObject(emptyMap()), required = this.required)
}
