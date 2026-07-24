package me.rerere.rikkahub.ui.pages.desk

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

@Composable
fun BrowserAgentOverlay(modifier: Modifier = Modifier) {
    var screenshotUrl by remember { mutableStateOf<String?>(null) }
    val markers = remember { mutableStateListOf<ScreenshotMarker>() }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    if (screenshotUrl == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("浏览器代理", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("在对话中让 AI 操作网页，截图自动显示。\n点击截图生成标号 ①②③...\n在对话中说「点①」指挥 AI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    } else {
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxSize()
                    .onSizeChanged { imageSize = it }
                    .pointerInput(screenshotUrl) {
                        detectTapGestures {
                            markers.add(ScreenshotMarker(
                                id = markers.size + 1,
                                x = it.x / imageSize.width,
                                y = it.y / imageSize.height,
                            ))
                        }
                    },
            ) {
                AsyncImage(screenshotUrl, "截图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    markers.forEach { m ->
                        val px = m.x * size.width; val py = m.y * size.height
                        val c = markerColors[m.id % markerColors.size]
                        drawCircle(c, 18f, Offset(px, py))
                        drawCircle(Color.White, 16f, Offset(px, py))
                        drawCircle(c, 14f, Offset(px, py))
                        drawContext.canvas.nativeCanvas.drawText(
                            m.id.toString(), px, py + 8f,
                            Paint().apply { color = android.graphics.Color.WHITE; textSize = 22f;
                                textAlign = Paint.Align.CENTER; isFakeBoldText = true })
                    }
                }
            }
            if (markers.isNotEmpty()) {
                Column(
                    modifier = Modifier.width(140.dp).fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.95f))
                        .padding(8.dp),
                ) {
                    Text("标号", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn { itemsIndexed(markers) { _, m ->
                        Row(Modifier.fillMaxWidth().background(
                            MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)
                        ).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(20.dp).background(
                                markerColors[m.id % markerColors.size], CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text(m.id.toString(), fontSize = 10.sp,
                                    color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("(${(m.x * 100).toInt()}%, ${(m.y * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }}
                }
            }
        }
    }
}

private val markerColors = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047),
    Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00ACC1),
)

data class ScreenshotMarker(
    val id: Int, val x: Float, val y: Float, val label: String = "",
)
