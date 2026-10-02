/* 【域 G·自动化】 — 子代理预算 | 地图: docs/APP_MAP.md §G */
package me.rerere.rikkahub.subagent

import android.util.Log
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.8.88 — 子代理 Token 预算（按对话计）。
 *
 * 目标：一个对话通过子代理消耗的 Token 不得超过上限（默认 100K），超出即熔断：
 *   ① 终止正在运行的子代理（由引擎调 `cancelAllForParent` + 真停生成）
 *   ② 禁止新的派发（dispatch 前置闸门）
 *   ③ 明确告知模型（拒因文案）
 *
 * 记账口径 = 账单口径：每轮 (promptTokens + completionTokens)，与 SubAgentRegistry 的
 * tokensIn/Out 同源（都来自消息的 usage 字段）。**不做本地 tokenizer 估算** ——
 * 各家 API 已经把真实用量回传，估算只会更差。
 *
 * 存储策略：内存实时增量 + 节流落盘（同一对话最快 10s 一次）。
 *  · 实时增量用于**快速熔断**（不必等落盘）；
 *  · 落盘进 Settings.subagentTokenUsage（DataStore，重启不丢；UI 可清零）；
 *  · 终态/熔断点强制 flush，保证 UI 数字最终一致。
 */
class SubAgentBudget(private val settingsStore: SettingsStore) {

    companion object {
        private const val TAG = "SubAgentBudget"

        /** 落盘节流间隔 */
        private const val FLUSH_INTERVAL_MS = 10_000L

        /** UI 用量表最多保留的对话数（超出丢最早，防无限膨胀） */
        private const val MAX_USAGE_ENTRIES = 256

        /** 上限保护的硬下限（防止误设 0 造成完全不可用） */
        private const val MIN_LIMIT = 1_000L
    }

    /** 未落盘增量：conversationId → tokens（进程内实时） */
    private val live = ConcurrentHashMap<String, Long>()

    /** 上次落盘时刻：conversationId → epoch ms */
    private val lastFlush = ConcurrentHashMap<String, Long>()

    /** 当前上限（默认 100K） */
    fun limit(): Long = settingsStore.settingsFlow.value.subagentTokenBudget.coerceAtLeast(MIN_LIMIT)

    /** 已用 = 落盘值 + 未落盘增量 */
    fun used(conversationId: String): Long =
        (settingsStore.settingsFlow.value.subagentTokenUsage[conversationId] ?: 0L) +
            (live[conversationId] ?: 0L)

    /** 是否已达上限（熔断判据） */
    fun isExhausted(conversationId: String?): Boolean {
        if (conversationId.isNullOrBlank()) return false
        return used(conversationId) >= limit()
    }

    /** 记账（正数）。调用方随后应调 [flushIfDue] / [flush]。 */
    fun add(conversationId: String?, tokens: Long) {
        if (conversationId.isNullOrBlank() || tokens <= 0L) return
        live.merge(conversationId, tokens, Long::plus)
    }

    /** 节流落盘：距上次落盘 ≥10s 才写。 */
    suspend fun flushIfDue(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (now - (lastFlush[conversationId] ?: 0L) < FLUSH_INTERVAL_MS) return
        flush(conversationId)
    }

    /** 强制落盘（终态/熔断点调用）。 */
    suspend fun flush(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        val delta = live.remove(conversationId) ?: return
        lastFlush[conversationId] = System.currentTimeMillis()
        if (delta <= 0L) return
        runCatching {
            settingsStore.update { s ->
                val usedNow = (s.subagentTokenUsage[conversationId] ?: 0L) + delta
                val map = LinkedHashMap(s.subagentTokenUsage)
                map[conversationId] = usedNow
                while (map.size > MAX_USAGE_ENTRIES) {
                    val first = map.keys.firstOrNull() ?: break
                    map.remove(first)
                }
                s.copy(subagentTokenUsage = map)
            }
        }.onFailure {
            Log.w(TAG, "flush failed for $conversationId", it)
            // 落盘失败把增量放回，避免丢账（下次再试）
            live.merge(conversationId, delta, Long::plus)
        }
    }
}
