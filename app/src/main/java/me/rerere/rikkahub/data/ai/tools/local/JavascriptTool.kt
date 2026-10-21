/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.local

/* ───【原版对齐】JavascriptTool.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript code using QuickJS engine (ES2020).
        The result is the value of the last expression in the code.
        For calculations with decimals, use toFixed() to control precision.
        Console output (log/info/warn/error) is captured and returned in 'logs' field.
        No DOM or Node.js APIs available.
        Example: '1 + 2' returns 3; 'const x = 5; x * 2' returns 10.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    execute = {
        // v5.0.60 ★「JS 计算工具卡住一直未完成」的机制性修复（长期待办）：
        // QuickJS 的 evaluate 是**阻塞 JNI 调用** —— 协程 withTimeout / cancel 都无法中断它，
        // 一旦脚本里有死循环或超长计算，协程会永久挂在那里，界面表现为"一直未完成"，
        // 且旧的 700s 工具超时让它要挂十几分钟。现在用**独立守护线程 + Future 超时**：
        // 到点调用方立即返回明确错误，线程随 isDaemon 消亡，不再拖住整条工具链。
        // 同时补 finally close()：旧实现每次调用都 new 一个 QuickJSContext 且从不释放，
        // native 内存只增不减（这也是"内存占用诡异"的嫌疑之一）。
        val logs = java.util.Collections.synchronizedList(arrayListOf<String>())
        val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "eval-js").apply { isDaemon = true }
        }
        val failPayload: (String) -> List<UIMessagePart> = { msg ->
            val payload = buildJsonObject {
                if (logs.isNotEmpty()) put("logs", JsonPrimitive(logs.joinToString("\n")))
                put("error", JsonPrimitive(msg))
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
        try {
            val future = executor.submit(
                java.util.concurrent.Callable {
                    val context = QuickJSContext.create()
                    try {
                        context.setConsole(object : QuickJSContext.Console {
                            override fun log(info: String?) {
                                logs.add("[LOG] $info")
                            }

                            override fun info(info: String?) {
                                logs.add("[INFO] $info")
                            }

                            override fun warn(info: String?) {
                                logs.add("[WARN] $info")
                            }

                            override fun error(info: String?) {
                                logs.add("[ERROR] $info")
                            }
                        })
                        context.evaluate(code)
                    } finally {
                        runCatching { context.close() }
                    }
                }
            )
            val result = try {
                future.get(JS_EVAL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                return@execute failPayload(
                    "脚本执行超过 ${JS_EVAL_TIMEOUT_SECONDS}s 已中止（可能存在无限循环）。" +
                        "请缩短计算量或改写为可终止的循环。"
                )
            } catch (e: java.util.concurrent.ExecutionException) {
                return@execute failPayload(
                    "脚本执行异常: ${e.cause?.message ?: e.message}"
                )
            }
            val payload = buildJsonObject {
                if (logs.isNotEmpty()) {
                    put("logs", JsonPrimitive(logs.joinToString("\n")))
                }
                put(
                    key = "result",
                    element = when (result) {
                        null -> JsonNull
                        is QuickJSObject -> JsonPrimitive(result.stringify())
                        else -> JsonPrimitive(result.toString())
                    }
                )
            }
            listOf(UIMessagePart.Text(payload.toString()))
        } finally {
            executor.shutdownNow()
        }
    }
)

/** v5.0.60: JS 单次求值上限（QuickJS 为阻塞 JNI，只能靠独立线程 + Future 超时兜底） */
private const val JS_EVAL_TIMEOUT_SECONDS = 20L
