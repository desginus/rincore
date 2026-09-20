package me.rerere.rikkahub.ui.pages.operitui


/* ───【自研】岔路口计划·扩展 — 插件界面渲染页
 * 入口: 对话页右上角 (新建对话/消息列表左侧)
 * 流程: 列出带 UI 面板的已装插件 → 选中 → WebView 渲染 (Operit 原生面板 HTML)
 * 桥: WebView JS Proxy → __rinBridge → QuickJS 桥函数 (Tools.Files 等)
 * ─────────────────────────────────────────────────────────────── */
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.operit.market.InstalledPackage
import me.rerere.rikkahub.data.operit.market.InstalledPackageStore
import me.rerere.rikkahub.data.operit.runtime.OperitUiRuntime
import org.koin.compose.koinInject
import java.io.File

/** UI 面板条目: 包 + ui 脚本文件 + 面板名 */
data class OperitUiEntry(
    val pkg: InstalledPackage,
    val uiScript: File,
    val panelName: String,
)

/** 扫描包内 ui/<panel>/*.ui.js (实测形态: ui/guardian_panel/index.ui.js) */
fun findUiEntries(pkg: InstalledPackage): List<OperitUiEntry> {
    if (pkg.type != "package") return emptyList()
    val root = File(pkg.installPath)
    if (!root.isDirectory) return emptyList()
    val uiDir = File(root, "ui")
    if (!uiDir.isDirectory) return emptyList()
    return uiDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { panelDir ->
        val script = panelDir.listFiles()?.firstOrNull { it.name.endsWith(".ui.js") }
        script?.let { OperitUiEntry(pkg, it, panelDir.name) }
    } ?: emptyList()
}

@Composable
fun OperitUiPage(onBack: () -> Unit) {
    val installedStore: InstalledPackageStore = koinInject()
    val installed by installedStore.installedFlow.collectAsState(initial = emptyList())
    val entries = remember(installed) { installed.flatMap { findUiEntries(it) } }
    var selected by remember { mutableStateOf<OperitUiEntry?>(null) }

    val current = selected
    if (current == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("插件界面") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    "带 UI 面板的插件在此渲染其原生界面 (Operit 面板 HTML)。" +
                        "面板数据通过插件桥读写, 与对话中的工具共用同一份配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (entries.isEmpty()) {
                    Text(
                        "暂无带界面的插件。\n在应用市场安装支持面板的插件 (如「温柔巡检」) 并启用后, 此处会出现入口。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.pkg.entryId + "/" + it.panelName }) { entry ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clickable { selected = entry },
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        entry.pkg.title.ifBlank { entry.pkg.entryId },
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "面板: ${entry.panelName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        OperitUiRenderer(entry = current, onBack = { selected = null })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OperitUiRenderer(entry: OperitUiEntry, onBack: () -> Unit) {
    val runtime: OperitUiRuntime = koinInject()
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<OperitUiRuntime.UiSession?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(entry) {
        loading = true
        error = null
        runtime.open(entry.uiScript).fold(
            onSuccess = { session = it; loading = false },
            onFailure = { error = it.message ?: it.toString(); loading = false },
        )
    }

    DisposableEffect(entry) {
        onDispose {
            session?.close()
            session = null
            runCatching { webViewRef?.destroy() }
            webViewRef = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entry.pkg.title.ifBlank { entry.panelName }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("正在加载面板…", style = MaterialTheme.typography.bodySmall)
                }
                error != null -> Text(
                    "面板加载失败:\n$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                session != null -> {
                    val s = session!!
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                val bridge = RinBridge(s, scope) { wv -> webViewRef = wv }
                                addJavascriptInterface(bridge, "__rinBridge")
                                bridge.attach(this)
                                webViewRef = this
                                loadDataWithBaseURL(
                                    s.baseUrl,
                                    injectBridgeScript(s),
                                    "text/html",
                                    "utf-8",
                                    null,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** WebView 侧桥包装脚本 — Proxy 转发到 __rinBridge.call (异步 id 回填) */
private fun injectBridgeScript(session: OperitUiRuntime.UiSession): String {
    val namesJson = session.bridgeNames.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
    val bridgeJs = """
        (function () {
            var pending = {}; var seq = 0;
            function call(bridge, method, args) {
                return new Promise(function (resolve, reject) {
                    var id = ++seq;
                    pending[id] = { resolve: resolve, reject: reject };
                    try { __rinBridge.call(bridge, method, JSON.stringify(args || []), id); }
                    catch (e) { delete pending[id]; reject(e); }
                });
            }
            window.__rinResolve = function (id, resultJson, error) {
                var p = pending[id]; if (!p) return; delete pending[id];
                if (error) { p.reject(new Error(error)); return; }
                try {
                    var parsed = JSON.parse(resultJson);
                    if (parsed && parsed.ok) { p.resolve(parsed.value); }
                    else { p.reject(new Error((parsed && parsed.error) || 'bridge error')); }
                } catch (e) { p.reject(e); }
            };
            var names = $namesJson;
            names.forEach(function (n) {
                window[n] = new Proxy({}, {
                    get: function (t, m) {
                        if (typeof m !== 'string' || m === 'then' || m === 'toJSON') return undefined;
                        return function () { return call(n, m, Array.prototype.slice.call(arguments)); };
                    }
                });
            });
        })();
    """.trimIndent()
    val html = session.html
    return if (html.contains("<head>")) {
        html.replaceFirst("<head>", "<head><script>$bridgeJs</script>")
    } else if (html.contains("<html")) {
        html.replaceFirst("<html", "<html><script>$bridgeJs</script>")
    } else {
        "<script>$bridgeJs</script>" + html
    }
}

/** Kotlin 侧桥 (WebView JS 线程调用) — 回投 QuickJS 执行桥函数 */
private class RinBridge(
    private val session: OperitUiRuntime.UiSession,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onWebView: (WebView) -> Unit,
) {
    @Volatile
    private var webView: WebView? = null

    fun attach(wv: WebView) {
        webView = wv
        onWebView(wv)
    }

    @JavascriptInterface
    fun call(bridge: String, method: String, argsJson: String, id: Int) {
        scope.launch {
            val result = runCatching { session.invokeBridge(bridge, method, argsJson) }
                .getOrElse { e ->
                    val msg = JsonPrimitive(e.message ?: e.toString()).toString()
                    """{"ok":false,"error":$msg}"""
                }
            val resultLiteral = JsonPrimitive(result).toString()
            webView?.post {
                webView?.evaluateJavascript("window.__rinResolve($id, $resultLiteral, null);", null)
            }
        }
    }
}
