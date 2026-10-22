/* 【域 L·基础设施】 — UI 基础 | 地图: docs/APP_MAP.md §L */

/* ───【上游对齐】ChatInputState.kt | v5.0.11 切换上游 3.0.0（含消息编辑改思考内容）
 * ───────────────────────────────────────────────────────────────*/
package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()

    /**
     * v5.0.72 ★「输入的消息偶发消失」根修之一：
     * `TextFieldState` 是 Compose 快照状态 —— 在 **onCleared（ViewModel 销毁，非 Compose
     * 上下文）** 里读它并不可靠，可能读到空串；旧 `onCleared` 正是拿这个空串去
     * `DraftStore.save(id, "")`，而 `save("")` 内部等于 `clear(id)` →
     * **正在输入的草稿被自己删掉**（用户感知：切走/返回后输入框空了）。
     *
     * 这里维护一份纯 Kotlin 镜像，由输入变化时同步更新；
     * 持久化层（防抖落盘 / onCleared 兜底 / 发送后清理）只读这里。
     */
    @Volatile
    var latestText: String = ""
        private set

    /** 把 Compose 侧当前文本同步到镜像（每次文本变化、onCleared 前都会调） */
    fun syncMirror() {
        latestText = textContent.text.toString()
    }
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)

    /** 编辑中的消息允许改思考内容时不为 null，见 [setContents] */
    var editingReasoning by mutableStateOf<TextFieldState?>(null)
        private set
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        latestText = ""
        messageContent = emptyList()
        editingMessage = null
        editingReasoning = null
        editingParts = null
        editingAttachmentUrls = emptySet()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
        latestText = text
    }

    fun appendText(content: String) {
        val next = textContent.text.toString() + content
        textContent.setTextAndPlaceCursorAtEnd(next)
        latestText = next
    }

    fun setContents(contents: List<UIMessagePart>) {
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        val reasoning = contents.editableReasoningOrNull()
        editingReasoning = reasoning?.let { TextFieldState(it.reasoning) }
        messageContent = contents.filter { it !is UIMessagePart.Text && (reasoning == null || it !is UIMessagePart.Reasoning) }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            if (text.isNotBlank()) {
                                merged.add(UIMessagePart.Text(text))
                            }
                        }

                        part is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                merged.add(part)
                            }
                        }

                        part is UIMessagePart.Reasoning && editingReasoning != null -> {
                            val reasoning = editingReasoning!!.text.toString()
                            if (reasoning.isNotBlank()) {
                                merged.add(part.copy(reasoning = reasoning))
                            }
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                if (editedTextIndex < 0 && text.isNotBlank()) {
                    merged.add(0, UIMessagePart.Text(text))
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isBlank() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    /**
     * 只有一段思考加正文的消息才允许改思考内容。
     * 带 metadata 的思考（Claude/Gemini 签名、OpenAI 加密内容等）回传时要和原文对得上，改了会失效或被拒，不允许编辑。
     */
    private fun List<UIMessagePart>.editableReasoningOrNull(): UIMessagePart.Reasoning? {
        if (!all { it is UIMessagePart.Text || it is UIMessagePart.Reasoning }) return null
        val reasoning = filterIsInstance<UIMessagePart.Reasoning>().singleOrNull() ?: return null
        return reasoning.takeIf { it.metadata.isNullOrEmpty() }
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
