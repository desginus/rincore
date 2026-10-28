package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class CompressedContext(
    val savedMessageNodes: List<MessageNode>,  // 压缩前存档的原始消息（旧版单留存，新代码不再写入）
)

// v3.8.13: 压缩留存位点 — 最多 3 个，最新在前；留存的是压缩前的原始消息。
// 每个位点带时间戳，UI 可查看原文或从此点恢复。
@Serializable
data class CompressRetention(
    val id: Uuid = Uuid.random(),
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val retentionLabel: String = "",
    val savedMessageNodes: List<MessageNode>,
    val summaryMessageNodes: List<MessageNode> = emptyList(),
)

private fun formatCompressTimestamp(epochMs: Long): String {
    val zdt = java.time.ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault()
    )
    val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
    return "${'$'}{zdt.year}年${'$'}{zdt.monthValue}月${'$'}{zdt.dayOfMonth}日 ${'$'}{zdt.hour}时${'$'}{zdt.minute}分 ${'$'}{weekdays[zdt.dayOfWeek.value - 1]}"
}

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val compressedContext: CompressedContext? = null,  // 旧版单留存（兼容旧数据，读取后迁移）
    val compressRetentions: List<CompressRetention> = emptyList(),  // v3.8.13: 多留存位点（最多 3）
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // 会话开始后固定的配置，null 表示尚未开始、跟随助手
    val config: ConversationConfig? = null,
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 已加载的工具区（跨对话持久化，仅内存；字段名沿用历史，语义 = 工具区 id）
    // v3.6.10: Set → List 保序（加载顺序持久化 — tools 数组按加载顺序追加，前缀稳定缓存命中）
    @Transient
    val loadedDomains: List<String> = emptyList(),
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    /** v5.3.2（3.1.1 对齐）：等待用户审批的工具调用，与 isAwaitingToolApproval 判断一致 */
internal val Conversation.pendingToolCalls: List<UIMessagePart.Tool>
    get() = currentMessages.flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.isPending }
    }

fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        // v4.8.15 性能: 快路径（流式热路径）— 流式期间绝大多数 node 的当前消息
        // 引用未变，仅尾部 1 条在动；逐条同一性检查可跳过全部 node 重建。
        run {
            var same = this.messageNodes.size == messages.size
            if (same) {
                for (i in messages.indices) {
                    if (this.messageNodes[i].currentMessage !== messages[i]) {
                        same = false
                        break
                    }
                }
            }
            if (same) return this
        }
        val newNodes = this.messageNodes.toMutableList()
        var anyChanged = false
        // v5.1.21 ★「新一轮回复被塞进上一个气泡」的结构性根修：
        // 下方 `else { newMessages.add(message) }` 的语义是「同一 node 的新分支（重新生成）」。
        // 一旦 messages 与 messageNodes 的 index 发生错位（例如流式 chunk 与内存态在
        // 队列派发窗口内不同步），user/assistant 会被追加进**上一个轮次的气泡**里，
        // 且 selectIndex 指向新分支 → 用户看到的就是「模型在先前回复的结果下面继续思考」。
        // 一个 node 的分支只可能是**同一轮的不同版本**（role 必然相同）；
        // role 不同 = 一定是新的一轮 → 必须独立成 node，绝不能当分支追加。
        val appendAsNewNodes = ArrayList<me.rerere.rikkahub.data.model.MessageNode>()

        messages.forEachIndexed { index, message ->
            // v4.8.19 性能: 逐 node 引用短路 — currentMessage 引用相同则该 node 完全无需重建
            val existing = if (index <= newNodes.lastIndex) newNodes[index] else null
            if (existing != null && existing.currentMessage === message) {
                return@forEachIndexed
            }
            anyChanged = true

            val node = existing ?: message.toMessageNode()

            // v5.1.21：跨 role 绝不追加为分支（见上方说明）——独立成 node 追加到末尾。
            val nodeRole = node.messages.firstOrNull()?.role
            if (existing != null && nodeRole != null && nodeRole != message.role) {
                appendAsNewNodes.add(message.toMessageNode())
                return@forEachIndexed
            }

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

        if (!anyChanged) return this

        // v5.1.21：跨 role 的新轮次节点统一追加到末尾（保持时间顺序）
        if (appendAsNewNodes.isNotEmpty()) newNodes.addAll(appendAsNewNodes)

        return this.copy(
            messageNodes = newNodes
        )
    }

    // v3.8.13: 压缩留存重做 — 由单次撤销改为多留存位点（最多 3）管理

    /** 压缩存档：记录压缩前的原始消息与压缩摘要，最多保留 3 个位点，超出覆盖最旧 */
    fun addCompressRetention(
        savedNodes: List<MessageNode>,
        summaryNodes: List<MessageNode> = emptyList(),
    ): Conversation {
        val retention = CompressRetention(
            savedAtEpochMs = System.currentTimeMillis(),
            retentionLabel = formatCompressTimestamp(System.currentTimeMillis()),
            savedMessageNodes = savedNodes,
            summaryMessageNodes = summaryNodes,
        )
        return copy(
            compressRetentions = (listOf(retention) + compressRetentions).take(3),
            compressedContext = null,
        )
    }

    /** 从留存位点恢复：恢复该位点保存的原始消息，该位点之后（更新）的压缩位点一并撤销 */
    fun restoreCompressAt(index: Int): Conversation {
        val retention = compressRetentions.getOrNull(index) ?: return this
        return copy(
            messageNodes = retention.savedMessageNodes,
            compressRetentions = compressRetentions.subList(index + 1, compressRetentions.size),
            compressedContext = null,
        )
    }

    /** 旧版单留存上下文迁移为留存位点（读取到旧数据时惰性执行一次） */
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

    /**
     * 写入一条消息：[nodeId] 节点里已有同 id 的消息就原地替换（不改变选中的分支），否则作为新分支加入并选中；
     * 节点不存在时追加到末尾。按节点 id 而不是下标定位，生成期间别的节点被增删、分支被切换，回复也不会写错位置。
     */
    fun upsertMessage(nodeId: Uuid, message: UIMessage): Conversation {
        // 生成中的回复几乎总在最后一个节点
        val index = messageNodes.indexOfLast { it.id == nodeId }
        if (index == -1) {
            return copy(messageNodes = messageNodes + MessageNode(id = nodeId, messages = listOf(message)))
        }
        val node = messageNodes[index]
        val existing = node.messages.indexOfFirst { it.id == message.id }
        val updated = if (existing == -1) {
            node.copy(messages = node.messages + message, selectIndex = node.messages.size)
        } else {
            node.copy(messages = node.messages.toMutableList().also { it[existing] = message })
        }
        return copy(messageNodes = messageNodes.toMutableList().also { it[index] = updated })
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

/**
 * 最近一次回复结束时的上下文占用（token），取不到时返回 null。
 * 之后又插入了压缩检查点的话，记录的占用已经过时，同样返回 null。
 */
fun Conversation.lastContextTokens(): Int? {
    val messages = currentMessages
    val message = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return null
    val checkpoint = messages.lastOrNull { it.isContextCheckpoint }
    if (checkpoint != null && checkpoint.createdAt > message.createdAt) return null
    // 旧消息没有 contextTokens，那时的 usage 只保留最后一步，可以直接用
    return message.contextTokens ?: message.usage?.let { it.promptTokens + it.completionTokens }
}

/** 在 [afterNodeId] 节点之后插入压缩检查点；该节点已不存在时返回 null。 */
internal fun Conversation.insertContextCheckpoint(afterNodeId: Uuid, summary: String): Conversation? {
    val index = messageNodes.indexOfFirst { it.id == afterNodeId }
    if (index == -1) return null
    val checkpoint = UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode()
    return copy(
        messageNodes = messageNodes.subList(0, index + 1) + checkpoint + messageNodes.subList(index + 1, messageNodes.size),
    )
}

/** 有工具调用在等待用户审批：这一轮还没结束，排队的消息要等审批完成后再发 */
internal val Conversation.isAwaitingToolApproval: Boolean
    get() = currentMessages.any { message -> message.getTools().any { it.isPending } }

/**
 * 去掉还有未执行、又没法继续执行的工具调用的消息（比如还在等待审批），这样的消息发给模型会被拒绝。
 * 已经批准、拒绝或回答过的工具调用之后会继续执行，保留。只处理下标小于 [untilNode] 的节点。
 */
internal fun Conversation.withoutUnresumableToolCalls(untilNode: Int = messageNodes.size): Conversation {
    val nodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index >= untilNode) return@mapIndexedNotNull node
        val unexecuted = node.currentMessage.getTools().filter { !it.isExecuted }
        if (unexecuted.isEmpty() || unexecuted.any { it.canResumeExecution }) return@mapIndexedNotNull node
        val messages = node.messages.filter { it.id != node.currentMessage.id }
        if (messages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = messages, selectIndex = (node.selectIndex - 1).coerceIn(messages.indices))
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 给还没有模型快照的消息补上快照，没有可补的消息时原样返回。
 *
 * 在保存会话时调用：新生成的消息由此带上快照，引入快照之前的旧消息也在会话下次保存时补齐。
 * 模型已经被删除的旧消息无从补起，保持原样。
 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

/** 替换本地附件的地址，覆盖范围与 [localFileUrls] 一致，包含工具结果中的嵌套附件。 */
internal fun UIMessagePart.mapLocalFileUrls(transform: (String) -> String): UIMessagePart {
    fun String.mapped() = if (startsWith("file://")) transform(this) else this
    return when (this) {
        is UIMessagePart.Image -> copy(url = url.mapped())
        is UIMessagePart.Document -> copy(url = url.mapped())
        is UIMessagePart.Video -> copy(url = url.mapped())
        is UIMessagePart.Audio -> copy(url = url.mapped())
        is UIMessagePart.Tool -> copy(output = output.map { it.mapLocalFileUrls(transform) })
        else -> this
    }
}
