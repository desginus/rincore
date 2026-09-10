package me.rerere.rikkahub.ui.components.ui

/* ───【原版对齐】ListSelectableItem.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ListSelectableItem(
    key: Any,
    selectedKeys: List<Any>,
    onSelectChange: (Any) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (enabled) {
            Checkbox(
                checked = key in selectedKeys,
                onCheckedChange = {
                    onSelectChange(key)
                }
            )
        }
        Box(
            modifier = Modifier.weight(1f)
        ) {
            content()
        }
    }
}
