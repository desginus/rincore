package me.rerere.rikkahub.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Moon02
import me.rerere.hugeicons.stroke.Sun01
import me.rerere.rikkahub.R

/**
 * 应用内 HTML 渲染 (v3.9.5)：
 * - 右上角日/月切换深色/浅色: 文本类内容黑白反转 (黑底白字), 彩色与图片保持
 * - WebView 捏合缩放 (setSupportZoom + builtInZoomControls, 隐藏系统控制条)
 * - JS/DOM 存储/混合内容完整可用, 页面内导航不跳外部
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlRenderDialog(
    htmlContent: String,
    fileName: String,
    onDismiss: () -> Unit,
) {
    var isDark by remember { mutableStateOf(false) }
    var renderedHtml by remember(htmlContent, isDark) {
        mutableStateOf(if (isDark) applyDarkTheme(htmlContent) else htmlContent)
    }
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(isDark) {
        renderedHtml = if (isDark) applyDarkTheme(htmlContent) else htmlContent
        webView?.loadDataWithBaseURL(null, renderedHtml, "text/html", "UTF-8", null)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF121212)
        else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(fileName, maxLines = 1)
                        Text(
                            text = if (isDark) "深色模式" else "浅色模式",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDark) androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.cancel),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isDark = !isDark }) {
                        Icon(
                            imageVector = if (isDark) HugeIcons.Sun01 else HugeIcons.Moon02,
                            contentDescription = "切换深色/浅色",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (isDark) androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                    else MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        // v3.9.5: 捏合缩放 — 所有文档渲染通用
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.mediaPlaybackRequiresUserGesture = false
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: android.webkit.WebResourceRequest?,
                            ): Boolean = false
                        }
                        loadDataWithBaseURL(null, renderedHtml, "text/html", "UTF-8", null)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 深色主题: 黑白反转 (背景/文字/表格线/标题), 彩色与图片保持原样 */
internal fun applyDarkTheme(html: String): String {
    val darkCss = """
        <style>
          html, body { background: #121212 !important; }
          body { color: #E0E0E0 !important; }
          h1,h2,h3,h4,h5,h6 { color: #F0F0F0 !important; }
          table { border-color: #444 !important; }
          td, th { border-color: #444 !important; color: #E0E0E0 !important; }
          pre { color: #E0E0E0 !important; background: transparent !important; }
          p, li, span, div, a { color: inherit !important; }
        </style>
    """.trimIndent()
    return if (html.contains("</head>")) {
        html.replace("</head>", darkCss + "</head>")
    } else {
        darkCss + html
    }
}