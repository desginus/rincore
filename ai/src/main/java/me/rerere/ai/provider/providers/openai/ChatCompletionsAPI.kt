@file:OptIn(DelicateCoroutinesApi::class)
/* ───【技术债审计 v3.19.0】
 * 审计结论: eventSource 生命周期 (awaitClose 双 cancel) ✓; 重试三轮链
 * (局部对象) ✓;
 * host×家族×档位三维思考控制 ✓。残余风险: Opus 直连/Gemini 兼容未覆盖
 * (OpenAI 类型走本类, 其他协议在各自 Provider 类)。
 * ───────────────────────────────────────────────────────────────*/
/**
 * ChatCompletions API 传输 — 模块: A. 传输链 / ai
 *
 * 职责: messages/tools 序列化 + HTTP 发送 + SSE 流式解析。
 * 基线: 回滚自 3.2.2 (v3.5.0)。
 *
 * 问题定位: 序列化错误/SSE 异常/工具格式问题 → 查本文件
 */
package me.rerere.ai.provider.providers.openai

/* ───【原版对齐】ChatCompletionsAPI ────────────────────────────────────
 * 原版: 有同文件 | RinCore 差异 +338 行
 * 来源: 原版移植 + v2.9.8 SSE 重试移植 + 自研
 * 功能: Chat Completions 流式主通道 (DeepSeek/OpenAI/OpenCode 全走此)
 * 特点: 1. SSE 未收数据自动重试 5 次指数退避 (v2.9.8 移植);
 *        2. watchdog 只日志不动作; 3. buffer UNLIMITED (丢 delta
 *        即缺字, #1295); 4. grok 流式完成判断 (usage/cost 行即完成,
 *        v3.6.78 — OpenCode Zen grok 不发 [DONE]/stop)
 * 逻辑: callbackFlow + okhttp3.sse EventSource; 请求体零改动原则
 * 与原版主要差异:
 *   1. SSE 重试/watchdog (原版无)
 *   2. grok 通道完成判断 (原版只认 [DONE])
 *   3. 报错带请求体摘要 (REQ=, v3.6.78)
 * ────────────────────────────────────────────────────────────────────*/

import java.io.IOException
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.ui.StreamChunk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import me.rerere.ai.provider.ProxyRoute
import me.rerere.ai.provider.resolveProxy
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.toTextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.ai.util.TraceLogger
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
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

/**
 * v3.8.32: OpenCode Zen 无完成信号关流的"未确认完成"异常。
 *
 * Zen 网关对部分模型 (ox 系免费模型等) 完成时不发 [DONE]/finish_reason/usage,
 * 直接关闭连接 — 与"服务端中途掐断"在信号层面无法区分。此时保留已生成内容,
 * 由上层明确提示用户 (不静默吞掉, 也不回滚重试轰炸)。
 */
private const val TAG = "ChatCompletionsAPI"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
    private val proxyRoute: ProxyRoute? = null,
    // v3.10.5: OpenCode 网关独立长保活池 — 请求侧按 host 切换 (TTFT 专项)
    private val opencodeClient: OkHttpClient? = null,
) : OpenAIImpl {
    // v3.10.5: opencode.ai 直连场景走长保活池, 其余回落默认池 (代理路由在 resolveProxy 后生效)
    // v3.17.0: api.commandcode.ai 同入长保活池 — CC 默认池 keepalive 60s,
    // 预热连接在用户发消息前已被 OkHttp 回收, 预热白做; 300s 池对齐 OpenCode 待遇
    private fun effClient(providerSetting: ProviderSetting.OpenAI): OkHttpClient {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return if (host == "opencode.ai" || host == "api.commandcode.ai") {
            (opencodeClient ?: client)
        } else client
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        // 4.1.3 TTFT: 删除全量请求体日志 — 大请求体 (工具 schema 多/上下文长) 每请求
        // 多一次全量 JSON 序列化 + logcat, 纯发送前开销; 错误路径 (下方) 保留完整请求体
        val response = effClient(providerSetting).resolveProxy(proxyRoute, params.model.modelId).newCall(request).await()
        if (!response.isSuccessful) {
            // v3.6.78: 报错带完整请求体 — 定位 400 触发字段 (grok 排查)
            val reqSummary = json.encodeToString(requestBody)
            throw Exception("Failed to get response: ${response.code} ${response.body.string()} REQ=$reqSummary")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val choice = bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason
                )
            ),
            usage = usage
        ).toTextGenerationResult()
    }

    private suspend fun streamTextRaw(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        // 4.1.3 TTFT: Log.d 的参数在 release 同样求值 — 每次流式请求都全量序列化
        // 请求体 (几百 KB 时 50-300ms + GC 压力) 直接加到首包延迟; 彻底删除

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        // SSE 有效数据看门狗: 120s 无有效数据 → 主动断开 (触发收尾+断流重试)。
        // 教训链: v3.5.14 主动断开误杀长思考 → 改只日志; v3.5.45 缩短到 25s 后
        // 用户实测误杀 (Trace 95098f39: 平台存在 >25s 静默期, 非断流) —
        // 25s 假设"推理期间持续有 reasoning chunk"在用户环境不成立。
        // 4.0.0 重写: 三阶段 watchdog 状态机统一收敛至 StreamWatchdog
        // (ai/core/WatchdogPolicy.kt) — 数值/文案/tick 节奏逐字保留
        val hasReceivedData = java.util.concurrent.atomic.AtomicBoolean(false)  // 前置声明 (watchdog 引用)
        // v4.3.0: 增量流工具调用 id 映射 — CC 协议的 tool_calls[i].index 是归属键,
        // id/name 仅首 delta 给出, 后续 delta 只有 arguments 增量。维护 index→id
        // 映射并回填到 parseMessage 产物, 多工具并行交错增量按 index 精确归属
        // (旧 blank-id "归属最近工具" 在并行交错时会把 tool0 增量拼进 tool1)
        val deltaToolIds = java.util.concurrent.ConcurrentHashMap<Int, String>()
        val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val isOpencode = providerSetting.baseUrl.toHttpUrl().host == "opencode.ai"
        val sentAtMs = System.currentTimeMillis()
        val firstDataAtMs = java.util.concurrent.atomic.AtomicLong(0)
        val headerReceived = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = me.rerere.ai.core.StreamWatchdog(
            isOpencode = isOpencode,
            headerReceived = headerReceived,
            hasData = hasReceivedData,
            lastEventAt = lastEventAt,
            onTimeout = { close(it) },
        )
        val watchdogJob = me.rerere.ai.core.StreamWatchdog.launchIn(this@callbackFlow, watchdog) { fired ->
            Log.w(TAG, "SSE idle timeout — closing stream: $fired")
        }

        // SSE 连接优化: 首次数据到达前断连时自动重试, 指数退避 (移植 v2.9.8 稳定行为)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)  // [DONE] 正常完成标记
        // v3.6.75: finish_reason=stop/length 已收到即视为完成 — 部分中转 (VPN 代理/
        // Go 订阅) 不发送 [DONE] 直接断开, 此前被误判为断流 → 回滚重试 → 多轮重复回复
        val gotFinish = java.util.concurrent.atomic.AtomicBoolean(false)
        // v4.5.12: 完成归因 — 流结束时记录显式 finish_reason (stop/length),
        // 中断现场日志可直接确证结束来源, 不再只有"正常收尾"一笔带过
        var lastFinishReason: String? = null
        var eventCount = 0
        val retryCount = java.util.concurrent.atomic.AtomicInteger(0)
        val maxRetries = 5 // 指数退避 1+2+4+8+16=31s 窗口, 覆盖瞬时网络波动
        var currentEventSource: EventSource? = null
        val scope = this@callbackFlow
        // v3.8.39: 正文/思考分离跟踪 — 已实证 ox-alpha-free 流式只发
        // reasoning_content (思考) 不发 content (正文): 仅思考无正文时
        // 必须可见报错而非静默"完成"
        var hasTextContent = false
        // v4.5.20: 工具调用检测标志 — "无正文关闭"判中断时必须排除正常工具
        // 回合 (工具流可无正文且部分网关无完成信号), 防误判重试
        var hasToolCalls = false
        lateinit var listener: EventSourceListener

        fun connect() {
            listener = object : EventSourceListener() {
            // v3.11.13: 响应头到达 — 阶段2 起, 计时重置
            override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                headerReceived.set(true)
                lastEventAt.set(System.currentTimeMillis())
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // v4.7.2 重写 (对齐原版 2.5.3): onEvent 必须整体 try-catch —
                // v4.0.0 重写丢失了该包裹, 解析异常逃逸到 OkHttp 线程后流被
                // 静默杀死 (无 onFailure/无 onClosed/无报错), GLM 思考内容触发
                // 解析异常时表现为"静默秒崩"。close(e) 使异常显式上抛。
                try {
                    if (data == "[DONE]") {
                        Log.d(TAG, "onEvent: [DONE]")
                        completed.set(true)
                        close()
                        return
                    }
                    // 仅有效数据刷新空闲标记 — 空行 (keep-alive) 不刷新,
                    // 否则服务器保活会使看门狗永远无法检测真挂起
                    if (data.isNotBlank()) {
                        lastEventAt.set(System.currentTimeMillis())
                        eventCount++
                        if (firstDataAtMs.compareAndSet(0, System.currentTimeMillis())) {
                            Log.i(TAG, "TTFT ${firstDataAtMs.get() - sentAtMs}ms host=${providerSetting.baseUrl.toHttpUrl().host}")
                        }
                    }
                    Log.d(TAG, "onEvent: $data")
                    data
                        .trim()
                        .split("\n")
                        .filter { it.isNotBlank() }
                        .map { json.parseToJsonElement(it).jsonObject }
                        .forEach { payload ->
                            if (payload["error"] != null) {
                                throw payload["error"]!!.parseErrorDetail()
                            }
                            val chunkId = payload["id"]?.jsonPrimitive?.contentOrNull ?: ""
                            val chunkModel = payload["model"]?.jsonPrimitive?.contentOrNull ?: ""

                            val choices = payload["choices"]?.jsonArray ?: JsonArray(emptyList())
                            val choiceList = buildList {
                                if (choices.isNotEmpty()) {
                                    val choice = choices[0].jsonObject
                                    // finish_reason 仅记录 — completed 只由 [DONE] 触发
                                    // (原版 2.5.3 语义); onClosed 不再做内容形态判定
                                    val finishReason =
                                        choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                    if (finishReason != null) {
                                        gotFinish.set(true)
                                        lastFinishReason = finishReason
                                    }
                                    val message =
                                        choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                    if (message != null) {
                                        var delta = parseMessage(message)
                                        if (delta.parts.any { it is UIMessagePart.Tool }) hasToolCalls = true
                                        if (delta.parts.any { it is UIMessagePart.Text }) hasTextContent = true
                                        // v4.7.2: chunk 级取证 — 流的每一步可回溯 (用户复现时
                                        // trace 直接显示 GLM 流的真实形态, 不再靠推断)
                                        TraceLogger.log(
                                            "SSE",
                                            "chunk #$eventCount finish=${finishReason ?: "-"} " +
                                                "parts=${delta.parts.map { it::class.simpleName }} " +
                                                "tc=${(choice["delta"] as? JsonObject)?.get("tool_calls")?.let { (it as? JsonArray)?.size } ?: 0}"
                                        )
                                        // v4.3.0: 增量流 id 回填 (tool_calls index 归属)
                                        val tcArr = (choice["delta"] as? JsonObject)?.get("tool_calls") as? JsonArray
                                        if (tcArr != null) {
                                            val tools = delta.parts.filterIsInstance<UIMessagePart.Tool>()
                                            if (tools.isNotEmpty()) {
                                                var ti = 0
                                                val patched = delta.parts.map { part ->
                                                    if (part is UIMessagePart.Tool && ti < tcArr.size) {
                                                        val tcObj = tcArr[ti].jsonObject
                                                        val idx = tcObj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                                                        val tcId = tcObj["id"]?.jsonPrimitive?.contentOrNull
                                                        ti++
                                                        when {
                                                            !tcId.isNullOrBlank() -> { deltaToolIds[idx] = tcId; part }
                                                            part.toolCallId.isBlank() -> deltaToolIds[idx]?.let { part.copy(toolCallId = it) } ?: part
                                                            else -> part
                                                        }
                                                    } else part
                                                }
                                                if (patched != delta.parts) {
                                                    delta = delta.copy(parts = patched)
                                                }
                                            }
                                        }
                                        add(
                                            UIMessageChoice(
                                                index = 0,
                                                delta = delta,
                                                message = null,
                                                finishReason = finishReason ?: "unknown",
                                            )
                                        )
                                    }
                                }
                            }
                            val usage = parseTokenUsage(payload["usage"] as? JsonObject)
                            usage?.let {
                                TraceLogger.log("SSE", "usage chunk#$eventCount completion=${it.completionTokens} prompt=${it.promptTokens}")
                            }
                            val messageChunk = MessageChunk(
                                id = chunkId,
                                model = chunkModel,
                                choices = choiceList,
                                usage = usage
                            )
                            trySend(messageChunk).onFailure { e ->
                                Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                            }
                            hasReceivedData.set(true)
                        }
                } catch (e: Throwable) {
                    // 原版语义: 解析异常显式 close — 上层收到错误, 不再静默死亡
                    Log.e(TAG, "onEvent: parse error — closing flow with exception", e)
                    close(e)
                    return
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t
                t?.printStackTrace()
                // v3.8.35: 流式失败诊断补齐 — 400/500 平台拒绝时响应体常为空,
                // 请求体结构摘要是定位唯一线索 (模型/消息数/工具数/maxTokens)
                val reqSummary = runCatching {
                    buildString {
                        append("model=").append(requestBody["model"])
                        append(", messages=").append((requestBody["messages"] as? JsonArray)?.size)
                        append(", tools=").append((requestBody["tools"] as? JsonArray)?.size)
                        append(", maxTokens=").append(requestBody["max_tokens"])
                        append(", stream=").append(requestBody["stream"])
                    }
                }.getOrDefault("?")
                Log.w(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / http=${response?.code} events=$eventCount REQ=$reqSummary")

                val bodyRaw = response?.body?.stringSafe()
                Log.w(TAG, "onFailure RESP: ${if (bodyRaw.isNullOrBlank()) "<empty>" else bodyRaw.take(600)}")
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    exception = e
                }

                // 仅在尚未收到任何数据时重试 (避免重复响应) — 移植 v2.9.8 稳定行为
                // v4.3.13 (BUG20): 4xx 客户端错误 (invalid_request/context length 等)
                // 终态不重试 — 重试只会原样重发同一大请求, 指数退避串行空转,
                // 用户感知"彻底卡死"。仅 408 (超时) / 429 (限流) 保留重试价值。
                val httpCodeForRetry = response?.code ?: 0
                val clientErrorNoRetry = httpCodeForRetry in 400..499 && httpCodeForRetry != 408 && httpCodeForRetry != 429
                if (!hasReceivedData.get() && !clientErrorNoRetry && retryCount.incrementAndGet() <= maxRetries && !scope.isClosedForSend) {
                    val delayMs = 1000L * (1 shl (retryCount.get() - 1))
                    Log.w(TAG, "SSE pre-data failure, retry ${retryCount.get()}/$maxRetries after ${delayMs}ms: ${exception?.message}")
                    scope.launch {
                        delay(delayMs)
                        if (!scope.isClosedForSend) {
                            connect()
                        }
                    }
                    return
                }

                // 4.0.1: 5xx 服务端瞬时错误转 IOException 进重试链 (与 ClaudeProvider 同步) —
                // HttpException 是 RuntimeException, 重试链只捕 IOException, 不转则 500 终态失败
                if (exception != null && response?.code in 500..599 && exception !is IOException) {
                    Log.w(TAG, "server error ${response?.code} — wrapping as IOException for retry chain")
                    exception = IOException("[${response?.code}] ${exception.message}", exception)
                }
                close(exception)
            }

            override fun onClosed(eventSource: EventSource) {
                // v4.7.2 重写 (对齐原版 2.5.3): 全部内容形态判定链删除 —
                // 截断启发/名单分流/思考正文化/零输出重试/usage 置位退役。
                // 无信号关闭 = 静默结束 (v4.4.x 行为, 用户实证该时期 GLM 正常)。
                // 静默秒崩的根源是 v4.0.0 重写丢失的 onEvent 异常包裹 (已在
                // onEvent 恢复 try-catch 显式化), 不是缺少完成信号判定。
                close()
            }
            }

            currentEventSource = EventSources.createFactory(
                effClient(providerSetting).resolveProxy(proxyRoute, params.model.modelId)
            ).newEventSource(request, listener)
        }

        connect()

        awaitClose {
            Log.d(TAG, "awaitClose: cancelling eventSource")
            watchdogJob.cancel()
            currentEventSource?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /**
     * v4.3.10 (BUG15 v2): 图片预算的消费端 — 替换已标记降级的图片为占位。
     * 预算判定与持久标记由 GenerationHandler (app 层) 负责: 降级标记写入
     * Image.metadata["budget_dropped"], 随消息落盘持久化, 降级不可逆 →
     * 请求前缀在已降级位置恒定 (消除滚动重算造成的每轮断裂点后移)。
     * 本层只做确定性替换: 带标记的 Image → 占位文本 (含原路径+read_image 指引)。
     */
    private fun applyImageMarkers(messages: List<UIMessage>): List<UIMessage> {
        var replaced = 0
        val out = messages.map { msg ->
            val newParts = msg.parts.map { part ->
                when {
                    part is UIMessagePart.Image && part.metadata?.get("budget_dropped")?.jsonPrimitive?.contentOrNull == "true" ->
                        UIMessagePart.Text(imageBudgetPlaceholder(part)).also { replaced++ }
                    part is UIMessagePart.Tool -> part.copy(output = part.output.map { op ->
                        if (op is UIMessagePart.Image && op.metadata?.get("budget_dropped")?.jsonPrimitive?.contentOrNull == "true") {
                            UIMessagePart.Text(imageBudgetPlaceholder(op)).also { replaced++ }
                        } else op
                    })
                    else -> part
                }
            }
            msg.copy(parts = newParts)
        }
        if (replaced > 0) Log.i(TAG, "Image budget: replaced " + replaced + " marked images with placeholders")
        return out
    }

    private fun estimateImageBytes(image: UIMessagePart.Image): Long {
        return when {
            image.url.startsWith("data:") -> image.url.length.toLong()  // base64 内联: 字符数量级≈字节数
            image.url.startsWith("file://") -> runCatching {
                java.io.File(image.url.removePrefix("file://")).length()
            }.getOrDefault(0L)
            else -> 0L  // 公网 URL: 无法本地计量, 仅计入张数预算
        }
    }

    private fun imageBudgetPlaceholder(image: UIMessagePart.Image): String = when {
        image.url.startsWith("file://") ->
            "[图片已省略 (超出本请求图片预算): 原文件已保存于会话中, 路径: " + image.url + " — 需要查看时调用 read_image 工具传入该路径]"
        else -> "[图片已省略 (超出本请求图片预算): 内联图片数据未保留]"
    }

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        // v4.3.6 (BUG15): 图片预算裁剪 — 严格端点 (GLM 类) 限制单请求图片数量/体积,
        // 高强度视觉工作流超限后整个请求被 [too_many_images] 拒绝, 用户全部进度中断。
        // 裁剪发生在请求副本上 (UI 不受影响), 保留最近图片, 超额部分降级为文本占位。
        val budgetedMessages = applyImageMarkers(messages)
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    messages = budgetedMessages,
                    includeHistoryReasoning = providerSetting.includeHistoryReasoning,
    
                )
            )

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            // v3.11.8: max_tokens 缺失兜底按家族分离 (用户: 家族冲突时按家族处理,
            // 一刀切会让部分模型不可用):
            //   DeepSeek 家族 (V4 输出上限 384K) / 网关默认 → 32K (OpenCode 官方默认)
            //   Kimi 家族 (K2.5 缺失默认 1024 截断) → 8K
            //   GLM 家族 (上限 131072, 建议≥1024) → 8K
            //   Grok 家族 (默认 4096, 上限保守) → 4K
            // 用户显式设置时尊重设置 (assistant.maxTokens)。
            val maxTokensFallback = when (host) {
                "api.deepseek.com" -> 32_768
                "api.moonshot.cn" -> 8_192
                "open.bigmodel.cn" -> 8_192
                "api.x.ai", "api.grok.ai" -> 4_096
                else -> 32_768  // opencode.ai 网关等: 与 OpenCode 官方默认一致
            }
            put("max_tokens", params.maxTokens ?: maxTokensFallback)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(host == "openrouter.ai") {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            // 4.0.0 重写: 思考控制按 host 分派提取为独立纯函数 thinkingControlFields
            // (原 185 行内联 when — host×家族×档位映射单一可测试)
            if (params.model.abilities.contains(ModelAbility.REASONING) || isAggregateGateway(host)) {
                thinkingControlFields(host, params)?.forEach { (k, v) -> put(k, v) }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    /** 聚合网关放行判定 (v3.15.2): 网关对不支持参数自行容错, 思考控制不被模型注册吞掉 */
    private fun isAggregateGateway(host: String): Boolean =
        host == "opencode.ai" || host == "api.commandcode.ai"

    /**
     * 4.0.0 重写: 思考控制字段按 host 分派 (原 buildChatCompletionRequest 内联
     * 185 行 when 块 → 独立纯函数, 输入 host+params, 输出字段集或 null)。
     * cherryCompatMode 时返回 null (v3.16.0: 强兼容零思考参数)。
     * 全部分派数值/文案逐字保留 (v3.15.2/3/4 定版语义)。
     */
    private fun thinkingControlFields(host: String, params: TextGenerationParams): JsonObject? {
        // 4.1.2: 门控诊断日志 — level/abilities/是否发送, 思考问题直接看此行
        val gate = params.model.abilities.contains(ModelAbility.REASONING) || isAggregateGateway(host)
        Log.i(TAG, "Thinking gate(cc): host=$host level=${params.reasoningLevel} " +
            "abilities=${params.model.abilities} → send=$gate")
        if (!gate) return null
        val level = params.reasoningLevel
        fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
            buildJsonObject(block)
        return when (host) {
            "openrouter.ai" -> obj {
                // https://openrouter.ai/docs/use-cases/reasoning-tokens
                put("reasoning", buildJsonObject {
                    when (level) {
                        ReasoningLevel.OFF -> put("effort", "none")
                        ReasoningLevel.AUTO -> put("enabled", true)
                        else -> put("effort", level.effort)
                    }
                })
            }
            "dashscope.aliyuncs.com" -> obj {
                // 阿里云百炼
                put("enable_thinking", level.isEnabled)
                if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
            }
            "ark.cn-beijing.volces.com" -> obj {
                // 豆包 (火山)
                put("thinking", buildJsonObject {
                    put("type", if (!level.isEnabled) "disabled" else "enabled")
                })
            }
            "api.mistral.ai" -> null
            "chat.intern-ai.org.cn" -> obj {
                // 书生
                put("thinking_mode", level.isEnabled)
            }
            "api.siliconflow.cn" -> {
                // https://docs.siliconflow.cn/... thinking 白名单模型才发
                val modelId = params.model.modelId
                val siliconflowThinkingModels = setOf(
                    "Pro/moonshotai/Kimi-K2.5",
                    "Pro/zai-org/GLM-5",
                    "Pro/zai-org/GLM-5.1",
                    "Pro/zai-org/GLM-4.7",
                    "deepseek-ai/DeepSeek-V3.2",
                    "Pro/deepseek-ai/DeepSeek-V3.2",
                    "Qwen/Qwen3.5-397B-A17B",
                    "Qwen/Qwen3.5-122B-A10B",
                    "Qwen/Qwen3.5-35B-A3B",
                    "Qwen/Qwen3.5-27B",
                    "Qwen/Qwen3.5-9B",
                    "Qwen/Qwen3.5-4B",
                    "zai-org/GLM-4.6",
                    "Qwen/Qwen3-8B",
                    "Qwen/Qwen3-14B",
                    "Qwen/Qwen3-32B",
                    "Qwen/Qwen3-30B-A3B",
                    "tencent/Hunyuan-A13B-Instruct",
                    "zai-org/GLM-4.5V",
                    "deepseek-ai/DeepSeek-V3.1-Terminus",
                    "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                    "deepseek-ai/DeepSeek-V4-Flash",
                    "Pro/deepseek-ai/DeepSeek-V4-Flash",
                    "deepseek-ai/DeepSeek-V4-Pro",
                    "Pro/deepseek-ai/DeepSeek-V4-Pro",
                )
                if (modelId in siliconflowThinkingModels) obj {
                    put("enable_thinking", level.isEnabled)
                } else null
            }
            "aiping.cn" -> obj {
                put("enable_thinking", level.isEnabled)
            }
            "open.bigmodel.cn" -> obj {
                put("thinking", buildJsonObject {
                    put("type", if (!level.isEnabled) "disabled" else "enabled")
                })
            }
            "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> obj {
                // v3.9.12 (2.4.11 移植): 小米 MiMo
                put("thinking", buildJsonObject {
                    put("type", if (!level.isEnabled) "disabled" else "enabled")
                })
            }
            "api.moonshot.cn" -> obj {
                put("thinking", buildJsonObject {
                    put("type", if (!level.isEnabled) "disabled" else "enabled")
                    // v4.5.31 (#1586): K2.6 思考开启时 keep=all (保留式思考);
                    // K2.5 不支持 keep — 仅对 k2.6 且 enabled 时发送
                    if (level.isEnabled && params.model.modelId.contains("k2.6", ignoreCase = true)) {
                        put("keep", "all")
                    }
                })
            }
            "api.deepseek.com", "opencode.ai" -> {
                // v3.15.2: DeepSeek 家族发 thinking/reasoning_effort (官方语义);
                // 非 DeepSeek 家族走 Bifrost reasoning_effort 全档 (AUTO 不发)
                val isDeepSeekFamily = host == "api.deepseek.com" ||
                    params.model.modelId.contains("deepseek", ignoreCase = true)
                // v4.5.8: thinking 字段仅对确认支持的 host 发送。实测 20260916:
                // OpenCode 网关 (Console Go) 的 CC 通道 schema 不含 thinking,
                // Go 端严格解析直接 400 (unknown field) — v4.5.7 的 GLM 家族
                // 分支在网关侧不可用。智谱直连 (open.bigmodel.cn) 维持原生
                // thinking.type 二态 (GLM 无档位: OFF=disabled 其余=enabled);
                // 经网关的 GLM 回退 reasoning_effort (网关 schema 内, 不 400),
                // 思考开关是否生效取决于网关是否向智谱映射该参数。
                val isGlmFamily = host == "open.bigmodel.cn" &&
                    params.model.modelId.contains("glm", ignoreCase = true)
                // v4.5.9: Grok 4.6 语义 (x.ai 官方) — reasoning_effort 四档
                // low/medium/high/xhigh, 默认 low, 无 true disabled (Think/Big
                // Brain flags 已废)。OFF→low 为 xAI 语义下最接近关闭的档位;
                // 不发任何参数时模型自主决定是否推理, 即"有时思考有时不思考"。
                val isGrokFamily = params.model.modelId.contains("grok", ignoreCase = true)
                if (isGrokFamily && (host == "api.x.ai" || host == "api.grok.ai" || host == "opencode.ai")) {
                    if (level != ReasoningLevel.AUTO) {
                        val effort = when (level) {
                            ReasoningLevel.OFF, ReasoningLevel.LOW -> "low"
                            ReasoningLevel.MEDIUM -> "medium"
                            ReasoningLevel.HIGH -> "high"
                            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "xhigh"
                        }
                        obj { put("reasoning_effort", effort) }
                    } else null
                } else if (isGlmFamily) {
                    obj {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }
                } else if (isDeepSeekFamily) {
                    obj {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            // 4.0.7 对齐原版 2.4.17; v4.5.9 按官方档位修订:
                            // V4 官方档位 low/high/max (low 自 0731 卡片起合法);
                            // MEDIUM/HIGH→high, XHIGH/MAX→max (xhigh 是 V4.1 才有)
                            val effort = when (level) {
                                ReasoningLevel.MEDIUM, ReasoningLevel.HIGH -> "high"
                                ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                                else -> level.effort
                            }
                            put("reasoning_effort", effort)
                        }
                    }
                } else if (host == "opencode.ai" && level != ReasoningLevel.AUTO) {
                    // 4.0.7 对齐原版: effort 直透 (OFF→none 原样发) —
                    // 撤 v3.15.3 none→minimal 补丁
                    obj { put("reasoning_effort", level.effort) }
                } else null
            }
            "integrate.api.nvidia.com" -> {
                if ("deepseek-v4" in params.model.modelId.lowercase()) {
                    if (level != ReasoningLevel.AUTO) {
                        val effort = when (level) {
                            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                            ReasoningLevel.OFF -> "none"
                            else -> "high"
                        }
                        obj { put("reasoning_effort", effort) }
                    } else null
                } else {
                    if (level != ReasoningLevel.AUTO) {
                        obj { put("reasoning_effort", if (level.effort == "none") "low" else level.effort) }
                    } else null
                }
            }

            else -> {
                // OpenAI 官方: completions API 只支持 low/medium/high
                if (level != ReasoningLevel.AUTO) {
                    obj { put("reasoning_effort", if (level.effort == "none") "low" else level.effort) }
                } else null
            }
        }
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
    }

    private fun JsonArrayBuilder.addCherryNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))
            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }
                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure { e ->
                                        Log.w(TAG, "encode user image failed: ${part.url}", e)
                                        put("type", "text")
                                        put("text", "[图片编码失败: ${part.url} — 需要查看时调用 read_image 工具传入该路径]")
                                    }
                                })
                            }
                            else -> {}
                        }
                    }
                }
            }
        })
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,

    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = includeHistoryReasoning,
                )
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        imageUploadCompat: Boolean = false,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    // v4.3.3 (BUG12): 整段重写 — OpenAI 现行标准 role=tool 消息仅
                    // role/tool_call_id/content 三字段。历史残留的 "name" 字段
                    // (老版协议的可选字段, 已废弃) 被严格端点拒绝: 用户实证
                    // [invalid_request_error] messages[3]: "name" is not supported
                    // by this endpoint — 工具结果回传后第二轮全部被拒, 表现为
                    // 生成永久卡死。工具归属由 tool_call_id 唯一确定 (v4.3.0 起
                    // 增量 id 回填保证非空), name 无任何消费方, 移除。
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(imageUploadCompat = imageUploadCompat))
                        })
                    }
                    // v4.3.12 (BUG19): 工具结果图转移 — tool content 只能 text
                    // (GLM 网关硬限制), 图片改为紧随的 user 消息附图: 模型支持
                    // 图片输入时直接可见 (无需 read_image 二次拉取); 预算降级
                    // (budget_dropped) 的图在 applyImageMarkers 已替换为占位,
                    // 此处只处理预算内的图。
                    // v4.5.5: 图片上传模式分流 — compat 时工具图内嵌 tool content
                    // (v4.3.12 前形态, 适用于接受工具结果带图的网关), 不转移不降级
                    val groupImages = if (imageUploadCompat) emptyList() else group.tools
                        .flatMap { it.output }
                        .filterIsInstance<UIMessagePart.Image>()
                        .filter { it.metadata?.get("budget_dropped")?.jsonPrimitive?.contentOrNull != "true" }
                    if (groupImages.isNotEmpty()) {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", "[工具返回的图片]")
                                })
                                groupImages.forEach { img ->
                                    add(buildJsonObject {
                                        img.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", encodedImage.base64)
                                            })
                                        }.onFailure { e ->
                                            Log.w(TAG, "encode tool-result image failed: ${img.url}", e)
                                            put("type", "text")
                                            put("text", "[图片编码失败: ${img.url} — 可调用 read_image 工具传入该路径读取]")
                                        }
                                    })
                                }
                            })
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
    ): JsonObject? {
        // v4.7.4: 整段重写对齐原版 2.5.3 — v4.5.18 把 content="" 改成 content=null
        // (有 tool_calls 时) 是 GLM 空回复的根因。原版 content="" 对全场景安全
        // (原版验证: GLM + opencode.ai 长对话缓存 99%+ 正常)。
        // v4.5.18 的"思考提升为正文"和"content=null"分支全部退役。
        val usableContent = contentParts.filter { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (usableContent.isEmpty() && !hasReasoning && tools.isEmpty()) {
            return null
        }
        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content — 对齐原版: hasReasoning 即回传
            if (hasReasoning) {
                put("reasoning_content", reasoningPart.reasoning)
            }

            // content — 对齐原版 2.5.3: 空内容时 content="" (非 null)
            // v4.5.18 改成 JsonNull 导致 GLM 对 content:null + tool_calls 的
            // assistant 消息触发异常路径输出空回复 (finish=stop + 零 delta)。
            // 原版 content="" 对所有上游安全 (含 Console Go / opencode.ai)。
            when {
                usableContent.size == 1 && usableContent[0] is UIMessagePart.Text -> {
                    put("content", (usableContent[0] as UIMessagePart.Text).text)
                }
                usableContent.isNotEmpty() -> {
                    putJsonArray("content") {
                        usableContent.forEach { part ->
                            when (part) {
                                is UIMessagePart.Text -> {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", part.text)
                                    })
                                }
                                is UIMessagePart.Image -> {
                                    add(buildJsonObject {
                                        part.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "image_url")
                                            put("image_url", buildJsonObject {
                                                put("url", encodedImage.base64)
                                            })
                                        }.onFailure { e ->
                                            Log.w(TAG, "encode user image failed: ${part.url}", e)
                                            put("type", "text")
                                            put("text", "[图片编码失败: ${part.url} — 需要查看时调用 read_image 工具传入该路径]")
                                        }
                                    })
                                }
                                else -> {}
                            }
                        }
                    }
                }
                else -> {
                    // 空内容 (有 tool_calls 或纯思考) — content="" 对齐原版
                    put("content", "")
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                put("arguments", tool.inputAsJson().toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure { e ->
                                        Log.w(TAG, "encode non-assistant image failed: ${part.url}", e)
                                        put("type", "text")
                                        put("text", "[图片编码失败: ${part.url} — 需要查看时调用 read_image 工具传入该路径]")
                                    }
                                })
                            }

                            // v4.3.16: 导入的 role=tool 消息 (如第三方导入) — 提取结果文本,
                            // 避免 Tool part 被忽略后 content 为空数组遭上游拒收
                            is UIMessagePart.Tool -> part.output.forEach { op ->
                                if (op is UIMessagePart.Text && op.text.isNotBlank()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", op.text)
                                    })
                                }
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    /**
     * v4.3.12 (BUG19): tool content 纯文本化 — 用户实证 GLM 网关硬限制:
     * messages[N]: tool content: part type "image_url" is not supported;
     * only text is。即使模型声明了 IMAGE modality, role=tool 的 content
     * 也不接受 image_url part。改为无条件纯文本: 工具结果中的图片以占位
     * 文本交代去向, 真正的图片由调用方 (工具分组循环) 转移到紧随其后的
     * user 消息中 — 模型支持图片输入时直接可见, 不支持时占位提示。
     */
    private fun UIMessagePart.Tool.toToolResultContent(imageUploadCompat: Boolean = false): JsonElement {
        // v4.5.5: 图片模式分流 — compat 时工具图以内嵌 image_url 进入 tool
        // content (v4.3.12 前形态); classic 时保持占位文本 (图由调用方转移)。
        // content 结构按是否含图选择: 纯文本=字符串 (兼容严格上游), 含图=块数组。
        if (imageUploadCompat && output.any { it is UIMessagePart.Image }) {
            return buildJsonArray {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> if (part.text.isNotBlank()) add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                        is UIMessagePart.Image -> add(buildJsonObject {
                            part.encodeBase64().onSuccess { encoded ->
                                put("type", "image_url")
                                put("image_url", buildJsonObject { put("url", encoded.base64) })
                            }.onFailure {
                                put("type", "text")
                                put("text", "[图片编码失败: " + part.url + " — 需要查看时调用 read_image 工具传入该路径]")
                            }
                        })
                        else -> {}
                    }
                }
            }
        }
        val lines = output.mapNotNull { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Image -> "[图片已省略: 工具返回的图片已作为随后的用户消息附图提供]"
                else -> null
            }
        }
        // v4.3.16: 空工具输出防线 — OpenCode API 要求 tool content 至少 1 项
        return JsonPrimitive(lines.filter { it.isNotBlank() }.ifEmpty { listOf("[工具返回为空]") }.joinToString("\n"))
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // content 可能是字符串或 block 数组 (如 [{type:"text",text:"..."}]); 数组时拼接 text 块, 否则文本丢失 (对齐原版)
        val contentElement = jsonObject["content"]
        val content = contentElement?.jsonPrimitiveOrNull?.contentOrNull
            ?: (contentElement as? JsonArray)?.mapNotNull { block ->
                val obj = block.jsonObjectOrNull ?: return@mapNotNull null
                if (obj["type"]?.jsonPrimitiveOrNull?.contentOrNull == "text") {
                    obj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                } else {
                    null
                }
            }?.joinToString("") ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: (contentElement as? JsonArray)?.mapNotNull { block ->
                // v3.10.2: content 数组 thinking 块泛化提取 — 兼容多种网关形态,
                // 修复 GPT-5.6-Luna (Opencode 订阅) 思考链完全丢失:
                // 原实现只取第一块 thinking 的嵌套 thinking[0].text (Mistral 形态),
                // 网关发 thinking 直接带 text / thinking 字符串 / 多块时全部丢失。
                // 参考: Mistral {"content":[{"type":"thinking","thinking":[{"type":"text","text":"..."}]}]}
                val obj = block.jsonObjectOrNull ?: return@mapNotNull null
                if (obj["type"]?.jsonPrimitiveOrNull?.contentOrNull != "thinking") return@mapNotNull null
                obj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: (obj["thinking"] as? JsonArray)?.mapNotNull { t ->
                        t.jsonObjectOrNull?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                            ?: t.jsonObjectOrNull?.toString()?.takeIf { it.isNotBlank() }
                    }?.joinToString("")
                    ?: obj["thinking"]?.jsonPrimitiveOrNull?.contentOrNull
            }?.filter { !it.isNullOrBlank() }?.joinToString("\n")?.takeIf { it.isNotBlank() }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        // v3.6.44: 缓存命中字段统一解析 — DeepSeek 用顶层 prompt_cache_hit_tokens,
        // OpenAI 用 prompt_tokens_details.cached_tokens (v3.3.12 回滚时丢失 → DeepSeek 缓存率恒为 0)
        val promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        // v3.6.53: 各 provider 缓存命中字段形状不统一, 按方言兜底解析 (对齐原版 #1576):
        // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
        val cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
            ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
            ?: 0
        // v3.8.43: 缓存命中钳制 — cached 不得超过本轮 prompt (中转/网关偶发
        // 将历史累计命中打包, 显示上出现 cached>prompt 违背直觉的脏数据)
        val cachedTokensClamped = if (promptTokens > 0) minOf(cachedTokens, promptTokens) else 0
        if (cachedTokens > 0) {
            val hitRate = if (promptTokens > 0) cachedTokensClamped * 100 / promptTokens else 0
            Log.i(TAG, "Cache hit: $cachedTokensClamped/$promptTokens tokens (${hitRate}%)")
        }
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokensClamped
        )
    }

    // 4.2.0 接口切换: Flow<StreamChunk> (原版 2.5.x 形态) — 内层解析仍产出
    // MessageChunk, 由 MessageChunkStreamAdapter 桥接 (行为等价, v4.2.1 起内层逐步替换 StreamDecoder)
    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val adapter = me.rerere.ai.ui.MessageChunkStreamAdapter()
        return streamTextRaw(providerSetting, messages, params).flatMapConcat { chunk ->
            adapter.adapt(chunk).asFlow()
        }
    }


    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }

    companion object {
        /** v4.3.10: 占位文案 — 预算判定/常量在 GenerationHandler (app 层), 此处仅消费标记 */
        /**
         * 判断流式传输中断是否为可恢复错误 (stream reset / protocol error).
         * 对于可恢复错误, 若已有部分数据到达则保留部分响应, 避免整体丢失.
         */
        fun isRecoverableStreamError(e: java.io.IOException): Boolean {
            val msg = e.message ?: return false
            return msg.contains("stream was reset", ignoreCase = true) ||
                   msg.contains("protocol error", ignoreCase = true) ||
                   msg.contains("unexpected end of stream", ignoreCase = true) ||
                   msg.contains("connection reset", ignoreCase = true) ||
                   msg.contains("connection abort", ignoreCase = true) ||
                   msg.contains("software caused", ignoreCase = true) ||
                   msg.contains("timeout", ignoreCase = true) ||
                   msg.contains("broken pipe", ignoreCase = true) ||
                   msg.contains("connection closed", ignoreCase = true) ||
                   msg.contains("canceled", ignoreCase = true)
        }
    }
}

