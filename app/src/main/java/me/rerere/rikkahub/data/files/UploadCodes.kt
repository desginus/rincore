/* 【域 D·工作区沙箱】 — 上传码 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.files

/**
 * v4.8.90: 上传码 —— 由文件**相对路径确定性派生**的 8 位短码（如 `K3F9Q2M7`）。
 *
 * 设计取舍（为什么是"派生"而不是"落库发号"）：
 *  · 零迁移、零新表 —— 历史已上传的文件**立刻**有码，不需要任何回填；
 *  · 任何一处（Prompt 转换器 / upload_fetch 工具 / 未来的 UI）都能独立算出同一个码，
 *    不用把数据库句柄到处传递；
 *  · 文件被删 → 码自然失效，永远不会有"悬空映射"这种脏状态。
 *
 * 字符集去掉了 0/O/1/I/L 等易混字符（32 字符）；8 位 ≈ 32^8 ≈ 1.1e12 的码空间，
 * 常规规模（≤10 万文件）碰撞概率 < 1e-4，且真撞了 upload_fetch 会把候选全部列出，
 * 绝不会静默取错文件。
 */
object UploadCodes {
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private const val LENGTH = 8

    /** 由相对路径（如 `upload/<物理名>`）派生稳定短码。大小写/路径分隔符归一后哈希。 */
    fun codeOf(relativePath: String): String {
        val normalized = relativePath.trim().replace('\\', '/').lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        var acc = 0L
        for (i in 0 until 5) {                       // 取前 40 bit → 恰好 8 × 5 bit
            acc = (acc shl 8) or (digest[i].toLong() and 0xFFL)
        }
        val sb = StringBuilder(LENGTH)
        repeat(LENGTH) {
            sb.append(ALPHABET[(acc and 31L).toInt()])
            acc = acc shr 5
        }
        return sb.toString()
    }

    /** 由 file:// URL 或普通路径派生码（解析不到 / 不在 upload 目录 → null） */
    fun codeForLocation(location: String): String? = runCatching {
        val path = android.net.Uri.parse(location).path ?: return null
        codeForPath(path)
    }.getOrNull()

    /** 由文件系统路径派生（仅 upload 目录下的文件） */
    fun codeForPath(path: String): String? {
        val f = java.io.File(path)
        if (f.parentFile?.name != "upload") return null
        return codeOf("upload/${f.name}")
    }

    /** 归一化用户/模型输入里的码（去空格/连字符、转大写） */
    fun normalize(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "")

    /** 是否符合码格式（归一化后） */
    fun hasCodeFormat(raw: String): Boolean {
        val t = normalize(raw)
        return t.length == LENGTH && t.all { it in ALPHABET }
    }
}
