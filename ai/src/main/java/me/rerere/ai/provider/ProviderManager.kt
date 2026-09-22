package me.rerere.ai.provider


/* ───【原版对齐】ProviderManager.kt | 差异 ±6 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import me.rerere.ai.provider.providers.ClaudeProvider
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    // v3.9.15: 按模型代理路由 (app 层注入), null = 不启用代理路由
    proxyRoute: ProxyRoute? = null,
    // v3.10.5: OpenCode 网关独立长保活池 (opencode.ai 直连场景), null = 回落默认池
    opencodeClient: OkHttpClient? = null,
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // v4.7.18: 主 client 静态引用 — 供 evictAllPools (断流重试/网络切换清理)
        primaryClient = client
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context, proxyRoute, opencodeClient))
        registerProvider("google", GoogleProvider(client, context, proxyRoute))
        registerProvider("claude", ClaudeProvider(claudeClient ?: client, context, proxyRoute))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
        } as Provider<T>
    }

    companion object {
        /** v3.7.1: Claude/Anthropic 独立连接池 (keepalive 300s), DataSourceModule 注入 */
        @Volatile
        var claudeClient: OkHttpClient? = null

        /** v3.10.5: OpenCode 网关独立连接池 (keepalive 300s), DataSourceModule 注入 */
        @Volatile
        var opencodeClient: OkHttpClient? = null

        /** v4.7.18: 主 client 静态引用 — 断流场景连接池清理用 */
        @Volatile
        private var primaryClient: OkHttpClient? = null

        /**
         * v4.7.18: 全域连接池清理 — 断流重试 / 网络切换 (WiFi↔蜂窝、抖动恢复)
         * 时调用。断流多由连接层失效引起 (网络抖动/僵尸连接/网卡问题); 复用
         * 失效连接会让重试直接失败。清理后重试 = 全新连接, 一次成功。
         */
        fun evictAllPools() {
            runCatching { primaryClient?.connectionPool?.evictAll() }
            runCatching { claudeClient?.connectionPool?.evictAll() }
            runCatching { opencodeClient?.connectionPool?.evictAll() }
        }
    }
}
