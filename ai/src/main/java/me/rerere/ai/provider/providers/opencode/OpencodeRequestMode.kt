package me.rerere.ai.provider.providers.opencode

import me.rerere.ai.provider.ProviderSetting

/* ───【自研】OpencodeRequestMode.kt | 仿 OpenCode 请求模式 (v4.5.17)
 * 数据源: models.dev api.json (OpenCode 客户端同源模型库), 提取
 * OpenCode Zen (opencode.ai/zen/v1) 与 OpenCode Go (opencode.ai/zen/go/v1)
 * 全部模型的传输协议 (per-model npm 字段)。
 * 对齐语义: OpenCode 客户端按每模型 npm 决定协议 (openai-compatible →
 * Chat Completions; openai → Responses; anthropic → Messages;
 * google → generateContent), 本表复刻同一映射。
 * 未知模型回退: 按模型名前缀启发式 (与两网关已知分布一致, 保守默认 CC)。
 * ───────────────────────────────────────────────────────────────*/

/** OpenCode 网关模型的传输协议 (对齐 models.dev npm → AI SDK 包) */
enum class OpencodeProtocol {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC,
    GOOGLE,
}

object OpencodeRequestMode {

    /** OpenCode Zen 网关 host 判定 */
    fun isOpencodeGateway(host: String): Boolean = host == "opencode.ai"

    /** 网关分表: zen/go (baseUrl 含 /zen/go/ 走 Go 表, 其余 zen) */
    fun isGoGateway(baseUrl: String): Boolean =
        baseUrl.contains("/zen/go", ignoreCase = true)

    private val ZEN: Map<String, OpencodeProtocol> = mapOf(
            "big-pickle" to OpencodeProtocol.CHAT_COMPLETIONS,
            "claude-3-5-haiku" to OpencodeProtocol.ANTHROPIC,
            "claude-fable-5" to OpencodeProtocol.ANTHROPIC,
            "claude-fable-5-1" to OpencodeProtocol.ANTHROPIC,
            "claude-haiku-4-5" to OpencodeProtocol.ANTHROPIC,
            "claude-opus-4-1" to OpencodeProtocol.ANTHROPIC,
            "claude-opus-4-5" to OpencodeProtocol.ANTHROPIC,
            "claude-opus-4-6" to OpencodeProtocol.ANTHROPIC,
            "claude-opus-4-7" to OpencodeProtocol.ANTHROPIC,
            "claude-opus-4-8" to OpencodeProtocol.ANTHROPIC,
            "claude-opus-5" to OpencodeProtocol.ANTHROPIC,
            "claude-sonnet-4" to OpencodeProtocol.ANTHROPIC,
            "claude-sonnet-4-5" to OpencodeProtocol.ANTHROPIC,
            "claude-sonnet-4-6" to OpencodeProtocol.ANTHROPIC,
            "claude-sonnet-5" to OpencodeProtocol.ANTHROPIC,
            "deepseek-v4-flash" to OpencodeProtocol.CHAT_COMPLETIONS,
            "deepseek-v4-flash-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "deepseek-v4-flash-vision-exp" to OpencodeProtocol.CHAT_COMPLETIONS,
            "deepseek-v4-pro" to OpencodeProtocol.CHAT_COMPLETIONS,
            "gemini-3-flash" to OpencodeProtocol.GOOGLE,
            "gemini-3-pro" to OpencodeProtocol.GOOGLE,
            "gemini-3.1-pro" to OpencodeProtocol.GOOGLE,
            "gemini-3.5-flash" to OpencodeProtocol.GOOGLE,
            "gemini-3.5-flash-lite" to OpencodeProtocol.GOOGLE,
            "gemini-3.6-flash" to OpencodeProtocol.GOOGLE,
            "gemini-3.7-flash" to OpencodeProtocol.GOOGLE,
            "gemini-3.8-flash" to OpencodeProtocol.GOOGLE,
            "glm-4.6" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-4.7" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-4.7-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.1" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.2" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.3" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.3-flash" to OpencodeProtocol.CHAT_COMPLETIONS,
            "gpt-5" to OpencodeProtocol.RESPONSES,
            "gpt-5-codex" to OpencodeProtocol.RESPONSES,
            "gpt-5-nano" to OpencodeProtocol.RESPONSES,
            "gpt-5.1" to OpencodeProtocol.RESPONSES,
            "gpt-5.1-codex" to OpencodeProtocol.RESPONSES,
            "gpt-5.1-codex-max" to OpencodeProtocol.RESPONSES,
            "gpt-5.1-codex-mini" to OpencodeProtocol.RESPONSES,
            "gpt-5.2" to OpencodeProtocol.RESPONSES,
            "gpt-5.2-codex" to OpencodeProtocol.RESPONSES,
            "gpt-5.3-codex" to OpencodeProtocol.RESPONSES,
            "gpt-5.3-codex-spark" to OpencodeProtocol.RESPONSES,
            "gpt-5.4" to OpencodeProtocol.RESPONSES,
            "gpt-5.4-mini" to OpencodeProtocol.RESPONSES,
            "gpt-5.4-nano" to OpencodeProtocol.RESPONSES,
            "gpt-5.4-pro" to OpencodeProtocol.RESPONSES,
            "gpt-5.5" to OpencodeProtocol.RESPONSES,
            "gpt-5.5-pro" to OpencodeProtocol.RESPONSES,
            "gpt-5.6-luna" to OpencodeProtocol.RESPONSES,
            "gpt-5.6-sol" to OpencodeProtocol.RESPONSES,
            "gpt-5.6-terra" to OpencodeProtocol.RESPONSES,
            "gpt-6-astra" to OpencodeProtocol.RESPONSES,
            "grok-4.5" to OpencodeProtocol.RESPONSES,
            "grok-4.6" to OpencodeProtocol.RESPONSES,
            "grok-build-0.1" to OpencodeProtocol.RESPONSES,
            "grok-code" to OpencodeProtocol.CHAT_COMPLETIONS,
            "hy3-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "hy3-preview-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2-thinking" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.5" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.5-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.6" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.7-code" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k3" to OpencodeProtocol.CHAT_COMPLETIONS,
            "laguna-s-2.1-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "ling-2.6-flash-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "ling-3.0-flash-fin-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "ling-3.0-flash-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "ling-3.0-tiny-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "longcat-2.0-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2-flash-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2-omni-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2-pro-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2.5-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "minimax-m2.1" to OpencodeProtocol.CHAT_COMPLETIONS,
            "minimax-m2.1-free" to OpencodeProtocol.ANTHROPIC,
            "minimax-m2.5" to OpencodeProtocol.CHAT_COMPLETIONS,
            "minimax-m2.5-free" to OpencodeProtocol.ANTHROPIC,
            "minimax-m2.7" to OpencodeProtocol.CHAT_COMPLETIONS,
            "minimax-m3" to OpencodeProtocol.CHAT_COMPLETIONS,
            "minimax-m3-free" to OpencodeProtocol.ANTHROPIC,
            "muse-spark-1.2" to OpencodeProtocol.RESPONSES,
            "muse-spark-1.2-contributor-free" to OpencodeProtocol.RESPONSES,
            "muse-spark-1.3" to OpencodeProtocol.RESPONSES,
            "muse-spark-1.3-contributor-free" to OpencodeProtocol.RESPONSES,
            "nemotron-3-super-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "nemotron-3-ultra-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "nemotron-3.5-lightning-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "north-mini-code-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3-coder" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3.5-plus" to OpencodeProtocol.ANTHROPIC,
            "qwen3.6-plus" to OpencodeProtocol.ANTHROPIC,
            "qwen3.6-plus-free" to OpencodeProtocol.ANTHROPIC,
            "ring-2.6-1t-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "trinity-large-preview-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "union-alpha" to OpencodeProtocol.ANTHROPIC,
            "x-preview-f-free" to OpencodeProtocol.CHAT_COMPLETIONS,
    )

    private val ZEN_GO: Map<String, OpencodeProtocol> = mapOf(
            "deepseek-v4-flash" to OpencodeProtocol.CHAT_COMPLETIONS,
            "deepseek-v4-flash-vision-exp" to OpencodeProtocol.CHAT_COMPLETIONS,
            "deepseek-v4-pro" to OpencodeProtocol.CHAT_COMPLETIONS,
            "deepseek-v4.1-flash" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.1" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.2" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.3" to OpencodeProtocol.CHAT_COMPLETIONS,
            "glm-5.3-flash" to OpencodeProtocol.CHAT_COMPLETIONS,
            "gpt-5.6-luna" to OpencodeProtocol.RESPONSES,
            "grok-4.5" to OpencodeProtocol.RESPONSES,
            "grok-4.6" to OpencodeProtocol.RESPONSES,
            "hy3" to OpencodeProtocol.CHAT_COMPLETIONS,
            "hy4-preview" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.5" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.6" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k2.7-code" to OpencodeProtocol.CHAT_COMPLETIONS,
            "kimi-k3" to OpencodeProtocol.CHAT_COMPLETIONS,
            "longcat-2.0" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2-omni" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2-pro" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2.5" to OpencodeProtocol.CHAT_COMPLETIONS,
            "mimo-v2.5-pro" to OpencodeProtocol.CHAT_COMPLETIONS,
            "minimax-m2.5" to OpencodeProtocol.ANTHROPIC,
            "minimax-m2.7" to OpencodeProtocol.ANTHROPIC,
            "minimax-m3" to OpencodeProtocol.ANTHROPIC,
            "muse-spark-1.2-contributor" to OpencodeProtocol.RESPONSES,
            "muse-spark-1.3-contributor" to OpencodeProtocol.RESPONSES,
            "omen-alpha" to OpencodeProtocol.CHAT_COMPLETIONS,
            "ox-alpha-free" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3.5-plus" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3.6-plus" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3.7-max" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3.7-plus" to OpencodeProtocol.CHAT_COMPLETIONS,
            "qwen3.8-flash" to OpencodeProtocol.ANTHROPIC,
            "qwen3.8-max" to OpencodeProtocol.CHAT_COMPLETIONS,
            "union-alpha" to OpencodeProtocol.ANTHROPIC,
    )

    /**
     * 统一入口: 非 opencode.ai 网关返回 null (调用方走原路径);
     * 是 opencode.ai 时返回该模型的目标协议。
     */
    fun resolveFor(base: ProviderSetting, modelId: String): OpencodeProtocol? {
        val baseUrl = baseUrlOf(base)
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: return null
        if (!isOpencodeGateway(host)) return null
        return resolve(baseUrl, modelId)
    }

    /**
     * 模型 → 协议。已知模型查表; 未知模型按前缀启发式兜底
     * (claude→Anthropic, gpt-/codex/grok-4.5+/muse-spark→Responses,
     * gemini→Google, 其余 Chat Completions)。
     */
    fun resolve(baseUrl: String, modelId: String): OpencodeProtocol {
        val table = if (isGoGateway(baseUrl)) ZEN_GO else ZEN
        table[modelId]?.let { return it }
        return fallbackByPrefix(modelId)
    }

    /**
     * 从用户配置的 provider 提取公共连接信息 (apiKey/baseUrl/密钥轮换)。
     * 各网关路径字段 (chatCompletionsPath/responsesPath) 由目标 handler 默认值承担;
     * opencode 场景 baseUrl 语义统一为 "https://opencode.ai/zen/v1" 前缀。
     */
    private fun baseUrlOf(s: ProviderSetting): String = when (s) {
        is ProviderSetting.OpenAI -> s.baseUrl
        is ProviderSetting.Claude -> s.baseUrl
        is ProviderSetting.Google -> s.baseUrl
        else -> ""
    }

    private fun apiKeyOf(s: ProviderSetting): String = when (s) {
        is ProviderSetting.OpenAI -> s.apiKey
        is ProviderSetting.Claude -> s.apiKey
        is ProviderSetting.Google -> s.apiKey
        else -> ""
    }

    /** 仿 OpenCode: Anthropic Messages 协议设置 (对齐 @ai-sdk/anthropic 路径 {baseUrl}/messages) */
    fun toClaudeSetting(base: ProviderSetting): ProviderSetting.Claude =
        ProviderSetting.Claude(
            id = base.id,
            name = base.name,
            apiKey = apiKeyOf(base),
            baseUrl = baseUrlOf(base),
            promptCaching = false,
            savedKeys = base.savedKeys,
        )

    /** 仿 OpenCode: Google generateContent 协议设置 (对齐 @ai-sdk/google 路径 {baseUrl}/models/{model}:streamGenerateContent) */
    fun toGoogleSetting(base: ProviderSetting): ProviderSetting.Google =
        ProviderSetting.Google(
            id = base.id,
            name = base.name,
            apiKey = apiKeyOf(base),
            baseUrl = baseUrlOf(base),
            savedKeys = base.savedKeys,
        )

    /** 仿 OpenCode: Responses 协议设置 (对齐 @ai-sdk/openai → {baseUrl}{responsesPath}) */
    fun toResponseSetting(base: ProviderSetting): ProviderSetting.OpenAI =
        ProviderSetting.OpenAI(
            id = base.id,
            name = base.name,
            apiKey = apiKeyOf(base),
            baseUrl = baseUrlOf(base),
            useResponseApi = true,
            savedKeys = base.savedKeys,
        )

    private fun fallbackByPrefix(modelId: String): OpencodeProtocol {
        val id = modelId.lowercase()
        return when {
            id.contains("claude") -> OpencodeProtocol.ANTHROPIC
            id.contains("gemini") -> OpencodeProtocol.GOOGLE
            id.startsWith("gpt-") || id.contains("codex") || id.contains("muse-spark") -> OpencodeProtocol.RESPONSES
            id == "grok-4.5" || id == "grok-4.6" -> OpencodeProtocol.RESPONSES
            else -> OpencodeProtocol.CHAT_COMPLETIONS
        }
    }
}
