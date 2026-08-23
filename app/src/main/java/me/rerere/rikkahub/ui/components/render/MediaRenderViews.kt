package me.rerere.rikkahub.ui.components.render

import android.media.MediaPlayer
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import java.io.File

/** 图片内容: 全屏黑底可缩放查看 */
@Composable
fun ImageRenderView(
    imageFile: File,
    contentDescription: String?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        ZoomableAsyncImage(
            model = imageFile.toUri().toString(),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 视频内容: VideoView + MediaController 系统控制条 (播放/暂停/进度/音量) */
@Composable
fun VideoRenderView(videoFile: File) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setVideoURI(videoFile.toUri())
                    val controller = android.widget.MediaController(context)
                    setMediaController(controller)
                    controller.setAnchorView(this)
                    setOnPreparedListener { start() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 音频内容: MediaPlayer 播放控制 */
@Composable
fun AudioRenderView(audioFile: File) {
    val context = LocalContext.current
    val player = remember {
        MediaPlayer().apply {
            setDataSource(context, audioFile.toUri())
            setOnPreparedListener { start() }
            prepareAsync()
        }
    }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            position = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            if (duration <= 0L) {
                duration = runCatching { player.duration.toLong() }.getOrDefault(0L)
            }
            delay(500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formatTime(position) + " / " + formatTime(duration),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            onValueChange = { newPos ->
                position = newPos.toLong()
                runCatching { player.seekTo(newPos.toInt()) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        IconButton(
            onClick = {
                if (isPlaying) player.pause() else player.start()
                isPlaying = !isPlaying
            },
            modifier = Modifier.size(64.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) HugeIcons.Pause else HugeIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}