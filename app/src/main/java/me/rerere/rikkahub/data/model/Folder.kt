package me.rerere.rikkahub.data.model

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
    // 4.8.24 项目包 CWD（工作目录，工作区相对路径）— null = 未设置
    val cwd: String? = null,
)
