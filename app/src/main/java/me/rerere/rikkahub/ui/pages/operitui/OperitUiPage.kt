/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
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
import me.rerere.rikkahub.data.operit.runtime.UiPanelRegistration
import org.koin.compose.koinInject
import java.io.File

/** UI 面板条目: 包 + ui 脚本文件 + 面板名 + 注册元数据 */
data class OperitUiEntry(
    val pkg: InstalledPackage,
    val uiScript: File,
    val panelName: String,
    val titleZh: String = "",
    val surface: String = "",
    val order: Int = 0,
    val registered: Boolean = false,
)

/** v4.5.35: 跑 main.js 发现注册的面板 (surface/title/order 元数据) */
suspend fun discoverUiEntries(pkg: InstalledPackage, runtime: OperitUiRuntime): List<OperitUiEntry> {
    val root = File(pkg.installPath)
    if (pkg.type != "package" || !root.isDirectory) return emptyList()
    val regs = runtime.discoverRegistrations(root)
    if (regs.isEmpty()) return emptyList()
    return regs.mapNotNull { reg ->
        val script = File(root, reg.screenRelPath)
        if (!script.exists()) return@mapNotNull null
        val panelName = script.parentFile?.name ?: reg.routeId
        OperitUiEntry(
            pkg = pkg,
            uiScript = script,
            panelName = panelName,
            titleZh = reg.titleZh.ifBlank { reg.titleEn },
            surface = reg.surface,
            order = reg.order,
            registered = true,
        )
    }
}

/** 扫描包内 ui/<panel> 目录下的 .ui.js (实测形态: ui/guardian_panel/index.ui.js) */
fun findUiEntries(pkg: InstalledPackage): List<OperitUiEntry> {
    if (pkg.type != "package") return emptyList()
    val root = File(pkg.installPath)
    if (!root.isDirectory) return emptyList()
    // v4.5.36: ui 目录位置按 manifest.main 推导 (DSH: dist/ui; guardian: ui)
    val mainRel = runCatching {
        val mf = File(root, "manifest.json")
        if (mf.exists()) {
            val m = kotlinx.serialization.json.Json.parseToJsonElement(mf.readText())
                as? kotlinx.serialization.json.JsonObject
            (m?.get("main") as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: "main.js"
        } else "main.js"
    }.getOrDefault("main.js")
    val mainDir = File(root, mainRel).parentFile ?: root
    val uiCandidates = buildList {
        add(File(mainDir, "ui"))
        if (mainDir != root) add(File(root, "ui"))
    }
    val uiDir = uiCandidates.firstOrNull { it.isDirectory } ?: return emptyList()
    return uiDir.listFiles()?.mapNotNull { f ->
        when {
            // 形态 A: ui/<panel>/index.ui.js (guardian)
            f.isDirectory -> f.listFiles()?.firstOrNull { it.name.endsWith(".ui.js") }
                ?.let { OperitUiEntry(pkg, it, f.name) }
            // 形态 B: ui/<name>.ui.js 直接文件 (messenger)
            f.isFile && f.name.endsWith(".ui.js") -> OperitUiEntry(pkg, f, f.name.removeSuffix(".ui.js"))
            else -> null
        }
    } ?: emptyList()
}

@Composable
fun OperitUiPage(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件界面") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding)) {
            OperitUiTabContent()
        }
    }
}

/**
 * v4.6.4: 面板内容块 (无 Scaffold) — 供"生态与插件"统一页 Tab 内嵌复用。
 * 面板 HTML 走独立 WebView 渲染管线, 与聊天/生态列表零耦合。
 */
@Composable
fun OperitUiTabContent() {
    val installedStore: InstalledPackageStore = koinInject()
    val runtime: OperitUiRuntime = koinInject()
    val installed by installedStore.installedFlow.collectAsState(initial = emptyList())
    var entries by remember { mutableStateOf<List<OperitUiEntry>>(emptyList()) }
    LaunchedEffect(installed) {
        // v4.5.35: 优先跑 main.js 注册 (surface/title/order); 失败则兜底扫描 ui 目录
        entries = installed.flatMap { pkg ->
            val regs = runCatching { discoverUiEntries(pkg, runtime) }.getOrDefault(emptyList())
            if (regs.isNotEmpty()) regs else findUiEntries(pkg)
        }
    }
    var selected by remember { mutableStateOf<OperitUiEntry?>(null) }

    val current = selected
    if (current == null) {
        Column(Modifier.fillMaxSize()) {
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
                    val sidebarEntries = entries.filter { it.surface == "main_sidebar_plugins" }
                    val otherEntries = entries.filter { it.surface != "main_sidebar_plugins" }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (sidebarEntries.isNotEmpty()) {
                            item(key = "grp_sidebar") {
                                Text(
                                    "侧边栏面板 (main_sidebar_plugins)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(sidebarEntries, key = { "sb_" + it.pkg.entryId + "/" + it.panelName }) { entry ->
                                OperitUiEntryCard(entry) { selected = entry }
                            }
                        }
                        if (otherEntries.isNotEmpty()) {
                            item(key = "grp_other") {
                                Text(
                                    if (sidebarEntries.isEmpty()) "插件面板" else "其他面板",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                            items(otherEntries, key = { "ot_" + it.pkg.entryId + "/" + it.panelName }) { entry ->
                                OperitUiEntryCard(entry) { selected = entry }
                            }
                        }
                    }
                }
        }
    } else {
        OperitUiRenderer(entry = current, onBack = { selected = null })
    }
}

@Composable
private fun OperitUiEntryCard(entry: OperitUiEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                entry.titleZh.ifBlank { entry.pkg.title }.ifBlank { entry.pkg.entryId },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("面板: ").append(entry.panelName)
                    if (entry.registered) append(" · 已注册")
                    if (entry.surface.isNotBlank()) append(" · ").append(entry.surface)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                                val du = s.directUrl
                                if (!du.isNullOrBlank()) {
                                    // v4.5.38: DSL 树含服务 URL (如 DSH 服务就绪) → 直连加载
                                    loadUrl(du)
                                } else {
                                    loadDataWithBaseURL(
                                        s.baseUrl,
                                        injectBridgeScript(s),
                                        "text/html",
                                        "utf-8",
                                        null,
                                    )
                                }
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
