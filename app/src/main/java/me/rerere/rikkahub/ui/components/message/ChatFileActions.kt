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

/* ─── v5.0.16: 从原 ChatMessageEditedFiles 迁出（上游化后本文件承接）─── */
/** v4.8.63: 胶囊窗文件条目 — 展示用原始路径 + 工具执行回执中已验证的解析路径。 */
internal data class ChipFile(val raw: String, val resolvedHint: String?)

/** v4.8.63 (用户定版) 规范重写: 胶囊窗文件解析。
 *  背景: CWD 只用于模型产物整理规划, 不是沙箱锁; 模型给出的地址可能为回执形式、
 *  全根绝对、scope 相对 (/workspace/... 含或不含 CWD 段) 或裸相对, 且项目包 CWD
 *  可能已变更 → 旧地址错位 ("File does not exist", 用户实证)。
 *  策略: ①回执解析路径 → ②原始输入 × {项目包 CWD、助手 CWD、根} 多候选直取 →
 *  ③仍未命中则按文件名全库模糊匹配 (后缀深度择优; 仍并列 → 交由调用方弹窗让用户
 *  选择, 不报错)。解析产物: 全根语义 Rootfs 绝对路径 (导出统一 cwd=null 消费)。 */
internal sealed interface ScopedResolution {
    data class Resolved(val rootfsPath: String) : ScopedResolution
    data class Ambiguous(val candidates: List<String>) : ScopedResolution
    data class NotFound(val detail: String) : ScopedResolution
}

/** v4.8.57: CWD 归一化 (同 WorkspaceTools 口径), null/空 = 无约束。
 *  接受助手级 workspaceCwd 与项目包 cwd (含 "/workspace/xxx" 绝对形态)。 */
internal fun normalizeCwdRel(raw: String?): String? {
    val trimmed = raw?.trim('/') ?: return null
    val rel = (if (trimmed == "workspace") "" else trimmed.removePrefix("workspace/")).trim('/')
    return rel.ifBlank { null }
}

internal suspend fun resolveScopedFile(
    repository: WorkspaceRepository,
    workspaceId: String,
    chip: ChipFile,
    cwdCandidates: List<String>,
): ScopedResolution {
    val probes = LinkedHashSet<String>()
    fun addPathCandidate(p: String?) {
        val v = p?.trim().orEmpty()
        if (v.isEmpty()) return
        if (!v.startsWith("/")) {
            // 裸相对: 按 CWD 自动拼接 (项目包 → 助手 → 根)
            for (cwd in cwdCandidates) probes.add("/workspace/" + cwd.trim('/') + "/" + v)
            probes.add("/workspace/$v")
        } else {
            probes.add(v)
            if (v.startsWith("/workspace/")) {
                // scope 相对形: "/workspace/xxx" 在旧 CWD 下实际位于 /workspace/<cwd>/xxx
                val rel = v.removePrefix("/workspace/")
                for (cwd in cwdCandidates) probes.add("/workspace/" + cwd.trim('/') + "/" + rel)
            }
        }
    }
    addPathCandidate(chip.resolvedHint)
    addPathCandidate(chip.raw)

    suspend fun exists(p: String): Boolean =
        runCatching { repository.rootfsFileSize(workspaceId, p, null); true }.getOrDefault(false)
    for (p in probes) if (exists(p)) return ScopedResolution.Resolved(p)

    // ③ 模糊: 按文件名全库查找 (排除版本快照), 后缀深度择优
    val base = chip.raw.substringAfterLast('/').trim()
    if (base.isEmpty()) return ScopedResolution.NotFound("路径无效: ${chip.raw}")
    val safeName = base.replace("'", "")
    val stdout = runCatching {
        repository.executeCommand(
            workspaceId,
            "find /workspace -type f -name '$safeName' -not -path '*/.versions/*' 2>/dev/null | head -50",
            "",
            me.rerere.workspace.WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        ).stdout.orEmpty()
    }.getOrDefault("")
    val hits = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("/workspace/") }.distinct().toList()
    if (hits.isEmpty()) return ScopedResolution.NotFound("未找到文件: ${chip.raw}")
    if (hits.size == 1) return ScopedResolution.Resolved(hits[0])
    val rawSegs = chip.raw.split('/').filter { it.isNotBlank() }
    fun suffixDepth(hit: String): Int {
        val hitSegs = hit.split('/').filter { it.isNotBlank() }
        var d = 0
        while (d < rawSegs.size && d < hitSegs.size &&
            rawSegs[rawSegs.size - 1 - d] == hitSegs[hitSegs.size - 1 - d]
        ) d++
        return d
    }
    val maxDepth = hits.maxOf { suffixDepth(it) }
    val best = hits.filter { suffixDepth(it) == maxDepth }.sorted()
    return if (best.size == 1) ScopedResolution.Resolved(best[0]) else ScopedResolution.Ambiguous(best)
}
