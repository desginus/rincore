package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.FloatingNotificationService

// 新渠道 ID: 旧渠道 rincore_ai_tool 以 IMPORTANCE_DEFAULT 创建,
// Android 不允许升级已存在渠道的 importance, 必须换 ID
private const val CHANNEL_ID = "rincore_ai_tool_v2"
private const val CHANNEL_NAME = "AI tool notifications"

private fun ensureChannel(context: Context) {
    val nm = NotificationManagerCompat.from(context)
    // 删除旧渠道, 让系统重新创建 (旧渠道仍会保留但不再使用)
    try {
        context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel("rincore_ai_tool")
    } catch (_: Throwable) {}

    val channel = NotificationChannelCompat.Builder(
        CHANNEL_ID,
        NotificationManager.IMPORTANCE_HIGH
    )
        .setName(CHANNEL_NAME)
        .setVibrationEnabled(true)
        .build()
    nm.createNotificationChannel(channel)
}

/**
 * 构建通知的内容意图: 启动 FloatingNotificationService 显示悬浮窗。
 * 传入 title/body/conversationId, 点击通知后弹出可拖动小窗,
 * 不覆盖主应用。
 */
private fun buildContentIntent(
    context: Context,
    id: Int,
    title: String,
    body: String,
    conversationId: String?
): PendingIntent {
    val intent = Intent(context, FloatingNotificationService::class.java).apply {
        putExtra(FloatingNotificationService.EXTRA_TITLE, title)
        putExtra(FloatingNotificationService.EXTRA_BODY, body)
        if (conversationId != null) {
            putExtra(FloatingNotificationService.EXTRA_CONVERSATION_ID, conversationId)
        }
    }
    return PendingIntent.getService(
        context,
        id,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

fun notificationTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
): Tool = Tool(
    name = "post_notification",
    description = "Post an Android notification on behalf of the user. Use sparingly — notifications are intrusive. " +
        "Clicking the notification opens a floating window with the content.",
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
        val body = params["body"]?.jsonPrimitive?.contentOrNull ?: ""
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
            .setContentIntent(
                buildContentIntent(
                    context,
                    id,
                    title,
                    body,
                    invocationContext.callerConversationId
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentText(body)
            // 始终设置 BigTextStyle, 确保长文本在通知栏可展开
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))

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
