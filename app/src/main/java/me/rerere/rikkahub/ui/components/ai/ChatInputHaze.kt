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
import me.rerere.rikkahub.data.datastore.DisplaySetting

/**
 * 输入框容器的背景效果（模糊），未开启时原样返回。
 *
 * v5.0.26 用户定案（第二次纠正，不可再动）：
 * **恒定走 hazeBlur（原版 3.0.0 的 BLUR 分支逐字），不读 backgroundEffectType。**
 *
 * 事故复盘（B203）：
 *   ① 5.0.22 恒定 hazeBlur → 用户实测「没有条纹的模糊」= 正确状态；
 *   ② 5.0.23 我「恢复双分支 + 设置选择器」，而用户数据里 backgroundEffectType 仍是
 *      历史残留的 GLASS → 又走回 hazeGlass 光学折射管线 → 条纹复现（本可避免的自伤）；
 *   ③ 现彻底删除 GLASS 分支与选择器：此文件不再有任何分支判断，液态管线在本项目作废。
 */
@Composable
internal fun Modifier.chatInputHaze(
    hazeState: HazeState,
    displaySetting: DisplaySetting,
    containerColor: Color,
    shape: RoundedCornerShape,
): Modifier {
    if (!displaySetting.enableBlurEffect) return this
    return hazeBlur(
        input = HazeInput.Sources(hazeState),
        style = HazeBlurStyle.Material3 {
            blurRadius(12.dp)
        },
    )
}
