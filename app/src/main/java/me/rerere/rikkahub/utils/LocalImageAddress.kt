/* 【域 L·基础设施】 | 地图: docs/APP_MAP.md §L */
package me.rerere.rikkahub.utils

/* ───【自研】LocalImageAddress.kt — v4.8.185 本地图片地址"单一解析点" (跨层)
 * 从 WorkspaceImageFetch (Coil 认领层) 迁出为 utils 纯函数 — 让 Coil 层、
 * 尺寸预读 (StableSizeImage)、生成完成固化器 (MessageImageSnapshotTransformer)
 * 共用同一条解析链, 消除"三处解析能力不一致"缺陷 (v4.8.185 审计 M4)。
 *
 * 地址形态契约 (与 ToolImagePayload.buildRenderUrl 对齐):
 *   规范形态 = percent 编码 file:///data/data/<pkg>/files/...
 *   解码收口 = decodeLocalFilePath (percentDecodeLenient, 回环有单测);
 *   其余一切位置只透传字符串, 不自行转换。
 * 全链: WorkspaceImageFetch(Coil 认领·消费) / StableSizeImage(尺寸预读·消费) /
 *       MessageImageSnapshotTransformer(固化器·消费) / FilesManager(保存图片 saveMessageImage·消费) /
 *       Export(导出内联·消费) ｜ 直引 WorkspaceImageResolver 的专项点不属本链:
 *       SandboxBridgeServer(/render 桥) / WorkspaceLinkLauncher(任意文件打开) / ZoomableAsyncImage(失败诊断)。
 * ───────────────────────────────────────────────────────────────*/

/**
 * v4.8.115: 本地地址解码 (纯函数, 无文件系统访问, 单测覆盖)。
 * file:// 剥 scheme → 裸路径原样 → 含 % 才 percentDecodeLenient (回环安全,
 * 非法 % 序列原样保留) → /data/user/0 别名折叠为 /data/data。
 * v4.8.129: 接纳 file:/ 单斜杠形态 — java.io.File.toUri() 的产出 (上传图/MCP 图
 * 等 "file:/data/user/0/..." 且中文名被 percent 编码); 此前"不猜"导致保存链
 * Uri.toFile() 不解码 → 中文名文件"保存失败"。
 * 返回 null = 不认识的形态 (裸 file: 无斜杠、相对路径等不猜)。
 */
internal fun decodeLocalFilePath(data: String): String? {
    val t = data.trim()
    val withoutScheme = when {
        t.length >= 7 && t.substring(0, 7).equals("file://", ignoreCase = true) ->
            t.substring(7)
        // v4.8.129: file:/ 单斜杠 (File.toUri() 产出形态) — 保留其 "/" 后走统一解码
        t.length >= 6 && t.substring(0, 6).equals("file:/", ignoreCase = true) ->
            t.substring(5)
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
internal fun versionedKey(prefix: String, file: java.io.File): String =
    "$prefix:" + file.absolutePath + ":" + file.lastModified() + ":" + file.length()
