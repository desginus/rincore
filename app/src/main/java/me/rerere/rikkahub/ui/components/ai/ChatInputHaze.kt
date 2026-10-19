package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
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
 * 输入框容器的背景效果（模糊或玻璃），未开启时原样返回。
 *
 * 开启后容器本身需要保持透明，[containerColor] 由效果负责铺底和着色。
 */
@Composable
internal fun Modifier.chatInputHaze(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    containerColor: Color,
    shape: RoundedCornerShape,
): Modifier {
    if (!displaySetting.enableBlurEffect) return this
    return when (displaySetting.backgroundEffectType) {
        // v5.0.13 (用户实证 2026-10-19 截图): 性能模式钉死 Quality。
        // haze 2.0.1 默认 Balanced=Fixed(0.5) → 输入缩放 sqrt(0.625)≈0.79:
        // 源内容先降采样到 79% 渲染再 1.26× 放大回 = "降清→模糊→放大"三连,
        // 叠加 Material3 预设默认噪点 0.15 的 SrcAtop 颗粒层 = 用户截图中的
        // 尖锐色斑网格 (PIL 验尸: 列向 10px 周期/自相关 0.84, 与 4.8.170 同源)。
        // Quality=Fixed(1f) → 全分辨率捕获零重采样链 (haze 文档: "If an effect
        // looks too pixelated, try a higher value")。
        // 与上游 3.0.0 的有意差异: 上游 enableBlurEffect 默认 false (无人跑这条
        // 管线), 我方 4.8.157/162 迁移强制开启 → 唯一受影响方, 修复必须做在这里。
        BackgroundEffectType.BLUR -> hazeBlur(
            input = HazeInput.Sources(hazeState),
            performanceMode = HazePerformanceMode.Quality,
            style = HazeBlurStyle.Material3 {
                blurRadius(12.dp)
                noiseFactor(0f)
            },
        )

        BackgroundEffectType.GLASS -> {
            val darkMode = LocalDarkMode.current
            hazeGlass(
                input = HazeInput.Sources(hazeState),
                performanceMode = HazePerformanceMode.Quality,  // v5.0.13: 同 BLUR——玻璃分支同受 0.79 降采样链影响（用户实证较轻但存在）
                style = GlassStyle.Material3(
                    containerColor = containerColor,
                    tint = containerColor.copy(alpha = 0.3f),
                ) {
                    appearanceResponse(dark = darkMode)
                    // Keep background text from competing with the input text.
                    optics(
                        GlassDefaults.optics.copy(
                            blurRadius = OpticalSizeValue.Fixed(16.dp),
                            depth = OpticalSizeValue.Fixed(0.5f),
                        )
                    )
                    shape(shape)
                },
            )
        }
    }
}

// Haze 按系统夜间模式选玻璃的明暗预设，应用内单独设置主题时会选错（深色主题下被提亮发白），
// 所以按应用主题显式写入。深色数值对应库里 internal 的 GlassStyle.regularDark。
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
