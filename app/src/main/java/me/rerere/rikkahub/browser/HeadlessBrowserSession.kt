package me.rerere.rikkahub.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import java.io.File

/**
 * Offscreen WebView host for AI-driven browsing.
 *
 * Parents the WebView to an unattached LinearLayout, drives measure/layout manually
 * with 1080x1920 viewport, and overrides visibility API so headless sessions work
 * on sites that gate behaviour on Page Visibility.
 */
class HeadlessBrowserSession(private val context: Context) {

    private var webView: WebView? = null
    private var host: LinearLayout? = null

    @Synchronized
    fun start(callerConvId: String): WebView {
        val existing = webView
        if (existing != null) return existing

        val profileDir = File(context.filesDir, "browser-profile")
        if (!profileDir.exists()) profileDir.mkdirs()

        CookieManager.getInstance().setAcceptCookie(true)

        val parent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        }

        val wv = WebView(context).apply {
            configureWebViewForRikka(this)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val toFile = request?.url?.scheme.equals("file", ignoreCase = true)
                    return toFile && view?.url?.startsWith("file:", ignoreCase = true) != true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view.evaluateJavascript(VISIBILITY_SHIM_JS, null)
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        parent.addView(wv, LinearLayout.LayoutParams(VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)

        host = parent
        webView = wv
        return wv
    }

    fun stop() {
        val wv = webView
        val h = host
        webView = null
        host = null
        if (wv == null) return
        val teardown = Runnable {
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                h?.removeView(wv)
                wv.destroy()
            }.onFailure {
                android.util.Log.w("HeadlessBrowserSession", "stop: teardown threw", it)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            teardown.run()
        } else {
            Handler(Looper.getMainLooper()).post(teardown)
        }
    }

    fun activeWebView(): WebView? = webView

    companion object {
        private const val VIEWPORT_WIDTH = 1080
        private const val VIEWPORT_HEIGHT = 1920

        private const val VISIBILITY_SHIM_JS = """
            (function(){
                try {
                    Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true});
                    Object.defineProperty(document, 'hidden', {value: false, configurable: true});
                } catch (e) { /* best-effort */ }
            })();
        """
    }
}

/**
 * Process-singleton pool keyed on calling conversation id.
 * One session per conv so multi-tool tasks reuse the same WebView (and cookies).
 * Idle eviction: sessions unused > singleTaskTimeoutMs are swept on getOrCreate.
 */
object HeadlessBrowserSessionPool {

    private class Entry(val session: HeadlessBrowserSession, var lastUsedAtMs: Long)

    private val sessions = mutableMapOf<String, Entry>()
    private val lock = Any()

    private val idleTtlMs: Long get() = BrowserController.singleTaskTimeoutMs

    fun getOrCreate(context: Context, callerConvId: String): HeadlessBrowserSession {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            sweepIdleLocked(now, keep = callerConvId)
            sessions[callerConvId]?.let { it.lastUsedAtMs = now; return it.session }
            val s = HeadlessBrowserSession(context.applicationContext ?: context)
            sessions[callerConvId] = Entry(s, now)
            return s
        }
    }

    private fun sweepIdleLocked(now: Long, keep: String?) {
        val ttl = idleTtlMs
        val stale = sessions.entries.filter { (id, entry) ->
            id != keep && (now - entry.lastUsedAtMs) > ttl
        }
        for ((id, entry) in stale) {
            runCatching { entry.session.stop() }
            sessions.remove(id)
            runCatching { BrowserController.clearModeIfHeadless(id) }
        }
    }

    fun release(callerConvId: String) {
        val e = synchronized(lock) { sessions.remove(callerConvId) } ?: return
        e.session.stop()
    }
}
