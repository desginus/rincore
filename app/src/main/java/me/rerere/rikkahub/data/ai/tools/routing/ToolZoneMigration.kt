/**
 * 工具矩阵迁移（v4.8.87 重写）。
 *
 * 四件事，各管一段，互不越界：
 *  A. [projectZoneModel] —— 模型投影 v1(`id`=路径) → v2(id 不透明 / name / parentId)。
 *     纯投影：只把"路径里隐含的层级"显式化成 name+parentId，**不改 id、不增删区**。
 *  B. [migrateToZones] —— 旧「工具域」九件套 → 工具区（首启一次）。
 *  C. [migrateLinkKeys] —— 归属键同源化：`skill:<名>` → 完整工具名 `skill__<净化名>`。
 *     上一版模型侧写 `skill:<净化名>`、SkillManager 清理侧比 `skill:<原始目录名>`，
 *     键不同源 ⇒ 挂载点被当孤儿删掉 ⇒ 归类回落"老家"（用户实证 bug）。
 *  D. （v4.8.92 已移除「恢复出厂」入口与实现 —— 误触会复活用户已删的模板区。）
 */
/* 【域 C·工具系统】 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.data.ai.tools.routing

import me.rerere.rikkahub.data.ai.tools.sanitizeSkillToolName
import me.rerere.rikkahub.data.datastore.LegacyCustomDomain

// ═══════════ A. 模型投影（一次性，幂等） ═══════════

/** 当前工具区模型版本（v1 = id 即路径；v2 = id 不透明 + name/parentId 引用） */
const val ZONE_MODEL_VERSION_CURRENT = 2

/**
 * 把 v1 数据（id 即路径、没有 name/parentId）投影成 v2（name + parentId 显式化）。
 *  · 只补空字段：已有 name/parentId 的原样保留（用户改名/移动过的区不会被"按路径复位"）。
 *  · 父区通过**旧 id 精确相等**解析（v1 里 id 就是路径，这是唯一正确的解析方式）。
 *  · 解析不到父区 ⇒ 该区就是根区（不会产生孤儿）。
 */
fun projectZoneModel(zones: List<ToolZone>): List<ToolZone> {
    val ids = zones.map { it.id }.filter { it.isNotBlank() }.toSet()
    return zones.map { z ->
        val name = z.name.ifBlank { z.id.substringAfterLast('/') }
        val parent = z.parentId
            ?.takeIf { it.isNotBlank() && it != z.id && it in ids }
            ?: z.id.substringBeforeLast('/', "")
                .takeIf { it.isNotBlank() && it != z.id && it in ids }
        if (name == z.name && parent == z.parentId) z else z.copy(name = name, parentId = parent)
    }
}

// ═══════════ B. 旧「工具域」→ 工具区（首启一次） ═══════════

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

    // 2. 旧自定义区 → 普通区（此阶段 id 仍是旧路径；层级由 projectZoneModel 显式化）
    val custom = customDomains
        .map { cd ->
            val id = legacyPath(cd)
            ToolZone(
                id = id,
                name = id.substringAfterLast('/'),
                title = domainNameOverrides[id].orEmpty(),
                description = customDomainDescriptions[id] ?: cd.description,
                keywords = customDomainKeywords[id] ?: cd.keywords,
            )
        }
        .filter { it.id.isNotBlank() }

    val zones = projectZoneModel((seeded + custom).distinctBy { it.id })
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

// ═══════════ C. 归属键同源化 ═══════════

/**
 * `skill:<名>` → `skill__<净化名>`（完整工具名，与 SkillManager/SkillsTools 同一套命名）。
 * 非 skill 键原样保留；已经是工具名的键幂等不变。
 */
fun migrateLinkKeys(links: Map<String, String>): Map<String, String> {
    var changed = false
    val out = LinkedHashMap<String, String>(links.size)
    for ((key, value) in links) {
        val newKey = if (key.startsWith("skill:")) {
            sanitizeSkillToolName(key.removePrefix("skill:"))
        } else key
        if (newKey != key) changed = true
        out[newKey] = value
    }
    return if (changed) out else links
}

