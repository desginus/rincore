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
    /** 种子版本 — 内置包内容更新时递增 (驱动文件刷新, 保留 enabled) */
    private const val SEED_VERSION = 1
    private const val ASSET_DIR = "operit-packages"
    private const val ENTRY_PREFIX = "builtin:"

    /**
     * 幂等播种 — 可在任意 refresh 链路安全重复调用。
     * @return 本次新增/更新的包数量 (0 = 无变化)
     */
    suspend fun ensureSeeded(context: Context, store: InstalledPackageStore): Int {
        val assets = runCatching {
            context.assets.list(ASSET_DIR)?.filter { it.endsWith(".js") }.orEmpty()
        }.getOrDefault(emptyList())
        if (assets.isEmpty()) return 0

        val installed = store.listInstalled().associateBy { it.entryId }
        val targetDir = store.storageDir("script")
        var changed = 0

        for (fileName in assets) {
            val entryId = ENTRY_PREFIX + fileName.removeSuffix(".js")
            val existing = installed[entryId]
            val targetFile = File(targetDir, "builtin_$fileName")

            // 已存在且内容版本一致且文件在 → 跳过 (保留用户 enabled 选择)
            if (existing != null && existing.version == builtinVersion() && targetFile.exists()) {
                continue
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
                    // 用户已开关过的保留其选择; 新包按 METADATA 默认值
                    enabled = existing?.enabled ?: meta.enabledByDefault,
                    sourceUrl = "builtin",
                )
            )
            changed++
        }
        return changed
    }

    private fun builtinVersion(): String = "builtin-v$SEED_VERSION"

    /** 是否为内置包 (UI 徽章/分组用) */
    fun isBuiltin(pkg: InstalledPackage): Boolean = pkg.sourceUrl == "builtin" || pkg.entryId.startsWith(ENTRY_PREFIX)
}
