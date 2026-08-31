package me.rerere.rikkahub.data.db.entity


/* ───【自研】SubAgentRunEntity.kt — 原版无此文件
 * v3.11.29: 子代理运行记录落盘 (Room) — 重启不丢。内存 registry 只做实时流,
 * 每次状态变更同步 upsert, 启动时恢复全部记录。
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sub_agent_runs",
    indices = [
        Index(name = "idx_sub_runs_parent_chat", value = ["parent_chat_id"]),
        Index(name = "idx_sub_runs_parent_asst", value = ["parent_assistant_id"]),
        Index(name = "idx_sub_runs_status", value = ["status"]),
        Index(name = "idx_sub_runs_started", value = ["started_at_ms"]),
    ],
)
data class SubAgentRunEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "parent_chat_id")
    val parentChatId: String?,

    @ColumnInfo(name = "parent_assistant_id")
    val parentAssistantId: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "task")
    val task: String,

    @ColumnInfo(name = "model_id")
    val modelId: String?,

    /** tools 列表 JSON 序列化 ([], null 存 null) */
    @ColumnInfo(name = "tools_json")
    val toolsJson: String?,

    @ColumnInfo(name = "run_in_background")
    val runInBackground: Boolean,

    @ColumnInfo(name = "timeout_seconds")
    val timeoutSeconds: Int,

    @ColumnInfo(name = "max_trips")
    val maxTrips: Int,

    /** SubAgentStatus.name */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "result")
    val result: String?,

    @ColumnInfo(name = "error")
    val error: String?,

    @ColumnInfo(name = "started_at_ms")
    val startedAtMs: Long,

    @ColumnInfo(name = "finished_at_ms")
    val finishedAtMs: Long?,

    @ColumnInfo(name = "tokens_in")
    val tokensIn: Long,

    @ColumnInfo(name = "tokens_out")
    val tokensOut: Long,

    @ColumnInfo(name = "trip_count")
    val tripCount: Int,

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)
