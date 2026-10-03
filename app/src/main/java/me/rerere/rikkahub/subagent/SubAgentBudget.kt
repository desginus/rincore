/* 【域 G·自动化】 — 子代理预算 | 地图: docs/APP_MAP.md §G */
package me.rerere.rikkahub.subagent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore
import java.util.concurrent.ConcurrentHashMap

/**
 * v4.8.88 — 子代理 Token 预算（按对话计）。v4.8.92 复核修整：
 *
 * 目标：一个对话通过子代理消耗的 Token 不得超过上限（默认 100K），超出即熔断：
 *   ① 终止正在运行的子代理（由引擎调 `cancelAllForParent` + 真停生成）
 *   ② 禁止新的派发（dispatch 前置闸门）
 *   ③ 明确告知模型（拒因文案）
 *
 * 记账口径 = 账单口径：每轮 (promptTokens + completionTokens)，与 SubAgentRegistry 的
 * tokensIn/Out 同源（都来自消息的 usage 字段）。**不做本地 tokenizer 估算**。
 *
 * 存储策略：内存实时增量（StateFlow，UI 可实时观察）+ 节流落盘（同对话最快 10s 一次）。
 *  · 实时增量用于**快速熔断**（不必等落盘）；
 *  · 落盘进 Settings.subagentTokenUsage（DataStore，重启不丢；UI 可清零）；
 *  · 终态/熔断点强制 flush，保证 UI 数字最终一致。
 *
 * v4.8.92 修复两处实质问题：
 *  1. 「清零本对话」此前只删落盘值，**未落盘增量（live）仍卡着闸门** —— 界面显示 0
 *     但预算依旧算已用尽（假清零）。现在 [reset] 把两者一起清干净；
 *  2. 展示口径此前只读落盘值（跑动中的子代理数字不动）—— live 改为 StateFlow，
 *     UI 用「落盘 + live」显示真实进度。
 *  另：flush/reset 用互斥锁串行化，防并发双写重复记账（旧实现靠 remove 原子性，改造后补回）。
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

    /** 未落盘增量：conversationId → tokens（进程内实时；StateFlow 供 UI 观察） */
    private val liveUsage = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** 供 UI 展示未落盘增量（真实已用 = 落盘值 + 本表值） */
    val liveUsageFlow: StateFlow<Map<String, Long>> = liveUsage.asStateFlow()

    /** 上次落盘时刻：conversationId → epoch ms */
    private val lastFlush = ConcurrentHashMap<String, Long>()

    /** flush / reset 串行化（防并发双写重复记账） */
    private val ledgerMutex = Mutex()

    /** 当前上限（默认 100K） */
    fun limit(): Long = settingsStore.settingsFlow.value.subagentTokenBudget.coerceAtLeast(MIN_LIMIT)

    /** 已用 = 落盘值 + 未落盘增量 */
    fun used(conversationId: String): Long =
        (settingsStore.settingsFlow.value.subagentTokenUsage[conversationId] ?: 0L) +
            (liveUsage.value[conversationId] ?: 0L)

    /** 是否已达上限（熔断判据） */
    fun isExhausted(conversationId: String?): Boolean {
        if (conversationId.isNullOrBlank()) return false
        return used(conversationId) >= limit()
    }

    /** 记账（正数）。调用方随后应调 [flushIfDue] / [flush]。 */
    fun add(conversationId: String?, tokens: Long) {
        if (conversationId.isNullOrBlank() || tokens <= 0L) return
        liveUsage.update { m -> m + (conversationId to ((m[conversationId] ?: 0L) + tokens)) }
    }

    /** 节流落盘：距上次落盘 ≥10s 才写。 */
    suspend fun flushIfDue(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        if (now - (lastFlush[conversationId] ?: 0L) < FLUSH_INTERVAL_MS) return
        flush(conversationId)
    }

    /** 强制落盘（终态/熔断点调用）。成功后才把增量从 live 里扣掉（失败保留，下次再试）。 */
    suspend fun flush(conversationId: String?) {
        if (conversationId.isNullOrBlank()) return
        ledgerMutex.withLock {
            val delta = liveUsage.value[conversationId] ?: return
            if (delta <= 0L) return
            lastFlush[conversationId] = System.currentTimeMillis()
            val ok = runCatching {
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
            }.isSuccess
            if (ok) {
                liveUsage.update { m -> m + (conversationId to ((m[conversationId] ?: 0L) - delta)) }
            } else {
                Log.w(TAG, "flush failed for $conversationId (delta kept in live)")
            }
        }
    }

    /**
     * v4.8.92: 「清零本对话」—— 未落盘增量 + 已落盘值**一起清**。
     * 此前只清落盘值：界面上显示 0，但闸门仍按 live 旧增量判"已用尽"（假清零）。
     * 语义：只影响这一个对话的计数；上限（limit）对所有对话生效，不在这里动。
     */
    suspend fun reset(conversationId: String) {
        if (conversationId.isBlank()) return
        ledgerMutex.withLock {
            liveUsage.update { it - conversationId }
            lastFlush.remove(conversationId)
            runCatching {
                settingsStore.update { s ->
                    s.copy(subagentTokenUsage = s.subagentTokenUsage - conversationId)
                }
            }.onFailure { Log.w(TAG, "reset persisted failed for $conversationId", it) }
        }
    }
}
