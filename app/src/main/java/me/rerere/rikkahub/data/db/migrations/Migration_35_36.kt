package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5.0.26 用户定案：记忆统一条目库的来源标记。
 *
 * source 取值：manual = 模型主动写入（记忆工具）/ user = 用户手动添加 / dream = 做梦整理。
 * 旧数据默认 manual（历史记忆均为工具或手写，无做梦条目）。
 */
val MIGRATION_35_36 = object : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'")
    }
}
