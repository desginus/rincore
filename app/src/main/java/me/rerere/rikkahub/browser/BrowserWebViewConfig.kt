package me.rerere.rikkahub.browser


/* ───【自研】BrowserWebViewConfig.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Single source of truth for WebView settings shared by the headless browser.
 * Fixes common white-page render issues: mixed content mode, hardware layer type,
 * autoplay user-gesture gate, and the "wv" user-agent suffix that breaks bot-sniff sites.
 */
internal fun configureWebViewForRikka(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        @Suppress("DEPRECATION")
        databaseEnabled = true
        allowFileAccess = true
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        allowContentAccess = false
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = false
        builtInZoomControls = true
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        userAgentString = userAgentString.replace("; wv)", ")")
    }
    webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
}
