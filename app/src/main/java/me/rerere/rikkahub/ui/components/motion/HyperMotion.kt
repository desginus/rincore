package me.rerere.rikkahub.ui.components.motion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3

/**
 * 4.0.2: 澎湃 OS 4 动效基建 — 柔和弹性曲线 + 柔光玻璃面板。
 * 全局弹窗系统统一走本组件 (弹出/关闭 scale+fade 弹性, 玻璃化表面, 高光描边)。
 *
 * 4.1.5 柔光玻璃根修: HyperGlassPanel 此前是半透明纯色底 (surface 0.88),
 * 用户实测 = "和背景同色的渐变", 无真模糊。升级为 HazeInput.Sources 真
 * blur — 弹窗层经 CompositionLocal 共享聊天页 hazeState (主窗 source),
 * haze 2.0 官方支持 Dialog 跨 window blur; 无 source 场景回退半透明。
 */

/** 聊天页 (或宿主页) 的 HazeState — 弹窗玻璃消费此 source 做真模糊 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** 澎湃弹性规格: 中高阻尼 (柔和回弹不过弹), 中刚度 (跟手不拖沓) */
object HyperMotionSpec {
    val spring = spring<Float>(
        dampingRatio = 0.85f,
        stiffness = 380f,
        visibilityThreshold = 0.001f,
    )
    const val FADE_IN_MS = 220
    const val FADE_OUT_MS = 180
    const val ENTER_FROM_SCALE = 0.92f
    const val EXIT_TO_SCALE = 0.95f
}

/**
 * 柔光玻璃面板 — 半透明表面 + 高光描边 (澎湃 4 玻璃质感):
 * 深色模式 alpha 高些保对比度, 浅色模式偏通透。
 */
@Composable
fun HyperGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val surfaceAlpha = 0.88f
    val highlightAlpha = 0.14f
    // 4.1.5: 真柔光玻璃 — 有 haze source 时 blur + 透明底 (模糊透出背景),
    // 无 source (非聊天场景) 时回退半透明底
    val hazeState = LocalHazeState.current
    Column(
        modifier = modifier
            .clip(shape)
            .then(
                if (hazeState != null) Modifier.hazeBlur(
                    input = HazeInput.Sources(hazeState),
                    style = HazeBlurStyle.Material3 { },
                ) else Modifier
            )
            .background(
                if (hazeState != null) Color.Transparent
                else MaterialTheme.colorScheme.surface.copy(alpha = surfaceAlpha)
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = highlightAlpha),
                shape = shape,
            ),
        content = content,
    )
}

/**
 * 澎湃动效弹窗 — 入场 fade+scale(0.92→1) 弹性, 出场 fade+scale(→0.95)。
 * 全 app 弹窗统一入口 (语义结构: title/content/actions 自绘)。
 */
@Composable
fun HyperDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        // 4.1.5: 弹窗 dim 归零 — 默认 dim 遮罩会盖住玻璃 blur 的透出效果
        val view = androidx.compose.ui.platform.LocalView.current
        (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f)
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(HyperMotionSpec.FADE_IN_MS)) +
                scaleIn(
                    initialScale = HyperMotionSpec.ENTER_FROM_SCALE,
                    animationSpec = HyperMotionSpec.spring,
                ),
            exit = fadeOut(tween(HyperMotionSpec.FADE_OUT_MS)) +
                scaleOut(targetScale = HyperMotionSpec.EXIT_TO_SCALE),
        ) {
            HyperGlassPanel(modifier = modifier) {
                if (title != null) {
                    Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) { title() }
                }
                if (text != null) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { text() }
                }
                if (confirmButton != null || dismissButton != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        if (dismissButton != null) dismissButton()
                        if (confirmButton != null) confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * 全屏玻璃面板 (工作区文件预览等场景): scrim + 玻璃化主体, 入场弹性。
 */
@Composable
fun HyperFullPanel(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(HyperMotionSpec.FADE_IN_MS)) +
                    scaleIn(
                        initialScale = HyperMotionSpec.ENTER_FROM_SCALE,
                        animationSpec = HyperMotionSpec.spring,
                    ),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Transparent,
                ) {
                    HyperGlassPanel {
                        content()
                    }
                }
            }
        }
    }
}
