/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】ZoomableAsyncImage.kt | 差异 ±110 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.IntSize
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
import me.rerere.rikkahub.utils.resolveLocalImageFile
import me.rerere.rikkahub.utils.isVirtualWorkspaceUri
import me.rerere.rikkahub.utils.isWorkspaceUri
import me.rerere.rikkahub.utils.resolveWorkspaceRelPath
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** v5.0.85：同一图片 URL 只允许一轮失败重试（进程级；URL 版本键随文件更新而变化） */
private val workspaceImageRetriedUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
    // v4.2.3 修正: 短路只针对虚拟 workspace:// 前缀 (流式逐字到达)。host 真实
    // 路径 (file:///data/data/.../files/upload/... 聊天文件, 工具结果注入) 的
    // isWorkspaceUri=true 但 resolve 失败是"非 workspace 文件"而非"路径未完整",
    // 必须照常走 Coil 加载 — v4.2.2 误判导致工具结果图片全部"图片不可用"。
    val workspaceDead = model != null && isVirtualWorkspaceUri(model) &&
        resolveWorkspaceRelPath(model) == null
    var workspaceFailed by remember(model) { mutableStateOf(false) }
    // v5.0.65 ★「偶发图片渲染失败，只能看到 ALT 文本，之后一直不恢复」根修：
    // 旧实现 onError 置 workspaceFailed=true 后，下面的 if 分支**永久**走纯文本占位，
    // 再没有任何重试路径 —— 而失败常常是**时序问题**（模型先读图、随即在同一条回复里
    // 引用，转存/落盘还没完成，Coil 第一次请求扑空）。
    // 现在：失败后做有限次延迟重试（0.7s / 1.4s / 2.1s，共 3 次），期间重新走 AsyncImage。
    // 注意 workspaceDead（流式尚未到达的残缺 workspace:// URL）**不重试** —— 那是等 URL
    // 完整，重试只会造成请求风暴（v4.2.2 的教训）。
    var workspaceRetry by remember(model) { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(model, workspaceFailed) {
        if (workspaceFailed && workspaceRetry < 3) {
            // v5.0.85 ★用户定案（重试链收敛）：同一张图**只允许一轮重试**（首次失败时的
            // 0.7s/1.4s/2.1s 三次），之后（切页面/重组/再次进入）不再重试 —— 免得每进一次
            // 都白转 3 次。用进程级集合按 URL 记账；URL 含 mtime 版本键，文件更新后自然放行新一轮。
            val urlKey = model?.toString()?.substringBefore('#') ?: return@LaunchedEffect
            if (workspaceRetry == 0 && !workspaceImageRetriedUrls.add(urlKey)) return@LaunchedEffect
            kotlinx.coroutines.delay(700L * (workspaceRetry + 1))
            workspaceRetry += 1
            workspaceFailed = false
        }
    }
    val context = LocalContext.current
    val placeholder = if (LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val export = LocalExportContext.current
    // v4.8.2: workspace 图经 buildVersionedImageRequest — 缓存键含文件 mtime+size,
    // 同名覆盖 (模型改图) 后强制重新加载, 不再命中旧缓存。
    val coilModel = buildVersionedImageRequest(context, model) {
        placeholder(placeholder)
        crossfade(false)
        allowHardware(!export)
    }
    var loading by remember { mutableStateOf(false) }

    if (workspaceFetch && (workspaceFailed || workspaceDead)) {
        // v5.3.0 ★缺陷一（用户清单第 1、2 条）：渲染前先 stat，**四类结果分开报**并回显原路径：
        //   可读 / 不存在 / 文件为空（0 字节）/ scheme 不支持
        // 旧的单一文案「文件不存在 / 解码失败 / 缓存已清理」把四种完全不同的故障混成一句，
        // 用户无法判断是路径错、文件空、还是地址 scheme 根本不被支持。
        val failedPath = model?.toString() ?: ""
        val probe = remember(model) { probeLocalImage(failedPath) }
        val failTitle: String
        val failReason: String
        if (workspaceDead) {
            failTitle = "路径尚未就绪"
            failReason = "流式内容未完整，等待地址补全后自动恢復"
        } else when (probe) {
            ImageProbe.Missing -> {
                failTitle = "图片不存在"
                failReason = "路径不存在，或文件已被清理"
            }
            ImageProbe.Empty -> {
                failTitle = "图片为空"
                failReason = "文件存在但大小为 0 字节（写入未完成或被截断）"
            }
            is ImageProbe.Unsupported -> {
                failTitle = "不支持的地址"
                failReason = "scheme=${probe.scheme}，本机无法渲染"
            }
            ImageProbe.Readable -> {
                failTitle = "加载失败"
                failReason = "文件可读但解码失败，或命中旧缓存（已重试一轮）"
            }
        }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        Column(
            modifier = modifier
                .heightIn(min = 96.dp)
                .background(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(8.dp)
                .clickable { runCatching { clipboard.setText(androidx.compose.ui.text.AnnotatedString(failedPath)) } },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = failTitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = failReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (failedPath.isNotBlank()) {
                Text(
                    text = failedPath.substringAfter("://").ifBlank { failedPath },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "点按复制路径",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // v5.3.0 ★用户清单第 2 条：占位卡片必须提供**重试**按钮（旧实现只能退出重进/重组）
            if (!workspaceDead) {
                TextButton(
                    onClick = {
                        val urlKey = failedPath.substringBefore('#')
                        workspaceImageRetriedUrls.remove(urlKey)
                        workspaceRetry = 0
                        workspaceFailed = false
                    }
                ) {
                    Text("重试", style = MaterialTheme.typography.labelSmall)
                }
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
            onSuccess = { state ->
                loading = false
                // v4.8.123: 回填尺寸 memo — 同会话再次出现 (含上滑回看) 按已知尺寸
                // 预留, 不再从小占位跳到实际尺寸
                runCatching {
                    val img = state.result.image
                    model?.let { m ->
                        ImagePixelSizeMemo.put(m, IntSize(img.width, img.height))
                    }
                }
            },
            onError = {
                loading = false
                if (workspaceFetch) workspaceFailed = true
                // v4.5.16: 图片不渲染现场诊断 — 失败时记录 url 形态与 resolve
                // 结果 (resolve 失败即数据/路径层问题; resolve 成功即解码层),
                // 用户报"图片不可见"时 logcat 一行定论。
                // ★直引说明: 此处为诊断专用直引 (勿效仿) — 显示/保存链必须走
                // LocalImageAddress 单一解析点 (v4.8.185 定版)。
                android.util.Log.w("ZoomableAsyncImage", "load failed: model=" +
                    model?.take(160) + " ws=" + workspaceFetch +
                    " resolve=" + runCatching {
                        me.rerere.rikkahub.utils.WorkspaceImageResolver.resolve(model)?.absolutePath
                    }.getOrNull())
            },
        )
    }
    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}

// ─── v5.3.0：本地图片地址四类探测（用户缺陷一第 1 条）──────────────────────────
//
// 渲染前先 stat，把「可读 / 不存在 / 文件为空 / scheme 不支持」**分开报**，
// 不再让四种完全不同的故障共用一句"图片不可用"。
private sealed interface ImageProbe {
    data object Readable : ImageProbe
    data object Missing : ImageProbe
    data object Empty : ImageProbe
    data class Unsupported(val scheme: String) : ImageProbe
}

private fun probeLocalImage(url: String?): ImageProbe {
    if (url.isNullOrBlank()) return ImageProbe.Missing
    // 非本地地址交给 Coil，这里不判定
    if (url.startsWith("data:", true) ||
        url.startsWith("http://", true) ||
        url.startsWith("https://", true)
    ) return ImageProbe.Readable
    val scheme = url.substringBefore("://", "")
    if (scheme.isNotEmpty() && scheme != "file" && scheme != "workspace" && scheme != "content") {
        return ImageProbe.Unsupported(scheme)
    }
    val file = runCatching<java.io.File?> { resolveLocalImageFile(url) }.getOrNull() ?: return ImageProbe.Missing
    if (!file.exists() || file.isDirectory || !file.canRead()) return ImageProbe.Missing
    if (file.length() == 0L) return ImageProbe.Empty
    return ImageProbe.Readable
}
