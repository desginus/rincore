package me.rerere.rikkahub.data.db.dao


/* ───【自研】SubAgentRunDao.kt — 原版无此文件
 * v3.11.29: 子代理运行记录持久化 DAO。
 * ───────────────────────────────────────────────────────────────*/
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.SubAgentRunEntity

@Dao
interface SubAgentRunDao {

    @Upsert
    suspend fun upsert(entity: SubAgentRunEntity)

    @Query("SELECT * FROM sub_agent_runs ORDER BY started_at_ms DESC")
    suspend fun getAll(): List<SubAgentRunEntity>

    @Query("SELECT * FROM sub_agent_runs WHERE parent_chat_id = :parentChatId ORDER BY started_at_ms DESC")
    suspend fun getByParentChatId(parentChatId: String): List<SubAgentRunEntity>

    @Query("SELECT * FROM sub_agent_runs WHERE id = :id")
    suspend fun getById(id: String): SubAgentRunEntity?

    @Query("DELETE FROM sub_agent_runs WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 保留最近的最近 [keep] 条, 删除更早的终态记录 (运行中不删)。
     */
    @Query(
        "DELETE FROM sub_agent_runs WHERE id IN (" +
            "SELECT id FROM sub_agent_runs " +
            "WHERE status IN ('SUCCEEDED','FAILED','TIMED_OUT','CANCELLED') " +
            "ORDER BY started_at_ms DESC LIMIT -1 OFFSET :keep)"
    )
    suspend fun trimTerminalKeep(keep: Int)
}
