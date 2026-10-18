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
import androidx.compose.ui.graphics.Brush
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

    /**
     * v4.8.181: 输入栏玻璃样式 — 可调半径 + 可调噪点（感知映射, 见 noiseAlpha）。
     * 默认半径 4dp = 干净点; 噪点 15 = Material3 预设默认档（映射后逐字节不变）。
     * 双层玻璃用法: S1(底层) 带噪点 = "先噪点"; S2(顶层) 传 0 = "再模糊" (把底噪糊开)。
     */
    @Composable
    fun inputBlurStyle(radiusDp: Int, noisePercent: Int): HazeBlurStyle = HazeBlurStyle.Material3 {
        blurRadius(radiusDp.coerceIn(2, 24).dp)
        noiseFactor(noiseAlpha(noisePercent))
    }

    /**
     * v4.8.181 (用户定版): 噪点感知映射 — "高了之后脏兮兮"。
     * 0..40% 线性不变（15% 默认与旧完全一致）; 40..100% 压缩上端:
     * 100% 等效强度 = 0.60（旧 = 1.0 全强度贴噪 = 脏）。浓而不脏; 上限一个常量可调。
     */
    fun noiseAlpha(noisePercent: Int): Float {
        val p = noisePercent.coerceIn(0, 100) / 100f
        return if (p <= 0.4f) p else 0.4f + (p - 0.4f) * (1f / 3f)
    }

    /**
     * v4.8.183 (用户定版): 第二层(顶层)样式 — 半径加倍（上限 10dp=30px, 恒在无伪影区 <32px）、零噪点。
     * "能再模糊一下、细腻一下最好"：串行等效 σ≈√(2²+4²)≈4.5dp，在无格栅区内多给一档糊度。
     * 主输入栏与标题栏共用（"模糊的逻辑和输入栏一样"）。
     */
    @Composable
    fun inputTopStyle(radiusDp: Int): HazeBlurStyle = inputBlurStyle(
        radiusDp = (radiusDp * 2).coerceIn(2, 10),
        noisePercent = 0,
    )

    /** 高光描边 (澎湃 4 玻璃质感 — HyperGlassPanel 同源) */
    val highlightColor: Color = Color.White.copy(alpha = 0.14f)

    /** 无 haze source 或用户关闭模糊时的回退底色 */
    @Composable
    fun fallbackColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

    /**
     * v4.8.177/178: 输入栏蒙版 — 叠加在模糊层之上的可调透明度覆盖层。
     * 0% = 关闭 (返回透明, 观感与无蒙版一致); 颜色: 0=跟随主题 1=深色 2=浅色。
     */
    @Composable
    fun inputMaskColor(colorMode: Int, percent: Int): Color {
        val p = percent.coerceIn(0, 100) / 100f
        if (p <= 0f) return Color.Transparent
        // v4.8.183 (用户定版): "深色仅深色模式生效" — 浅色模式回落跟随主题色。
        val darkTheme = run {
            val s = MaterialTheme.colorScheme.surface
            (s.red + s.green + s.blue) / 3f < 0.5f
        }
        val base = when (colorMode) {
            1 -> if (darkTheme) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow
            2 -> Color.White
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        }
        return base.copy(alpha = p)
    }

    /**
     * v4.8.181 (用户定版): 雾化 — 白色柔雾覆盖层, 两栏镜像:
     *  · 输入栏 fogBrush = 下浓上淡（雾贴屏幕底边收拢, 朝消息一侧渐隐）;
     *  · 标题栏 titleFogBrush = 上浓下淡（雾贴顶边收拢, 消息划上来不被接缝浓雾吞掉）。
     * 0 = 关闭 (返回 null 无雾)。
     */
    fun fogBrush(percent: Int): Brush? {
        val p = percent.coerceIn(0, 100) / 100f
        return if (p <= 0f) null
        else Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.15f * p),
                Color.White.copy(alpha = 0.7f * p),
            ),
        )
    }

    /** v4.8.181: 标题栏雾化 — 上浓下淡（贴顶边收拢; 消息从下方划上来时接缝处清晰、往上渐隐）。 */
    fun titleFogBrush(percent: Int): Brush? {
        val p = percent.coerceIn(0, 100) / 100f
        return if (p <= 0f) null
        else Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.7f * p),
                Color.White.copy(alpha = 0.15f * p),
            ),
        )
    }
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
