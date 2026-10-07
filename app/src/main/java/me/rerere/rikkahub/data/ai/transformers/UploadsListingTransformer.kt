/* 【域 B·AI 传输】 — 附件注记（v4.8.109 重写） | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/*
 * v4.8.91 初版：上传清单注入**系统提示** <uploads_in_conversation>。
 * v4.8.109 重写（用户实证 + 定版）：**缓存连续性**与**模型知晓**兼顾 —
 *
 * 问题（用户实证）：清单注入在系统提示 = 前缀首位，上传任何文件都改写系统提示
 * → 整个已缓存前缀全断（"上传那次必崩、第二次纯文本恢复"现象的准确来源）。
 *
 * 新形态：**注记锚定在附件消息自身**——
 *   ① 对每条含"位于 /upload 的文档附件"的用户消息，在其文本**末端**追加一行
 *      轻量注记「[用户上传了文档: 文件名（路径）]」；
 *   ② 上传轮请求 = 上一轮请求 + 纯追加（注记只出现在新消息里）→ 历史前缀零破坏,
 *      缓存连续（含图片上传：图片不产生任何注入, 天然连续）；
 *   ③ 历史附件消息的注记只依赖其自身 parts → 逐轮字节恒定, 永不漂移；
 *   ④ 模型知晓：新文档在消息末端有明确提示（旧系统提示清单被边缘化, 模型"无法知晓"）；
 *   ⑤ 不突出：单行方括号、无格式噪音；无文档 → 零注入。
 *
 * 注册位置保持在 DocumentAsPromptTransformer **之前**（后者会把 Document part 转文本/移除）。
 */

import android.net.Uri
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.UploadCodes
import java.io.File

object UploadsListingTransformer : InputMessageTransformer {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        var changed = false
        val out = messages.map { msg ->
            if (msg.role != MessageRole.USER) return@map msg
            val docs = msg.parts.filterIsInstance<UIMessagePart.Document>()
                .filter { UploadCodes.codeForLocation(it.url) != null }
            if (docs.isEmpty()) return@map msg
            val note = buildString {
                append("\n\n[用户上传了文档: ")
                append(docs.joinToString("、") { it.fileName })
                append("（路径: ")
                append(
                    docs.mapNotNull { d ->
                        runCatching { Uri.parse(d.url).path }.getOrNull()?.let { p ->
                            "/upload/" + File(p).name
                        }
                    }.joinToString("、")
                )
                append("）]")
            }
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
