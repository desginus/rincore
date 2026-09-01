package me.rerere.rikkahub.data.ai.tools


/* ───【原版对齐】MemoryTools.kt | 差异 ±3 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory

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
