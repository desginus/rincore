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
 * 柔光玻璃款式 (haze 2.0.0 渲染管线 + blurRadius 12dp)。
 * v4.7.0 移植原版 2.5.3 时误升 haze rc02 导致"格栅式"变化 (v4.7.21 已
 * 回滚版本); 本文件将参数收敛为单一来源, 杜绝多处定义再次漂移。
 *
 * 【输入框/玻璃件显示逻辑 — 全状态规则】(任何时候都确定如此渲染)
 * ┌──────────────┬────────────────────────────────────────────────┐
 * │ 状态          │ 渲染                                            │
 * ├──────────────┼────────────────────────────────────────────────┤
 * │ 模糊开启(默认) │ hazeBlur(12dp) + 透明底 + 1dp 高光/描边         │
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
     * 柔光玻璃模糊样式 — 唯一规格，对齐原版 RikkaHub 2.5.6 的 blurRadius(12.dp)。
     * v4.8.166: 4dp → 12dp。4dp 半径下列表文字残影锐利，叠加 haze 默认噪点呈现格栅感；
     * v4.8.167: haze 依赖同步 2.0.0 正式版（渲染管线逐字对齐原版 2.5.6）。
     * v4.8.169: 输入框族调用点钉死 performanceMode=Quality(Fixed(1f)) — haze 自适应保真
     * 策略在设备几何触发时会把模糊输入降采样(0.8/0.5 档)再非整数放大=织物/条纹纹理的
     * 唯一运行时来源; 钉死后纹理粒度恒为物理像素、不随滚动节奏波动。弹窗/图标族小面积
     * 恒处 1.0 全质量档, 不钉。
     */
    @Composable
    fun blurStyle(): HazeBlurStyle = HazeBlurStyle.Material3 {
        blurRadius(12.dp)
    }

    /**
     * 输入框族玻璃样式 — v4.8.171 渲染器实验场用户指认 **F 配方**:
     * Material3 预设 + 4dp 半径 + 零噪点 + 模糊层透明度 90% (调用点另钉全保真 Quality)。
     * 仅主输入框 + 全屏编辑器消费; chrome 族 (弹窗/图标/工具条) 仍走 blurStyle()。
     * 若用户在渲染器 G 样本 (F+D 叠加薄板) 定版叠加形态, 只需在调用点 Surface
     * color 处加 frostFill 同款填充, 本样式不动。
     */
    @Composable
    fun inputBlurStyle(): HazeBlurStyle = HazeBlurStyle.Material3 {
        blurRadius(4.dp)
        noiseFactor(0f)
        alpha(0.9f)
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
