/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
package me.rerere.rikkahub.data.operit.runtime


/* ───【自研】岔路口计划·生态模块 — 内置包播种器
 * 把 assets/operit-packages 的生态包 (Operit 内置工具包) 释放到包存储,
 * 统一走 InstalledPackageStore 视角: 生态页/工具供给/开关管理同源。
 * 幂等: 仅新增缺失包; 种子版本升级时刷新文件内容但保留用户开关选择。
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import me.rerere.rikkahub.data.operit.market.InstalledPackage
import me.rerere.rikkahub.data.operit.market.InstalledPackageStore
import java.io.File

object OperitBuiltinPackages {
    /** 种子版本 — 内置包内容更新时递增 (驱动文件刷新, 保留 enabled)
     *  v2 (v4.6.5): 增强记忆 / 增强HTTP 默认开启 (升级时对这两个包强制开一次) */
    private const val SEED_VERSION = 2

    /** 默认开启白名单 (首次播种即开; 种子升级时强制开一次, 之后尊重用户开关) */
    private val DEFAULT_ENABLED_PACKAGES = setOf("extended_memory_tools", "extended_http_tools")
    private const val ASSET_DIR = "operit-packages"
    private const val ENTRY_PREFIX = "builtin:"

    /**
     * 幂等播种 — 可在任意 refresh 链路安全重复调用。
     * v4.6.3 修正: "已播种集合"记忆 — 用户卸载的内置包不再复活。
     * 迁移: 库里已有内置包但无集合记录 (旧版播种) → 集合初始化为当前资产全集
     *       (用户卸过的同样打标, 不复活); 全新用户 → 空集正常全量播种。
     * @return 本次新增/更新的包数量 (0 = 无变化)
     */
    suspend fun ensureSeeded(context: Context, store: InstalledPackageStore): Int {
        val assets = runCatching {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".js") }.orEmpty()
        }.getOrDefault(emptyList())
        if (assets.isEmpty()) return 0

        val allEntryIds = assets.map { ENTRY_PREFIX + it.removeSuffix(".js") }.toSet()
        val installed = store.listInstalled().associateBy { it.entryId }
        var seeded = store.getSeededEntries()

        // 迁移: 旧版播种过 (库里有内置包) 但集合为空 → 全部资产打标 (不复活已卸载的)
        if (seeded.isEmpty() && installed.keys.any { it.startsWith(ENTRY_PREFIX) }) {
            store.addSeededEntries(allEntryIds)
            seeded = allEntryIds
        }

        val targetDir = store.storageDir("script")
        var changed = 0
        val newlySeeded = mutableListOf<String>()

        for (fileName in assets) {
            val entryId = ENTRY_PREFIX + fileName.removeSuffix(".js")
            val existing = installed[entryId]
            val targetFile = File(targetDir, "builtin_$fileName")

            // 已打标 (播过/被卸载) 且文件在版本一致 → 跳过
            if (entryId in seeded) {
                // 例外: 内容版本升级刷新 (仅对仍在库中的, 不复活已卸载的)
                if (existing != null && existing.version != builtinVersion()) {
                    // fallthrough 刷新
                } else {
                    continue
                }
            }

            val source = runCatching {
                context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { it.readText() }
            }.getOrNull() ?: continue

            val meta = runCatching { ScriptMetadataParser.parse(source) }.getOrNull()
                ?: runCatching { ScriptMetadataParser.parseLoose(source) }.getOrNull()
                ?: continue

            // 释放文件
            runCatching { targetFile.writeText(source) }.getOrElse { continue }

            val title = ScriptMetadataParser.pickText(meta.display_name)
                .ifBlank { meta.name.ifBlank { fileName.removeSuffix(".js") } }

            // v4.6.5: 白名单包默认开启 (首次即开; 种子升级强制开一次, 之后尊重用户)
            val baseName = fileName.removeSuffix(".js")
            val isDefaultOn = baseName in DEFAULT_ENABLED_PACKAGES
            val versionBumped = existing != null && existing.version != builtinVersion()
            val enabled = when {
                isDefaultOn && (existing == null || versionBumped) -> true
                existing != null -> existing.enabled
                else -> meta.enabledByDefault || isDefaultOn
            }

            store.upsert(
                InstalledPackage(
                    entryId = entryId,
                    type = "script",
                    title = title,
                    version = builtinVersion(),
                    assetId = "builtin",
                    fileName = targetFile.name,
                    installPath = targetFile.absolutePath,
                    formatVer = "script_v2",
                    enabled = enabled,
                    sourceUrl = "builtin",
                )
            )
            newlySeeded.add(entryId)
            changed++
        }
        if (newlySeeded.isNotEmpty()) store.addSeededEntries(newlySeeded)
        return changed
    }

    private fun builtinVersion(): String = "builtin-v$SEED_VERSION"

    /** 是否为内置包 (UI 徽章/分组用) */
    fun isBuiltin(pkg: InstalledPackage): Boolean = pkg.sourceUrl == "builtin" || pkg.entryId.startsWith(ENTRY_PREFIX)
}
