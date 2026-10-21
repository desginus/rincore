package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassStyleScope
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import me.rerere.rikkahub.data.datastore.BackgroundEffectType
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 输入框容器的背景效果（模糊 / 液态玻璃），未开启时原样返回。
 *
 * v5.0.27 用户定案：玻璃效果恢复（"问题不在玻璃，玻璃和模糊都是奇怪显示 bug 的受害者"）。
 * 条纹的真正判据见 B203：源 = ChatPage 的 AssistantBackground 层；
 * 若该层绘制的是低分辨率背景图，任何效果（模糊/玻璃）都会在低采样下产生网格。
 * → 与效果分支无关，**背景图体检（BackgroundHealth）才是根因入口**。
 */
@Composable
internal fun Modifier.chatInputHaze(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    containerColor: Color,
    shape: RoundedCornerShape,
): Modifier {
    // v5.0.41 用户定案：玻璃/模糊双分支恢复（"平时用玻璃，玻璃的 bug 不明显"）。
    // 说明：条纹为设备对包名 me.rincore.app 的系统级渲染状态问题（同设备其他包名正常、
    // 双开同包名复现），与本分支无关；渲染效果按用户选择执行。
    if (!displaySetting.enableBlurEffect) return this
    return when (displaySetting.backgroundEffectType) {
        BackgroundEffectType.BLUR -> hazeBlur(
            input = HazeInput.Sources(hazeState),
            style = HazeBlurStyle.Material3 {
                blurRadius(12.dp)
            },
        )

        BackgroundEffectType.GLASS -> {
            val darkMode = LocalDarkMode.current
            hazeGlass(
                input = HazeInput.Sources(hazeState),
                style = GlassStyle.Material3(
                    containerColor = containerColor,
                    tint = containerColor.copy(alpha = 0.3f),
                ) {
                    appearanceResponse(dark = darkMode)
                    optics(
                        GlassDefaults.optics.copy(
                            blurRadius = OpticalSizeValue.Fixed(16.dp),
                            depth = OpticalSizeValue.Fixed(0.5f),
                        ),
                    )
                },
            )
        }
    }
}

private fun GlassStyleScope.appearanceResponse(dark: Boolean) {
    if (dark) {
        specularIntensity(0.38f)
        edgeShadow(Color.Black.copy(alpha = 0.32f))
        ambientResponse(0.08f)
        contrast(0.08f)
        whitePoint(-0.22f)
        chromaMultiplier(1.1f)
    } else {
        specularIntensity(GlassDefaults.specularIntensity)
        edgeShadow(GlassDefaults.edgeShadow)
        ambientResponse(GlassDefaults.ambientResponse)
        contrast(GlassDefaults.contrast)
        whitePoint(GlassDefaults.whitePoint)
        chromaMultiplier(GlassDefaults.chromaMultiplier)
    }
}
