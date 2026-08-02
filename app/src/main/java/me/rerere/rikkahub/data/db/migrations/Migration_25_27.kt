package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v25 → v26: alarms 表 (设备闹钟) */
val Migration_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `alarms` (
                `id` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `note` TEXT,
                `scheduleType` TEXT NOT NULL,
                `time` TEXT,
                `hour` INTEGER,
                `minute` INTEGER,
                `daysOfWeek` TEXT,
                `timezone` TEXT NOT NULL,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `vibrate` INTEGER NOT NULL DEFAULT 1,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                `lastFiredAtMs` INTEGER,
                `nextFireAtMs` INTEGER,
                PRIMARY KEY(`id`)
            )"""
        )
    }
}

/** v26 → v27: workflows + workflow_runs 表 (工作流) */
val Migration_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `workflows` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `description` TEXT,
                `enabled` INTEGER NOT NULL DEFAULT 1,
                `definitionJson` TEXT NOT NULL,
                `createdAtMs` INTEGER NOT NULL,
                `updatedAtMs` INTEGER NOT NULL,
                `lastRunAtMs` INTEGER,
                `lastRunStatus` TEXT,
                `lastRunError` TEXT,
                `runsTodayCount` INTEGER NOT NULL DEFAULT 0,
                `runsTodayDate` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`)
            )"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `workflow_runs` (
                `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `workflowId` TEXT NOT NULL,
                `firedAtMs` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `errorMessage` TEXT
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workflow_runs_workflowId_firedAtMs` ON `workflow_runs` (`workflowId`, `firedAtMs`)")
    }
}
