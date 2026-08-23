@file:OptIn(DelicateCoroutinesApi::class)
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
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
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
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
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
class OpenCodeStreamUnconfirmedException(message: String) : IOException(message)

private const val TAG = "ChatCompletionsAPI"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
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
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        val response = client.newCall(request).await()
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
        )
    }

    override suspend fun streamText(
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
            .build()

        // v3.6.17: 降 d — release 裁剪 (每请求大 JSON 格式化是功耗热点, debug 保留诊断)
        Log.d(TAG, "streamText: ${json.encodeToString(requestBody)}")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())

        // SSE 有效数据看门狗: 120s 无有效数据 → 主动断开 (触发收尾+断流重试)。
        // 教训链: v3.5.14 主动断开误杀长思考 → 改只日志; v3.5.45 缩短到 25s 后
        // 用户实测误杀 (Trace 95098f39: 平台存在 >25s 静默期, 非断流) —
        // 25s 假设"推理期间持续有 reasoning chunk"在用户环境不成立。
        // v3.6.5 分阶段 watchdog: 首包前 60s 断 (连接无数据 → 快速失败进入
        // 断流重试, 收敛中断后"正在输出"挂起时长 — 用户实测中断后长时间不结束);
        // 首包后 120s (平台思考/回复间隙静默不误杀)。
        val hasReceivedData = java.util.concurrent.atomic.AtomicBoolean(false)  // 前置声明 (watchdog 引用)
        val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        // v3.6.44: opencode.ai 针对性适配 — 网关聚合转发 + 深度思考, 静默期更长,
        // 放宽 watchdog 避免误杀长思考 (首包 120s / 流中 180s)
        val isOpencode = providerSetting.baseUrl.toHttpUrl().host == "opencode.ai"
        // v3.8.40: ox 系模型正文走 reasoning_content (社区实锤: opencode 客户端
        // 将其直接当正文显示, 无独立 content 输出)。开启后 reasoning_content
        // 提升为正文, 不再作为思考链单独显示 — 与 opencode 行为对齐。
        val reasoningAsBody = isOpencode && (
            params.model.displayName.contains("ox", ignoreCase = true) ||
                params.model.displayName.contains("x-preview", ignoreCase = true) ||
                params.model.modelId.contains("x-preview", ignoreCase = true)
            )
        val firstByteLimit = if (isOpencode) 120_000L else 60_000L
        val streamLimit = if (isOpencode) 180_000L else 120_000L
        val watchdog = launch {
            while (true) {
                delay(15_000)
                val idleMs = System.currentTimeMillis() - lastEventAt.get()
                val limit = if (hasReceivedData.get()) streamLimit else firstByteLimit
                if (idleMs > limit) {
                    Log.w(TAG, "SSE idle ${idleMs / 1000}s (phase=${if (hasReceivedData.get()) "stream" else "first-byte"}) — closing stream")
                    close(java.io.IOException("生成无有效数据超时 (${limit / 1000}s): 平台断流或卡死"))
                    break
                }
            }
        }

        // SSE 连接优化: 首次数据到达前断连时自动重试, 指数退避 (移植 v2.9.8 稳定行为)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)  // [DONE] 正常完成标记
        // v3.6.75: finish_reason=stop/length 已收到即视为完成 — 部分中转 (VPN 代理/
        // Go 订阅) 不发送 [DONE] 直接断开, 此前被误判为断流 → 回滚重试 → 多轮重复回复
        val gotFinish = java.util.concurrent.atomic.AtomicBoolean(false)
        val retryCount = java.util.concurrent.atomic.AtomicInteger(0)
        val maxRetries = 5 // 指数退避 1+2+4+8+16=31s 窗口, 覆盖瞬时网络波动
        var currentEventSource: EventSource? = null
        val scope = this@callbackFlow
        // v3.8.33: 完成信号诊断 + 物理判据 —
        // SSE 每行 data 必须是完整 JSON; 服务端正常发完关流 = 所有行完整;
        // 中途掐断 = 最后一行残缺 (parse 失败)。
        // 事件数与最近 5 条原始数据缓冲, 关流/失败时输出核对服务端收尾形态。
        var eventCount = 0
        var lastEventParsed = false
        // v3.8.36: 文本尾部跟踪 — 服务端"行完整但内容截断"场景 (无完成信号的
        // 模型输出被平台截短仍按完整行发送), 需内容形态启发辅助判定
        var textTail = ""
        // v3.8.39: 正文/思考分离跟踪 — 已实证 ox-alpha-free 流式只发
        // reasoning_content (思考) 不发 content (正文): 仅思考无正文时
        // 必须可见报错而非静默"完成"
        var hasTextContent = false
        var reasoningTail = ""
        // v3.8.42: 思考缓冲 — ox 系无 content 时结束后正文化; 流中思考保持思考链
        val reasoningBuffer = StringBuilder()
        // v3.8.38: 最后一条"delta 非空"块原文 + 字段名 — 定位 Zen 网关内容块
        // 真实结构 (用户实测 287 events tail 仍为空: 网关文本不走 content 字段)
        var lastDeltaRaw = ""
        var lastDeltaKeys = ""
        val lastEvents = ArrayDeque<String>()
        fun recordEvent(data: String) {
            val lines = data.trim().split("\n").filter { it.isNotBlank() }
            for (line in lines) {
                lastEventParsed =
                    runCatching { json.parseToJsonElement(line); true }.getOrDefault(false)
                // 记录最后一条含非空 delta 的 chunk (结构取证)
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val choices = obj["choices"] as? JsonArray
                    val delta = choices?.firstOrNull()
                        ?.let { (it as JsonObject)["delta"] as? JsonObject }
                    if (delta != null && delta.keys.isNotEmpty()) {
                        lastDeltaKeys = delta.keys.joinToString(",")
                        lastDeltaRaw = line.take(300)
                    }
                }
            }
            eventCount++
            val preview = data.trim().replace("\n", " ")
            lastEvents.addLast(if (preview.length > 160) preview.take(160) + "…" else preview)
            while (lastEvents.size > 5) lastEvents.removeFirst()
        }
        fun dumpLastEvents(): String = lastEvents.joinToString(" | ") { it.replace("\n", " ") }
        // v3.8.36: 内容级截断启发 (仅辅助无完成信号模型, DeepSeek 等有官方
        // finish_reason 信号不启用)。强特征才报, 防误报:
        // 未闭合代码块 / 以逗号分号顿号冒号破折号收尾 / 以连接词收尾
        fun looksTruncated(tail: String): Boolean {
            if (tail.isBlank()) return false
            if (tail.count { it == '`' } % 2 != 0) return true
            val last = tail.lastOrNull() ?: return false
            if (last in ",;，；、:：|_—–".toList()) return true
            val lower = tail.lowercase()
            return lower.endsWith(" and") || lower.endsWith(" because") ||
                lower.endsWith(" but") || lower.endsWith(" the") ||
                tail.endsWith("但是") || tail.endsWith("因为") ||
                tail.endsWith("然后") || tail.endsWith("以及") ||
                tail.endsWith("所以") || tail.endsWith("总之") || tail.endsWith("因此")
        }
        lateinit var listener: EventSourceListener

        fun connect() {
            listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    Log.d(TAG, "onEvent: [DONE]")
                    completed.set(true)  // 正常完成标记 — onClosed 据此区分静默中断
                    close()
                    return
                }
                // 仅有效数据刷新空闲标记 — 空行 (keep-alive) 不刷新,
                // 否则服务器保活会使看门狗永远无法检测真挂起
                if (data.isNotBlank()) lastEventAt.set(System.currentTimeMillis())
                recordEvent(data)
                Log.d(TAG, "onEvent: $data")
                data
                    .trim()
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .map { json.parseToJsonElement(it).jsonObject }
                    .forEach {
                        if (it["error"] != null) {
                            val error = it["error"]!!.parseErrorDetail()
                            throw error
                        }
                        val id = it["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = it["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = it["choices"]?.jsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            if (choices.isNotEmpty()) {
                                val choice = choices[0].jsonObject
                                // v3.8.31: finish_reason 判定提到 choice 层 —
                                // 结尾 chunk 常为 delta:null + finish_reason:"stop",
                                // 原判定写在 message!=null 分支内会漏判 → 误判断流
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                if (finishReason == "stop" || finishReason == "length") {
                                    gotFinish.set(true)
                                }
                                val message =
                                    choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                // v3.8.37: 记录文本尾部 (截断启发用) — 兼容三种 chunk 形态:
                                // string content (OpenAI 标准) / content 数组 (Claude 风格
                                // 网关: [{"type":"text","text":"..."}]) / thinking 文本
                                (choice["delta"] as? JsonObject)?.get("content")?.let { raw ->
                                    val text = when (raw) {
                                        is JsonPrimitive -> raw.contentOrNull
                                        is JsonArray -> raw.mapNotNull {
                                            (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                                        }.joinToString("")
                                        else -> null
                                    }
                                    text?.takeIf { it.isNotBlank() }?.let {
                                        hasTextContent = true
                                        textTail = if (it.length > 80) it.takeLast(80) else it
                                    }
                                }
                                // v3.8.39/40: 思考内容跟踪 — 非 ox 模型记录思考尾部
                                // (无正文场景判定); ox 模型 (reasoningAsBody) 思考即正文,
                                // 直接提升为正文尾部并视为有正文
                                val reasoningRaw = (choice["delta"] as? JsonObject)
                                    ?.get("reasoning_content")?.jsonPrimitiveOrNull?.contentOrNull
                                    ?: message?.get("reasoning_content")?.jsonPrimitiveOrNull?.contentOrNull
                                if (reasoningAsBody) {
                                    // v3.8.42: 流中思考保持思考链实时显示 (不再提升为正文);
                                    // 缓冲全文, 仅当流结束仍无 content 时才正文化补发
                                    reasoningRaw?.takeIf { it.isNotBlank() }?.let {
                                        reasoningBuffer.append(it)
                                    }
                                } else {
                                    reasoningRaw?.takeIf { it.isNotBlank() }?.let {
                                        reasoningTail = if (it.length > 80) it.takeLast(80) else it
                                    }
                                }
                                if (message != null) {
                                    val delta = parseMessage(message)
                                    // v3.8.42: 思考链经 parseMessage 自然成为 Reasoning part,
                                    // 正文经 content 提取 — 两者不再混淆
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
                        val usage = parseTokenUsage(it["usage"] as? JsonObject)
                        // v3.6.78: grok 系 (OpenCode Zen) 不发 [DONE] 也不发
                        // finish_reason=stop, 以 usage/cost 结尾行标记完成 —
                        // usage 或 cost 收到即视为本轮完成信号
                        if (usage != null || it["cost"] != null) {
                            gotFinish.set(true)
                        }

                        val messageChunk = MessageChunk(
                            id = id,
                            model = model,
                            choices = choiceList,
                            usage = usage
                        )
                        trySend(messageChunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                        hasReceivedData.set(true)
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
                if (!hasReceivedData.get() && retryCount.incrementAndGet() <= maxRetries && !scope.isClosedForSend) {
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

                close(exception)
            }

            override fun onClosed(eventSource: EventSource) {
                // 服务器主动关闭连接: [DONE] 或 finish_reason=stop 已收到 → 正常完成;
                // 否则视为断流 (消息不完整且无报错 → 用户感知"莫名其妙中断")
                // v3.8.33 物理判据 (替代 v3.8.31/32 的模型名单分流与一律未确认):
                //   SSE 每行必须是完整 JSON。最后一行解析成功 = 服务端把本段数据
                //   完整发完 (正常完结, 即使无 [DONE]/stop/usage — Zen 网关常态);
                //   最后一行残缺 = 服务端中途掐断 (真断流)。
                //   按物理层的事实判定, 不再猜测模型行为。
                if (!completed.get() && !gotFinish.get()) {
                    if (isOpencode && hasReceivedData.get()) {
                        // v3.8.42: 运行时自适应 —
                        //   行完整 + 有 content => 正常完成 (思考链与正文泾渭分明);
                        //   行完整 + 无 content + 有思考缓冲 => 思考正文化补发为正文
                        //   (ox 系纯思考输出场景, 对齐 opencode 客户端行为);
                        //   行残缺 / 尾部强截断特征 => 保留内容 + 明确报错。
                        val truncated = !lastEventParsed || looksTruncated(textTail)
                        if (truncated) {
                            val why = if (!lastEventParsed) "mid-event truncation"
                            else "content ends truncated (tail=\"${textTail.take(40)}\")"
                            Log.w(TAG, "onClosed: opencode.ai truncated ($why, model=${params.model.modelId} events=$eventCount) — keep partial content, notify user\nlast: ${dumpLastEvents()}")
                            TraceLogger.log("SSE", "zen truncated close — keep data, notify user (events=$eventCount $why)")
                            close(OpenCodeStreamUnconfirmedException("OpenCode 输出被截断，已保留已生成内容"))
                        } else if (!hasTextContent && reasoningBuffer.isNotBlank()) {
                            // 无 content: 缓冲思考提升为正文补发 (思考链已实时显示过,
                            // 补发使正文区完整 — 与 opencode 将 reasoning_content 当正文一致)
                            val bodyText = reasoningBuffer.toString()
                            val bodyTail = if (bodyText.length > 80) bodyText.takeLast(80) else bodyText
                            if (looksTruncated(bodyTail)) {
                                Log.w(TAG, "onClosed: opencode.ai reasoning-only truncated (tail=\"${bodyTail.take(40)}\") — keep data, notify")
                                TraceLogger.log("SSE", "zen reasoning-only truncated — keep data, notify user")
                                close(OpenCodeStreamUnconfirmedException("OpenCode 输出被截断，已保留已生成内容"))
                            } else {
                                TraceLogger.log("SSE", "opencode.ai closed after complete data (events=$eventCount) — no content, promoted reasoning as body (chars=${bodyText.length} tail=\"${bodyTail.take(40)}\")")
                                trySend(
                                    MessageChunk(
                                        id = "",
                                        model = params.model.modelId,
                                        choices = listOf(
                                            UIMessageChoice(
                                                index = 0,
                                                delta = UIMessage(
                                                    role = MessageRole.ASSISTANT,
                                                    parts = listOf(UIMessagePart.Text(bodyText)),
                                                ),
                                                message = null,
                                                finishReason = "stop",
                                            )
                                        ),
                                        usage = null,
                                    )
                                ).onFailure { e -> Log.w(TAG, "onClosed: body promotion chunk dropped (${e?.message})") }
                                close()
                            }
                        } else {
                            TraceLogger.log("SSE", "opencode.ai closed after complete data (events=$eventCount) — treated as complete, no completion signal needed (tail=\"${textTail.take(40)}\" deltaKeys=\"$lastDeltaKeys\" delta=\"${lastDeltaRaw.take(260)}\")")
                            close()
                        }
                    } else {
                        Log.w(TAG, "onClosed: stream closed before completion — unexpected interruption" +
                                " (completed=${completed.get()} gotFinish=${gotFinish.get()} hasData=${hasReceivedData.get()} opencode=$isOpencode model=${params.model.modelId} events=$eventCount lastParsed=$lastEventParsed)\nlast: ${dumpLastEvents()}")
                        close(IOException("SSE 流在完成前被服务器关闭"))
                    }
                } else {
                    TraceLogger.log("SSE", "stream closed by server")
                    close()
                }
            }
            }

            currentEventSource = EventSources.createFactory(client).newEventSource(request, listener)
        }

        connect()

        awaitClose {
            Log.d(TAG, "awaitClose: cancelling eventSource")
            watchdog.cancel()
            currentEventSource?.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED)


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    messages = messages,
                    includeHistoryReasoning = providerSetting.includeHistoryReasoning,
                    supportInputModalities = params.model.inputModalities,
                )
            )

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

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

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) put("thinking_budget", level.budgetTokens)
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
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
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "aiping.cn" -> {
                        put("enable_thinking", level.isEnabled)
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com" -> {
                        // v3.9.12 (2.4.11 移植): 小米 MiMo
                        // https://mimo.mi.com/docs/zh-CN/api/chat/openai-api
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            // v3.6.49: DeepSeek 官方 reasoning_effort 只支持 high/max
                            // (对齐 DeepSeek Harness llm-deepseek: off→thinking:disabled,
                            //  high/max→reasoning_effort; low/medium 不支持直接报错)。
                            // low/medium 映射到最低档 high, xhigh 映射 max
                            val effort = when (level) {
                                ReasoningLevel.LOW -> "high"     // low 不支持 → 最低档 high
                                ReasoningLevel.MEDIUM -> "high"  // medium 不支持 → high
                                ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"    // xhigh/max → max
                                else -> level.effort             // HIGH -> "high"
                            }
                            put("reasoning_effort", effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = when (level) {
                                    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                            }
                        }
                    }

                    "opencode.ai" -> {
                        // v3.6.80: 对齐原版 RikkaHub — 原版 grok 调用正常, 不做任何特判
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", level.effort)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", if (level.effort == "none") "low" else level.effort)
                        }
                    }
                }
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

    private fun isModelAllowTemperature(model: Model): Boolean {
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = includeHistoryReasoning,
                    supportInputModalities = supportInputModalities,
                )
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        supportInputModalities: List<Modality>,
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
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", tool.toolName)
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(supportInputModalities))
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
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }
        return buildJsonObject {
            put("role", "assistant")

            // v3.6.53: reasoning_content 回传完全对齐原版 RikkaHub — hasReasoning 就回传
            // (所有模型、所有轮, 不区分 tool-call/plain)。原版超长对话缓存 99%+ 验证:
            // reasoning_content 字段时有时无会破坏 token 序列前缀稳定性。
            if (hasReasoning) {
                put("reasoning_content", reasoningPart.reasoning)
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
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
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
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
                                // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
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
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
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

    private fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
        // 只考虑文字和图片;只有模型支持图片输入时,图片才作为多模态内容回传,否则以文本占位,避免发给不支持的模型报错
        val supportsImageInput = Modality.IMAGE in supportInputModalities
        val hasImageToSend = output.any { it is UIMessagePart.Image && supportsImageInput }
        return if (!hasImageToSend) {
            JsonPrimitive(output.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                    else -> null
                }
            }.joinToString("\n"))
        } else {
            buildJsonArray {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    Log.w(TAG, "encode tool result image failed: ${part.url}", it)
                                    put("type", "text")
                                    put("text", "Error: Failed to encode image to base64")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
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
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObject?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
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

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }

    companion object {
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
