/* 【域 I·数据存储】 — 页面 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.ui.pages.stats

/* ───【原版对齐 + v4.8.96 即开优化】StatsCache.kt
 * 来源: 原版 2.5.6 StatsVM 计算逻辑抽出 + 自研进程级缓存/预热
 * ───────────────────────────────────────────────────────────────*/

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * v4.8.96: 统计结果进程级缓存 ——
 * ① 抽屉打开瞬间预热 (ChatPage 钩子) 后台先算一次;
 * ② 统计页进入时缓存直出秒显 (无缓存才走加载态), 随后后台刷新覆盖。
 */
object StatsCache {
    /** 复用窗口: 该时长内的结果视为新鲜, 抽屉预热与页面 VM 不重复全量计算。 */
    private const val FRESH_MS = 10_000L

    @Volatile
    var cached: AppStats? = null
        private set

    private var cachedAt = 0L
    private val mutex = Mutex()

    /**
     * 计算一次完整统计并写入缓存 (串行化: 并发调用等锁, 等到的若是新鲜结果直接复用)。
     * 计算失败时返回现有缓存 (保留旧展示); 无缓存且失败返回 null (页面保持加载态, 下次再试)。
     */
    suspend fun refresh(
        conversationDAO: ConversationDAO,
        messageNodeDAO: MessageNodeDAO,
        settingsStore: SettingsStore,
    ): AppStats? = mutex.withLock {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < FRESH_MS) return@withLock it }
        try {
            computeAppStats(conversationDAO, messageNodeDAO, settingsStore).also {
                cached = it
                cachedAt = System.currentTimeMillis()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached
        }
    }

    /** 与 2.5.6 StatsVM.loadStats 同口径的统计计算 (去掉 delay/isLoading)。 */
    suspend fun computeAppStats(
        conversationDAO: ConversationDAO,
        messageNodeDAO: MessageNodeDAO,
        settingsStore: SettingsStore,
    ): AppStats {
        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        return AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
        )
    }
}
