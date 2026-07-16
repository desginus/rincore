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
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.theme.ExtendColors
import me.rerere.rikkahub.ui.theme.darkExtendColors
import me.rerere.rikkahub.ui.theme.lightExtendColors

/**
 * 悬浮窗通知服务: WindowManager 叠加层 + ComposeView.
 */
class FloatingNotificationService : Service() {

    private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var cv: ComposeView? = null
    private var lr: LifecycleRegistry? = null
    private var wp: WindowManager.LayoutParams? = null
    private var ix = 0; private var iy = 0
    private var itx = 0f; private var ity = 0f
    private var drag = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() { super.onCreate(); startFg() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || !canDraw()) { stopSelf(); return START_NOT_STICKY }
        show(extTitle(intent), extBody(intent), extConv(intent))
        return START_NOT_STICKY
    }

    private fun show(title: String, body: String, convId: String?) {
        dismiss()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // ComposeView WindowManager 三大件
        val lo = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        lr = lo.lifecycle as LifecycleRegistry
        lr!!.currentState = Lifecycle.State.CREATED

        val sso = object : SavedStateRegistryOwner {
            override val savedStateRegistry = SavedStateRegistry(this)
            override val lifecycle: Lifecycle get() = lr!!
        }
        val vmo = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

        cv = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lo)
            setViewTreeSavedStateRegistryOwner(sso)
            setViewTreeViewModelStoreOwner(vmo)

            setContent {
                val dark = isSystemInDarkTheme()
                val ex = if (dark) darkExtendColors() else lightExtendColors()
                val cs = if (dark) darkColorScheme() else lightColorScheme()
                MaterialTheme(colorScheme = cs) {
                    Content(title, body, convId, ex,
                        onClose = { stopSelf() },
                        onNav = { cid ->
                            startActivity(Intent(this@FloatingNotificationService, RouteActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("conversationId", cid)
                            })
                            stopSelf()
                        },
                        onCopy = {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("notification", "$title\n\n$body"))
                        }
                    )
                }
            }
        }

        cv!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = wp!!.x; iy = wp!!.y; itx = e.rawX; ity = e.rawY; drag = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - itx).toInt(); val dy = (e.rawY - ity).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) drag = true
                    if (drag) { wp!!.x = ix + dx; wp!!.y = iy + dy; wm?.updateViewLayout(cv, wp); true } else false
                }
                MotionEvent.ACTION_UP -> { if (drag) { wp!!.x = 0; wm?.updateViewLayout(cv, wp) }; drag }
                else -> false
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        wp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp2px(120) }

        lr!!.currentState = Lifecycle.State.RESUMED
        wm?.addView(cv, wp)
    }

    private fun dismiss() {
        cv?.let { wm?.removeView(it) }
        lr?.currentState = Lifecycle.State.DESTROYED
        lr = null; cv = null
    }

    override fun onDestroy() { dismiss(); svcScope.cancel(); super.onDestroy() }

    private fun canDraw() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun startFg() {
        val ch = "floating_window"
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(ch, NotificationManager.IMPORTANCE_LOW)
                .setName("Floating Window").setVibrationEnabled(false).setShowBadge(false).build()
        )
        startForeground(1001, NotificationCompat.Builder(this, ch)
            .setContentTitle("RinCore notification").setSmallIcon(R.drawable.small_icon).setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW).build())
    }

    private fun dp2px(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private fun extTitle(i: Intent) = i.getStringExtra(EXTRA_TITLE) ?: ""
        private fun extBody(i: Intent) = i.getStringExtra(EXTRA_BODY) ?: ""
        private fun extConv(i: Intent) = i.getStringExtra(EXTRA_CONVERSATION_ID)
    }
}

// ── Compose ──

@Composable
private fun Content(
    title: String, body: String, convId: String?, ex: ExtendColors,
    onClose: () -> Unit, onNav: (String) -> Unit, onCopy: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (dark) ex.gray3 else ex.gray1,
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold, color = if (dark) ex.gray10 else ex.gray9
                ), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Text("✕", fontSize = 16.sp, color = if (dark) ex.gray7 else ex.gray6)
                }
            }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Text(body, style = MaterialTheme.typography.bodyMedium.copy(color = if (dark) ex.gray7 else ex.gray6),
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                }
            }
            if (body.isNotBlank() || convId != null) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (body.isNotBlank())
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCopy), contentAlignment = Alignment.Center) {
                            Text("⎘", fontSize = 18.sp, color = if (dark) ex.gray7 else ex.gray6)
                        }
                    Spacer(Modifier.weight(1f))
                    if (convId != null)
                        TextButton(onClick = { onNav(convId) }) {
                            Text("转到对话", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                }
            }
        }
    }
}
