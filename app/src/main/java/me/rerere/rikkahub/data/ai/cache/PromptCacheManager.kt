package me.rerere.rikkahub.data.ai.cache

/**
 * 提示词缓存策略 — 按稳定性分层，最大化提供商端前缀缓存命中率。
 *
 * 三层设计:
 *   Tier 1 (极稳定) — 系统提示词 + 记忆 → 跨回合 100% 命中
 *   Tier 2 (半稳定) — 路由表 + 工具定义 → 同配置跨回合命中
 *   Tier 3 (动态)   — 对话历史 + 工具结果 → 不缓存
 *
 * 适配优先级: DeepSeek V4 Pro (基础) > Qwen 3.7 (核心) > Zhipu 5.2 (关键)
 */
enum class CacheTier(val label: String, val stability: Int) {
    /** 系统提示词 — 用户编写的 assistant.systemPrompt，极少变更 */
    SYSTEM("系统提示词", 1),

    /** 记忆上下文 — 仅在 enableMemory 时出现，内容稳定 */
    MEMORY("记忆", 1),

    /** 域路由表 — buildLayer1 输出，工具列表不变时稳定 */
    ROUTING("路由表", 2),

    /** 工具定义 — 始终可用工具的 systemPrompt */
    TOOLS("工具定义", 2),

    /** 对话消息 — 每轮必变 */
    CONVERSATION("对话", 3),
}

/**
 * 提供商缓存配置。
 * 三个目标提供商均使用 OpenAI 兼容 API，自动前缀缓存。
 * 显式标记仅用于支持 cache_control 的提供商（如 Zhipu GLM 5.2）。
 */
data class ProviderCacheConfig(
    /** 是否启用提示词缓存 */
    val enabled: Boolean = false,

    /** 缓存 TTL（秒），仅对显式标记的提供商有效 */
    val ttlSeconds: Int = 300,

    /** 是否在请求中添加显式 cache_control 标记 */
    val explicitMarkers: Boolean = false,
) {
    companion object {
        /** DeepSeek V4 Pro — 自动前缀缓存，无需显式标记 */
        fun deepSeek(enabled: Boolean = true) = ProviderCacheConfig(
            enabled = enabled,
            ttlSeconds = 600,
            explicitMarkers = false,
        )

        /** Qwen 3.7 — 自动前缀缓存 */
        fun qwen(enabled: Boolean = true) = ProviderCacheConfig(
            enabled = enabled,
            ttlSeconds = 600,
            explicitMarkers = false,
        )

        /** Zhipu GLM 5.2 — 支持显式 cache_control */
        fun zhipu(enabled: Boolean = true) = ProviderCacheConfig(
            enabled = enabled,
            ttlSeconds = 600,
            explicitMarkers = true,
        )

        val DISABLED = ProviderCacheConfig(enabled = false)
    }
}

/**
 * 缓存段 — 提示词中一段可缓存的内容
 */
data class CacheSegment(
    val tier: CacheTier,
    val content: String,
    val estimatedTokens: Int,
) {
    /** 此段在完整提示词中的起始 token 偏移 */
    var tokenOffset: Int = 0
}

/**
 * 缓存统计
 */
data class CacheStats(
    /** 可缓存的总 token 数（Tier1 + Tier2） */
    val cacheableTokens: Int = 0,

    /** 实际从缓存命中的 token 数（来自 API 响应） */
    val actualCachedTokens: Int = 0,

    /** 缓存命中率 = actualCached / cacheable */
    val hitRate: Float
        get() = if (cacheableTokens > 0) actualCachedTokens.toFloat() / cacheableTokens else 0f,

    /** 节省的 prompt token 数 */
    val savedPromptTokens: Int
        get() = actualCachedTokens,

    /** 估算节省成本（DeepSeek: cache_hit = $0.014/1M, full_prompt = $0.28/1M） */
    val estimatedSavingsUSD: Float
        get() {
            val savedM = actualCachedTokens / 1_000_000f
            val fullPriceM = 0.28f
            val cachePriceM = 0.014f
            return savedM * (fullPriceM - cachePriceM)
        }
)

/**
 * 提示词缓存管理器 — 构建缓存感知的提示词结构。
 *
 * 原理: 将提示词按 CacheTier 排序后拼接，确保 Tier1 在最前面。
 * 提供商自动前缀缓存会缓存 Tier1+Tier2，每轮只传输 Tier3。
 *
 * 使用:
 *   val cache = PromptCacheManager()
 *   cache.append(CacheTier.SYSTEM, systemPrompt)
 *   cache.append(CacheTier.ROUTING, routingTable)
 *   cache.append(CacheTier.CONVERSATION, messages)
 *   val fullPrompt = cache.build()
 *   val stats = cache.stats
 */
class PromptCacheManager {
    private val segments = mutableListOf<CacheSegment>()

    fun append(tier: CacheTier, content: String) {
        if (content.isBlank()) return
        val estimatedTokens = (content.length / 2.5).toInt()
        segments.add(CacheSegment(tier, content, estimatedTokens))
    }

    fun build(): String {
        // 按 stability 排序: Tier1 → Tier2 → Tier3
        val sorted = segments.sortedBy { it.tier.stability }
        var offset = 0
        val result = buildString {
            for (seg in sorted) {
                seg.tokenOffset = offset
                if (isNotEmpty()) appendLine()
                append(seg.content)
                offset += seg.estimatedTokens
            }
        }
        return result
    }

    val cacheableTokens: Int
        get() = segments
            .filter { it.tier != CacheTier.CONVERSATION }
            .sumOf { it.estimatedTokens }

    val totalTokens: Int
        get() = segments.sumOf { it.estimatedTokens }

    fun makeStats(actualCachedTokens: Int): CacheStats = CacheStats(
        cacheableTokens = cacheableTokens,
        actualCachedTokens = actualCachedTokens,
    )

    /** 构建缓存摘要字符串（用于日志/UI） */
    fun buildCacheSummary(actualCached: Int): String = buildString {
        append("💾 缓存: ${cacheableTokens}t可缓存/${actualCached}t命中")
        if (cacheableTokens > 0) {
            val rate = (actualCached * 100f / cacheableTokens).toInt()
            append(" (${rate}%)")
        }
        val stats = makeStats(actualCached)
        if (stats.estimatedSavingsUSD > 0.0001f) {
            append(" · ~$${String.format("%.4f", stats.estimatedSavingsUSD)}节省")
        }
    }

    fun isEmpty(): Boolean = segments.isEmpty()
}
