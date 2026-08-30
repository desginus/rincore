package me.rerere.rikkahub.ui.pages.chat


/* ───【原版对齐】MeshGradientBackground.kt
 * 来源: 原版移植 + 自研小调整
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.animation.core RepeatMode
import androidx.compose.animation.core StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.roundToInt

/**
 * Gemini 风格渐变背景 (v3.11.20 动画回归)。
 *
 * 历史脉络:
 *  - 原版: 4 光斑缓慢漂移动画 (Canvas 每帧重绘)
 *  - v3.6.82: 用户报静态卡顿 — Canvas 逐帧重绘 + hazeSource 重采样
 *    导致 GPU 满载, 被静态化 → 光团永久不动 (用户 2026-08-30 指出
 *    "光团留在那里根本不动", 要求恢复)
 *  - v3.11.20: 恢复漂移且不再走 Canvas 重绘 — 每个光团为独立
 *    radient Box, 用 graphicsLayer translation 做位移动画 (合成器
 *    属性动画, 不触发 measure/layout/draw), GPU 成本近似零,
 *    兼得动画与不卡。
 *
 * 每个 nek Τα光斑独立周期 (11s~19s) + 相位错开, 往复漂移。
 */
private data class BlobSpec(
    val color: Color,
    val alpha: Float,
    val baseX: Float, val baseY: Float,
    val ampX: Float, val ampY: Float,
    val durMs: Int, val phaseMs: Int,
)

@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val dark = LocalDarkMode.current
    val baseGradient = if (dark) {
        arrayOf(
            0.0f to Color(0xFF1B2A45),
            0.22f to Color(0xFF15223A),
            0.45f to Color(0xFF0D1626),
            0.65f to Color(0xFF0A0F18),
            1.0f to Color(0xFF080B12),
        )
    } else {
        arrayOf(
            0.0f to Color(0xFFAFD0F2),
            0.22f to Color(0xFFCBE0F6),
            0.45f to Color(0xFFF1F7FD),
            0.65f to Color(0xFFFFFFFF),
            1.0f to Color(0xFFFFFFFF),
        )
    }

    // 光斑配色 (蓝/青/淡蓝/暖色) 及浓度, 亮暗各一套
    val blobBlue = if (dark) Color(0xFF3E6FB0) else Color(0xFF9EC5F0)
    val blobTeal = if (dark) Color(0xFF2E7D74) else Color(0xFFA8E6E0)
    val blobLightBlue = if (dark) Color(0xFF4A6E96) else Color(0xFFB6D7F2)
    val blobWarm = if (dark) Color(0xFF7C5F9E) else Color(0xFFFFC8D2)
    val alphaBlue = if (dark) 0.56f else 0.72f
    val alphaTeal = if (dark) 0.44f else 0.56f
    val alphaLightBlue = if (dark) 0.48f else 0.62f
    val alphaWarm = if (dark) 0.32f else 0.42f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colorStops = baseGradient)),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val diameter = with(density) { maxOf(maxWidth, maxHeight).toPx() * 0.72f }

            // 漂移参数: 中心基准 + 幅度 (px) + 独立周期/相位
            // (幅度与 v3.6.82 前原动画同量级, 光斑全部聚在顶部向下渐隐)
            val specs = listOf(
                BlobSpec(blobBlue, alphaBlue, wPx * 0.48f, hPx * 0.10f, wPx * 0.20f, hPx * 0.07f, 13_000, 0),
                BlobSpec(blobTeal, alphaTeal, wPx * 0.18f, hPx * 0.24f, wPx * 0.16f, hPx * 0.09f, 17_000, 1_800),
                BlobSpec(blobLightBlue, alphaLightBlue, wPx * 0.82f, hPx * 0.12f, wPx * 0.17f, hPx * 0.08f, 11_000, 3_100),
                BlobSpec(blobWarm, alphaWarm, wPx * 0.58f, hPx * 0.32f, wPx * 0.14f, hPx * 0.07f, 19_000, 5_600),
            )

            specs.forEachIndexed { i, s ->
                val t = rememberInfiniteTransition(label = "blob_$i")
                val bx = t.animateFloat(
                    initialValue = -1f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = s.durMs, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(s.phaseMs),
                    ),
                    label = "blob_x_$i",
                )
                val by = t.animateFloat(
                    initialValue = 1f, targetValue = -1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = s.durMs, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(s.phaseMs + 400),
                    ),
                    label = "blob_y_$i",
                )
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                (s.baseX + bx.value * s.ampX - diameter / 2f).roundToInt(),
                                (s.baseY + by.value * s.ampY - diameter / 2f).roundToInt(),
                            )
                        }
                        .size(with(density) { diameter.toDp() })
                        .background(
                            Brush.radialGradient(
                                colors = listOf(s.color.copy(alpha = s.alpha), Color.Transparent),
                                center = Offset.Unspecified,
                            )
                        )
                )
            }
        }

        content()
    }
}
