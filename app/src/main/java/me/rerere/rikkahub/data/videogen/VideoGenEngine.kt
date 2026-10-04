/* 【域 A·对话核心】 — 视频生成数据面 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.data.videogen

/* ───【自研】VideoGenEngine.kt — 视频生成引擎 (v4.8.102)
 * 两个真实协议实现（提交 → 轮询 → 下载），错误消息可读：
 *   · Google Veo（Gemini API）:
 *       POST {base}/models/{model}:predictLongRunning   header x-goog-api-key
 *         body {"instances":[{"prompt","image?":{bytesBase64Encoded,mimeType}}],
 *               "parameters":{"numberOfVideos":1,"aspectRatio?","resolution?","durationSeconds?"}}
 *       GET  {base}/{operation.name}  → done=true 时
 *         response.generateVideoResponse.generatedSamples[0].video.uri → 带 key 下载
 *   · 阿里云 HappyHorse（百炼 DashScope 异步任务）:
 *       POST {base}/services/aigc/video-generation/video-synthesis
 *         headers {Authorization: Bearer, X-DashScope-Async: enable}
 *         body {"model","input":{"prompt"},"parameters":{"resolution?","ratio?","duration?","watermark"}}
 *       GET  {base}/tasks/{task_id} → output.task_status=SUCCEEDED → output.video_url（免鉴权下载）
 * ───────────────────────────────────────────────────────────────*/

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import kotlin.time.Duration.Companion.seconds

object VideoGenEngine {

    enum class Status { SUBMITTING, QUEUED, RUNNING, DOWNLOADING }

    /** 单任务总预算 (提交+轮询+下载) */
    private const val POLL_BUDGET_MS = 20 * 60 * 1000L
    private const val POLL_INTERVAL_MS = 10_000L

    /**
     * 生成并下载到 [destFile]；失败抛异常（message = 可读错误）。
     * [onStatus] 汇报过程状态（挂起环境外的回调，建议仅更新状态）。
     */
    suspend fun generate(
        client: OkHttpClient,
        endpoint: VideoGenEndpoint,
        prompt: String,
        aspectRatio: String?,
        resolution: String?,
        durationSeconds: Int?,
        watermark: Boolean,
        firstFramePath: String?,
        onStatus: (Status) -> Unit,
        destFile: File,
    ): Unit = withContext(Dispatchers.IO) {
        when (endpoint.id) {
            VideoGenEndpoint.ID_VEO -> generateVeo(
                client, endpoint, prompt, aspectRatio, resolution, durationSeconds,
                firstFramePath, onStatus, destFile,
            )
            VideoGenEndpoint.ID_HAPPYHORSE -> generateHappyHorse(
                client, endpoint, prompt, aspectRatio, resolution, durationSeconds,
                watermark, onStatus, destFile,
            )
            else -> error("未知的视频生成接口: " + endpoint.id)
        }
    }

    // ── Google Veo ──────────────────────────────────────────────

    private suspend fun generateVeo(
        client: OkHttpClient,
        endpoint: VideoGenEndpoint,
        prompt: String,
        aspectRatio: String?,
        resolution: String?,
        durationSeconds: Int?,
        firstFramePath: String?,
        onStatus: (Status) -> Unit,
        destFile: File,
    ) {
        val key = endpoint.apiKey.trim()
        if (key.isEmpty()) error("未配置 API 密钥")
        val base = endpoint.baseUrl.trimEnd('/')
        val headers = mapOf("x-goog-api-key" to key)

        onStatus(Status.SUBMITTING)
        val instance = JSONObject().apply {
            put("prompt", prompt)
            if (firstFramePath != null) {
                val bytes = File(firstFramePath).readBytes()
                put("image", JSONObject().apply {
                    put("bytesBase64Encoded", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("mimeType", "image/png")
                })
            }
        }
        val parameters = JSONObject().apply {
            put("numberOfVideos", 1)
            aspectRatio?.takeIf { it.isNotBlank() }?.let { put("aspectRatio", it) }
            resolution?.takeIf { it.isNotBlank() }?.let { put("resolution", it.lowercase()) }
            durationSeconds?.let { put("durationSeconds", it) }
        }
        val body = JSONObject().apply {
            put("instances", org.json.JSONArray().apply { put(instance) })
            put("parameters", parameters)
        }.toString()

        val submit = httpCall(
            client, "POST",
            "$base/models/${endpoint.modelId}:predictLongRunning",
            headers, body,
        )
        val opName = submit.optString("name").takeIf { it.isNotBlank() }
            ?: error("Veo 提交失败: " + submit.toString().take(300))

        onStatus(Status.RUNNING)
        val deadline = System.currentTimeMillis() + POLL_BUDGET_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) error("Veo 任务超时")
            delay(POLL_INTERVAL_MS)
            val op = httpCall(client, "GET", "$base/$opName", headers, null)
            op.optJSONObject("error")?.let { err ->
                error("Veo 失败: " + err.optString("message").ifBlank { err.toString().take(200) })
            }
            if (op.optBoolean("done")) {
                val response = op.optJSONObject("response")?.optJSONObject("generateVideoResponse")
                val uri = response
                    ?.optJSONArray("generatedSamples")
                    ?.optJSONObject(0)
                    ?.optJSONObject("video")
                    ?.optString("uri")
                if (uri.isNullOrBlank()) {
                    val reasons = response?.optString("raiMediaFilteredReasons").orEmpty()
                    error("Veo 完成但无视频产出" + if (reasons.isNotBlank()) ": $reasons" else "")
                }
                onStatus(Status.DOWNLOADING)
                downloadTo(client, uri, key, destFile)
                return
            }
        }
    }

    // ── 阿里云 HappyHorse ───────────────────────────────────────

    private suspend fun generateHappyHorse(
        client: OkHttpClient,
        endpoint: VideoGenEndpoint,
        prompt: String,
        aspectRatio: String?,
        resolution: String?,
        durationSeconds: Int?,
        watermark: Boolean,
        onStatus: (Status) -> Unit,
        destFile: File,
    ) {
        val key = endpoint.apiKey.trim()
        if (key.isEmpty()) error("未配置 API 密钥")
        val base = endpoint.baseUrl.trimEnd('/')
        val headers = mapOf(
            "Authorization" to "Bearer $key",
            "X-DashScope-Async" to "enable",
        )

        onStatus(Status.SUBMITTING)
        val parameters = JSONObject().apply {
            resolution?.takeIf { it.isNotBlank() }?.let { put("resolution", it) }
            aspectRatio?.takeIf { it.isNotBlank() }?.let { put("ratio", it) }
            durationSeconds?.let { put("duration", it) }
            put("watermark", watermark)
        }
        val body = JSONObject().apply {
            put("model", endpoint.modelId)
            put("input", JSONObject().apply { put("prompt", prompt) })
            put("parameters", parameters)
        }.toString()

        val submit = httpCall(
            client, "POST",
            "$base/services/aigc/video-generation/video-synthesis",
            headers, body,
        )
        val taskId = submit.optJSONObject("output")?.optString("task_id")?.takeIf { it.isNotBlank() }
            ?: error("HappyHorse 提交失败: " + submit.toString().take(300))

        onStatus(Status.QUEUED)
        val deadline = System.currentTimeMillis() + POLL_BUDGET_MS
        while (true) {
            if (System.currentTimeMillis() > deadline) error("HappyHorse 任务超时")
            delay(POLL_INTERVAL_MS)
            val poll = httpCall(
                client, "GET", "$base/tasks/$taskId",
                mapOf("Authorization" to "Bearer $key"), null,
            )
            val output = poll.optJSONObject("output")
            when (val status = output?.optString("task_status").orEmpty()) {
                "PENDING" -> onStatus(Status.QUEUED)
                "RUNNING" -> onStatus(Status.RUNNING)
                "SUCCEEDED" -> {
                    val url = output?.optString("video_url").orEmpty()
                    if (url.isBlank()) error("HappyHorse 任务成功但无 video_url")
                    onStatus(Status.DOWNLOADING)
                    downloadTo(client, url, null, destFile)
                    return
                }
                "FAILED", "CANCELED", "UNKNOWN" -> {
                    val detail = output?.optString("message").orEmpty()
                        .ifBlank { output?.optString("code").orEmpty() }
                    error("HappyHorse " + status + if (detail.isNotBlank()) ": $detail" else "")
                }
                else -> onStatus(Status.RUNNING)
            }
        }
    }

    // ── 公共 ────────────────────────────────────────────────────

    private suspend fun httpCall(
        client: OkHttpClient,
        method: String,
        url: String,
        headers: Map<String, String>,
        jsonBody: String?,
    ): JSONObject {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.addHeader(k, v) }
        if (jsonBody != null) {
            builder.method(method, jsonBody.toRequestBody("application/json".toMediaType()))
        } else {
            builder.method(method, null)
        }
        client.newCall(builder.build()).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val jo = JSONObject(text)
                    jo.optString("message").ifBlank {
                        jo.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: jo.optString("code").takeIf { it.isNotBlank() }
                            ?: text.take(300)
                    }
                }.getOrDefault(text.take(300))
                error("HTTP " + response.code + ": " + detail)
            }
            return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        }
    }

    private suspend fun downloadTo(
        client: OkHttpClient,
        url: String,
        googleApiKey: String?,
        destFile: File,
    ) {
        destFile.parentFile?.mkdirs()
        var response = client.newCall(
            Request.Builder().url(url)
                .apply { if (googleApiKey != null) addHeader("x-goog-api-key", googleApiKey) }
                .build(),
        ).await()
        var ok = response.isSuccessful
        if (!ok && googleApiKey != null && (response.code == 401 || response.code == 403)) {
            runCatching { response.close() }
            // 回退：部分文件的下载要求把 key 作为查询参数
            val separator = if (url.contains("?")) "&" else "?"
            response = client.newCall(Request.Builder().url(url + separator + "key=" + googleApiKey).build()).await()
            ok = response.isSuccessful
        }
        response.use { r ->
            if (!r.isSuccessful) error("下载失败 HTTP " + r.code)
            val body = r.body ?: error("下载失败: 空响应")
            body.byteStream().use { ins -> destFile.outputStream().use { outs -> ins.copyTo(outs) } }
        }
    }
}
