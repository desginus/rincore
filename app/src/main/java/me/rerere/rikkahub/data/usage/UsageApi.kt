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

    suspend fun fetchUsage(apiKey: String): UsageResult? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        runCatching {
            val request = Request.Builder()
                .url(USAGE_URL)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "usage http ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                val usage = JSONObject(body).optJSONObject("usage") ?: return@use null
                fun parseWindow(key: String): WindowUsage {
                    val w = usage.optJSONObject(key)
                        ?: return WindowUsage(null, null)
                    return WindowUsage(
                        percent = w.optInt("percent", -1).takeIf { it >= 0 },
                        resetsAt = w.optString("resetsAt").takeIf { it.isNotBlank() },
                    )
                }
                UsageResult(
                    rolling = parseWindow("rolling"),
                    weekly = parseWindow("weekly"),
                    monthly = parseWindow("monthly"),
                )
            }
        }.getOrElse { e ->
            Log.e(TAG, "fetchUsage failed: ${e.message}")
            null
        }
    }
}
