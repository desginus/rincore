package me.rerere.rikkahub.di


/* ───【原版对齐】DataSourceModule | 差异 +97 行
 * 来源: 原版移植 + 自研 (连接配置根治)
 * 差异: HTTP/1.1 only (HTTP/2 禁用 PROTOCOL_ERROR 根治)、
 *       ConnectionPool(12, 60s)、readTimeout 3min (v2.9.8 参照)
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_24_25
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.network.SettingsProxySelector
import me.rerere.rikkahub.data.network.SettingsProxyAuthenticator
import me.rerere.rikkahub.data.network.SettingsSocks5Authenticator
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.net.Socket
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(Migration_6_7, Migration_11_12, Migration_13_14, Migration_14_15, Migration_15_16, Migration_24_25, Migration_25_26, Migration_26_27, Migration_27_28)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                    options.customExtensions.add(
                        SQLiteCustomExtension(
                            context.applicationInfo.nativeLibraryDir + "/libsimple",
                            null
                        )
                    )
                    options
                }
            )))
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single {
        get<AppDatabase>().folderDao()
    }

    single {
        MessageFtsManager(get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get(), appEventBus = get(), workspaceRepository = getOrNull()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            settingsStore = get(),
            skillManager = getOrNull()
        )
    }

    single<OkHttpClient> {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        // DNS 缓存: 中国网络环境下 DNS 解析频繁抖动的缓冲
        val dnsCache = okhttp3.Cache(
            directory = java.io.File(get<android.content.Context>().cacheDir, "okhttp-dns"),
            maxSize = 4L * 1024 * 1024 // 4MB
        )
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = 8
        }
        // v3.9.15: 默认 client 不挂代理 — 代理仅由 named("proxy") client 承接,
        // 按 ProxyRoute 判定是否走代理 (全局开关 / 部分开启 / 模型勾选)
        OkHttpClient.Builder()
            // ALPN 协商到 h2 后报 stream was reset: PROTOCOL_ERROR
            // (okhttp3.internal.http2.StreamResetException)。
            // protocols(HTTP_1_1, HTTP_2) 顺序不影响 ALPN — 服务端支持 h2 必选 h2,
            // 唯一可靠方案是协议列表只留 HTTP_1_1
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS) // 对齐 v2.9.8 — 大请求体写入宽容
            .pingInterval(30, TimeUnit.SECONDS) // 对齐 v2.9.8
            .connectionPool(
                // 12 连接对齐 v2.9.8; keepalive 60s — DeepSeek 服务端空闲关闭快,
                // 长 keepalive 导致连接池复用陈旧连接 → unexpected end of stream
                // (工具执行 60s+ 后请求必触发, 近几版才出现)
                ConnectionPool(12, 60, TimeUnit.SECONDS)
            )
            .dispatcher(dispatcher)
            .socketFactory(BufferedSocketFactory)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .cache(dnsCache)
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = orig.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)
                    .apply {
                        if (orig.header(HttpHeaders.UserAgent) == null) {
                            // v3.9.13: 只发用户显式自定义的 UA; 留空不发送,
                            // 避免暴露客户端标识 (不污染请求头/上下文)
                            val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent.trim()
                            if (userAgent.isNotEmpty()) {
                                addHeader(HttpHeaders.UserAgent, userAgent)
                            }
                        }
                    }
                    .build()
                chain.proceed(req)
            }
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (
                    contentTypeHeader != null &&
                    contentTypeHeader.contains(";") &&
                    contentTypeHeader.substringBefore(";").trim().equals("application/json", ignoreCase = true)
                ) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                        else HttpLoggingInterceptor.Level.NONE
            })
            .build()
            .also { SearchService.init(it, get()) }
    }

    // v3.9.15: 代理 client — 与默认 client 同配置, 额外挂 ProxySelector/
    // ProxyAuthenticator/SOCKS5 鉴权 + 代理参数热变更 evictAll。
    // 仅在 ProxyRoute 判定命中 (开关 + 模型勾选) 时被 AI 请求选用。
    single<OkHttpClient>(named("proxy")) {
        val settingsStore: SettingsStore = get()
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        val dnsCache = okhttp3.Cache(
            directory = java.io.File(get<android.content.Context>().cacheDir, "okhttp-dns-proxy"),
            maxSize = 4L * 1024 * 1024 // 4MB
        )
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = 8
        }
        java.net.Authenticator.setDefault(SettingsSocks5Authenticator(settingsStore))
        val initialNetworkSetting = settingsStore.settingsFlow.value.networkSetting
        val appliedProxySetting = AtomicReference(
            Triple(
                initialNetworkSetting.proxyUrl,
                initialNetworkSetting.proxyUsername,
                initialNetworkSetting.proxyPassword,
            )
        )
        lateinit var proxyClient: OkHttpClient
        proxyClient = OkHttpClient.Builder()
            .proxySelector(SettingsProxySelector(settingsStore))
            .proxyAuthenticator(SettingsProxyAuthenticator(settingsStore))
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(12, 60, TimeUnit.SECONDS))
            .dispatcher(dispatcher)
            .socketFactory(BufferedSocketFactory)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .cache(dnsCache)
            .addInterceptor { chain ->
                // 代理参数变更 → 驱逐全部连接, 让新连接走新代理
                val networkSetting = settingsStore.settingsFlow.value.networkSetting
                val currentProxySetting = Triple(
                    networkSetting.proxyUrl,
                    networkSetting.proxyUsername,
                    networkSetting.proxyPassword,
                )
                if (appliedProxySetting.getAndSet(currentProxySetting) != currentProxySetting) {
                    proxyClient.connectionPool.evictAll()
                }
                val orig = chain.request()
                chain.proceed(
                    orig.newBuilder()
                        .addHeader(HttpHeaders.AcceptLanguage, acceptLang)
                        .apply {
                            if (orig.header(HttpHeaders.UserAgent) == null) {
                                val userAgent = settingsStore.settingsFlow.value.networkSetting.userAgent.trim()
                                if (userAgent.isNotEmpty()) {
                                    addHeader(HttpHeaders.UserAgent, userAgent)
                                }
                            }
                        }
                        .build()
                )
            }
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                redactHeader("Proxy-Authorization")
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                        else HttpLoggingInterceptor.Level.NONE
            })
            .build()
        proxyClient
    }

    // v3.9.15: 按模型代理路由 — 读 Settings.networkSetting 三个开关状态
    single<me.rerere.ai.provider.ProxyRoute> {
        val settingsStore: SettingsStore = get()
        val defaultClient: OkHttpClient = get()
        val proxyClient: OkHttpClient = get(named("proxy"))
        me.rerere.ai.provider.ProxyRoute { default, modelId ->
            val ns = settingsStore.settingsFlow.value.networkSetting
            val proxyOn = ns.proxyEnabled && ns.proxyUrl.isNotBlank() &&
                (!ns.proxyPartialEnabled || modelId in ns.proxyModelIds)
            if (proxyOn && default === defaultClient) proxyClient else default
        }
    }

    // v3.7.1: Claude/Anthropic 中转 (OpenCode Zen) 独立连接池 —
    // 中转环境空闲关闭比 DeepSeek 慢, keepalive 300s 减少连接重建,
    // 稳定首字延迟 (TTFT)。DeepSeek 的 60s 不变 (避免陈旧连接复发)。
    single<OkHttpClient>(named("claude")) {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(12, 300, TimeUnit.SECONDS))
            .socketFactory(BufferedSocketFactory)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
            .also { me.rerere.ai.provider.ProviderManager.claudeClient = it }
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ProviderManager(
            client = get(),
            context = get(),
            proxyRoute = getOrNull(),
        )
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}

/**
 * SocketFactory 包装: 为新创建的 Socket 设置 SO_RCVBUF=512KB。
 * 增大接收缓冲区减少 TCP 窗口停等, 改善 Deepseek V4 Pro 等模型的
 * 突发式 Token 输出的平滑度。
 *
 * 作用于 OkHttp 的所有连接, 内存上限: 512KB × 连接池(12) ≈ 6MB。
 */
private object BufferedSocketFactory : SocketFactory() {
    private const val RECEIVE_BUFFER_SIZE = 512 * 1024 // 512KB
    private val delegate = SocketFactory.getDefault()

    override fun createSocket(): Socket {
        return delegate.createSocket().apply { receiveBufferSize = RECEIVE_BUFFER_SIZE }
    }

    override fun createSocket(host: String, port: Int): Socket {
        return delegate.createSocket(host, port).apply { receiveBufferSize = RECEIVE_BUFFER_SIZE }
    }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket {
        return delegate.createSocket(host, port, localHost, localPort).apply { receiveBufferSize = RECEIVE_BUFFER_SIZE }
    }

    override fun createSocket(address: java.net.InetAddress, port: Int): Socket {
        return delegate.createSocket(address, port).apply { receiveBufferSize = RECEIVE_BUFFER_SIZE }
    }

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket {
        return delegate.createSocket(address, port, localAddress, localPort).apply { receiveBufferSize = RECEIVE_BUFFER_SIZE }
    }
}
