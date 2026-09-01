package me.rerere.rikkahub.utils


/* ───【自研】WorkspaceImageResolver.kt — 原版无此文件
 * v3.11.31: 工作区图片内联渲染 — workspace:// 地址解析器 (纯函数 + 单测覆盖)。
 * 前缀: workspace:// (大小写不敏感) / file://workspace/ (容错) / /workspace/ (裸挂载路径)。
 * percent-decode 宽松 (+ 为字面量, 非法 % 序列保原样); ../ 折叠逃逸一律拒绝。
 * ───────────────────────────────────────────────────────────────*/
import java.io.File

/** 支持的图片扩展名 (与 show_image 声明一致) */
val WORKSPACE_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

/** scheme/前缀是否为工作区地址 (前缀大小写不敏感, 路径本体大小写敏感) */
fun isWorkspaceUri(raw: String?): Boolean {
    if (raw == null) return false
    val lower = raw.lowercase().trim()
    return lower.startsWith("workspace://") ||
        lower.startsWith("file://workspace/")
        || raw.trim().startsWith("/workspace/")
}

/**
 * 宽松 percent-decode: 非法 % 序列原样保留不抛错; `+` 保持字面量 (只有查询串才转空格)。
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
 * 空路径 (指向根) → null。返回值已规范为 "/a/b/c" 形式 (Rootfs 内绝对路径)。
 */
fun resolveWorkspaceRelPath(raw: String): String? {
    val lower = raw.lowercase().trim()
    val rest: String = when {
        lower.startsWith("workspace://") -> raw.trim().substring("workspace://".length)
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
 * 单例 — 渲染链路同步入口。workspace root 来自 Koin WorkspaceRepository
 * (与 workspace_read_file 同一挂载语义: ROOTFS_WORKSPACE_DIR = "/workspace")。
 */
object WorkspaceImageResolver {
    @Volatile
    private var cachedRoot: String? = null

    /** 取当前默认 workspace root (首个 workspace; 与工具链同源) */
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
     * workspace:// 名称 → 宿主真实 File。任何失败 (越权/不存在/目录/非图片扩展名)
     * 一律返回 null, 不抛异常。
     */
    fun resolve(raw: String?): File? {
        if (raw == null) return null
        val rel = resolveWorkspaceRelPath(raw) ?: return null
        val root = currentRoot() ?: return null
        val manager = runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<me.rerere.workspace.WorkspaceManager>()
        }.getOrNull() ?: return null
        val file = manager.resolveRootfsFileSafe(root, rel) ?: return null
        val ext = file.extension.lowercase()
        return if (ext in WORKSPACE_IMAGE_EXTENSIONS) file else null
    }
}
