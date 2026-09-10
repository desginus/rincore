package me.rerere.rikkahub.ui.context

/* ───【原版对齐】LocalTTSState.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkahub.ui.hooks.CustomTtsState

val LocalTTSState = compositionLocalOf<CustomTtsState> { error("Not provided yet") }
