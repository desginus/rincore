package me.rerere.rikkahub.data.db.migrations


/* ───【自研】Migration_24_25.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v24 → v25: scheduled_jobs + scheduled_job_runs + agent_runs (定时任务体系) */
val Migration_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `scheduled_jobs` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `prompt` TEXT,
                `assistantId` TEXT NOT NULL,
                `scheduleType` TEXT NOT NULL,
                `atUnixMs` INTEGER,
                `intervalSeconds` INTEGER,
                `enabled` INTEGER NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `lastRunAtMs` INTEGER,
                `nextRunAtMs` INTEGER,
                `mode` TEXT NOT NULL DEFAULT 'llm',
                `actionsJson` TEXT,
                `cronExpression` TEXT,
                `timezone` TEXT,
                `startAtUnixMs` INTEGER,
                `endAtUnixMs` INTEGER,
                `maxRuns` INTEGER,
                `runsSoFar` INTEGER NOT NULL DEFAULT 0,
                `catchup` TEXT NOT NULL DEFAULT 'fire_once',
                `description` TEXT,
                `tags` TEXT,
                PRIMARY KEY(`id`)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `scheduled_job_runs` (
                `id` TEXT NOT NULL,
                `jobId` TEXT NOT NULL,
                `mode` TEXT NOT NULL,
                `scheduledAtMs` INTEGER NOT NULL,
                `startedAtMs` INTEGER NOT NULL,
                `finishedAtMs` INTEGER,
                `outcome` TEXT NOT NULL,
                `conversationId` TEXT,
                `errorMessage` TEXT,
                PRIMARY KEY(`id`)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `agent_runs` (
                `id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `domain_id` TEXT NOT NULL,
                `parent_run_id` TEXT,
                `status` TEXT NOT NULL,
                `created_at_ms` INTEGER NOT NULL,
                `updated_at_ms` INTEGER NOT NULL,
                `started_at_ms` INTEGER,
                `finished_at_ms` INTEGER,
                `last_error` TEXT,
                `metadata_json` TEXT,
                PRIMARY KEY(`id`)
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_status` ON `agent_runs` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_kind_dom` ON `agent_runs` (`kind`, `domain_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_parent` ON `agent_runs` (`parent_run_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_runs_updated_at` ON `agent_runs` (`updated_at_ms`)")
    }
}
