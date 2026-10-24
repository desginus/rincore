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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
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

/** v5.0.80 Token 用量时间范围（对齐 3.1.0：7 天/30 天/90 天/全部） */
enum class StatsRange(val days: Int) {
    D7(7), D30(30), D90(90), ALL(0)
}

/** v5.0.80 Token 用量区状态：总计 + 按模型 + 按来源 */
data class TokenUsageStats(
    val range: StatsRange = StatsRange.D30,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val byModel: List<me.rerere.rikkahub.data.db.dao.ModelUsage> = emptyList(),
    val bySource: List<me.rerere.rikkahub.data.db.dao.SourceUsage> = emptyList(),
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    // v5.0.80：Token 用量（时间窗 + 分组）
    private val _usage = MutableStateFlow(TokenUsageStats())
    val usage = _usage.asStateFlow()

    init {
        // v4.8.96: 缓存直出 — 有上次结果 (含抽屉预热结果) 先秒显, 随后后台刷新覆盖
        StatsCache.cached?.let { _stats.value = it }
        viewModelScope.launch {
            delay(50)
            StatsCache.refresh(conversationDAO, messageNodeDAO, settingsStore)?.let { _stats.value = it }
        }
        loadUsage(StatsRange.D30)
    }

    /** 切换时间范围并重查（DAO 查询全部带 since 参数，分桶不适用这里） */
    fun setUsageRange(range: StatsRange) {
        if (_usage.value.range == range) return
        loadUsage(range)
    }

    private fun loadUsage(range: StatsRange) {
        viewModelScope.launch {
            val since = if (range.days == 0) 0L
            else System.currentTimeMillis() - range.days * 24L * 60 * 60 * 1000
            val settings = settingsStore.settingsFlow.first()
            // v5.0.85 ★Token 用量乱码根修：迁移 33→34 回填历史时把 modelId(UUID) 写进了
            // model_name（`json_extract($.modelSnapshot.modelId)`）→ UI 显示一串 UUID。
            // 这里统一解析成当前配置里的 displayName；已删除的模型显示「未知模型」。
            val modelNames = settings.providers
                .flatMap { it.models }
                .associate { it.id.toString() to it.displayName.ifBlank { it.modelId }.ifBlank { "模型" } }
            val totals = usageRecordDAO.getTotals(since)
            _usage.value = TokenUsageStats(
                range = range,
                promptTokens = totals.promptTokens,
                completionTokens = totals.completionTokens,
                cachedTokens = totals.cachedTokens,
                byModel = usageRecordDAO.getTotalsByModel(since).map { m ->
                    val raw = m.modelName
                    val resolved = when {
                        !raw.isNullOrBlank() && UUID_PATTERN.matches(raw) ->
                            modelNames[raw] ?: "未知模型"
                        !raw.isNullOrBlank() -> raw
                        else -> m.modelId?.let { modelNames[it] ?: "未知模型" } ?: "未知模型"
                    }
                    m.copy(modelName = resolved)
                },
                bySource = usageRecordDAO.getTotalsBySource(since).map { s ->
                    s.copy(source = sourceLabel(s.source))
                },
            )
        }
    }

    private fun sourceLabel(source: String): String = when (source.uppercase()) {
        "CHAT" -> "聊天"
        "TOOL" -> "工具"
        "SUBAGENT" -> "子代理"
        "IMAGE", "IMGGEN" -> "图片生成"
        "TRANSLATE" -> "翻译"
        "MEMORY" -> "记忆整理"
        "TTS" -> "语音"
        "RERANK" -> "重排"
        else -> source
    }

    private companion object {
        val UUID_PATTERN = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )
    }
}
