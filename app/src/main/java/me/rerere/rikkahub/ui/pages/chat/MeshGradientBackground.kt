package me.rerere.rikkahub.ui.pages.chat


/* ───【原版对齐】MeshGradientBackground.kt | v3.11.21 对齐最新原版
 * 动画速度与轨迹逐参数复刻原版 phase(5500/20)/7000/8500/6200 正弦
 * 漂移; 差异仅在实现载体: 原版单 Canvas 逐帧全屏重绘 (v3.6.82 曾因此
 * GPU 满载被静态化), 本版改为每光斑独立渐变 Box + withFrameNanos 相
 * 位驱动 offset (placement 层合成位移, 不触发重绘) — 视觉与原版一致,
 * GPU 成本近似零。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gemini 风格动态渐变背景。
 *
 * 原理 (与原版一致):
 *  1. 底层一个线性渐变(顶部偏蓝/深蓝、底部渐隐)。
 *  2. 上面叠几个 radialGradient 光斑 (中心有色 → 边缘透明)。
 *  3. 每个光斑独立周期的无限动画, 沿正弦/余弦轨迹缓慢漂移,
 *     总时长取 loops 对齐原版 (5.5s/圈 ×20, 7s ×1, 8.5s ×10, 6.2s ×10)。
 *
 * 实现差异: 位移动画走 offset placement 层, 渐层本体零重绘。
 */
@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // 全局时间源: 每帧更新一次 (placement 阶段读取, 不触发重组/重绘)
    val timeNanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { timeNanos.longValue = it }
        }
    }

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

    // 光斑配色 (蓝 / 青 / 淡蓝 / 暖色) 及浓度, 亮暗各一套
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

            // 原版各光斑参数逐一复刻: 中心基准 + 正弦漂移幅度 + 独立周期
            // (periodMs = 原版 phase() 的 durationMillis, 一圈时长)
            val specs = listOf(
                // 顶部蓝 (主色, 横向漂移) — 5.5s/圈 [x圈=×20]
                BlobSpec(blobBlue, alphaBlue, 5_500.0, 0.0,
                    wPx * 0.48, hPx * 0.08, wPx * 0.38, hPx * 0.18, 0.0, 1.0, 1.15, 1.0),
                // 左上青绿点缀 — 7s/圈
                BlobSpec(blobTeal, alphaTeal, 7_000.0, PI * 0.55,
                    wPx * 0.18, hPx * 0.24, wPx * 0.30, hPx * 0.20, 0.0, 1.0, 1.0, 1.0),
                // 右上淡蓝 — 8.5s/圈 (x 方向负幅度)
                BlobSpec(blobLightBlue, alphaLightBlue, 8_500.0, PI * 0.9,
                    wPx * 0.82, hPx * 0.12, wPx * 0.34, hPx * 0.18, 0.0, -1.0, 0.9, 1.0),
                // 暖色光斑 — 6.2s/圈
                BlobSpec(blobWarm, alphaWarm, 6_200.0, PI * 1.25,
                    wPx * 0.58, hPx * 0.34, wPx * 0.28, hPx * 0.16, 0.0, 1.0, 1.1, 1.0),
            )

            specs.forEachIndexed { i, s ->
                BlobView(
                    spec = s, index = i,
                    twoPi = TWO_PI,
                    diameterPx = diameter,
                    timeNanos = timeNanos,
                    modifier = Modifier.size(with(density) { diameter.toDp() }),
                )
            }
        }

        content()
    }
}

private data class BlobSpec(
    val color: Color,
    val alpha: Float,
    val periodMs: Double,
    val phaseOffset: Double,
    val cx: Double, val cy: Double,
    val ampX: Double, val ampY: Double,
    // 轨迹参数: x = cx + sin(p + px) * ax ; y = cy + cos(p * ky) * ay
    val px: Double, val ax: Double,
    val ky: Double, val ay: Double,
)

@Composable
private fun BlobView(
    spec: BlobSpec,
    index: Int,
    twoPi: Double,
    diameterPx: Float,
    timeNanos: androidx.compose.runtime.State<Long>,
    modifier: Modifier = Modifier,
) {
    // 中心基准 + 半径: placement 阶段逐帧计算 (无重组)
    val diameterHalf = diameterPx / 2.0
    Box(
        modifier = modifier
            .offset {
                val t = timeNanos.value / 1_000_000_000.0
                // 周期 → 相位速度 (原版: 2π 每一圈)
                val p = (t * twoPi * 1000.0 / spec.periodMs) + spec.phaseOffset
                val cx = spec.cx + kotlin.math.sin(p + spec.px) * spec.ax
                val cy = spec.cy + kotlin.math.cos(p * spec.ky) * spec.ay
                IntOffset(
                    (cx - diameterHalf).roundToInt(),
                    (cy - diameterHalf).roundToInt(),
                )
            }
            .background(
                Brush.radialGradient(
                    colors = listOf(spec.color.copy(alpha = spec.alpha), Color.Transparent),
                    center = Offset.Unspecified,
                )
            )
    )
}
