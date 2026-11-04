package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 连接外部 MCP server 失败(网络、HTTP、JSON-RPC 或协议不匹配)。消息不包含令牌/请求头。 */
class McpClientException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 外部 MCP server 的最小 Streamable HTTP 客户端(2025-03-26)。
 *
 * [connect] 完成 initialize / notifications/initialized / tools/list,之后 [tools] 返回开局时的目录,
 * [call] 只发 tools/call。目录在连接时冻结,和 [McpEndpoint] 的约定一致。
 *
 * 不自动重试 tools/call(结果未知,避免重复副作用);网络、HTTP 非 2xx、JSON-RPC error 都转成
 * [McpResult.isError] 或 [McpClientException],不抛到任务循环外。
 * 令牌等自定义头只放在请求里,不写日志。
 */
class HttpMcpEndpoint private constructor(
    override val serverId: String,
    private val url: HttpUrl,
    private val http: OkHttpClient,
    private val headers: Map<String, String>,
    private val protocolVersion: String,
    private val sessionId: String?,
    private val ids: AtomicLong,
    private val tools: List<McpToolDescriptor>,
    private val resources: List<McpResourceDescriptor>,
    private val prompts: List<McpPromptDescriptor>,
    private val timeoutSec: Int,
    val serverName: String?,
    val instructions: String?,
) : McpEndpoint, Closeable {
    private val json = Json { ignoreUnknownKeys = true }
    // MCP 端点不应该靠重定向跳转;禁止重定向,避免把请求(含认证头)带去别的主机
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override fun tools(): List<McpToolDescriptor> = tools
    override fun resources(): List<McpResourceDescriptor> = resources
    override fun prompts(): List<McpPromptDescriptor> = prompts

    override suspend fun readResource(uri: String): McpResult = try {
        mcpResourceResult(rpc("resources/read", buildJsonObject { put("uri", uri) }))
    } catch (e: McpClientException) {
        McpResult(e.message.orEmpty(), isError = true)
    } catch (e: IOException) {
        McpResult("MCP server request failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    override suspend fun getPrompt(name: String, arguments: JsonObject): McpResult = try {
        mcpPromptResult(rpc("prompts/get", buildJsonObject {
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
            mcpToolResult(result)
        } catch (e: Exception) {
            McpResult("MCP server returned an unreadable result: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    /** 关闭会话(如果服务端给了会话编号);失败忽略。 */
    override fun close() {
        val sid = sessionId ?: return
        runCatching {
            val request = Request.Builder().url(url).delete()
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("MCP-Session-Id", sid)
                .header("MCP-Protocol-Version", protocolVersion)
                .build()
            // 关闭不阻塞调用方(设置页/任务收尾);失败忽略
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = post(body.toString())
        if (response.code !in 200..299) throw McpClientException("MCP server returned HTTP ${response.code} for $method")
        val message = parseMessages(response.body).firstOrNull { it.matchesId(id) }
            ?: throw McpClientException("MCP server returned no response for $method")
        val error = message["error"]
        if (error != null) {
            val msg = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "JSON-RPC error"
            throw McpClientException("MCP server rejected $method: $msg")
        }
        return message["result"] as? JsonObject ?: throw McpClientException("MCP server returned no result for $method")
    }

    private suspend fun post(body: String): RawResponse {
        val builder = Request.Builder().url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocolVersion)
        sessionId?.let { builder.header("MCP-Session-Id", it) }
        headers.forEach { (k, v) -> if (!k.equals("Content-Type", true) && !k.equals("Accept", true)) builder.header(k, v) }
        val request = builder.post(body.toRequestBody(JSON_MEDIA)).build()
        return client.newCall(request).awaitBody(timeoutSec) { response ->
            RawResponse(response.code, response.header("Mcp-Session-Id"), response.body?.string().orEmpty())
        }
    }

    private fun sseMessages(body: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val data = StringBuilder()
        fun flush() {
            if (data.isNotEmpty()) {
                runCatching { json.parseToJsonElement(data.toString()) as? JsonObject }.getOrNull()?.let(out::add)
                data.clear()
            }
        }
        for (line in body.lineSequence()) {
            when {
                line.isBlank() -> flush()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
            }
        }
        flush()
        return out
    }

    private fun parseMessages(body: String): List<JsonObject> {
        if (body.trimStart().startsWith("{")) {
            return listOfNotNull(runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull())
        }
        return sseMessages(body)
    }

    private fun JsonObject.matchesId(id: Long): Boolean =
        (this["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == id

    companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        private const val MAX_TOOL_PAGES = 20
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 连接并读取工具目录。返回的 endpoint 已经初始化;失败时 [Result.failure] 里是 [McpClientException]。
         * [clientInfo] 里的 name/version 只用于 initialize,不参与认证。
         */
        suspend fun connect(
            serverId: String,
            url: String,
            http: OkHttpClient = OkHttpClient(),
            headers: Map<String, String> = emptyMap(),
            timeoutSec: Int = 60,
            clientName: String = "Mika",
            clientVersion: String = "1",
            protocolVersion: String = PROTOCOL_VERSION,
        ): Result<HttpMcpEndpoint> = runCatching {
            val parsed = url.toHttpUrlOrNull() ?: throw McpClientException("invalid MCP server URL")
            require(timeoutSec in 1..600) { "timeoutSec must be 1..600" }
            val ids = AtomicLong(0)
            val initId = ids.incrementAndGet()
            val initBody = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", initId)
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", protocolVersion)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", clientName)
                        put("version", clientVersion)
                    }
                }
            }
            val init = postRaw(parsed, initBody.toString(), headers, timeoutSec, http)
            if (init.code !in 200..299) throw McpClientException("MCP initialize returned HTTP ${init.code}")
            val initMsg = parseMessages(init.body).firstOrNull { it.matchesId(initId) }
                ?: throw McpClientException("MCP initialize returned no response")
            val initError = initMsg["error"]
            if (initError != null) {
                val msg = (initError as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "JSON-RPC error"
                throw McpClientException("MCP initialize rejected: $msg")
            }
            val initResult = initMsg["result"] as? JsonObject
                ?: throw McpClientException("MCP initialize returned no result")
            val negotiated = (initResult["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: protocolVersion
            val session = init.sessionId
            val serverInfo = initResult["serverInfo"] as? JsonObject
            val serverName = (serverInfo?.get("name") as? JsonPrimitive)?.contentOrNull
            val instructions = (initResult["instructions"] as? JsonPrimitive)?.contentOrNull

            val initialized = buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
                putJsonObject("params") {}
            }
            runCatching { postRaw(parsed, initialized.toString(), headers, timeoutSec, http, session, negotiated) }
                .onFailure { if (it is CancellationException) throw it }

            val tools = mutableListOf<McpToolDescriptor>()
            var cursor: String? = null
            var pages = 0
            do {
                val requestId = ids.incrementAndGet()
                val listBody = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId)
                    put("method", "tools/list")
                    putJsonObject("params") { cursor?.let { put("cursor", it) } }
                }
                val response = postRaw(parsed, listBody.toString(), headers, timeoutSec, http, session, negotiated)
                if (response.code !in 200..299) throw McpClientException("MCP tools/list returned HTTP ${response.code}")
                val message = parseMessages(response.body).firstOrNull { it.matchesId(requestId) }
                    ?: throw McpClientException("MCP tools/list returned no response")
                val error = message["error"]
                if (error != null) {
                    val msg = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "JSON-RPC error"
                    throw McpClientException("MCP tools/list rejected: $msg")
                }
                val result = message["result"] as? JsonObject ?: throw McpClientException("MCP tools/list returned no result")
                (result["tools"] as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.let { tools += mcpToolDescriptor(it) }
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                pages++
            } while (cursor != null && pages < MAX_TOOL_PAGES)

            // resources/prompts 是可选能力:服务端自称支持却报错时,工具照常可用,不让整个连接失败
            val caps = initResult["capabilities"] as? JsonObject
            val resources = if (caps?.get("resources") is JsonObject) {
                try {
                    listResources(parsed, headers, timeoutSec, http, session, negotiated, ids)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            val prompts = if (caps?.get("prompts") is JsonObject) {
                try {
                    listPrompts(parsed, headers, timeoutSec, http, session, negotiated, ids)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            HttpMcpEndpoint(
                serverId = serverId,
                url = parsed,
                http = http,
                headers = headers,
                protocolVersion = negotiated,
                sessionId = session,
                ids = ids,
                tools = tools,
                resources = resources,
                prompts = prompts,
                timeoutSec = timeoutSec,
                serverName = serverName,
                instructions = instructions,
            )
        }.onFailure { if (it is CancellationException) throw it }

        private fun JsonObject.matchesId(id: Long): Boolean =
            (this["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == id

        private suspend fun listResources(
            url: HttpUrl,
            headers: Map<String, String>,
            timeoutSec: Int,
            http: OkHttpClient,
            session: String?,
            protocolVersion: String,
            ids: AtomicLong,
        ): List<McpResourceDescriptor> {
            val resources = mutableListOf<McpResourceDescriptor>()
            var cursor: String? = null
            var pages = 0
            do {
                val requestId = ids.incrementAndGet()
                val body = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId)
                    put("method", "resources/list")
                    putJsonObject("params") { cursor?.let { put("cursor", it) } }
                }
                val response = postRaw(url, body.toString(), headers, timeoutSec, http, session, protocolVersion)
                if (response.code !in 200..299) throw McpClientException("MCP resources/list returned HTTP ${response.code}")
                val message = parseMessages(response.body).firstOrNull { it.matchesId(requestId) }
                    ?: throw McpClientException("MCP resources/list returned no response")
                message["error"]?.let {
                    throw McpClientException("MCP resources/list rejected: ${mcpErrorMessage(it)}")
                }
                val result = message["result"] as? JsonObject
                    ?: throw McpClientException("MCP resources/list returned no result")
                (result["resources"] as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.let { resources += mcpResourceDescriptor(it) }
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                pages++
            } while (cursor != null && pages < MAX_TOOL_PAGES)
            return resources
        }

        private suspend fun listPrompts(
            url: HttpUrl,
            headers: Map<String, String>,
            timeoutSec: Int,
            http: OkHttpClient,
            session: String?,
            protocolVersion: String,
            ids: AtomicLong,
        ): List<McpPromptDescriptor> {
            val prompts = mutableListOf<McpPromptDescriptor>()
            var cursor: String? = null
            var pages = 0
            do {
                val requestId = ids.incrementAndGet()
                val body = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", requestId)
                    put("method", "prompts/list")
                    putJsonObject("params") { cursor?.let { put("cursor", it) } }
                }
                val response = postRaw(url, body.toString(), headers, timeoutSec, http, session, protocolVersion)
                if (response.code !in 200..299) throw McpClientException("MCP prompts/list returned HTTP ${response.code}")
                val message = parseMessages(response.body).firstOrNull { it.matchesId(requestId) }
                    ?: throw McpClientException("MCP prompts/list returned no response")
                message["error"]?.let {
                    throw McpClientException("MCP prompts/list rejected: ${mcpErrorMessage(it)}")
                }
                val result = message["result"] as? JsonObject
                    ?: throw McpClientException("MCP prompts/list returned no result")
                (result["prompts"] as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.let { prompts += mcpPromptDescriptor(it) }
                }
                cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                pages++
            } while (cursor != null && pages < MAX_TOOL_PAGES)
            return prompts
        }

        private fun parseMessages(body: String): List<JsonObject> {
            if (body.trimStart().startsWith("{")) {
                return listOfNotNull(runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull())
            }
            val out = mutableListOf<JsonObject>()
            val data = StringBuilder()
            fun flush() {
                if (data.isNotEmpty()) {
                    runCatching { json.parseToJsonElement(data.toString()) as? JsonObject }.getOrNull()?.let(out::add)
                    data.clear()
                }
            }
            for (line in body.lineSequence()) {
                when {
                    line.isBlank() -> flush()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                }
            }
            flush()
            return out
        }

        private suspend fun postRaw(
            url: HttpUrl,
            body: String,
            headers: Map<String, String>,
            timeoutSec: Int,
            http: OkHttpClient,
            session: String? = null,
            protocolVersion: String? = null,
        ): RawResponse {
            val builder = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
            if (protocolVersion != null) builder.header("MCP-Protocol-Version", protocolVersion)
            if (session != null) builder.header("MCP-Session-Id", session)
            headers.forEach { (k, v) -> if (!k.equals("Content-Type", true) && !k.equals("Accept", true)) builder.header(k, v) }
            val request = builder.post(body.toRequestBody(JSON_MEDIA)).build()
            val client = http.newBuilder().callTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build()
            return client.newCall(request).awaitBody(timeoutSec) { response ->
                RawResponse(response.code, response.header("Mcp-Session-Id"), response.body?.string().orEmpty())
            }
        }

        private data class RawResponse(val code: Int, val sessionId: String?, val body: String)
    }
}

private val JSON_MEDIA = "application/json".toMediaType()

/** 正文也在 OkHttp 回调线程里读完再交回;读取期间取消仍会取消 call,Response 在所有路径上关闭。 */
private suspend fun <T> Call.awaitBody(timeoutSec: Int, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    timeout().timeout(timeoutSec.toLong(), TimeUnit.SECONDS)
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            val result = try {
                response.use(read)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
                return
            }
            if (continuation.isActive) continuation.resume(result)
        }
    })
}

/** OkHttp 的异步调用包成可取消的 suspend;取消时取消底层 call。 */
internal suspend fun Call.await(timeoutSec: Int): Response = suspendCancellableCoroutine { continuation ->
    timeout()?.timeout(timeoutSec.toLong(), TimeUnit.SECONDS)
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

/** SSE 长连接不能设整次调用超时(会在一段时间后掐断流);只等响应头,之后的超时由协议层控制。 */
internal suspend fun Call.awaitStream(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
