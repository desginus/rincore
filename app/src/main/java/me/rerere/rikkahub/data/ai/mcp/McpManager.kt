package me.rerere.rikkahub.data.ai.mcp

/* ───【域 B·AI 传输】McpManager.kt
 * 职责: MCP 门面 (状态聚合/调用转发/reconcile 触发)
 * 常用改动: 连接触发 → init collect reconcile; 状态 → syncingStatus
 * 问题定位: MCP 状态异常 → 本文件 + McpSessionRegistry
 * 基线: 原版移植 + 自研 | 地图: docs/APP_MAP.md §B | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.saveUploadFromBytes
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth、连接注册表与 UI 内容转换；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val filesManager: FilesManager,
    // v3.11.2 兼容: STDIO viaWorkspace 沙箱启动需要 WorkspaceRepository
    private val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
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

    private val statusStore = McpStatusStore()
    private val callbackServer = OAuthLoopbackCallbackServer(
        port = MCP_OAUTH_CALLBACK_PORT,
        callbackPath = MCP_OAUTH_CALLBACK_PATH,
    )
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        oauthClient = OAuthHttpClient(okHttpClient),
        discoveryClient = McpOAuthDiscoveryClient(okHttpClient),
        callbackServer = callbackServer,
        authorizationLauncher = CustomTabsOAuthAuthorizationLauncher,
        updateStatus = statusStore::update,
    )
    private val sessionRegistry = McpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        httpClient = httpClient,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
        // v3.11.2 兼容: STDIO viaWorkspace 沙箱启动 (原版无, 需注入)
        workspaceRepository = workspaceRepository,
    )

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect(sessionRegistry::reconcile)
        }
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = sessionRegistry.getClient(config.id)

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = sessionRegistry.getStatus(config.id)

    fun getAllAvailableTools(): List<Triple<Uuid, String, McpTool>> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { server ->
                server.commonOptions.tools
                    .filter { tool -> tool.enable }
                    .map { tool -> Triple(server.id, server.commonOptions.name, tool) }
            }
    }

    suspend fun callTool(serverId: Uuid, toolName: String, args: JsonObject, ctx: McpCallContext? = null): List<UIMessagePart> {
        val result = try {
            sessionRegistry.callTool(serverId, toolName, args, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            return listOf(UIMessagePart.Text("Failed to execute MCP tool: ${e.message ?: e.javaClass.name}"))
        }
        return result.content.map { content ->
            when (content) {
                // v4.8.110 (B2): 文本里的绝对路径产物归集 (调用方可见范围外 → rescued + 改写)
                is TextContent -> UIMessagePart.Text(rescueMcpArtifactPaths(content.text, serverId, ctx))
                is ImageContent -> convertImageContentToFilePart(content)
                else -> UIMessagePart.Text(JsonInstant.encodeToString(content))
            }
        }
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    /**
     * v4.8.110 (B2): 产物归集入口 — 解析 server/调用方的工作区 root 后交给
     * FilesManager 策略函数; 任何失败降级为"原样 + 一行提示", 绝不让工具调用整体失败。
     */
    private suspend fun rescueMcpArtifactPaths(text: String, serverId: Uuid, ctx: McpCallContext?): String {
        if (ctx?.workspaceId.isNullOrBlank() || !text.contains('/')) return text
        return runCatching {
            val wsRepo = workspaceRepository ?: return@runCatching text
            val cfg = settingsStore.settingsFlow.value.mcpServers.find { it.id == serverId }
            val slug = sanitizeRescueSlug(cfg?.commonOptions?.name ?: serverId.toString().take(8))
            val serverRoot = (cfg as? McpServerConfig.StdioTransportServer)
                ?.workspaceId?.takeIf { it.isNotBlank() }
                ?.let { runCatching { wsRepo.getById(it)?.root }.getOrNull() }
            val callerRoot = runCatching { wsRepo.getById(ctx.workspaceId)?.root }.getOrNull()
            filesManager.rescueArtifactPathsByPolicy(text, slug, serverRoot, callerRoot, ctx.workspaceCwd)
        }.getOrElse { e ->
            text + "\n[产物归集失败: " + (e.message ?: e.javaClass.simpleName) + "]"
        }
    }

    private fun sanitizeRescueSlug(name: String): String =
        name.filter { it.isLetterOrDigit() || it in "._-" }.replace("..", "_").take(48).ifBlank { "server" }

    private suspend fun convertImageContentToFilePart(image: ImageContent): UIMessagePart.Image {
        val bytes = Base64.decode(image.data)
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(image.mimeType) ?: "bin"
        val entity = filesManager.saveUploadFromBytes(
            bytes = bytes,
            displayName = "mcp_image.$extension",
            mimeType = image.mimeType,
        )
        return UIMessagePart.Image(url = filesManager.getFile(entity).toUri().toString())
    }
}
