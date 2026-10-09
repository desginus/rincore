/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext


/* ───【自研】WorkspaceImageFetch.kt — 原版无此文件
 * v3.11.31: workspace:// 图片走 rootfs 文件直接解码。
 * v4.8.115 (用户定版"渲染地址统一、单一解码点"): 本文件是全部"应用本地图片地址"
 * 的唯一 Coil 认领层 —— workspace:// 虚拟形态 / host workspaces 锚点形态 /
 * file:// 应用私有 (编码或裸) / 裸 host 路径, 统一经 isAppLocalImageUri 认领 +
 * resolveLocalImageFile 解析, 一个 Fetcher 实现, 所有本地文件统一 mtime+size 缓存键。
 *
 * 地址形态契约 (与 ToolImagePayload.buildRenderUrl 对齐):
 *   规范形态 = percent 编码 file:///data/data/<pkg>/files/... (产出层唯一形态);
 *   解码收口在本文件 decodeLocalFilePath (percentDecodeLenient, 回环有单测);
 *   其余一切位置 (markdown 节点 / 工具段缩略图 / 提取器) 只透传字符串, 不自行转换。
 * 解析失败/文件不存在 → 不认领或落占位符, 不崩溃。
 * API 口径按 coil3 3.5.0: Keyer / ImageSource(path, fileSystem) / toPath()。
 * ───────────────────────────────────────────────────────────────*/
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import me.rerere.rikkahub.utils.WorkspaceImageResolver
import me.rerere.rikkahub.utils.isAppPrivateFileUri
import me.rerere.rikkahub.utils.isVirtualWorkspaceUri
import me.rerere.rikkahub.utils.normalizeDataUserAlias
import me.rerere.rikkahub.utils.normalizeHostWorkspacePath
import me.rerere.rikkahub.utils.percentDecodeLenient
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * v4.8.115: 本地地址解码 (纯函数, 无文件系统访问, 单测覆盖)。
 * file:// 剥 scheme → 裸路径原样 → 含 % 才 percentDecodeLenient (回环安全,
 * 非法 % 序列原样保留) → /data/user/0 别名折叠为 /data/data。
 * 返回 null = 不认识的形态 (file:/ 单斜杠、相对路径等不猜)。
 */
internal fun decodeLocalFilePath(data: String): String? {
    val t = data.trim()
    val withoutScheme = when {
        t.length >= 7 && t.substring(0, 7).equals("file://", ignoreCase = true) ->
            t.substring(7)
        t.length >= 5 && t.substring(0, 5).equals("file:", ignoreCase = true) -> return null
        t.startsWith("/") -> t
        else -> return null
    }
    val decoded = if ('%' in withoutScheme) percentDecodeLenient(withoutScheme) else withoutScheme
    return normalizeDataUserAlias(decoded)
}

/**
 * v4.8.115: 应用私有目录路径判定 (规范化后)。
 * normalizeDataUserAlias 已把 /data/user/0/ 别名折叠, 只判 /data/data/<pkg>/files|cache。
 */
internal fun isAppPrivatePath(
    path: String,
    pkg: String = me.rerere.rikkahub.BuildConfig.APPLICATION_ID,
): Boolean {
    val lower = normalizeDataUserAlias(path.trim()).lowercase()
    val p = pkg.lowercase()
    return lower.startsWith("/data/data/$p/files/") || lower.startsWith("/data/data/$p/cache/")
}

/**
 * v4.8.115: 认领判定 — 是否为"应用本地图片地址"。
 * 覆盖: workspace 虚拟形态 (workspace:// 等) / host workspaces 锚点形态 /
 * file:// 应用私有 (编码或裸) / 裸 host 私有路径。
 * http(s) 与外部存储 file:// 不认领 (http 走网络 fetcher, 外部存储维持 Coil 内建行为)。
 */
internal fun isAppLocalImageUri(data: String): Boolean {
    if (isVirtualWorkspaceUri(data) || normalizeHostWorkspacePath(data) != null) return true
    if (isAppPrivateFileUri(data)) return true
    val path = decodeLocalFilePath(data) ?: return false
    return isAppPrivatePath(path)
}

/**
 * v4.8.115: 统一解析 — 本地图片地址 → 宿主 File (全应用唯一解码点)。
 * 1) workspace 语义形态 (虚拟前缀 or host workspaces 锚点) → WorkspaceImageResolver
 *    (多工作区遍历 + 双前缀自愈, 语义与历史一致);
 * 2) 私有目录 file:// / 裸路径 → 解码 + 存在性检查后直取 File。
 * 返回 null = 不认领 (调用方落默认行为/占位符)。
 */
internal fun resolveLocalImageFile(data: String): java.io.File? {
    // 1) 虚拟 workspace 形态只有 resolver 能解析 (rootfs→host 映射 + 多工作区遍历)
    if (isVirtualWorkspaceUri(data)) {
        return WorkspaceImageResolver.resolve(data)
    }
    // 2) host 形态: 先走 resolver (workspaces 锚点 + 双前缀自愈); resolver 因
    //    图片扩展名白名单拒绝 (svg/heic/avif 等) 或未命中时, 降级为解码直读 —
    //    文件真在应用私有目录下 (buildRenderUrl 产出的就是宿主字面路径)。
    WorkspaceImageResolver.resolve(data)?.let { return it }
    val path = decodeLocalFilePath(data) ?: return null
    if (!isAppPrivatePath(path)) return null
    val f = java.io.File(path)
    return if (f.isFile) f else null
}

/** 本地文件统一版本化缓存键 (mtime+size — 同名覆盖后强制重新加载) */
private fun versionedKey(prefix: String, file: java.io.File): String =
    "$prefix:" + file.absolutePath + ":" + file.lastModified() + ":" + file.length()

/**
 * 本地图片缓存键 — v4.8.115 起认领口径与 Fetcher 一致 (isAppLocalImageUri),
 * file:// 应用私有 (上传图/工具产图) 与 workspace:// 一样获得 mtime+size 键。
 * 解析失败返回 null (Coil 用默认 key, 失败态稳定, 不触发重试抖动)。
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
