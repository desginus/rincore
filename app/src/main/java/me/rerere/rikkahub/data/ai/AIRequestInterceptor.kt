/* 【域 B·AI 传输】 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai

/* ───【原版对齐】AIRequestInterceptor.kt | 原版 2.5.1 为空壳直通
 * v4.8.126 差异 (OC 网关客户端合规): OpenCode Go/Zen 官方客户端要求 —
 * "Identify itself with its own user agent, such as `my-coding-agent/1.0`,
 * rather than a generic SDK or HTTP-library name" (opencode.ai/docs/console/go)。
 * 此前全链 AI 请求 UA = OkHttp 默认 (okhttp/4.x) = 文档点名的不合规形态
 * (该网关明确"Traffic is monitored for abuse"; 客户端身份 = 待遇门槛)。
 * 本拦截器为未显式设置 UA 的请求统一注入 RinCore/<版本>。
 * 显式自带 UA 的调用方 (市场/Opera 导入/CC 用量接口等) 不受影响 — 缺省才补。
 * 装配面: 主 client + proxy client (既有) + opencode/claude 长保活 client (本版新增)。
 * ───────────────────────────────────────────────────────────────*/

import me.rerere.rikkahub.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

class AIRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // v4.8.126: OC 合规 — 自有 UA 标识 (仅缺省时补充; 调用方显式设置者让行)
        if (request.header("User-Agent") == null) {
            return chain.proceed(
                request.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        return chain.proceed(request)
    }

    companion object {
        /** 自有 UA — 形态对齐 OC 文档示例 "my-coding-agent/1.0" */
        val USER_AGENT: String = "RinCore/${BuildConfig.VERSION_NAME}"
    }
}
