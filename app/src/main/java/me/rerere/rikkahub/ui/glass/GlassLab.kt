/* 【渲染器 · 玻璃实验场】 | 地图: docs/APP_MAP.md §E(兼容测试·开发者) */
package me.rerere.rikkahub.ui.glass

/* ───【自研】GlassLab.kt — v4.8.170 (用户定版 2026-10-17)
 * 渲染器 = 输入栏玻璃效果受控实验场。用户指令:
 *   "把负责这一个渲染模块的代码单独拎出来, 给它起个名字叫渲染器;
 *    你可以在那里用不同的方式放好多个输入栏…多放几个输入栏,
 *    我来看看到底哪个是我想要的效果"。
 * 性质: 仅测试用途, 不承载业务逻辑; 真实输入栏由用户选定方案后单点落地。
 * 每个样本 = 独立 hazeState + 相同背景内容, 变量只有玻璃配方。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.rememberHazeState
import me.rerere.rikkahub.ui.components.motion.RinGlass

/**
 * 渲染器 — 玻璃实验场。
 * 样本 A-F 共用同一背景内容, 变量只有玻璃配方; 用户真机比对后指认字母,
 * 选定配方回写到 RinGlass(真实输入栏单一来源)。
 */
@Composable
fun GlassLabPage(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("渲染器（玻璃实验）") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
        )
        Text(
            "测试用途 · 与真实输入栏无关。每个样本背景内容相同, 只有玻璃配方不同。" +
                "滚动背景内容可用页面滚动, 对比各样本栅格/颗粒/柔和度, 指认字母即可。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        val variants = labVariants()
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(variants) { _, variant ->
                LabSpecimen(variant)
            }
        }
    }
}

/** 单个实验样本的配方 (style=null 表示完全不跑 haze 管线) */
private data class LabVariant(
    val label: String,
    val note: String,
    val style: HazeBlurStyle?,
    val fill: Color,
)

@Composable
private fun labVariants(): List<LabVariant> {
    val low = MaterialTheme.colorScheme.surfaceContainerLow
    return listOf(
        LabVariant(
            "A · 旧规格对照",
            "Material3 12dp + 默认噪点 0.15 + 全保真 — v4.8.170 前的真实输入栏配方（复现格栅用）",
            RinGlass.blurStyle(),
            Color.Transparent,
        ),
        LabVariant(
            "B · 零噪点",
            "Material3 12dp + noiseFactor(0f) — 拿掉噪点贴图层, 验证条纹是否来自它",
            m3Style(12.dp, 0f, null),
            Color.Transparent,
        ),
        LabVariant(
            "C · 零噪点 + 薄板 35%",
            "B 的基础上垫 35% 半透明薄板 — 「嵌入一层薄板」方案的混合形态",
            m3Style(12.dp, 0f, null),
            low.copy(alpha = 0.35f),
        ),
        LabVariant(
            "D · 纯薄板（无模糊）",
            "完全不跑 haze 管线, 只垫 55% 半透明板 — 兜底对照（理论上零纹理）",
            null,
            low.copy(alpha = 0.55f),
        ),
        LabVariant(
            "E · 重磨砂 8dp 零噪点",
            "8dp + 零噪点 + 模糊层透明度 85% — 半径更小=GPU 内部降采样链更短",
            m3Style(8.dp, 0f, 0.85f),
            Color.Transparent,
        ),
        LabVariant(
            "F · 轻磨砂 4dp 零噪点",
            "4dp + 零噪点 + 模糊层透明度 90% — 用户已指认 (v4.8.171 已落地真实输入栏)",
            m3Style(4.dp, 0f, 0.9f),
            Color.Transparent,
        ),
        LabVariant(
            "G · F + D 叠加（F玻璃+55%薄板）",
            "F 的玻璃 (4dp+零噪点+90%) 叠 D 的 55% 半透明薄板 — 双层叠加对照 (用户指定)",
            m3Style(4.dp, 0f, 0.9f),
            low.copy(alpha = 0.55f),
        ),
    )
}

/** Material3 预设 + 半径/噪点/层透明度覆写 (alpha=null 表示用预设默认 1.0) */
@Composable
private fun m3Style(radius: Dp, noise: Float, alpha: Float?): HazeBlurStyle =
    HazeBlurStyle.Material3 {
        blurRadius(radius)
        noiseFactor(noise)
        if (alpha != null) alpha(alpha)
    }

@Composable
private fun LabSpecimen(variant: LabVariant) {
    val hazeState = rememberHazeState()
    Column {
        Text(
            variant.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            variant.note,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            // 背景 = 模糊源 (模拟正文文字滑入输入栏下方的场景)
            Column(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val rowWidths = remember {
                    listOf(0.9f, 0.7f, 0.85f, 0.5f, 0.95f, 0.6f, 0.8f, 0.75f, 0.9f, 0.65f)
                }
                rowWidths.forEachIndexed { i, w ->
                    Box(
                        Modifier
                            .padding(horizontal = 14.dp)
                            .fillMaxWidth(w)
                            .height(if (i % 3 == 0) 18.dp else 12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (i % 2 == 0) 0.42f else 0.26f,
                                ),
                            ),
                    )
                }
            }
            // 输入栏样本 (变量所在)
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .then(
                        if (variant.style != null) {
                            Modifier.hazeBlur(
                                input = HazeInput.Sources(hazeState),
                                style = variant.style,
                                performanceMode = HazePerformanceMode.Quality,
                            )
                        } else {
                            Modifier
                        }
                    ),
                shape = RoundedCornerShape(20.dp),
                color = variant.fill,
                tonalElevation = 0.dp,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "输入消息…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("↑", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}
