package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.remote.RemoteStatus
import com.wangzan101.mika.core.remote.RemoteTasks
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskService
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.device.DeviceAccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 电脑能用的工具:只给高层的「把事交给 Mika」,具体怎么做由手机上的 Mika 自己决定。 */
class McpTaskTools(
    private val service: RemoteTaskService,
    device: DeviceAccess? = null,
) {
    constructor(tasks: RemoteTasks, pollMs: Long = 250, device: DeviceAccess? = null) :
        this(RemoteTaskService(tasks, pollMs), device)

    private val deviceTools = device?.let(::McpDeviceTools)
    class ToolError(message: String) : Exception(message)

    data class Tool(val name: String, val description: String, val schema: JsonObject, val readOnly: Boolean)

    val tools: List<Tool> = listOf(
        Tool(RUN, "Hand Mika a task to do on the Android phone. Mika runs its own agent on the phone (apps, web, files) " +
            "and records the task in a conversation the user can see in the app. Returns a task_id right away; " +
            "follow it with mika_wait_task. Payments and confirmations are never approved over MCP.",
            schema(required = listOf("goal")) {
                putJsonObject("goal") { put("type", "string"); put("description", "What to get done, in plain words, as you would tell a person.") }
                putJsonObject("request_id") {
                    put("type", "string"); put("format", "uuid")
                    put("description", "Choose a lowercase UUID before submitting. It is also the task_id for status after a lost reply. Reuse only for the same request.")
                }
                putJsonObject("conversation") {
                    put("type", "string")
                    put("description", "Optional: continue a conversation returned by an earlier task so Mika keeps its context.")
                }
            }, readOnly = false),
        Tool(STATUS, "Current state of a task: status, latest progress note, the question it is waiting on, or the final answer.",
            schema(required = listOf("task_id")) { taskIdProp() }, readOnly = true),
        Tool(WAIT, "Wait until the task's status changes or it finishes, up to timeout_s seconds " +
            "(default $DEFAULT_WAIT_S, at most $MAX_WAIT_S), then return its state. Call again to keep waiting.",
            schema(required = listOf("task_id")) {
                taskIdProp()
                putJsonObject("timeout_s") {
                    put("type", "integer"); put("minimum", 1); put("maximum", MAX_WAIT_S)
                    put("description", "Longest wait in seconds.")
                }
            }, readOnly = true),
        Tool(ANSWER, "Answer the question Mika asked in this task (status waiting_for_answer). " +
            "Cannot approve payments, confirmations or steps handed to the user; those stay on the phone.",
            schema(required = listOf("task_id", "text")) {
                taskIdProp()
                putJsonObject("text") { put("type", "string"); put("description", "The answer.") }
            }, readOnly = false),
        Tool(STEER, "Add instructions to a running remote task, keeping its task_id and context. " +
            "Accepted means queued, not executed; check steers in mika_task_status for the message_id. " +
            "Does not answer questions, approve payments, resume paused tasks, or start a new task.",
            schema(required = listOf("task_id", "text")) {
                taskIdProp()
                putJsonObject("text") { put("type", "string"); put("minLength", 1); put("maxLength", RemoteTaskService.MAX_STEER) }
            }, readOnly = false),
        Tool(CANCEL, "Stop a remote task.",
            schema(required = listOf("task_id")) { taskIdProp() }, readOnly = false),
        Tool(LIST, "Recent remote tasks, newest first.",
            schema(required = emptyList()) {
                putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", MAX_LIST); put("description", "How many (default $DEFAULT_LIST).") }
            }, readOnly = true),
    ) + deviceTools?.tools.orEmpty()

    /** MCP 的工具定义(tools/list 的一项)。 */
    fun definitions(): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("inputSchema", t.schema)
                putJsonObject("annotations") {
                    put("readOnlyHint", t.readOnly)
                    if (!t.readOnly) put("destructiveHint", t.name != ANSWER)
                    put("openWorldHint", t.name == RUN || t.name == STEER)
                }
            })
        }
    }

    fun has(name: String) = tools.any { it.name == name }

    /** 只做协议参数转换和 MCP 展示;业务规则由两个入口共用的 service 执行。 */
    suspend fun call(name: String, args: JsonObject): JsonObject = try {
        when (name) {
            RUN -> {
                val requestId = if ("request_id" in args) string(args, "request_id").orEmpty() else null
                describe(service.start(string(args, "goal").orEmpty(), optString(args, "conversation"), requestId))
            }
            STATUS -> describe(service.get(taskId(args)))
            WAIT -> service.wait(taskId(args), optInt(args, "timeout_s") ?: DEFAULT_WAIT_S).let { r ->
                buildJsonObject {
                    describe(r.task).forEach { (k, v) -> put(k, v) }
                    put("changed", r.changed)
                }
            }
            ANSWER -> buildJsonObject {
                put("task_id", service.answer(taskId(args), string(args, "text").orEmpty()))
                put("accepted", true)
            }
            STEER -> service.steer(taskId(args), string(args, "text").orEmpty()).let { r -> buildJsonObject {
                put("task_id", r.taskId); put("message_id", r.messageId); put("accepted", true)
            } }
            CANCEL -> service.cancel(taskId(args)).let { r -> buildJsonObject {
                put("task_id", r.taskId); put("cancelled", r.accepted)
                r.status?.let { put("status", it) }
            } }
            LIST -> buildJsonObject {
                putJsonArray("tasks") {
                    service.list(optInt(args, "limit") ?: DEFAULT_LIST).forEach { t ->
                        add(buildJsonObject {
                            put("task_id", t.id); put("status", RemoteStatus.of(t.state)); put("goal", t.goal)
                            put("conversation", t.conversation); put("started_at_ms", t.createdAtMs)
                        })
                    }
                }
            }
            else -> if (deviceTools?.tools?.any { it.name == name } == true) deviceTools.call(name, args)
                else throw ToolError("Unknown tool: $name")
        }
    } catch (e: RemoteTaskError) {
        // 按错误类型换回原 MCP 客户端看到的说法(带 MCP 工具名);共用服务不包含 MCP 命令名。
        throw ToolError(when (e) {
            is RemoteTaskError.NoSuchTask -> "No MCP task with id ${e.id}"
            is RemoteTaskError.BadConversation -> "conversation must be one returned by an earlier MCP task (looks like mcp-<uuid>)"
            is RemoteTaskError.NeedsPhone -> ON_PHONE_NOTE
            is RemoteTaskError.QuestionClosed -> "The question is no longer open; check mika_task_status."
            is RemoteTaskError.General -> e.message.orEmpty()
        })
    }

    private fun taskId(args: JsonObject) = string(args, "task_id").orEmpty()

    private fun describe(task: RemoteTask): JsonObject = buildJsonObject {
        RemoteStatus.describe(task).forEach { (k, v) -> put(k, v) }
        when (RemoteStatus.of(task.state)) {
            RemoteStatus.WAITING_ON_PHONE -> put("note", ON_PHONE_NOTE)
            RemoteStatus.WAITING_FOR_ANSWER -> put("note", "Mika is asking a question. Reply with mika_answer; the user can also answer on the phone.")
        }
    }

    private fun string(args: JsonObject, key: String): String? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw ToolError("$key must be a string")
        else -> throw ToolError("$key must be a string")
    }

    private fun optString(args: JsonObject, key: String) = string(args, key)

    private fun optInt(args: JsonObject, key: String): Int? = when (val v = args[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) throw ToolError("$key must be an integer")
            else v.intOrNull ?: v.contentOrNull?.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 && it in -1e9..1e9 }?.toInt()
                ?: throw ToolError("$key must be an integer")
        else -> throw ToolError("$key must be an integer")
    }

    companion object {
        const val RUN = "mika_run_task"
        const val STEER = "mika_steer_task"
        const val STATUS = "mika_task_status"
        const val WAIT = "mika_wait_task"
        const val ANSWER = "mika_answer"
        const val CANCEL = "mika_cancel_task"
        const val LIST = "mika_list_tasks"
        const val DEFAULT_WAIT_S = RemoteTaskService.DEFAULT_WAIT_S
        const val MAX_WAIT_S = RemoteTaskService.MAX_WAIT_S
        const val DEFAULT_LIST = RemoteTaskService.DEFAULT_LIST
        const val MAX_LIST = RemoteTaskService.MAX_LIST
        const val MAX_GOAL = RemoteTaskService.MAX_GOAL
        const val MAX_ANSWER = RemoteTaskService.MAX_ANSWER
        private const val ON_PHONE_NOTE = "This step needs the user on the phone and cannot be answered over MCP. " +
            "Mika has shown its usual notification there; keep waiting with mika_wait_task or cancel the task."

        fun newConversation(): String = RemoteTaskService.newConversation()
        fun isConversation(id: String): Boolean = RemoteTaskService.isConversation(id)

        private fun schema(required: List<String>, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties", props)
            putJsonArray("required") { required.forEach { add(it) } }
            put("additionalProperties", false)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.taskIdProp() =
            putJsonObject("task_id") { put("type", "string"); put("description", "The task_id returned by mika_run_task.") }
    }
}
