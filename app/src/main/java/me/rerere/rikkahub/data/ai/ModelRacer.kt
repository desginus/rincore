/* 【域 C·工具系统】 */
package me.rerere.rikkahub.data.ai

/* ───【自研】ModelRacer.kt — 原版无此文件（v5.1.4 新增）
 * 职责：给「快速 / 极致」模型池做**并发竞速** —— 同时对池内每个模型发一次
 * 极小请求（maxTokens=1），第一个成功返回的即为"当前响应最快"的模型。
 *
 * ★隔离契约（用户定案：这功能坏了不能影响别的）：
 *   · 本文件任何异常都在内部吞掉，只返回 null；
 *   · 调用方（SettingsExt.resolveRoutedModel）再包一层 runCatching，
 *     最终回退到「普通模型」——**最坏结果等于没启用这个功能**；
 *   · `modelRouting == 0` 或池为空时，本文件根本不会被调用。
 * ───────────────────────────────────────────────────────────────*/
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

object ModelRacer {

    private const val TAG = "ModelRacer"

    /** 单个探针的硬超时（模型首包慢于这个就不算"快"） */
    private const val PROBE_TIMEOUT_MS = 8_000L

    /** 竞速结果缓存：同一组候选在 60s 内复用上次赢家，避免每轮都烧 N 个请求 */
    private const val CACHE_TTL_MS = 60_000L

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
        timeoutMs: Long = 10_000L,
    ): Uuid? = runCatching {
        if (ids.isEmpty()) return@runCatching null
        if (ids.size == 1) return@runCatching ids.first()

        val key = ids.joinToString(",")
        val now = System.currentTimeMillis()
        cache?.let { entry ->
            if (entry.key == key && now - entry.at < CACHE_TTL_MS) {
                Log.d(TAG, "race cache hit -> ${entry.modelId}")
                return@runCatching entry.modelId
            }
        }

        val winnerId = race(settings, ids, timeoutMs)
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

    /** 并发探针，取第一个成功者；全部失败/超时 → null */
    private suspend fun race(
        settings: Settings,
        ids: List<Uuid>,
        timeoutMs: Long,
    ): Uuid? = supervisorScope {
        val winner = Channel<Uuid>(ids.size)
        ids.forEach { id ->
            launch(Dispatchers.IO) {
                runCatching {
                    val model = settings.findModelById(id) ?: return@runCatching
                    val provider = model.findProvider(settings.providers, checkOverwrite = false)
                        ?: return@runCatching
                    withTimeout(PROBE_TIMEOUT_MS) {
                        // v5.1.4：ProviderManager 没有 getProviderByType（CI 实证 Unresolved），
                        // 按其注册名分发；Provider<*> 星投影用显式转型后传 ProviderSetting。
                        @Suppress("UNCHECKED_CAST")
                        val impl = ProviderManager.getProvider(
                            when (provider) {
                                is ProviderSetting.OpenAI -> "openai"
                                is ProviderSetting.Google -> "google"
                                is ProviderSetting.Claude -> "claude"
                            }
                        ) as Provider<ProviderSetting>
                        impl.generateText(
                            provider,
                            listOf(
                                UIMessage(
                                    role = MessageRole.USER,
                                    parts = listOf(UIMessagePart.Text("1")),
                                )
                            ),
                            // 极小请求：1 个 token，只为测"谁先回来"
                            TextGenerationParams(model = model, maxTokens = 1),
                        )
                    }
                    winner.trySend(id)
                }.onFailure {
                    Log.d(TAG, "probe miss ${id}: ${it.message}")
                }
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { winner.receive() }
        // 赢家已出，立刻掐掉还在跑的探针（不占连接、不拖延发送）
        coroutineContext.cancelChildren()
        result
    }
}
