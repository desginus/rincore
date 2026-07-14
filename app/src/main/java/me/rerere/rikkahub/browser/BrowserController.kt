package me.rerere.rikkahub.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * Singleton bridge between the LLM browser tools and the headless WebView.
 *
 * Headless-only mode: the AI drives an offscreen WebView managed by
 * [HeadlessBrowserSession]. No visual BrowserActivity needed — screenshots are
 * streamed to cache files for the tool results.
 */
object BrowserController {

    private const val MAX_RECENT_ACTIONS = 20

    @Volatile
    var singleTaskTimeoutMs: Long = BrowserToolDefaults.DEFAULT_SINGLE_TASK_TIMEOUT_MS

    @Volatile
    var perToolTimeoutMs: Long = BrowserToolDefaults.DEFAULT_PER_TOOL_TIMEOUT_MS

    private const val TAG = "BrowserController"

    /** Cache subdir for browser screenshots. */
    const val SCREENSHOT_CACHE_SUBDIR = "browser-shots"

    /** Execution mode. Only Headless is used in RinCore. */
    sealed class Mode {
        data object Idle : Mode()
        data class Headless(val callerConvId: String, val webView: WebView) : Mode()
    }

    @Volatile
    private var mode: Mode = Mode.Idle

    private val bindLock = Any()
    private var bindDeferred = CompletableDeferred<Unit>()
    private val _recentActions = MutableStateFlow<List<String>>(emptyList())
    private var currentTaskStartedAt: Long? = null
    private var pendingTaskJob: Job? = null

    fun recentActionsFlow(): StateFlow<List<String>> = _recentActions.asStateFlow()

    /** Bind a headless WebView for [callerConvId]. Returns false if slot is taken. */
    fun bindHeadless(callerConvId: String, webView: WebView): Boolean {
        synchronized(bindLock) {
            return when (val current = mode) {
                is Mode.Headless ->
                    if (current.callerConvId == callerConvId) { true }
                    else { false }
                Mode.Idle -> {
                    mode = Mode.Headless(callerConvId, webView)
                    bindDeferred.complete(Unit)
                    true
                }
            }
        }
    }

    fun unbindHeadless(callerConvId: String) {
        synchronized(bindLock) {
            val m = mode
            if (m is Mode.Headless && m.callerConvId == callerConvId) {
                mode = Mode.Idle
                currentTaskStartedAt = null
                _recentActions.value = emptyList()
                bindDeferred = CompletableDeferred()
            }
        }
    }

    fun clearModeIfHeadless(callerConvId: String) {
        synchronized(bindLock) {
            val m = mode
            if (m is Mode.Headless && m.callerConvId == callerConvId) {
                mode = Mode.Idle
                currentTaskStartedAt = null
                _recentActions.value = emptyList()
                bindDeferred = CompletableDeferred()
            }
        }
    }

    fun isBound(): Boolean = activeWebView() != null
    fun currentUrl(): String? = activeWebView()?.url
    fun currentTitle(): String? = activeWebView()?.title

    fun appendAction(label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        _recentActions.value = (listOf(trimmed) + _recentActions.value).take(MAX_RECENT_ACTIONS)
    }

    fun stopCurrentTask() {
        pendingTaskJob?.cancel()
        pendingTaskJob = null
        currentTaskStartedAt = null
        appendAction("AI task stopped by user")
    }

    fun startTaskWindow() {
        currentTaskStartedAt = System.currentTimeMillis()
    }

    fun clearTaskWindow() {
        currentTaskStartedAt = null
    }

    fun isWithinTaskWindow(): Boolean {
        val started = currentTaskStartedAt ?: return true
        return System.currentTimeMillis() - started < singleTaskTimeoutMs
    }

    internal fun activeWebView(): WebView? = when (val m = mode) {
        is Mode.Headless -> m.webView
        Mode.Idle -> null
    }

    fun notOpenEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_not_open")
        put("recovery", "Call browser_open with a URL to launch the browser before invoking this tool.")
    }

    fun taskTimeoutEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_task_timeout")
        put("recovery", "Call browser_done with a summary; the per-task time cap has been reached.")
    }

    fun sessionLostEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_session_lost")
        put("recovery", "The headless browser session ended. Ask the user to retry.")
    }

    fun bindBusyEnvelope(): JsonObject = buildJsonObject {
        put("error", "browser_busy")
        put("recovery", "Another conversation is currently driving the browser. Wait for it to finish, then retry browser_open.")
    }

    /**
     * Capture the current WebView as a PNG to a cache file.
     * Returns the absolute path of the written file.
     */
    suspend fun captureScreenshot(): File? {
        val wv = activeWebView() ?: return null
        val context = wv.context.applicationContext ?: return null
        // Paint settle delay
        kotlinx.coroutines.delay(600L)
        return runCatching {
            withContext(Dispatchers.Main) {
                val w = wv.width.coerceAtLeast(1)
                val h = wv.height.coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    wv.draw(canvas)
                    val cacheDir = File(context.cacheDir, SCREENSHOT_CACHE_SUBDIR).apply { mkdirs() }
                    val out = File(cacheDir, "shot-${System.currentTimeMillis()}.png")
                    FileOutputStream(out).use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                    out
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrNull()
    }
}

/**
 * Handle / dispatch helper for browser tools.
 */
object BrowserControllerHandle {

    data class WithControllerScope(
        val controller: BrowserController,
        val webView: WebView,
    )

    suspend fun withController(
        block: suspend WithControllerScope.() -> JsonObject,
    ): JsonObject {
        val wv = BrowserController.activeWebView() ?: return BrowserController.notOpenEnvelope()
        if (!BrowserController.isWithinTaskWindow()) {
            return BrowserController.taskTimeoutEnvelope()
        }
        return withContext(Dispatchers.Main) {
            WithControllerScope(BrowserController, wv).block()
        }
    }
}

/**
 * Run JS on the WebView's main thread and return the JSON-encoded result.
 */
suspend fun WebView.evaluateJavascriptAsync(code: String, timeoutMs: Long = 8_000L): String? {
    val deferred = CompletableDeferred<String?>()
    withContext(Dispatchers.Main) {
        try {
            evaluateJavascript(code) { result -> deferred.complete(result) }
        } catch (e: Exception) {
            android.util.Log.w("BrowserController", "evaluateJavascriptAsync: threw", e)
            deferred.complete(null)
        }
    }
    return withTimeoutOrNull(timeoutMs) { deferred.await() }
}

/**
 * Wait for document.readyState === "complete". Polls every 200ms.
 */
suspend fun WebView.awaitReadyState(timeoutMs: Long = 8_000L): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val raw = evaluateJavascriptAsync("(function(){return document.readyState;})()", 1_500L)
        if (raw != null && raw.trim() == "\"complete\"") return true
        kotlinx.coroutines.delay(200)
    }
    return false
}
