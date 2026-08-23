package me.rerere.rikkahub.ui.components.render

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import java.io.File

/**
 * 统一渲染视图 (v3.9.6 渲染机 UI)
 *
 * 顶栏: 关闭 | 标题 + 页码 | 上一页/下一页 (多页时) | 日/月深色切换
 * 内容: 按 RenderResult 分发:
 *  - HtmlPages: WebView 分页加载 file 产物, 深色 JS 切类, 捏合缩放
 *  - PdfView:   PdfRenderView 原生手势缩放
 *  - ImageView / VideoView / AudioView: 原生媒体视图
 *  - Unsupported: 明确错误提示
 */
@Composable
fun RenderViewDialog(
    result: RenderResult,
    onDismiss: () -> Unit,
) {
    var isDark by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pageIndex by remember { mutableIntStateOf(0) }

    val pageCount = when (result) {
        is RenderResult.HtmlPages -> result.pageCount
        is RenderResult.PdfView -> 0
        else -> 0
    }

    Scaffold(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        containerColor = if (isDark) Color(0xFF121212) else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = when {
                            result is RenderResult.HtmlPages && result.pageCount > 1 ->
                                "第 ${pageIndex + 1} / ${result.pageCount} 页"
                            result is RenderResult.Unsupported -> result.message
                            else -> "双指缩放 · 滑动浏览"
                        }
                        Text(
                            text = sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) Color(0xFF9E9E9E)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (result is RenderResult.HtmlPages && result.pageCount > 1) {
                        IconButton(
                            onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                            enabled = pageIndex > 0,
                        ) {
                            Icon(HugeIcons.ArrowLeft01, "上一页")
                        }
                        IconButton(
                            onClick = { pageIndex = (pageIndex + 1).coerceAtMost(result.pageCount - 1) },
                            enabled = pageIndex < result.pageCount - 1,
                        ) {
                            Icon(HugeIcons.ArrowRight01, "下一页")
                        }
                    }
                    when (result) {
                        is RenderResult.HtmlPages, is RenderResult.PdfView -> {
                            IconButton(onClick = { isDark = !isDark }) {
                                Icon(
                                    imageVector = if (isDark) HugeIcons.Sun01 else HugeIcons.Moon02,
                                    contentDescription = "切换深色/浅色",
                                )
                            }
                        }
                        else -> {}
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDark) Color(0xFF1E1E1E)
                    else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = androidx.compose.ui.Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (result) {
                is RenderResult.HtmlPages -> HtmlPagesContent(
                    workDir = result.workDir,
                    pageIndex = pageIndex,
                    isDark = isDark,
                )
                is RenderResult.PdfView -> PdfRenderView(
                    pdfFile = result.pdfFile,
                    zoom = zoom,
                    onZoomChange = { change ->
                        zoom = (zoom * change).coerceIn(0.5f, 4f)
                    },
                )
                is RenderResult.ImageView -> ImageRenderView(
                    imageFile = result.imageFile,
                    contentDescription = result.title,
                )
                is RenderResult.VideoView -> VideoRenderView(result.videoFile)
                is RenderResult.AudioView -> AudioRenderView(result.audioFile)
                is RenderResult.Unsupported -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(result.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** WebView 分页内容: file 产物加载 + JS 深色切类 + 捏合缩放 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPagesContent(
    workDir: File,
    pageIndex: Int,
    isDark: Boolean,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            "document.documentElement.classList.toggle('dark', $isDark)",
                            null,
                        )
                    }
                }
                webView = this
            }
        },
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
    )

    LaunchedEffect(webView, workDir, pageIndex) {
        val pageFile = File(workDir, "page${pageIndex + 1}.html")
        webView?.loadUrl(Uri.fromFile(pageFile).toString())
    }
}