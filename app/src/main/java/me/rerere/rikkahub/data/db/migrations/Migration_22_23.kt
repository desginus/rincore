package me.rerere.rikkahub.data.db.migrations

/* ───【原版对齐】Migration_22_23.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec

@DeleteColumn(tableName = "workspaces", columnName = "shell_enabled")
class Migration_22_23 : AutoMigrationSpec
