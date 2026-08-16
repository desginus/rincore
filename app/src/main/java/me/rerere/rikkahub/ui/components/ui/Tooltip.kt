package me.rerere.rikkahub.ui.components.ui


/* ───【原版对齐】Tooltip.kt | 差异 ±3 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Tooltip(
    modifier: Modifier = Modifier,
    tooltip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        modifier = modifier,
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                tooltip()
            }
        },
        state = rememberTooltipState()
    ) {
        content()
    }
}
