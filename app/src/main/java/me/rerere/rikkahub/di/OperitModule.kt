package me.rerere.rikkahub.di


/* ───【自研】岔路口计划·阶段1 — Operit 生态兼容 DI 模块
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import me.rerere.rikkahub.data.operit.market.InstalledPackageStore
import me.rerere.rikkahub.data.operit.market.MarketApiService
import me.rerere.rikkahub.data.operit.market.MarketInstallService
import me.rerere.rikkahub.data.operit.market.MarketRepository
import me.rerere.rikkahub.data.operit.market.PackageDownloader
import me.rerere.rikkahub.ui.pages.market.MarketVM
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val operitModule = module {
    single { MarketApiService(get()) }
    single { MarketRepository(get()) }
    single { PackageDownloader(get<OkHttpClient>()) }
    single { InstalledPackageStore(get()) }
    single {
        MarketInstallService(
            apiService = get(),
            downloader = get(),
            store = get(),
            context = get(),
            okHttpClient = get(),
            skillManager = get(),
            settingsStore = get(),
        )
    }

    // v4.5.29 阶段2: 脚本运行时 + 工具提供器
    single {
        val ctx = get<android.content.Context>()
        // v4.6.4 运行兼容: HTTP 桥 + shell 桥注入 (eager 解析防 Koin 延迟上下文问题)
        val okHttp = get<okhttp3.OkHttpClient>()
        val wsRepo = get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
        me.rerere.rikkahub.data.operit.runtime.OperitScriptRuntime(
            filesRootProvider = {
                java.io.File(ctx.filesDir, "operit_runtime")
            },
            okHttpProvider = { okHttp },
            workspaceProvider = {
                kotlinx.coroutines.runBlocking {
                    wsRepo.getAllWorkspaces()
                        .firstOrNull { it.shellStatus == me.rerere.workspace.WorkspaceShellStatus.READY.name }
                }?.let { ws -> wsRepo to ws.id }
            },
            // v4.6.7 记忆打通 (极简版): 增强记忆工具直接读写 RinCore 原生记忆
            memoryRepositoryProvider = { get<me.rerere.rikkahub.data.repository.MemoryRepository>() },
        )
    }
    single {
        // v4.6.2: 内置包播种挂接 — 首次 refresh 时把 assets/operit-packages 释放入库
        val ctx = get<android.content.Context>()
        val builtinStore = get<me.rerere.rikkahub.data.operit.market.InstalledPackageStore>()
        me.rerere.rikkahub.data.operit.runtime.OperitToolProvider(
            builtinStore,
            get(),
            preRefresh = {
                me.rerere.rikkahub.data.operit.runtime.OperitBuiltinPackages.ensureSeeded(ctx, builtinStore)
            },
        )
    }

    // v4.5.34: 插件 UI 运行时 (WebView 桥) — filesRoot 与脚本运行时同目录
    single {
        val ctx = get<android.content.Context>()
        me.rerere.rikkahub.data.operit.runtime.OperitUiRuntime().apply {
            filesRoot = java.io.File(ctx.filesDir, "operit_runtime")
        }
    }

    viewModelOf(::MarketVM)
}
