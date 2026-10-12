/* 【域 B·AI 传输】 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai

/* ───【原版对齐】AIRequestInterceptor.kt | 原版 2.5.1 为空壳直通
 * v4.8.126→v4.8.127 差异 (网关客户端身份伪装, 用户定版: "不要暴露我们自己"):
 * 不注入自有 UA; 按目标网关伪装成其已验证客户端 —
 *  - opencode.ai → `opencode/latest/1.19.0/cli` (OC 官方 CLI 真实 UA 格式,
 *    模板取自 anomalyco/opencode build.ts `--user-agent=opencode/<channel>/
 *    <version>/cli`; channel=latest 为 npm 发布 dist-tag, version=1.19.0 为
 *    当前发布版 — 与官方安装同款身份; Console 读取 UA 识别"哪个 OpenCode");
 *  - 其余 AI 请求 → `codex_cli_rs/0.162.1 (Linux 6.8.0; x86_64)` (Codex CLI
 *    真实 UA 格式, 取自 openai/codex login/auth/default_client.rs
 *    get_codex_user_agent; 该身份为各网关广泛认可的编码代理客户端)。
 * 判别面: 仅"AI 请求"注入 (OC host, 或携带 Bearer / x-api-key / x-goog-api-key
 * 认证头); 搜索/WebDAV/下载等非 AI 流量与显式自设 UA 的调用方 (市场/Opera
 * 导入/CC 用量接口等) 零影响。
 * 装配面: 主 client + proxy client (既有) + opencode/claude 长保活 client。
 * ───────────────────────────────────────────────────────────────*/

import okhttp3.Interceptor
import okhttp3.Response

class AIRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 调用方显式设置 UA 一律让行 (市场/Opera 导入/CC 用量接口等)
        if (request.header("User-Agent") != null) return chain.proceed(request)

        val host = request.url.host
        val ua = when {
            // OC 网关: 伪装官方客户端 (与官方安装同款身份)
            host == "opencode.ai" -> OPENCODE_UA
            // 其余 AI 提供商: codex 通用身份 (按 AI 认证头判别, 非 AI 流量不碰)
            request.header("Authorization")?.startsWith("Bearer ") == true ||
                request.header("x-api-key") != null ||
                request.header("x-goog-api-key") != null -> CODEX_UA
            else -> return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header("User-Agent", ua).build())
    }

    companion object {
        /** 伪装 = OC 官方 CLI 真实形态 `opencode/<channel>/<version>/cli`
         *  (channel=latest / version=1.19.0 = 当前 npm 发布实值; 模板源 anomalyco/opencode) */
        const val OPENCODE_UA = "opencode/latest/1.19.0/cli"

        /** 伪装 = Codex CLI 真实形态 `codex_cli_rs/<version> (<os> <ver>; <arch>)`
         *  (格式源 openai/codex login/auth/default_client.rs) */
        const val CODEX_UA = "codex_cli_rs/0.162.1 (Linux 6.8.0; x86_64)"
    }
}
