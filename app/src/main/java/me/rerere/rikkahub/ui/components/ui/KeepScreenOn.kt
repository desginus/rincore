package me.rerere.rikkahub.ui.components.ui

/* ───【原版对齐】KeepScreenOn.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import me.rerere.rikkahub.utils.getActivity

@Composable
fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.getActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
