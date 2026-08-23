package me.rerere.rikkahub.ui.components.render

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileView
import java.io.File
import kotlin.math.roundToInt

/**
 * PDF 内容视图: 原生 PdfRenderer 逐页高清渲染
 * - 手势捏合缩放 0.5x~4x
 * - 页面位图按缩放重渲染, 深色只影响外壳由调用方控制
 */
@Composable
fun PdfRenderView(
    pdfFile: File,
    zoom: Float,
    onZoomChange: (Float) -> Unit,
) {
    var openError by remember { mutableStateOf<String?>(null) }
    val descriptor = remember(pdfFile) {
        runCatching { ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }
    val renderer = remember(descriptor) {
        if (descriptor == null) null else runCatching { PdfRenderer(descriptor) }.getOrNull()
    }

    if (renderer == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(HugeIcons.FileView, null, modifier = Modifier.size(40.dp))
                Text(
                    text = openError ?: "无法打开此 PDF（文件可能损坏或受密码保护）",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp),
                )
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        items((0 until pageCount).toList()) { pageIndex ->
            val bitmap = remember(renderer, pageIndex, zoom) {
                renderPage(renderer, pageIndex, zoom)
            }
            if (bitmap == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp)) { Text("页面渲染失败") }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoomChange, _ ->
                                onZoomChange(zoomChange)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .width((screenWidth * zoom).dp)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

private fun renderPage(renderer: PdfRenderer, pageIndex: Int, zoom: Float): Bitmap? {
    return runCatching {
        val page = renderer.openPage(pageIndex)
        try {
            val scale = 1.5f * zoom
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } finally {
            runCatching { page.close() }
        }
    }.getOrNull()
}