package me.rerere.rikkahub.data.operit.runtime


/* ───【自研】岔路口计划·扩展 — Operit 插件 UI 运行时 (WebView 桥)
 * Operit 插件界面形态 (实测): "HTML 承重、DSL 薄桥" —
 *   ui/<panel>/index.ui.js: Screen(ctx) 内建 HTML 字符串 +
 *   ctx.createWebViewController(name).addJavascriptInterface(桥名, {方法}) +
 *   ctx.UI.WebView({html, ...})
 * RinCore 实现: QuickJS 跑 ui.js (ctx 桩) → 取 html → Android WebView 渲染 →
 *   JS 侧 Proxy 包装桥 → __rinBridge.call → 回投 QuickJS 执行桥函数 (Tools.Files 等)
 * ─────────────────────────────────────────────────────────────── */
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** UI 面板注册项 — main.js 的 registerUiRoute/registerNavigationEntry 归一化产物
 * (对齐 Operit: screen 函数 → 脚本相对路径; surface: main_sidebar_plugins/toolbox) */
data class UiPanelRegistration(
    val routeId: String,
    val screenRelPath: String,
    val titleZh: String,
    val titleEn: String,
    val surface: String,
    val order: Int,
)

class OperitUiRuntime {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 打开一个插件 UI 面板 — 返回常驻会话 (持有 QuickJS 实例, 桥调用复用) */
    suspend fun open(uiScriptFile: File): Result<UiSession> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!uiScriptFile.exists()) return@withContext Result.failure(IllegalStateException("ui script not found: $uiScriptFile"))
        val instance = QuickJs.create(jobDispatcher = Dispatchers.IO)
        try {
            instance.evaluationTimeoutMillis = 60_000L
            // 宿主桥 (Kotlin 侧统一入口, WebView JS 线程调用)
            instance.function("__hostCall") { args ->
                val name = args.getOrNull(0) as? String ?: ""
                val argsJson = args.getOrNull(1) as? String ?: "[]"
                handleHostCall(name, argsJson)
            }
            instance.evaluate<Any?>(HOST_PRELUDE)
            instance.evaluate<Any?>(uiScriptFile.readText(), "operit_ui.js")
            // 调用 Screen(ctx)
            instance.evaluate<Any?>(
                """
                try {
                    var __screen = (typeof exports !== 'undefined' && exports && exports.default) ? exports.default
                        : (typeof Screen !== 'undefined' ? Screen : null);
                    if (!__screen) {
                        __uiError = 'no Screen/default export in ui script';
                    } else {
                        var __r = __screen(ctx);
                        if (__r && typeof __r.then === 'function') { await __r; }
                    }
                } catch (e) {
                    __uiError = String((e && e.message) || e);
                }
                """.trimIndent(),
            )
            val err = instance.evaluate<String?>("__uiError")
            if (!err.isNullOrBlank()) {
                instance.close()
                return@withContext Result.failure(IllegalStateException("ui script error: $err"))
            }
            val html = instance.evaluate<String?>("__uiResult && __uiResult.html") ?: ""
            if (html.isBlank()) {
                instance.close()
                return@withContext Result.failure(IllegalStateException("ui script produced no html (Screen 未返回 ctx.UI.WebView)"))
            }
            val baseUrl = instance.evaluate<String?>("(__uiResult && __uiResult.baseUrl) || 'about:blank'") ?: "about:blank"
            val bridgeNamesRaw = instance.evaluate<String?>("Object.keys(__uiBridges)") ?: ""
            Result.success(UiSession(instance, html, baseUrl, bridgeNamesRaw))
        } catch (e: Throwable) {
            runCatching { instance.close() }
            Result.failure(e)
        }
    }

    private fun handleHostCall(name: String, argsJson: String): String {
        // ui.js 的桥函数内部用 Tools.Files — 与脚本运行时同语义
        val args: List<kotlinx.serialization.json.JsonElement> = runCatching {
            (json.parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
        fun str(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
            is JsonPrimitive -> el.content
            null -> ""
            else -> el.toString()
        }
        return try {
            when (name) {
                "files.read" -> {
                    val f = resolvePath(str(args.getOrNull(0)))
                    if (!f.exists()) throw IllegalStateException("File not found: ${str(args.getOrNull(0))}")
                    buildString {
                        append("""{"success":true,"content":""")
                        append(JsonPrimitive(f.readText()))
                        append("}")
                    }
                }
                "files.write" -> {
                    val f = resolvePath(str(args.getOrNull(0)))
                    f.parentFile?.mkdirs()
                    if (args.getOrNull(2)?.let { it is JsonPrimitive && it.content == "true" } == true) {
                        f.appendText(str(args.getOrNull(1)))
                    } else {
                        f.writeText(str(args.getOrNull(1)))
                    }
                    """{"success":true}"""
                }
                "files.exists" -> {
                    val f = resolvePath(str(args.getOrNull(0)))
                    """{"exists":${f.exists()}}"""
                }
                "files.mkdir" -> {
                    resolvePath(str(args.getOrNull(0))).mkdirs()
                    """{"success":true}"""
                }
                else -> """{"success":false,"message":"capability not implemented in RinCore runtime yet: $name"}"""
            }
        } catch (e: Throwable) {
            buildString {
                append("""{"success":false,"message":""")
                append(JsonPrimitive(e.message ?: e.toString()))
                append("}")
            }
        }
    }

    private fun resolvePath(path: String): File {
        val root = filesRoot ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp", "operit_runtime").also { filesRoot = it }
        val normalized = path.replace('\\', '/')
        val relative = when {
            normalized.startsWith("/sdcard/") -> normalized.removePrefix("/sdcard/")
            normalized.startsWith("/") -> "sys/" + normalized.trimStart('/')
            else -> normalized
        }
        val f = File(root, relative)
        val canonical = f.canonicalFile
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.path
        val canonicalPath = canonical.path
        val inside = canonicalPath == rootPath || canonicalPath.startsWith(rootPath + File.separator)
        if (!inside) throw SecurityException("path escapes runtime root: $path")
        return canonical
    }

    /** 运行时文件根 (由 DI 注入 setFilesRoot) */
    @Volatile
    var filesRoot: File? = null

    inner class UiSession(
        private val instance: QuickJs,
        val html: String,
        val baseUrl: String,
        private val bridgeNamesRaw: String,
    ) {
        val bridgeNames: List<String> = bridgeNamesRaw
            .trim().removePrefix("[").removeSuffix("]")
            .split(",").map { it.trim().trim('"') }.filter { it.isNotBlank() }

        /** 在 QuickJS 里执行桥函数 (async 安全: evaluate 自动 drain) */
        suspend fun invokeBridge(bridge: String, method: String, argsJson: String): String {
            val bridgeLit = JsonPrimitive(bridge).toString()
            val methodLit = JsonPrimitive(method).toString()
            val argsLiteral = if (argsJson.isBlank()) "[]" else argsJson
            instance.evaluate<Any?>(
                """
                (function () {
                    __operitBridgeResult = null;
                    (async function () {
                        try {
                            var fn = (__uiBridges[$bridgeLit] || {})[$methodLit];
                            if (typeof fn !== 'function') {
                                __operitBridgeResult = JSON.stringify({ ok: false, error: 'bridge method not found: $bridge.$method' });
                                return;
                            }
                            var __args = $argsLiteral;
                            var r = await fn.apply(null, __args);
                            __operitBridgeResult = JSON.stringify({ ok: true, value: r === undefined ? null : r });
                        } catch (e) {
                            __operitBridgeResult = JSON.stringify({ ok: false, error: String((e && e.message) || e) });
                        }
                    })();
                })();
                """.trimIndent(),
            )
            return instance.evaluate<String?>("__operitBridgeResult")
                ?: """{"ok":false,"error":"bridge produced no result"}"""
        }

        fun close() {
            runCatching { instance.close() }
        }
    }

    /**
     * v4.5.35: 发现包的 UI 面板注册 — 跑 main.js 收集 ToolPkg 注册项
     * (对齐 Operit 的 JsToolPkgRegistration 机制:
     *  registerUiRoute → screen 函数→脚本路径, registerNavigationEntry → surface/order)
     * 返回按包根解析好的注册列表; main.js 缺失/异常时返回空 (调用方兜底扫描)
     */
    suspend fun discoverRegistrations(pkgRoot: File): List<UiPanelRegistration> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val mainJs = File(pkgRoot, "main.js")
            if (!mainJs.exists()) return@withContext emptyList()
            val instance = QuickJs.create(jobDispatcher = Dispatchers.IO)
            try {
                instance.evaluationTimeoutMillis = 30_000L
                instance.evaluate<Any?>(REGISTRATION_PRELUDE)
                instance.evaluate<Any?>(mainJs.readText(), "toolpkg_main.js")
                instance.evaluate<Any?>(
                    """
                    try {
                        var __fn = (typeof exports !== 'undefined' && exports && typeof exports.registerToolPkg === 'function')
                            ? exports.registerToolPkg : null;
                        if (__fn) { __fn(); } else { __regError = 'no registerToolPkg export'; }
                    } catch (e) { __regError = String((e && e.message) || e); }
                    """.trimIndent(),
                )
                val jsonStr = instance.evaluate<String?>(
                    "JSON.stringify({ routes: __uiRoutes, entries: __navEntries })",
                ) ?: "{}"
                parseRegistrations(jsonStr)
            } catch (e: Throwable) {
                emptyList()
            } finally {
                runCatching { instance.close() }
            }
        }

    private fun parseRegistrations(jsonStr: String): List<UiPanelRegistration> {
        return runCatching {
            val root = json.parseToJsonElement(jsonStr) as? kotlinx.serialization.json.JsonObject
                ?: return emptyList()
            val routes = (root["routes"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val id = (o["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val screenPath = (o["screenPath"] as? JsonPrimitive)?.content
                if (id.isBlank() || screenPath.isNullOrBlank()) return@mapNotNull null
                val title = o["title"] as? kotlinx.serialization.json.JsonObject
                Triple(
                    id,
                    screenPath,
                    Pair(
                        (title?.get("zh") as? JsonPrimitive)?.content ?: "",
                        (title?.get("en") as? JsonPrimitive)?.content ?: "",
                    ),
                )
            } ?: emptyList()
            val entries = (root["entries"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val route = (o["route"] as? JsonPrimitive)?.content ?: ""
                val surface = (o["surface"] as? JsonPrimitive)?.content ?: ""
                val order = ((o["order"] as? JsonPrimitive)?.content?.toIntOrNull()) ?: 0
                val title = o["title"] as? kotlinx.serialization.json.JsonObject
                NavEntry(
                    route = route,
                    surface = surface,
                    order = order,
                    titleZh = (title?.get("zh") as? JsonPrimitive)?.content ?: "",
                    titleEn = (title?.get("en") as? JsonPrimitive)?.content ?: "",
                )
            } ?: emptyList()
            routes.map { (id, screenPath, titles) ->
                val nav = entries.firstOrNull { it.route.endsWith(id) } ?: entries.firstOrNull { it.route.contains(id) }
                UiPanelRegistration(
                    routeId = id,
                    screenRelPath = screenPath.replace('\\', '/').trimStart('/'),
                    titleZh = nav?.titleZh?.takeIf { it.isNotBlank() } ?: titles.first,
                    titleEn = nav?.titleEn?.takeIf { it.isNotBlank() } ?: titles.second,
                    surface = nav?.surface ?: "",
                    order = nav?.order ?: 0,
                )
            }.sortedWith(compareBy({ if (it.surface == "main_sidebar_plugins") 0 else 1 }, { it.order }))
        }.getOrDefault(emptyList())
    }

    private data class NavEntry(
        val route: String,
        val surface: String,
        val order: Int,
        val titleZh: String,
        val titleEn: String,
    )

    private companion object {
        val REGISTRATION_PRELUDE = """
            // v4.5.35: ToolPkg 注册桩 — 对齐 Operit JsToolPkgRegistration
            var exports = {};
            var module = { exports: exports };
            var __regError = null;
            var __uiRoutes = [];
            var __navEntries = [];
            function require(path) {
                var norm = String(path || '').replace(/^\.\//, '');
                return { default: { __uiPath: norm }, __esModule: true };
            }
            var ToolPkg = {
                _m: function () { return null; },
                registerUiRoute: function (def) {
                    def = def || {};
                    __uiRoutes.push({
                        id: String(def.id || ''),
                        runtime: String(def.runtime || 'compose_dsl'),
                        title: def.title || {},
                        screenPath: (def.screen && def.screen.__uiPath) ? String(def.screen.__uiPath) : null,
                        keepAlive: !!def.keepAlive
                    });
                },
                registerNavigationEntry: function (def) {
                    def = def || {};
                    __navEntries.push({
                        id: String(def.id || ''),
                        route: String(def.route || ''),
                        surface: String(def.surface || '').toLowerCase(),
                        title: def.title || {},
                        order: (typeof def.order === 'number') ? def.order : 0
                    });
                },
                registerToolboxUiModule: function () {},
                registerDesktopWidget: function () {},
                registerAiProvider: function () {},
                readResource: function () { return null; },
                getConfigDir: function () { return '/tmp/operit_cfg'; }
            };
            var globalThisRef = this;
        """.trimIndent()

        val HOST_PRELUDE = """
            // v4.5.35: exports/module 全局注入 (ui.js 结尾 exports.default = Screen —
            // 与脚本运行时同款缺口: 桩实验环境掩盖, 实机 ReferenceError)
            var exports = {};
            var module = { exports: exports };
            // v4.5.34: Operit 插件 UI 宿主桩 (ctx) — "HTML 承重、DSL 薄桥"
            var __uiBridges = {};
            var __uiResult = null;
            var __uiError = null;
            var __operitBridgeResult = null;
            var ctx = {
                createWebViewController: function (name) {
                    var bridges = {};
                    __uiBridges[name] = bridges;
                    return {
                        addJavascriptInterface: function (bridgeName, impl) { bridges[bridgeName] = impl; },
                        setWebView: function () {},
                        evaluateJavascript: function () {},
                        reload: function () {}
                    };
                },
                UI: {
                    WebView: function (opts) {
                        opts = opts || {};
                        __uiResult = {
                            html: String(opts.html == null ? '' : opts.html),
                            baseUrl: String(opts.baseUrl == null ? 'about:blank' : opts.baseUrl)
                        };
                        return { __type: 'webview' };
                    }
                },
                log: function () {},
                getContext: function () { return {}; },
                createScreen: function () { return {}; }
            };
            var Tools = {
                Files: {
                    read: function (p) { return JSON.parse(__hostCall('files.read', JSON.stringify([p]))); },
                    write: function (p, c, a) { return JSON.parse(__hostCall('files.write', JSON.stringify([p, c, a === true]))); },
                    makeDirectory: function (p, r) { return JSON.parse(__hostCall('files.mkdir', JSON.stringify([p, r === true]))); },
                    exists: function (p) { return JSON.parse(__hostCall('files.exists', JSON.stringify([p]))); }
                },
                System: {
                    shell: function (cmd) { return __notImplemented('system.shell'); },
                    exec: function (cmd) { return __notImplemented('system.exec'); },
                    terminal: function (cmd) { return __notImplemented('system.terminal'); },
                    sleep: function (ms) { return Promise.resolve(); }
                }
            };
            function __notImplemented(name) {
                return { success: false, message: 'capability not implemented in RinCore runtime yet: ' + name };
            }
            if (typeof setTimeout === 'undefined') {
                var setTimeout = function (fn) { if (typeof fn === 'function') { try { fn(); } catch (e) {} } return 0; };
                var clearTimeout = function () {};
                var setInterval = function () { return 0; };
                var clearInterval = function () {};
            }
        """.trimIndent()
    }
}
