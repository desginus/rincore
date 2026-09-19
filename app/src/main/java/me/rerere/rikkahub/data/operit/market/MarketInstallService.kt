package me.rerere.rikkahub.data.operit.market


/* ───【自研】岔路口计划·阶段1 — 安装协调器
 * 串起: 条目详情 → 下载器 → 落盘 → 已安装记录
 * 来源: RinCore 自研新增
 * ───────────────────────────────────────────────────────────────*/
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MarketInstallService(
    private val apiService: MarketApiService,
    private val downloader: PackageDownloader,
    private val store: InstalledPackageStore,
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
        // 删除文件
        runCatching {
            val file = File(existing.installPath)
            if (file.exists()) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
        store.remove(entryId)
        true
    }
}
