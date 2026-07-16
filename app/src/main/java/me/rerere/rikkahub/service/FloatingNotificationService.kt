package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * 悬浮窗通知服务: 以 WindowManager 叠加层显示通知内容。
 * 类似微信小窗, 不覆盖主应用, 可拖动、可关闭。
 * 由 post_notification 工具的内容意图触发。
 */
class FloatingNotificationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var windowManager: WindowManager? = null
    private var floatingView: ViewGroup? = null
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

        val outerLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val contentView = buildFloatingContent(title, body, conversationId)
        outerLayout.addView(contentView)

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

        // 拖动处理在 FrameLayout 层
        outerLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams!!.x
                    initialY = windowParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                    }
                    if (isDragging) {
                        windowParams!!.x = initialX + dx
                        windowParams!!.y = initialY + dy
                        windowManager?.updateViewLayout(outerLayout, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 吸边: 贴到最近一侧
                        windowParams!!.x = 0
                        windowManager?.updateViewLayout(outerLayout, windowParams)
                    }
                    isDragging
                }
                else -> false
            }
        }

        floatingView = outerLayout
        windowManager?.addView(outerLayout, windowParams)
    }

    private fun buildFloatingContent(title: String, body: String, conversationId: String?): View {
        val dp16 = dpToPx(16)
        val dp12 = dpToPx(12)
        val dp8 = dpToPx(8)
        val dp4 = dpToPx(4)

        // 主容器
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp16, dp16, dp16, dp16)
            minimumWidth = dpToPx(280)
            // 圆角背景
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0F0F0"))
                cornerRadius = dpToPx(16).toFloat()
            }
            elevation = dpToPx(8).toFloat()
        }

        // 标题行
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp8)
        }

        val titleText = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            val typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.graphics.Typeface.create(null, 600, false)
            } else {
                android.graphics.Typeface.DEFAULT_BOLD
            }
            setTypeface(typeface)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val closeButton = Button(this).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.GRAY)
            setPadding(dp8, 0, dp8, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { stopSelf() }
        }

        titleRow.addView(titleText, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ))
        titleRow.addView(closeButton)
        card.addView(titleRow)

        // 正文
        if (body.isNotBlank()) {
            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dpToPx(300)
                )
            }
            val bodyText = TextView(this).apply {
                text = body
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, dp8)
            }
            scrollView.addView(bodyText)
            card.addView(scrollView)
        }

        // 跳转按钮
        if (conversationId != null) {
            val navButton = Button(this).apply {
                text = "转到对话"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1976D2"))
                setPadding(dp16, dp8, dp16, dp8)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp12 }
                setOnClickListener {
                    val intent = Intent(this@FloatingNotificationService, RouteActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra("conversationId", conversationId)
                    }
                    startActivity(intent)
                    stopSelf()
                }
            }
            card.addView(navButton)
        }

        return card
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

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
