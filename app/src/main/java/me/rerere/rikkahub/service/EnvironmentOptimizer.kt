/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.service


/* ───【自研】EnvironmentOptimizer.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import okhttp3.OkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    // v4.7.18: 网络切换监听 — 网络可用性变化 (WiFi↔蜂窝切换/抖动恢复) 时
    // 清理连接池。用户实证: 电脑上同类断流与网卡有关; 手机对应场景 = 网络
    // 接口切换后旧连接已死, 复用即断流。
    @Volatile
    private var networkMonitorRegistered = false

    fun startNetworkMonitor(context: android.content.Context) {
        if (networkMonitorRegistered) return
        runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.i(TAG, "network available — evicting connection pools")
                    me.rerere.ai.provider.ProviderManager.evictAllPools()
                }
                override fun onLost(network: android.net.Network) {
                    Log.i(TAG, "network lost — evicting connection pools")
                    me.rerere.ai.provider.ProviderManager.evictAllPools()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkMonitorRegistered = true
            Log.i(TAG, "network monitor started")
        }.onFailure { Log.w(TAG, "network monitor failed: ${it.message}") }
    }

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

    /**
     * v4.8.91: 自动选池的保活入口 —— 供 UI 侧（冷启动/回前台/进入对话）调用。
     * OC/CC 走长保活池（与主请求同池），其余走主 client；均幂等、非阻塞。
     */
    fun ensureProviderKeepAliveAuto(
        appScope: kotlinx.coroutines.CoroutineScope,
        baseUrl: String,
        apiKey: String? = null,
    ) {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return
        val host = runCatching { java.net.URI(trimmed).host }.getOrNull() ?: return
        val isLongLived = host == "opencode.ai" || host == "api.commandcode.ai"
        val client = if (isLongLived) {
            me.rerere.ai.provider.ProviderManager.opencodeClient ?: mainClient
        } else {
            mainClient ?: runCatching {
                org.koin.core.context.GlobalContext.get().get<okhttp3.OkHttpClient>()
            }.getOrNull()
        } ?: return
        ensureProviderKeepAlive(appScope, client, trimmed, apiKey)
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
    // v3.12.6: okhttp 预热节流 — 同 host 60s 内只发一次 (此前每条消息都发)
    private val lastWarmAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun warmWithOkHttp(client: OkHttpClient, baseUrl: String, opencodeClient: OkHttpClient? = null) {
        val safe = runCatching { baseUrl.trimEnd('/') + "/models" }.getOrNull() ?: return
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val last = lastWarmAt[host] ?: 0L
        if (now - last < 60_000L) return
        lastWarmAt[host] = now
        // v3.10.7: 按 host 选池 — 长保活池 host (opencode.ai/api.commandcode.ai)
        // 主请求走 opencodeClient, 预热必须进同一个池否则白做 (v3.10.5 疏漏)
        // v3.17.0: CC 同入长池判定 (v3.13.4 CC 预热进了 60s 默认池, 基本白做)
        val isLongLived = runCatching {
            val h = java.net.URI(baseUrl).host
            h == "opencode.ai" || h == "api.commandcode.ai"
        }.getOrDefault(false)
        val eff = if (isLongLived) (opencodeClient ?: client) else client
        // v3.12.0: 预热请求用短超时 clone (同池) — 死网关/黑洞 host 时预热线程
        // 不再按主 client 的 3min readTimeout 挂死, 占用线程与连接池位置;
        // 连接建立部分照常进池复用
        val warmClient = runCatching {
            eff.newBuilder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }.getOrDefault(eff)
        Thread({
            runCatching {
                val req = okhttp3.Request.Builder().url(safe).get().build()
                warmClient.newCall(req).execute().use { }
            }.onFailure {
                Log.w(TAG, "OkHttp 预热失败: $safe — ${it.message}")
            }
        }, "warmup-okhttp").start()
    }

    // ─────────────────────────────────────────────────────────────
    // 4.1.3: 常驻连接保活心跳 (TTFT 根治) —
    // 旧预热体系失效的两个结构性原因:
    //   a) 服务端 (CF 系) 空闲 ~100s 关连接, 池 300s 只是客户端意愿,
    //      用户隔几分钟发一条 → 池内连接早已死, 复用失败重试反而更慢;
    //   b) 生成前预热与主请求并发, 同 key 请求被网关串行化 (v3.12.6
    //      用户实测), 预热请求挂在慢网关上时主请求排队等它。
    // 心跳方案: 应用级常驻协程每 60s (< 100s 空闲阈值, 留 40s 安全余量; v4.8.35 从 45s 放宽)
    // 对目标 host 发 GET /models (同池), 连接池内永远有 ≤45s 新鲜连接;
    // 发送时零握手零冷连, 且主请求与心跳碰撞窗口 <1s (心跳请求耗时)。
    // 开关沿用 opencodeWarmEnabled/commandCodeWarmEnabled (语义升级为保活)。
    // ─────────────────────────────────────────────────────────────

    // v4.8.34: 通用 per-host 心跳池 — 覆盖"当前对话正在使用的 provider"
    // (此前仅 opencode/CC 两个 host 有心跳; DeepSeek 等直连 provider 在工具
    //  执行期间连接空闲被服务端/中间设备关闭, 下一轮请求需重建连接 (或更糟:
    //  复用半死连接被 watchdog 60s 判死后重试) — "工具结束→恢复输出"的
    //  额外延迟来源之一)。按 host 幂等管理, 同 host 不重复起 job。
    private val keepAliveJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * v4.8.34: 确保某个 provider baseUrl 有 45s 心跳 (幂等)。
     * 由生成开始时调用 (当前对话确定的 provider) — 工具执行期间连接保持新鲜,
     * 工具结束后的下一轮请求直接复用热连接。
     */
    fun ensureProviderKeepAlive(
        appScope: kotlinx.coroutines.CoroutineScope,
        client: OkHttpClient,
        baseUrl: String,
        apiKey: String? = null,
    ) {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return
        val existing = keepAliveJobs[trimmed]
        if (existing?.isActive == true) return
        val host = runCatching { java.net.URI(trimmed).host }.getOrNull() ?: return
        // v4.8.38: launch(Dispatchers.IO) — 循环体内 newCall().execute() 是同步
        // 阻塞网络调用; AppScope 的默认调度器为 Main, 原实现在主线程每 60s 阻塞
        // 一次 (网络差时数秒), 即"预热连接"引发的周期性卡顿源。统一移入 IO。
        keepAliveJobs[trimmed] = appScope.launch(Dispatchers.IO) {
            // v4.8.99 能效策略: 后台且无活跃生成 → 暂停心跳 (循环存活, 15s 粒度检查;
            // 回到前台 (foregroundEpoch 变化) 或后台生成中立即恢复原 60s 节奏)。
            // 用户定版场景 (前台使用/生成) 行为不变; 仅消除"熄屏挂后台空转心跳"的耗电。
            var lastForegroundEpoch = AppForegroundState.foregroundEpoch
            while (isActive) {
                val epoch = AppForegroundState.foregroundEpoch
                if (AppForegroundState.isBackground &&
                    !GenerationForegroundService.hasActiveGeneration() &&
                    epoch == lastForegroundEpoch
                ) {
                    kotlinx.coroutines.delay(15_000L)
                    continue
                }
                lastForegroundEpoch = epoch
                // v4.8.71 (用户定版): 先 ping 后计时 — 启动/生成开始即建立热连接
                // ("开应用直接拉心跳, 首次延迟不是连接延迟"), 首 ping 不再等 60s;
                // 之后 60s 间隔维持 (服务端空闲断连窗口 ~100s, 60s 留 40s 余量)。
                val warmOk = runCatching {
                    val warmClient = client.newBuilder()
                        .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val reqBuilder = okhttp3.Request.Builder()
                        .url(trimmed + "/models").get()
                    if (!apiKey.isNullOrBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                    }
                    warmClient.newCall(reqBuilder.build()).execute().use { }
                }.onFailure {
                    Log.w(TAG, "keepalive(ensure) $host: ${it.message}")
                }.isSuccess
                // v4.8.98: 心跳成功打点 — 轮首探活据此判断"最近热", 热则零等待放行
                if (warmOk) markWarm(trimmed)
                Log.d(TAG, "keepalive(ensure) $host ok (pool fresh)")
                kotlinx.coroutines.delay(60_000L)
            }
        }
        Log.i(TAG, "Provider keepalive ensured: $host (60s interval, same-pool)")
    }

    @Volatile
    private var mainClient: OkHttpClient? = null

    /**
     * v4.8.74: OC/CC 定向探活 — 工具长执行后、真实请求发起前调用 (单次 GET /models,
     * 4s 短超时)。半死连接在此被剔除 + 清池, 真实请求不再整段等 readTimeout —
     * "工具返回后模型久久不开口"的客户端侧主因修复。成功返回 true 不清池。
     */
    suspend fun pokeProviderHost(baseUrl: String, apiKey: String?): Boolean {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return false
        val isOcCc = trimmed.contains("opencode.ai") || trimmed.contains("commandcode.ai")
        val client = (if (isOcCc) me.rerere.ai.provider.ProviderManager.opencodeClient else null)
            ?: mainClient ?: return false
        val ok = runCatching {
            val warmClient = client.newBuilder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val reqBuilder = okhttp3.Request.Builder().url(trimmed + "/models").get()
            if (!apiKey.isNullOrBlank()) reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            warmClient.newCall(reqBuilder.build()).execute().use { true }
        }.getOrElse { e ->
            runCatching { client.connectionPool.evictAll() }
            Log.w(TAG, "poke fail ($trimmed): ${e.message} — pool evicted")
            me.rerere.rikkahub.data.ai.CallTracer.event(
                "CONN", "poke_failed", "host=$trimmed err=${e.message?.take(120)}"
            )
            false
        }
        if (ok) markWarm(trimmed)
        return ok
    }

    // ── v4.8.98: 工具后探活二阶段 — "工具返回后模型久久不开口"的剩余等待消除 ──
    // 旧行为: 每轮请求前若 gap>15s 同步探活 (最长 4s) — 长工具后每轮都付这份等待。
    // 新行为: 工具完成瞬间先发后台预探活 (与消息组装并行); 轮首仅在"非最近热"时等待。
    private val lastWarmAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val pokeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 进程级作用域 (生成开始时经 startProviderKeepAlive 注入, 供后台预探活使用) */
    @Volatile
    private var appScopeRef: kotlinx.coroutines.CoroutineScope? = null

    private fun markWarm(host: String) {
        lastWarmAtMs[host] = System.currentTimeMillis()
    }

    /** 最近 70s 内心跳/探活成功过 — 连接池被视为新鲜 (服务端空闲断连窗口 ~100s, 留余量) */
    private fun isRecentlyWarm(host: String): Boolean =
        System.currentTimeMillis() - (lastWarmAtMs[host] ?: 0L) < 70_000L

    /** 工具执行完成的瞬间调用 (不阻塞): 后台预探活 — 下一轮请求发起前通常已完成。 */
    fun pokeAsync(baseUrl: String, apiKey: String?) {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return
        if (!(trimmed.contains("opencode.ai") || trimmed.contains("commandcode.ai"))) return
        if (isRecentlyWarm(trimmed)) return
        val scope = appScopeRef ?: return
        if (pokeJobs[trimmed]?.isActive == true) return
        pokeJobs[trimmed] = scope.launch(Dispatchers.IO) {
            runCatching { pokeProviderHost(trimmed, apiKey) }
        }
    }

    /** v4.8.105 (无感衔接): 轮首调用 = 关键路径零等待 —— 热 → 立即放行;
     *  有在途预探活最多 join maxJoinMs (默认 500ms); 仍冷 → **不再同步探活**,
     *  放行真实请求 + 后台补探。半死连接由真实请求首包超时暴露, 走既有断流重试链
     *  (3 轮重试 + 清理连接池) 剔除恢复 —— 不再让每一轮都付探活等待税
     *  (旧兜底: join 1.5s + 同步探活最长数秒, 用户体感"工具后更久"的来源之一)。 */
    suspend fun awaitPokeIfStale(baseUrl: String, apiKey: String?, maxJoinMs: Long = 500L): Boolean {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return false
        if (isRecentlyWarm(trimmed)) return true
        pokeJobs[trimmed]?.takeIf { it.isActive }?.let { job ->
            kotlinx.coroutines.withTimeoutOrNull(maxJoinMs) { job.join() }
            if (isRecentlyWarm(trimmed)) return true
        }
        pokeAsync(trimmed, apiKey)
        return false
    }

    fun startProviderKeepAlive(
        appScope: kotlinx.coroutines.CoroutineScope,
        httpClient: OkHttpClient,
        opencodeClient: OkHttpClient?,
        apiKey: String,
        commandCodeEnabled: Boolean,
        opencodeEnabled: Boolean,
    ) {
        mainClient = httpClient
        // v4.8.98: 存进程级作用域 — 工具完成瞬间的后台预探活 (pokeAsync) 使用
        appScopeRef = appScope
        // v4.8.71 (用户定版): 双供应商常驻心跳 — OpenCode 与 Command Code 各自独立,
        // 互不踩踏 (原实现单槽 keepAliveJob: 两者只能其一; 且首个 ping 延迟 60s —
        // "开应用执行任务"的首请求仍付完整连接延迟)。现改为: 启动即 ping
        // (见 ensureProviderKeepAlive '先 ping 后计时') + 双 host 并行常驻;
        // per-host 幂等 (keepAliveJobs), 重复调用零成本。
        val client = opencodeClient ?: httpClient
        if (commandCodeEnabled) {
            val key = apiKey.takeIf { it.startsWith("user_", ignoreCase = true) }
            ensureProviderKeepAlive(appScope, client, "https://api.commandcode.ai/provider/v1", key)
        }
        if (opencodeEnabled) {
            val key = apiKey.takeIf { it.startsWith("sk", ignoreCase = true) }
            ensureProviderKeepAlive(appScope, client, "https://opencode.ai/zen/go/v1", key)
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
