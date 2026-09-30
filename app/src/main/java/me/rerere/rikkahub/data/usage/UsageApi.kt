/* 【域 I·数据存储】 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.usage

/* ───【自研】UsageApi.kt — OpenCode 用量查询 (v3.8.0)
 * 接口: GET https://opencode.ai/zen/go/v1/usage (Authorization: Bearer <key>)
 * 返回: { usage: { rolling/weekly/monthly: { status, percent, resetsAt } } }
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UsageApi {
    private const val TAG = "UsageApi"
    private const val USAGE_URL = "https://opencode.ai/zen/go/v1/usage"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    data class WindowUsage(
        val percent: Int?,
        val resetsAt: String?,
    )

    data class UsageResult(
        val rolling: WindowUsage,
        val weekly: WindowUsage,
        val monthly: WindowUsage,
    )

    /** v4.8.78: 详细结果 — 携带 HTTP 状态码供空密钥分类
     *  (服务端可达但拒绝 (4xx) = 无套餐; 网络异常/5xx = 真失败) */
    data class UsageOutcome(
        val result: UsageResult?,
        val httpCode: Int?,
        val error: String?,
    )

    suspend fun fetchUsage(apiKey: String): UsageResult? = fetchUsageDetailed(apiKey).result

    suspend fun fetchUsageDetailed(apiKey: String): UsageOutcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext UsageOutcome(null, null, "密钥为空")
        runCatching {
            val request = Request.Builder()
                .url(USAGE_URL)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "usage http ${resp.code}")
                    return@use UsageOutcome(null, resp.code, "HTTP ${resp.code}")
                }
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@use UsageOutcome(null, resp.code, "响应为空")
                val usage = runCatching { JSONObject(body).optJSONObject("usage") }.getOrNull()
                if (usage == null) return@use UsageOutcome(null, resp.code, "响应缺少 usage 字段")
                fun parseWindow(key: String): WindowUsage {
                    val w = usage.optJSONObject(key)
                        ?: return WindowUsage(null, null)
                    return WindowUsage(
                        percent = w.optInt("percent", -1).takeIf { it >= 0 },
                        resetsAt = w.optString("resetsAt").takeIf { it.isNotBlank() },
                    )
                }
                return@use UsageOutcome(
                    result = UsageResult(
                        rolling = parseWindow("rolling"),
                        weekly = parseWindow("weekly"),
                        monthly = parseWindow("monthly"),
                    ),
                    httpCode = resp.code,
                    error = null,
                )
            }
        }.getOrElse { e ->
            Log.e(TAG, "fetchUsage failed: ${e.message}")
            UsageOutcome(null, null, "${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
        }
    }
}
