package me.rerere.rikkahub.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import java.io.File
import kotlin.math.max

/**
 * PDF 应用内渲染 — PdfRenderer 逐页渲染为位图, 竖向滚动浏览。
 * 内存管理: 页面渲染后立即 close, 位图回收; 每页最多保留 ~2.5x 原生采样。
 */
@Composable
fun PdfRenderDialog(
    pdfFile: File,
    fileName: String,
    onDismiss: () -> Unit,
) {
    val descriptor = remember(pdfFile) { ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY) }
    val renderer = remember(descriptor) { PdfRenderer(descriptor) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                renderer.close()
                descriptor.close()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, maxLines = 1)
                        Text(
                            text = "${renderer.pageCount} 页",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items((0 until renderer.pageCount).toList()) { pageIndex ->
                val page = remember(renderer) { renderer.openPage(pageIndex) }
                val bitmap = remember(renderer, page) { renderPageToBitmap(page) }
                DisposableEffect(page) {
                    onDispose { runCatching { page.close() } }
                }
                PdfPageImage(pageBitmap = bitmap)
            }
        }
    }
}

@Composable
private fun PdfPageImage(pageBitmap: Bitmap?) {
    if (pageBitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) { Text("页面渲染失败") }
        return
    }
    Image(
        bitmap = pageBitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun renderPageToBitmap(page: android.graphics.pdf.PdfRenderer.Page): Bitmap? {
    return runCatching {
        val width = max(1, page.width)
        val height = max(1, page.height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }.getOrNull()
}