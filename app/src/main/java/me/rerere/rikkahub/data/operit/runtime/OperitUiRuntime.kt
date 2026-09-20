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

    private companion object {
        val HOST_PRELUDE = """
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
