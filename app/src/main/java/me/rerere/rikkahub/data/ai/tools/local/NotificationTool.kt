package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val CHANNEL_ID = "rincore_ai_tool"
private const val CHANNEL_NAME = "AI tool notifications"

private fun ensureChannel(context: Context) {
    val channel = NotificationChannelCompat.Builder(
        CHANNEL_ID,
        NotificationManager.IMPORTANCE_HIGH
    )
        .setName(CHANNEL_NAME)
        .setVibrationEnabled(true)
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}

private fun buildContentIntent(context: Context, id: Int): PendingIntent {
    val intent = Intent(context, RouteActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return TaskStackBuilder.create(context)
        .addNextIntentWithParentStack(intent)
        .getPendingIntent(
            id,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )!!
}

fun notificationTool(
    context: Context,
): Tool = Tool(
    name = "post_notification",
    description = "Post an Android notification on behalf of the user. Use sparingly — notifications are intrusive.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional notification body text")
                })
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional notification id; defaults to a fresh auto-generated id")
                })
            },
            required = listOf("title")
        )
    },
    execute = {
        val params = it.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
            ?: error("title is required")
        val body = params["body"]?.jsonPrimitive?.contentOrNull
        val idParam = params["id"]?.jsonPrimitive?.intOrNull
        val id = idParam?.coerceIn(0, Int.MAX_VALUE)
            ?: (System.currentTimeMillis() / 1000).toInt()

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "notification permission not granted") }.toString()
                )
            )
        }

        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.small_icon)
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent(context, id))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (body != null) {
            builder.setContentText(body)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val payload = try {
            manager.notify(id, builder.build())
            buildJsonObject {
                put("success", true)
                put("id", id)
            }
        } catch (_: SecurityException) {
            buildJsonObject { put("error", "notification permission not granted") }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
