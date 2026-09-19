package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — 安装协调器
 * 串起: 条目详情 → 下载器 → 落盘 → 已安装记录
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.operit.importer.OperitMcpImporter
import me.rerere.rikkahub.data.operit.importer.OperitSkillImporter
import java.io.File

class MarketInstallService(
    private val apiService: MarketApiService,
    private val downloader: PackageDownloader,
    private val store: InstalledPackageStore,
    // v4.5.30 阶段3: skill/mcp 导入链依赖
    private val context: android.content.Context,
    private val okHttpClient: okhttp3.OkHttpClient,
    private val skillManager: me.rerere.rikkahub.data.files.SkillManager,
    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore,
) {
    sealed class InstallResult {
        data class Success(val installed: InstalledPackage) : InstallResult()
        data class Failure(val reason: String, val cause: Throwable? = null) : InstallResult()
        data class AlreadyInstalled(val installed: InstalledPackage) : InstallResult()
    }

    /**
     * 安装市场条目: 取最新版本资产 → 下载到 storageDir → 记录已安装。
     * 阶段1: 落盘 + 记录 enabled=false (脚本启用能力在阶段2 实现)。
     */
    suspend fun install(
        entry: MarketEntry,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        // 先查已安装 — 同 entryId 同版本直接返回
        store.getInstalled(entry.id)?.let { existing ->
            if (existing.version == entry.latestVersion?.version) {
                return@withContext InstallResult.AlreadyInstalled(existing)
            }
        }

        // v4.5.30 阶段3: skill / mcp 走导入链 (不下载文件, 直接接入现有体系)
        when (entry.type) {
            "skill" -> return@withContext installSkill(entry)
            "mcp" -> return@withContext installMcp(entry)
        }

        val latestVersion = entry.latestVersion
            ?: return@withContext InstallResult.Failure("No latest version for entry ${entry.id}")
        val asset = entry.assets.lastOrNull { it.versionId == latestVersion.id }
            ?: entry.assets.lastOrNull()
            ?: return@withContext InstallResult.Failure("No downloadable asset for entry ${entry.id}")

        val fileName = asset.assetName.ifBlank { "${entry.id}.bin" }
        val storageDir = store.storageDir(entry.type.lowercase())
        val destFile = File(storageDir, fileName)

        val downloadUrl = asset.url.ifBlank { apiService.downloadUrlForAsset(asset.id) }
        if (downloadUrl.isBlank()) {
            return@withContext InstallResult.Failure("Empty download url for asset ${asset.id}")
        }

        val result = downloader.download(
            url = downloadUrl,
            destFile = destFile,
            expectedSha256 = asset.sha256,
            onProgress = onProgress,
        )
        if (result.isFailure) {
            return@withContext InstallResult.Failure(
                reason = result.exceptionOrNull()?.message ?: "download failed",
                cause = result.exceptionOrNull(),
            )
        }

        val installed = InstalledPackage(
            entryId = entry.id,
            type = entry.type,
            title = entry.title,
            version = latestVersion.version,
            assetId = asset.id,
            fileName = fileName,
            installPath = destFile.absolutePath,
            formatVer = latestVersion.formatVer,
            sha256 = asset.sha256,
            installedAt = System.currentTimeMillis(),
            enabled = false,
            runtimePackageId = entry.artifact?.runtimePkg,
        )
        store.upsert(installed)
        InstallResult.Success(installed)
    }

    suspend fun uninstall(entryId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = store.getInstalled(entryId) ?: return@withContext false
        // v4.5.30 阶段3: 按类型清理
        when (existing.type) {
            "skill" -> {
                val names = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(existing.extraJson)
                        .let { el -> (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList() }
                }.getOrDefault(emptyList())
                names.forEach { runCatching { skillManager.deleteSkill(it) } }
            }
            "mcp" -> {
                val ids = parseUuids(existing.extraJson)
                runCatching { OperitMcpImporter.remove(settingsStore, ids) }
            }
            else -> {
                // 删除文件
                runCatching {
                    val file = File(existing.installPath)
                    if (file.exists()) {
                        if (file.isDirectory) file.deleteRecursively() else file.delete()
                    }
                }
            }
        }
        store.remove(entryId)
        true
    }

    // ─── v4.5.30 阶段3: skill / mcp 导入实现 ───

    private suspend fun installSkill(entry: MarketEntry): InstallResult {
        val repoUrl = entry.source?.url.orEmpty()
        if (repoUrl.isBlank()) return InstallResult.Failure("skill 条目缺少 source url")
        val tempRoot = File(context.cacheDir, "operit_import").apply { mkdirs() }
        val result = OperitSkillImporter.importFromRepo(okHttpClient, repoUrl, skillManager, tempRoot)
        return result.fold(
            onSuccess = { r ->
                if (r.importedSkills.isEmpty()) {
                    InstallResult.Failure(r.skippedReason ?: "未导入任何技能")
                } else {
                    val pkg = InstalledPackage(
                        entryId = entry.id,
                        type = entry.type,
                        title = entry.title,
                        version = entry.latestVersion?.version ?: "",
                        assetId = "",
                        fileName = "",
                        installPath = "",
                        formatVer = entry.latestVersion?.formatVer ?: "skill_v2",
                        installedAt = System.currentTimeMillis(),
                        enabled = true,
                        extraJson = kotlinx.serialization.json.JsonArray(
                            r.importedSkills.map { kotlinx.serialization.json.JsonPrimitive(it) },
                        ).toString(),
                        sourceUrl = repoUrl,
                    )
                    store.upsert(pkg)
                    InstallResult.Success(pkg)
                }
            },
            onFailure = { e -> InstallResult.Failure("技能导入失败: ${e.message ?: e}", e) },
        )
    }

    private suspend fun installMcp(entry: MarketEntry): InstallResult {
        val installConfig = entry.latestVersion?.installConfig.orEmpty()
        if (installConfig.isBlank()) return InstallResult.Failure("mcp 条目缺少 installConfig")
        val configs = OperitMcpImporter.parseInstallConfig(installConfig)
        if (configs.isEmpty()) return InstallResult.Failure("installConfig 解析为空 (可能为不支持的传输类型)")
        val ids = OperitMcpImporter.applyImport(settingsStore, configs)
        val pkg = InstalledPackage(
            entryId = entry.id,
            type = entry.type,
            title = entry.title,
            version = entry.latestVersion?.version ?: "",
            assetId = "",
            fileName = "",
            installPath = "",
            formatVer = entry.latestVersion?.formatVer ?: "mcp_v2",
            installedAt = System.currentTimeMillis(),
            enabled = true,
            extraJson = kotlinx.serialization.json.JsonArray(
                ids.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) },
            ).toString(),
            sourceUrl = entry.source?.url.orEmpty(),
        )
        store.upsert(pkg)
        return InstallResult.Success(pkg)
    }

    private fun parseUuids(extraJson: String): List<java.util.UUID> = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(extraJson)
            .let { el -> (el as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.content } ?: emptyList() }
            .mapNotNull { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
    }.getOrDefault(emptyList())
}
