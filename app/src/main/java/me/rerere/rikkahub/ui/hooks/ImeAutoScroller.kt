package me.rerere.rikkahub.ui.hooks

/* ───【原版对齐】ImeAutoScroller.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    var imeHeigh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { keyboardHeight ->
            if (keyboardHeight > 0) {
                if (imeHeigh < keyboardHeight) {
                    lazyListState.scrollBy((keyboardHeight - imeHeigh).toFloat())
                } else {
                    lazyListState.scrollBy((keyboardHeight - imeHeigh).toFloat())
                }
                imeHeigh = keyboardHeight
            }
        }
    }
}
