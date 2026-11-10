/* 【域 I·数据存储】 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.db.fts

/* ───【原版对齐】MessageFtsManager.kt | 差异 ±2 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    // v5.3.69：标题/时间改从 conversationentity 实时读，不再依赖 FTS 行里的旧副本
    RELEVANCE("f.rank, c.update_at DESC"),
    NEWEST_FIRST("c.update_at DESC, f.rank"),
    OLDEST_FIRST("c.update_at ASC, f.rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    /**
     * ★v5.3.69：只重建**变化的节点**（流式落库每秒都在写，整表重建 FTS 是卡顿主因之一）。
     * 语义与 [indexConversation] 对同一批节点完全一致：先删该节点的旧行，再按当前内容插入。
     */
    suspend fun indexNodes(conversation: Conversation, nodes: List<MessageNode>) = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) return@withContext
        val conversationId = conversation.id.toString()
        val nodeIds = nodes.map { it.id.toString() }
        db.execSQL(
            "DELETE FROM message_fts WHERE node_id IN (${nodeIds.joinToString(",") { "?" }})",
            nodeIds.toTypedArray(),
        )
        nodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    /** ★v5.3.69：增量落库用 —— 只删除已消失节点的索引。 */
    suspend fun deleteNodes(conversationId: String, nodeIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (nodeIds.isEmpty()) return@withContext
        val ids = nodeIds.toList()
        db.execSQL(
            "DELETE FROM message_fts WHERE conversation_id = ? AND node_id IN (${ids.joinToString(",") { "?" }})",
            arrayOf<Any?>(conversationId, *ids.toTypedArray()),
        )
    }


    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        // 只搜这些助手的对话，null 表示不限
        assistantIds: Collection<String>? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        if (assistantIds != null && assistantIds.isEmpty()) return@withContext results
        val assistantFilter = if (assistantIds != null) {
            "AND c.assistant_id IN (${assistantIds.joinToString { "?" }})"
        } else {
            ""
        }
        val cursor = db.query(
            """
            SELECT f.node_id, f.message_id, f.conversation_id, c.title, c.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts AS f
            JOIN conversationentity AS c ON c.id = f.conversation_id
            WHERE f.text MATCH jieba_query(?)
            $assistantFilter
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword, *assistantIds.orEmpty().toTypedArray())
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
