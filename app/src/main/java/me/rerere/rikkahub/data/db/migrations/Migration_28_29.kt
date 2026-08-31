package me.rerere.rikkahub.data.db.migrations


/* ───【自研】Migration_28_29.kt — 原版无此文件
 * v3.11.29: 新增 sub_agent_runs 表 (子代理运行记录落盘)。
 * 手写迁移 (schema 28.json 未导出, AutoMigration 不可用)。
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sub_agent_runs` (
                `id` TEXT NOT NULL,
                `parent_chat_id` TEXT,
                `parent_assistant_id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `task` TEXT NOT NULL,
                `model_id` TEXT,
                `tools_json` TEXT,
                `run_in_background` INTEGER NOT NULL,
                `timeout_seconds` INTEGER NOT NULL,
                `max_trips` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `result` TEXT,
                `error` TEXT,
                `started_at_ms` INTEGER NOT NULL,
                `finished_at_ms` INTEGER,
                `tokens_in` INTEGER NOT NULL,
                `tokens_out` INTEGER NOT NULL,
                `trip_count` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sub_runs_parent_chat` ON `sub_agent_runs` (`parent_chat_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sub_runs_parent_asst` ON `sub_agent_runs` (`parent_assistant_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sub_runs_status` ON `sub_agent_runs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_sub_runs_started` ON `sub_agent_runs` (`started_at_ms`)")
    }
}
