package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.ui.theme.ColorMode

/**
 * 悬浮窗通知服务: 以 WindowManager 叠加层显示通知内容。
 * UI 完全对齐应用内 Material3 消息气泡风格 —
 * surfaceContainerHigh + RoundedCornerShape(16dp) + 应用字体/色彩。
 */
class FloatingNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var floatingView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !canDrawOverlays()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)

        showFloatingWindow(title, body, conversationId)
        return START_NOT_STICKY
    }

    private fun showFloatingWindow(title: String, body: String, conversationId: String?) {
        dismissFloatingWindow()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val composeView = ComposeView(this).apply {
            setContent {
                RikkahubTheme(colorMode = ColorMode.SYSTEM) {
                    FloatingWindowContent(
                        title = title,
                        body = body,
                        onClose = { stopSelf() },
                        onNavigateToChat = { convId ->
                            val intent = Intent(this@FloatingNotificationService, RouteActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("conversationId", convId)
                            }
                            startActivity(intent)
                            stopSelf()
                        },
                        conversationId = conversationId,
                        onCopyAll = {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("notification", "$title\n\n$body"))
                        }
                    )
                }
            }
        }

        // 拖动处理放在 ComposeView 外层
        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams!!.x
                    initialY = windowParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // 让 Compose 也收到 DOWN 事件
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        isDragging = true
                    }
                    if (isDragging) {
                        windowParams!!.x = initialX + dx
                        windowParams!!.y = initialY + dy
                        windowManager?.updateViewLayout(composeView, windowParams)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 吸边: 复位 X
                        windowParams!!.x = 0
                        windowManager?.updateViewLayout(composeView, windowParams)
                    }
                    isDragging
                }
                else -> false
            }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(120)
        }

        floatingView = composeView
        windowManager?.addView(composeView, windowParams)
    }

    private fun dismissFloatingWindow() {
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
    }

    override fun onDestroy() {
        dismissFloatingWindow()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun canDrawOverlays(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun startForegroundNotification() {
        val channelId = "floating_window"
        val channel = NotificationChannelCompat.Builder(channelId, NotificationManager.IMPORTANCE_LOW)
            .setName("Floating Window")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(this).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("RinCore notification")
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1001, notification)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}

// ─────────────────── Compose 内容 ───────────────────

@Composable
private fun FloatingWindowContent(
    title: String,
    body: String,
    conversationId: String?,
    onClose: () -> Unit,
    onNavigateToChat: (String) -> Unit,
    onCopyAll: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    // 消息气泡风格: 圆角 16dp, 背景 surfaceContainerHigh
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // ── 标题行 + 关闭 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.onSurface
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                // 关闭按钮 — 圆形区域风格
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }

            // ── 正文 ──
            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                // 用 SelectionContainer 支持文本选择, 类同 ChatMessage
                SelectionContainer {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            }

            // ── 底部操作栏: 复制 + 转到对话 ──
            if (body.isNotBlank() || conversationId != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 复制按钮 — 圆形图标风格, 对齐 ChatMessageActionButtons
                    if (body.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { onCopyAll() },
                            contentAlignment = Alignment.Center
                        ) {
                            // 用文本模拟复制图标, 避免引入 HugeIcons 依赖
                            Text(
                                text = "⎘",
                                fontSize = 18.sp,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 转到对话按钮
                    if (conversationId != null) {
                        TextButton(onClick = { onNavigateToChat(conversationId) }) {
                            Text(
                                text = "转到对话",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
