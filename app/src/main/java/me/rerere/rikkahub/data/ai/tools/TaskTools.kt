package me.rerere.rikkahub.data.ai.tools


/* ───【自研】TaskTools.kt — Cherry Studio Agent 任务功能移植
 * 任务清单 = 全量替换模式, 清单持久化在会话消息的 Tool output 中,
 * 跨轮经上下文可见, 零 DB 依赖。
 * v3.11.30: 22 条压测修复 — 输入清洗/严格类型/规模上限/单行错误/轻量响应/
 * removed 报告/回退警示/双 in_progress 硬拒/[] 清空/action=get/振荡警示/注入防御。
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant

private val VALID_STATUS = setOf("pending", "in_progress", "completed")
private val ID_REGEX = Regex("""[A-Za-z0-9_\-]{1,32}""")
private const val MAX_TASKS = 50
private const val MAX_TITLE_CHARS = 200
private const val MAX_ACTIVE_FORM_CHARS = 100
private const val MAX_HINT_CHARS = 300

/**
 * 进程内任务清单状态 — 支持 removed 报告/回退警示/振荡检测。
 * 进程重启后清空 (清单真源在会话消息的 tool output 中, get 时提示模型读上下文)。
 */
internal object TaskStateStore {
    @Volatile var version: Int = 0
    @Volatile var snapshot: List<TaskItem> = emptyList()
    val flipHistory = LinkedHashMap<String, MutableList<String>>()
    val reopenCounts = HashMap<String, Int>()
}

internal data class TaskItem(
    val id: String,
    val title: String,
    val status: String,
    val activeForm: String? = null,
)

/**
 * 输入清洗 (IN-1/SC-1): 剥离 C0/C1/Cf 控制字符 (含 RLO/LRO 双向欺骗、零宽、
 * 组合符), CR/LF/TAB 折叠为空格, trim。
 */
internal fun sanitizeTaskText(raw: String): String = buildString(raw.length) {
    for (c in raw) {
        when {
            c == '\n' || c == '\r' || c == '\t' -> append(' ')
            Character.getType(c) == Character.CONTROL.toInt() -> {}
            Character.getType(c) == Character.FORMAT.toInt() -> {}
            else -> append(c)
        }
    }
}.replace("  +".toRegex(), " ").trim()

private fun isPlainString(el: JsonElement?): Boolean =
    el is JsonPrimitive && el.isString

/** 压测 RS-1: 校验错误一律单行可读 JSON (index 定位 + fix 指引), 零堆栈。 */
private fun errorPayload(message: String, index: Int? = null, fix: String? = null): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            JsonInstant.encodeToString(
                buildJsonObject {
                    put("ok", false)
                    put("error", message.take(240))
                    if (index != null) put("index", index)
                    if (fix != null) put("fix", fix.take(160))
                    put("hint", "修正参数后重新调用; 或用 action=get 查看当前清单现状 (无副作用)。")
                }
            )
        )
    )

private fun hintFor(firstInProgress: String?, inProgress: Int, completed: Int, total: Int, extra: String?): String {
    val base = when {
        total == 0 -> "清单已清空。如需继续, 重新创建任务清单。"
        inProgress == 0 && completed == 0 -> "清单已建立。开始执行: 将第一步标记为 in_progress 并立即着手。"
        inProgress == 0 && completed < total -> "当前无进行中任务。从剩余 pending 步骤中选下一步标记 in_progress。"
        inProgress == 0 && completed >= total -> "全部步骤 completed。核对结果后即可向用户交付最终总结。"
        else -> {
            val t = firstInProgress?.let { "「${it.take(24)}」" } ?: ""
            "进行中: $t。完成后立即标记 completed 并推进下一步。"
        }
    }
    val merged = if (extra.isNullOrBlank()) base else "$extra $base"
    // RS-3: hint 总长 ≤300, 超出折叠; SC-1: 内容已经过清洗, 无注入面
    return if (merged.length <= MAX_HINT_CHARS) merged
    else merged.take(MAX_HINT_CHARS - 12) + "…(另有 ${merged.length - MAX_HINT_CHARS + 12} 字符)"
}

/**
 * 任务清单工具 — 全量替换模式 + get/clear。
 * 每次调用以完整清单替换; 响应只回进度与变更摘要 (轻量, <1KB)。
 */
fun createTaskTool(): Tool = Tool(
    name = "task_tool",
    description = "任务清单管理。多步骤任务必须先创建任务清单引导自己: " +
        "把目标拆解为可核验的步骤; 执行中同一时间只允许一个 in_progress 任务; " +
        "完成一步立即更新状态; 全部 completed 后才能交付最终结果。" +
        "默认 action=replace: 以完整清单全量替换 (未列出的任务视为移除, 响应会回报 removed 列表); " +
        "action=get: 只读查询当前清单 (无副作用); action=clear: 清空清单。" +
        "约束: 任务数 ≤50, title ≤200 字符, id 匹配 [A-Za-z0-9_-]{1,32}。" +
        "若调用被拒绝, 不要原样重试 — 先按 error/fix 修正, 必要时先 action=get 查看现状。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("replace"); add("get"); add("clear") })
                    put("description", "replace=全量替换 (默认), get=只读查询, clear=清空")
                })
                put("tasks", buildJsonObject {
                    put("type", "array")
                    put("description", "完整任务清单 (action=replace 时必填)")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "任务唯一 id ([A-Za-z0-9_-]{1,32}, 如 task-1)")
                            })
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "任务标题 (祈使句, ≤200 字符)")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("pending"); add("in_progress"); add("completed")
                                })
                                put("description", "必填, 精确值: pending / in_progress / completed")
                            })
                            put("activeForm", buildJsonObject {
                                put("type", "string")
                                put("description", "仅 status=in_progress 时有效 (现在进行时描述)")
                            })
                        })
                        put("required", buildJsonArray { add("id"); add("title"); add("status") })
                    })
                })
            },
            required = listOf<String>()
        )
    },
    systemPrompt = { _, _ ->
        """
        ### 任务清单 (task_tool) 使用规则
        面对多步骤、有明确交付物、或容易遗漏步骤的任务时, 必须先创建任务清单引导自己:
        1. 调用 task_tool (默认 replace) 写入完整任务清单 (含全部步骤, 初始 pending);
        2. 开始某步骤前将其标记 in_progress (同一时间只允许一个, 双个会被拒绝);
        3. 完成步骤立即更新为 completed, 同时把下一步置为 in_progress;
        4. 全部 completed 后才能向用户交付最终结果;
        5. 计划外新步骤 → 追加进清单; 不可行步骤 → 标记 completed 并说明原因;
        6. 不确定清单现状时用 action=get 只读查询, 不要盲目重写。
        简单问答 (1-2 步可完成) 不需要清单。
        注意: 已完成任务回退 (completed→pending/in_progress) 会被记录并警告。
        """.trimIndent()
    },
    execute = { input ->
        val params = runCatching { input.jsonObject }.getOrNull()
            ?: return@Tool errorPayload("invalid input: expected JSON object")
        val action = params["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "replace"

        // ── get: 只读查询, 无副作用, version 不变 ──
        if (action == "get") {
            val snapshot = TaskStateStore.snapshot
            if (snapshot.isEmpty()) {
                return@Tool listOf(UIMessagePart.Text(
                    JsonInstant.encodeToString(buildJsonObject {
                        put("ok", true)
                        put("tasks", buildJsonArray { })
                        put("note", "进程内无清单快照 (可能因重启丢失)。请直接读取上下文中最近一次 task_tool 的调用参数作为当前清单。")
                    })
                ))
            }
            val completed = snapshot.count { it.status == "completed" }
            val inProgress = snapshot.count { it.status == "in_progress" }
            return@Tool listOf(UIMessagePart.Text(
                JsonInstant.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("version", TaskStateStore.version)
                    put("tasks", buildJsonArray {
                        snapshot.forEach { t ->
                            add(buildJsonObject {
                                put("id", t.id)
                                put("title", t.title)
                                put("status", t.status)
                                if (t.activeForm != null) put("activeForm", t.activeForm)
                            })
                        }
                    })
                    put("progress", buildJsonObject {
                        put("total", snapshot.size)
                        put("completed", completed)
                        put("in_progress", inProgress)
                        put("pending", snapshot.size - completed - inProgress)
                    })
                    put("hint", hintFor(
                        snapshot.firstOrNull { it.status == "in_progress" }?.title,
                        inProgress, completed, snapshot.size, null))
                })
            ))
        }

        // ── clear: 清空清单 ──
        if (action == "clear") {
            val removedCount = TaskStateStore.snapshot.size
            TaskStateStore.snapshot = emptyList()
            TaskStateStore.version += 1
            return@Tool listOf(UIMessagePart.Text(
                JsonInstant.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("removed_count", removedCount)
                    put("note", "清单已清空 (原 $removedCount 条)。")
                })
            ))
        }

        if (action != "replace") {
            return@Tool errorPayload("invalid action: $action", fix = "action 必须是 replace / get / clear")
        }

        // ── replace: 全量替换 ──
        val tasksArr = params["tasks"]
        if (tasksArr !is JsonArray) {
            return@Tool errorPayload("tasks is required (array) for action=replace", fix = "提供 tasks 数组, 或改用 action=get/clear")
        }
        if (tasksArr.size > MAX_TASKS) {
            return@Tool errorPayload(
                "too many tasks: ${tasksArr.size} exceeds limit $MAX_TASKS",
                fix = "拆分清单: 单次 ≤$MAX_TASKS 条, 先建主干再原子任务"
            )
        }
        // ST-4: [] = 清空, 合法
        if (tasksArr.isEmpty()) {
            val removedCount = TaskStateStore.snapshot.size
            TaskStateStore.snapshot = emptyList()
            TaskStateStore.version += 1
            return@Tool listOf(UIMessagePart.Text(
                JsonInstant.encodeToString(buildJsonObject {
                    put("ok", true)
                    put("removed_count", removedCount)
                    put("note", "空清单 = 清空语义, 已清空原 $removedCount 条; 已计入 version=${TaskStateStore.version}。")
                })
            ))
        }

        val seenIds = HashSet<String>()
        var inProgress = 0
        var completed = 0
        val parsed = ArrayList<TaskItem>(tasksArr.size)
        var firstInProgressTitle: String? = null
        tasksArr.forEachIndexed { idx, el ->
            val obj = (el as? JsonObject)
                ?: return@Tool errorPayload("task at index=$idx is not an object", index = idx,
                    fix = "tasks 数组每项须为 {id,title,status} 对象")
            // IN-2/IN-3: id 必须是纯 string 且匹配白名单 (拒绝数字/空格/中文/emoji/控制字符)
            if (!isPlainString(obj["id"])) {
                return@Tool errorPayload("task.id must be a string (index=$idx; got ${obj["id"]?.let { it::class.simpleName }})", index = idx,
                    fix = "id 用纯 ASCII 短横线命名, 如 task-1")
            }
            val rawId = obj["id"]!!.jsonPrimitive.contentOrNull ?: ""
            val id = sanitizeTaskText(rawId)
            if (!ID_REGEX.matches(id)) {
                return@Tool errorPayload("invalid task.id: \"$id\" (index=$idx)", index = idx,
                    fix = "id 匹配 [A-Za-z0-9_-]{1,32}, 不含空格/中文/emoji/控制字符")
            }
            if (!seenIds.add(id)) {
                return@Tool errorPayload("duplicate task id: $id (index=$idx)", index = idx, fix = "每个 id 必须唯一")
            }
            // title: string + 清洗 + 上限
            if (!isPlainString(obj["title"])) {
                return@Tool errorPayload("task.title must be a string (id=$id)", index = idx, fix = "title 用自然语言描述任务")
            }
            val title = sanitizeTaskText(obj["title"]!!.jsonPrimitive.contentOrNull ?: "")
            if (title.isBlank()) {
                return@Tool errorPayload("task.title is blank (id=$id)", index = idx, fix = "title 不能为空")
            }
            if (title.length > MAX_TITLE_CHARS) {
                return@Tool errorPayload("task.title too long: ${title.length} > $MAX_TITLE_CHARS (id=$id)", index = idx,
                    fix = "压缩到 ≤$MAX_TITLE_CHARS 字符")
            }
            // IN-4: status 必填 + 精确枚举 (trim + lower 后仍须匹配)
            if (!isPlainString(obj["status"])) {
                return@Tool errorPayload("task.status is required (id=$id)", index = idx,
                    fix = "status ∈ pending / in_progress / completed, 缺省不收")
            }
            val status = obj["status"]!!.jsonPrimitive.contentOrNull?.trim()?.lowercase() ?: ""
            if (status !in VALID_STATUS) {
                return@Tool errorPayload(
                    "invalid status: \"$status\" (id=$id)", index = idx,
                    fix = "合法值: pending | in_progress | completed (精确匹配, 勿加空格或大小写变体)"
                )
            }
            // IN-7: 非 in_progress 一律剥离 activeForm; 空串拒
            val rawActiveForm = if (isPlainString(obj["activeForm"])) {
                sanitizeTaskText(obj["activeForm"]!!.jsonPrimitive.contentOrNull ?: "")
            } else null
            val activeForm = when {
                status != "in_progress" -> null
                rawActiveForm.isNullOrBlank() -> null
                else -> rawActiveForm.take(MAX_ACTIVE_FORM_CHARS)
            }
            if (status == "in_progress") {
                inProgress++
                if (firstInProgressTitle == null) firstInProgressTitle = title
            }
            if (status == "completed") completed++
            parsed += TaskItem(id, title, status, activeForm)
        }

        // ST-3: 双 in_progress 硬拒绝 (与描述契约一致)
        if (inProgress > 1) {
            return@Tool errorPayload(
                "$inProgress tasks with status=in_progress (must be exactly 1)",
                index = null,
                fix = "只保留真正在做的第一步为 in_progress, 其余改回 pending; 重新提交完整清单"
            )
        }

        // ST-1: removed 报告; ST-2: 回退警示; DL-2: 振荡检测
        val removed = TaskStateStore.snapshot.map { it.id }.filter { newId -> parsed.none { it.id == newId } }
        val reopened = ArrayList<String>()
        var oscillating = false
        parsed.forEach { task ->
            val old = TaskStateStore.snapshot.firstOrNull { it.id == task.id }
            if (old != null && old.status == "completed" && task.status != "completed") {
                val n = (TaskStateStore.reopenCounts[task.id] ?: 0) + 1
                TaskStateStore.reopenCounts[task.id] = n
                reopened += task.id
            }
            if (old != null && old.status != task.status) {
                val flips = TaskStateStore.flipHistory.getOrPut(task.id) { mutableListOf() }
                flips.add(task.status)
                if (flips.size >= 4) oscillating = true
            }
        }

        TaskStateStore.version += 1
        TaskStateStore.snapshot = parsed

        val total = parsed.size
        val pending = total - inProgress - completed
        val extra = buildString {
            if (removed.isNotEmpty()) append("已移除 ${removed.size} 项 (${removed.take(6).joinToString(",")}${if (removed.size > 6) "…" else ""})。 ")
            if (reopened.isNotEmpty()) append("⚠️ ${reopened.size} 项已完成任务被回退 (${reopened.take(4).joinToString(",")}), 请确认是否有意重开。 ")
            if (oscillating) append("⚠️ 检测到部分任务状态反复翻转, 停止无意义的状态往返。 ")
        }.takeIf { it.isNotBlank() }

        val hint = hintFor(firstInProgressTitle, inProgress, completed, total, extra)

        listOf(
            UIMessagePart.Text(
                JsonInstant.encodeToString(
                    buildJsonObject {
                        put("ok", true)
                        put("version", TaskStateStore.version)
                        put("progress", buildJsonObject {
                            put("total", total)
                            put("completed", completed)
                            put("in_progress", inProgress)
                            put("pending", pending)
                        })
                        if (removed.isNotEmpty()) {
                            put("removed", buildJsonArray { removed.forEach { add(it) } })
                        }
                        if (reopened.isNotEmpty()) {
                            put("reopened", buildJsonArray { reopened.forEach { add(it) } })
                        }
                        put("hint", hint)
                    }
                )
            )
        )
    },
)
