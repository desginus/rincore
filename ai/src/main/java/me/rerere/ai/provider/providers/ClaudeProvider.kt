package me.rerere.ai.provider.providers


/* ───【自研】ClaudeProvider.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ProxyRoute
import me.rerere.ai.provider.resolveProxy
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.TraceLogger
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"

class ClaudeProvider(
    private val client: OkHttpClient,
    context: Context? = null,
    // v3.9.15: 按模型代理路由 (仅 generateText/streamText 生效)
    private val proxyRoute: ProxyRoute? = null,
) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        // v3.11.9: 2013 降级重试 — MiniMax 等严格校验上游间歇性 invalid params
        // (服务端波动/未知字段间歇拒绝, 用户实测同一请求随机 400)。
        // 首次 400+2013 → 用最简请求体 (无 cache_control/thinking) 重试一次。
        var requestBody = buildMessageRequest(providerSetting, messages, params)
        var response = null as okhttp3.Response?
        var attempts = 0
        while (attempts < 2) {
            attempts++
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/messages")
                .headers(params.customHeaders.toHeaders())
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .configureReferHeaders(providerSetting.baseUrl)
                .build()

            Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

            response = client.resolveProxy(proxyRoute, params.model.modelId).newCall(request).await()
            if (response.isSuccessful) break
            val bodyStr = response.body.string()
            if (attempts == 1 && response.code == 400 && bodyStr.contains("2013")) {
                Log.w(TAG, "generateText: 2013 invalid params — retrying with minimal body")
                TraceLogger.log("SSE", "generateText 2013 → minimal retry")
                requestBody = buildMessageRequest(providerSetting, messages, params, minimal = true)
                continue
            }
            throw Exception("Failed to get response: ${response.code} $bodyStr")
        }

        val bodyStr = response!!.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val usage = parseTokenUsage(bodyJson)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(content),
                    finishReason = stopReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        // v3.11.9: 2013 降级重试标记 — MiniMax 等严格校验上游间歇性
        // invalid params (服务端波动), 首次 400+2013 用最简请求体重试一次
        // AtomicReference: object expression (EventSourceListener) 内写捕获
        // 局部 var 受限 (JVM 匿名类 effectively-final), 引用容器绕过
        var attemptedMinimal = false
        val requestBodyRef =
            java.util.concurrent.atomic.AtomicReference(
                buildMessageRequest(providerSetting, messages, params, stream = true)
            )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBodyRef.get()).toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.d(TAG, "streamText: ${json.encodeToString(requestBodyRef.get())}") // v3.6.17: 降 d

        requestBodyRef.get()["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }

        val hasData = java.util.concurrent.atomic.AtomicBoolean(false)
        val streamStartMs = System.currentTimeMillis()
        // v3.8.5: 完成标记 — Anthropic 协议 message_stop 是唯一正常收尾,
        // 连接关闭时若未收到 message_stop 视为断流 (输出中途静默中断当
        // 正常完成保存半截回复的根因修复)。对照 ChatCompletionsAPI
        // completed/gotFinish 机制 (v3.6.75 双向: 有收尾标记才视为完成)。
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)

        // v3.8.3: OpenCode 适配 — 对齐 ChatCompletionsAPI v3.6.44 (该通道已有
        // opencode.ai 判定放宽 watchdog): OpenCode Zen 中转聚合转发 + 深度思考,
        // 静默期长且可能不逐 token 转发。无 watchdog 时中转静默会无限挂起直到
        // readTimeout (3min), 用户感知首字延迟极久。分阶段 watchdog:
        // 首包前 120s 断开 (opencode) / 60s (其他); 首包后 180s (opencode) /
        // 120s (其他)。断开抛 IOException → GenerationHandler 断流重试, 收敛挂起。
        val isOpencode = runCatching {
            providerSetting.baseUrl.toHttpUrl().host == "opencode.ai"
        }.getOrDefault(false)
        // v3.11.6: opencode 时限收紧 (120/180→60/90s) — 用户反馈 OpenCode Go
        // 通道"极不稳定": 网关断流/静默后旧时限要等 2-3 分钟才断开重试,
        // 收紧后 60-90s 内快速失败进入断流重试 (15 次 10s 预算), 恢复更快
        // v3.11.13: 三阶段 watchdog ("尽可能多尝试, 少静默") — 旧实现把
        // "连接未就绪" 与 "已连接在思考" 混成同一 60s 计时, 后果两头堵:
        // 网关挂起要等满 60s 才断开重试 (静默久), 上游长思考又常被 60s
        // 误杀 (重试后重新排队更慢)。按连接状态分级:
        //   阶段1 建连/响应头 (onOpen 之前): 30s — 网关冷启动/挂起快速
        //     断开 → GenerationHandler 瞬时分支 15 次快速重试 (多尝试)
        //   阶段2 已连接未出首事件 (上游思考/排队): 150s — 请求已被网关
        //     接受, 重试只会重新排队更慢, 耐心等待 (原版即如此)
        //   阶段3 流出中 (首事件后): 90s — 流间隙上限
        val headerReceived = java.util.concurrent.atomic.AtomicBoolean(false)
        val headerLimit = 15_000L
        val firstEventLimit = 150_000L
        val streamLimit = if (isOpencode) 90_000L else 120_000L
        val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

        // v3.8.6: SSE 诊断统计 — 输出中途中断/半截时在运行日志页可抓取全部现场
        val eventCount = java.util.concurrent.atomic.AtomicInteger(0)
        val dataChars = java.util.concurrent.atomic.AtomicLong(0)
        TraceLogger.log("SSE", "start: model=${params.model.modelId}, isOpencode=$isOpencode, headerLimit=30s, firstEventLimit=150s, streamLimit=${streamLimit / 1000}s, maxTokens=${params.maxTokens ?: 64000}")

        val watchdog = launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                val idleMs = System.currentTimeMillis() - lastEventAt.get()
                val (phase, limit, closeMsg) = when {
                    hasData.get() -> Triple("stream", streamLimit,
                        "生成无有效数据超时 (${streamLimit / 1000}s): 平台断流或卡死")
                    headerReceived.get() -> Triple("first-event", firstEventLimit,
                        "生成无有效数据超时 (${firstEventLimit / 1000}s): 上游思考或排队中无输出")
                    else -> Triple("header", headerLimit,
                        "平台连接无响应 (${headerLimit / 1000}s): 网关冷启动或挂起")
                }
                if (idleMs > limit) {
                    Log.w(TAG, "Claude SSE idle ${idleMs / 1000}s (phase=$phase) — closing")
                    TraceLogger.log("SSE", "watchdog timeout: idle=${idleMs / 1000}s, limit=${limit / 1000}s, events=${eventCount.get()}, dataChars=${dataChars.get()}")
                    close(java.io.IOException(closeMsg))
                    break
                }
            }
        }

        // v3.11.9: eventSource 容器前置声明 — 局部变量无前向引用,
        // listener 匿名对象内 (onFailure 降级重试分支) 必须可见
        val eventSourceRef = java.util.concurrent.atomic.AtomicReference<okhttp3.sse.EventSource?>()
        val listener = object : EventSourceListener() {
            // v3.11.13: SSE 200 响应头到达 — 阶段1(建连)结束/阶段2(思考)开始,
            // 计时起点重置, 后续按首事件/流间隙分别判定
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                headerReceived.set(true)
                lastEventAt.set(System.currentTimeMillis())
                TraceLogger.log("SSE", "onOpen: header after ${System.currentTimeMillis() - streamStartMs}ms, code=${response.code}")
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: type=$type, data=$data")
                if (data == "[DONE]") {
                    return
                }
                if (data.isNotBlank()) lastEventAt.set(System.currentTimeMillis())

                // v3.8.6: 诊断计数 (每事件累加, 成本可忽略)
                val seq = eventCount.incrementAndGet()
                dataChars.addAndGet(data.length.toLong())
                if (seq == 1) {
                    TraceLogger.log("SSE", "first event after ${System.currentTimeMillis() - streamStartMs}ms, type=$type, len=${data.length}")
                } else if (seq % 50 == 1) {
                    TraceLogger.log("SSE", "progress: events=$seq, dataChars=${dataChars.get()}, lastType=$type")
                }

                val dataJson = json.parseToJsonElement(data).jsonObject
                val deltaMessage = parseMessage(buildJsonArray {
                    val contentBlockObj = dataJson["content_block"]?.jsonObject
                    val deltaObj = dataJson["delta"]?.jsonObject
                    if (contentBlockObj != null) {
                        add(contentBlockObj)
                    }
                    if (deltaObj != null) {
                        add(deltaObj)
                    }
                })
                val tokenUsage = parseTokenUsage(dataJson)
                val messageChunk = MessageChunk(
                    id = id ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = deltaMessage,
                            message = null,
                            finishReason = null
                        )
                    ),
                    usage = tokenUsage
                )

                trySend(messageChunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
                hasData.set(true)

                when (type) {
                    "message_stop" -> {
                        Log.d(TAG, "Stream ended")
                        completed.set(true)
                        TraceLogger.log("SSE", "message_stop: events=${eventCount.get()}, dataChars=${dataChars.get()}, usage=$tokenUsage")
                        close()
                    }

                    "error" -> {
                        val eventData = json.parseToJsonElement(data).jsonObject
                        val error = eventData["error"]?.parseErrorDetail()
                        close(error)
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")
                TraceLogger.log("SSE", "onFailure: ${t?.javaClass?.name} ${t?.message} / http=${response?.code}, events=${eventCount.get()}, dataChars=${dataChars.get()}")

                // 流式传输中断恢复: 如果已有部分数据则保留
                if (t is java.io.IOException &&
                    (t.message?.contains("stream was reset", ignoreCase = true) == true ||
                     t.message?.contains("protocol error", ignoreCase = true) == true ||
                     t.message?.contains("connection reset", ignoreCase = true) == true ||
                     t.message?.contains("connection abort", ignoreCase = true) == true ||
                     t.message?.contains("software caused", ignoreCase = true) == true ||
                     t.message?.contains("timeout", ignoreCase = true) == true ||
                     t.message?.contains("broken pipe", ignoreCase = true) == true ||
                     t.message?.contains("connection closed", ignoreCase = true) == true ||
                     t.message?.contains("canceled", ignoreCase = true) == true)
                ) {
                    // 移除静默恢复 (v3.1.0 引入) — 曾致回复缺失无报错感知中断
                    // 中断传播异常, 用户可见明确错误
                    Log.w(TAG, "onFailure: recoverable stream error (will propagate): ${t.message} hasData=${hasData.get()}")
                }

                val bodyRaw = response?.body?.stringSafe()

                // v3.11.9: 2013 降级重试 — 严格校验网关 (MiniMax) 间歇性
                // invalid params (用户实测同一请求随机 400): 首次 400+2013
                // 用最简请求体 (无 cache_control/thinking) 重试一次;
                // 重试仍失败则走正常错误上报
                if (!attemptedMinimal && response?.code == 400 && bodyRaw?.contains("2013") == true) {
                    attemptedMinimal = true
                    Log.w(TAG, "2013 invalid params detected — retrying with minimal request body")
                    TraceLogger.log("SSE", "2013 detected (code=400), retrying minimal body")
                    requestBodyRef.set(
                        buildMessageRequest(
                            providerSetting, messages, params, stream = true, minimal = true
                        )
                    )
                    val retryRequest = Request.Builder()
                        .url("${providerSetting.baseUrl}/messages")
                        .headers(params.customHeaders.toHeaders())
                        .post(json.encodeToString(requestBodyRef.get()).toRequestBody("application/json".toMediaType()))
                        .addHeader("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .addHeader("Content-Type", "application/json")
                        .configureReferHeaders(providerSetting.baseUrl)
                        .build()
                    eventSourceRef.set(
                        EventSources.createFactory(
                            client.resolveProxy(proxyRoute, params.model.modelId)
                        ).newEventSource(retryRequest, this)
                    )
                    // 重试连接建立前, 重置 watchdog 计时 — 防止旧 idle 计时
                    // 在连接/首字节阶段误掐断重试流
                    lastEventAt.set(System.currentTimeMillis())
                    return
                }

                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                        // v3.10.15: 元数据诊断 — 不泄露 system/messages 内容
                        // (用户警告: 错误详情泄露系统提示词). 只显示结构摘要
                        val meta = buildString {
                            append("\nREQ_META: model=").append(requestBodyRef.get()["model"])
                            val msgs = requestBodyRef.get()["messages"]?.jsonArray
                            append(" msgCount=").append(msgs?.size ?: 0)
                            msgs?.forEachIndexed { i, m ->
                                val o = m.jsonObject
                                append(" [").append(i).append("]=").append(o["role"])
                                // v3.11.3: 块类型统计 — 定位严格网关拒绝的具体块
                                val content = o["content"]
                                if (content is JsonArray) {
                                    val counts = mutableMapOf<String, Int>()
                                    content.forEach { blk ->
                                        val t = blk.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                                        counts[t] = (counts[t] ?: 0) + 1
                                    }
                                    append("(")
                                    counts.entries.sortedBy { it.key }.forEach { (t, n) ->
                                        append(t).append(":").append(n).append(" ")
                                    }
                                    append(")")
                                    // tool_use id 格式提示
                                    content.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }?.let { tu ->
                                        val id = tu.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""
                                        append(" toolId=${id.take(12)} len=${id.length}")
                                    }
                                } else {
                                    append("(content=").append(content?.let { it::class.simpleName } ?: "null").append(")")
                                }
                            }
                            append(" maxTokens=").append(requestBodyRef.get()["max_tokens"] ?: "?")
                            append(" keys=").append(requestBodyRef.get().keys.joinToString(","))
                        }
                        exception = me.rerere.ai.util.HttpException(
                            "${exception.message}$meta"
                        )
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    TraceLogger.dumpAndLog(TAG, exception ?: Exception("Unknown"), 60)
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // v3.8.5: 无 message_stop 的连接关闭 = 输出中途静默中断
                // (中转断流/网关切换), 必须走断流重试而非保存半截回复
                if (completed.get()) {
                    TraceLogger.log("SSE", "closed: completed=true, events=${eventCount.get()}, dataChars=${dataChars.get()}")
                    close()
                } else {
                    Log.w(TAG, "Claude SSE closed without message_stop — treating as stream interruption")
                    TraceLogger.log("SSE", "closed WITHOUT message_stop: events=${eventCount.get()}, dataChars=${dataChars.get()} — treating as stream interruption")
                    close(java.io.IOException("流未正常结束 (无 message_stop): 平台中断输出"))
                }
            }
        }

        eventSourceRef.set(
            EventSources.createFactory(
                client.resolveProxy(proxyRoute, params.model.modelId)
            ).newEventSource(request, listener)
        )

        awaitClose {
            runCatching { watchdog.cancel() }
            Log.d(TAG, "Closing eventSource")
            eventSourceRef.get()?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false,
        minimal: Boolean = false
    ): JsonObject {
        // v3.11.9: 家族分离定稿 (调研存档 docs/ecosystem/05-请求体格式调研/):
        //   - MiniMax 家族 (modelId 含 minimax): 顶层 cache_control (自动缓存
        //     模式) 不支持 — Pydantic 实证 (MiniMax/OpenRouter/LiteLLM 类网关
        //     "don't support top-level automatic caching"); 块级 cache_control
        //     (system 尾块/消息级) 官方 Explicit Prompt Caching 文档支持 ✓ 保留
        //   - 千问/官方/其他兼容层: 顶层+块级全保留 (v3.11.6 恢复)
        //   - minimal=true (2013 降级重试): 顶层/块级 cache_control 全关,
        //     thinking 不发 (平台默认), 最简标准 Anthropic 协议子集
        // 保留防御: 无签名 thinking 丢弃 / 空文本块丢弃 / BOM strip /
        // 连续同角色合并 / tool_result 消息净化 (v3.10.12-3.11.3)。
        val isMiniMaxFamily = params.model.modelId.contains("minimax", ignoreCase = true)
        // v3.11.11: 历史 thinking 不回放 (非官方通道统一规则) —
        // 实证 REQ_META msgCount=5帮: minimal 极简请求也 2013 时,
        // 剩余非常量 Circumstance 只有历史 assistant 回放的 thinking 块
        // ([1]/[3] thinking:1)。兼容网关下签名与会话绑定不可迁移,
        // 官方协议要求的历史 thinking 回放在 strict 上游必然验签失败;
        // 同时其内容每次不同会破坏隐式缓存前缀。官方 api.anthropic.com
        // 保持回放 (interleave thinking 协议收益); 本地纯文本判断与缓存
        // 双正向。minimal 原创已然不发 thinking 字段,历史同样剔除。
        val officialHost = runCatching {
            providerSetting.baseUrl.toHttpUrl().host == "api.anthropic.com"
        }.getOrDefault(false)
        val dropHistoryThinking = !officialHost || minimal
        // 顶层 cache_control: 仅非 MiniMax 家族且非降级模式
        val useTopLevelCache = providerSetting.promptCaching && !minimal && !isMiniMaxFamily
        // 块级 cache_control (system 尾块 + 消息级): 全家族可用, 降级模式关闭
        val useBlockCache = providerSetting.promptCaching && !minimal
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(messages, useBlockCache, dropHistoryThinking, providerSetting.promptCacheTtl)
            )
            // v3.11.9: MiniMax 家族兜底用官方推荐 65536 (M2.x 64K, M3 128K)
            put("max_tokens", params.maxTokens ?: if (isMiniMaxFamily) 65_536 else 64_000)

            if (useTopLevelCache) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null && !params.reasoningLevel.isEnabled) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text.trimStart(CHAR_BOM).replace(CHAR_BOM.toString(), ""))
                            if (useBlockCache && index == systemTextParts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            // v3.11.9: thinking 按家族分离 —
            //   minimal (2013 降级): 不发 thinking, 平台默认行为
            //   MiniMax 家族: 官方示例仅 type adaptive (无 display/output_config,
            //     间歇性严格校验未知字段 → 2013)
            //   其他家族: 原版完整协议 (adaptive + display + output_config effort)
            if (params.model.abilities.contains(ModelAbility.REASONING) && !minimal) {
                when (params.reasoningLevel) {
                    ReasoningLevel.OFF -> {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    }

                    ReasoningLevel.AUTO -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            if (!isMiniMaxFamily) put("display", "summarized")
                        })
                    }

                    else -> {
                        put("thinking", buildJsonObject {
                            put("type", "adaptive")
                            if (!isMiniMaxFamily) put("display", "summarized")
                        })
                        if (!isMiniMaxFamily) {
                            put("output_config", buildJsonObject {
                                put("effort", params.reasoningLevel.effort)
                            })
                        }
                    }
                }
            }

            // 处理工具
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            val toolDefinitions = buildList {
                if (useFunctionTools) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.parameters()))
                        })
                    }
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> add(buildJsonObject {
                            put("type", "web_search_20250305")
                            put("name", "web_search")
                        })
                        BuiltInTools.UrlContext,
                        BuiltInTools.ImageGeneration,
                            -> Unit
                    }
                }
            }
            if (toolDefinitions.isNotEmpty()) {
                putJsonArray("tools") {
                    toolDefinitions.forEachIndexed { index, definition ->
                        if (useBlockCache && index == toolDefinitions.lastIndex) {
                            add(JsonObject(
                                definition + mapOf(
                                    "cache_control" to cacheControlEphemeral(providerSetting.promptCacheTtl)
                                )
                            ))
                        } else {
                            add(definition)
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        promptCaching: Boolean,
        dropHistoryThinking: Boolean = false,
        promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES
    ) = buildJsonArray {
        // v3.10.13: 防御链 — Anthropic 消息硬性规则: 首条 user + 角色交替。
        // 1) 过滤 SYSTEM/跳过无效; 2) 丢弃前导非 user; 3) 合并连续同角色
        // (自研 transformer 曾以独立 user 消息注入 time_reminder → 连续 user
        // → Console Go/Minimax 严格校验 400 (2013), 铁证 REQ_MESSAGES)
        val clean = messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .dropWhile { it.role != MessageRole.USER }
        val merged = mutableListOf<UIMessage>()
        for (m in clean) {
            val last = merged.lastOrNull()
            if (last != null && last.role == m.role) {
                merged[merged.size - 1] = last.copy(parts = last.parts + m.parts)
            } else {
                merged.add(m)
            }
        }
        // v3.11.11: 历史思考剥离 — 兼容网关场景 MINIMAL_VALIDATE via upstream
        val finalMsgs = if (!dropHistoryThinking) merged else merged.mapNotNull { m ->
            if (m.role != MessageRole.ASSISTANT) return@mapNotNull m
            val kept = m.parts.filterNot { it is UIMessagePart.Reasoning }
            // 仅含 thinking 的 assistant 剥离后整条跳过 (空 content 必被拒)
            if (kept.isEmpty()) null else m.copy(parts = kept)
        }
        finalMsgs.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessage(message)
            } else {
                addUserMessage(message)
            }
        }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
    private fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.toContentBlocks().forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.flatMap { it.toContentBlocks() }
                    .map { stripBom(it) }.forEach { add(it) }
            }
        })
    }

    /** v3.10.15: strip BOM (U+FEFF) 等不可见控制符 — 严格网关 JSON parser
     * 可能拒收. 应用于所有 text 块内容. */
    private fun stripBom(obj: JsonObject): JsonObject {
        val text = obj["text"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull
        if (text == null) return obj
        val cleaned = text.trimStart(CHAR_BOM).replace(CHAR_BOM.toString(), "")
        if (cleaned == text) return obj
        return JsonObject(obj.toMutableMap().apply { put("text", JsonPrimitive(cleaned)) })
    }

    // 原版对应函数含 UIMessagePart.ServerTool 分支 (serverToolContentBlocks) —
    // 我们未移植 ServerTool part 机制, 仅保留通用分支
    private fun UIMessagePart.toContentBlocks(): List<JsonObject> =
        listOfNotNull(toContentBlock())

    // 原版 List 重载 (server tool 按原始 content block index 排序回放);
    // 我们无 server tool, 顺序转换等价
    private fun List<UIMessagePart>.toContentBlocks(): List<JsonObject> =
        flatMap { it.toContentBlocks() }

    private fun UIMessagePart.toContentBlock(): JsonObject? = when (this) {
        is UIMessagePart.Text -> {
            // v3.10.12 防御: 空 text 块被严格网关 (Minimax 等) 拒绝
            if (text.isBlank()) null
            else buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        }

        is UIMessagePart.Image -> buildJsonObject {
            encodeBase64(withPrefix = false).onSuccess { encoded ->
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }.onFailure {
                Log.w(TAG, "encode image failed: $url", it)
                put("type", "text")
                put("text", "")
            }
        }

        is UIMessagePart.Reasoning -> {
            // v3.10.12 防御: 无 signature 的 thinking 块 — 兼容网关 (千问等)
            // 不返回签名, Minimax 等严格校验上游要求历史 thinking 带签名 → 400
            // (2013)。有签名 (官方 Claude) 保留, 无签名丢弃。
            val sig = metadataAs<ClaudeReasoningMetadata>()?.signature
            if (sig != null) {
                buildJsonObject {
                    put("type", "thinking")
                    put("thinking", reasoning)
                    put("signature", sig)
                }
            } else null
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", toolCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock() = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", toolCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock() }.forEach { add(it) }
        }
    }

    private fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty()) {
                        parts.add(UIMessagePart.Text(text))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null) {
                        val reasoning = UIMessagePart.Reasoning(
                            reasoning = thinking,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                        if (signature != null) {
                            reasoning.metadata = ClaudeReasoningMetadata(signature = signature).toMetadata()
                        }
                        parts.add(reasoning)
                    }
                }

                "redacted_thinking" -> {
                    val data = block["data"]?.jsonPrimitiveOrNull?.contentOrNull
                    
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                "input_json_delta" -> {
                    // v3.11.3: 空 toolCallId/name 的增量块不保存 —
                    // 回放会发出 id="" 的 tool_use, 严格网关 (Minimax) 400
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    if (input.isNullOrBlank()) {
                        // 无内容则忽略
                    } else {
                        parts.add(
                            UIMessagePart.Tool(
                                toolCallId = "",
                                toolName = "",
                                input = input,
                                output = emptyList()
                            )
                        )
                    }
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts
        )
    }

    private fun parseTokenUsage(bodyJson: JsonObject?): TokenUsage? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }
}

private const val CHAR_BOM = '\uFEFF'
