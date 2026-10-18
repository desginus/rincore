/* 【域 F·主题渲染】 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.motion

/* ───【自研】ContinuousCornerShape.kt — 连续曲率圆角（曲率梳平滑 / G2 连续） */

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * v4.8.180 (用户定版): 连续曲率圆角 —— "所有的圆角…用曲率梳梳出来"。
 *
 * 角部曲线 = 超椭圆 |x|^n + |y|^n = 1 的四分之一（n = [smoothing], 默认 4）:
 *  · 与两条直边衔接处曲率 = 0（直边曲率也是 0）→ 曲率连续（G2）;
 *  · 曲率梳在角部两端平滑收束为 0、居中平滑隆起 —— 不再有圆弧角(G1)的"梳齿接缝跳变"。
 * 参数化: (x', y') = (cos^(2/n)φ, sin^(2/n)φ), φ ∈ [0, π/2], 每角 24 段采样成 Path。
 *
 * 半径语义与 RoundedCornerShape 相同（角部沿两直边各让出 cornerRadius）。
 */
class ContinuousCornerShape(
    private val cornerRadius: Dp,
    private val topStart: Boolean = true,
    private val topEnd: Boolean = true,
    private val bottomEnd: Boolean = true,
    private val bottomStart: Boolean = true,
    private val smoothing: Float = 4f,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val r = minOf(with(density) { cornerRadius.toPx() }, minOf(w, h) / 2f)
        if (r <= 0.5f) return Outline.Rectangle(Rect(0f, 0f, w, h))

        val rho = 2.0 / smoothing.coerceAtLeast(2.5f).toDouble()
        val steps = 24
        val halfPi = PI / 2.0
        val path = Path()

        // 顺时针: 顶边 → 右 → 底 → 左（含四角, 按开关取舍）
        if (topStart) path.moveTo(r, 0f) else path.moveTo(0f, 0f)

        if (topEnd) {
            path.lineTo(w - r, 0f)
            for (i in 1..steps) { // 右上角: φ 从 π/2 → 0
                val phi = halfPi * (1.0 - i.toDouble() / steps)
                val c = cos(phi).pow(rho).toFloat()
                val s = sin(phi).pow(rho).toFloat()
                path.lineTo(w - r + r * c, r * (1f - s))
            }
        } else {
            path.lineTo(w, 0f)
        }

        if (bottomEnd) {
            path.lineTo(w, h - r)
            for (i in 1..steps) { // 右下角: φ 从 0 → π/2
                val phi = halfPi * i.toDouble() / steps
                val c = cos(phi).pow(rho).toFloat()
                val s = sin(phi).pow(rho).toFloat()
                path.lineTo(w - r + r * c, h - r + r * s)
            }
        } else {
            path.lineTo(w, h)
        }

        if (bottomStart) {
            path.lineTo(r, h)
            for (i in 1..steps) { // 左下角: φ 从 π/2 → 0
                val phi = halfPi * (1.0 - i.toDouble() / steps)
                val c = cos(phi).pow(rho).toFloat()
                val s = sin(phi).pow(rho).toFloat()
                path.lineTo(r * (1f - c), h - r + r * s)
            }
        } else {
            path.lineTo(0f, h)
        }

        if (topStart) {
            path.lineTo(0f, r)
            for (i in 1..steps) { // 左上角: φ 从 0 → π/2
                val phi = halfPi * i.toDouble() / steps
                val c = cos(phi).pow(rho).toFloat()
                val s = sin(phi).pow(rho).toFloat()
                path.lineTo(r * (1f - c), r * (1f - s))
            }
        } else {
            path.lineTo(0f, 0f)
        }

        path.close()
        return Outline.Generic(path)
    }
}
