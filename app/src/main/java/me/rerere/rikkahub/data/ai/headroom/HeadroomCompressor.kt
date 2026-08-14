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
    // v3.6.42: lastResult 与 lastDetail 信息重叠, 已合并 (状态/统计统一进 lastDetail)
    // v3.6.37: 上轮实际发送请求体的文本字符数 (验证压缩是否真正进入请求)
    val lastRequestChars = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    // v3.6.38: 上轮压缩节省估算 token (字符差 / 2 — 消息统计行直接显示)
    val lastSavedTokens = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    // v3.6.41: 压缩详情 (证明压缩发生 + 信息保留) — 原始/总结字符 + 总结预览
    val lastDetail = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/**
 * 会话级压缩状态 (v3.6.43 缓存修复):
 * 追加式增量压缩包 — 压缩包前缀稳定增长, 缓存可包含压缩包。
 * 此前每次请求整体重新总结 → 前缀全变 → 缓存断在 system+tools (13.8K)。
 */
object HeadroomCache {
    class State(val packedText: String, val packedUntil: Int)
    private val states = HashMap<String, State>()

    fun get(key: String): State? = states[key]
    fun put(key: String, state: State) {
        states[key] = state
        if (states.size > 32) {
            // 简易清理: 保留最近 16 个 (避免长期会话累积)
            val oldest = states.keys.take(states.size - 16)
            oldest.forEach { states.remove(it) }
        }
    }
}

object HeadroomCompressor {
    private const val TAG = "Headroom"
    // v3.6.43: 增量追加上限 (消息数, 约 8 轮) 与压缩包增长阈值
    const val DELTA_MAX_MSGS = 24
    const val MAX_PACKED_CHARS = 8000

    private val json = Json { prettyPrint = false }

    fun summarizeHistory(history: List<UIMessage>): UIMessage {
        // v3.6.58: 根因修复 — 之前每行截断到固定字符 (120/350), 对超长消息压缩率
        // 只有 0.6% (350/60000), 压缩包太小缓存永远上不去。改为:
        // 1. 每轮完整内容 (不截断单条消息, 凡有必存)
        // 2. 整体长度控制: 保留 40% (首尾各 20%, 中段省略)
        // 压缩包 = rawLen 的 40%, 缓存命中随压缩包成比例增长。
        val rawLen = history.sumOf { msg ->
            msg.parts.sumOf { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.length
                    is UIMessagePart.Tool -> part.output.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                    else -> 0
                }
            }
        }

        val builder = StringBuilder()
        history.forEach { msg ->
            when (msg.role) {
                me.rerere.ai.core.MessageRole.USER -> {
                    val t = msg.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString(" ") { it.text }.trim()
                    if (t.isNotBlank()) builder.append("U: ").append(t).append('\n')
                }
                me.rerere.ai.core.MessageRole.ASSISTANT -> {
                    val t = msg.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString(" ") { it.text }.trim()
                    if (t.isNotBlank()) builder.append("A: ").append(t).append('\n')
                }
                else -> {
                    val tool = msg.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                    if (tool != null) {
                        val t = tool.output.filterIsInstance<UIMessagePart.Text>()
                            .joinToString(" ") { it.text }.trim()
                        if (t.isNotBlank()) {
                            builder.append("[工具 ").append(tool.toolName).append("]: ")
                                .append(t).append('\n')
                        }
                    }
                }
            }
        }
        var summary = builder.toString().trimEnd()

        // 整体长度控制: 保留 40% (首尾各 20%, 中段省略)
        val target = maxOf(rawLen * 40 / 100, 200)
        if (summary.length > target) {
            val half = target / 2
            summary = summary.take(half) + "\n…[中段省略]…\n" + summary.takeLast(half)
        }

        val ratio = if (rawLen > 0) (100 - 100 * summary.length / rawLen) else 0
        me.rerere.rikkahub.data.ai.headroom.HeadroomStats.lastDetail.value =
            "历史 ${rawLen} 字符 → 总结 ${summary.length} 字符 (压缩 ${ratio}%)\n总结预览: " + summary.take(160)

        return UIMessage(
            role = me.rerere.ai.core.MessageRole.USER,
            parts = listOf(UIMessagePart.Text(summary)),
        )
    }

    /**
     * 单轮增量行 (v3.6.43): 追加到压缩包尾部, 前缀稳定 → 缓存可命中。
     * 每轮固定 3 行: 用户首句 / 助手末句 / 工具名+结果首部。
     */
    fun summarizeDelta(msg: UIMessage): String {
        // v3.6.55: 凡有必存 — 每轮增量一行, 与完整总结同格式 (U:/A:/工具 标签),
        // 使增量追加前缀与重新总结字节一致, 缓存逐 token 稳定累积。
        return when (msg.role) {
            me.rerere.ai.core.MessageRole.USER -> "U: " + extractHead(msg, 350)
            me.rerere.ai.core.MessageRole.ASSISTANT -> "A: " + extractTail(msg, 350)
            else -> {
                val tool = msg.parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull()
                if (tool != null) {
                    val outText = tool.output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString(" ") { it.text }.trim()
                    "[工具 ${tool.toolName}]: " + outText.take(350)
                } else {
                    ""
                }
            }
        }
    }

    /** 用户/助手: 前 max 字符 (v3.6.57 修复: 之前取'首句'导致首句很短时压缩包只有几十字符,
     *  增大 max 完全无效 → 缓存始终 16K。改为取前 max 字符, 凡有必存) */
    private fun extractHead(msg: UIMessage, max: Int): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        return text.take(max)
    }

    /** 助手: 后 max 字符 (同上, 之前取'末句'有同样问题) */
    private fun extractTail(msg: UIMessage, max: Int): String {
        val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        return text.takeLast(max)
    }
}