package me.rerere.rikkahub.utils


/* ───【自研】WorkspaceImageResolver.kt — 原版无此文件
 * v3.11.31: 工作区图片内联渲染 — workspace:// 地址解析器 (纯函数 + 单测覆盖)。
 * v3.11.32 实测修复: resolver→manager 转发路径语义断裂 — resolveRootfsPath 的
 * filesDir 分支只认 "/workspace" 开头的 Rootfs 内路径, 旧实现转发 "/x.png" 落
 * 到 linuxDir 必然 not_found (三前缀一致失败的真实死点, 非跨进程问题)。
 * 现转发拼接 ROOTFS_WORKSPACE_DIR + rel, 并遍历全部 workspace 求命中。
 * ───────────────────────────────────────────────────────────────*/
import java.io.File

/** 支持的图片扩展名 (与 show_image 声明一致) */
val WORKSPACE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/** scheme/前缀是否为工作区地址 (前缀大小写不敏感, 路径本体大小写敏感) */
fun isWorkspaceUri(raw: String?): Boolean {
    if (raw == null) return false
    val lower = raw.lowercase().trim()
    val bare = raw.trim()
    return lower.startsWith("workspace://") ||
        lower.startsWith("file:///workspace/") ||
        lower.startsWith("file://workspace/") ||
        bare.startsWith("/workspace/")
}

/**
 * 宽松 percent-decode: 非法 % 序列原样保留不抛错; `+` 保持字面量 (只有查询串才转空格)。
 * 多字节按 UTF-8 重组 (中文目录名不失真)。
 */
fun percentDecodeLenient(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder(s.length)
    val byteBuf = java.io.ByteArrayOutputStream()
    fun flushBytes() {
        if (byteBuf.size() > 0) {
            out.append(byteBuf.toString("UTF-8"))
            byteBuf.reset()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%') {
            val hi = s.getOrNull(i + 1)?.digitToIntOrNull(16)
            val lo = s.getOrNull(i + 2)?.digitToIntOrNull(16)
            if (hi != null && lo != null) {
                byteBuf.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        flushBytes()
        out.append(c)
        i++
    }
    flushBytes()
    return out.toString()
}

/**
 * 解析 workspace:// 类地址 → Rootfs 内相对路径 (不含前缀)。
 * 折叠 // 与 .;`..` 在栈空时弹出 = 目录穿越 → 返回 null。
 * 空路径 (指向根) → null。返回值已规范为 "/a/b/c" 形式。
 */
fun resolveWorkspaceRelPath(raw: String): String? {
    val lower = raw.lowercase().trim()
    val rest: String = when {
        lower.startsWith("workspace://") -> raw.trim().substring("workspace://".length)
        lower.startsWith("file:///workspace/") -> raw.trim().substring("file:///workspace/".length)
        lower.startsWith("file://workspace/") -> raw.trim().substring("file://workspace/".length)
        raw.trim().startsWith("/workspace/") -> raw.trim().substring("/workspace/".length)
        else -> return null
    }
    if (rest.isBlank()) return null
    val decoded = percentDecodeLenient(rest)
    val stack = ArrayDeque<String>()
    for (seg in decoded.split('/')) {
        when (seg) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeLast() else return null
            else -> stack.addLast(seg)
        }
    }
    if (stack.isEmpty()) return null
    return "/" + stack.joinToString("/")
}

/**
 * 单例 — 渲染链路同步入口。Koin WorkspaceRepository / WorkspaceManager 与
 * workspace_read_file 同源 (单进程, baseDir = filesDir/workspaces)。
 */
object WorkspaceImageResolver {
    private const val TAG = "WorkspaceImage"

    @Volatile
    private var cachedRoot: String? = null

    /** 取默认 workspace root (首个; 仅用于日志诊断) */
    fun currentRoot(): String? {
        cachedRoot?.let { return it }
        return runCatching {
            val koin = org.koin.core.context.GlobalContext.get()
            val repo = koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
            val ws = runCatching { kotlinx.coroutines.runBlocking { repo.getAllWorkspaces() } }
                .getOrNull()?.firstOrNull()
            ws?.root?.also { cachedRoot = it }
        }.getOrNull()
    }

    /** 强制下次重新读 root (workspace 删除/新建后调用) */
    fun invalidateRoot() {
        cachedRoot = null
    }

    /**
     * 全链路解析: 返回 (宿主文件, "ok") 或 (null, 失败环节标签)。
     * 失败环节: empty_input / prefix_not_recognized / invalid_path / no_workspace /
     * not_found / extension_rejected — 供 show_image 分级文案与日志定位。
     * 多 workspace 环境遍历全部 root 求命中 (同名文件以首个命中为准)。
     */
    fun resolveDetailed(raw: String?): Pair<File, String>? {
        if (raw.isNullOrBlank()) return null to "empty_input"
        val rel = resolveWorkspaceRelPath(raw)
            ?: return null to if (isWorkspaceUri(raw)) "invalid_path" else "prefix_not_recognized"
        val ext = rel.substringAfterLast('.', "").lowercase()
        if (ext !in WORKSPACE_IMAGE_EXTENSIONS) return null to "extension_rejected:$ext"

        val koin = runCatching { org.koin.core.context.GlobalContext.get() }.getOrNull()
            ?: return null to "no_workspace"
        val repo = runCatching { koin.get<me.rerere.rikkahub.data.repository.WorkspaceRepository>() }.getOrNull()
            ?: return null to "no_workspace"
        val manager = runCatching { koin.get<me.rerere.workspace.WorkspaceManager>() }.getOrNull()
            ?: return null to "no_workspace"
        val workspaces = runCatching {
            kotlinx.coroutines.runBlocking { repo.getAllWorkspaces() }
        }.getOrNull()
        if (workspaces.isNullOrEmpty()) return null to "no_workspace"

        // 转发路径 = ROOTFS_WORKSPACE_DIR 常量拼接 (语义: workspace:// 永远指
        // Rootfs 内 /workspace 文件区) — v3.11.32 死点修复
        val rootfsPath = me.rerere.workspace.WorkspaceManager.ROOTFS_WORKSPACE_DIR + rel
        var lastHostGuess: File? = null
        for (ws in workspaces) {
            val hit = manager.resolveRootfsFileSafe(ws.root, rootfsPath)
            if (hit != null) {
                android.util.Log.i(
                    TAG,
                    "pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} " +
                        "ok ws=${ws.root.take(8)} host=${hit.canonicalPath} " +
                        "mtime=${hit.lastModified()} size=${hit.length()}"
                )
                cachedRoot = ws.root
                return hit to "ok"
            }
            // 记录最后尝试的宿主猜测路径 (linuxDir 误落诊断用)
            runCatching {
                lastHostGuess = File(
                    manager.filesDir(ws.root).parentFile, "linux" + rel
                )
            }
        }
        android.util.Log.w(
            TAG,
            "miss path=$rootfsPath reason=not_found " +
                "workspaces=${workspaces.size} fallbackGuess=${lastHostGuess?.absolutePath} " +
                "fallbackExists=${lastHostGuess?.exists()}"
        )
        return null to "not_found"
    }

    /** 简单入口: 成功返回宿主 File, 失败返回 null (Coil Fetcher 用) */
    fun resolve(raw: String?): File? = resolveDetailed(raw)?.first
}
