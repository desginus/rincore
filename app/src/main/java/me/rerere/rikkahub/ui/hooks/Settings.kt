package me.rerere.rikkahub.ui.hooks

/* ───【原版对齐】Settings.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

@Composable
fun rememberUserSettingsState(): State<Settings> {
    val store = koinInject<SettingsStore>()
    return store.settingsFlow.collectAsStateWithLifecycle(
        initialValue = Settings.dummy(),
    )
}
