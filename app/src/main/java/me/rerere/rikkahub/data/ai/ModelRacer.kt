/* 【域 C·工具系统】 */
package me.rerere.rikkahub.data.ai

/* ───【自研】ModelRacer.kt — 原版无此文件（v5.1.4 新增）
 * 职责：给「快速 / 极致」模型池做**并发竞速** —— 同时对池内每个模型所属的
 * baseUrl 打一次轻量 GET，第一个连上并拿到响应的即为"当前响应最快"。
 *
 * ★实现取舍（v5.1.4 第二轮，CI 实证后修正）：
 *   先前版本想用 ProviderManager 发真实极小请求（maxTokens=1），但 ProviderManager
 *   是 **class 而非 object**、也没有 getProviderByType —— 从数据层静态调用它会把
 *   Provider/泛型/Koin 三样东西一起拖进来（隔离开销过大，且编译期就炸）。
 *   现在改用**纯连接探针**：只测 DNS+TCP+TLS+首包，不构造任何 Provider、不花 token、
 *   不触发工具，失败面极小。
 *
 * ★隔离契约（用户定案：这功能坏了不能影响别的）：
 *   · 本文件任何异常都在内部吞掉，只返回 null；
 *   · 调用方 resolveRoutedModel 再包一层 runCatching，最终回退「普通模型」；
 *   · modelRouting == 0 或池为空时，本文件根本不会被调用。
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

object ModelRacer {

    private const val TAG = "ModelRacer"

    /** 单个探针的硬超时（首包慢于这个就不算"快"） */
    private const val PROBE_TIMEOUT_MS = 3_000L

    /**
     * v5.3.8 ★竞速结果缓存 5s → 120s。
     *
     * 背景：v5.1.7 把 TTL 压到 5s 是为了满足「每次发送都要重新选最快的」，
     * 但代价是**每轮都会在首字前同步阻塞最多 3s**（探针与整体双 3s 超时）。
     * 现在改为「**上一轮生成结束时后台预取**（见 [prefetch]）+ 发送时命中缓存」：
     *   · 第 1 轮：可能同步竞速一次（上限降到 1.5s）
     *   · 之后每轮：用上一轮结束后的预取结果，**首字零额外等待**，且仍是最新一次测量
     * 缓存键含候选列表（池变了自然失效），所以 120s 不会拿到过期池的结果。
     */
    private const val CACHE_TTL_MS = 120_000L

    private data class CacheEntry(val key: String, val modelId: Uuid, val at: Long)

    @Volatile
    private var cache: CacheEntry? = null

    /**
     * 从候选池里挑一个**当前响应最快**的模型。
     * @return 胜出的 modelId；任何失败都返回 null（调用方回退）。
     */
    suspend fun pickFastest(
        settings: Settings,
        ids: List<Uuid>,
        timeoutMs: Long = 3_000L,
    ): Uuid? = runCatching {
        if (ids.isEmpty()) return@runCatching null
        if (ids.size == 1) return@runCatching ids.first()

        // v5.1.5 ★核验发现：竞速是"生成前"的阻塞调用，最坏情况会拖慢发送。
        // 三重收敛：探针 8s→3s、整体 10s→3s、候选上限 8（用户配 20 个也只测前 8 个）。
        val candidates = ids.take(8)
        val key = candidates.joinToString(",")
        val now = System.currentTimeMillis()
        cache?.let { entry ->
            if (entry.key == key && now - entry.at < CACHE_TTL_MS) {
                Log.d(TAG, "race cache hit -> ${entry.modelId}")
                return@runCatching entry.modelId
            }
        }

        val winnerId = race(settings, candidates, timeoutMs)
        if (winnerId != null) {
            cache = CacheEntry(key, winnerId, now)
            Log.d(TAG, "race winner=${winnerId} (candidates=${ids.size})")
        } else {
            Log.w(TAG, "race produced no winner (candidates=${ids.size})")
        }
        winnerId
    }.onFailure {
        // ★隔离：绝不向上抛
        Log.w(TAG, "pickFastest failed, fallback to caller: ${it.message}")
    }.getOrNull()

    /**
     * v5.3.8：**后台预取**（在生成结束时调用）。
     *
     * 与 [pickFastest] 走完全相同的路径，只是给足超时（不阻塞用户），
     * 目的只有一个：把结果写进 [cache]，让**下一次发送**直接命中、零等待。
     */
    suspend fun prefetch(settings: Settings, ids: List<Uuid>) {
        runCatching {
            if (ids.isEmpty()) return@runCatching
            pickFastest(settings, ids, timeoutMs = 5_000L)
        }.onFailure { Log.d(TAG, "prefetch failed: ${it.message}") }
    }

    /** 并发探针，取第一个成功者；全部失败/超时 → null */
    private suspend fun race(
        settings: Settings,
        ids: List<Uuid>,
        timeoutMs: Long,
    ): Uuid? = supervisorScope {
        val client: OkHttpClient? = runCatching {
            GlobalContext.getOrNull()?.getOrNull<OkHttpClient>()
        }.getOrNull()
        if (client == null) {
            Log.w(TAG, "no OkHttpClient available, skip race")
            return@supervisorScope null
        }

        val winner = Channel<Uuid>(ids.size)
        ids.forEach { id ->
            launch(Dispatchers.IO) {
                runCatching {
                    val model = settings.findModelById(id) ?: return@runCatching
                    val provider = model.findProvider(settings.providers, checkOverwrite = false)
                        ?: return@runCatching
                    val baseUrl = when (provider) {
                        is ProviderSetting.OpenAI -> provider.baseUrl
                        is ProviderSetting.Google -> provider.baseUrl
                        is ProviderSetting.Claude -> provider.baseUrl
                    }.trimEnd('/')
                    if (baseUrl.isBlank()) return@runCatching

                    val request = Request.Builder()
                        .url("$baseUrl/models")
                        .get()
                        .build()
                    val startedAt = System.currentTimeMillis()
                    withTimeout(PROBE_TIMEOUT_MS) {
                        // 任何响应码（含 401/404）都说明 DNS+TCP+TLS+首包已通 —— 正是要测的
                        client.newCall(request).execute().close()
                    }
                    Log.d(
                        TAG,
                        "probe ok ${System.currentTimeMillis() - startedAt}ms " +
                            "model=${model.displayName} host=$baseUrl",
                    )
                    winner.trySend(id)
                }.onFailure {
                    Log.d(TAG, "probe miss $id: ${it.message}")
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { winner.receive() }
        // 赢家已出，立刻掐掉还在跑的探针（不占连接、不拖延发送）
        coroutineContext.cancelChildren()
        result
    }
}
