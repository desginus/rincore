package me.rerere.rikkahub.service

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

// Live Update 通知节流间隔：流式输出每个chunk都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

/**
 * 订阅 [AppEventBus] 上的聊天生成事件，负责后台生成相关的系统通知
 * （Live Update 进度通知和生成完成通知）。
 */
class ChatNotificationManager(
    private val context: Application,
    appScope: AppScope,
    eventBus: AppEventBus,
    private val settingsStore: SettingsStore,
) {
    private val liveUpdateLastSentAt = ConcurrentHashMap<Uuid, Long>()

    // ActivityLifecycleCallbacks: HyperOS 3 上 ProcessLifecycleOwner 回调不可靠,
    // 改用 resumed activity 计数器判断前后台
    @Volatile
    private var isForeground: Boolean = false
    private val resumedActivityCount = AtomicInteger(0)

    init {
        context.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (resumedActivityCount.incrementAndGet() > 0) {
                    isForeground = true
                    // 回到前台时取消对话完成通知 (notificationId=1)
                    context.cancelNotification(1)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumedActivityCount.decrementAndGet() <= 0) {
                    isForeground = false
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        appScope.launch(Dispatchers.Default) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.ChatGenerationUpdate -> handleGenerationUpdate(event)
                    is AppEvent.ChatGenerationEnded -> handleGenerationEnded(event)
                    else -> {}
                }
            }
        }
    }

    private fun handleGenerationUpdate(event: AppEvent.ChatGenerationUpdate) {
        if (isForeground) return
        val displaySetting = settingsStore.settingsFlow.value.displaySetting
        if (!displaySetting.enableNotificationOnMessageGeneration) return
        if (!displaySetting.enableLiveUpdateNotification) return

        val now = SystemClock.elapsedRealtime()
        val lastSentAt = liveUpdateLastSentAt[event.conversationId]
        if (lastSentAt != null && now - lastSentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS) return
        liveUpdateLastSentAt[event.conversationId] = now

        sendLiveUpdateNotification(event.conversationId, event.lastMessage, event.senderName)
    }

    private fun handleGenerationEnded(event: AppEvent.ChatGenerationEnded) {
        cancelLiveUpdateNotification(event.conversationId)

        val contentPreview = event.contentPreview ?: return
        if (isForeground) return
        if (!settingsStore.settingsFlow.value.displaySetting.enableNotificationOnMessageGeneration) return
        sendGenerationDoneNotification(event.conversationId, event.senderName, contentPreview)
    }

    private fun sendGenerationDoneNotification(
        conversationId: Uuid,
        senderName: String,
        contentPreview: String
    ) {
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = contentPreview
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        lastMessage: UIMessage,
        senderName: String
    ) {
        // 仅用稳定状态文本，不传流式内容——避免 HyperOS 动态通知做文本过渡动画导致卡顿
        val (chipText, statusText) = determineNotificationContent(lastMessage.parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    /** 返回 (chipText, statusText)。不返回流式内容文本。 */
    private fun determineNotificationContent(parts: List<UIMessagePart>): Pair<String, String> {
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            lastTool != null && !lastTool.isExecuted -> {
                val toolName = lastTool.toolName.substringAfterLast("__")
                context.getString(R.string.notification_live_update_chip_tool) to
                    context.getString(R.string.notification_live_update_tool, toolName)
            }
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                context.getString(R.string.notification_live_update_chip_thinking) to
                    context.getString(R.string.notification_live_update_thinking)
            }
            lastText != null -> {
                context.getString(R.string.notification_live_update_chip_writing) to
                    context.getString(R.string.notification_live_update_writing)
            }
            else -> {
                context.getString(R.string.notification_live_update_chip_writing) to
                    context.getString(R.string.notification_live_update_title)
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        liveUpdateLastSentAt.remove(conversationId)
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(intent)
            .getPendingIntent(
                conversationId.hashCode(),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )!!
    }
}
