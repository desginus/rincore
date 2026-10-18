/* 【自研】RinDevPanel.kt — 开发者调试面板（仅开发者版可见；全部选项实时作用于真实输入栏） */

package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import kotlin.math.roundToInt

/**
 * v4.8.179 (用户定版): 开发者调试面板 — 把"能用的选项"全部收在一处, 全部实时作用于真实输入栏。
 * 仅开发者版可见 (入口 = 主页面顶栏"更多/新增对话"左侧 Bug 图标; BuildConfig.DEVELOPER_BUILD 门控)。
 */
@Composable
fun RinDevPanel(
    current: DisplaySetting,
    onUpdate: (DisplaySetting) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .heightIn(max = 430.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dev_panel_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dev_panel_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, contentDescription = null)
                }
            }
            // 模糊半径（回归: 多少 dp 可调）
            DevSliderRow(
                label = stringResource(R.string.setting_glass_radius_title),
                valueText = "${current.glassBlurRadiusDp}dp",
                value = current.glassBlurRadiusDp.toFloat(),
                range = 2f..24f,
                steps = 21,
            ) { v -> onUpdate(current.copy(glassBlurRadiusDp = v.roundToInt())) }
            // 噪点（作用于最底层玻璃片 = 先噪点; 顶层模糊会把它糊开）
            DevSliderRow(
                label = stringResource(R.string.setting_glass_noise_title),
                valueText = "${current.glassNoisePercent}%",
                value = current.glassNoisePercent.toFloat(),
                range = 0f..100f,
                steps = 99,
            ) { v -> onUpdate(current.copy(glassNoisePercent = v.roundToInt())) }
            // 蒙版浓度
            DevSliderRow(
                label = stringResource(R.string.setting_glass_mask_title),
                valueText = "${current.glassMaskPercent}%",
                value = current.glassMaskPercent.toFloat(),
                range = 0f..100f,
                steps = 99,
            ) { v -> onUpdate(current.copy(glassMaskPercent = v.roundToInt())) }
            // 蒙版颜色
            Column(modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    text = stringResource(R.string.setting_glass_mask_color_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = current.glassMaskColorMode == 0,
                        onClick = { onUpdate(current.copy(glassMaskColorMode = 0)) },
                        label = { Text(stringResource(R.string.setting_glass_mask_color_theme)) },
                    )
                    FilterChip(
                        selected = current.glassMaskColorMode == 1,
                        onClick = { onUpdate(current.copy(glassMaskColorMode = 1)) },
                        label = { Text(stringResource(R.string.setting_glass_mask_color_dark)) },
                    )
                    FilterChip(
                        selected = current.glassMaskColorMode == 2,
                        onClick = { onUpdate(current.copy(glassMaskColorMode = 2)) },
                        label = { Text(stringResource(R.string.setting_glass_mask_color_light)) },
                    )
                }
            }
            // 雾化
            DevSliderRow(
                label = stringResource(R.string.setting_glass_fog_title),
                valueText = "${current.glassFogPercent}%",
                value = current.glassFogPercent.toFloat(),
                range = 0f..100f,
                steps = 99,
            ) { v -> onUpdate(current.copy(glassFogPercent = v.roundToInt())) }
            // 描边粗细（默认 0.5dp = 尽可能细）
            DevSliderRow(
                label = stringResource(R.string.setting_glass_line_width_title),
                valueText = "%.1f".format(current.glassLineWidthDp) + "dp",
                value = current.glassLineWidthDp,
                range = 0.5f..2.0f,
                steps = 14,
            ) { v -> onUpdate(current.copy(glassLineWidthDp = v)) }
            // 描边亮度（默认 55% = 不刺眼）
            DevSliderRow(
                label = stringResource(R.string.setting_glass_line_brightness_title),
                valueText = "${current.glassLineBrightness}%",
                value = current.glassLineBrightness.toFloat(),
                range = 0f..100f,
                steps = 99,
            ) { v -> onUpdate(current.copy(glassLineBrightness = v.roundToInt())) }
        }
    }
}

@Composable
private fun DevSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}
