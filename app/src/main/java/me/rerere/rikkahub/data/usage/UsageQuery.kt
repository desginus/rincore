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
import me.rerere.rikkahub.data.datastore.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object UsageQuery {
    private const val TAG = "UsageQuery"

    /** 缓存新鲜窗口: 60s 内进入页面不重复查询 (下拉刷新始终强制) */
    const val FRESH_MS = 60_000L

    private data class Entry(val state: KeyQueryState, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** 密钥族分流: user_ = Command Code; 其余 (含 OC 新格式 oc-sk-) = OpenCode */
    fun isCcKey(key: String) = key.startsWith("user_", ignoreCase = true)

    /** 内存快照 (进入页面即显; 查询失败时保留旧数据用) */
    fun snapshot(): Map<String, KeyQueryState> =
        cache.entries.associate { it.key to it.value.state }

    fun hasFresh(key: String): Boolean =
        cache[key]?.let { System.currentTimeMillis() - it.at < FRESH_MS } == true

    /** v4.8.96: 抽屉打开瞬间预热 — 后台先查一轮, 用户点进页面时命中新鲜窗口即秒开。 */
    suspend fun prefetch(settings: Settings) {
        val keys = (listOf(settings.opencodeApiKey) + settings.opencodeApiKeys)
            .filter { it.isNotBlank() }
            .distinct()
        if (keys.isEmpty() || keys.all { hasFresh(it) }) return
        fetchAll(keys)
    }

    /** 并行查询全部密钥; 每个密钥独立三态 (Ok / NoSubscription / Failed) */
    suspend fun fetchAll(keys: List<String>): Map<String, KeyQueryState> = coroutineScope {
        keys.distinct().map { k ->
            async(Dispatchers.IO) {
                val state = runCatching { queryOne(k) }.getOrElse { e ->
                    Log.w(TAG, "fetch ${k.take(6)}… failed: ${e.message}")
                    KeyQueryState.Failed(e.message)
                }
                cache[k] = Entry(state, System.currentTimeMillis())
                k to state
            }
        }.awaitAll().toMap()
    }

    /**
     * v4.8.78 空密钥分类 (用户定版): 账户真实但当前无套餐 —
     *  CC: 无信息 (各窗口/套餐字段全空) 或 HTTP 4xx → NoSubscription;
     *  OC: 服务端可达但拒绝 (HTTP 4xx, "查询失败"表象) → NoSubscription;
     *  网络异常/5xx → Failed (保留"查询失败"展示, 不误折叠)。
     */
    private suspend fun queryOne(key: String): KeyQueryState {
        return if (isCcKey(key)) {
            val outcome = CommandCodeUsageApi.fetchUsage(key)
            when {
                outcome.result != null && outcome.result.isEffectivelyEmpty() -> KeyQueryState.NoSubscription
                outcome.result != null -> KeyQueryState.Ok(outcome.result.toOcShape())
                outcome.error?.startsWith("HTTP 4") == true -> KeyQueryState.NoSubscription
                else -> KeyQueryState.Failed(outcome.error)
            }
        } else {
            val outcome = UsageApi.fetchUsageDetailed(key)
            when {
                outcome.result != null -> KeyQueryState.Ok(outcome.result)
                outcome.httpCode != null && outcome.httpCode in 400..499 -> KeyQueryState.NoSubscription
                else -> KeyQueryState.Failed(outcome.error)
            }
        }
    }

    /**
     * CC 空密钥判据 (v4.8.79 修正 — 按用户实见特征): 账户真实但无套餐时, 用户能看到的
     * 唯一特征 = 重置倒计时"未知" (任一窗口都没有有效重置时间), 且各项额度全为 0。
     * 旧判据要求字段"全缺" (fiveHour/weekly==null) — 实测空套餐响应仍带窗口对象,
     * 从未命中 (用户: "套餐还是被展示")。反例保护: 按量付费/有余额账户 (credits>0)
     * 或任一窗口有重置时间 → 不折叠。
     */
    private fun CommandCodeUsageApi.CommandCodeUsageResult.isEffectivelyEmpty(): Boolean {
        val noResets = fiveHour?.resetAtMs == null && weekly?.resetAtMs == null && currentPeriodEnd == null
        val noCredits = monthlyRemaining <= 0.0 && purchasedCredits <= 0.0 && freeCredits <= 0.0
        return noResets && noCredits
    }
}

/** 单密钥查询三态 (v4.8.78) */
sealed class KeyQueryState {
    data class Ok(val data: UsageApi.UsageResult) : KeyQueryState()

    /** 空密钥 — 账户真实但当前无套餐; UI 折叠展示, 不出明细卡 */
    data object NoSubscription : KeyQueryState()

    data class Failed(val reason: String?) : KeyQueryState()
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
