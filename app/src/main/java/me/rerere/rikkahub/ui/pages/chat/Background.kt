/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

/* ───【原版对齐】Background.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

@Composable
fun AssistantBackground(setting: Settings, modifier: Modifier) {
    val assistant = setting.getCurrentAssistant()
    val gradientOn = assistant.useGradientBackground
    val image = assistant.background
    val opacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
    when {
        gradientOn && image == null -> {
            MeshGradientBackground(modifier = modifier)
        }

        gradientOn && image != null -> {
            // v4.8.52: 动态背景 + 壁纸共存 — 动态渐变在后 (光效), 壁纸在上
            // (透明度滑块可透出动态光效; 层次 = 用户定版)。
            Box(modifier = modifier) {
                MeshGradientBackground(modifier = Modifier.fillMaxSize())
                ImageBackgroundLayer(
                    image = image,
                    opacity = opacity,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        image != null -> {
            ImageBackgroundLayer(
                image = image,
                opacity = opacity,
                modifier = modifier,
            )
        }
    }
}

/** v4.8.52: 壁纸层 (图 + 主题渐变遮罩) — 单壁纸与"动态×壁纸"两形态复用。 */
@Composable
private fun ImageBackgroundLayer(
    image: String,
    opacity: Float,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Box(modifier = modifier) {
        // v4.8.51: 显式内存缓存键 — 与 WarmPipeline 壁纸预载同键 (assistant-bg::),
        // 启动预载命中后首帧即显示壁纸 (修复"先进黑屏后出壁纸")。
        AsyncImage(
            model = coil3.request.ImageRequest.Builder(LocalContext.current)
                .data(image)
                .memoryCacheKey("assistant-bg::" + image)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(opacity)
        )

        // 全屏渐变遮罩
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.2f),
                            backgroundColor.copy(alpha = 0.5f)
                        )
                    )
                )
        )
    }
}
