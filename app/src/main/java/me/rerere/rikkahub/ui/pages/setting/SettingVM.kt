/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

/* ───【原版对齐】SettingVM.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * v4.8.89 工具矩阵**事务入口**（UI 侧唯一写路径）。
     *
     * 与旧写法的本质区别：
     *  · 旧：`vm.updateSettings(settings.copy(...))` —— 拿**捕获时的整份快照**去覆盖，
     *    一次动作里写两次就互相顶掉（假成功），并发写还会互相回退（丢更新）。
     *  · 新：把操作描述成 `(当前设置) -> ZoneOps.Res`，在锁内**以当前值为基线**施加，
     *    整次动作只写一次；写完对**刚施加的值**做写后校验，回执由事实生成。
     */
    fun applyZoneOp(op: (Settings) -> me.rerere.rikkahub.data.ai.tools.routing.ZoneOps.Res, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val res = settingsStore.updateWithResult { s ->
                val r = op(s)
                r.settings to r
            }
            // updateWithResult 返回时内存与磁盘都已落定 —— 对返回的施加值判定即可，
            // 不再重读 settingsFlow（重读会被并发写夹层误判成"校验未通过"）。
            val verified = res.verify?.invoke(res.settings) ?: true
            onDone(
                when {
                    !res.ok -> "操作未生效：${res.message}"   // 校验失败：文案本身已说明原因
                    !verified -> "操作未生效（写后校验未通过）：${res.message}"
                    else -> res.message
                }
            )
        }
    }
}
