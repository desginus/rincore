package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * v3.6.71: 原版兔子 AnimatedVectorDrawable 动画替换为 RinCore 图标线条动画 —
 * launcher 前景图标直接去除背景 (暗色背景透明, 保留亮色线条,
 * drawable-nodpi/rin_logo_light.png), 规律缓慢的 alpha 呼吸渐变闪烁
 * (1.4s 周期), 与原版"线条动画"逻辑同构, 不整块糊图标, 只用线条。
 */
@Composable
fun RabbitLoadingIndicator(modifier: Modifier = Modifier) {
    val useAppIconStyleLoadingIndicator = LocalSettings.current.displaySetting.useAppIconStyleLoadingIndicator
    val primaryColor = MaterialTheme.colorScheme.primary

    if (useAppIconStyleLoadingIndicator) {
        val pulse = rememberInfiniteTransition(label = "rin_logo_pulse")
        val alpha by pulse.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rin_logo_alpha",
        )
        Image(
            painter = painterResource(R.drawable.rin_logo_light),
            contentDescription = null,
            modifier = modifier.graphicsLayer { this.alpha = alpha },
            colorFilter = ColorFilter.tint(primaryColor),
        )
    } else {
        ContainedLoadingIndicator(
            modifier = modifier,
        )
    }
}
