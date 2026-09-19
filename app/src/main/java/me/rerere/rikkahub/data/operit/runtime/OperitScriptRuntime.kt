package me.rerere.rikkahub.data.operit.runtime


// ───【自研】岔路口计划·阶段2 — Operit 脚本运行时 (QuickJS 最小宿主面)
// 协议对齐实测结论: Tools.Files 子集 + complete/done/emit + 两种返回风格
// (complete 回调 与 return Promise 均支持 — 桩实验已验证)
// ───────────────────────────────────────────────────────────────
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

class OperitScriptRuntime(
    private val filesRootProvider: () -> File,
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
            instance.evaluate<Any?>(HOST_PRELUDE)
            val source = scriptFile.readText()
            instance.evaluate<Any?>(source, "operit_script.js")

            // 4. 调用工具函数 (async 安全: evaluate 自动 drain job queue 直到 Promise 完成)
            val paramsLiteral = json.encodeToString(JsonElement.serializer(), paramsJson)
            val invokeCode = """
                (function () {
                    try {
                        var __r = exports[${json.encodeToString(JsonPrimitive.serializer(), toolName)}]($paramsLiteral);
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
            instance.evaluate<Any?>(invokeCode)

            // 5. 读取结果
            val resultStr = instance.evaluate<String?>("__operitResult")
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
        // 防逃逸
        val canonical = f.canonicalFile
        val rootCanonical = root.canonicalFile
        return if (canonical.path.startsWith(rootCanonical.path)) canonical else File(root, "blocked")
    }

    private companion object {
        const val SCRIPT_TIMEOUT_MS = 60_000L

        /** 宿主前导 JS — 定义脚本世界 (与 Operit 协议对齐) */
        val HOST_PRELUDE = """
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
            var console = { log: function(){}, info: function(){}, warn: function(){}, error: function(){}, debug: function(){} };
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
                System: {
                    sleep: function (ms) { return new Promise(function (res) { setTimeout(res, Math.min(Number(ms) || 0, 5000)); }); },
                    getDeviceInfo: function () { return { model: 'RinCore', sdk: 0 }; }
                }
            };
            // QuickJS 无 setTimeout — 用 job 队列兼容实现 (立即 resolve, 避免脚本挂起)
            if (typeof setTimeout === 'undefined') {
                var setTimeout = function (fn) { self_promise_resolve(fn); return 0; };
                function self_promise_resolve(fn) { if (typeof fn === 'function') { try { fn(); } catch (e) {} } }
                var clearTimeout = function () {};
                var setInterval = function () { return 0; };
                var clearInterval = function () {};
            }
        """.trimIndent()
    }
}
