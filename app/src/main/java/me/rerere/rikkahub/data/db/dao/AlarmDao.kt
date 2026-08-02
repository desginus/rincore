package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.AlarmEntity

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute")
    fun getAll(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarms")
    suspend fun getAllOnce(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun getById(id: String): AlarmEntity?

    @Query("SELECT * FROM alarms WHERE enabled = 1 AND nextFireAtMs IS NOT NULL")
    suspend fun getEnabledWithNextFire(): List<AlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: AlarmEntity)

    @Delete
    suspend fun delete(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE alarms SET enabled = :enabled, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAtMs: Long = System.currentTimeMillis())

    @Query("UPDATE alarms SET lastFiredAtMs = :lastFiredAt, nextFireAtMs = :nextFireAt, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun markFired(id: String, lastFiredAt: Long, nextFireAt: Long?, updatedAtMs: Long = System.currentTimeMillis())
}
