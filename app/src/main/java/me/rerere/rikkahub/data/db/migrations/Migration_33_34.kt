package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_33_34"

/**
 * v5.0.22：并入上游 3.1.0 的用量流水表（上游为 Migration_30_31）。
 *
 * 我方迁移链独立演进到 v33，故这里只取该批的净效果：
 *  1. 新建 usage_record 表（含 created_at 索引）；
 *  2. 用现有 message_node 里的 messages JSON 回填历史用量（统计不断档）。
 *
 * 回填口径与上游一致：每条 assistant 消息记一行，所有分支都算；
 * createdAt 是本地时间 ISO 串，按 UTC 换算毫秒，解析失败用会话创建时间。
 * 表名 ConversationEntity 与 conversationentity 在 SQLite 中大小写不敏感。
 */
val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 33 to 34 (usage records)")
        DatabaseMigrationTracker.onMigrationStart(33, 34)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `usage_record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `source` TEXT NOT NULL, `provider_id` TEXT, `model_id` TEXT, " +
                    "`model_name` TEXT, `assistant_id` TEXT, `conversation_id` TEXT, " +
                    "`prompt_tokens` INTEGER NOT NULL, `completion_tokens` INTEGER NOT NULL, " +
                    "`cached_tokens` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` ON `usage_record` (`created_at`)")

            db.execSQL(
                """
                INSERT INTO usage_record (
                    created_at, source, provider_id, model_id, model_name, assistant_id, conversation_id,
                    prompt_tokens, completion_tokens, cached_tokens
                )
                SELECT
                    COALESCE(
                        CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000,
                        c.create_at
                    ),
                    'CHAT',
                    NULL,
                    json_extract(j.value, '$.modelId'),
                    json_extract(j.value, '$.modelSnapshot.modelId'),
                    c.assistant_id,
                    mn.conversation_id,
                    COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0),
                    COALESCE(json_extract(j.value, '$.usage.cachedTokens'), 0)
                FROM message_node mn
                JOIN ConversationEntity c ON c.id = mn.conversation_id,
                    json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) j
                WHERE json_extract(j.value, '$.role') = 'assistant'
                    AND json_type(j.value, '$.usage') = 'object'
                    AND (
                        COALESCE(json_extract(j.value, '$.usage.promptTokens'), 0) > 0
                        OR COALESCE(json_extract(j.value, '$.usage.completionTokens'), 0) > 0
                    )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 33 to 34 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
