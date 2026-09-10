package me.rerere.rikkahub.ui.components.ui

/* ───【原版对齐】StickyHeader.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun StickyHeader(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        ProvideTextStyle(
            MaterialTheme.typography.titleSmall.copy(
                color = MaterialTheme.colorScheme.secondary
            )
        ) {
            content()
        }
    }
}
