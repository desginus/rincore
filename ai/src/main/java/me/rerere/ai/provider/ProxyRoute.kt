package me.rerere.ai.provider


import okhttp3.OkHttpClient

/**
 * v3.9.15: 按模型代理路由。
 * app 层注入实现: 根据模型 id 判断本次请求是否走代理 client。
 * 判定逻辑 (app 层实现读 Settings.networkSetting):
 *   proxyEnabled == false -> 默认 client (直连)
 *   proxyEnabled == true && proxyPartialEnabled == false -> 代理 client (全局)
 *   proxyEnabled == true && proxyPartialEnabled == true && modelId in proxyModelIds -> 代理 client (仅勾选模型)
 *   其余 -> 默认 client
 * 默认返回 defaultClient, 无 proxyRoute 注入时零行为变化。
 */
fun interface ProxyRoute {
    fun clientFor(defaultClient: OkHttpClient, modelId: String): OkHttpClient
}

/** 便捷函数: 有路由按模型选 client, 无路由原样返回 */
internal fun OkHttpClient.resolveProxy(route: ProxyRoute?, modelId: String): OkHttpClient =
    route?.clientFor(this, modelId) ?: this