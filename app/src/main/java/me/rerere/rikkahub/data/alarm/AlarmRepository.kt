package me.rerere.rikkahub.data.alarm


/* ───【自研】AlarmRepository.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.AlarmDao
import me.rerere.rikkahub.data.db.entity.AlarmEntity

class AlarmRepository(private val alarmDao: AlarmDao) {
    fun getAll(): Flow<List<AlarmEntity>> = alarmDao.getAll()

    suspend fun getAllOnce(): List<AlarmEntity> = alarmDao.getAllOnce()

    suspend fun getById(id: String): AlarmEntity? = alarmDao.getById(id)

    suspend fun upsert(alarm: AlarmEntity) = alarmDao.upsert(alarm)

    suspend fun deleteById(id: String) = alarmDao.deleteById(id)

    suspend fun getEnabledWithNextFire(): List<AlarmEntity> = alarmDao.getEnabledWithNextFire()

    suspend fun setEnabled(id: String, enabled: Boolean) {
        alarmDao.setEnabled(id, enabled)
    }

    suspend fun markFired(id: String, lastFiredAt: Long, nextFireAt: Long?) {
        alarmDao.markFired(id, lastFiredAt, nextFireAt)
    }
}
