package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.MediaPlaybackService
import java.io.IOException

fun playMediaTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "play_media",
    description = "从头开始播放音视频文件，启用系统媒体控件（锁屏通知、蓝牙按钮）。会替换当前活动会话，如需继续之前播放请用恢复播放。支持 file://、content://、https:// 格式的源文件。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("source", buildJsonObject { put("type", "string"); put("description", "File path or URL to play") })
                put("title", buildJsonObject { put("type", "string"); put("description", "Track title for the media notification (optional)") })
                put("artist", buildJsonObject { put("type", "string"); put("description", "Artist name for the media notification (optional)") })
                put("album", buildJsonObject { put("type", "string"); put("description", "Album name for the media notification (optional)") })
                put("artwork_uri", buildJsonObject { put("type", "string"); put("description", "URI for album artwork image (optional)") })
            },
            required = listOf("source")
        )
    },
    execute = {
        wakeScreenIfNeeded(context)
        val params = it.jsonObject
        val source = params["source"]?.jsonPrimitive?.contentOrNull ?: error("source is required")
        val title = params["title"]?.jsonPrimitive?.contentOrNull
        val artist = params["artist"]?.jsonPrimitive?.contentOrNull
        val album = params["album"]?.jsonPrimitive?.contentOrNull
        val artworkUri = params["artwork_uri"]?.jsonPrimitive?.contentOrNull

        val payload = try {
            val intent = MediaPlaybackService.buildPlayIntent(context, source, title, artist, album, artworkUri)
            ContextCompat.startForegroundService(context, intent)
            buildJsonObject { put("success", true); put("source", source); put("session_active", true) }
        } catch (e: IOException) {
            buildJsonObject { put("error", e.message ?: "io error") }
        } catch (e: IllegalStateException) {
            buildJsonObject { put("error", e.message ?: "illegal state") }
        } catch (e: IllegalArgumentException) {
            buildJsonObject { put("error", e.message ?: "invalid argument") }
        } catch (e: SecurityException) {
            buildJsonObject { put("error", e.message ?: "security error") }
        }
        streamer.streamIfHeadless(invocationContext, "PlayMedia: ${source.take(60)}")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun stopMediaTool(context: Context): Tool = Tool(
    name = "stop_media",
    description = "停止当前播放并关闭媒体通知。会销毁播放器实例——如需临时暂停请用暂停播放。停止前会保存播放位置快照，以便恢复播放时可以大致从断点继续。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val wasPlaying = MediaPlaybackService.instance?.isPlaying ?: false
        val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply { action = MediaPlaybackService.ACTION_STOP }
        context.startService(intent)
        listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("was_playing", wasPlaying) }.toString()))
    }
)

fun pauseMediaTool(context: Context): Tool = Tool(
    name = "pause_media",
    description = "暂停当前播放的音频。使用恢复播放来继续。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val svc = MediaPlaybackService.instance
        val state = when {
            svc == null -> "no_session"
            !svc.isPlaying -> "already_paused"
            else -> {
                val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply { action = MediaPlaybackService.ACTION_PAUSE }
                context.startService(intent)
                "paused"
            }
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("success", state != "no_session"); put("state", state) }.toString()))
    }
)

fun resumeMediaTool(context: Context): Tool = Tool(
    name = "resume_media",
    description = "恢复播放。优先从当前会话的暂停位置继续；如果会话已被停止播放销毁，则从上次停止的快照恢复。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val svc = MediaPlaybackService.instance
        if (svc != null) {
            val state = if (svc.isPlaying) "already_playing" else {
                val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply { action = MediaPlaybackService.ACTION_PLAY }
                context.startService(intent)
                "playing"
            }
            return@Tool listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("state", state) }.toString()))
        }
        val snap = MediaPlaybackService.lastStoppedSnapshot
        if (snap != null) {
            val intent = MediaPlaybackService.buildPlayIntent(
                context, snap.source, snap.title, snap.artist, snap.album, snap.artworkUri, snap.positionMs,
            )
            ContextCompat.startForegroundService(context, intent)
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("success", true); put("state", "resumed_from_snapshot")
                put("source", snap.source); put("position_ms", snap.positionMs)
            }.toString()))
        }
        listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("state", "no_session") }.toString()))
    }
)

fun seekMediaTool(context: Context): Tool = Tool(
    name = "seek_media",
    description = "跳转到当前媒体会话的指定毫秒位置。播放或暂停状态下均可使用，保持原有播放/暂停状态。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject { put("position_ms", buildJsonObject { put("type", "integer"); put("description", "Target position in milliseconds") }) },
            required = listOf("position_ms"),
        )
    },
    execute = {
        val posMs = it.jsonObject["position_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: error("position_ms is required")
        val svc = MediaPlaybackService.instance
        if (svc == null) {
            listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", "no_session") }.toString()))
        } else {
            val intent = android.content.Intent(context, MediaPlaybackService::class.java).apply {
                action = MediaPlaybackService.ACTION_SEEK
                putExtra(MediaPlaybackService.EXTRA_POSITION_MS, posMs)
            }
            context.startService(intent)
            listOf(UIMessagePart.Text(buildJsonObject { put("success", true); put("position_ms", posMs) }.toString()))
        }
    }
)

fun getMediaStatusTool(): Tool = Tool(
    name = "get_media_status",
    description = "查询当前媒体播放状态：是否正在播放、来源、当前位置、总时长和元数据。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val svc = MediaPlaybackService.instance
        val payload = if (svc == null) {
            buildJsonObject { put("playing", false) }
        } else {
            buildJsonObject {
                put("playing", svc.isPlaying)
                svc.currentSource?.let { put("source", it) }
                put("position_ms", svc.readCurrentPositionMs())
                put("duration_ms", svc.durationMs)
                svc.currentTitle?.let { put("title", it) }
                svc.currentArtist?.let { put("artist", it) }
                svc.currentAlbum?.let { put("album", it) }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
