package me.rerere.rikkahub.data.ai

/* ───【原版对齐】AIRequestInterceptor.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import okhttp3.Interceptor
import okhttp3.Response

class AIRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request)
    }
}
