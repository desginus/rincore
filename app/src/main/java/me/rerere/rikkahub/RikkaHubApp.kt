package me.rerere.rikkahub


/* ───【原版对齐】RikkaHubApp | 差异 +71 行
 * 来源: 原版移植 + 自研 (启动流程扩展)
 * 差异: DSH 技能根刷新 + 插件扫描注册 (v3.6.85-86)、崩溃日志
 *       持久化等自研启动步骤
 * ───────────────────────────────────────────────────────────────*/
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.rerere.rikkahub.data.agentrun.AgentRunBootRecovery
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.log.LogSessionStore
import java.io.File
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.service.ConnectionWarmer
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // v3.8.34: 运行日志持久化存储初始化 (轮次会话, 最多 10 轮)
        LogSessionStore.init(this)
        // v3.9.14: 设备环境检测日志 — 澎湃 OS4 / 骁龙 8 Elite 适配诊断铺垫。
        // 记录: Android API / 系统版本 / 页大小(16KB 设备) / SoC / ABI 列表。
        // 用于发行后线上问题追溯: 若 16KB 页设备上出现 native 崩溃,
        // 通过本条日志即可确认设备页大小与镜像版本。
        logDeviceEnvironment()
        // 连接预热: 冷启动后预解析 DNS + 预建 TCP 到 API 端点, 首次请求延迟降低 200-500ms
        // v3.11.10: 补 Claude 型 provider — 旧实现只暖 OpenAI 型, Console Go 等
        // Claude 通道网关完全不预热 (用户: 打开软件就预热)
        val warmUrls = DEFAULT_PROVIDERS
            .filterIsInstance<me.rerere.ai.provider.ProviderSetting.OpenAI>()
            .mapNotNull { it.baseUrl.takeIf { u -> u.isNotEmpty() } }
        val warmClaudeUrls = DEFAULT_PROVIDERS
            .filterIsInstance<me.rerere.ai.provider.ProviderSetting.Claude>()
            .mapNotNull { it.baseUrl.takeIf { u -> u.isNotEmpty() } }
        ConnectionWarmer.warmConfiguredProviders(this, warmUrls + warmClaudeUrls)
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        // v3.6.45: 异步预热用户自定义 provider host (含 OpenCode Zen) —
        // 首次请求跳过 DNS+TCP, 降低首字延迟。DEFAULT_PROVIDERS 已在上面同步预热。
        Thread({
            runCatching {
                // v3.11.10: 按类型分流预热 — 池必须与主请求一致否则白做:
                //   OpenAI 型 → 默认池 (opencode.ai 域进长保活池)
                //   Claude 型 → claudeClient 长保活池 (300s keepalive + ping),
                //     与 ClaudeProvider 主请求同池 (Console Go 等网关首次预热生效)
                val providers = get<SettingsStore>().settingsFlow.value.providers
                val userUrls = providers
                    .filterIsInstance<ProviderSetting.OpenAI>()
                    .mapNotNull { it.baseUrl.takeIf { u -> u.isNotEmpty() } }
                val claudeUrls = providers
                    .filterIsInstance<ProviderSetting.Claude>()
                    .mapNotNull { it.baseUrl.takeIf { u -> u.isNotEmpty() } }
                // v3.10.5: OkHttp 级预热 — 连接真实进池 (默认池 60s / opencode 池 300s),
                // 跳过 DNS+TCP+TLS 缩短 TTFT; 裸 socket 预热保留作 DNS 兜底
                val httpClient = get<OkHttpClient>()
                val opencodeClient = me.rerere.ai.provider.ProviderManager.opencodeClient
                userUrls.forEach { url -> ConnectionWarmer.warmWithOkHttp(httpClient, url, opencodeClient) }
                claudeUrls.forEach { url ->
                    ConnectionWarmer.warmWithOkHttp(
                        me.rerere.ai.provider.ProviderManager.claudeClient ?: httpClient, url
                    )
                }
                ConnectionWarmer.warmConfiguredProviders(this@RikkaHubApp, userUrls + claudeUrls)
                // v3.13.4: Command Code 启动即预热 (用户定版: 应用启动预热,
                // 非"发消息时") — 焦点 key 为 user_ 且开关开时, 启动线程预热
                // api.commandcode.ai, 冷启动 10-20s 窗口在首条消息前消化
                val st = get<SettingsStore>().settingsFlow.value
                if (st.opencodeApiKey.startsWith("user_", ignoreCase = true) && st.commandCodeWarmEnabled) {
                    // v3.17.0: CC 预热必须进长保活池 (与 v3.17.0 起 CC 主请求同池)
                    ConnectionWarmer.warmWithOkHttp(
                        httpClient,
                        "https://api.commandcode.ai/provider/v1",
                        me.rerere.ai.provider.ProviderManager.opencodeClient,
                    )
                }
            }
        }, "warmup-user-providers").start()
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()
        AgentWorkspace.init(this)

        // delete temp files
        deleteTempFiles()

        // cleanup stale tool output files
        cleanupToolOutputs()

        // cleanup workspace temp dirs (proot + rootfs /tmp)
        cleanupWorkspaceTempDirs()

        // check workspace integrity (mark workspaces with missing files as broken after backup restore)
        checkWorkspaceIntegrity()

        // sync upload files to DB
        syncManagedFiles()

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()
        startWorkflowRegistry()

        // AgentRun boot recovery — flip stranded in-flight runs to process_lost
        get<AgentRunBootRecovery>().runSweep()

        // 初始化诊断日志
        me.rerere.rikkahub.data.ai.diagnostics.DiagnosticLogger.initialize(this)
        // 多生态系统兼容层
        me.rerere.rikkahub.ecosystem.EcosystemManager.initialize(this)
        // 插件管理器
        me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry.initialize(this)
        // Hooks 执行引擎
        me.rerere.rikkahub.ecosystem.plugin.HookEngine.refresh(
            me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry.getSkillRoots().map { it.parentFile!! }
        )
        // Agent 注册表
        me.rerere.rikkahub.ecosystem.plugin.AgentRegistry.loadFromDir(
            this.filesDir.resolve("ecosystem")
        )

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }


    /**
     * v3.9.14: 设备环境检测日志。
     * 澎湃 OS4 (HyperOS, Android 17 底层) 与骁龙 8 Elite (8 Gen 4) 适配铺垫:
     * Android 平台 API / 系统版本 / 页大小 / SoC / ABI 全量落运行日志,
     * 16KB 页设备如在发行后出现 native 加载问题可立即定位镜像版本。
     */
    private fun logDeviceEnvironment() {
        runCatching {
            val abi = Build.SUPPORTED_ABIS.joinToString(",")
            val pageSizeKb = try {
                // sysconf(_SC_PAGESIZE) — 16KB 页设备返回 16384
                android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024
            } catch (_: Throwable) { 4 }
            android.util.Log.i(
                "RinCoreEnv",
                "api=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} " +
                    "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} " +
                    "soc=${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE} " +
                    "pageSize=${pageSizeKb}KB abi=$abi " +
                    "miuiVersion=${Build.VERSION.INCREMENTAL ?: ""}"
            )
        }
    }

    private fun cleanupWorkspaceTempDirs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceManager>().cleanupAllTempDirs()
            }.onFailure {
                Log.e(TAG, "cleanupWorkspaceTempDirs failed", it)
            }
        }
    }

    private fun checkWorkspaceIntegrity() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<WorkspaceRepository>().checkIntegrity()
                // v3.6.85: DSH 插件生态 — 刷新 workspace 的 DSH 技能根
                get<WorkspaceRepository>().refreshDshSkillRoots(get())
                // v3.6.112: 插件与技能彻底隔开 — 不再把插件 skills 拆包进技能系统。
                // 插件技能经 plugin__<插件名>__<技能> 工具读取 (插件域), 桥接经
                // STDIO MCP (mcp__plugin__<插件名>__<工具>)。仅迁移 v3.6.109 误落
                // /skills 的历史残留回插件目录。
                runCatching {
                    val skillManager = get<me.rerere.rikkahub.data.files.SkillManager>()
                    me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry
                        .migrateLegacySkills(skillManager.getSkillsDir())
                    // v3.6.121: 回滚收尾 — 移除 v3.6.112-119 自动注册的 plugin__ 服务器
                    // 残留 (viaWorkspace STDIO 桥接), 清理后 MCP 连接恢复干净状态
                    get<me.rerere.rikkahub.data.datastore.SettingsStore>().update { s ->
                        val stale = s.mcpServers.filter { it.commonOptions.name.startsWith("plugin__") }
                        if (stale.isEmpty()) return@update s
                        val staleIds = stale.map { it.id }.toSet()
                        Log.i(TAG, "cleanup stale plugin bridges: ${stale.map { it.commonOptions.name }}")
                        s.copy(
                            mcpServers = s.mcpServers.filter { it !in stale },
                            assistants = s.assistants.map { a ->
                                a.copy(mcpServers = a.mcpServers.filter { it !in staleIds }.toSet())
                            },
                        )
                    }
                }.onFailure { Log.e(TAG, "claw plugin setup failed", it) }
                // v3.6.86: 插件生态 — 扫描 .plugins 并注册桥接 (技能 + STDIO MCP)
                get<me.rerere.rikkahub.data.plugin.PluginManager>().refresh()
            }.onFailure {
                Log.e(TAG, "checkWorkspaceIntegrity failed", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun cleanupToolOutputs() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val dir = File(filesDir, FileFolders.TOOL_OUTPUTS)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun startWorkflowRegistry() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                val registry = get<me.rerere.rikkahub.workflow.trigger.TriggerRegistry>()
                val engine = get<me.rerere.rikkahub.workflow.execution.WorkflowEngine>()
                registry.setEngineCallback(engine.triggerCallback)
                registry.start()
            }.onFailure {
                Log.e(TAG, "startWorkflowRegistry failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
