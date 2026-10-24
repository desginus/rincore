/* 【域 B·生成链路】 — 预热 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext

/**
 * v5.0.90 ★「预热到底对首字有没有用」的代码级答案与重构。
 *
 * 旧实现（`SandboxPrewarmer`）在输入沿触发 `officecli --version`：
 * 让 proot 启动 / rootfs patch / officecli 34MB 二进制走热。
 * **它只影响"首个工具调用"，与首字回复没有任何因果关系** —— 首字是 LLM 流式响应的
 * 第一个 token，耗时构成是 DNS+TCP+TLS → 请求体构建 → 服务端排队/TTFB。
 * 更糟的是它跑在"用户打完字立刻点发送"的那几秒里：proot 启动 1~3s + 34MB 读盘
 * 与首字请求抢 IO/CPU → 用户直觉「预热没什么用，等待时间反而越来越长」有据。
 *
 * 本类做真正能省首字的那件事：**用同一个 OkHttpClient（同一连接池）提前把
 * DNS + TCP + TLS 走完**，等真正发送时直接复用连接（省 200~1500ms）。
 *
 * 行为约束：
 * - 输入沿触发，同 host 60s 内只做一次（零成本节流）
 * - fire-and-forget，任何失败都只记 Log.d（预热永远不能让用户看见报错）
 * - 到达任何 HTTP 状态码都算成功（401/404 也说明连接已建立并进池）
 * - 跳过本地/局域网地址（连不上时要白等 connectTimeout，毫无意义）
 */
object LlmConnectionPrewarmer {
    private const val TAG = "LlmPrewarmer"
    private const val WINDOW_MS = 60_000L

    @Volatile
    private var lastAtMs = 0L

    @Volatile
    private var lastBaseUrl: String? = null

    /**
     * @param baseUrl 当前对话模型的 baseUrl（`https://api.openai.com/v1` 之类）
     */
    fun warmAsync(scope: CoroutineScope, baseUrl: String?) {
        val normalized = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (normalized.isEmpty() || !normalized.startsWith("http")) return
        if (isLocalEndpoint(normalized)) return

        val now = System.currentTimeMillis()
        if (now - lastAtMs < WINDOW_MS && lastBaseUrl == normalized) return
        lastAtMs = now
        lastBaseUrl = normalized

        scope.launch(Dispatchers.IO) {
            runCatching {
                // 必须复用 Koin 单例 client —— 新建 client 自带独立连接池，预热等于没做
                val client = runCatching { GlobalContext.get().get<OkHttpClient>() }.getOrNull()
                    ?: return@runCatching
                val started = SystemClock.elapsedRealtime()
                // 任意路径都可以：连接在任何响应码下都已建立并留在池里
                val request = Request.Builder()
                    .url("$normalized/models")
                    .get()
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { it.close() }
                Log.d(TAG, "llm connection warm ${SystemClock.elapsedRealtime() - started}ms -> $normalized")
            }.onFailure {
                Log.d(TAG, "llm warm skipped: ${it.message} (无害，发送时再握手)")
            }
        }
    }

    /** 本地/局域网端点预热无收益（且会白等 connectTimeout）。 */
    private fun isLocalEndpoint(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return true
        return host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
    }
}
