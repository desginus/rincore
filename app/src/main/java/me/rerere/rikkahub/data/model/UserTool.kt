package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/**
 * 用户自定义工具 — 独立于 MCP / Skill / 插件体系。
 *
 * 用户要求把某个东西设为工具 (算法、GitHub 开源项目、脚本、文件等)，
 * 注册后可通过输入栏 @ 引用 (对模型表现为精确路径，与 @/workspace 引用一致)。
 *
 * 仅做 name → path 的映射，不改变工具注入/缓存/路由逻辑。
 */
@Serializable
data class UserTool(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
