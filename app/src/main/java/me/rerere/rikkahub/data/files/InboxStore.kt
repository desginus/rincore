/* ───【域 D·工作区沙箱】InboxStore.kt —— 收件箱单一门面（v5.0.77 全量重写）
 *
 * 职责：把"任何来源的文件"收进收件箱（files/upload/，带上传码），并对外提供
 * 统一的读取/列举/清理能力。UI（加号菜单/拍照/裁剪/草图/文件选择）一律经
 * 本门面，不再各自拼 createChatFilesByContents。
 *
 * 设计契约（维修与扩展接口见文件尾注释）：
 *  1. **IO 一律 Dispatchers.IO** —— UI 线程只做状态与 toast；
 *  2. **失败必抛 InboxImportException**（带来源描述）—— 调用方 toast 即可，无静默；
 *  3. **cacheDir 下的 file:// 临时源导入成功后自动清理** —— 临时文件职责归一；
 *  4. **码/路径派生复用 UploadCodes/UploadRef** —— 映射体系不重建，避免双轨；
 *  5. **扩展点**：InboxSource sealed 可加新来源；events 流可供检修订阅。
 * ───────────────────────────────────────────────────────────────*/
package me.rerere.rikkahub.data.files

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 收件箱导入来源（sealed —— 新来源只需加子类，导入逻辑集中在一处 when）。 */
sealed interface InboxSource {
    /** content:// / file:// 等 Uri（相册、文档选择器、相机回执、裁剪输出）。 */
    data class FromUri(val uri: Uri, val displayName: String? = null, val mime: String? = null) : InboxSource

    /** 宿主本地文件（拍照 cacheDir 产物等）。 */
    data class FromFile(val file: File, val displayName: String? = null, val mime: String? = null) : InboxSource

    /** 内存字节（草图 encode、剪贴板图片等）。 */
    data class FromBytes(val bytes: ByteArray, val displayName: String, val mime: String) : InboxSource
}

/** 导入失败的唯一异常类型：source = 来源描述（原样回显给用户/toast）。 */
class InboxImportException(
    val source: String,
    cause: Throwable,
) : IllegalStateException("收件箱导入失败：$source → ${cause.message ?: cause::class.simpleName}", cause)

/** 导入结果：uri 可直接 addImages/addFiles；code/hostPath 可供 @提及 与检修。 */
data class InboxEntry(
    val uri: Uri,
    val code: String,
    /** 宿主绝对路径（files/upload/<码>_<名>.<ext>）——检修/直读用。 */
    val hostPath: String,
    val displayName: String,
    val mime: String,
)

/** 导入事件流（检修点）：UI 可选订阅；出问题先看这里。 */
sealed interface InboxEvent {
    data class Imported(val entry: InboxEntry) : InboxEvent
    data class Failed(val source: String, val reason: String) : InboxEvent
}

class InboxStore(
    private val context: Context,
) {
    private val TAG = "InboxStore"

    private val _events = MutableSharedFlow<InboxEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<InboxEvent> = _events.asSharedFlow()

    /** 上传目录（收件箱本体）。 */
    val uploadDir: File get() = context.filesDir.resolve(FileFolders.UPLOAD)

    /**
     * 唯一导入入口。所有磁盘 IO 在 IO 线程；失败抛 [InboxImportException]；
     * cacheDir 下的临时 file:// 源在成功导入后自动删除。
     */
    suspend fun import(source: InboxSource): InboxEntry = withContext(Dispatchers.IO) {
        val described = describe(source)
        try {
            val entry = when (source) {
                is InboxSource.FromBytes -> importBytes(source)
                is InboxSource.FromFile -> importUri(source.file.toUri(), source.displayName, source.mime)
                is InboxSource.FromUri -> importUri(source.uri, source.displayName, source.mime)
            }
            // 临时源清理：cacheDir 下的 file:// 在成功导入后删除（裁剪输出/拍照临时件）
            cleanupTempSource(source)
            _events.tryEmit(InboxEvent.Imported(entry))
            entry
        } catch (e: InboxImportException) {
            _events.tryEmit(InboxEvent.Failed(described, e.message ?: "unknown"))
            throw e
        } catch (e: Throwable) {
            _events.tryEmit(InboxEvent.Failed(described, e.message ?: e::class.simpleName ?: "unknown error"))
            throw InboxImportException(described, e)
        }
    }

    /** 批量导入：逐个尝试，全部失败抛最后一个异常；部分成功返回成功项（失败已逐项上报 events）。 */
    suspend fun importAll(sources: List<InboxSource>): List<InboxEntry> {
        val ok = mutableListOf<InboxEntry>()
        var lastError: InboxImportException? = null
        for (source in sources) {
            try {
                ok += import(source)
            } catch (e: InboxImportException) {
                lastError = e
            }
        }
        if (ok.isEmpty() && lastError != null) throw lastError
        return ok
    }

    /** 按上传码解析（与 upload_fetch 同一解析器，不重建映射）。 */
    fun resolve(code: String): InboxEntry? =
        UploadRef.findFilesByCode(uploadDir, code).firstOrNull()?.let(::toEntry)

    /** 最近导入（mtime 倒序）。 */
    fun recent(limit: Int = 10): List<InboxEntry> =
        UploadRef.listRecent(uploadDir, limit).map(::toEntry)

    // ---- 内部 ----

    private fun toEntry(file: File): InboxEntry {
        val ref = UploadRef.fromPhysicalName(file.name)
        return InboxEntry(
            uri = file.toUri(),
            code = ref.code,
            hostPath = file.absolutePath,
            displayName = file.name,
            mime = guessMime(file),
        )
    }

    private fun importBytes(source: InboxSource.FromBytes): InboxEntry {
        if (source.bytes.isEmpty()) error("内容为空（0 字节）")
        val file = FileUtils.createUploadFile(
            ensureDir(),
            displayName = source.displayName,
            mimeType = source.mime,
        )
        file.writeBytes(source.bytes)
        return toEntry(file)
    }

    private fun importUri(uri: Uri, nameHint: String?, mimeHint: String?): InboxEntry {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("无法打开输入流（contentResolver 返回 null）")
        stream.use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty()) error("来源内容为空（0 字节）")
            val displayName = nameHint
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "file"
            val mime = mimeHint ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val file = FileUtils.createUploadFile(
                ensureDir(),
                displayName = displayName,
                mimeType = mime,
            )
            file.writeBytes(bytes)
            return toEntry(file)
        }
    }

    private fun ensureDir(): File = uploadDir.apply { if (!exists()) mkdirs() }

    private fun guessMime(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mp3", "m4a" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }

    /** 临时源清理：仅清 cacheDir 下的 file:// 文件（裁剪输出/相机临时件），绝不碰用户内容。 */
    private fun cleanupTempSource(source: InboxSource) {
        val path = when (source) {
            is InboxSource.FromUri ->
                if (source.uri.scheme == "file") source.uri.path else null
            is InboxSource.FromFile -> source.file.absolutePath
            is InboxSource.FromBytes -> null
        } ?: return
        val cacheRoot = context.cacheDir.canonicalPath
        runCatching {
            val canonical = File(path).canonicalPath
            if (canonical.startsWith(cacheRoot + File.separator)) {
                File(path).delete()
                Log.d(TAG, "cleaned temp source: $canonical")
            }
        }
    }

    private fun describe(source: InboxSource): String = when (source) {
        is InboxSource.FromUri -> source.uri.toString()
        is InboxSource.FromFile -> source.file.absolutePath
        is InboxSource.FromBytes -> "bytes(${source.bytes.size}B, ${source.displayName})"
    }
}
