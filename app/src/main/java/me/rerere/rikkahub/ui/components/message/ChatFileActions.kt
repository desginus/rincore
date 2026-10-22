/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.message

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import java.io.File

/**
 * v4.8.129 (用户定版): 对话内文件交互三件套统一核心 — **[导出 / 分享 / 渲染]**。
 * 上传文件胶囊 / 编辑过的文件 / 本对话产出 三处逻辑路径完全一致 (单一实现):
 *
 *   ① 统一前置 = 拿到一个"本地文件":
 *      - 上传胶囊: 文件本就在本地 (upload 目录), 直接使用;
 *      - 工作区来源 (编辑过的文件 / 本对话产出): resolveScopedFile 解析 (多候选
 *        直取 → 模糊 → 多命中弹层, v4.8.63 同源) → [materializeWorkspaceFile]
 *        物化到缓存 — 导出/分享/渲染共用同一物化步。
 *   ② 导出 = 系统另存 (CreateDocument) → [exportLocalFileTo] 流拷贝;
 *   ③ 分享 = [shareLocalFile] FileProvider → ACTION_SEND 选择器 (octet-stream,
 *      与「编辑过的文件」既有实现逐字同源);
 *   ④ 渲染 = RenderEngine.renderSmart(本地文件) → RenderViewDialog (UI 态在调用方)。
 *
 * 回滚记录: v4.8.125 曾把三件套改成 [渲染/导出/用其他应用打开] — 「打开」与
 * 分享重复且解析链不同源 (openWorkspaceLink), 用户实证"提示文档不存在"假失败;
 * 本版按用户指令整体回滚为 [导出/分享/渲染] 三件套。
 */

/**
 * 工作区解析产物 → 本地缓存文件 (分享/导出/渲染统一物化步; 覆盖式写入)。
 * [resolvedRootfsPath] = resolveScopedFile 产出的全根语义路径。
 */
internal suspend fun materializeWorkspaceFile(
    context: Context,
    workspaceRepository: WorkspaceRepository,
    workspaceId: String,
    resolvedRootfsPath: String,
    cacheSubDir: String = "file_actions",
): File {
    val dir = File(context.cacheDir, cacheSubDir).apply { mkdirs() }
    val file = File(dir, resolvedRootfsPath.substringAfterLast('/'))
    file.outputStream().use { output ->
        exportResolvedFile(workspaceRepository, workspaceId, resolvedRootfsPath, output)
    }
    return file
}

/**
 * v5.0.62：宿主 File 直接物化 —— 产出记录现在带 `hostPath`，这里一次复制即可。
 * 不再走 `exportResolvedFile`（那条链要 workspaceId + rootfs 语义 + CWD 推断）。
 */
internal fun materializeHostFile(
    context: Context,
    hostFile: File,
    cacheSubDir: String = "file_actions",
): File {
    val dir = File(context.cacheDir, cacheSubDir).apply { mkdirs() }
    val out = File(dir, hostFile.name)
    hostFile.inputStream().use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    }
    return out
}

/** 导出: 本地文件 → 系统另存目标 (统一入口; 失败抛异常由调用方 toast) */
internal fun exportLocalFileTo(context: Context, file: File, target: Uri) {
    if (!file.isFile) error("文件不存在")
    val out = context.contentResolver.openOutputStream(target) ?: error("无法写入目标位置")
    out.use { output -> file.inputStream().use { input -> input.copyTo(output) } }
}

/** 分享: 本地文件 → FileProvider → ACTION_SEND 选择器 (统一入口) */
internal fun shareLocalFile(context: Context, file: File) {
    if (!file.isFile) error("文件不存在")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
