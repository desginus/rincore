package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 旧版 MCP 传输(HTTP+SSE,2024-11-05):先 GET 一条 SSE 流,服务端发 `endpoint` 事件给出 POST 地址;
 * 之后 JSON-RPC 请求都 POST 到该地址,响应从 SSE 流回来。
 *
 * 只在用户明确选择旧版、或服务端只支持旧版时使用。和 [HttpMcpEndpoint] 一样:目录连接时冻结,
 * `tools/call` 不自动重试,关闭时取消 SSE 连接并让进行中的调用失败。
 */
class LegacySseMcpEndpoint private constructor(
    override val serverId: String,
    private val pending: ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>,
    private val ids: AtomicLong,
    private val timeoutSec: Int,
    private val sseCall: Call,
    private val scope: CoroutineScope,
    private val tools: List<McpToolDescriptor>,
    private val resources: List<McpResourceDescriptor>,
    private val prompts: List<McpPromptDescriptor>,
    private val post: suspend (String) -> Response,
    val serverName: String?,
    val instructions: String?,
) : McpEndpoint, Closeable {

    override fun tools(): List<McpToolDescriptor> = tools
    override fun resources(): List<McpResourceDescriptor> = resources
    override fun prompts(): List<McpPromptDescriptor> = prompts

    override suspend fun readResource(uri: String): McpResult = try {
        LegacyResultReader.resultToResource(rpc("resources/read", buildJsonObject { put("uri", uri) }))
    } catch (e: McpClientException) {
        McpResult(e.message.orEmpty(), isError = true)
    } catch (e: IOException) {
        McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    override suspend fun getPrompt(name: String, arguments: JsonObject): McpResult = try {
        LegacyResultReader.resultToPrompt(rpc("prompts/get", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }))
    } catch (e: McpClientException) {
        McpResult(e.message.orEmpty(), isError = true)
    } catch (e: IOException) {
        McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    override suspend fun call(name: String, arguments: JsonObject): McpResult {
        if (tools.none { it.name == name }) return McpResult("unknown MCP tool: $name", isError = true)
        val result = try {
            rpc("tools/call", buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            })
        } catch (e: McpClientException) {
            return McpResult(e.message.orEmpty(), isError = true)
        } catch (e: IOException) {
            return McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        return try {
            LegacyResultReader.resultToMcpResult(result)
        } catch (e: Exception) {
            McpResult("MCP server returned an unreadable result: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JsonObject>()
        pending[id] = deferred
        try {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
            }
            post(body.toString()).use { response ->
                if (response.code !in 200..299) throw McpClientException("MCP server returned HTTP ${response.code} for $method")
            }
            val message = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
                ?: throw McpClientException("MCP server did not answer $method in time")
            message["error"]?.let { throw McpClientException("MCP server rejected $method: ${mcpErrorMessage(it)}") }
            return message["result"] as? JsonObject ?: throw McpClientException("MCP server returned no result for $method")
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        scope.cancel()
        sseCall.cancel()
        val error = IOException("MCP SSE connection closed")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        private val JSON_MEDIA = "application/json".toMediaType()

        suspend fun connect(
            serverId: String,
            url: String,
            http: OkHttpClient = OkHttpClient(),
            headers: Map<String, String> = emptyMap(),
            timeoutSec: Int = 60,
            clientName: String = "Mika",
            clientVersion: String = "1",
            protocolVersion: String = PROTOCOL_VERSION,
        ): Result<LegacySseMcpEndpoint> = runCatching {
            val parsed = url.toHttpUrlOrNull() ?: throw McpClientException("invalid MCP server URL")
            require(timeoutSec in 1..600) { "timeoutSec must be 1..600" }
            val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val request = Request.Builder().url(parsed)
                .header("Accept", "text/event-stream")
                .apply { headers.forEach { (k, v) -> if (!k.equals("Accept", true)) header(k, v) } }
                .get().build()
            val sseCall = client.newCall(request)
            val response = sseCall.awaitStream()
            if (response.code !in 200..299) {
                response.close()
                throw McpClientException("legacy MCP SSE returned HTTP ${response.code}")
            }
            val body = response.body ?: throw McpClientException("legacy MCP SSE returned no body")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
            val endpointUrl = CompletableDeferred<HttpUrl>()
            val reader = scope.launch { readEvents(parsed, body, endpointUrl, pending) }
            try {
                val postUrl = withTimeoutOrNull(minOf(timeoutSec * 1000L, 15_000L)) { endpointUrl.await() }
                    ?: throw McpClientException("legacy MCP SSE did not provide an endpoint")
                val ids = AtomicLong(0)
                suspend fun postRaw(text: String): Response {
                    val builder = Request.Builder().url(postUrl)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                    headers.forEach { (k, v) -> if (!k.equals("Content-Type", true) && !k.equals("Accept", true)) builder.header(k, v) }
                    return client.newBuilder().callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
                        .newCall(builder.post(text.toRequestBody(JSON_MEDIA)).build()).await(timeoutSec)
                }
                suspend fun request(method: String, params: JsonObject): JsonObject {
                    val id = ids.incrementAndGet()
                    val deferred = CompletableDeferred<JsonObject>()
                    pending[id] = deferred
                    try {
                        val text = buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("method", method)
                            putJsonObject("params") { params.forEach { (k, v) -> put(k, v) } }
                        }.toString()
                        postRaw(text).use { r ->
                            if (r.code !in 200..299) throw McpClientException("MCP server returned HTTP ${r.code} for $method")
                        }
                        val message = withTimeoutOrNull(timeoutSec * 1000L) { deferred.await() }
                            ?: throw McpClientException("MCP server did not answer $method in time")
                        message["error"]?.let { throw McpClientException("MCP server rejected $method: ${mcpErrorMessage(it)}") }
                        return message["result"] as? JsonObject
                            ?: throw McpClientException("MCP server returned no result for $method")
                    } finally {
                        pending.remove(id)
                    }
                }
                suspend fun notify(method: String) {
                    val text = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("method", method)
                        putJsonObject("params") {}
                    }.toString()
                    postRaw(text).close()
                }

                val handshake = mcpHandshake(
                    requestedProtocol = protocolVersion,
                    clientName = clientName,
                    clientVersion = clientVersion,
                    request = { method, params -> request(method, params) },
                    notify = { method -> notify(method) },
                )

                LegacySseMcpEndpoint(
                    serverId = serverId,
                    pending = pending,
                    ids = ids,
                    timeoutSec = timeoutSec,
                    sseCall = sseCall,
                    scope = scope,
                    tools = handshake.tools,
                    resources = handshake.resources,
                    prompts = handshake.prompts,
                    post = ::postRaw,
                    serverName = handshake.serverName,
                    instructions = handshake.instructions,
                )
            } catch (e: Throwable) {
                reader.cancel()
                scope.cancel()
                sseCall.cancel()
                body.close()
                throw e
            }
        }

        private suspend fun readEvents(
            base: HttpUrl,
            body: ResponseBody,
            endpointUrl: CompletableDeferred<HttpUrl>,
            pending: ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>,
        ) {
            val json = Json { ignoreUnknownKeys = true }
            val source = body.source()
            var event = ""
            val data = StringBuilder()
            fun dispatch() {
                val name = event.ifEmpty { "message" }
                val payload = data.toString()
                event = ""
                data.clear()
                if (payload.isEmpty()) return
                when (name) {
                    "endpoint" -> resolveEndpoint(base, payload)?.let { endpointUrl.complete(it) }
                    "message" -> {
                        val message = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return
                        val id = (message["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
                        pending.remove(id)?.complete(message)
                    }
                }
            }
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.isEmpty() -> dispatch()
                        line.startsWith(":") -> Unit
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.removePrefix("data:").trimStart())
                        }
                    }
                }
                throw IOException("MCP SSE stream ended")
            } catch (e: Throwable) {
                endpointUrl.completeExceptionally(e)
                pending.values.forEach { it.completeExceptionally(e) }
                pending.clear()
                if (e is CancellationException) throw e
            } finally {
                body.close()
            }
        }

        /** endpoint 事件会给绝对或相对地址;不允许跳协议、带 userinfo 或换主机。 */
        private fun resolveEndpoint(base: HttpUrl, payload: String): HttpUrl? {
            val url = base.resolve(payload.trim()) ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            if (base.isHttps && !url.isHttps) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.host != base.host) return null
            return url
        }
    }
}

/** 工具结果解析和 HTTP 传输保持一致;单独放一处,避免两边分叉。 */
private object LegacyResultReader {
    fun resultToResource(result: JsonObject): McpResult = mcpResourceResult(result)
    fun resultToPrompt(result: JsonObject): McpResult = mcpPromptResult(result)
    fun resultToMcpResult(result: JsonObject): McpResult = mcpToolResult(result)
}
