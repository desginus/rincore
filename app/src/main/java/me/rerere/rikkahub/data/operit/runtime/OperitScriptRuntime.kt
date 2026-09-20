package me.rerere.rikkahub.data.operit.runtime


/* ───【自研】岔路口计划·阶段2 — Operit 脚本运行时 (QuickJS 最小宿主面)
 * 协议对齐实测结论: Tools.Files 子集 + complete/done/emit + 两种返回风格
 * (complete 回调 与 return Promise 均支持 — 桩实验已验证)
 * ───────────────────────────────────────────────────────────────*/
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OperitScriptRuntime(
    private val filesRootProvider: () -> File,
    // v4.6.4 运行兼容: HTTP 桥 (Tools.Net / OkHttp DSL 的底层执行器)
    private val okHttpProvider: () -> okhttp3.OkHttpClient = { okhttp3.OkHttpClient() },
    // v4.6.4 运行兼容: shell 桥 (workspace 沙箱; null = 无可用工作区 → 诚实降级)
    private val workspaceProvider: () -> Pair<me.rerere.rikkahub.data.repository.WorkspaceRepository, String>? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }

    /**
     * 执行脚本中的单个工具函数。
     *
     * @param scriptFile 脚本 .js 文件
     * @param toolName exports 里的函数名
     * @param paramsJson 模型传入的参数 (JSON object)
     * @return 脚本 complete/return 的结果 JSON
     */
    suspend fun executeTool(
        scriptFile: File,
        toolName: String,
        paramsJson: JsonElement,
    ): Result<JsonElement> = withContext(Dispatchers.IO) {
        if (!scriptFile.exists()) {
            return@withContext Result.failure(IllegalStateException("script not found: ${scriptFile.absolutePath}"))
        }
        val instance = QuickJs.create(jobDispatcher = Dispatchers.IO)
        try {
            instance.evaluationTimeoutMillis = SCRIPT_TIMEOUT_MS

            // 1. 宿主调用桥 (同步, 返回 JSON 字符串)
            instance.function("__hostCall") { args ->
                val name = args.getOrNull(0) as? String ?: ""
                val argsJson = args.getOrNull(1) as? String ?: "[]"
                handleHostCall(name, argsJson)
            }
            // 2. 结果捕获 (complete 协议兜底 — JS 前导已用 JS 实现, 这里不再需要 Kotlin 侧)
            // 3. 注入宿主前导 + 脚本源码
            // v4.5.37: 尾部 ;null; — 返回值确定化 (脚本末行可能是函数赋值, 避免转换器接触不确定类型)
            instance.evaluate<Any?>(HOST_PRELUDE + "\n;null;")
            val source = scriptFile.readText()
            instance.evaluate<Any?>(source + "\n;null;", "operit_script.js")

            // 4. 调用工具函数 (async 安全: evaluate 自动 drain job queue 直到 Promise 完成)
            val paramsLiteral = json.encodeToString(JsonElement.serializer(), paramsJson)
            val toolNameLiteral = JsonPrimitive(toolName).toString()
            val invokeCode = """
                (function () {
                    try {
                        var __r = exports[$toolNameLiteral]($paramsLiteral);
                        if (__r !== undefined && __r !== null && typeof __r.then === 'function') {
                            __r.then(function (v) { __operitFinish(v); }, function (e) { __operitFinish({ success: false, message: String((e && e.message) || e) }); });
                        } else {
                            __operitFinish(__r);
                        }
                    } catch (e) {
                        __operitFinish({ success: false, message: String((e && e.message) || e) });
                    }
                })();
            """.trimIndent()
            instance.evaluate<Any?>(invokeCode + "\n;null;")

            // 5. 读取结果
            val resultStr = instance.evaluate<String?>(jsSafeString("__operitResult"))
                ?: return@withContext Result.failure(IllegalStateException("script produced no result (tool=$toolName)"))
            val parsed = runCatching { json.parseToJsonElement(resultStr) }.getOrDefault(JsonPrimitive(resultStr))
            Result.success(parsed)
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            runCatching { instance.close() }
        }
    }

    /** 宿主调用分发 (最小面: Tools.Files 子集; 其余诚实降级) */
    private fun handleHostCall(name: String, argsJson: String): String {
        val args: List<JsonElement> = runCatching {
            json.parseToJsonElement(argsJson).let { el ->
                (el as? kotlinx.serialization.json.JsonArray)?.toList() ?: emptyList()
            }
        }.getOrDefault(emptyList())

        return try {
            when (name) {
                "files.read" -> {
                    val p = args.getOrNull(0)?.let { str(it) } ?: return err("missing path")
                    val f = resolvePath(p)
                    if (!f.exists()) throw IllegalStateException("File not found: $p")
                    ok { put("content", JsonPrimitive(f.readText())) }
                }
                "files.write" -> {
                    val p = args.getOrNull(0)?.let { str(it) } ?: return err("missing path")
                    val content = args.getOrNull(1)?.let { str(it) } ?: ""
                    val append = args.getOrNull(2)?.let { bool(it) } ?: false
                    val f = resolvePath(p)
                    f.parentFile?.mkdirs()
                    if (append) f.appendText(content) else f.writeText(content)
                    ok { put("success", JsonPrimitive(true)); put("path", JsonPrimitive(p)) }
                }
                "files.mkdir" -> {
                    val p = args.getOrNull(0)?.let { str(it) } ?: return err("missing path")
                    val recursive = args.getOrNull(1)?.let { bool(it) } ?: false
                    val f = resolvePath(p)
                    if (recursive) f.mkdirs() else f.mkdir()
                    ok { put("success", JsonPrimitive(true)) }
                }
                "files.exists" -> {
                    val p = args.getOrNull(0)?.let { str(it) } ?: return err("missing path")
                    ok { put("exists", JsonPrimitive(resolvePath(p).exists())) }
                }
                "files.delete" -> {
                    val p = args.getOrNull(0)?.let { str(it) } ?: return err("missing path")
                    val f = resolvePath(p)
                    ok { put("success", JsonPrimitive(if (f.isDirectory) f.deleteRecursively() else f.delete())) }
                }
                "files.list" -> {
                    val p = args.getOrNull(0)?.let { str(it) } ?: return err("missing path")
                    val f = resolvePath(p)
                    val names = f.listFiles()?.map { it.name } ?: emptyList()
                    ok { put("files", kotlinx.serialization.json.JsonArray(names.map { JsonPrimitive(it) })) }
                }
                "files.move" -> {
                    val from = resolvePath(args.getOrNull(0)?.let { str(it) } ?: return err("missing from"))
                    val to = resolvePath(args.getOrNull(1)?.let { str(it) } ?: return err("missing to"))
                    to.parentFile?.mkdirs()
                    ok { put("success", JsonPrimitive(from.renameTo(to))) }
                }
                "files.copy" -> {
                    val from = resolvePath(args.getOrNull(0)?.let { str(it) } ?: return err("missing from"))
                    val to = resolvePath(args.getOrNull(1)?.let { str(it) } ?: return err("missing to"))
                    to.parentFile?.mkdirs()
                    from.copyTo(to, overwrite = true)
                    ok { put("success", JsonPrimitive(true)) }
                }
                // v4.6.4 运行兼容: HTTP 桥 (OkHttp — Tools.Net / OkHttp DSL 的底层)
                "http.request" -> {
                    val payload = args.getOrNull(0)?.let { str(it) } ?: return err("missing request payload")
                    val req = runCatching {
                        json.parseToJsonElement(payload) as? kotlinx.serialization.json.JsonObject
                    }.getOrNull() ?: return err("invalid request payload")
                    val url = req["url"]?.let { (it as? JsonPrimitive)?.content } ?: return err("missing url")
                    val method = req["method"]?.let { (it as? JsonPrimitive)?.content }?.uppercase() ?: "GET"
                    val body = req["body"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    val headers = req["headers"] as? kotlinx.serialization.json.JsonObject
                    val connectMs = req["connectTimeoutMs"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: 30_000L
                    val readMs = req["readTimeoutMs"]?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: 30_000L
                    val client = okHttpProvider().newBuilder()
                        .connectTimeout(connectMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(readMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .callTimeout(maxOf(60_000L, connectMs + readMs), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                    val reqBody = when {
                        body.isNullOrEmpty() -> null
                        method in listOf("POST", "PUT", "PATCH", "DELETE") ->
                            body.toRequestBody("application/json; charset=utf-8".toMediaType())
                        else -> null
                    }
                    val builder = okhttp3.Request.Builder().url(url).method(method, reqBody)
                    headers?.forEach { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { builder.addHeader(k, it) }
                    }
                    client.newCall(builder.build()).execute().use { resp ->
                        val content = resp.body?.string() ?: ""
                        ok {
                            put("status", JsonPrimitive(resp.code))
                            put("statusMessage", JsonPrimitive(resp.message))
                            put("content", JsonPrimitive(content))
                        }
                    }
                }
                // v4.6.4 运行兼容: shell 桥 (接 workspace 沙箱 — code_runner/ffmpeg 类包的执行底座)
                "system.shell" -> {
                    val cmd = args.getOrNull(0)?.let { str(it) } ?: return err("missing command")
                    val ws = workspaceProvider()
                        ?: return err("no workspace available — bind a shell-ready workspace to an assistant first")
                    val (repo, wsId) = ws
                    val result = kotlinx.coroutines.runBlocking {
                        repo.executeCommand(wsId, cmd)
                    }
                    ok {
                        put("exitCode", JsonPrimitive(result.exitCode))
                        put("stdout", JsonPrimitive(result.stdout ?: ""))
                        put("stderr", JsonPrimitive(result.stderr ?: ""))
                    }
                }
                else -> err("capability not implemented in RinCore runtime yet: $name")
            }
        } catch (e: Throwable) {
            err(e.message ?: e.toString())
        }
    }

    private inline fun ok(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject { put("success", JsonPrimitive(true)); block() }.toString()

    private fun err(message: String): String = buildJsonObject {
        put("success", JsonPrimitive(false))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun str(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    private fun bool(el: JsonElement): Boolean = when (el) {
        is JsonPrimitive -> el.content == "true"
        else -> false
    }

    /** 路径解析: 脚本世界 /sdcard/... → RinCore 隔离目录 (阶段3 再考虑桥接工作区) */
    private fun resolvePath(path: String): File {
        val root = filesRootProvider()
        val normalized = path.replace('\\', '/')
        val relative = when {
            normalized.startsWith("/sdcard/") -> normalized.removePrefix("/sdcard/")
            normalized.startsWith("/") -> "sys/" + normalized.trimStart('/')
            else -> normalized
        }
        val f = File(root, relative)
        // 防逃逸 (v4.5.31 加固: 前缀匹配带分隔符边界 — 防 operit_runtime_evil 类
        // sibling 目录绕过; 逃逸时抛异常由 handleHostCall 统一转为错误响应)
        val canonical = f.canonicalFile
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.path
        val canonicalPath = canonical.path
        val inside = canonicalPath == rootPath || canonicalPath.startsWith(rootPath + File.separator)
        if (!inside) throw SecurityException("path escapes runtime root: $path")
        return canonical
    }

    private companion object {
        /**
         * v4.5.37 根治: JS 侧类型归一化 —
         * dokar3 evaluate<String?> 对 JS Array/Object 会抛
         * "No such type converter to convert 'kotlin.collections.List<*>' to 'kotlin.String?'"。
         * 所有跨边界取值经此包裹: 任意 JS 值 → string | null。
         */
        fun jsSafeString(expr: String): String =
            "(function(){ var __v = ($expr); if (__v === null || __v === undefined) return null; " +
                "if (typeof __v === 'string') return __v; " +
                "var __s = JSON.stringify(__v); return (__s === undefined) ? String(__v) : __s; })()"

        const val SCRIPT_TIMEOUT_MS = 60_000L

        /** 宿主前导 JS — 定义脚本世界 (与 Operit 协议对齐) */
        val HOST_PRELUDE = OPERIT_COMMON_PRELUDE + """
            // v4.5.31: 脚本世界 (exports.xxx = function 风格; exports/module 由共享层提供)
            var __operitDone = false;
            var __operitResult = null;
            function __operitFinish(v) {
                if (__operitDone) { return; }
                __operitDone = true;
                try { __operitResult = JSON.stringify(v === undefined ? null : v); }
                catch (e) { __operitResult = JSON.stringify({ success: false, message: 'result not serializable: ' + String(e) }); }
            }
            function complete(v) { __operitFinish(v); }
            function done(v) { __operitFinish(v); }
            function emit(v) { }
            function update(v) { }
            function delta(v) { }
            function log(v) { }
            function getEnv(k) { return undefined; }
            function getLang() { return 'zh'; }
            function getState() { return undefined; }
            function getCallerName() { return undefined; }
            function getChatId() { return undefined; }
            function getCallerCardId() { return undefined; }
            function __hostJSON(name, args) {
                return JSON.parse(__hostCall(name, JSON.stringify(args)));
            }
            var Tools = {
                Files: {
                    read: function (p) { return __hostJSON('files.read', [p]); },
                    write: function (p, c, a, env) { return __hostJSON('files.write', [p, c, a === true, env]); },
                    makeDirectory: function (p, r) { return __hostJSON('files.mkdir', [p, r === true]); },
                    createDirectory: function (p, r) { return __hostJSON('files.mkdir', [p, r === true]); },
                    mkdir: function (p, r) { return __hostJSON('files.mkdir', [p, r === true]); },
                    exists: function (p) { return __hostJSON('files.exists', [p]); },
                    deleteFile: function (p) { return __hostJSON('files.delete', [p]); },
                    listFiles: function (p) { return __hostJSON('files.list', [p]); },
                    move: function (f, t) { return __hostJSON('files.move', [f, t]); },
                    copy: function (f, t) { return __hostJSON('files.copy', [f, t]); }
                },
                // v4.6.4 运行兼容: HTTP 桥 (RinCore OkHttp; 搜索/绘图/GitHub 类包的底座)
                Net: {
                    httpGet: function (url) {
                        return JSON.parse(__hostCall('http.request', JSON.stringify([JSON.stringify({ url: String(url), method: 'GET' })])));
                    },
                    httpPost: function (url, body) {
                        var b = (body != null && typeof body === 'object') ? JSON.stringify(body) : (body == null ? null : String(body));
                        return JSON.parse(__hostCall('http.request', JSON.stringify([JSON.stringify({ url: String(url), method: 'POST', body: b })])));
                    },
                    fetch: function (url) {
                        return JSON.parse(__hostCall('http.request', JSON.stringify([JSON.stringify({ url: String(url), method: 'GET' })])));
                    }
                },
                System: {
                    sleep: function (ms) { return Promise.resolve(); },
                    getDeviceInfo: function () { return { model: 'RinCore', sdk: 0 }; },
                    startApp: function () { return __notImplemented('system.startApp'); },
                    sendNotification: function () { return __notImplemented('system.sendNotification'); },
                    toast: function () { return __notImplemented('system.toast'); },
                    // v4.6.4 运行兼容: terminal/hiddenExec/shell 接 workspace 沙箱
                    // (code_runner/ffmpeg 类包的执行底座; 无 workspace 时诚实报错)
                    terminal: {
                        hiddenExec: function (command, options) {
                            return JSON.parse(__hostCall('system.shell', JSON.stringify([String(command)])));
                        },
                        exec: function (command, options) {
                            return JSON.parse(__hostCall('system.shell', JSON.stringify([String(command)])));
                        },
                        create: function () { return __notImplemented('terminal.create'); },
                        execute: function () { return __notImplemented('terminal.execute'); },
                        close: function () { return __notImplemented('terminal.close'); }
                    },
                    shell: function (cmd) {
                        try {
                            var __r = JSON.parse(__hostCall('system.shell', JSON.stringify([String(cmd)])));
                            return __r;
                        } catch (e) { return __notImplemented('system.shell'); }
                    },
                    exec: function (cmd) { return __notImplemented('system.exec'); },
                    getAppUsageTime: function () { return __notImplemented('system.getAppUsageTime'); }
                },
                // v4.5.31: 未实现命名空间的友好降级桩 —
                // 明确 capability not implemented (而非裸 TypeError), 脚本可按需处理
                Chat: {
                    listChats: function () { return __notImplemented('chat.listChats'); },
                    findChat: function () { return __notImplemented('chat.findChat'); },
                    getMessages: function () { return __notImplemented('chat.getMessages'); },
                    getMessagesRange: function () { return __notImplemented('chat.getMessagesRange'); },
                    updateTitle: function () { return __notImplemented('chat.updateTitle'); },
                    deleteChat: function () { return __notImplemented('chat.deleteChat'); },
                    startService: function () { return __notImplemented('chat.startService'); }
                },
                UI: {
                    getPageInfo: function () { return __notImplemented('ui.getPageInfo'); },
                    swipe: function () { return __notImplemented('ui.swipe'); }
                },
                Workflow: {
                    getAll: function () { return __notImplemented('workflow.getAll'); },
                    create: function () { return __notImplemented('workflow.create'); },
                    update: function () { return __notImplemented('workflow.update'); }
                }
            };
            // (__notImplemented / 定时器桩移至共享层 OPERIT_COMMON_PRELUDE)
            // ═══ v4.6.4 运行兼容: JS 版 OkHttp DSL (github/messenger 等 TS 编译包的底层) ═══
            // 用法对齐 Operit: OkHttp.newBuilder().connectTimeout(ms)...build()
            //   → client.newRequest().url(u).method(m).headers(h).body(b, type).build().execute()
            //   → response.isSuccessful()/statusCode()/statusMessage()/content/json()
            function __rinOkHttpClient(cfg) {
                return {
                    newRequest: function () {
                        var req = { headers: {}, method: 'GET', url: '', body: null, bodyType: null };
                        var rb = {};
                        rb.url = function (u) { req.url = String(u); return rb; };
                        rb.method = function (m) { req.method = String(m == null ? 'GET' : m); return rb; };
                        rb.headers = function (h) { if (h && typeof h === 'object') { for (var k in h) { req.headers[k] = String(h[k]); } } return rb; };
                        rb.body = function (b, type) { req.body = (b == null ? null : String(b)); req.bodyType = (type == null ? null : String(type)); return rb; };
                        rb.build = function () {
                            return {
                                execute: function () {
                                    var payload = JSON.stringify({
                                        url: req.url, method: req.method, headers: req.headers,
                                        body: req.body, bodyType: req.bodyType,
                                        connectTimeoutMs: cfg.connectTimeoutMs, readTimeoutMs: cfg.readTimeoutMs
                                    });
                                    var raw = __hostCall('http.request', JSON.stringify([payload]));
                                    var r = JSON.parse(raw);
                                    if (!r.success) { throw new Error(String(r.message == null ? 'http request failed' : r.message)); }
                                    return {
                                        isSuccessful: function () { return r.status >= 200 && r.status < 300; },
                                        statusCode: function () { return r.status; },
                                        statusMessage: function () { return r.statusMessage == null ? '' : r.statusMessage; },
                                        content: r.content == null ? '' : r.content,
                                        body: { string: function () { return r.content == null ? '' : r.content; } },
                                        json: function () { return JSON.parse(r.content == null ? '{}' : r.content); }
                                    };
                                }
                            };
                        };
                        return rb;
                    }
                };
            }
            var OkHttp = {
                newBuilder: function () {
                    var cfg = { connectTimeoutMs: 30000, readTimeoutMs: 30000 };
                    var b = {};
                    b.connectTimeout = function (ms) { cfg.connectTimeoutMs = (ms == null ? 30000 : ms); return b; };
                    b.readTimeout = function (ms) { cfg.readTimeoutMs = (ms == null ? 30000 : ms); return b; };
                    b.writeTimeout = function (ms) { return b; };
                    b.build = function () { return __rinOkHttpClient(cfg); };
                    return b;
                }
            };
        """.trimIndent()
    }
}
