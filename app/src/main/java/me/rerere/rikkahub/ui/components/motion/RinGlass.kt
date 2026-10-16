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
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * ═══════════════════════════════════════════════════════════════════
 * RinGlass — 柔光玻璃统一渲染规格 (v4.7.22)
 *
 * 【定版依据】用户定版: 全 app 玻璃元素统一为"光波粒"柔光玻璃款
 * (haze 2.0.0-beta01 渲染管线 + blurRadius 8dp[全质量档] + 零噪点 + surface@0.5
 * 材质 tint; v4.8.150 像素级仿真对比定案 — 取代"4dp+噪点"与"20dp 半分辨率"两代旧规格)。
 * v4.7.0 移植原版 2.5.3 时误升 haze rc02 导致"格栅式"变化 (v4.7.21 已
 * 回滚版本); 本文件将参数收敛为单一来源, 杜绝多处定义再次漂移。
 *
 * 【输入框/玻璃件显示逻辑 — 全状态规则】(任何时候都确定如此渲染)
 * ┌──────────────┬────────────────────────────────────────────────┐
 * │ 状态          │ 渲染                                            │
 * ├──────────────┼────────────────────────────────────────────────┤
 * │ 模糊开启(默认) │ hazeBlur(8dp·零噪点·材质tint) + 1dp 高光/描边 │
 * │ 生成中         │ 同上 (永不在生成期降级为纯色底 — v4.1.5 定版)   │
 * │ 键盘弹出       │ 同上 (仅布局位移动画, 玻璃参数不变)             │
 * │ 空/有内容      │ 同上 (内容多少不改变玻璃材质)                   │
 * │ 语音模式       │ 同上 (行内容切换, 玻璃材质不变)                 │
 * │ 编辑消息       │ 同上                                            │
 * │ 用户关闭模糊    │ 输入框族: 半透明底 (inputFallbackColor,        │
 * │ (enableBlurEffect│   v4.8.144 定版"任何场景默认半透明");          │
 * │  =false)       │ 弹窗族: 纯色回退 (fallbackColor)               │
 * │ 无 haze source │ 同上 (按消费方族别取回退色)                     │
 * └──────────────┴────────────────────────────────────────────────┘
 * 其他形态 (格栅/纯色/其他模糊参数) 均为 bug, 不允许出现。
 * v4.8.144: 输入框族(主输入框/全屏输入框)任何场景默认半透明 — 关闭模糊不再出纯色板。
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
    fun blurStyle(): HazeBlurStyle {
        // v4.8.151 编译修复: MaterialTheme.colorScheme 是 @Composable getter —
        // 必须在函数体(组合上下文)取值后传入非 @Composable 的 HazeBlurStyle{} 构建器。
        val materialTint = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        return HazeBlurStyle {
        // v4.8.150 (用户实证终版 — 像素级仿真对比定案): "柔光玻璃"三要素
        //   ① 深模糊但锁全质量档: 8dp × density≤4.0 ≤ 32px 物理 → 恒 1.0 输入缩放
        //      (≥32px 掉 0.8 / ≥60px 掉 0.5 半分辨率 = 块状"隔栅", 见 v4.8.147 误改教训);
        //   ② 零噪点: 噪点(haze 默认 0.15 / 曾用 0.06)在真实观感=颗粒脏感 —
        //      历次"隔山/糟糕"的共因, 彻底移除(色带由 tint 层兜底, 不再靠噪点抖动);
        //   ③ 系统材质 tint (CS ultraThinMaterial 同思路): surface@0.5 以 SrcOver 叠于
        //      模糊之上 = 乳白"柔光"扩散层; 无 tint 的纯模糊在深色内容后=灰污渍感。
        // 参照: Cherry Studio v0.2.1 安卓头部玻璃 (dimezis blur intensity 24 + ultraThin 材质 tint)。
        blurRadius(8.dp)
        noiseFactor(0f)
        backgroundColor(Color.Transparent)
        colorEffects(
            listOf(
                HazeColorEffect.tint(materialTint)
            )
        )
        }
    }

    /** 高光描边 (澎湃 4 玻璃质感 — HyperGlassPanel 同源) */
    val highlightColor: Color = Color.White.copy(alpha = 0.14f)

    /** 无 haze source 或用户关闭模糊时的回退底色 (弹窗族) */
    @Composable
    fun fallbackColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

    /**
     * v4.8.144 (用户定版): 输入框族"任何场景默认半透明" — 模糊关闭/无源时, 主输入框与
     * 全屏输入框的回退底色由不透明纯色改为半透明 (alpha 0.8), 不再存在纯色板形态。
     * (弹窗族 HyperGlassPanel 回退保持原规格 — 本值仅输入框族消费。)
     */
    @Composable
    fun inputFallbackColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.8f)
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
