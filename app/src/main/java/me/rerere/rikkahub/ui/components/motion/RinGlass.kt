/* 【域 F·主题渲染】 — UI 组件 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.motion

/* ───【自研】RinGlass.kt | 原版无此文件
 * 4.7.22 统一玻璃基建 — 全 app 玻璃渲染的单一来源。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3

/**
 * ═══════════════════════════════════════════════════════════════════
 * RinGlass — 柔光玻璃统一渲染规格 (v4.7.22)
 *
 * 【定版依据】用户定版: 全 app 玻璃元素统一为 4.3~4.5 时代"原生构建"的
 * 柔光玻璃款式 (haze 2.0.0-beta01 渲染管线 + blurRadius 4dp)。
 * v4.7.0 移植原版 2.5.3 时误升 haze rc02 导致"格栅式"变化 (v4.7.21 已
 * 回滚版本); 本文件将参数收敛为单一来源, 杜绝多处定义再次漂移。
 *
 * 【输入框/玻璃件显示逻辑 — 全状态规则】(任何时候都确定如此渲染)
 * ┌──────────────┬────────────────────────────────────────────────┐
 * │ 状态          │ 渲染                                            │
 * ├──────────────┼────────────────────────────────────────────────┤
 * │ 模糊开启(默认) │ hazeBlur(4dp) + 透明底 + 1dp 高光/描边         │
 * │ 生成中         │ 同上 (永不在生成期降级为纯色底 — v4.1.5 定版)   │
 * │ 键盘弹出       │ 同上 (仅布局位移动画, 玻璃参数不变)             │
 * │ 空/有内容      │ 同上 (内容多少不改变玻璃材质)                   │
 * │ 语音模式       │ 同上 (行内容切换, 玻璃材质不变)                 │
 * │ 编辑消息       │ 同上                                            │
 * │ 用户关闭模糊    │ 半透明纯色底 (surfaceContainerLow) — 显式设置,  │
 * │ (enableBlurEffect│   唯一允许的非玻璃形态                          │
 * │  =false)       │                                                │
 * │ 无 haze source │ 半透明纯色底 (弹窗层无源时回退 — 与上同族)      │
 * └──────────────┴────────────────────────────────────────────────┘
 * 其他形态 (格栅/纯色/其他模糊参数) 均为 bug, 不允许出现。
 *
 * 【消费方】ChatInput / HyperGlassPanel(弹窗体系) / 聊天悬浮工具条 /
 * 附件面板 — 全部经 Modifier.rinGlass() 或 RinGlass.blurStyle。
 * ═══════════════════════════════════════════════════════════════════
 */
object RinGlass {
    /**
     * 柔光玻璃模糊样式 — 唯一规格。
     * blurRadius 4dp: 4.3 时代参数 (v3.6.82 起: 8->4dp, 120Hz GPU 采样开销优化),
     * 即用户认可的"原生款"。
     */
    @Composable
    fun blurStyle(): HazeBlurStyle = HazeBlurStyle.Material3 {
        blurRadius(4.dp)
    }

    /** 高光描边 (澎湃 4 玻璃质感 — HyperGlassPanel 同源) */
    val highlightColor: Color = Color.White.copy(alpha = 0.14f)

    /** 无 haze source 或用户关闭模糊时的回退底色 */
    @Composable
    fun fallbackColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow
}

/**
 * 玻璃表面修饰符 — 模糊 + 透明底 (有源) 或半透明底 (无源)。
 * 行为与 4.3 时代输入框逐行同构:
 *   if (enableBlur) hazeBlur(style) + Color.Transparent else fallbackColor
 *
 * @param hazeState 宿主 HazeState; null = 无源, 走半透明回退
 * @param blurEnabled 用户设置 displaySetting.enableBlurEffect; false = 半透明回退
 * @param shape 裁剪形状 (玻璃必须与容器同 shape, 否则模糊溢出)
 */
@Composable
fun Modifier.rinGlass(
    hazeState: HazeState?,
    blurEnabled: Boolean = true,
    shape: Shape,
): Modifier = this
    .clip(shape)
    .then(
        if (blurEnabled && hazeState != null) Modifier.hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = RinGlass.blurStyle(),
        ) else Modifier
    )
    .background(
        if (blurEnabled && hazeState != null) Color.Transparent
        else RinGlass.fallbackColor()
    )

/**
 * 玻璃高光描边 — 玻璃件统一用它 (输入框描边仍走 outlineVariant 风格, 见消费方)。
 * 1dp 白色 14% — 与 HyperGlassPanel 一致。
 */
fun Modifier.rinGlassHighlight(shape: Shape): Modifier = this.border(
    width = 1.dp,
    color = RinGlass.highlightColor,
    shape = shape,
)
