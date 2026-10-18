/* 【域 B·AI 传输】 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/* ───【自研】MessageImageSnapshotTransformer.kt — v4.8.185 渲染即固化
 * 用户定版 (2026-10-18): "把渲染图片改成渲染后保存的图片 —— 模型输出地址,
 * 渲染器渲染为图片, 然后将图片保存在对话中, Markdown 导出也要把图片带走"。
 *
 * 审计结论 (B193): "一批图个别错"的两层机制 —
 *   A) 消息里存的是"地址活引用", 显示时实时解析最新文件: 同名覆盖会把历史
 *      消息回灌成新图 (Coil 缓存键 = 路径+mtime+size, 覆盖后必然重解码);
 *   B) 解析器丢弃地址里的工作区 UUID, 全工作区 first-hit: 同名文件命中错工作区。
 * 根治 = 生成完成时把地址解析+复制为对话媒体快照 (ChatMediaStore), 并把消息里
 * 的地址重写为快照地址 —— 显示/导出/再发送从此全部是"渲染过就固化"的图片,
 * 覆盖回灌与错工作区从机制上消失。
 *
 * 幂等: chat-media 地址跳过; 文件不存在跳过 (不阻断生成, 下一轮自动再试)。
 * 覆盖域: 消息全部 Text parts + 工具输出内 Text (render_urls / render_markdown
 * 与正文 Markdown 走同一份字符串替换); 仅图片扩展名 (不误伤文档/脚本链接)。
 * 全量含历史消息 —— 老消息在其对话下一次生成完成时自动固化 (回填)。
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.CallTracer
import me.rerere.rikkahub.data.ai.tools.encodeMarkdownUrl
import me.rerere.rikkahub.data.files.ChatMediaStore
import me.rerere.rikkahub.utils.isAppLocalImageUri
import me.rerere.rikkahub.utils.resolveLocalImageFile

object MessageImageSnapshotTransformer : OutputMessageTransformer {

    /** 地址词法 (规范形态不含空白/引号/括号/反引号 — 编码层保证, 词边界安全)。 */
    private val LOCAL_URL_REGEX =
        Regex("(?:file:/{1,3}|workspace://)[^\\s\"'`<>\\[\\]()]+")

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = runCatching {
        // 1) 全量收集候选地址 (Text 域, 含工具输出递归)
        val urls = LinkedHashSet<String>()
        messages.forEach { m ->
            forEachText(m.parts) { t ->
                LOCAL_URL_REGEX.findAll(t).forEach { urls.add(it.value) }
            }
        }
        if (urls.isEmpty()) return@runCatching messages

        // 2) 解析 + 快照 → 映射 (同一地址全消息一次性固化, 只复制一份)
        val conversationId = ctx.conversationId?.toString()
        val replacements = LinkedHashMap<String, String>()
        withContext(Dispatchers.IO) {
            urls.forEach { url ->
                if (ChatMediaStore.isSnapshotUrl(url)) return@forEach
                if (!isAppLocalImageUri(url)) return@forEach
                val src = resolveLocalImageFile(url) ?: return@forEach
                if (src.extension.lowercase() !in IMAGE_EXTENSIONS) return@forEach
                val snap = ChatMediaStore.snapshotCopy(ctx.context, conversationId, src)
                    ?: return@forEach
                replacements[url] = encodeMarkdownUrl("file://" + snap.absolutePath)
            }
        }
        if (replacements.isEmpty()) return@runCatching messages

        // 3) 重写 (字符串级精确替换: 正文 Markdown 与工具结果 JSON 同一处理)
        CallTracer.event("SNAPSHOT", "固化", "n=${replacements.size}")
        messages.map { m -> m.copy(parts = m.parts.map { rewrite(it, replacements) }) }
    }.onFailure { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.e("MsgImageSnapshot", "snapshot failed: ${e.message}", e)
        CallTracer.event("SNAPSHOT", "failed", e.message ?: "?")
    }.getOrDefault(messages)

    private inline fun forEachText(parts: List<UIMessagePart>, action: (String) -> Unit) {
        parts.forEach { p ->
            when (p) {
                is UIMessagePart.Text -> action(p.text)
                is UIMessagePart.Tool -> p.output.forEach { op ->
                    if (op is UIMessagePart.Text) action(op.text)
                }

                else -> {}
            }
        }
    }

    private fun rewrite(part: UIMessagePart, map: Map<String, String>): UIMessagePart =
        when (part) {
            is UIMessagePart.Text -> part.copy(text = applyReplacements(part.text, map))
            is UIMessagePart.Tool -> part.copy(
                output = part.output.map { op ->
                    if (op is UIMessagePart.Text) {
                        op.copy(text = applyReplacements(op.text, map))
                    } else {
                        op
                    }
                }
            )

            else -> part
        }

    private fun applyReplacements(text: String, map: Map<String, String>): String {
        var out = text
        for ((from, to) in map) {
            if (from in out) out = out.replace(from, to)
        }
        return out
    }
}
