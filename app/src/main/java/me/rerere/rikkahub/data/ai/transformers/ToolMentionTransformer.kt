/* 【域 B·AI 传输】 — @工具/工具区 语义注入 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/*
 * v4.8.90: `@工具名` 语义注入；v4.8.92 升级：同时识别**工具区**提及。
 * v4.8.112 重写（缓存连续性根治，与 v4.8.109 上传注记同款思路）：
 *
 * 旧实现把注记追加到**系统提示**（前缀首位）——用一次 `/@` 点名，那一轮请求的
 * 系统提示即被改写 → 整个已缓存前缀全断（且点名后下一轮注记又“消失”，再断一次）。
 * 新形态：**注记锚定在提及所在的那条用户消息自身** ——
 *   ① 注记 = f(该消息文本, 当前工具矩阵配置)，对每条含有效提及的用户消息逐轮
 *      重算，结果恒定（历史消息字节永不漂移）；
 *   ② 点名轮请求 = 上一轮请求 + 纯追加（注记只出现在新消息里）→ 历史前缀零破坏；
 *   ③ 无提及零注入；非法提及（普通 @ 文本）零注入。
 *
 * 提及校验（防误伤普通文本）：token 要么是"工具名形态"（ASCII 字母开头），要么能被当前
 * 设置的 ZoneRouter 解析成一个真实存在的工具区；邮箱形态（@ 前接 ASCII 词字符/点）豁免。
 */

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf

object ToolMentionTransformer : InputMessageTransformer {

    /** 工具名形态：字母开头，可含数字/下划线/连字符/点（MCP 工具名含 __） */
    private val ASCII_TOOL = Regex("@([A-Za-z][A-Za-z0-9_.-]{0,63})")

    /** 任意 @提及候选（含中文与 `/` —— 工具区路径形如 搜索/搜索引擎）；
     *  v4.8.112: 邮箱形态豁免（"a@b.com" 中的 @b 不视为提及）。 */
    private val ANY_MENTION = Regex("(?<![A-Za-z0-9.])@([^\\s@]{1,120})")

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val router = runCatching { zoneRouterOf(ctx.settings) }.getOrNull()
        var changed = false
        val out = messages.map { msg ->
            if (msg.role != MessageRole.USER) return@map msg
            val text = msg.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            if (text.isBlank() || !text.contains('@')) return@map msg
            val mentions = ANY_MENTION.findAll(text)
                .map { it.groupValues[1] }
                .distinct()
                .filter { token ->
                    ASCII_TOOL.matches("@" + token) ||
                        router?.resolveCandidates(token)?.isNotEmpty() == true
                }
                .take(8)
                .toList()
            if (mentions.isEmpty()) return@map msg

            val note = buildString {
                append("\n\n<tool-mention>")
                append("The user explicitly references the following tool(s)/tool zone(s) via `@`-mentions: ")
                mentions.forEachIndexed { i, m -> if (i > 0) append(", "); append('`').append('@').append(m).append('`') }
                append(". If it is a tool, prefer calling exactly that tool (name = the text after @; case-insensitive); ")
                append("if it is a tool zone, load it with `invoke_tools` and use its tools. ")
                append("Do not substitute a generic alternative unless the mentioned tool/zone genuinely cannot do the job.")
                append("</tool-mention>")
            }
            // v4.8.112: 追加到该消息自身的最后一个 Text part（无则补一个）——
            // 严禁进入系统提示（前缀首位 = 缓存全断；v4.8.109 上传注记同款教训）。
            val updated = msg.parts.toMutableList()
            val textIdx = updated.indexOfLast { it is UIMessagePart.Text }
            if (textIdx >= 0) {
                val t = updated[textIdx] as UIMessagePart.Text
                updated[textIdx] = t.copy(text = t.text + note)
            } else {
                updated.add(UIMessagePart.Text(note.trimStart()))
            }
            changed = true
            msg.copy(parts = updated)
        }
        return if (changed) out else messages
    }
}
