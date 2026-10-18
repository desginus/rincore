/* 【域 D·工作区沙箱】 — 上传文件四元组 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.files

/* ───【自研】UploadRef.kt — v4.8.189 上传文件四元组"单一解析器" (L-A3)
 * 背景: 上传文件的 {码, 物理名, 宿主路径, 沙箱路径} 关系此前在四处各自解析
 *  (DocumentAsPrompt.resolveUploadFile / UploadsListing Uri 截取 / 收件箱手搓前缀 /
 *   UploadFetchTool 扫目录) —— 同一概念四种写法, 演进必漂移 (v4.8.188 复核一处即缺口)。
 * 本文件收口为唯一权威: 一切上传文件的码/路径派生必须经此处, 别处不得自建。
 *     输入任意形态 (Document.url / file:/ 单斜杠 / percent 编码 / 沙箱路径 / 物理名)
 *         → 输出统一 Parsed {code, physicalName, hostFile, sandboxPath}
 * 全链: DocumentAsPromptTransformer(注入·消费) / UploadsListingTransformer(注记·消费) /
 *       SettingUploadInboxPage(标注·消费) / UploadFetchTool(码直取·消费) ｜
 *       命名落盘侧见 FileUtils.createUploadFile + UploadCodes.nextCode (原子取号)。
 * ───────────────────────────────────────────────────────────────*/

import java.io.File
import java.net.URLDecoder

object UploadRef {

    /** 四元组解析结果 — 一切字段由本文件派生, 调用方只读不拼。 */
    data class Parsed(
        val code: String,           // 上传码 (时间码; 旧 uuid 文件=8 位哈希码, 不断链)
        val physicalName: String,   // 落盘物理名 (含码前缀)
        val hostFile: File?,        // 宿主文件 (真实存在才非空; 虚拟形态=null)
        val sandboxPath: String,    // 沙箱内规范路径 /upload/<physicalName>
    )

    /**
     * Document.url (file:// 各形态: 三斜杠/单斜杠/percent 编码) → 四元组。
     * 仅认 upload 目录内的文件路径; 其他形态 (workspace:// 等虚拟地址) 返回 null。
     */
    fun resolveDocumentUrl(url: String): Parsed? {
        if (url.isBlank() || !url.startsWith("file:")) return null
        val decoded = decodeLenient(url)
        val path = when {
            decoded.startsWith("file://") -> decoded.removePrefix("file://")
            decoded.startsWith("file:") -> decoded.removePrefix("file:")
            else -> decoded
        }
        val idx = path.lastIndexOf("/upload/")
        if (idx < 0) return null
        val after = path.substring(idx + "/upload/".length).trimStart('/')
        if (after.isEmpty() || after.contains('/')) return null
        val file = File(path)
        if (file.parentFile?.name != "upload") return null
        return Parsed(
            code = UploadCodes.codeForFileName(file.name),
            physicalName = file.name,
            hostFile = file.takeIf { it.isFile },
            sandboxPath = "/upload/" + file.name,
        )
    }

    /** 物理名 (任意来源: DB relativePath 段 / ls 输出) → 四元组 (hostFile 交由调用方补)。 */
    fun fromPhysicalName(name: String): Parsed =
        Parsed(
            code = UploadCodes.codeForFileName(name),
            physicalName = name,
            hostFile = null,
            sandboxPath = "/upload/$name",
        )

    /** 按码查找 upload 目录内的文件 (norm 归一; 仅返命中的文件实体)。 */
    fun findFilesByCode(uploadDir: File, codeRaw: String): List<File> {
        val norm = UploadCodes.normalize(codeRaw)
        return uploadDir.listFiles()
            ?.filter { it.isFile && UploadCodes.codeForFileName(it.name) == norm }
            .orEmpty()
    }

    /** 最近上传 (按 mtime 倒序, 上限 limit)。 */
    fun listRecent(uploadDir: File, limit: Int = 10): List<File> =
        uploadDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            .orEmpty()

    /** percent 解码: 只在含 '%' 时解, 并保护 '+' (文件名的 '+' 是实义字符, URLDecoder 默认会吞)。 */
    private fun decodeLenient(raw: String): String =
        if ('%' !in raw) raw
        else runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
}
