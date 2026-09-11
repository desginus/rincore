package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】ZoomableAsyncImage.kt | 差异 ±110 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.LocalExportContext
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.isWorkspaceUri
import me.rerere.rikkahub.utils.resolveWorkspaceRelPath
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
) {
    var showImageViewer by remember { mutableStateOf(false) }
    // v3.11.33: 布局回退为分支式 — 外部 modifier 直接作用于显示组件。
    // v3.11.32 的 Box+matchParentSize 结构在无宽度约束场景 (用户消息缩略图
    // clip+height(72)) 下 Box 无固有宽度 → matchParentSize 子项塌缩不可见,
    // 用户发送图片全部变成空白占位。分支式保证: 正常态与失败态共用同一
    // modifier 语义, 尺寸行为与 v3.11.31 之前完全一致。
    // 其余行为不变: 正常 scheme (https/file:///sdcard) 零差异; workspace://
    // 加载失败 → 渲染 alt + 占位框 (规格 §4)。
    val workspaceFetch = model != null && isWorkspaceUri(model)
    // v4.2.2: 流式不完整 URL 短路 — 模型回复 ![](workspace://...) 逐字到达,
    // 路径段未完整时 resolve 必失败; 若放行 Coil, 每次 delta 重组都会重建
    // ImageRequest 失败重试 (remember(model) 随部分 URL 变化) → 请求风暴。
    // 短路判定 = resolveWorkspaceRelPath 能解析出完整相对路径才进 Coil,
    // 否则直接渲染占位。完整 URL 的行为不变 (存在与否仍由 Coil 判定)。
    val workspaceResolvable = workspaceFetch && resolveWorkspaceRelPath(model) != null
    val workspaceDead = workspaceFetch && !workspaceResolvable
    var workspaceFailed by remember(model) { mutableStateOf(false) }
    val context = LocalContext.current
    val placeholder = if (LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember { mutableStateOf(false) }

    if (workspaceFetch && (workspaceFailed || workspaceDead)) {
        // 失败态: 同一 modifier 作用在占位组件上 (尺寸跟随调用方约束)
        Column(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(8.dp)
                .clickable { showImageViewer = false },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "图片不可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!contentDescription.isNullOrBlank()) {
                Text(
                    text = contentDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        AsyncImage(
            model = coilModel,
            contentDescription = contentDescription,
            modifier = modifier
                .shimmer(isLoading = loading)
                .clickable {
                    showImageViewer = true
                },
            contentScale = contentScale,
            alpha = alpha,
            alignment = alignment,
            onLoading = {
                loading = true
            },
            onSuccess = {
                loading = false
            },
            onError = {
                loading = false
                if (workspaceFetch) workspaceFailed = true
            },
        )
    }
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
