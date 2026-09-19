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
    single { MarketInstallService(get(), get(), get()) }
    viewModelOf(::MarketVM)
}
