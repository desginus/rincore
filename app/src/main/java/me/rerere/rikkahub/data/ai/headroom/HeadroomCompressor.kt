package me.rerere.rikkahub.data.ai.headroom

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Headroom 上下文降维 (v3.6.24)
 *
 * 对话两种模式, 通过右上角「上下文降维」开关隔离:
 *  - 关闭 (默认): 本压缩器完全不介入, 消息原样发送 (零干预)
 *  - 开启: 所有会发送向 API 的消息内容都经过压缩, 提高信息密度
 *
 * 铁律: 缓存率凌驾一切 — 纯规则、确定性实现 (同输入必同输出),
 * 压缩结果作为请求前缀与上一轮稳定一致, 不破坏 DeepSeek 前缀缓存。
 *
 * 压缩范围 (开启时):
 *  1. 工具输出 (Tool.output 的 Text): 内容级压缩
 *     - JSON 数组: 去重 / 常量字段提取(头部声明+删字段) / 大数组采样
 *       (首 30% + 尾 15% + 错误项优先, 最多 15 项)
 *     - 非 JSON 日志: 重复行合并 (相同行 ×N) + 空行归一
 *  2. 消息正文 (用户/助手历史 Text): 无损紧凑 (空行归一/重复行合并)
 *
 * 不压缩 (保证功能与缓存):
 *  - system prompt (缓存前缀基础, 静态)
 *  - 工具定义 tools 数组 (模型需要完整 schema, 工具调用正常)
 *  - 工具调用参数 (模型需要读取)
 *
 * 规则参照 GitHub chopratejas/headroom (SmartCrusher) 核心策略移植。
 */
/**
 * 压缩统计 — 发送后 UI 可见 (降维标签显示上轮压缩结果)
 */
object HeadroomStats {
    val lastResult = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    // v3.6.37: 上轮实际发送请求体的文本字符数 (验证压缩是否真正进入请求)
    val lastRequestChars = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    // v3.6.38: 上轮压缩节省估算 token (字符差 / 2.5 — 消息统计行直接显示)
    val lastSavedTokens = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
}

object HeadroomCompressor {
    private const val TAG = "Headroom"
    private const val MARKER = "[Headroom-压缩"

    // 错误关键词 — 采样时必须保留的项 (headroom ERROR_KEYWORDS 移植)
    private val ERROR_KEYWORDS = listOf(
        "error", "exception", "failed", "failure", "denied", "refused",
        "not found", "timeout", "unreachable", "错误", "异常", "失败", "拒绝", "超时"
    )

    // v3.6.27: 历史骨架压缩 (用户选定方案 — 每轮压成首句+结论, 压缩明显)
    private const val MIN_ITEMS = 5          // JSON 数组少于 5 项不分析
    private const val MIN_CHARS = 150        // 工具输出少于 150 字符不压
    private const val TEXT_MIN_CHARS = 50    // 消息正文少于 50 字符不压
    private const val SKELETON_USER_MIN = 60    // 用户历史超过此长度骨架化 (v3.6.29 极限降低)
    private const val SKELETON_ASST_MIN = 80    // 助手历史超过此长度骨架化 (v3.6.29 极限降低)
    private const val USER_HEAD_MAX = 80        // 用户骨架: 首句最长
    private const val USER_TAIL_MAX = 60        // 用户骨架: 末句最长
    private const val ASST_HEAD_MAX = 120       // 助手骨架: 首句最长
    private const val ASST_TAIL_MAX = 100       // 助手骨架: 末句最长
    private const val LOSSY_THRESHOLD = 30   // 数组超过 30 项才走有损采样
    private const val MAX_ITEMS = 15         // 有损后最多保留 15 项
    private const val FIRST_FRACTION = 0.30  // 头部保留比例
    private const val LAST_FRACTION = 0.15   // 尾部保留比例

    private val json = Json { prettyPrint = false }

    /**
     * 压缩消息列表。只作用于消息正文 Text 与工具输出 Text,
     * 消息结构/角色/工具定义不动。
     */
    fun compress(messages: List<UIMessage>): List<UIMessage> {
        Log.i(TAG, "降维执行: ${messages.size} 条消息")
        if (messages.isEmpty()) return messages
        // 当前轮窗口: 最后一条 USER 消息及之后的消息不截断 (完整语义),
        // 更早的历史轮次才做有损截断 (全上下文压缩, 信息首尾保留)
        val currentWindowStart = messages.indexOfLast { it.role == me.rerere.ai.core.MessageRole.USER }
            .let { if (it >= 0) it else messages.size }
        var changed = false
        val result = messages.mapIndexed { index, msg ->
            val isCurrentWindow = index >= currentWindowStart
            var msgChanged = false
            val newParts = msg.parts.map { part ->
                when (part) {
                    // ── 工具输出: 内容级压缩 (JSON 数组 / 日志) ──
                    is UIMessagePart.Tool -> {
                        if (part.isExecuted) {
                            var toolChanged = false
                            val newOutput = part.output.map { out ->
                                if (out is UIMessagePart.Text && out.text.length >= MIN_CHARS) {
                                    val crushed = crush(out.text)
                                    if (crushed != out.text) {
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
                    // ── 消息正文: 无损紧凑 + 历史有损截断 (全上下文压缩) ──
                    is UIMessagePart.Text -> {
                        if (part.text.length >= TEXT_MIN_CHARS) {
                            val compacted = compactText(part.text)
                            val truncated = if (!isCurrentWindow) {
                                skeletonHistory(part.text, msg.role)
                            } else {
                                compacted
                            }
                            if (truncated != part.text) {
                                msgChanged = true
                                UIMessagePart.Text(truncated)
                            } else if (compacted != part.text) {
                                msgChanged = true
                                UIMessagePart.Text(compacted)
                            } else part
                        } else part
                    }
                    else -> part
                }
            }
            if (msgChanged) {
                changed = true
                msg.copy(parts = newParts)
            } else msg
        }
        val before = messages.sumOf { it.parts.filterIsInstance<UIMessagePart.Text>().sumOf { t -> t.text.length } }
        val after = result.sumOf { it.parts.filterIsInstance<UIMessagePart.Text>().sumOf { t -> t.text.length } }
        if (changed) {
            Log.i(TAG, "降维完成: ${messages.size} 条消息, 文本 ${before}c → ${after}c (省 ${before - after}c, ${if (before > 0) (100 * (before - after) / before) else 0}%), 当前轮完整")
        } else {
            Log.i(TAG, "降维执行: ${messages.size} 条消息无变化 (历史消息均为短句/单句或已在当前窗口)")
        }
        HeadroomStats.lastResult.value = if (changed) {
            "发送前: ${before} → ${after} 字符 (省 ${before - after}, ${if (before > 0) (100 * (before - after) / before) else 0}%)"
        } else {
            "发送前: ${before} 字符, 无消息可压缩 (历史均为短句)"
        }
        return result
    }

    /**
     * 历史消息骨架压缩 (v3.6.27 用户选定方案):
     * 每轮历史压成「首句 + 末句」骨架, 中段细节省略并标记。
     * 首句=问题核心/结论开头, 末句=补充/总结 — 对话脉络完整保留。
     * 确定性规则 (句子边界分割), 同输入同输出 (缓存稳定)。
     */
    private fun skeletonHistory(content: String, role: me.rerere.ai.core.MessageRole): String {
        // 幂等: 已骨架化 (带省略标记) 跳过 — 避免重复压缩破坏前缀稳定
        if (content.contains("Headroom 中段省略")) return content
        val isUser = role == me.rerere.ai.core.MessageRole.USER
        val minLen = if (isUser) SKELETON_USER_MIN else SKELETON_ASST_MIN
        if (content.length <= minLen) return content
        val headMax = if (isUser) USER_HEAD_MAX else ASST_HEAD_MAX
        val tailMax = if (isUser) USER_TAIL_MAX else ASST_TAIL_MAX

        // 句子边界: 中英文句号/问号/感叹号/换行
        val sentences = content.split(Regex("(?<=[。！？!?\n])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size < 2) return content

        val head = sentences.first().take(headMax)
        val tail = sentences.last().take(tailMax)
        if (sentences.first() == sentences.last() && sentences.size == 1) return content
        val omitted = content.length - head.length - tail.length
        if (omitted <= 0) return content
        return head + "…[Headroom 中段省略 " + omitted + " 字符，如需细节可追问]…" + tail
    }

    /**
     * 历史上下文打包压缩 (v3.6.32):
     * 把当前窗口前的所有历史消息打包成一条压缩包消息 (每轮一行紧凑格式),
     * 整体压低上下文体积 — 不是逐句骨架, 而是整体打包。
     * 确定性规则 (同输入同输出), 缓存前缀稳定。
     */
    fun packHistory(history: List<UIMessage>): UIMessage {
        val builder = StringBuilder()
        var round = 0
        history.forEach { msg ->
            when (msg.role) {
                me.rerere.ai.core.MessageRole.USER -> {
                    round++
                    builder.append(round).append(" U: ").append(extractHead(msg, USER_HEAD_MAX)).append('\n')
                }
                me.rerere.ai.core.MessageRole.ASSISTANT -> {
                    builder.append(round).append(" A: ").append(extractHeadTail(msg, ASST_HEAD_MAX, ASST_TAIL_MAX)).append('\n')
                }
                else -> {
                    // 工具轮次: 工具名 + 结果首部 (内容级压缩后取前 150 字符)
                    val tool = msg.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                    if (tool != null) {
                        val outText = tool.output.filterIsInstance<UIMessagePart.Text>()
                            .joinToString(" ") { it.text }.trim()
                        val compressed = if (outText.length >= MIN_CHARS) crush(outText) else outText
                        builder.append(round).append(" [工具 ").append(tool.toolName).append("]: ")
                            .append(compressed.take(150)).append('\n')
                    }
                }
            }
        }
        val body = builder.toString().trimEnd()
        val before = history.sumOf { it.parts.filterIsInstance<UIMessagePart.Text>().sumOf { t -> t.text.length } }
        return UIMessage(
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    "[历史包 ${history.size}条 原${before}c]\n" + body
                )
            ),
        )
    }

    /** 用户历史: 首句 (≤max) */
    private fun extractHead(msg: UIMessage, max: Int): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        val sentences = text.split(Regex("(?<=[。！？!?\n])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        return (sentences.firstOrNull() ?: text).take(max)
    }

    /** 助手历史: 首句 + 末句 (紧凑一行) */
    private fun extractHeadTail(msg: UIMessage, headMax: Int, tailMax: Int): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        val sentences = text.split(Regex("(?<=[。！？!?\n])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.size < 2) return text.take(headMax)
        val head = sentences.first().take(headMax)
        val tail = sentences.last().take(tailMax)
        return if (tail.isNotEmpty() && tail != head) "$head … $tail" else head
    }

    /**
     * 压缩单段内容 (工具输出用)。确定性规则。
     */
    fun crush(content: String): String {
        // 幂等: 已压缩内容跳过 (避免重复采样破坏前缀稳定)
        if (content.contains(MARKER)) return content

        // JSON 数组 → SmartCrusher 规则
        val element = runCatching { json.parseToJsonElement(content) }.getOrNull()
        if (element is JsonArray && element.size >= MIN_ITEMS && content.length >= MIN_CHARS) {
            val crushed = crushArray(element)
            if (crushed != null && crushed.length < content.length) {
                Log.d(TAG, "JSON 数组降维: ${content.length} → ${crushed.length} 字符 (${element.size} 项)")
                return crushed
            }
        }

        // 非 JSON 长文本: 无损紧凑 (重复行合并/空行归一)
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
                    if (items[i] != items[i - 1] && i !in keep) keep.add(i)
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

    // ── 非 JSON 无损紧凑 (日志/消息正文) ──────────────────────────

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
