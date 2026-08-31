package me.rerere.rikkahub.data.ai.tools


/* ───【自研】TaskTools.kt — Cherry Studio Agent 任务功能移植
 * 任务清单 = TodoWrite 全量替换模式, 清单持久化在会话消息的 Tool output 中,
 * 跨轮经上下文可见, 零 DB 依赖。
 * ───────────────────────────────────────────────────────────────*/
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

/**
 * 任务清单工具 — Cherry Studio Agent 任务功能等价 (TodoWrite 全量清单模式)。
 * 模型用它自建任务步骤引导自己: 多步任务先建清单, 执行中更新状态, 全部完成后交付。
 * 清单作为 tool output 持久化在会话消息中, 跨轮经上下文可见, 零 DB 依赖。
 */
fun createTaskTool(): Tool = Tool(
    name = "task_tool",
    description = "任务清单管理 (全量替换模式)。多步骤任务必须先创建任务清单引导自己: " +
        "把目标拆解为可核验的步骤; 执行中同一时间只允许一个 in_progress; " +
        "完成一步立即更新状态; 全部 completed 后才能交付最终结果。每次调用以完整清单全量替换 (未列出的任务视为删除)。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("tasks", buildJsonObject {
                    put("type", "array")
                    put("description", "完整任务清单 (全量替换, 未列出的任务视为删除)")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "任务唯一 id, 如 task-1")
                            })
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "任务标题 (祈使句, 如: 分析污染来源)")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("pending"); add("in_progress"); add("completed")
                                })
                                put("description", "pending=待办, in_progress=进行中, completed=已完成")
                            })
                            put("activeForm", buildJsonObject {
                                put("type", "string")
                                put("description", "进行中时的现在进行时描述 (如: 正在分析污染来源)")
                            })
                        })
                        put("required", buildJsonArray { add("id"); add("title"); add("status") })
                    })
                })
            },
            required = listOf("tasks")
        )
    },
    systemPrompt = { _, _ ->
        """
        ### 任务清单 (task_tool) 使用规则
        面对多步骤、有明确交付物、或容易遗漏步骤的任务时, 必须先创建任务清单引导自己:
        1. 调用 task_tool 写入完整任务清单 (含全部步骤, 初始 pending);
        2. 开始某步骤前将其标记 in_progress (同一时间只允许一个 in_progress);
        3. 完成步骤立即更新为 completed, 同时把下一步置为 in_progress;
        4. 全部 completed 后才能向用户交付最终结果;
        5. 计划外新步骤 → 追加进清单; 不可行步骤 → 标记 completed 并说明原因。
        简单问答 (1-2 步可完成) 不需要清单。
        """.trimIndent()
    },
    execute = { input ->
        val params = input.jsonObject
        val tasksArr = params["tasks"]?.jsonArray ?: error("tasks is required (array)")
        val seenIds = HashSet<String>()
        var inProgress = 0
        var completed = 0
        val normalized = buildJsonArray {
            tasksArr.forEach { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("task.id is required")
                require(seenIds.add(id)) { "duplicate task id: $id" }
                val title = obj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: error("task.title is required (id=$id)")
                val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                require(status in VALID_STATUS) { "invalid status: $status (id=$id)" }
                if (status == "in_progress") inProgress++
                if (status == "completed") completed++
                val activeForm = obj["activeForm"]?.jsonPrimitive?.contentOrNull
                add(buildJsonObject {
                    put("id", id)
                    put("title", title)
                    put("status", status)
                    if (!activeForm.isNullOrBlank()) put("activeForm", activeForm)
                })
            }
        }
        require(tasksArr.isNotEmpty()) { "tasks must not be empty" }
        // v3.11.26: sequential-thinking 式推进反馈 — 引擎回话, 每次写入返回进度与下一步指引
        val total = tasksArr.size
        val pending = total - inProgress - completed
        val progress = buildJsonObject {
            put("total", total)
            put("completed", completed)
            put("in_progress", inProgress)
            put("pending", pending)
        }
        val firstInProgressTitle = buildString {
            tasksArr.forEach { el ->
                val o = el.jsonObject
                if (o["status"]?.jsonPrimitive?.contentOrNull == "in_progress") {
                    val t = o["title"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (isNotEmpty()) append("; ")
                    append(t)
                }
            }
        }
        val hint = when {
            inProgress == 0 && completed == 0 -> "清单已建立。开始执行: 将第一步标记为 in_progress 并立即着手。"
            inProgress == 0 && completed < total -> "当前无进行中任务。从剩余 pending 步骤中选下一步标记 in_progress。"
            inProgress == 0 && completed >= total -> "全部步骤 completed。核对结果后即可向用户交付最终总结。"
            inProgress > 1 -> "警告: 同时有 $inProgress 个 in_progress (=$firstInProgressTitle)。按规则同一时间只允许一个, 立即收敛。"
            else -> "进行中: $firstInProgressTitle。完成后立即标记 completed 并推进下一步。"
        }
        listOf(
            UIMessagePart.Text(
                JsonInstant.encodeToString(
                    buildJsonObject {
                        put("tasks", normalized)
                        put("progress", progress)
                        put("hint", hint)
                    }
                )
            )
        )
    },
)
