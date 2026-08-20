package me.rerere.rikkahub.data.db.migrations


/* ───【自研】Migration_27_28.kt — 压缩留存位点持久化
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v27 → v28: conversationentity 增加 compress_retentions 列 (压缩留存位点 JSON) */
val Migration_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `conversationentity` ADD COLUMN `compress_retentions` TEXT NOT NULL DEFAULT ''"
        )
    }
}
