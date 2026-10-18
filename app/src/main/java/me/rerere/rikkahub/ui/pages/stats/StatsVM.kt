/* 【域 I·数据存储】 — 页面 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.ui.pages.stats

/* ───【原版对齐 + v4.8.96 即开优化】StatsVM.kt
 * 基线: 原版 2.5.6 (计算逻辑逐字节对齐, 抽至 StatsCache)
 * v4.8.96 (用户定版): 缓存直出 —— 点开即统计页, 不再有加载过程;
 *                     计算走 StatsCache (抽屉预热共用), 后台刷新覆盖。
 * ───────────────────────────────────────────────────────────────*/

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.time.LocalDate

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    init {
        // v4.8.96: 缓存直出 — 有上次结果 (含抽屉预热结果) 先秒显, 随后后台刷新覆盖
        StatsCache.cached?.let { _stats.value = it }
        viewModelScope.launch {
            delay(50)
            StatsCache.refresh(conversationDAO, messageNodeDAO, settingsStore)?.let { _stats.value = it }
        }
    }
}
