/* 【域 I·数据存储】 — 用量查询统一引擎 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.usage

/* ───【自研】UsageQuery.kt — v4.8.59 用量查询引擎 (整页重写配套)
 * 职责: ① 密钥族分流 (user_ = Command Code, 其余 = OpenCode);
 *       ② 并行查询 — 原实现逐密钥串行 (associateWith), N 张卡 ≈ N 倍等待,
 *          是"查询太慢"的直接根因; 现以 async 并发, 总耗时 ≈ 单次最慢;
 *       ③ 内存缓存 + 新鲜窗口 — 进入页面缓存直出, 60s 内不重复打网络;
 *       ④ CC 结果 → OC 形状统一 (v3.22.0 数据链统一延续, 下游零差别)。
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object UsageQuery {
    private const val TAG = "UsageQuery"

    /** 缓存新鲜窗口: 60s 内进入页面不重复查询 (下拉刷新始终强制) */
    const val FRESH_MS = 60_000L

    private data class Entry(val result: UsageApi.UsageResult, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun isCcKey(key: String) = key.startsWith("user_", ignoreCase = true)

    /** 内存快照 (进入页面即显; 查询失败时保留旧数据用) */
    fun snapshot(): Map<String, UsageApi.UsageResult> =
        cache.entries.associate { it.key to it.value.result }

    fun hasFresh(key: String): Boolean =
        cache[key]?.let { System.currentTimeMillis() - it.at < FRESH_MS } == true

    /** 并行查询全部密钥; 每个密钥独立成败 (失败=null, 不影响其他) */
    suspend fun fetchAll(keys: List<String>): Map<String, UsageApi.UsageResult?> = coroutineScope {
        keys.distinct().map { k ->
            async(Dispatchers.IO) {
                val r = runCatching {
                    if (isCcKey(k)) {
                        CommandCodeUsageApi.fetchUsage(k)?.result?.toOcShape()
                    } else {
                        UsageApi.fetchUsage(k)
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "fetch ${k.take(6)}… failed: ${e.message}")
                    null
                }
                if (r != null) cache[k] = Entry(r, System.currentTimeMillis())
                k to r
            }
        }.awaitAll().toMap()
    }
}

/** v3.22.0 数据链统一 (迁自 UsagePage): CC 结果按 OC 形状解析, 下游零差别 */
internal fun CommandCodeUsageApi.CommandCodeUsageResult.toOcShape(): UsageApi.UsageResult {
    val iso = { ms: Long? -> ms?.let { java.time.Instant.ofEpochMilli(it).toString() } }
    return UsageApi.UsageResult(
        rolling = UsageApi.WindowUsage(
            percent = fiveHour?.percent,
            resetsAt = iso(fiveHour?.resetAtMs),
        ),
        weekly = UsageApi.WindowUsage(
            percent = weekly?.percent,
            resetsAt = iso(weekly?.resetAtMs),
        ),
        monthly = UsageApi.WindowUsage(
            percent = monthlyUsedPercent,
            resetsAt = currentPeriodEnd,
        ),
    )
}
