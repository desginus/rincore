package me.rerere.ai.provider.providers


/* ───【自研】OpenAIProvider.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.delay
import me.rerere.ai.provider.VideoGenerationParams
import me.rerere.ai.ui.VideoGenerationItem
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.ProxyRoute
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

// v4.8.103: 视频生成轮询总预算 (提交+轮询+下载)
private const val VIDEO_GENERATION_POLL_BUDGET_MS = 20L * 60 * 1000

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null,
    // v3.9.15: 按模型代理路由
    private val proxyRoute: ProxyRoute? = null,
    // v3.10.5: OpenCode 网关独立长保活池 (opencode.ai 直连), null = 回落默认池
    private val opencodeClient: OkHttpClient? = null,
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    // v3.10.5: 请求侧按 host 选择连接池 — opencode.ai 走长保活池 (TTFT 专项)
    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette, proxyRoute = proxyRoute, opencodeClient = opencodeClient)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette, proxyRoute = proxyRoute, opencodeClient = opencodeClient)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .headers(providerSetting.mergeCustomHeaders())
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .headers(providerSetting.mergeCustomHeaders())
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    // 4.2.0: 接口切换 StreamChunk (原版 2.5.x 形态) — 内层 API 已完成桥接
    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult {
        // 协议兜底: 严格端点 (DeepSeek V4 Flash 等) 要求首条消息为 system —
        // 子请求 (标题生成/建议/背景文本/工具分类) 常直接传 user 消息,
        // 缺 system 前缀 → 服务端报 'Required SETTINGS preface not received'
        // 主请求已由 MessageProtocol.enforce 保证, 此处为全请求统一兜底 (幂等)
        // v3.10.3: 兜底 system 改最小非空前言 — 空 content 的 system 消息
        // 会被 Opencode 网关拒 (HTTP 500), 见 MessageProtocol.FALLBACK_SYSTEM_PROMPT
        val normalized = if (messages.firstOrNull()?.role != MessageRole.SYSTEM) {
            listOf(UIMessage.system("You are a helpful assistant.")) + messages
        } else {
            messages
        }
        // 内层 API 已返回 TextGenerationResult (桥接在内层完成)
        return if (providerSetting.useResponseApi) {
            responseAPI.generateText(
                providerSetting = providerSetting,
                messages = normalized,
                params = params
            )
        } else {
            chatCompletionsAPI.generateText(
                providerSetting = providerSetting,
                messages = normalized,
                params = params
            )
        }
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    /* ── v4.8.103: 视频生成 — 阿里云 HappyHorse (百炼 DashScope 异步任务, 对照官方 API 参考) ──
     * 提交 POST {baseUrl}/services/aigc/video-generation/video-synthesis
     *   headers {Authorization: Bearer, X-DashScope-Async: enable}
     *   body {model, input:{prompt}, parameters:{resolution?,ratio?,duration?,watermark?}}
     * 轮询 GET {baseUrl}/tasks/{task_id} → output.task_status=SUCCEEDED → output.video_url 下载 */
    override suspend fun generateVideo(
        providerSetting: ProviderSetting,
        params: VideoGenerationParams
    ): Flow<VideoGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) { "Expected OpenAI provider setting" }
        val baseUrl = providerSetting.baseUrl.trimEnd('/')
        require(baseUrl.contains("aliyuncs.com")) {
            "当前视频生成适配: Google Veo (Google 提供方) 与阿里云 HappyHorse (接口地址含 aliyuncs.com 的提供方)"
        }
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val submitBody = buildJsonObject {
            put("model", params.model.modelId)
            put("input", buildJsonObject {
                put("prompt", params.prompt)
            })
            put("parameters", buildJsonObject {
                params.resolution.takeIf { it.isNotBlank() }?.let { put("resolution", it) }
                params.aspectRatio.takeIf { it.isNotBlank() }?.let { put("ratio", it) }
                params.durationSeconds?.let { put("duration", it) }
                params.watermark?.let { put("watermark", it) }
            })
        }.mergeCustomBody(params.customBody)
        val submitUrl = "$baseUrl/services/aigc/video-generation/video-synthesis"
        val submitResponse = client.newCall(
            Request.Builder()
                .url(submitUrl)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("X-DashScope-Async", "enable")
                .addHeader("Content-Type", "application/json")
                .post(json.encodeToString(submitBody).toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        ).await()
        val submitText = submitResponse.body.string()
        if (!submitResponse.isSuccessful) {
            error("提交视频任务失败 [$submitUrl]: ${submitResponse.code} $submitText")
        }
        val submitJson = json.parseToJsonElement(submitText).jsonObject
        submitJson["code"]?.jsonPrimitive?.contentOrNull?.let { code ->
            error("HappyHorse 提交失败: $code " + (submitJson["message"]?.jsonPrimitive?.contentOrNull ?: submitText.take(200)))
        }
        val taskId = submitJson["output"]?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull
            ?: error("提交响应缺少 task_id: $submitText")
        Log.i(TAG, "generateVideo(happyhorse): task=$taskId")

        val deadline = System.currentTimeMillis() + VIDEO_GENERATION_POLL_BUDGET_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) error("HappyHorse 任务超时 (>20 分钟)")
            delay(10_000)
            val pollUrl = "$baseUrl/tasks/$taskId"
            val pollResponse = client.newCall(
                Request.Builder()
                    .url(pollUrl)
                    .addHeader("Authorization", "Bearer $key")
                    .configureReferHeaders(providerSetting.baseUrl)
                    .get()
                    .build()
            ).await()
            val pollText = pollResponse.body.string()
            if (!pollResponse.isSuccessful) {
                error("轮询视频任务失败 [$pollUrl]: ${pollResponse.code} $pollText")
            }
            val output = json.parseToJsonElement(pollText).jsonObject["output"]?.jsonObject
            when (val status = output?.get("task_status")?.jsonPrimitive?.contentOrNull) {
                "SUCCEEDED" -> {
                    val videoUrl = output["video_url"]?.jsonPrimitive?.contentOrNull
                        ?: error("任务成功但无 video_url")
                    val download = client.newCall(
                        Request.Builder().url(videoUrl).get().build()
                    ).await()
                    if (!download.isSuccessful) error("视频下载失败: ${download.code}")
                    val tmp = File.createTempFile("rincore_hh_", ".mp4")
                    tmp.writeBytes(download.body.bytes())
                    emit(VideoGenerationItem(file = tmp, mimeType = "video/mp4"))
                    return@flow
                }
                "FAILED", "CANCELED", "UNKNOWN" -> {
                    val detail = output["message"]?.jsonPrimitive?.contentOrNull
                        ?: output["code"]?.jsonPrimitive?.contentOrNull ?: ""
                    error("HappyHorse 任务 $status: $detail")
                }
                else -> {} // PENDING / RUNNING
            }
        }
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                if (params.size.isNotBlank()) {
                    put("size", params.size)
                }
            }
                .mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "generateImage: $requestBody")

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
