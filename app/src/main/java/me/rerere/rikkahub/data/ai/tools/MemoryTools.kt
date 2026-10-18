package me.rerere.rikkahub.data.ai.tools

/* ───【域 C·工具系统】MemoryTools.kt
 * 职责: 记忆工具 (增删改查/链接)
 * 常用改动: 工具行为 → MemoryTools 各方法; 存储 → data/repository/MemoryRepository.kt (Room: MemoryEntity)
 * 问题定位: 记忆不生效 → 本文件 + 注入链
 * 基线: 自研 | 地图: docs/APP_MAP.md §C | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.MemoryWriteResult
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.model.MemoryFile

/** v3.11.24 记忆内容长度上限 (F1 污染以 30+ 同构巨块写入实测) */
internal const val MEMORY_CONTENT_LIMIT_CHARS = 4000

/** v3.11.24 尾窗重复采样计数: 取尾部 window 字符的首 sampleLen 片段在全文的出现次数。
 *  大段自复读 (生成退化) 的强特征; 正常文本 96 字符片段全文出现 >=4 次近乎不可能。 */
internal fun repetitionSampleCount(text: String, sampleLen: Int = 96, window: Int = 768): Int {
    val t = text.trim()
    if (t.length < 400) return 0
    val tail = t.takeLast(window)
    val sample = tail.take(sampleLen)
    if (sample.count { it.isWhitespace() } > sampleLen / 2) return 0
    var count = 0
    var idx = t.indexOf(sample)
    while (idx >= 0) { count++; idx = t.indexOf(sample, idx + sampleLen) }
    return count
}

/** v3.11.30: 尾窗内片段近邻重复计数 — 复读退化 (连续同块) 的强特征。
 *  与 repetitionSampleCount 区别: 只在尾部 768 字符窗口内数, 不看全文。
 *  正常长文档的结构性重复 (表格行/清单模板散布全文) 在尾窗内通常仅 1-2 次。 */
internal fun repetitionTailCount(text: String, sampleLen: Int = 96, window: Int = 768): Int {
    val t = text.trim()
    if (t.length < sampleLen) return 0
    val tail = t.takeLast(window)
    var s = tail.take(sampleLen)
    // 尾部片段若空白占比过高 (表格边框线等), 退化为无判定
    if (s.count { it.isWhitespace() || it == '|' || it == '-' } > sampleLen * 2 / 3) return 0
    var count = 0
    var idx = tail.indexOf(s)
    while (idx >= 0) { count++; idx = tail.indexOf(s, idx + sampleLen) }
    return count
}

/**
 * v3.11.24 记忆健康门 — F1 (系统提示注入污染) 根治。
 * 2026-08-31 案: 退化生成产物 (错误日期锚 + 30+ 同构块 + typo 稳定传播)
 * 经 memory_tool 无校验落库, 之后每轮注入 system 记忆区毒化全部会话。
 * 门禁三则: 长度上限 / "今天=4月15日"式时间锚与真实时钟一致性断言 / 结构重复检测。
 * 返回 null = 健康; 非空 = 拒绝理由 (error 文本)。
 */
internal fun memoryHealthCheck(content: String): String? {
    val c = content.trim()
    if (c.isEmpty()) return "content is blank"
    if (c.length > MEMORY_CONTENT_LIMIT_CHARS) {
        return "content rejected: ${c.length} chars exceeds limit $MEMORY_CONTENT_LIMIT_CHARS. " +
            "Degenerate output suspected. Split into concise standalone records and retry."
    }
    // 时间锚一致性: 仅当内容把某日期断言为"今天/当前时间"时校验 (历史事实日期合法)
    val now = java.time.LocalDate.now()
    val anchorRegex = Regex(
        """(?:今天|今日|今天是|现在是|当前时间|current time(?: is)?|today(?: is)?)\D{0,6}((20\d{2})[-/年.](\d{1,2})[-/月.](\d{1,2}))""",
        RegexOption.IGNORE_CASE
    )
    for (m in anchorRegex.findAll(c)) {
        val y = m.groupValues[2].toIntOrNull() ?: continue
        val mo = m.groupValues[3].toIntOrNull() ?: continue
        val d = m.groupValues[4].toIntOrNull() ?: continue
        val date = runCatching { java.time.LocalDate.of(y, mo, d) }.getOrNull() ?: continue
        if (date.isAfter(now.plusDays(1)) || date.isBefore(now.minusDays(3))) {
            return "content rejected: asserts '$date' is current time but real today is $now. " +
                "Do not persist stale/merged time anchors into memories. Remove the time anchor or fix it."
        }
    }
    // 结构重复: 大段同构块 (退化产物可与任一 true 日期无关, 单凭重复度即可拦截)
    if (repetitionSampleCount(c) >= 4) {
        return "content rejected: contains the same 96-char block repeated 4+ times (degenerate output signature). " +
            "Rewrite as a single concise record."
    }
    return null
}

fun buildMemoryTools(
    json: Json,
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Long, String) -> AssistantMemory,
    onDelete: suspend (Long) -> Unit
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            The memory tool stores long-term information across conversations.
            Use `action` to control the operation: `create` (add), `edit` (update), `delete` (remove).
            - No relevant record: `create` + `content`
            - Existing relevant record: `edit` + `id` + `content`
            - Outdated/irrelevant record: `delete` + `id`
            Memories will automatically appear in the <memories> tag in later conversations.
            New memories follow the assistant's memory scope setting: assistant-wide by default;
            when 单对话记忆 (conversation-scoped memory) is on, they are mounted to the CURRENT
            conversation only — visible/editable there, and destroyed with that conversation.
            Do not store sensitive information (e.g., ethnicity, religion, sexual orientation, political views, sex life, criminal records).
            You may store: preferred name, preferences, plans, work-related notes, chat style preferences, first chat time, etc.
            Do not show memory content directly in the conversation unless the user explicitly asks.
            Similar memories should be merged; prefer updating existing records.

            Examples:
            {"action":"create","content":"User prefers brief replies and is more active on weekends."}
            {"action":"edit","id":12,"content":"User’s preferred name updated to “A-Xing”, prefers Chinese replies."}
            {"action":"delete","id":7}
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "Operation to perform: create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "The id of the memory record (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "The content of the memory record (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = {
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    // v3.11.24: 健康门 — 污染/退化/超限内容拒绝落库 (返回 error 给模型纠正)
                    memoryHealthCheck(content)?.let { error(it) }
                    json.encodeToJsonElement(AssistantMemory.serializer(), onCreation(content))
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.longOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    // v3.11.24: 健康门 — 编辑同受检, 防绕道 (删除重建路径也过此门)
                    memoryHealthCheck(content)?.let { error(it) }
                    json.encodeToJsonElement(AssistantMemory.serializer(), onUpdate(id, content))
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.longOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

object MemoryToolNames {
    const val READ = "memory_read"
    const val WRITE = "memory_write"
    const val STR_REPLACE = "memory_str_replace"
    const val APPEND = "memory_append"
    const val DELETE = "memory_delete"
    const val LIST = "memory_list"

    /** 会改动记忆库的工具 */
    val WRITES = setOf(WRITE, STR_REPLACE, APPEND, DELETE)
}

// ══ RinCore 兼容层（B1 并树）：上游 3.0.0 记忆 2.0「文件式记忆库」工具（MemoryConsolidator/ChatToolFactory 消费） ══
private const val MAX_READ_PATHS = 20

private const val IF_VERSION_DESCRIPTION =
    "The version returned by the most recent read or write of this file"

/**
 * 记忆库的读写工具，[assistantId] 决定操作哪个记忆库（助手自己的或全局的）。
 *
 * 何时读、何时写由 system prompt 里的记忆片段约束，这里只描述每个工具本身。
 */
fun buildMemoryTools(
    repository: MemoryRepository,
    assistantId: String,
): List<Tool> = listOf(
    Tool(
        name = MemoryToolNames.READ,
        description = "Read one or more memory files. Returns each file's content and version.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("maxItems", MAX_READ_PATHS)
                        put("description", "Paths of the files to read, e.g. [\"/topics/food.md\"]")
                    })
                },
                required = listOf("path")
            )
        },
        execute = { args ->
            // 有些模型只读一个文件时会直接传字符串
            val paths = when (val path = args.jsonObject["path"]) {
                is JsonArray -> path.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(path.contentOrNull)
                else -> emptyList()
            }.take(MAX_READ_PATHS)
            if (paths.isEmpty()) error("path is required")

            val files = paths.associateWith { repository.getFile(assistantId, it) }
            toolOutput {
                put("files", buildJsonArray {
                    files.values.filterNotNull().forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("version", file.version)
                            put("content", file.content)
                        })
                    }
                })
                val notFound = files.filterValues { it == null }.keys
                if (notFound.isNotEmpty()) {
                    put("not_found", buildJsonArray { notFound.forEach { add(it) } })
                }
            }
        }
    ),
    Tool(
        name = MemoryToolNames.WRITE,
        description = """
            Create a memory file or overwrite it entirely. content is the complete file; lines not included are removed.
            if_version: pass "new" for a new file, or the version returned by the most recent read or write of an existing file.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file, e.g. /topics/food.md"))
                    put("content", stringSchema("The complete file content"))
                    put("if_version", stringSchema("\"new\" for a new file, otherwise the file's current version"))
                },
                required = listOf("path", "content", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            val content = args.string("content")
            guarded(content) {
                repository.writeFile(assistantId, path, content, args.string("if_version"))
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.STR_REPLACE,
        description = """
            Replace the single occurrence of old_str in a memory file with new_str. An empty new_str deletes it.
            Rejected when old_str matches zero or several places.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("old_str", stringSchema("Text to replace; must match exactly one place"))
                    put("new_str", stringSchema("Replacement text; empty to delete"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "old_str", "new_str", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            val newStr = args.string("new_str")
            guarded(newStr) {
                repository.replaceInFile(assistantId, path, args.string("old_str"), newStr, args.string("if_version"))
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.APPEND,
        description = "Append one line to the end of a memory file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("content", stringSchema("The line to append"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "content", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            val content = args.string("content")
            if (content.isBlank()) error("content must not be blank")
            guarded(content) {
                repository.appendToFile(assistantId, path, content, args.string("if_version"))
            }.toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.DELETE,
        description = "Delete an entire memory file. Only use when the user explicitly asks to forget a whole topic.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", stringSchema("Path of the file"))
                    put("if_version", stringSchema(IF_VERSION_DESCRIPTION))
                },
                required = listOf("path", "if_version")
            )
        },
        execute = { args ->
            val path = args.string("path")
            repository.deleteFile(assistantId, path, args.string("if_version")).toToolOutput(path)
        }
    ),
    Tool(
        name = MemoryToolNames.LIST,
        description = "List memory files (path, size, update time), optionally filtered by path prefix.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path_prefix", stringSchema("Only list files whose path starts with this, e.g. /people/"))
                }
            )
        },
        execute = { args ->
            val prefix = (args.jsonObject["path_prefix"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val files = repository.getFiles(assistantId).filter { it.path.startsWith(prefix) }
            toolOutput {
                put("files", buildJsonArray {
                    files.forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("size", file.content.toByteArray().size)
                            put("updated_at", Instant.ofEpochMilli(file.updatedAt).toString())
                        })
                    }
                })
            }
        }
    ),
)

/** 这次工具调用成功改动的记忆文件路径；不是写工具、还没执行或写入被拒绝时返回 null */
fun UIMessagePart.Tool.memoryWritePath(): String? {
    if (toolName !in MemoryToolNames.WRITES || !isExecuted) return null
    val result = runCatching {
        Json.parseToJsonElement(output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text })
    }.getOrNull() as? JsonObject ?: return null
    if ("error" in result) return null
    return (result["path"] as? JsonPrimitive)?.contentOrNull
}

private fun stringSchema(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun JsonElement.string(key: String): String =
    (jsonObject[key] as? JsonPrimitive)?.contentOrNull ?: error("$key is required")

private fun toolOutput(builder: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))

private inline fun guarded(content: String, write: () -> MemoryWriteResult): MemoryWriteResult {
    val forbidden = findForbiddenMemoryContent(content) ?: return write()
    return MemoryWriteResult.Rejected(
        "Refused: the content looks like it contains $forbidden, which is never stored in memory. " +
            "Leave it out, and tell the user in one sentence that it cannot be saved."
    )
}

// 冲突时带上最新内容和版本，模型可以在同一轮里合并后重试
private fun MemoryWriteResult.toToolOutput(path: String): List<UIMessagePart> = toolOutput {
    when (val result = this@toToolOutput) {
        is MemoryWriteResult.Success -> {
            val file = result.file
            put("path", file?.path ?: path)
            if (file != null) put("version", file.version) else put("deleted", true)
        }

        is MemoryWriteResult.Conflict -> putConflict(path, result.current)
        is MemoryWriteResult.Rejected -> put("error", result.reason)
    }
}

private fun JsonObjectBuilder.putConflict(path: String, current: MemoryFile?) {
    if (current == null) {
        put(
            "error",
            "$path does not exist. Create it with ${MemoryToolNames.WRITE} and " +
                "if_version=\"${MemoryRepository.NEW_FILE_VERSION}\"."
        )
        return
    }
    put(
        "error",
        "Version conflict: $path already exists or has changed since you read it. " +
            "Merge your change into current_content and retry with current_version."
    )
    put("current_version", current.version)
    put("current_content", current.content)
}
