/* 【域 B·生成链路】 — 预热 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext
import java.net.InetAddress

/**
 * v5.0.91 ★预热强化（用户要求：做到实处、优先级更高、更有效、更激进）。
 *
 * ===== 上一版（v5.0.90）的四个漏洞 =====
 *  1. **只建 1 条连接** —— DI 的 OkHttpClient 明确 `protocols(listOf(HTTP_1_1))`
 *     （曾有 h2 PROTOCOL_ERROR 历史），HTTP/1.1 一条连接同时只能跑一个请求。
 *     双对话并发 / 聊天 + 工具并发时，其余请求仍要现握手。
 *  2. **没有 DNS 预热** —— OkHttp 走系统解析器，首次解析 20~300ms（移动网更糟）。
 *  3. **无法证明到底省了多少** —— 只有"总耗时"，既看不出钱花在哪，也验证不了复用。
 *  4. **只在输入沿触发一次** —— 进对话直接发、切模型、60s 后二次发送都不预热。
 *
 * ===== 本版（每条都能被日志证明）=====
 *  A. **并发建 2 条连接**（HTTP/1.1：一连接一请求；h2 服务端下第二条自然复用，零成本）。
 *  B. **系统 DNS 预热**：`InetAddress.getAllByName` 先解析一次，结果留在解析器缓存。
 *  C. **连接池计数自证**：预热前后 `connectionPool.connectionCount()` 必须增加 →
 *     证明连接真的进了**同一个池**（不是另建的 client 池）。
 *  D. **复用自证**：紧接着再发一次同请求，若耗时远小于建连耗时且池计数不再增加 →
 *     证明确实复用了预热连接，也就是**发送时不会再握手**。
 *  E. **触发点 4 个**：进对话（模型就绪）、切模型、开始输入、点发送；30s 节流合并。
 *  F. **失败重试 1 次**（300ms 后）。
 *
 * ===== 刻意不做 =====
 *  - **不带 API key**：DI 的 client 挂了 `HttpLoggingInterceptor`，DEBUG 下会打印请求头，
 *    带 key 等于把密钥写进 logcat；鉴权发生在连接建立**之后**，不影响预热收益。
 *  - **不新建 OkHttpClient**：必须 `client.newBuilder()` —— Builder(client) 复制
 *    `connectionPool` / `dispatcher` **引用**，连接才进同一个池；从零 Builder() 是另一个池。
 *  - 不发 POST `/chat/completions`：会产生真实生成与计费。
 *  - 不用 OkHttp `EventListener`：OkHttp 5 的 Builder 监听器 API 与 4.x 有变动风险，
 *    改用「池计数 + 复用耗时」这类公开且稳定的证据。
 */
object LlmConnectionPrewarmer {
    private const val TAG = "LlmPrewarmer"

    /** 同一 host 的预热节流窗（v5.0.90 为 60s，本版更激进：30s） */
    private const val THROTTLE_MS = 30_000L

    /** HTTP/1.1 一条连接只能跑一个请求 → 预热 2 条覆盖并发 */
    private const val CONNECTIONS = 2

    @Volatile
    private var lastAtMs = 0L

    @Volatile
    private var lastKey: String? = null

    /**
     * @param baseUrl 当前对话模型所属 provider 的 baseUrl（如 https://api.openai.com/v1）
     */
    fun warmAsync(scope: CoroutineScope, baseUrl: String?) {
        val normalized = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (normalized.isEmpty() || !normalized.startsWith("http")) return
        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return
        val host = uri.host ?: return
        if (isLocalHost(host)) return

        val key = "${uri.scheme}://$host:${if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80}"
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastAtMs < THROTTLE_MS && lastKey == key) return
            lastAtMs = now
            lastKey = key
        }

        scope.launch(Dispatchers.IO) {
            runCatching { warmBlocking(normalized, host) }
                .onFailure { Log.d(TAG, "warm skipped: ${it.message}") }
        }
    }

    /**
     * v5.0.91 ★keep-alive（比 warm 更激进的一层）：
     * 连接池 idle 180s 会回收连接。用户"打完字想很久再发送"（>3 分钟很常见）时，
     * 预热过的连接已经没了。这里在对话页停留期间每 60s 打一次极轻量请求（最多 5 次，
     * 由调用方的 viewModelScope 控制生命周期），让池内连接持续被使用/保鲜。
     * 绕过 30s 节流（否则会被 warmAsync 的窗口吞掉）。
     */
    fun keepAliveAsync(scope: CoroutineScope, baseUrl: String?) {
        val normalized = baseUrl?.trim()?.trimEnd('/').orEmpty()
        if (normalized.isEmpty() || !normalized.startsWith("http")) return
        val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return
        val host = uri.host ?: return
        if (isLocalHost(host)) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val client = GlobalContext.get().get<OkHttpClient>()
                val request = Request.Builder()
                    .url("$normalized/models")
                    .get()
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store")
                    .build()
                val started = SystemClock.elapsedRealtime()
                client.newBuilder().build().newCall(request).execute().use { it.close() }
                Log.d(TAG, "keepalive host=$host ${SystemClock.elapsedRealtime() - started}ms")
            }.onFailure { Log.d(TAG, "keepalive skipped: ${it.message}") }
        }
    }

    private suspend fun warmBlocking(baseUrl: String, host: String) {
        val client = runCatching { GlobalContext.get().get<OkHttpClient>() }.getOrNull()
            ?: return
        val pool = client.connectionPool

        // ① 系统 DNS 预热（OkHttp 使用同一解析器，随后直接命中）
        val dnsWarmStart = SystemClock.elapsedRealtime()
        val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        val dnsWarmMs = SystemClock.elapsedRealtime() - dnsWarmStart

        val request = Request.Builder()
            .url("$baseUrl/models")
            .get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()

        val poolBefore = pool.connectionCount()

        // ② 并发建 2 条连接（共享同一个池：Builder(client) 复制 connectionPool 引用）
        val results = coroutineScope {
            (0 until CONNECTIONS).map { index ->
                async(Dispatchers.IO) {
                    val warmClient = client.newBuilder().build()

                    suspend fun once(): Boolean =
                        runCatching { warmClient.newCall(request).execute().use { it.close() } }
                            .isSuccess

                    val started = SystemClock.elapsedRealtime()
                    var ok = once()
                    if (!ok) {
                        delay(300)
                        ok = runCatching { once() }.getOrDefault(false)
                    }
                    val total = SystemClock.elapsedRealtime() - started
                    if (!ok) Log.d(TAG, "conn#$index failed (host=$host)")
                    "$index:${total}ms/${if (ok) "ok" else "fail"}"
                }
            }.awaitAll()
        }

        val poolAfterWarm = pool.connectionCount()

        // ③ 复用自证：同一请求再来一次 —— 耗时骤降且池计数不增 = 命中预热连接
        val verifyStart = SystemClock.elapsedRealtime()
        runCatching {
            client.newBuilder().build().newCall(request).execute().use { it.close() }
        }
        val verifyMs = SystemClock.elapsedRealtime() - verifyStart
        val poolAfterVerify = pool.connectionCount()

        Log.i(
            TAG,
            "warm host=$host dnsWarm=${dnsWarmMs}ms ips=${resolved?.size ?: 0} " +
                "conn=[${results.joinToString(" ")}] " +
                "pool=$poolBefore→$poolAfterWarm " +
                "reuse=${verifyMs}ms poolAfterVerify=$poolAfterVerify",
        )
    }

    /** 本地/局域网端点预热无收益（且会白等 connectTimeout）。 */
    private fun isLocalHost(host: String): Boolean {
        return host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            host.matches(Regex("""172\.(1[6-9]|2\d|3[01])\..*"""))
    }
}
