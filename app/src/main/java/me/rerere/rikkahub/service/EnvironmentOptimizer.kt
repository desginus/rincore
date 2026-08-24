package me.rerere.rikkahub.service


/* ───【自研】EnvironmentOptimizer.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * 连接预热 (v3.6.42: 断路器/蜂窝粘连已删除, 仅保留预热)
 * 冷启动后预解析 + 预连接 API 端点, 减少首次请求延迟。
 */

// ─────────────────────────────────────────────────────────────────
// 3. 连接预热 (Connection Warmup)
// ─────────────────────────────────────────────────────────────────

/**
 * 应用冷启动后, 预解析 DNS + 预建立 TCP 连接到 API 服务器。
 * 用户的首次请求将跳过 DNS 查询和 TCP 握手, 延迟降低 200-500ms。
 */
object ConnectionWarmer {
    private const val TAG = "ConnectionWarmer"
    // v3.6.45: per-host warmed — 避免重复预热同一 host, 但支持多 host 预热
    private val warmedHosts = ConcurrentHashMap.newKeySet<String>()

    /**
     * 异步预热指定主机。应在 Application.onCreate 或首个 Activity 中调用,
     * 不阻塞主线程。
     */
    fun warmHost(context: Context, host: String, port: Int = 443) {
        if (!warmedHosts.add("$host:$port")) return // 已预热过则跳过
        Thread({
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return@Thread
                val activeNetwork = connectivityManager.activeNetwork ?: return@Thread
                // 绑定到当前活跃网络, 确保 DNS 解析使用正确接口 (Wi-Fi 或蜂窝)
                val socketFactory = activeNetwork.socketFactory
                val socket = socketFactory.createSocket()
                val addr = java.net.InetSocketAddress(host, port)
                socket.connect(addr, 2000) // 2s 超时, 不阻塞太久
                socket.close()
                Log.i(TAG, "预热连接成功: $host:$port")
            } catch (e: Exception) {
                Log.w(TAG, "预热连接失败: $host:$port — ${e.message}")
            }
        }, "warmup-$host").start()
    }

    /** 预热所有已配置的 API 端点 */
    fun warmConfiguredProviders(context: Context, baseUrls: List<String>) {
        val hosts = baseUrls.mapNotNull { url ->
            runCatching { java.net.URI(url).host }.getOrNull()
        }.distinct().filter { it.isNotEmpty() }
        for (host in hosts) {
            warmHost(context, host)
        }
    }

    // v3.10.5: OkHttp 级预热 — 用实际请求 (GET <base>/models) 建立连接并进入
    // OkHttp 连接池, 主请求直接复用已就绪连接, 跳过 DNS+TCP+TLS (200-500ms)。
    // 裸 socket 预热 (warmHost) 只暖 DNS 缓存, 不进池 — TTFT 专项升级。
    // 注意: 必须使用与主请求相同的 OkHttpClient 实例, 否则连接池独立无复用。
    // 401/404 亦可 (连接已建立进池); 全部静默, 不影响主链路。
    fun warmWithOkHttp(client: OkHttpClient, baseUrl: String) {
        val safe = runCatching { baseUrl.trimEnd('/') + "/models" }.getOrNull() ?: return
        Thread({
            runCatching {
                val req = okhttp3.Request.Builder().url(safe).get().build()
                client.newCall(req).execute().use { }
            }.onFailure {
                Log.w(TAG, "OkHttp 预热失败: $safe — ${it.message}")
            }
        }, "warmup-okhttp").start()
    }

    /**
     * 单次预热某个主机 (不受 warmed 标记限制)。
     * 用于对话启动时延迟预热, 与消息预处理并发执行。
     */
    fun warmHostOnce(context: Context, host: String, port: Int = 443) {
        Thread({
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return@Thread
                val activeNetwork = connectivityManager.activeNetwork ?: return@Thread
                val socketFactory = activeNetwork.socketFactory
                val socket = socketFactory.createSocket()
                val addr = java.net.InetSocketAddress(host, port)
                socket.connect(addr, 2000)
                socket.close()
                Log.i(TAG, "延迟预热成功: $host:$port")
            } catch (e: Exception) {
                Log.w(TAG, "延迟预热失败: $host:$port — ${e.message}")
            }
        }, "lazy-warmup-$host").start()
    }
}
