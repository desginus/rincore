package me.rerere.rikkahub.data.model

/* ───【原版对齐】Folder.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 会话文件夹（助手内分组）。
 */
data class Folder(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val name: String,
    val sortIndex: Int = 0,
    val createAt: Instant = Instant.now(),
)
