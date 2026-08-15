package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import kotlin.math.roundToInt

@Composable
fun rememberAvatarShape(loading: Boolean): Shape {
    // v3.6.81: 非 loading 直接返回, 不再无条件创建无限动画。
    // 此前每条消息头像即使静态也挂一个每帧驱动的 infiniteTransition,
    // 列表内 N 个头像 × 每帧 invalidate 空转 → 间歇性卡顿来源。
    if (!loading) return CircleShape
    val infiniteTransition = rememberInfiniteTransition()
    val rotateAngle = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            ),
        )
    )
    return MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt())
}
