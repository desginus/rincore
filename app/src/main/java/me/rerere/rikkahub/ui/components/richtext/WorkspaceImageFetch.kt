/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext


/* ───【自研】WorkspaceImageFetch.kt — 原版无此文件
 * = Coil 认领层 (应用本地图片地址的唯一 Fetcher / Keyer 注册面)。
 * v3.11.31: workspace:// 图片走 rootfs 文件直接解码。
 * v4.8.115: 认领口径统一 (workspace:// 虚拟 / host workspaces 锚点 / file:// 私有 /
 *           裸 host 路径), 所有本地文件统一 mtime+size 缓存键。
 * v4.8.185 (审计重写): 纯解析函数 (decodeLocalFilePath / isAppPrivatePath /
 *           isAppLocalImageUri / resolveLocalImageFile / versionedKey) 迁出至
 *           utils.LocalImageAddress — 与尺寸预读 (StableSizeImage)、生成完成
 *           固化器 (MessageImageSnapshotTransformer) 共用同一条"单一解析点"链,
 *           消除"多处解析能力不一致"缺陷 (v4.8.185 审计 M4)。本文件只保留 Coil 接线。
 * 地址形态契约与解码口径 (唯一权威) 见: utils/LocalImageAddress.kt。
 * API 口径按 coil3 3.5.0: Keyer / ImageSource(path, fileSystem) / toPath()。
 * ───────────────────────────────────────────────────────────────*/
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import me.rerere.rikkahub.utils.isAppLocalImageUri
import me.rerere.rikkahub.utils.resolveLocalImageFile
import me.rerere.rikkahub.utils.versionedKey
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * 本地图片缓存键 — v4.8.115 起认领口径与 Fetcher 一致 (isAppLocalImageUri),
 * file:// 应用私有 (上传图/工具产图/对话媒体快照) 与 workspace:// 一样获得
 * mtime+size 键。解析失败返回 null (Coil 用默认 key, 失败态稳定, 不触发重试抖动)。
 */
class WorkspaceUriKeyer : Keyer<String> {
    override fun key(data: String, options: Options): String? {
        if (!isAppLocalImageUri(data)) return null
        val file = resolveLocalImageFile(data) ?: return null
        return versionedKey("local", file)
    }
}

/**
 * 本地图片 Fetcher 工厂 — v4.8.115 起按统一口径认领并解析 (唯一解码点)。
 * 全部尝试解码不按扩展名拒绝 (v3.19.0 用户定版放宽); mime 仅 svg 需要显式提示,
 * 其余交给解码器按内容嗅探 (gif/heic/avif/未知格式均宽松处理)。
 */
class WorkspaceImageFetcherFactory : Fetcher.Factory<String> {
    override fun create(
        data: String,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        if (!isAppLocalImageUri(data)) return null
        val file = resolveLocalImageFile(data) ?: return null
        return LocalImageFetcher(file)
    }
}

private fun mimeHintFor(file: java.io.File): String? =
    when (file.extension.lowercase()) {
        "svg" -> "image/svg+xml"
        else -> null
    }

private class LocalImageFetcher(
    private val file: java.io.File,
) : Fetcher {
    override suspend fun fetch(): coil3.fetch.FetchResult {
        // 文件加载中途被删 → 读取抛错 → Coil error 态 → 占位符, 不崩。
        val okioPath = file.absolutePath.toPath()
        return SourceFetchResult(
            source = ImageSource(okioPath, FileSystem.SYSTEM),
            mimeType = mimeHintFor(file),
            dataSource = DataSource.DISK,
        )
    }
}

/**
 * v4.8.2: 构建带"文件版本"缓存键的 ImageRequest。
 * v4.8.115: 键策略统一到 isAppLocalImageUri 认领口径 — 所有本地图片
 * (workspace:// / file:// 私有 / 裸路径) 均含宿主文件 mtime+size,
 * 同路径覆盖后强制重新解码, 不再命中旧缓存。非本地图零行为变化。
 */
fun buildVersionedImageRequest(
    context: android.content.Context,
    model: String?,
    configure: (coil3.request.ImageRequest.Builder.() -> Unit)? = null,
): coil3.request.ImageRequest {
    val builder = coil3.request.ImageRequest.Builder(context).data(model)
    configure?.invoke(builder)
    if (model != null && isAppLocalImageUri(model)) {
        val f = resolveLocalImageFile(model)
        if (f != null) {
            val vKey = versionedKey("local", f)
            builder.memoryCacheKey(vKey)
            builder.diskCacheKey(vKey)
        }
    }
    return builder.build()
}
