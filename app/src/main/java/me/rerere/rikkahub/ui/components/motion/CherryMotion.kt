/* 【域 F·主题渲染】 — 动效基建 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.motion

/* ───【自研】CherryMotion.kt — v4.8.143 按 Cherry Studio 手机版 v0.2.1 动效体系复刻
 * 用户定版 (2026-10-16): "把(动效)引擎按照 Cherry Studio 手机版 v0.2.1 重做和复刻。
 * 例如弹窗的缩放、滑动的动画、交互的手感。"
 *
 * 体系对齐 (逐字取自其仓库):
 *   packages/ui/src/motion.ts                  — 曲线/弹簧/时长词汇表
 *   packages/ui/src/components/menu/menu-motion.ts — 弹层配对 (开/关/淡 + 静置缩放/滑入)
 *   scroll-to-bottom-button 的 visibilityMotion    — 回底按钮 0.8 缩放淡出
 *   按钮按压 active:opacity-80 / 浮动件 -60        — 无涟漪的透明度反馈
 *
 * 曲线与时长分轴 — 曲线是设计令牌 (与位移距离无关, 共享), 时长按手势配对:
 *   · Settle    = cubic-bezier(0.22, 1, 0.36, 1)    纯减速: 起步快、落位硬 —
 *                 改动"既有个体"的尺寸/位置时的默认。
 *   · Overshoot = cubic-bezier(0.34, 1.25, 0.64, 1) 越过再回来 — "从无到有"的到达感。
 *   · 时长: FAST=200 (淡入淡出) / BASE=250 (关闭) / SLOW=350 (打开, 让过冲读成一次到达)。
 * 弹簧 = 临界阻尼 (dampingRatio 1.0 ← 其 damping 31.5 / stiffness 248, 单位质量换算):
 *   同一时间常数无论走多远、落位精确不越界 — 用于必须精确落位的位移。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/** CS 曲线令牌 (设计令牌层: 只表达"怎么动", 不含时长)。 */
object CherryEasing {
    /** 纯减速 — 默认曲线 (改动既有个体的尺寸/位置)。 */
    val Settle = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    /** 过冲到达 — "从无到有"的出现 (弹窗/弹层入场)。 */
    val Overshoot = CubicBezierEasing(0.34f, 1.25f, 0.64f, 1f)

    val Linear = LinearEasing
}

/** CS 时长令牌 (毫秒)。 */
object CherryDuration {
    const val FAST = 200
    const val BASE = 250
    const val SLOW = 350
}

/**
 * CS 手势配对 (曲线×时长) — 全部动效的唯一出口。
 * 与其仓库同构: 组件按手势取配对, 而不是各自拼曲线 (防漂移)。
 */
object CherryMotion {
    /** 打开 (弹窗/弹层): 350ms + 过冲。 */
    val Open: TweenSpec<Float> = tween(CherryDuration.SLOW, easing = CherryEasing.Overshoot)

    /** 关闭: 250ms + 纯减速。 */
    val Close: TweenSpec<Float> = tween(CherryDuration.BASE, easing = CherryEasing.Settle)

    /** 淡入淡出/可见性: 200ms + 纯减速 (含缩放通道)。 */
    val Fade: TweenSpec<Float> = tween(CherryDuration.FAST, easing = CherryEasing.Settle)

    /** 临界阻尼落位弹簧 (63.5ms 时间常数, ~420ms 走完 99%) — 需精确落位的位移。 */
    val SettleSpring: SpringSpec<Float> = spring(
        dampingRatio = 1f,
        stiffness = 248f,
        visibilityThreshold = 0.001f,
    )

    /** 弹层静置缩放 (入场起点 / 出场终点)。 */
    const val POPUP_REST_SCALE = 0.97f

    /** 回底浮动件的隐藏缩放。 */
    const val FAB_HIDDEN_SCALE = 0.8f

    /** 常规按压透明度 (CS 按钮 active:opacity-80)。 */
    const val PRESS_ALPHA = 0.8f

    /** 浮动件按压透明度 (CS active:opacity-60)。 */
    const val PRESS_ALPHA_SOFT = 0.6f
}

/**
 * 无涟漪按压反馈 — CS 交互手感: 按下整体降到 [alpha] 透明度 (默认 0.8), 松开回弹。
 * 明度动画 90ms (瞬时跟手), 替代 M3 涟漪。
 */
fun Modifier.cherryPress(
    alpha: Float = CherryMotion.PRESS_ALPHA,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) alpha else 1f,
        animationSpec = tween(90),
        label = "cherryPress",
    )
    this
        .graphicsLayer { this.alpha = pressAlpha }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
