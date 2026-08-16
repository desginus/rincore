package me.rerere.rikkahub.data.db.entity


/* ───【自研】AlarmEntity.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey
    val id: String,                    // UUID
    val label: String,                 // 闹钟标题
    val note: String? = null,          // 备注
    val scheduleType: String,          // "once" | "weekly"
    val time: String? = null,          // ISO-8601 for "once"
    val hour: Int? = null,             // 小时 (0-23) for "weekly"
    val minute: Int? = null,           // 分钟 (0-59) for "weekly"
    val daysOfWeek: String? = null,    // 逗号分隔 "1,3,5" (1=周一, 7=周日)
    val timezone: String = java.time.ZoneId.systemDefault().id,
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val lastFiredAtMs: Long? = null,
    val nextFireAtMs: Long? = null,
)
