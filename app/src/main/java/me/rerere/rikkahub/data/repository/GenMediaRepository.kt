package me.rerere.rikkahub.data.repository

/* ───【原版对齐】GenMediaRepository.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
