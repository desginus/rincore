/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
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

/**
 * v4.5.39: Operit JS 通用宿主环境 (共享层) — 所有 JS 执行点统一注入,
 * 杜绝"某个 prelude 有、另一个没有"的缺口复发 (require 缺口实机事故复盘)。
 * 覆盖: exports/module/require(万能 Proxy 桩)/Icons/Java/console/setTimeout/__notImplemented
 */
internal val OPERIT_COMMON_PRELUDE = """
    // ═══ RinCore 通用宿主环境 (共享层 v4.5.39) ═══
    var exports = {};
    var module = { exports: exports };
    // require 桩: 任意相对路径模块 → 万能 Proxy (属性访问返回可调用 stub,
    // 调用返回结构化失败; __uiPath 供注册机制解析 screen 来源文件)
    function require(path) {
        var norm = String(path || '').replace(/^\.\.\//g, '').replace(/^\.\//, '');
        var target = { __uiPath: norm, __esModule: true };
        var stubFn = function () { return Promise.resolve({ success: false, message: 'capability not available in RinCore runtime yet' }); };
        var stub = null;
        stub = new Proxy(target, {
            get: function (t, k) {
                if (k in t) return t[k];
                if (k === 'then') return undefined;
                if (k === 'default') return stub;
                return stubFn;
            }
        });
        return stub;
    }
    // 图标桩 (任意名返回 null)
    var Icons = new Proxy({}, { get: function () { return null; } });
    // Java 桥宽容桩 (getApplicationContext → null; 调用方 try/catch 兜底)
    var Java = {
        getApplicationContext: function () { return null; },
        getContext: function () { return null; }
    };
    // console / 定时器 条件桩 (QuickJS 可能内置, 存在则不覆盖)
    if (typeof console === 'undefined') {
        var console = { log: function () {}, info: function () {}, warn: function () {}, error: function () {}, debug: function () {} };
    }
    if (typeof setTimeout === 'undefined') {
        var setTimeout = function (fn) { if (typeof fn === 'function') { try { fn(); } catch (e) {} } return 0; };
        var clearTimeout = function () {};
        var setInterval = function () { return 0; };
        var clearInterval = function () {};
    }
    function __notImplemented(name) {
        return { success: false, message: 'capability not implemented in RinCore runtime yet: ' + name };
    }
""".trimIndent()

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
            instance.evaluate<Any?>(HOST_PRELUDE + "\n;null;")
            instance.evaluate<Any?>(uiScriptFile.readText() + "\n;null;", "operit_ui.js")
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
                        if (__r && typeof __r.then === 'function') { __r = await __r; }
                        __screenReturn = __r;
                    }
                } catch (e) {
                    __uiError = String((e && e.message) || e);
                }
                // v4.5.38: 统一收尾 — WebView 直返 (HTML 承重型) 或 DSL 虚拟树转 HTML
                try {
                    var __ret = __screenReturn;
                    if (__ret && __ret.__rinNode === 'webview') {
                        __uiResult = {
                            html: String(__ret.props.html == null ? '' : __ret.props.html),
                            baseUrl: String(__ret.props.baseUrl == null ? 'about:blank' : __ret.props.baseUrl),
                            mode: 'html'
                        };
                    } else if (__ret != null && typeof __rinRenderDocument === 'function') {
                        __uiResult = { html: __rinRenderDocument(__ret), baseUrl: 'about:blank', mode: 'dsl' };
                    }
                } catch (e) {
                    __uiError = 'render failed: ' + String((e && e.message) || e);
                }
                ;null;
                """.trimIndent(),
            )
            val err = instance.evaluate<String?>(jsSafeString("__uiError"))
            if (!err.isNullOrBlank()) {
                instance.close()
                return@withContext Result.failure(IllegalStateException("ui script error: $err"))
            }
            val html = instance.evaluate<String?>(jsSafeString("__uiResult && __uiResult.html")) ?: ""
            if (html.isBlank()) {
                instance.close()
                return@withContext Result.failure(IllegalStateException("ui script produced no html (Screen 未返回 ctx.UI.WebView)"))
            }
            val baseUrl = instance.evaluate<String?>(jsSafeString("(__uiResult && __uiResult.baseUrl) || 'about:blank'")) ?: "about:blank"
            // v4.5.36 修复: JS Array 直接 evaluate<String?> 会抛
            // "No such type converter to convert 'kotlin.collections.List<*>' to 'kotlin.String?'"
            // v4.5.38: __uiBridges 是两层结构 (controller name → bridge name → methods),
            // 提取全部二层桥名 (HTML 里调用的名字, 如 Guardian)
            val bridgeNamesRaw = instance.evaluate<String?>(
                jsSafeString(
                    "(function(){ var names=[]; for (var k in __uiBridges) { var b = __uiBridges[k]; " +
                        "for (var n in b) names.push(n); } return names; })()",
                ),
            ) ?: ""
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
        /** v4.5.38: DSL 树中若含 WebView(url) 占位 (如 DSH 服务就绪后), 页面直接 loadUrl */
        val directUrl: String? = Regex("data-rin-url=\"([^\"]+)\"")
            .find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

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
                            var fn = null;
                            for (var __ck in __uiBridges) {
                                var __cb = __uiBridges[__ck];
                                if (__cb && __cb[$bridgeLit] && typeof __cb[$bridgeLit][$methodLit] === 'function') {
                                    fn = __cb[$bridgeLit][$methodLit];
                                    break;
                                }
                            }
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
                ;null;
                """.trimIndent(),
            )
            return instance.evaluate<String?>(jsSafeString("__operitBridgeResult"))
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
            // v4.5.36: 入口路径读 manifest.main (DSH: "dist/main.js"; guardian: "main.js")
            val manifestFile = File(pkgRoot, "manifest.json")
            val mainRel = runCatching {
                if (manifestFile.exists()) {
                    val m = json.parseToJsonElement(manifestFile.readText()) as? kotlinx.serialization.json.JsonObject
                    (m?.get("main") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: "main.js"
                } else "main.js"
            }.getOrDefault("main.js")
            val mainJs = File(pkgRoot, mainRel)
            if (!mainJs.exists()) return@withContext emptyList()
            val instance = QuickJs.create(jobDispatcher = Dispatchers.IO)
            try {
                instance.evaluationTimeoutMillis = 30_000L
                instance.evaluate<Any?>(REGISTRATION_PRELUDE + "\n;null;")
                instance.evaluate<Any?>(mainJs.readText() + "\n;null;", "toolpkg_main.js")
                instance.evaluate<Any?>(
                    """
                    try {
                        var __fn = (typeof exports !== 'undefined' && exports && typeof exports.registerToolPkg === 'function')
                            ? exports.registerToolPkg : null;
                        if (__fn) { __fn(); } else { __regError = 'no registerToolPkg export'; }
                    } catch (e) { __regError = String((e && e.message) || e); }
                    ;null;
                    """.trimIndent(),
                )
                val jsonStr = instance.evaluate<String?>(
                    jsSafeString("JSON.stringify({ routes: __uiRoutes, entries: __navEntries })"),
                ) ?: "{}"
                // screen 相对 main.js 所在目录 — 拼接为相对包根路径
                val mainDirRel = mainJs.parentFile
                    ?.relativeTo(pkgRoot)?.path?.replace('\\', '/')?.takeIf { it != "." } ?: ""
                parseRegistrations(jsonStr, mainDirRel)
            } catch (e: Throwable) {
                emptyList()
            } finally {
                runCatching { instance.close() }
            }
        }

    private fun parseRegistrations(jsonStr: String, mainDirRel: String): List<UiPanelRegistration> {
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
                val rel = screenPath.replace('\\', '/').trimStart('/')
                UiPanelRegistration(
                    routeId = id,
                    screenRelPath = if (mainDirRel.isBlank()) rel else "$mainDirRel/$rel",
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
        /**
         * v4.5.37 根治: JS 侧类型归一化 —
         * dokar3 evaluate<String?> 对 JS Array/Object 会抛
         * "No such type converter to convert 'kotlin.collections.List<*>' to 'kotlin.String?'"。
         * 所有跨边界取值经此包裹: 任意 JS 值 → string | null (永不向 Kotlin 暴露数组/对象/函数)。
         */
        fun jsSafeString(expr: String): String =
            "(function(){ var __v = ($expr); if (__v === null || __v === undefined) return null; " +
                "if (typeof __v === 'string') return __v; " +
                "var __s = JSON.stringify(__v); return (__s === undefined) ? String(__v) : __s; })()"

        val REGISTRATION_PRELUDE = OPERIT_COMMON_PRELUDE + """
            // v4.5.35: ToolPkg 注册桩 — 对齐 Operit JsToolPkgRegistration
            // (exports/require/Icons/Java 由共享层提供; require 的 __uiPath 供 screen 来源解析)
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
                registerToolboxUiModule: function (def) {
                    def = def || {};
                    __uiRoutes.push({
                        id: String(def.id || ''),
                        runtime: String(def.runtime || 'compose_dsl'),
                        title: def.title || {},
                        screenPath: (def.screen && def.screen.__uiPath) ? String(def.screen.__uiPath) : null,
                        keepAlive: !!def.keepAlive,
                        surfaceHint: 'toolbox'
                    });
                },
                registerDesktopWidget: function () {},
                registerAiProvider: function () {},
                registerPromptInputHook: function () {},
                registerInputMenuTogglePlugin: function () {},
                registerChatInputHook: function () {},
                registerChatViewHook: function () {},
                registerChatMessageHook: function () {},
                registerToolLifecycleHook: function () {},
                registerMessageProcessingPlugin: function () {},
                registerXmlRenderPlugin: function () {},
                registerSummaryGenerateHook: function () {},
                readResource: function () { return null; },
                getConfigDir: function () { return '/tmp/operit_cfg'; },
                ipc: {
                    on: function (channel, handler) { __ipcHandlers[String(channel || '')] = handler; },
                    invoke: function () { return Promise.reject(new Error('ipc.invoke not implemented in RinCore runtime yet')); },
                    emit: function () {}
                }
            };
            var __ipcHandlers = {};
            var globalThisRef = this;
        """.trimIndent()

        val HOST_PRELUDE = OPERIT_COMMON_PRELUDE + """
            // v4.5.34: Operit 插件 UI 宿主桩 (ctx) — "HTML 承重、DSL 薄桥"
            var __uiBridges = {};
            var __uiResult = null;
            var __uiError = null;
            var __screenReturn = null;
            var __operitBridgeResult = null;
            var __rinState = {};
            var __rinStateSeq = 0;
            function __rinNode(type, props, children) {
                // children 归一化: 单 node / 数组 / null 全部接受 (DSH 有 UI.Box({...}, webContent) 单 node 用法)
                var ch = [];
                if (children != null) { ch = Array.isArray(children) ? children : [children]; }
                return { __rinNode: type, props: props || {}, children: ch };
            }
            function __rinColor(hex) {
                var c = { __color: hex, copy: null, toString: null };
                c.copy = function (o) {
                    var a = (o && o.alpha != null) ? o.alpha : 1;
                    if (a >= 1) return __rinColor(hex);
                    var r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16);
                    return __rinColor('rgba(' + r + ',' + g + ',' + b + ',' + a + ')');
                };
                c.toString = function () { return hex; };
                return c;
            }
            function __rinColorScheme() {
                return {
                    primary: __rinColor('#A8C7FA'), onPrimary: __rinColor('#00325A'),
                    surface: __rinColor('#131318'), onSurface: __rinColor('#E5E1E9'),
                    surfaceVariant: __rinColor('#47464F'), onSurfaceVariant: __rinColor('#C8C5D0'),
                    background: __rinColor('#131318'), onBackground: __rinColor('#E5E1E9'),
                    error: __rinColor('#FFB4AB'), onError: __rinColor('#690005'),
                    outline: __rinColor('#938F99'), secondaryContainer: __rinColor('#47464F'),
                    onSecondaryContainer: __rinColor('#E5E1E9'), tertiaryContainer: __rinColor('#633B48'),
                    onTertiaryContainer: __rinColor('#FFD8E4')
                };
            }
            function __rinCssColor(v) {
                if (v == null) return null;
                if (typeof v === 'string') return v;
                if (v.__color) return v.__color;
                if (typeof v.toString === 'function') return String(v);
                return null;
            }
            function __rinEsc(s) {
                return String(s == null ? '' : s)
                    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
            }
            function __rinStyle(kind, p) {
                var s = [];
                if (p.fillMaxWidth) s.push('width:100%');
                if (p.fillMaxSize) s.push('width:100%');
                if (p.fillMaxHeight) s.push('height:100%');
                if (p.weight != null) s.push('flex:' + p.weight + ' 1 0%');
                if (p.padding != null) s.push('padding:' + p.padding + 'px');
                if (p.height != null) s.push('height:' + p.height + 'px');
                if (p.width != null) s.push('width:' + p.width + 'px;flex-shrink:0');
                if (p.background != null) { var bg = __rinCssColor(p.background); if (bg) s.push('background:' + bg); }
                if (p.containerColor != null) { var cc = __rinCssColor(p.containerColor); if (cc) s.push('background:' + cc); }
                if (p.color != null) { var c1 = __rinCssColor(p.color); if (c1) s.push('color:' + c1); }
                if (p.contentColor != null) { var c2 = __rinCssColor(p.contentColor); if (c2) s.push('color:' + c2); }
                if (p.tint != null) { var c3 = __rinCssColor(p.tint); if (c3) s.push('color:' + c3); }
                if (p.shape != null && p.shape.cornerRadius != null) s.push('border-radius:' + p.shape.cornerRadius + 'px');
                if (p.cornerRadius != null) s.push('border-radius:' + p.cornerRadius + 'px');
                if (kind === 'row') s.push('display:flex;flex-direction:row;align-items:center;flex-wrap:wrap');
                if (kind === 'column' || kind === 'box' || kind === 'surface') s.push('display:flex;flex-direction:column');
                if (p.verticalAlignment === 'center') s.push('align-items:center');
                if (p.horizontalAlignment === 'center') s.push('align-items:center;text-align:center');
                if (p.horizontalAlignment === 'end' || p.horizontalAlignment === 'End') s.push('justify-content:flex-end');
                if (p.alignment === 'center' || p.contentAlignment === 'center') s.push('align-items:center;justify-content:center');
                return s.join(';');
            }
            function __rinRenderNode(node, depth) {
                if (depth > 30) return '';
                if (node == null || node === false || node === true) return '';
                if (typeof node === 'string') return __rinEsc(node);
                if (typeof node === 'number') return String(node);
                if (Array.isArray(node)) {
                    var out = [];
                    for (var i = 0; i < node.length; i++) out.push(__rinRenderNode(node[i], depth + 1));
                    return out.join('');
                }
                if (!node.__rinNode) return '';
                var kind = node.__rinNode, p = node.props || {};
                var style = __rinStyle(kind, p);
                var inner = '';
                for (var j = 0; j < (node.children || []).length; j++) inner += __rinRenderNode(node.children[j], depth + 1);
                if (kind === 'text') {
                    var t = (p.text != null) ? p.text : inner;
                    var tstyle = style + ';line-height:1.45;white-space:pre-wrap;word-break:break-word';
                    if (p.style === 'titleMedium' || p.style === 'titleLarge') tstyle += ';font-size:16px;font-weight:600';
                    else if (p.style === 'titleSmall') tstyle += ';font-size:14px;font-weight:600';
                    else if (p.style === 'labelSmall' || p.style === 'labelMedium') tstyle += ';font-size:11px;opacity:.85';
                    else tstyle += ';font-size:13px';
                    return '<div style="' + tstyle + '">' + (typeof t === 'string' ? __rinEsc(t) : __rinRenderNode(t, depth + 1)) + '</div>';
                }
                if (kind === 'icon') {
                    var nm = __rinEsc(p.name || '');
                    return '<div style="' + style + ';font-size:' + (p.size || 18) + 'px;display:flex;align-items:center;justify-content:center">◈<span style="font-size:9px;margin-left:2px;opacity:.6">' + nm + '</span></div>';
                }
                if (kind === 'spacer') {
                    var hs = p.height != null ? 'height:' + p.height + 'px;' : '';
                    var ws = p.width != null ? 'width:' + p.width + 'px;flex-shrink:0;' : '';
                    return '<div style="' + hs + ws + '"></div>';
                }
                if (kind === 'divider') return '<hr style="border:none;border-top:1px solid #3a3a42;margin:6px 0;width:100%">';
                if (kind === 'progress') {
                    var prog = (p.progress != null) ? Math.round(p.progress * 100) : 30;
                    return '<div style="height:5px;background:#3a3a42;border-radius:3px;overflow:hidden;width:100%"><div style="height:100%;width:' + prog + '%;background:#A8C7FA"></div></div>';
                }
                if (kind === 'button' || kind === 'outlinedbutton') {
                    var label = inner || __rinEsc(p.text || '');
                    var bstyle = 'border-radius:18px;padding:8px 14px;font-size:13px;margin:2px;cursor:pointer;'
                        + (kind === 'button' ? 'background:#A8C7FA;color:#00325A;border:none;' : 'background:transparent;color:#A8C7FA;border:1px solid #6b6a73;');
                    return '<button data-rin-action="1" style="' + bstyle + '">' + label + '</button>';
                }
                if (kind === 'webview') {
                    if (p.url) {
                        return '<div data-rin-url="' + __rinEsc(p.url) + '" style="flex:1;min-height:70vh;border:1px dashed #555;border-radius:8px;display:flex;align-items:center;justify-content:center;color:#888;font-size:12px;padding:16px;text-align:center">[内嵌页面] ' + __rinEsc(p.url) + '</div>';
                    }
                    var h = String(p.html == null ? '' : p.html);
                    return '<div data-rin-embed="1" style="flex:1;width:100%">' + h + '</div>';
                }
                if (kind === 'image') {
                    var src = p.src || p.url || '';
                    return src ? '<img src="' + __rinEsc(src) + '" style="' + style + ';max-width:100%">' : '';
                }
                if (kind === 'switch') return '<div style="' + style + ';width:36px;height:20px;border-radius:10px;background:' + (p.checked ? '#A8C7FA' : '#3a3a42') + '"></div>';
                if (kind === 'input') return '<input style="' + style + ';background:#26262c;color:#E5E1E9;border:1px solid #47464F;border-radius:8px;padding:8px;font-size:13px;width:100%" placeholder="' + __rinEsc(p.placeholder || '') + '" value="' + __rinEsc(p.value || '') + '">';
                return '<div style="' + style + '">' + inner + '</div>';
            }
            function __rinRenderDocument(root) {
                var body = __rinRenderNode(root, 0);
                return '<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">'
                    + '<style>html,body{margin:0;padding:0;background:#131318;color:#E5E1E9;font-family:sans-serif}'
                    + '*{box-sizing:border-box}body{padding:12px;min-height:100vh}'
                    + 'div{min-width:0}</style></head><body>' + body + '</body></html>';
            }
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
                    // v4.5.38: Compose DSL 虚拟树 (两种面板形态统一 —
                    //  "HTML 承重"型直接返回 WebView(html); "DSL"型返回组件树, 由转换器渲染)
                    Text: function (p, c) { return __rinNode('text', p, c); },
                    Row: function (p, c) { return __rinNode('row', p, c); },
                    Column: function (p, c) { return __rinNode('column', p, c); },
                    Box: function (p, c) { return __rinNode('box', p, c); },
                    Spacer: function (p) { return __rinNode('spacer', p, []); },
                    Icon: function (p) { return __rinNode('icon', p, []); },
                    Surface: function (p, c) { return __rinNode('surface', p, c); },
                    Card: function (p, c) { return __rinNode('surface', p, c); },
                    HorizontalDivider: function (p) { return __rinNode('divider', p, []); },
                    VerticalDivider: function (p) { return __rinNode('divider', p, []); },
                    Button: function (p, c) { return __rinNode('button', p, c); },
                    OutlinedButton: function (p, c) { return __rinNode('outlinedbutton', p, c); },
                    TextButton: function (p, c) { return __rinNode('outlinedbutton', p, c); },
                    LinearProgressIndicator: function (p) { return __rinNode('progress', p, []); },
                    CircularProgressIndicator: function (p) { return __rinNode('progress', p, []); },
                    LazyColumn: function (p, c) { return __rinNode('column', p, c); },
                    LazyRow: function (p, c) { return __rinNode('row', p, c); },
                    SelectionContainer: function (p, c) { return __rinNode('box', p, c); },
                    Image: function (p) { return __rinNode('image', p, []); },
                    Switch: function (p) { return __rinNode('switch', p, []); },
                    TextField: function (p) { return __rinNode('input', p, []); },
                    WebView: function (opts) { return __rinNode('webview', opts || {}, []); }
                },
                useState: function (name, initial) {
                    // Compose DSL 状态桩: [值, setter] — setter 更新内存值 (不触发重渲染,
                    // 面板以初始/当前值渲染; 服务型面板的完整交互依赖后续服务层)
                    var __v = initial;
                    var __key = String(name || ('s' + (__rinStateSeq++)));
                    __rinState[__key] = __v;
                    var setter = function (nv) { __rinState[__key] = nv; };
                    return [__v, setter];
                },
                MaterialTheme: {
                    colorScheme: __rinColorScheme()
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
            // (__notImplemented / 定时器桩移至共享层 OPERIT_COMMON_PRELUDE)
        """.trimIndent()
    }
}
