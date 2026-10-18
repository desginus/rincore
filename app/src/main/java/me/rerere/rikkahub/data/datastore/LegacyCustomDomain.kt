package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.Serializable

/**
 * 旧「工具域」的自定义域记录 —— 仅迁移期（[me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV5Migration]）读取，
 * 迁移后不再写入。新体系用 ToolZone（id 即身份，无 parent 字段）。
 */
@Serializable
data class LegacyCustomDomain(
    val name: String = "",
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val parent: String? = null,
)
