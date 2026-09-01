package me.rerere.rikkahub.ui.components.richtext


/* ───【自研】WorkspaceImageFetch.kt — 原版无此文件
 * v3.11.31: Coil3 自定义数据源 — workspace:// 图片走 rootfs 文件直接解码。
 * cache key 含 mtime+size (同名覆盖更新后显示新图); 解码失败走占位符不崩溃。
 * ───────────────────────────────────────────────────────────────*/
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import me.rerere.rikkahub.utils.WORKSPACE_IMAGE_EXTENSIONS
import me.rerere.rikkahub.utils.WorkspaceImageResolver
import me.rerere.rikkahub.utils.isWorkspaceUri
import me.rerere.rikkahub.utils.resolveWorkspaceRelPath
import okio.FileSystem

/**
 * workspace:// 的 cache key — 含宿主文件 mtime+size, 同名覆盖后强制新加载。
 * 解析失败时返回 null (Coil 用默认 key, 失败态同样稳定, 不触发重试抖动)。
 */
class WorkspaceUriKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String? {
        if (!isWorkspaceUri(data)) return null
        val file = WorkspaceImageResolver.resolve(data) ?: return null
        return "workspace:" + file.absolutePath + "?m=" + file.lastModified() + "&s=" + file.length()
    }
}

/**
 * workspace:// Fetcher 工厂 — 解析成功且为受支持的图片扩展名时接管加载。
 * 大图由 Coil 自带降采样; GIF/WebP 动图由已注册的 AnimatedImageDecoder 处理。
 */
class WorkspaceImageFetcherFactory : Fetcher.Factory<String> {
    override fun create(
        data: String,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        if (!isWorkspaceUri(data)) return null
        // 扩展名前置校验: 非图片扩展名不加载 (.txt/.md → 占位符)
        val rel = resolveWorkspaceRelPath(data)
        val ext = rel?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext !in WORKSPACE_IMAGE_EXTENSIONS) return null
        val file = WorkspaceImageResolver.resolve(data) ?: return null
        val mime = when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> return null
        }
        return WorkspaceImageFetcher(file, mime)
    }
}

private class WorkspaceImageFetcher(
    private val file: java.io.File,
    private val mime: String,
) : Fetcher {
    override suspend fun fetch(): coil3.fetch.FetchResult {
        // 文件加载中途被删 → source 读写抛错 → Coil error 态 → 占位符, 不崩
        val okioPath = okio.Path.of(file.absolutePath)
        val buffered = FileSystem.SYSTEM.source(okioPath)
        return SourceFetchResult(
            source = ImageSource(buffered, FileSystem.SYSTEM),
            mimeType = mime,
            dataSource = DataSource.DISK,
        )
    }
}
