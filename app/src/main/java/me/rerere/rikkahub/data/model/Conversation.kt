package me.rerere.rikkahub.data.model


/* ───【原版对齐】Conversation.kt | 差异 ±33 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class CompressedContext(
    val savedMessageNodes: List<MessageNode>,  // 压缩前存档的原始消息 (旧版单留存, 新代码不再写入)
)

// v3.8.13: 压缩留存位点 — 最多 3 个, 最新在前。留存的是压缩前的原始消息。
// 每个位点带时间戳 (年/月/日 时:分 星期几), UI 可查看原文或从此点恢复。
@Serializable
data class CompressRetention(
    val id: Uuid = Uuid.random(),
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val retentionLabel: String = "",  // 预格式化时间戳, 如 "2026年8月19日 19时30分 星期三"
    val savedMessageNodes: List<MessageNode>,
)

private fun formatCompressTimestamp(epochMs: Long): String {
    val zdt = java.time.ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()
    )
    val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    return "${zdt.year}年${zdt.monthValue}月${zdt.dayOfMonth}日 ${zdt.hour}时${zdt.minute}分 ${weekdays[zdt.dayOfWeek.value - 1]}"
}

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val compressedContext: CompressedContext? = null,  // 旧版单留存 (兼容旧数据, 读取后迁移)
    val compressRetentions: List<CompressRetention> = emptyList(),  // v3.8.13: 多留存位点 (最多 3)
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 已加载的工具域（用于跨对话持久化，仅在内存中）
    // v3.6.10: Set → List 保序 (加载顺序持久化 — tools 数组按加载顺序追加,
    // 前缀稳定缓存命中; Set 迭代顺序不定曾致跨轮前缀断裂)
    @Transient
    val loadedDomains: List<String> = emptyList(),
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    // v3.8.13: 压缩留存重做 — 由单次撤销改为多留存位点 (最多 3) 管理

    /** 压缩存档: 记录压缩前的原始消息, 最多保留 3 个位点, 超出覆盖最旧 */
    fun addCompressRetention(savedNodes: List<MessageNode>): Conversation {
        val retention = CompressRetention(
            savedAtEpochMs = System.currentTimeMillis(),
            retentionLabel = formatCompressTimestamp(System.currentTimeMillis()),
            savedMessageNodes = savedNodes,
        )
        return copy(
            compressRetentions = (listOf(retention) + compressRetentions).take(3),
            compressedContext = null,
        )
    }

    /** 从留存位点恢复: 恢复该位点保存的原始消息, 该位点之后 (更新) 的
     * 压缩位点一并撤销 (基于旧状态的后续压缩失效); 更早的位点保留 */
    fun restoreCompressAt(index: Int): Conversation {
        val retention = compressRetentions.getOrNull(index) ?: return this
        return copy(
            messageNodes = retention.savedMessageNodes,
            compressRetentions = compressRetentions.subList(index + 1, compressRetentions.size),
            compressedContext = null,
        )
    }

    /** 旧版单留存上下文迁移为留存位点 (读取到旧数据时惰性执行一次) */
    fun migrateLegacyCompress(): Conversation {
        val legacy = compressedContext ?: return this
        return copy(
            compressRetentions = listOf(
                CompressRetention(
                    savedAtEpochMs = System.currentTimeMillis(),
                    retentionLabel = formatCompressTimestamp(System.currentTimeMillis()),
                    savedMessageNodes = legacy.savedMessageNodes,
                )
            ),
            compressedContext = null,
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false,
            folderId: Uuid? = null,
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
            folderId = folderId,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
