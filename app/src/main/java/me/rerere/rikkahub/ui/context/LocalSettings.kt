package me.rerere.rikkahub.ui.context

/* ───【原版对齐】LocalSettings.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.runtime.staticCompositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings

val LocalSettings = staticCompositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
