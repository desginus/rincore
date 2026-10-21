/* 【域 I·数据存储】 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.data.sync

/* ───【原版对齐】BackupManager.kt | 差异 ±4 行 (基线 2.5.1)
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.files.ChatMediaStore
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        // v5.0.41：进度回调（字节目录 = 当前已打包的用户数据量 + 阶段名）
        onProgress: ((stage: String, bytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        // v5.0.45 用户实证：导出中途进程被杀 → finally 不执行 → cache 里留下 20G+ 的
        // staging 目录与半成品 zip（"文件没导出来，存储却被占了"）。每次导出前先清残留。
        runCatching {
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("backup-") || f.name.startsWith("backup_")) {
                    f.deleteRecursively()
                }
            }
        }
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.settingsFlow.first()
            ZipOutputStream(FileOutputStream(archive)).apply {
                // v5.0.45 关键修复：设 NO_COMPRESSION。
                // workspaces.tar.gz 本身已是 gzip 压缩，zip 再 DEFLATE 一遍纯属浪费：
                // 20G 数据会把 CPU 打满数十分钟，应用在后台被系统杀掉（用户实测"提示导出中→回主页→文件没有"）。
                // 存储介质写入带宽远高于此，不压缩后耗时从数十分钟降到数分钟级。
                setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            }.use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(json.encodeToString(settings).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    onProgress?.invoke("附件", 0L)
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // 上游语义：正在下载的媒体结果是半成品（完成后改名），不进备份
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                    // v5.0.41 用户定案：**全量备份必须包含工作区**（此前 ATTACHMENT_FOLDERS 不含 workspaces，
                    // 换包名/换机后工作区全丢）。用 tar.gz 保留 rootfs 符号链接；排除 tmp/ 临时垃圾。
                    val wsRoot = File(context.filesDir, "workspaces")
                    if (wsRoot.isDirectory && wsRoot.listFiles()?.isNotEmpty() == true) {
                        val wsTar = File(staging, "workspaces.tar.gz")
                        wsTar.outputStream().buffered().use { out ->
                            me.rerere.rikkahub.data.workspace.TarGz.write(
                                output = out,
                                roots = listOf(wsRoot.toPath() to "workspaces"),
                                excludeDirNames = setOf("tmp"),
                                onProgress = { files, bytes -> onProgress?.invoke("工作区", bytes) },
                            )
                        }
                        addFile(zip, wsTar, "workspaces.tar.gz", onProgress = onProgress)
                    }
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            // v5.0.41：工作区全量恢复（tar.gz → payload/files/workspaces）
                            // v5.0.50 ★关键架构修正（用户实证"导入备份失败"的总根因）：
                            //
                            // 旧实现把 15G 工作区解到 `pending/payload/files/workspaces`，再由 PendingRestore
                            // 逐文件生成 journal + 逐条 move。一个 15G rootfs 有**几十万个文件**：
                            //   buildEntries 几十万条 → journal JSON 几十 MB → 每条一次 targetFile().exists()
                            //   → 再几十万次 ATOMIC_MOVE → 内存/IO/时间全爆 → 抛异常 → RestoreFailedException
                            //   → 用户看到"导入备份失败，已使用先前的数据"。
                            //
                            // 工作区是"目录树"，不需要 journal 的事务语义。改为**就地解压到最终位置**：
                            //   ① 现有 workspaces 整目录改名备份（失败可回滚）
                            //   ② 直接解压 tar.gz 到 filesDir/workspaces
                            //   ③ 失败 → 删半成品 + 备份改回原名
                            // 这样 PendingRestore 只需处理 database/settings/附件（几 MB），重启秒完成。
                            if (entry.name == "workspaces.tar.gz") {
                                if (includeFiles) {
                                    val wsRoot = File(context.filesDir, "workspaces")
                                    val backupDir = File(
                                        context.filesDir,
                                        "workspaces.bak-" + System.currentTimeMillis(),
                                    )
                                    var hadPrevious = false
                                    if (wsRoot.exists()) {
                                        hadPrevious = wsRoot.renameTo(backupDir)
                                        if (!hadPrevious) error("无法备份现有工作区目录，已中止导入")
                                    }
                                    check(wsRoot.mkdirs() || wsRoot.isDirectory) { "无法创建工作区目录" }
                                    try {
                                        zip.getInputStream(entry).use { ins ->
                                            me.rerere.rikkahub.data.workspace.TarGz.read(ins) { tarEntry, stream ->
                                                val name = tarEntry.name.trimStart('/')
                                                if (!name.startsWith("workspaces/") || name.contains("..")) return@read
                                                val rel = name.removePrefix("workspaces/")
                                                if (rel.isBlank()) return@read
                                                val outFile = File(wsRoot, rel)
                                                val base = wsRoot.absolutePath.trimEnd(File.separatorChar)
                                                if (!outFile.absolutePath.startsWith(base + File.separator)) return@read
                                                when {
                                                    tarEntry.isDirectory -> outFile.mkdirs()
                                                    tarEntry.isSymlink -> {
                                                        val t = tarEntry.linkTarget
                                                        if (t.isNotBlank() && !t.contains("..") && !t.startsWith("/")) {
                                                            outFile.parentFile?.mkdirs()
                                                            runCatching {
                                                                java.nio.file.Files.createSymbolicLink(
                                                                    outFile.toPath(), File(t).toPath()
                                                                )
                                                            }
                                                        }
                                                    }
                                                    else -> {
                                                        outFile.parentFile?.mkdirs()
                                                        FileOutputStream(outFile).use { o -> stream.copyTo(o, 64 * 1024) }
                                                    }
                                                }
                                            }
                                        }
                                        restoredEntries++
                                    } catch (e: Throwable) {
                                        // 回滚：删半成品，恢复原工作区
                                        runCatching { wsRoot.deleteRecursively() }
                                        if (hadPrevious) runCatching { backupDir.renameTo(wsRoot) }
                                        throw e
                                    }
                                }
                                continue
                            }
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    output.fd.sync()
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText()))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeToString(settings))
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(
        zip: ZipOutputStream,
        file: File,
        name: String,
        onProgress: ((stage: String, bytes: Long) -> Unit)? = null,
    ) {
        zip.putNextEntry(ZipEntry(name))
        // v5.0.44：工作区 tar.gz 进 zip 是 20G 级复制，原实现无进度 → UI 表现为「突然停住」。
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            var written = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                zip.write(buf, 0, n)
                written += n
                if (written / (32L shl 20) != (written - n) / (32L shl 20)) {
                    onProgress?.invoke("归档工作区", written)
                }
            }
        }
        zip.closeEntry()
    }

    companion object {
        // 5.0.2: 备份目录集 — 对齐上游 +MEDIA_CREATION（媒体创作产物）；+chat-media（v4.8.185 对话媒体快照）
        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION, ChatMediaStore.DIR_NAME)

        /** 带子目录备份；其余目录只含顶层文件 */
        private val NESTED_ATTACHMENT_FOLDERS =
            setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION, ChatMediaStore.DIR_NAME)

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(context, json.decodeFromString<Settings>(settingsJson))
            }
        }
    }
}
