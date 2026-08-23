package me.rerere.rikkahub.ui.components.render

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileView
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PDF 内容视图 (v3.9.8 修复手势)
 *
 * v3.9.7 根因: detectTransformGestures 无条件消费指针事件,
 * 单指滑动被每页手势吃掉导致 LazyColumn 无法滚动。
 * 修复: 自实现双指捏合缩放 (仅两指时计算距离比并回调),
 * 单指事件不消费, 滚动全部放行给 LazyColumn。
 * 位图渲染在 IO 协程, 主线程不阻塞; 双缓冲消除缩放闪烁。
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
            // v3.9.10 双缓冲: bitmap 不随 zoom 变化清空, 缩放时旧图继续显示
            // (拉伸), 后台重渲染新分辨率位图完成后替换 — 消除放大闪烁
            var bitmap by remember(renderer, pageIndex) {
                mutableStateOf<Bitmap?>(null)
            }
            androidx.compose.runtime.LaunchedEffect(renderer, pageIndex, zoom) {
                val newBmp = withContext(Dispatchers.IO) {
                    renderPage(renderer, pageIndex, zoom)
                }
                if (newBmp != null) bitmap = newBmp
            }
            val bmp = bitmap
            if (bmp == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp)) { Text("页面渲染中...", color = Color.Gray) }
            } else {
                val pageWidthPx = (screenWidth * zoom).dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                        .pointerInput(Unit) {
                            // 仅双指捏合缩放, 单指事件完全放行给滚动
                            var prevDist = 0f
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                prevDist = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        val dist = pressed[0].position.distanceTo(pressed[1].position)
                                        if (prevDist > 0f) {
                                            val change = dist / prevDist
                                            if (abs(change - 1f) > 0.02f) {
                                                onZoomChange(change)
                                            }
                                        }
                                        prevDist = dist
                                    } else {
                                        prevDist = 0f
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .width(pageWidthPx)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

private fun Offset.distanceTo(other: Offset): Float =
    kotlin.math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y))

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