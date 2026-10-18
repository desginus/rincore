/* 【域 A·对话核心】 — 模型连接预热 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.service

import android.util.Log
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

/**
 * v4.8.91: 当前对话模型的连接预热入口。
 *
 * 目标（用户定版）：**打开软件后发送消息的首字延迟尽量低**。
 * 首字延迟的客户端侧组成 = DNS + TCP + TLS + 请求体构建 + 服务端首包。
 * 请求体构建早已在 IO 线程预热（工具池/AGENTS 缓存）；DNS/TCP/TLS 则由这里消除：
 *
 *  · 冷启动：RikkaHubApp 的预热线程已对配置的 provider 全量预热；
 *  · **回前台/进入对话**（本次新增）：立即为"当前对话模型"的 provider 建立/刷新热连接，
 *    并拉起 60s 常驻保活（ConnectionWarmer，首 ping 立即），
 *    这样用户看一会儿再发消息，连接依然是热的（服务端 ~100s 空闲断连也吃不到我们）。
 *
 * 幂等：同一 baseUrl 的保活任务已存在时直接返回；失败静默（不影响主链路）。
 * 由 RouteActivity onResume 调用（每次进入前台都会跑一次，含冷启动）。
 */
object ProviderWarmup {
    private const val TAG = "ProviderWarmup"

    fun warmCurrentChatModel() {
        runCatching {
            val koin = org.koin.core.context.GlobalContext.get()
            val settings = koin.get<me.rerere.rikkahub.data.datastore.SettingsStore>()
                .settingsFlow.value
            val model = settings.getCurrentChatModel() ?: return
            val provider = model.findProvider(settings.providers) ?: return
            val baseUrl = when (provider) {
                is me.rerere.ai.provider.ProviderSetting.OpenAI -> provider.baseUrl
                is me.rerere.ai.provider.ProviderSetting.Claude -> provider.baseUrl
                is me.rerere.ai.provider.ProviderSetting.Google -> provider.baseUrl
                else -> null
            } ?: return
            val apiKey = when (provider) {
                is me.rerere.ai.provider.ProviderSetting.OpenAI -> provider.apiKey
                is me.rerere.ai.provider.ProviderSetting.Claude -> provider.apiKey
                is me.rerere.ai.provider.ProviderSetting.Google -> provider.apiKey
                else -> null
            }
            val appScope = koin.get<me.rerere.rikkahub.AppScope>()
            ConnectionWarmer.ensureProviderKeepAliveAuto(appScope, baseUrl, apiKey)
        }.onFailure {
            Log.w(TAG, "warm skipped: ${it.message}")
        }
    }
}
