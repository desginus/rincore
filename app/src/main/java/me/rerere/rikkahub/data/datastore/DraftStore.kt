/* 【域 I·数据存储】 — 会话输入草稿 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * v4.8.70: 会话输入草稿持久化 — 每会话一条 (key = conversationId)。
 * 生命周期: 输入变化 1s 防抖落盘 (ChatVM collectLatest 取消式) + onCleared 兜底 flush;
 * 发送后文本为空 → save("") 自动转为清除; VM 重建/进程重启后恢复 (仅当输入为空时注入)。
 * 独立 DataStore 文件 (settings 库不掺动态 key); 草稿属可丢数据, 损坏静默回退空。
 */
class DraftStore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) {
        context.preferencesDataStoreFile("drafts")
    }

    private fun key(id: Uuid) = stringPreferencesKey("draft_" + id.toString())

    suspend fun load(id: Uuid): String = runCatching {
        store.data.catch { emit(emptyPreferences()) }.first()[key(id)] ?: ""
    }.getOrDefault("")

    suspend fun save(id: Uuid, text: String) {
        if (text.isEmpty()) {
            clear(id)
            return
        }
        runCatching { store.edit { it[key(id)] = text } }
    }

    /** 非挂起兜底落盘 (onCleared 等不可挂起现场; 发送后为空串即清除) */
    fun saveNow(id: Uuid, text: String) {
        scope.launch { runCatching { save(id, text) } }
    }

    suspend fun clear(id: Uuid) {
        runCatching { store.edit { it.remove(key(id)) } }
    }
}
