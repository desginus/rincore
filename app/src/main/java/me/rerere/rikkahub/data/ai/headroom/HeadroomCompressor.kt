package me.rerere.rikkahub.data.ai.headroom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import android.util.Log

/**
 * Headroom 上下文降维 (v3.6.19)
 *
 * 对话两种模式:
 *  - 默认模式: 消息原样发送, 不做任何压缩
 *  - 降维模式 (设置开关 headroomCompression): 工具输出经确定性规则压缩
 *
 * 铁律: 缓存率凌驾一切 — 本压缩器是纯规则、确定性实现 (同输入必同输出),
 * 压缩结果作为请求体前缀时与上一轮稳定一致, 不破坏 DeepSeek 前缀缓存。
 *
 * 压缩策略 (信息留存第一, 压缩率第二):
 *  1. 只压缩工具输出 (Tool.output 的 Text) — 对话文本消息不动 (信息留存)
 *  2. JSON 数组: 无损层 (去重/常量字段提取/紧凑序列化) → 有损层
 *     (仅数组 >30 项才采样, 首 30% + 尾 15% + 错误项优先, 最多 15 项)
 *  3. 非 JSON 长文本: 无损紧凑 (连续空行/行尾空白)
 *  4. 幂等: 已压缩内容 (带标记) 跳过, 避免重复采样破坏前缀
 *
 * 规则参照 GitHub chopratejas/headroom (SmartCrusher) 的核心策略移植。
 */
object HeadroomCompressor {
    private const val TAG = "Headroom"
    private const val MARKER = "[Headroom-压缩"

    // 错误关键词 — 采样时必须保留的项 (headroom ERROR_KEYWORDS 移植)
    private val ERROR_KEYWORDS = listOf(
        "error", "exception", "failed", "failure", "denied", "refused",
        "not found", "timeout", "unreachable", "错误", "异常", "失败", "拒绝", "超时"
    )

    private const val MIN_ITEMS = 5          // 数组少于 5 项不分析
    private const val MIN_CHARS = 200        // 内容少于 200 字符不压缩
    private const val LOSSY_THRESHOLD = 30   // 数组超过 30 项才走有损采样
    private const val MAX_ITEMS = 15         // 有损后最多保留 15 项
    private const val FIRST_FRACTION = 0.30  // 头部保留比例
    private const val LAST_FRACTION = 0.15   // 尾部保留比例

    private val json = Json { prettyPrint = false }

    /**
     * 压缩消息列表。只作用于已执行工具的输出 Text, 消息结构与角色不动。
     */
    fun compress(messages: List<UIMessage>): List<UIMessage> {
        if (messages.isEmpty()) return messages
        var changed = false
        val result = messages.map { msg ->
            if (msg.parts.none { it is UIMessagePart.Tool && it.isExecuted }) {
                msg
            } else {
                var msgChanged = false
                val newParts = msg.parts.map { part ->
                    if (part is UIMessagePart.Tool && part.isExecuted) {
                        var toolChanged = false
                        val newOutput = part.output.map { out ->
                            if (out is UIMessagePart.Text && out.content.length >= MIN_CHARS) {
                                val crushed = crush(out.content)
                                if (crushed != out.content) {
                                    toolChanged = true
                                    UIMessagePart.Text(crushed)
                                } else out
                            } else out
                        }
                        if (toolChanged) {
                            msgChanged = true
                            part.copy(output = newOutput)
                        } else part
                    } else part
                }
                if (msgChanged) {
                    changed = true
                    msg.copy(parts = newParts)
                } else msg
            }
        }
        if (changed) {
            Log.i(TAG, "压缩完成: ${messages.size} 条消息, 工具输出已降维")
        }
        return result
    }

    /**
     * 压缩单段文本。确定性规则。
     */
    fun crush(content: String): String {
        // 幂等: 已压缩内容跳过 (避免重复采样破坏前缀稳定)
        if (content.contains(MARKER)) return content

        // JSON 数组 → SmartCrusher 规则
        val element = runCatching { json.parseToJsonElement(content) }.getOrNull()
        if (element is JsonArray && element.size >= MIN_ITEMS && content.length >= MIN_CHARS) {
            val crushed = crushArray(element)
            if (crushed != null && crushed.length < content.length) {
                Log.d(TAG, "JSON 数组压缩: ${content.length} → ${crushed.length} 字符 (${element.size} 项)")
                return crushed
            }
        }

        // 非 JSON 长文本: 无损紧凑 (连续空行 → 单空行, 行尾空白)
        return compactText(content)
    }

    // ── JSON 数组压缩 (SmartCrusher 核心策略移植) ──────────────────

    private fun crushArray(array: JsonArray): String? {
        // 1. 去重相同项 (保序) — 无损
        val items = array.filterIndexed { i, el -> array.indexOfFirst { it == el } == i }
        if (items.size < MIN_ITEMS) return null

        // 2. 常量字段提取: 全项同值字段 → 头部声明 + 从项中删除 (信息保留, 去冗余)
        val constFields = extractConstantFields(items)

        // 3. 有损采样 (仅大数组): 首 30% + 尾 15% + 错误项, 最多 MAX_ITEMS
        var selected: List<JsonElement>
        if (items.size > LOSSY_THRESHOLD) {
            val keep = LinkedHashSet<Int>()
            val firstCount = maxOf(1, (items.size * FIRST_FRACTION).toInt())
            val lastCount = maxOf(1, (items.size * LAST_FRACTION).toInt())
            repeat(firstCount) { keep.add(it) }
            for (i in items.size - lastCount until items.size) keep.add(i)
            // 错误项优先保留
            items.indices.forEach { i ->
                if (containsError(items[i])) keep.add(i)
            }
            // 变化点: 相邻项不同且未保留的, 从中间均匀补足到 MAX_ITEMS
            if (keep.size < MAX_ITEMS) {
                val midRange = (firstCount until items.size - lastCount).toList()
                midRange.forEach { i ->
                    if (keep.size >= MAX_ITEMS) return@forEach
                    val prev = items[i - 1]
                    if (items[i] != prev && i !in keep) keep.add(i)
                }
            }
            selected = keep.sorted().map { items[it] }
        } else {
            selected = items
        }

        // 4. 常量字段从项中删除 (信息已在头部声明) — 仅删声明过的顶层字段
        if (constFields.isNotEmpty()) {
            selected = selected.map { el ->
                if (el is JsonObject) {
                    val newObj = el.filterKeys { it !in constFields }
                    if (newObj.size != el.size) JsonObject(newObj) else el
                } else el
            }
        }

        // 5. 组装: 常量声明 + 紧凑数组 + 压缩标记
        val builder = StringBuilder()
        if (constFields.isNotEmpty()) {
            builder.append("/* 全部项恒值: ")
            builder.append(constFields.entries.joinToString(", ") { "${it.key}=${it.value}" })
            builder.append(" */\n")
        }
        builder.append(JsonArray(selected).toString())
        if (selected.size < items.size) {
            builder.append(MARKER)
            builder.append(" ${items.size}→${selected.size} 项]")
        }
        return builder.toString()
    }

    /** 全项同值字段 (唯一率 ≈ 0) — 提取为头部声明, 保留信息同时去冗余 */
    private fun extractConstantFields(items: List<JsonElement>): Map<String, String> {
        val objects = items.filterIsInstance<JsonObject>()
        if (objects.size < items.size) return emptyMap()
        val first = objects.first()
        val result = LinkedHashMap<String, String>()
        first.forEach { (key, value) ->
            val allSame = objects.drop(1).all { it[key] == value }
            if (allSame) result[key] = value.toString().take(80)
        }
        // 只提取短值字段 (长值恒同可能是大块内容, 保留原文更安全)
        return result.filter { (_, v) -> v.length <= 40 }
    }

    private fun containsError(element: JsonElement): Boolean {
        val text = element.toString()
        return ERROR_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    // ── 非 JSON 无损紧凑 (日志/文本类工具输出) ────────────────────

    private fun compactText(content: String): String {
        // 1. 完全重复行合并: "xxx" 连续出现 N 次 → "xxx (×N)" — 无损 (信息保留)
        // 2. 连续空行 → 单个, 行尾空白去除 — 无损
        val lines = content.split("\n").map { it.trimEnd() }
        val result = buildString {
            var i = 0
            var blankPending = false
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    blankPending = true
                    i++
                    continue
                }
                if (blankPending && isNotEmpty()) {
                    append('\n')
                }
                blankPending = false
                // 统计连续相同行
                var run = 1
                while (i + run < lines.size && lines[i + run] == line) {
                    run++
                }
                append(line)
                if (run >= 4) {
                    append(" (×")
                    append(run)
                    append(')')
                }
                append('\n')
                i += run
            }
            if (isNotEmpty() && last() == '\n') {
                setLength(length - 1)
            }
        }
        return if (result.length < content.length) result else content
    }
}
