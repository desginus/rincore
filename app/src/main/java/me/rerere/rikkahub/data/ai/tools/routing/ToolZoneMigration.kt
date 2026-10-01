/**
 * 旧「工具域」配置 → 新「工具区」配置的一次性迁移（v4.8.83）。
 *
 * 旧体系有 9 个平行配置面（customDomains / removedBuiltinDomains / hiddenDomains /
 * toolDomainOverrides / customDomainDescriptions / customDomainKeywords /
 * domainNameOverrides / exemptFromDomainTools / demotedFrameworkTools），
 * 新体系收敛为 5 个：toolZones / toolZoneLinks / hiddenZones / topLevelAdditions /
 * topLevelRemovals（+ toolZoneSeeded 幂等标记）。
 *
 * 迁移原则: 精确保留「实际生效过」的语义，丢弃「从未生效」的死配置。
 *  - 旧的 demotedFrameworkTools 只被 UI 读写、注入链从未消费（框架工具即使被"移进域"
 *    仍顶层注入）→ 迁移时丢弃（保留实际行为），用户可在新界面一键真正移进工具区。
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.rikkahub.data.datastore.LegacyCustomDomain

/** 旧体系原始配置（仅迁移期读取，迁移完成后新字段完全取代） */
data class LegacyZoneConfig(
    val customDomains: List<LegacyCustomDomain> = emptyList(),
    val toolDomainOverrides: Map<String, String> = emptyMap(),
    val customDomainDescriptions: Map<String, String> = emptyMap(),
    val customDomainKeywords: Map<String, List<String>> = emptyMap(),
    val domainNameOverrides: Map<String, String> = emptyMap(),
    val hiddenDomains: Set<String> = emptySet(),
    val removedBuiltinDomains: Set<String> = emptySet(),
    val exemptFromDomainTools: Set<String> = emptySet(),
)

/** 迁移结果 —— 直接落进 Settings 的新字段 */
data class MigratedZoneConfig(
    val zones: List<ToolZone>,
    val links: Map<String, String>,
    val hiddenZones: Set<String>,
    val topLevelAdditions: Set<String>,
)

/** 旧 CustomDomain 算全路径（与旧 normalizedFullPath 等价，只是写法统一了） */
private fun legacyPath(cd: LegacyCustomDomain): String {
    val namePart = cd.name.substringAfterLast('/')
    return cd.parent?.takeIf { it.isNotBlank() }?.let { "$it/$namePart" } ?: cd.name
}

fun LegacyZoneConfig.migrateToZones(): MigratedZoneConfig {
    val removed = removedBuiltinDomains.filter { it.isNotBlank() }.toSet()

    // 1. 出厂模板区（排除旧体系里被显式删除的），并套用旧覆盖层
    val seeded = DEFAULT_TOOL_ZONES
        .filter { z -> z.id !in removed && z.id.substringBefore('/') !in removed }
        .map { z ->
            z.copy(
                title = domainNameOverrides[z.id].orEmpty(),
                description = customDomainDescriptions[z.id] ?: z.description,
                keywords = customDomainKeywords[z.id] ?: z.keywords,
            )
        }

    // 2. 旧自定义区 → 普通区（同一身份即 id）
    val custom = customDomains
        .map { cd ->
            val id = legacyPath(cd)
            ToolZone(
                id = id,
                title = domainNameOverrides[id].orEmpty(),
                description = customDomainDescriptions[id] ?: cd.description,
                keywords = customDomainKeywords[id] ?: cd.keywords,
            )
        }
        .filter { it.id.isNotBlank() }

    val zones = (seeded + custom).distinctBy { it.id }
    val ids = zones.map { it.id }.toSet()

    // 3. 旧覆盖层 value 可能是短名/双叠路径 → 归一到真实 id；指不到任何区的丢弃
    fun resolveLegacy(raw: String): String? {
        if (raw in ids) return raw
        val short = raw.substringAfterLast('/')
        return ids.firstOrNull { it.substringAfterLast('/') == short }
    }

    return MigratedZoneConfig(
        zones = zones,
        links = toolDomainOverrides.mapNotNull { (tool, zone) -> resolveLegacy(zone)?.let { tool to it } }.toMap(),
        hiddenZones = hiddenDomains.mapNotNull { resolveLegacy(it) }.toSet(),
        topLevelAdditions = exemptFromDomainTools,
    )
}

/** 恢复出厂工具区：只补回「缺失的模板区」，不动用户自建区、不覆盖用户改过的区。 */
fun restoreDefaultZones(current: List<ToolZone>): List<ToolZone> {
    val have = current.map { it.id }.toSet()
    val missing = DEFAULT_TOOL_ZONES.filter { it.id !in have }
    return current + missing
}
