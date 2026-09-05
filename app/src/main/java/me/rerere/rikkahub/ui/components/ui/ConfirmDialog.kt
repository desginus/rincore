package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.rerere.rikkahub.ui.components.motion.HyperDialog

/**
 * 全局确认弹窗 — 4.0.2 澎湃 OS 4 动效统一 (HyperDialog 弹性入场+玻璃面板)。
 * 签名与旧版完全一致, 调用方零改动。
 */
@Composable
fun RikkaConfirmDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: @Composable () -> Unit,
) {
    if (!show) {
        return
    }

    HyperDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = text,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
