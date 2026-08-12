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
    // v3.6.38: 上轮压缩节省估算 token (字符差 / 2 — 消息统计行直接显示)
    val lastSavedTokens = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    // v3.6.41: 压缩详情 (证明压缩发生 + 信息保留) — 原始/总结字符 + 总结预览
    val lastDetail = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

object HeadroomCompressor {
    private const val TAG = "Headroom"

    private val json = Json { prettyPrint = false }

    /**
     * 压缩消息列表。只作用于消息正文 Text 与工具输出 Text,
     * 消息结构/角色/工具定义不动。
    fun summarizeHistory(history: List<UIMessage>): UIMessage {
        // ── 提取阶段 ──
        val userFocus = LinkedHashSet<String>()   // 用户关注点 (每轮 USER 首句)
        val conclusions = mutableListOf<String>()  // 助手结论 (每轮 ASSISTANT 末句)
        val toolUsage = LinkedHashMap<String, Int>() // 工具名 → 次数
        var roundCount = 0
        var lastUser = ""
        history.forEach { msg ->
            when (msg.role) {
                me.rerere.ai.core.MessageRole.USER -> {
                    val head = extractHead(msg, 80)
                    if (head.isNotBlank()) {
                        userFocus.add(head)
                        lastUser = head
                        roundCount++
                    }
                }
                me.rerere.ai.core.MessageRole.ASSISTANT -> {
                    val tail = extractTail(msg, 100)
                    if (tail.isNotBlank()) conclusions.add(tail)
                }
                else -> {
                    val tool = msg.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                    if (tool != null) {
                        toolUsage[tool.toolName] = (toolUsage[tool.toolName] ?: 0) + 1
                    }
                }
            }
        }

        // ── 组装阶段 (一段连贯总结) ──
        val builder = StringBuilder()
        builder.append("[上下文总结] 共 ").append(roundCount).append(" 轮。")
        if (userFocus.isNotEmpty()) {
            builder.append("用户关注: ").append(userFocus.take(6).joinToString("；")).append("。")
        }
        if (conclusions.isNotEmpty()) {
            // 去重: 重复结论合并 (多轮相同末句)
            builder.append("助手结论: ").append(conclusions.distinct().take(8).joinToString("；")).append("。")
        }
        if (toolUsage.isNotEmpty()) {
            builder.append("涉及工具: ")
                .append(toolUsage.entries.take(6).joinToString("、") { "${it.key}×${it.value}" })
                .append("。")
        }

        // ── 长度控制 (v3.6.41 修正): 压缩率上限 65%, 信息保留率 ≥35% ──
        // 用户明确: 不是压得越狠越好, 要保留足够信息。压缩 ≤65% 即保留 ≥35%。
        val rawLen = history.sumOf { msg ->
            msg.parts.sumOf { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.length
                    is UIMessagePart.Tool -> part.output.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                    else -> 0
                }
            }
        }
        var summary = builder.toString().trimEnd()
        val minKeep = rawLen * 35 / 100   // 至少保留 35% (压缩 ≤65%)
        val maxKeep = rawLen * 50 / 100   // 最多保留 50% (压缩 ≥50%, 信息密度优先)
        val budget = maxOf(minKeep, 200)
        if (summary.length < budget && summary.length < maxKeep) {
            // 总结内容不足预算: 补充最近一轮的完整内容 (信息保留)
            val lastRound = history.takeLastWhile { it.role != me.rerere.ai.core.MessageRole.USER }
                .let { tail ->
                    val userIdx = history.lastIndex - tail.size
                    if (userIdx >= 0) history[userIdx] else null
                }
            val lastText = lastRound?.parts?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString(" ") { it.text }?.trim().orEmpty()
            if (lastText.isNotBlank() && summary.length + lastText.length <= budget) {
                summary += "\n最近一轮: $lastText"
            } else if (lastText.isNotBlank()) {
                val room = budget - summary.length
                if (room > 50) summary += "\n最近一轮: " + lastText.take(room)
            }
        }
        if (summary.length > maxKeep) {
            // 超上限: 截断 (保留结尾 — 最近信息)
            summary = summary.take(maxKeep) + "…"
        }

        // 压缩详情 (v3.6.41): 证明压缩发生 + 信息保留
        val ratio = if (rawLen > 0) (100 - 100 * summary.length / rawLen) else 0
        me.rerere.rikkahub.data.ai.headroom.HeadroomStats.lastDetail.value =
            "历史 ${rawLen} 字符 → 总结 ${summary.length} 字符 (压缩 ${ratio}%, 保留 ${100 - ratio}%)\n" +
            "总结预览: " + summary.take(160)

        return UIMessage(
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text(summary)),
        )
    }

    /** 用户/助手: 首句 (≤max) */
    private fun extractHead(msg: UIMessage, max: Int): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        val sentences = text.split(Regex("(?<=[。！？!?\n])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        return (sentences.firstOrNull() ?: text).take(max)
    }

    /** 助手: 末句 (结论, ≤max) */
    private fun extractTail(msg: UIMessage, max: Int): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        val sentences = text.split(Regex("(?<=[。！？!?\n])"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        return (sentences.lastOrNull() ?: text).take(max)
    }

    /**
     * 压缩单段内容 (工具输出用)。确定性规则。
     */