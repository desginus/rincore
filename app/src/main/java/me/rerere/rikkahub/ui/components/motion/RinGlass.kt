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
 * │ 模糊开启(默认) │ hazeBlur(8dp·零噪点) + 半透明填充(frostFill@0.30)   │
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
        // v4.8.153 (用户定版 — "最简单的柔光玻璃 = 半透明 + 高斯模糊"):
        //   ① 高斯模糊: 8dp × density≤4.0 ≤ 32px 物理 → 恒 1.0 全质量输入档
        //      (≥32px 掉 0.8 / ≥60px 掉 0.5 半分辨率 = 块状"隔栅", 见 v4.8.147 教训);
        //   ② 零噪点: 噪点(0.15/0.06)在真实观感=颗粒脏感 — 永久移除;
        //   ③ 无 tint/无底色: "半透明"由消费方的**填充层**提供 (rinGlass→frostFill /
        //      输入框 Surface color=frostFill) — 撤除 v4.8.150 的 colorEffects 材质 tint
        //      (其实现在深色主题下把玻璃压成黑板感, 用户判"又黑又有隔栅")。
        //      模糊源只含背景 (消息列表 v4.8.153 起不入源 — 散光/拖影从机制上消除)。
        return HazeBlurStyle {
            blurRadius(8.dp)
            noiseFactor(0f)
            backgroundColor(Color.Transparent)
        }
    }

    /**
     * v4.8.153 (用户定版"半透明加高斯模糊"): 玻璃面**半透明填充** —
     * 与高斯模糊叠加 = 最简单的柔光玻璃。主题自适应 (浅=乳白 / 深=烟色)。
     * v4.8.154 (用户: "输入框是黑的, 压根不透明"): 0.65 → **0.30** —
     * 深色主题下 65% 的 surface 系填充=黑板观感(透出量不可感知);
     * "全部的模糊感"原义 = 玻璃面主要呈现模糊本身, 填充只留薄薄一层:
     * 30% 填充/70% 透出 = 明确可感知的半透明。
     */
    @Composable
    fun frostFill(): Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.3f)

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
        // v4.8.153: 模糊开启 = 半透明填充 (最简单的柔光玻璃); 关闭 = 回退底色
        if (blurEnabled && hazeState != null) RinGlass.frostFill()
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
