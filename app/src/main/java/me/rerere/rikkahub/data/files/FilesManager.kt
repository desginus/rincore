/* 【域 D·工作区沙箱】 — 文件/技能 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.files


/* ───【原版对齐】FilesManager | 差异 +62 行
 * 来源: 原版移植 + 自研 (文件管理增强)
 * 差异: 会话附件生命周期自研处理
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.decodeLocalFilePath
import me.rerere.rikkahub.utils.isAppLocalImageUri
import me.rerere.rikkahub.utils.resolveLocalImageFile
import me.rerere.rikkahub.utils.exportImageFile
import me.rerere.rikkahub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** v4.8.110 (B2): MCP 产物归集单文件上限 (与工作区读上限同语义) */
private const val MAX_RESCUE_BYTES = 8L * 1024 * 1024

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
        )
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeBytes(bytes)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        )
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                // v4.8.91: 上传一律时间码命名（<码>_<原名>.ext），码即文件名前缀
                // v4.8.134 (B155): 原子取号+创建 (并行上传不再撞号覆盖)
                val file = FileUtils.createUploadFile(dir, displayName = sourceName, mimeType = sourceMime)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackManagedFile(
                    folder = FileFolders.UPLOAD,
                    file = file,
                    displayName = sourceName,
                    mimeType = guessedMime
                )
                newUris.add(file.toUri())
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(
        byteArrays: List<ByteArray>,
        // v5.0.44：回滚 5.0.15 后兼容当前 ChatAttachmentPicker 调用（草图/裁剪自定义名与 MIME）
        displayName: String? = null,
        mimeType: String? = null,
    ): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            // v4.8.134 (B155): 原子取号+创建 — 并行读图各自独立文件, 不再同号覆盖
            val file = FileUtils.createUploadFile(
                dir,
                displayName = displayName ?: "image.png",
                mimeType = mimeType ?: "image/png",
            )
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = "image.png",
                mimeType = "image/png"
            )
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = FileUtils.compressBitmapToPng(bitmap)
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    fun deleteChatFiles(uris: List<Uri>) {
        val relativePaths = mutableSetOf<String>()
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            getRelativePathInFilesDir(file)?.let { relativePaths.add(it) }
            if (file.exists()) {
                file.delete()
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    repository.deleteByPath(path)
                }
            }
        }
    }

    /** v4.8.185 (审计重写): 对话媒体快照整目录清理 — 对话删除时调用。 */
    fun deleteConversationChatMedia(conversationId: String) =
        ChatMediaStore.deleteConversationMedia(context, conversationId)

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = FileUtils.createUploadFile(dir, displayName = "pasted_text.txt", mimeType = "text/plain")
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String): Boolean = withContext(Dispatchers.IO) {
        val activity = activityContext.getActivity()
        if (activity == null) {
            Log.e("FilesManager", "saveMessageImage: activity not found")
            return@withContext false
        }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                if (bitmap == null) {
                    logSaveFailure("data_decode_failed", image)
                    false
                } else activityContext.exportImage(activity, bitmap)
            }

            // v4.8.129 (用户实证: "Workspace/上传图片保存失败且运行日志为空"):
            // 与渲染链同源 — v4.8.115 单一解码点 (isAppLocalImageUri + resolveLocalImageFile)
            // 认领并解析, 凡渲染认领的本地图片一律可保存。旧实现两处缺陷在此修复:
            // ①host 前缀误判 (上传图 "file:///data/..." 命中 HOST 前缀 → resolver 对
            //   非 workspaces 结构判 invalid_path → 保存失败);
            // ②file:/ 单斜杠形态 (File.toUri() 产出, 中文名 percent 编码) 走
            //   Uri.toFile() 不解码 → file not found。
            isAppLocalImageUri(image) -> {
                val resolved = resolveLocalImageFile(image)
                if (resolved != null && resolved.isFile) {
                    activityContext.exportImageFile(activity, resolved)
                } else {
                    logSaveFailure("local_unresolved", image)
                    false
                }
            }

            image.startsWith("file:") -> {
                val decoded = decodeLocalFilePath(image)
                val file = decoded?.let { File(it) }
                if (file == null || !file.isFile) {
                    logSaveFailure("file_not_found", image)
                    false
                } else activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                val file = File(image)
                if (!file.isFile) {
                    logSaveFailure("path_not_found", image)
                    false
                } else activityContext.exportImageFile(activity, file)
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    try {
                        connection.connect()
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                            if (bitmap == null) {
                                logSaveFailure("http_decode_failed", image)
                                false
                            } else activityContext.exportImage(activity, bitmap)
                        } else {
                            logSaveFailure("http_${connection.responseCode}", image)
                            false
                        }
                    } finally {
                        runCatching { connection.disconnect() }
                    }
                }.getOrElse { t ->
                    Log.e("FilesManager", "saveMessageImage: http failed: $image", t)
                    logSaveFailure("http_failed", image)
                    false
                }
            }

            else -> {
                logSaveFailure("unsupported", image)
                false
            }
        }
    }

    /**
     * v4.8.129: 保存失败统一落点 — logcat + 运行日志实落 (空闲期走 CallTracer
     * 的最近会话兜底, 不再静默丢弃)。此前"详情见运行日志"因事件被丢而名不副实。
     */
    private suspend fun logSaveFailure(reason: String, image: String) {
        Log.e("FilesManager", "saveMessageImage: $reason: ${image.take(300)}")
        me.rerere.rikkahub.data.ai.CallTracer.event(
            "SAVE", "save_image_failed", "reason=$reason url=${image.take(300)}"
        )
    }

    data class SyncResult(
        val inserted: Int,
        val removed: Int,
    )

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val diskFiles = if (File(context.filesDir, folder).exists()) {
            File(context.filesDir, folder).listFiles()?.filter { it.isFile }
                ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }
        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 数据库 -> 磁盘：清理文件已不存在的孤儿记录
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (entity.relativePath !in diskRelativePaths && !getFile(entity).isFile) {
                repository.deleteByPath(entity.relativePath)
                removed += 1
            }
        }
        SyncResult(inserted = inserted, removed = removed)
    }

    suspend fun deleteAll(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        try {
            val files = repository.listByFolder(folder).first()
            files.forEach { entity ->
                val file = getFile(entity)
                if (file.isFile) file.delete()
                repository.deleteByPath(entity.relativePath)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all files in folder: $folder", e)
            false
        }
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (deleteFromDisk) {
            runCatching { getFile(entity).delete() }
        }
        repository.deleteById(id) > 0
    }

    /**
     * v4.8.110 (B2): MCP 产物归集 — MCP 输出按"全局视角"书写绝对路径, 受限助手
     * (CWD 工作区) 按子目录视角解读必然失效。对"存在且不在调用方可见范围内"的文件:
     * 复制到 /tool_outputs/<serverSlug>/rescued/ 并原样改写文本路径。
     *  ① 可见范围 = 调用方 files 区 (workspaces/<callerRoot>/files + CWD 前缀) 或 /tool_outputs 共享区;
     *  ② 单文件上限 8MB (与工作区读上限同语义), 超限跳过并在文本尾注说明 (不静默);
     *  ③ 失败/跳过以尾注说明; 正常归集静默完成。
     */
    suspend fun rescueArtifactPathsByPolicy(
        text: String,
        serverSlug: String,
        serverRoot: String?,   // MCP 绑定工作区的 root 目录名 (null=无法解析 /workspace 路径)
        callerRoot: String?,   // 调用方助手工作区 root 目录名
        callerCwd: String?,    // 调用方 CWD (相对 files 区; 空=整区可见)
    ): String = withContext(Dispatchers.IO) {
        val slug = serverSlug.takeIf { it.isNotBlank() && !it.contains("..") && !it.contains('/') } ?: "server"
        val pathRegex = Regex("""(/workspace/|/tool_outputs/|/data/data/|/data/user/)[^\s"'`<>()\[\]]+""")
        val literals = pathRegex.findAll(text).map { it.value }.distinct().toList()
        if (literals.isEmpty()) return@withContext text

        // 与 RepositoryModule 的 WorkspaceManager baseDir 对齐 (布局漂移时两处同步)
        val workspacesBase = File(context.filesDir, "workspaces")
        val callerVisibleRoot: File? = callerRoot?.let { root ->
            val base = File(File(workspacesBase, root), "files")
            val cwdRel = callerCwd?.trim('/')?.takeIf { it.isNotEmpty() }
            if (cwdRel == null) base else File(base, cwdRel)
        }
        val sharedRootPath = File(context.filesDir, FileFolders.TOOL_OUTPUTS).absolutePath + File.separator
        val notes = mutableListOf<String>()
        var out = text
        for (literal in literals) {
            val src: File? = when {
                literal.startsWith("/tool_outputs/") -> continue   // 共享区: 所有工作区可见, 无需归集
                literal.startsWith("/workspace/") -> serverRoot?.let { root ->
                    File(File(File(workspacesBase, root), "files"), literal.removePrefix("/workspace/"))
                }
                else -> File(literal) // /data/data|/data/user 全物理路径
            }
            if (src == null || !src.exists() || !src.isFile) continue
            val srcPath = src.absolutePath
            val inCallerVisible = callerVisibleRoot?.let { srcPath.startsWith(it.absolutePath + File.separator) } ?: false
            if (inCallerVisible || srcPath.startsWith(sharedRootPath)) continue
            if (src.length() > MAX_RESCUE_BYTES) {
                notes += "「" + src.name + "」超过 " + (MAX_RESCUE_BYTES / 1024 / 1024) + "MB 未归集"
                continue
            }
            val ok = runCatching {
                val dir = File(File(context.filesDir, FileFolders.TOOL_OUTPUTS), "$slug/rescued").apply { mkdirs() }
                val target = File(dir, src.name)
                if (!target.exists() || target.length() != src.length()) {
                    src.copyTo(target, overwrite = true)
                }
                out = out.replace(literal, "/tool_outputs/$slug/rescued/" + src.name)
            }.isSuccess
            if (!ok) notes += "「" + src.name + "」归集失败 (原路径保留)"
        }
        if (notes.isEmpty()) out else out + "\n[产物归集: " + notes.joinToString("; ") + "]"
    }

    suspend fun deleteOlderThan(
        folder: String = FileFolders.UPLOAD,
        cutoffMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        var allDeleted = true
        repository.listByFolder(folder).first()
            .filter { it.createdAt < cutoffMillis }
            .forEach { entity ->
                val file = getFile(entity)
                val deletedFromDisk = !file.exists() || runCatching {
                    file.deleteRecursively()
                }.getOrDefault(false)

                if (deletedFromDisk) {
                    if (repository.deleteById(entity.id) == 0) {
                        allDeleted = false
                    }
                } else {
                    allDeleted = false
                }
            }
        allDeleted
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        // v4.8.91: upload 目录统一时间码命名；其它目录保持 uuid 命名
        return if (folder == FileFolders.UPLOAD) {
            // v4.8.134 (B155): 原子取号+创建
            FileUtils.createUploadFile(dir, displayName = displayName, mimeType = mimeType)
        } else {
            File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
        }
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

object FileFolders {
    const val BUILTIN_SKILLS = "builtin_skills"
    const val MEDIA_CREATION = "media_creation"
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
