package me.rerere.rikkahub.data.usage

/* ───【自研】CommandCodeUsageApi.kt — Command Code 用量查询 (v3.12.2)
 * 接口 (并行):
 *   GET https://api.commandcode.ai/alpha/billing/credits
 *   GET https://api.commandcode.ai/alpha/billing/subscriptions
 * 认证: Authorization: Bearer <user_... key>
 * 语义 (实测/CLI 逆向):
 *   monthlyCredits = 当月剩余 (非已用, 不含总额)
 *   resetAt = 毫秒时间戳, 0/缺省 = 无重置 (不可渲染 1970)
 *   exceeded: true = 对应窗口打满; limited:false = 不受滚动窗口约束
 *   加油包 purchasedCredits 永不过期不占月度池
 * 月度总额: 接口不报, 走 GOAT 目录三重校验 (planId + 双 cap 锚点 + 剩余<=总额),
 *   失败静默降级为只报剩余 (monthlyTotal = null)
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object CommandCodeUsageApi {
    private const val TAG = "CommandCodeUsageApi"
    private const val BASE = "https://api.commandcode.ai"

    // v1.0 方案 §5.1: 套餐目录 — 官方 pricing-limits 页 (2026-09) 全套餐抄录,
    // cap 作锚点防调价失真
    private val PLAN_CATALOG = mapOf(
        "go" to PlanCatalog(fiveHour = 3.0, weekly = 6.0, monthly = 10.0),
        "goat" to PlanCatalog(fiveHour = 14.0, weekly = 35.0, monthly = 70.0),
        "pro" to PlanCatalog(fiveHour = 16.0, weekly = 40.0, monthly = 80.0),
        "provider" to PlanCatalog(fiveHour = 0.0, weekly = 0.0, monthly = 0.0), // 按量付费无窗口
        "max10" to PlanCatalog(fiveHour = 45.0, weekly = 90.0, monthly = 150.0),
        "max20" to PlanCatalog(fiveHour = 90.0, weekly = 180.0, monthly = 300.0),
        "teampro" to PlanCatalog(fiveHour = 12.0, weekly = 24.0, monthly = 40.0),
    )

    data class PlanCatalog(val fiveHour: Double, val weekly: Double, val monthly: Double)

    data class WindowInfo(
        val used: Double?,
        val cap: Double?,
        val resetAtMs: Long?,   // null/0 = 无重置
        val exceeded: Boolean = false,
    ) {
        val remaining: Double? get() = if (used != null && cap != null) cap - used else null
        val percent: Int? get() = if (used != null && cap != null && cap > 0) {
            (used / cap * 100).toInt().coerceIn(0, 100)
        } else null
    }

    data class CommandCodeUsageResult(
        val fiveHour: WindowInfo?,
        val weekly: WindowInfo?,
        val monthlyRemaining: Double,       // monthlyCredits (当月剩余)
        val purchasedCredits: Double,
        val freeCredits: Double,
        val belowThreshold: Boolean,
        val limited: Boolean,               // false = 不受滚动窗口约束
        val planId: String?,
        val currentPeriodEnd: String?,      // ISO, 月度重置时间
        val monthlyTotal: Double?,          // GOAT 三重校验通过才有, null = 只报剩余
        val catalogMatched: Boolean,
    ) {
        val monthlyUsedPercent: Int?
            get() = if (monthlyTotal != null && monthlyTotal > 0) {
                ((monthlyTotal - monthlyRemaining) / monthlyTotal * 100).toInt().coerceIn(0, 100)
            } else null
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 查询失败原因 (UI 直接展示; null = 无错误) */
    data class FetchOutcome(val result: CommandCodeUsageResult?, val error: String?)

    private fun getJson(url: String, apiKey: String): Pair<JSONObject?, String?> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("User-Agent", "commandcode-cli/1.40.1")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { resp ->
                val bodyText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 透出服务端错误 message (Hono 结构化错误含 message 字段)
                    val serverMsg = runCatching {
                        JSONObject(bodyText).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    Log.w(TAG, "http ${resp.code} $url ${bodyText.take(200)}")
                    return null to "HTTP ${resp.code}${serverMsg?.let { ": $it" } ?: ""}"
                }
                val json = runCatching { JSONObject(bodyText) }.getOrNull()
                if (json == null) {
                    Log.w(TAG, "bad json $url ${bodyText.take(200)}")
                    return null to "响应非 JSON: ${bodyText.take(80)}"
                }
                return json to null
            }
        }.getOrElse { e ->
            Log.e(TAG, "request failed $url: ${e.javaClass.simpleName}: ${e.message}")
            return null to "${e.javaClass.simpleName}: ${e.message ?: "网络异常"}"
        }
    }

    private fun parseWindow(limits: JSONObject, key: String): WindowInfo? {
        val w = limits.optJSONObject(key) ?: return null
        val reset = w.optLong("resetAt", 0L)
        return WindowInfo(
            used = if (w.has("used")) w.optDouble("used") else null,
            cap = if (w.has("cap")) w.optDouble("cap") else null,
            resetAtMs = if (reset > 0) reset else null,
            exceeded = limits.optBoolean("exceeded", false) &&
                limits.optString("exceeded").length > 4, // 占位, 语义见 credits 顶层
        )
    }

    suspend fun fetchUsage(apiKey: String): FetchOutcome = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext FetchOutcome(null, "未配置 API Key")
        runCatching {
        coroutineScope {
            // 方案 §3.1: 两接口并行
            val creditsJob = async { getJson("$BASE/alpha/billing/credits", apiKey) }
            val subsJob = async { getJson("$BASE/alpha/billing/subscriptions", apiKey) }
            val (creditsRoot, creditsErr) = creditsJob.await()
            val (subsRoot, subsErr) = subsJob.await()
            // credits 失败 = 整体失败 (核心数据); subs 失败仅降级 (重置时间缺省)
            if (creditsRoot == null) {
                val e = creditsErr ?: "未知错误"
                Log.w(TAG, "fetchUsage fail: $e")
                return@coroutineScope FetchOutcome(null, e)
            }

                val credits = creditsRoot.optJSONObject("credits")
                val limits = creditsRoot.optJSONObject("windowLimits")
                val monthlyCredits = credits?.optDouble("monthlyCredits", 0.0) ?: 0.0
                val purchased = credits?.optDouble("purchasedCredits", 0.0) ?: 0.0
                val free = credits?.optDouble("freeCredits", 0.0) ?: 0.0
                val belowThreshold = credits?.optBoolean("belowThreshold", false) ?: false
                val limited = limits?.optBoolean("limited", false) ?: false
                val exceeded = limits?.optBoolean("exceeded", false) ?: false

                val fiveHour = limits?.optJSONObject("fiveHour")?.let { w ->
                    val reset = w.optLong("resetAt", 0L)
                    WindowInfo(
                        used = if (w.has("used")) w.optDouble("used") else null,
                        cap = if (w.has("cap")) w.optDouble("cap") else null,
                        resetAtMs = if (reset > 0) reset else null,
                        exceeded = exceeded,
                    )
                }
                val weekly = limits?.optJSONObject("weekly")?.let { w ->
                    val reset = w.optLong("resetAt", 0L)
                    WindowInfo(
                        used = if (w.has("used")) w.optDouble("used") else null,
                        cap = if (w.has("cap")) w.optDouble("cap") else null,
                        resetAtMs = if (reset > 0) reset else null,
                        exceeded = exceeded,
                    )
                }

                val planId = subsRoot?.optJSONObject("data")?.optString("planId")?.takeIf { it.isNotBlank() }
                val currentPeriodEnd = subsRoot?.optJSONObject("data")
                    ?.optString("currentPeriodEnd")?.takeIf { it.isNotBlank() }

                // §5.1 三重校验: planId 命中目录 + 双 cap 锚点一致 + 剩余<=总额
                val cat = PLAN_CATALOG[planId?.lowercase()]
                var monthlyTotal: Double? = null
                var matched = false
                if (cat != null && fiveHour?.cap != null && weekly?.cap != null) {
                    val capsOk = kotlin.math.abs(fiveHour.cap!! - cat.fiveHour) < 1e-6 &&
                        kotlin.math.abs(weekly.cap!! - cat.weekly) < 1e-6
                    if (capsOk && monthlyCredits <= cat.monthly) {
                        monthlyTotal = cat.monthly
                        matched = true
                    }
                }

                CommandCodeUsageResult(
                    fiveHour = fiveHour,
                    weekly = weekly,
                    monthlyRemaining = monthlyCredits,
                    purchasedCredits = purchased,
                    freeCredits = free,
                    belowThreshold = belowThreshold,
                    limited = limited,
                    planId = planId,
                    currentPeriodEnd = currentPeriodEnd,
                    monthlyTotal = monthlyTotal,
                    catalogMatched = matched,
                ).let { FetchOutcome(it, null) }
            }
        }.getOrElse { e ->
            Log.e(TAG, "parse failed: ${e.javaClass.simpleName}: ${e.message}")
            FetchOutcome(null, "解析异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 用量颜色 ARGB: 0%→绿, 50%→黄, 75%→橙, 100%→红 (分段线性插值)。
     * 额度卡 (用量) 从绿渐变到红。
     */
    fun usageColorArgb(percent: Int): Int {
        val p = percent.coerceIn(0, 100).toFloat()
        val green = floatArrayOf(0f, 0.77f, 0.37f)   // #22C55E
        val yellow = floatArrayOf(0.92f, 0.71f, 0.03f) // #EAB308
        val orange = floatArrayOf(0.98f, 0.45f, 0.09f) // #F97316
        val red = floatArrayOf(0.86f, 0.15f, 0.15f)  // #DC2626
        val c = when {
            p <= 50f -> lerp(green, yellow, p / 50f)
            p <= 75f -> lerp(yellow, orange, (p - 50f) / 25f)
            else -> lerp(orange, red, (p - 75f) / 25f)
        }
        return argb(c)
    }

    /**
     * 重置时间颜色 ARGB: 等待中(刚用完)→红, 临近重置→绿 (额度即将恢复)。
     * progress = 1 - remaining/window (0=刚重置过, 1=即将重置), 颜色红→绿反向插值。
     */
    fun resetColorArgb(remainingMs: Long?, windowMs: Long): Int {
        val progress = if (remainingMs == null || remainingMs <= 0) 1f
        else (1f - remainingMs.toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
        val green = floatArrayOf(0f, 0.77f, 0.37f)
        val yellow = floatArrayOf(0.92f, 0.71f, 0.03f)
        val red = floatArrayOf(0.86f, 0.15f, 0.15f)
        val c = if (progress < 0.5f) {
            lerp(red, yellow, progress / 0.5f)
        } else {
            lerp(yellow, green, (progress - 0.5f) / 0.5f)
        }
        return argb(c)
    }

    private fun argb(c: FloatArray): Int =
        (0xFF shl 24) or (c[0].times(255).toInt().coerceIn(0, 255) shl 16) or
            (c[1].times(255).toInt().coerceIn(0, 255) shl 8) or
            c[2].times(255).toInt().coerceIn(0, 255)

    private fun lerp(a: FloatArray, b: FloatArray, t: Float): FloatArray =
        FloatArray(3) { i -> a[i] + (b[i] - a[i]) * t }

    /** 倒计时 "Xh Ym 后重置" (resetAt 为毫秒, null 返回 null) */
    fun countdownText(resetAtMs: Long?): String? {
        if (resetAtMs == null || resetAtMs <= 0) return null
        val diff = resetAtMs - System.currentTimeMillis()
        if (diff <= 0) return "即将重置"
        val h = diff / 3_600_000
        val m = (diff % 3_600_000) / 60_000
        return if (h > 0) "${h}h ${m}m 后重置" else "${m}m 后重置"
    }

    /** 月度重置日 "10月1日重置" */
    fun periodEndText(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val d = Instant.parse(iso).atZone(ZoneId.systemDefault())
            "${d.monthValue}月${d.dayOfMonth}日重置"
        }.getOrNull()
    }
}
