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
        me.rerere.rikkahub.data.operit.runtime.OperitScriptRuntime(
            filesRootProvider = {
                java.io.File(ctx.filesDir, "operit_runtime")
            },
        )
    }
    single { me.rerere.rikkahub.data.operit.runtime.OperitToolProvider(get(), get()) }

    // v4.5.34: 插件 UI 运行时 (WebView 桥) — filesRoot 与脚本运行时同目录
    single {
        val ctx = get<android.content.Context>()
        me.rerere.rikkahub.data.operit.runtime.OperitUiRuntime().apply {
            filesRoot = java.io.File(ctx.filesDir, "operit_runtime")
        }
    }

    viewModelOf(::MarketVM)
}
