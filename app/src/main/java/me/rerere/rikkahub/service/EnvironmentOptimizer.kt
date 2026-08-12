package me.rerere.rikkahub.service

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
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
    private var warmed = false

    /**
     * 异步预热指定主机。应在 Application.onCreate 或首个 Activity 中调用,
     * 不阻塞主线程。
     */
    fun warmHost(context: Context, host: String, port: Int = 443) {
        if (warmed) return
        warmed = true
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
