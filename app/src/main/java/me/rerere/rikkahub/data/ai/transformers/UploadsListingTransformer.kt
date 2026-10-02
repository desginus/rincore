/* 【域 B·AI 传输】 — 本对话上传清单 | 地图: docs/APP_MAP.md §B */
package me.rerere.rikkahub.data.ai.transformers

/*
 * v4.8.91: "本对话上传的文件"清单注入 —— 引导模型精确拿文件的核心件（用户定版思路）。
 *
 * 不要让模型慢慢翻 /upload：每轮请求直接把当前上下文里出现过的上传文件按**先后**
 * 列给模型 —— 它能直接按路径读取；也能拿文件名前缀里的时间码让 upload_fetch 直取
 * （跨对话/历史文件同理）。这就是"上传区分区：看全部 / 只看本对话"的模型侧实现：
 * 本对话清单常驻本轮上下文，其余文件随时可按码/近列表反查。
 *
 * 无附件 → 零注入（不污染提示词缓存前缀）。
 * 扫描发生在 DocumentAsPromptTransformer **之前**（后者会把 Document part 转文本/移除）。
 */

import android.net.Uri
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.UploadCodes

object UploadsListingTransformer : InputMessageTransformer {
    private const val MAX_LISTED = 40

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val names = LinkedHashSet<String>()   // 按出现先后去重
        messages.forEach { msg ->
            msg.parts.forEach partLoop@{ part ->
                val url = when (part) {
                    is UIMessagePart.Document -> part.url
                    is UIMessagePart.Image -> part.url
                    is UIMessagePart.Audio -> part.url
                    is UIMessagePart.Video -> part.url
                    else -> null
                } ?: return@partLoop
                // 只统计真正位于 filesDir/upload 下的文件（附件/工具图等一律排除）
                if (UploadCodes.codeForLocation(url) == null) return@partLoop
                val name = runCatching { Uri.parse(url).path?.substringAfterLast('/') }.getOrNull()
                if (!name.isNullOrBlank()) names.add(name)
            }
        }
        if (names.isEmpty()) return messages

        val list = names.toList()
        val shown = if (list.size <= MAX_LISTED) list else list.takeLast(MAX_LISTED)
        val note = buildString {
            append("\n\n<uploads_in_conversation>\n")
            append("Files uploaded in THIS conversation, in chronological order")
            if (shown.size < list.size) append(" (last ${shown.size} of ${list.size})")
            append(". Read them directly by these exact paths — do NOT browse /upload. ")
            append("The filename prefix is the upload code (12-digit time code for new uploads); ")
            append("for files from other conversations, resolve them with `upload_fetch` by code.\n")
            shown.forEach { append("- /upload/").append(it).append('\n') }
            append("</uploads_in_conversation>")
        }

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendUploadsNote(note)
            }
        } else {
            listOf(UIMessage.system(note.trim())) + messages
        }
    }
}

/** 把注记追加到消息的第一个文本 part（没有则补一个） */
private fun UIMessage.appendUploadsNote(extra: String): UIMessage {
    val updated = parts.toMutableList()
    val firstTextIndex = updated.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updated[firstTextIndex] as UIMessagePart.Text
        updated[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updated.add(0, UIMessagePart.Text(extra))
    }
    return copy(parts = updated)
}
