package me.rerere.rikkahub.ui.components.ai

import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.HazeBlurStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
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
    // v5.0.22 (B203 用户实证终局): 恒定走 hazeBlur —— 即原版 3.0.0 的 BLUR 分支逐字。
    // 事故链：ChatInputHaze 的 GLASS 分支 = hazeGlass（液态光学折射管线）——
    //   ①用户设置里 backgroundEffectType 历史残留 = GLASS → 从 5.0.x 起这台设备一直走光学管线
    //   （哪怕 5.0.16 该文件与上游 0 diff：0 diff 只保证代码一致，不保证走到同一分支）；
    //   ②v5.0.20 我又把 GLASS 强制成唯一分支，等于把坑钉死；
    //   ③光学折射在低分辨率源上呈现规则采样网格/条纹，与半径/噪点/alpha 无关（参数域无解）。
    // 用户 4.8.161 定版："原版就根本没有一个模糊和液态玻璃的分支"——液态玻璃分类整体作废。
    return hazeBlur(
        input = HazeInput.Sources(hazeState),
        style = HazeBlurStyle.Material3 {
            blurRadius(12.dp)
        },
    )
}

// v5.0.22: 液态玻璃分支已整体移除（用户定版：没有模糊/液态玻璃之分，只有原版模糊）。
