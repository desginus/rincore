package me.rerere.rikkahub.sandbox

/* ───【域 D·工作区沙箱】SandboxBridgeServer.kt
 * 职责: 沙箱→软件桥 (loopback 17526 + token; rin CLI 对端)
 * 常用改动: 新桥接口 → 路由注册; 启动 → get<AppScope>().launch(IO)
 * 问题定位: rin 命令无响应/桥 401 → 本文件
 * 基线: 自研 (v4.8.0) — 原版无此文件 | 地图: docs/APP_MAP.md §D | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.SecureRandom

private const val TAG = "SandboxBridge"

object SandboxBridgeServer {

    /** 桥端口 — 固定值 (与 webServer 8080 不同域), 仅绑定 127.0.0.1 */
    const val PORT = 17526
    private const val HOST = "127.0.0.1"
    private const val TOKEN_DIR = ".rin-bridge"
    private const val TOKEN_FILE = "token"
    private const val NOTIFY_CHANNEL_ID = "sandbox_bridge"
    private const val AI_TIMEOUT_MS = 300_000L

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var token: String = ""

    /**
     * 应用启动时调用 (幂等)。Koin 已就绪前提下生效; 任何失败静默记录 —
     * 桥不可用不影响主功能。
     */
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        appContext = app
        try {
            token = ensureToken(app)
            ensureNotificationChannel(app)
            server = embeddedServer(CIO, host = HOST, port = PORT) {
                install(ContentNegotiation) { json(JsonInstant) }
                routing {
                    get("/health") {
                        call.respondText(
                            buildJsonObject {
                                put("ok", true)
                                put("service", "rin-sandbox-bridge")
                            }.toString(),
                            io.ktor.http.ContentType.Application.Json,
                        )
                    }
                    get("/info") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@get
                        }
                        call.respondText(infoJson(app), io.ktor.http.ContentType.Application.Json)
                    }
                    post("/notify") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val title = body["title"]?.jsonPrimitive?.contentOrNull ?: "RinCore 沙箱"
                        val text = body["body"]?.jsonPrimitive?.contentOrNull ?: ""
                        val ok = postNotification(app, title, text)
                        call.respondText(okJson(ok), io.ktor.http.ContentType.Application.Json)
                    }
                    post("/toast") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val text = body["text"]?.jsonPrimitive?.contentOrNull ?: return@post call.respondText(
                            errJson("missing text"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                        )
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                        }
                        call.respondText(okJson(true), io.ktor.http.ContentType.Application.Json)
                    }
                    post("/clipboard") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val op = body["op"]?.jsonPrimitive?.contentOrNull ?: "get"
                        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        if (op == "set") {
                            val text = body["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            Handler(Looper.getMainLooper()).post {
                                cm.setPrimaryClip(ClipData.newPlainText("rin", text))
                            }
                            call.respondText(okJson(true), io.ktor.http.ContentType.Application.Json)
                        } else {
                            val text = withContext(Dispatchers.Main) {
                                runCatching {
                                    cm.primaryClip?.getItemAt(0)?.coerceToText(app)?.toString().orEmpty()
                                }.getOrDefault("")
                            }
                            call.respondText(
                                buildJsonObject {
                                    put("ok", true)
                                    put("text", text)
                                }.toString(),
                                io.ktor.http.ContentType.Application.Json,
                            )
                        }
                    }
                    post("/ai") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val prompt = body["prompt"]?.jsonPrimitive?.contentOrNull
                            ?: return@post call.respondText(
                                errJson("missing prompt"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                            )
                        val system = body["system"]?.jsonPrimitive?.contentOrNull
                        val result = runCatching { runAi(prompt, system) }
                        result.fold(
                            onSuccess = { text ->
                                call.respondText(
                                    buildJsonObject {
                                        put("ok", true)
                                        put("text", text)
                                    }.toString(),
                                    io.ktor.http.ContentType.Application.Json,
                                )
                            },
                            onFailure = { e ->
                                Log.w(TAG, "ai call failed", e)
                                call.respondText(
                                    errJson(e.message ?: "ai failed"), io.ktor.http.ContentType.Application.Json,
                                    HttpStatusCode.InternalServerError,
                                )
                            },
                        )
                    }
                    post("/open") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val target = body["target"]?.jsonPrimitive?.contentOrNull
                            ?: return@post call.respondText(
                                errJson("missing target"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest,
                            )
                        val ok = runCatching {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(target)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            app.startActivity(intent)
                        }.isSuccess
                        call.respondText(okJson(ok), io.ktor.http.ContentType.Application.Json)
                    }
                    post("/share") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val text = body["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        val ok = runCatching {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            app.startActivity(Intent.createChooser(intent, "分享").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }.isSuccess
                        call.respondText(okJson(ok), io.ktor.http.ContentType.Application.Json)
                    }
                    post("/log") {
                        if (!authorized(call.request.headers["X-Rin-Token"])) {
                            call.respondText(errJson("unauthorized"), io.ktor.http.ContentType.Application.Json, HttpStatusCode.Unauthorized); return@post
                        }
                        val body = parseBody(call.receiveText())
                        val text = body["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        Log.i("RinSandbox", text)
                        call.respondText(okJson(true), io.ktor.http.ContentType.Application.Json)
                    }
                }
            }.start(wait = false)
            Log.i(TAG, "SandboxBridge started on $HOST:$PORT")
        } catch (e: Exception) {
            Log.w(TAG, "SandboxBridge start failed (non-fatal)", e)
            server = null
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private fun authorized(header: String?): Boolean = header != null && token.isNotEmpty() && header == token

    private fun parseBody(raw: String): JsonObject = runCatching {
        JsonInstant.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap<String, kotlinx.serialization.json.JsonElement>())
    }.getOrDefault(JsonObject(emptyMap<String, kotlinx.serialization.json.JsonElement>()))

    private fun okJson(ok: Boolean): String = buildJsonObject { put("ok", ok) }.toString()

    private fun errJson(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    /**
     * Token 管理 — 沙箱可见文件 (filesDir 挂载为沙箱 /workspace):
     *   /workspace/.rin-bridge/token  → 沙箱内 rin CLI 读取。
     * 进程内首次生成后持久; 应用私有目录下其他应用不可读, 端口仅 loopback,
     * 双保险防本机其他应用误用。
     */
    private fun ensureToken(context: Context): String {
        val dir = File(context.filesDir, TOKEN_DIR).apply { mkdirs() }
        val file = File(dir, TOKEN_FILE)
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrEmpty()) return existing
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val fresh = bytes.joinToString("") { "%02x".format(it) }
        runCatching { file.writeText(fresh) }
        return fresh
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NOTIFY_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFY_CHANNEL_ID,
            "沙箱桥消息",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "来自沙箱（模型脚本/服务）的通知"
        }
        nm.createNotificationChannel(channel)
    }

    private fun postNotification(context: Context, title: String, body: String): Boolean {
        return runCatching {
            me.rerere.rikkahub.utils.NotificationUtil.notify(
                context = context,
                channelId = NOTIFY_CHANNEL_ID,
                notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            ) {
                this.title = title
                this.content = body
            }
        }.getOrDefault(false)
    }

    private fun infoJson(context: Context): String = buildJsonObject {
        put("ok", true)
        put("package", context.packageName)
        put("sdk", Build.VERSION.SDK_INT)
        put("device", Build.MODEL)
        put("brand", Build.BRAND)
        put("port", PORT)
    }.toString()

    /**
     * 一次性 AI 生成 (非流式, 无工具, 无对话副作用) —
     * 让沙箱内脚本/服务直接请模型处理文本。
     */
    private suspend fun runAi(prompt: String, system: String?): String {
        val settingsStore: SettingsStore = GlobalContext.get().get()
        val settings = settingsStore.settingsFlow.value
        val model = settings.getCurrentChatModel() ?: error("no chat model configured")
        val providerSetting = model.findProvider(settings.providers) ?: error("provider not found for model")
        val providerManager: ProviderManager = GlobalContext.get().get()
        val provider = providerManager.getProviderByType(providerSetting)
        val messages = buildList {
            if (!system.isNullOrBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text(system))))
            }
            add(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(prompt))))
        }
        val params = TextGenerationParams(model = model)
        val result = withTimeout(AI_TIMEOUT_MS) {
            provider.generateText(providerSetting, messages, params)
        }
        return result.message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }
}
