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
        // v5.0.92 ★用户定案：预热只针对 Opencode（go 订阅）与 CommandCode 两个提供商，
        // **不再顺带给其它 provider 起心跳**（同时预热多个会白占进程与连接槽）。
        // 其它 provider 只在"生成开始时"由 ChatService 对当前这一个起心跳（新鲜度保障）。
        val isOcCc = host == "opencode.ai" || host == "api.commandcode.ai"
        if (!isOcCc) return
        // 必须是主请求用的长保活池 —— 预热进错池等于没做（v3.10.5/v3.17.0 两次踩过）
        val client = me.rerere.ai.provider.ProviderManager.opencodeClient
            ?: mainClient
            ?: return
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
    // 对目标 host 发 GET /models (同池), 连接池内永远有 ≤60s 新鲜连接;
    // 发送时零握手零冷连, 且主请求与心跳碰撞窗口 <1s (心跳请求耗时)。
    // 开关沿用 opencodeWarmEnabled/commandCodeWarmEnabled (语义升级为保活)。
    // ─────────────────────────────────────────────────────────────

    // v4.8.34: 通用 per-host 心跳池 — 覆盖"当前对话正在使用的 provider"
    // (此前仅 opencode/CC 两个 host 有心跳; DeepSeek 等直连 provider 在工具
    //  执行期间连接空闲被服务端/中间设备关闭, 下一轮请求需重建连接 (或更糟:
    //  复用半死连接被 watchdog 60s 判死后重试) — "工具结束→恢复输出"的
    //  额外延迟来源之一)。按 host 幂等管理, 同 host 不重复起 job。
    private val keepAliveJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // v5.0.92 ★收益熔断（用户定案："一定要稳定可靠地缩短时间，不能保证缩短时间的话可以放弃"）：
    // 心跳 ping 连续 3 次耗时 > 800ms（含失败）说明网关慢或预热本身成了负担，
    // 暂停该 host 的心跳 30 分钟，让节奏回到真实请求上；恢复后重新评估。
    private val keepAliveSlowStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val keepAlivePausedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val KEEPALIVE_SLOW_MS = 800L
    private const val KEEPALIVE_PAUSE_MS = 30 * 60_000L

    /**
     * v4.8.34: 确保某个 provider baseUrl 有 60s 心跳 (幂等; v4.8.35 起 60s)。
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
                // v5.0.92 熔断期直接睡过（不 ping）
                if ((keepAlivePausedUntil[trimmed] ?: 0L) > System.currentTimeMillis()) {
                    kotlinx.coroutines.delay(60_000L)
                    continue
                }
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
                val pingStart = android.os.SystemClock.elapsedRealtime()
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
                val pingCost = android.os.SystemClock.elapsedRealtime() - pingStart
                // v4.8.98: 心跳成功打点 — 轮首探活据此判断"最近热", 热则零等待放行
                if (warmOk) markWarm(trimmed)
                // v5.0.92 熔断判定：慢 ping（>800ms）或失败都记一次；连续 3 次 → 暂停 30 分钟
                if (warmOk && pingCost <= KEEPALIVE_SLOW_MS) {
                    keepAliveSlowStreak[trimmed] = 0
                } else {
                    val streak = (keepAliveSlowStreak[trimmed] ?: 0) + 1
                    keepAliveSlowStreak[trimmed] = streak
                    if (streak >= 3) {
                        keepAlivePausedUntil[trimmed] = System.currentTimeMillis() + KEEPALIVE_PAUSE_MS
                        keepAliveSlowStreak[trimmed] = 0
                        Log.w(
                            TAG,
                            "keepalive $host 连续 $streak 次慢/失败（本次 ${pingCost}ms，阈值 ${KEEPALIVE_SLOW_MS}ms）" +
                                " → 判定预热未带来稳定收益，暂停 30 分钟",
                        )
                    }
                }
                Log.d(TAG, "keepalive(ensure) $host ok=$warmOk ping=${pingCost}ms (pool fresh)")
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
            lastPokeFailAtMs[trimmed] = System.currentTimeMillis()  // v4.8.132: 失败冷却打点
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

    /** 进程级作用域 (应用启动时经 RikkaHubApp 预热线程的 startProviderKeepAlive 注入, 供后台预探活使用) */
    @Volatile
    private var appScopeRef: kotlinx.coroutines.CoroutineScope? = null

    private fun markWarm(host: String) {
        lastWarmAtMs[host] = System.currentTimeMillis()
    }

    /** 最近 70s 内心跳/探活成功过 — 连接池被视为新鲜 (服务端空闲断连窗口 ~100s, 留余量) */
    private fun isRecentlyWarm(host: String): Boolean =
        System.currentTimeMillis() - (lastWarmAtMs[host] ?: 0L) < 70_000L

    // v4.8.132 (用户实证: "工具返回后恢复间隔越来越久" — 单次探活挂 13.5s 复盘):
    // ① 失败冷却 — 网络坏窗口内 (如 IPv6 路由死) 探活会连环尝试多个地址, 单次可挂
    //    13s+; 60s 内失败过 → 跳过探活直接放行真实请求 (半死连接剔除由既有 header
    //    watchdog + 重试链兜底; 坏网络下探活救不了, 只会叠加等待)。
    // ② 同步探活总时限 — 阻塞 execute 不可被 withTimeout 打断, 改"后台任务 + join
    //    上限"形态: 超时即放行, 探活在后台继续 (成功照常 markWarm)。
    private val lastPokeFailAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val POKE_FAIL_COOLDOWN_MS = 60_000L
    private const val SYNC_POKE_MAX_MS = 6_000L

    private fun isPokeCoolingDown(host: String): Boolean =
        System.currentTimeMillis() - (lastPokeFailAtMs[host] ?: 0L) < POKE_FAIL_COOLDOWN_MS

    /** 工具执行开始前调用 (v4.8.105 C1 提前; 与消息组装并行, 不阻塞): 后台预探活 — 下一轮请求发起前通常已完成。 */
    fun pokeAsync(baseUrl: String, apiKey: String?) {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return
        if (!(trimmed.contains("opencode.ai") || trimmed.contains("commandcode.ai"))) return
        if (isRecentlyWarm(trimmed)) return
        if (isPokeCoolingDown(trimmed)) return  // v4.8.132: 失败冷却期跳过
        val scope = appScopeRef ?: return
        if (pokeJobs[trimmed]?.isActive == true) return
        pokeJobs[trimmed] = scope.launch(Dispatchers.IO) {
            runCatching { pokeProviderHost(trimmed, apiKey) }
        }
    }

    /** v4.8.98 语义（v4.8.106 回调）：热 → 零等待放行；有在途预探活 join ≤maxJoinMs；
     *  仍冷 → **同步快探活剔除半死连接**（connect/read 各 4s 上限，失败即清池）。
     *  教训（v4.8.105→v4.8.106）: "冷=直接放行"在 VPN/抖动网络下会让真实请求整轮卡在
     *  半死连接上直到首包超时 —— 比前置 4s 探活更伤体感。必须保留前置剔除语义。
     *  预探活已在「工具执行开始前」提前发起（v4.8.105 C1），故此处兜底极少触发。 */
    suspend fun awaitPokeIfStale(baseUrl: String, apiKey: String?, maxJoinMs: Long = 1500L): Boolean {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.isBlank()) return false
        if (isRecentlyWarm(trimmed)) return true
        if (isPokeCoolingDown(trimmed)) return false  // v4.8.132: 失败冷却期直接放行
        pokeJobs[trimmed]?.takeIf { it.isActive }?.let { job ->
            kotlinx.coroutines.withTimeoutOrNull(maxJoinMs) { job.join() }
            if (isRecentlyWarm(trimmed)) return true
        }
        // v4.8.132: 同步探活改"后台任务 + join 上限" — 总等待有界 (≤6s), 超时放行
        val scope = appScopeRef
        if (scope == null) return pokeProviderHost(trimmed, apiKey)
        val existing = pokeJobs[trimmed]?.takeIf { it.isActive }
        val job = existing ?: scope.launch(Dispatchers.IO) {
            runCatching { pokeProviderHost(trimmed, apiKey) }
        }.also { pokeJobs[trimmed] = it }
        val finished = kotlinx.coroutines.withTimeoutOrNull(SYNC_POKE_MAX_MS) { job.join() } != null
        return finished && isRecentlyWarm(trimmed)
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

    // v4.8.131 审计: 旧 warmHostOnce (裸 socket 单次预热, "对话启动时延迟预热") 全库
    // 零调用方 — 已被常驻保活 (ensureProviderKeepAlive) 与探活体系取代, 删除。
}
