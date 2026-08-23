package me.rerere.rikkahub.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkahub.R
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDF 应用内渲染 (v3.9.4)：
 * - 高分辨率渲染 (基准 1.5x, 防模糊)
 * - 缩放功能: 右上角 + / - 按钮, 0.5x ~ 3x, 按缩放重渲染
 * - 深色/浅色模式切换: 右上角日/月图标
 * - 页面横向溢出时横向滚动
 */
@Composable
fun PdfRenderDialog(
    pdfFile: File,
    fileName: String,
    onDismiss: () -> Unit,
) {
    var openError by remember { mutableStateOf<String?>(null) }
    val descriptor = remember(pdfFile) {
        runCatching { ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }
    val renderer = remember(descriptor) {
        if (descriptor == null) null else runCatching { PdfRenderer(descriptor) }.getOrNull()
    }
    var isDark by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }

    if (renderer == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(HugeIcons.FileView, null, modifier = Modifier.size(40.dp))
                Text(
                    text = openError ?: "无法打开此 PDF（文件可能损坏或受密码保护）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.cancel))
                }
            }
        }
        return
    }

    DisposableEffect(renderer) {
        onDispose {
            runCatching { renderer.close() }
            runCatching { descriptor?.close() }
        }
    }

    val pageCount = renderer.pageCount
    val screenWidth = LocalConfiguration.current.screenWidthDp

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (isDark) {
            androidx.compose.ui.graphics.Color(0xFF121212)
        } else {
            MaterialTheme.colorScheme.background
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, maxLines = 1)
                        Text(
                            text = "$pageCount 页 · ${(zoom * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(HugeIcons.Cancel01, stringResource(R.string.cancel))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.5f) },
                    ) {
                        Text("−", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(
                        onClick = { zoom = (zoom + 0.25f).coerceAtMost(3f) },
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                    IconButton(
                        onClick = { isDark = !isDark },
                    ) {
                        Icon(
                            imageVector = if (isDark) HugeIcons.Sun01 else HugeIcons.Moon02,
                            contentDescription = "切换深色/浅色",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDark) {
                        androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items((0 until pageCount).toList()) { pageIndex ->
                val page = remember(renderer) { renderer.openPage(pageIndex) }
                val bitmap = remember(renderer, page, zoom) {
                    renderPageToBitmap(page, zoom)
                }
                DisposableEffect(page) {
                    onDispose { runCatching { page.close() } }
                }
                PdfPageImage(
                    pageBitmap = bitmap,
                    zoom = zoom,
                    screenWidth = screenWidth,
                    isDark = isDark,
                )
            }
        }
    }
}

@Composable
private fun PdfPageImage(
    pageBitmap: Bitmap?,
    zoom: Float,
    screenWidth: Int,
    isDark: Boolean,
) {
    if (pageBitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) { Text("页面渲染失败") }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val pageBg = if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.White
        Image(
            bitmap = pageBitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .width((screenWidth * zoom).dp)
                .background(pageBg),
        )
    }
}

/** 按缩放渲染页面, 基准 1.5x 保证屏幕清晰度 */
private fun renderPageToBitmap(page: android.graphics.pdf.PdfRenderer.Page, zoom: Float): Bitmap? {
    return runCatching {
        val scale = 1.5f * zoom
        val width = max(1, (page.width * scale).roundToInt())
        val height = max(1, (page.height * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }.getOrNull()
}