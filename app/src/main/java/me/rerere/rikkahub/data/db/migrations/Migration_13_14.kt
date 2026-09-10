package me.rerere.rikkahub.data.db.migrations

/* ───【原版对齐】Migration_13_14.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_13_14"

val Migration_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 13 to 14 (UIMessagePart type -> @SerialName)")
        DatabaseMigrationTracker.onMigrationStart(13, 14)
        db.beginTransaction()
        try {
            val cursor = db.query("SELECT id, messages FROM message_node")
            var updatedCount = 0
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val messagesJson = cursor.getString(1)
                val migratedJson = migrateMessagesJson(messagesJson)
                if (migratedJson != messagesJson) {
                    db.execSQL(
                        "UPDATE message_node SET messages = ? WHERE id = ?",
                        arrayOf(migratedJson, id)
                    )
                    updatedCount++
                }
            }
            cursor.close()
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 13 to 14 success ($updatedCount nodes updated)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
