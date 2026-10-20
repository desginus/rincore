package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v5.0.24 用户定案：记忆三范围（单对话 / 项目包 / 助手）。
 *
 * 仅新增 folder_id 列（可空）：
 *  - NULL 且 conversation_id 为 NULL = 助手级记忆（旧行为，零迁移风险）
 *  - 非 NULL = 项目包记忆（该包内所有对话可见）
 *  - conversation_id 非 NULL = 单对话记忆（优先级最高，仍然只在所属对话可见）
 */
val MIGRATION_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memoryentity ADD COLUMN folder_id TEXT")
    }
}
