package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * RinCore 5.0.0（反向换底）：上游 3.0.0 的新表并入我方迁移链。
 *
 * 我方链 1→32（含自研 24_25/25_27/27_28/28_29/29_30/30_31/31_32）保持不变；
 * 现有用户 v32 → v33 一步补齐：memory_file / memory_consolidation /
 * media_creation_* / translation_history + conversationentity.config 列。
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 3.0.0: 会话固定配置快照列
        db.execSQL("ALTER TABLE `conversationentity` ADD COLUMN `config` TEXT NOT NULL DEFAULT ''")
        // 3.0.0: 记忆 2.0（文件式记忆库）
        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_file` (`assistant_id` TEXT NOT NULL, `path` TEXT NOT NULL, `content` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`assistant_id`, `path`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_consolidation` (`conversation_id` TEXT NOT NULL, `consolidated_at` INTEGER NOT NULL, PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `media_creation_session` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `draft` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `media_creation_node` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `selected_record_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_media_creation_node_session_id` ON `media_creation_node` (`session_id`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `media_creation_record` (`id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `node_id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `provider_name` TEXT NOT NULL, `model_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `prompt` TEXT NOT NULL, `params` TEXT NOT NULL, `inputs` TEXT NOT NULL, `status` TEXT NOT NULL, `task_id` TEXT, `error` TEXT, `outputs` TEXT NOT NULL, `create_at` INTEGER NOT NULL, `update_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `media_creation_session`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`node_id`) REFERENCES `media_creation_node`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_media_creation_record_session_id` ON `media_creation_record` (`session_id`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_media_creation_record_node_id` ON `media_creation_record` (`node_id`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `translation_history` (`id` TEXT NOT NULL, `source_text` TEXT NOT NULL, `translated_text` TEXT NOT NULL, `target_language` TEXT NOT NULL, `model_id` TEXT NOT NULL, `create_at` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_translation_history_create_at` ON `translation_history` (`create_at`)""")
        // 已有对话视为整理过，避免升级后第一次整理把整段历史交给模型（对齐上游 Migration_28_29 spec）
        db.execSQL("INSERT OR IGNORE INTO memory_consolidation (conversation_id, consolidated_at) SELECT id, update_at FROM ConversationEntity")
    }
}
