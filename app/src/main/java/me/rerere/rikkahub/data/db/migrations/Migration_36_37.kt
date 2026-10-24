package me.rerere.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_36_37"

/**
 * v5.0.85（用户 B1/B3）：
 *  1. `invalidated_at` —— 记忆失效标记（软删）。模型侧废弃的旧口径不再注入上下文，
 *     但记录留痕（可审计），解决"旧结论永久改不动、还每次会话都出现"。
 *  2. `updated_at` —— 记忆最后更新时间戳（环境类结论随时间失效时用户可据此判断新旧）。
 */
val MIGRATION_36_37 = object : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 36 to 37 (memory invalidated_at/updated_at)")
        DatabaseMigrationTracker.onMigrationStart(36, 37)
        try {
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN invalidated_at INTEGER")
            db.execSQL("ALTER TABLE memoryentity ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            Log.i(TAG, "migrate: migrate from 36 to 37 success")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
