/* 【域 A·对话核心】 — 模型连接预热 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.service

import android.util.Log
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

/**
 * v4.8.91: 当前对话模型的连接预热入口。
 *
 * 目标（用户定版）：**打开软件后发送消息的首字延迟尽量低**。
 * 首字延迟的客户端侧组成 = DNS + TCP + TLS + 请求体构建 + 服务端首包。
 * 请求体构建早已在 IO 线程预热（工具池/AGENTS 缓存）；DNS/TCP/TLS 则由这里消除：
 *
 *  · 冷启动：RikkaHubApp 的预热线程已对配置的 provider 全量预热；
 *  · **回前台/进入对话**：立即为"当前对话模型"的 provider 建立/刷新热连接，
 *    并拉起 60s 常驻保活（ConnectionWarmer，首 ping 立即），
 *    这样用户看一会儿再发消息，连接依然是热的（服务端 ~100s 空闲断连也吃不到我们）。
 *
 * 幂等：同一 baseUrl 的保活任务已存在时直接返回；失败静默（不影响主链路）。
 * 由 RouteActivity onResume / 输入栏 focus 调用。
 *
 * ★v5.3.71（用户定案：OC/CC 是主力）：
 * 冷启动与回前台时，除了「当前普通模型」，还把**快速/极致池里的 OC/CC 模型**一起预热。
 * 原因：路由可能在池里选另一个模型，只预热普通模型会导致「选中的那个从没热过」。
 * 仍然只预热 opencode.ai / api.commandcode.ai，其它 provider 一律跳过。
 */
object ProviderWarmup {
    private const val TAG = "ProviderWarmup"

    fun warmCurrentChatModel() {
        runCatching {
            val koin = org.koin.core.context.GlobalContext.get()
            val settings = koin.get<me.rerere.rikkahub.data.datastore.SettingsStore>()
                .settingsFlow.value
            val appScope = koin.get<me.rerere.rikkahub.AppScope>()

            val assistant = settings.getCurrentAssistant()
            val candidates = LinkedHashMap<kotlin.uuid.Uuid, me.rerere.ai.provider.Model>()
            settings.getCurrentChatModel()?.let { candidates[it.id] = it }
            when (assistant.modelRouting) {
                1 -> settings.fastChatModelIds
                2 -> settings.ultraChatModelIds
                else -> emptyList()
            }.forEach { id ->
                settings.findModelById(id)?.let { candidates[it.id] = it }
            }

            val ns = settings.networkSetting
            val proxyClient = if (ns.proxyEnabled && ns.proxyUrl.isNotBlank()) {
                runCatching {
                    koin.get<okhttp3.OkHttpClient>(
                        org.koin.core.qualifier.named("proxy")
                    )
                }.getOrNull()
            } else null

            candidates.values.forEach { model ->
                val provider = model.findProvider(settings.providers) ?: return@forEach
                val baseUrl = when (provider) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> provider.baseUrl
                    is me.rerere.ai.provider.ProviderSetting.Claude -> provider.baseUrl
                    is me.rerere.ai.provider.ProviderSetting.Google -> provider.baseUrl
                    else -> null
                } ?: return@forEach
                val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
                    ?: return@forEach
                // 用户定案：只预热 OpenCode（go 订阅）与 Command Code。
                if (host != "opencode.ai" && host != "api.commandcode.ai") return@forEach

                val apiKey = when (provider) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> provider.apiKey
                    is me.rerere.ai.provider.ProviderSetting.Claude -> provider.apiKey
                    is me.rerere.ai.provider.ProviderSetting.Google -> provider.apiKey
                    else -> null
                }
                // ═══ v5.3.33 ★代理命中 → 主请求走 proxyClient → 预热必须同池，否则白做 ═══
                val proxyOn = ns.proxyEnabled && ns.proxyUrl.isNotBlank() &&
                    (!ns.proxyPartialEnabled || model.modelId in ns.proxyModelIds)
                val overrideClient = if (proxyOn) proxyClient else null
                ConnectionWarmer.ensureProviderKeepAliveAuto(
                    appScope = appScope,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    overrideClient = overrideClient,
                )
            }
        }.onFailure {
            Log.w(TAG, "warm skipped: ${it.message}")
        }
    }
}
