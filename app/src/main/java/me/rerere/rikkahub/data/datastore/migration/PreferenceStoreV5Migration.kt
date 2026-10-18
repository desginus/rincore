package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.ai.tools.routing.LegacyZoneConfig
import me.rerere.rikkahub.data.ai.tools.routing.ToolZone
import me.rerere.rikkahub.data.ai.tools.routing.ZONE_MODEL_VERSION_CURRENT
import me.rerere.rikkahub.data.ai.tools.routing.migrateLinkKeys
import me.rerere.rikkahub.data.ai.tools.routing.projectZoneModel
import me.rerere.rikkahub.data.datastore.LegacyCustomDomain
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * RinCore：旧「工具域」九件套 → 工具区模型 一次性迁移 + v4.8.87 模型投影。
 *
 * 原实现内联在 PreferencesStore 的解码链（每次读取重跑、幂等）；反向换底后迁移为
 * 标准 DataMigration（只跑一次），行为等价：
 * - 迁移完成写死 toolZoneSeeded=true 并删除全部旧键；
 * - 版本落后时对现有工具区做一次投影（id 不透明 + name/parentId）。
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val seeded = currentData[SettingsStore.TOOL_ZONE_SEEDED] == true
        val version = currentData[SettingsStore.ZONE_MODEL_VERSION] ?: 0
        val legacyPresent = currentData[SettingsStore.CUSTOM_DOMAINS] != null ||
            currentData[SettingsStore.TOOL_DOMAIN_OVERRIDES] != null ||
            currentData[SettingsStore.CUSTOM_DOMAIN_DESCRIPTIONS] != null ||
            currentData[SettingsStore.CUSTOM_DOMAIN_KEYWORDS] != null ||
            currentData[SettingsStore.DOMAIN_NAME_OVERRIDES] != null ||
            currentData[SettingsStore.HIDDEN_DOMAINS] != null ||
            currentData[SettingsStore.REMOVED_BUILTIN_DOMAINS] != null ||
            currentData[SettingsStore.EXEMPT_FROM_DOMAIN_TOOLS] != null
        return !seeded || legacyPresent || version < ZONE_MODEL_VERSION_CURRENT
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        var zones = prefs[SettingsStore.TOOL_ZONES]?.let {
            runCatching { JsonInstant.decodeFromString<List<ToolZone>>(it) }.getOrDefault(emptyList())
        } ?: emptyList()
        var links = prefs[SettingsStore.TOOL_ZONE_LINKS]?.let {
            runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
        } ?: emptyMap()

        if (prefs[SettingsStore.TOOL_ZONE_SEEDED] != true) {
            val legacy = LegacyZoneConfig(
                customDomains = prefs[SettingsStore.CUSTOM_DOMAINS]?.let {
                    runCatching { JsonInstant.decodeFromString<List<LegacyCustomDomain>>(it) }.getOrDefault(emptyList())
                } ?: emptyList(),
                toolDomainOverrides = prefs[SettingsStore.TOOL_DOMAIN_OVERRIDES]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                customDomainDescriptions = prefs[SettingsStore.CUSTOM_DOMAIN_DESCRIPTIONS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                customDomainKeywords = prefs[SettingsStore.CUSTOM_DOMAIN_KEYWORDS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, List<String>>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                domainNameOverrides = prefs[SettingsStore.DOMAIN_NAME_OVERRIDES]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrDefault(emptyMap())
                } ?: emptyMap(),
                hiddenDomains = prefs[SettingsStore.HIDDEN_DOMAINS]?.let {
                    runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet())
                } ?: emptySet(),
                removedBuiltinDomains = prefs[SettingsStore.REMOVED_BUILTIN_DOMAINS]?.let {
                    runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet())
                } ?: emptySet(),
                exemptFromDomainTools = prefs[SettingsStore.EXEMPT_FROM_DOMAIN_TOOLS]?.let {
                    runCatching { JsonInstant.decodeFromString<Set<String>>(it) }.getOrDefault(emptySet())
                } ?: emptySet(),
            ).migrateToZones()
            zones = legacy.zones
            links = legacy.links
            prefs[SettingsStore.HIDDEN_ZONES] = JsonInstant.encodeToString(legacy.hiddenZones)
            prefs[SettingsStore.TOP_LEVEL_ADDITIONS] = JsonInstant.encodeToString(legacy.topLevelAdditions)
            prefs[SettingsStore.TOP_LEVEL_REMOVALS] = JsonInstant.encodeToString(emptySet<String>())
        }

        val version = prefs[SettingsStore.ZONE_MODEL_VERSION] ?: 0
        if (version < ZONE_MODEL_VERSION_CURRENT) {
            zones = projectZoneModel(zones)
            links = migrateLinkKeys(links)
        }

        prefs[SettingsStore.TOOL_ZONES] = JsonInstant.encodeToString(zones)
        prefs[SettingsStore.TOOL_ZONE_LINKS] = JsonInstant.encodeToString(links)
        prefs[SettingsStore.TOOL_ZONE_SEEDED] = true
        prefs[SettingsStore.ZONE_MODEL_VERSION] = ZONE_MODEL_VERSION_CURRENT

        // 旧键一次性清除（迁移已完成，不再保留墓碑）
        prefs.remove(SettingsStore.CUSTOM_DOMAINS)
        prefs.remove(SettingsStore.TOOL_DOMAIN_OVERRIDES)
        prefs.remove(SettingsStore.CUSTOM_DOMAIN_DESCRIPTIONS)
        prefs.remove(SettingsStore.CUSTOM_DOMAIN_KEYWORDS)
        prefs.remove(SettingsStore.DOMAIN_NAME_OVERRIDES)
        prefs.remove(SettingsStore.HIDDEN_DOMAINS)
        prefs.remove(SettingsStore.REMOVED_BUILTIN_DOMAINS)
        prefs.remove(SettingsStore.EXEMPT_FROM_DOMAIN_TOOLS)

        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
