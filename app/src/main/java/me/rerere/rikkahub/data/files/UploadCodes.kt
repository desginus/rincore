/* 【域 D·工作区沙箱】 — 上传码 | 地图: docs/APP_MAP.md §D */
package me.rerere.rikkahub.data.files

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * v4.8.91: 上传码 v2 —— **时间码**。
 * 全链: FileUtils.createUploadFile(命名落盘·写入) / DocumentAsPrompt(<UploadFile code> 注入·消费) /
 *       UploadFetchTool(码直取·消费) / UploadsListingTransformer + 收件箱(注记与使用标注·消费)。
 *
 * 格式：`月日时分`（MMddHHmm，8 位）+ `顺序补位`（≥2 位），共 10 位起；24 小时制、固定东八区（Asia/Shanghai）：
 *   · 10 月 3 日 00:29 的第 1 个上传 → `1003002901`（00 时 = 24 小时制的 00 点）
 *   · 同一分钟的后续上传 → 补位 02、03…（按落盘顺序，扫描目录取 max+1，并发安全）
 *
 * 码直接写进**文件名的前缀**（`<码>_<原文件名>`，如 `1003002901_报告.pdf`）：
 *  · 没有数据库、没有新表 —— 文件名即映射；删文件即失效，永无脏映射；
 *  · 沙箱里一眼可读：模型 `ls /upload` 看到的就是"什么时候传的、叫什么"；
 *  · 与"直取"完全兼容：upload_fetch 解析的就是这份前缀。
 *
 * 历史文件（v4.8.90 及更早的 uuid 命名）没有时间前缀，回退到旧的 8 位哈希码
 * （[legacyCodeOf]），保证旧引用不断链。
 *
 * 时间只由东八区钟面生成：SimpleDateFormat 显式绑定 Asia/Shanghai，
 * 不依赖设备/沙箱的时区设置（与沙箱时钟"严格东八区"同一条纪律）。
 */
object UploadCodes {
    private const val CN_TZ_ID = "Asia/Shanghai"

    /** 新命名：<8 位 月日时分><2+ 位补位>_<原名>（实际总位数 = 10 起）
     *  v4.8.137 根修：旧正则误写为 `^(\d{10})(\d{2,3})_`（要求 12-13 位数字），
     *  与实际 10 位文件名永不匹配 → 扫描永远取不到 max → **同分钟所有同 basename
     *  文件（工具图恒为 image.png）全部拿 01 号 → 同一文件被覆盖，多个工具结果
     *  指向同一张图**（用户 100% 复现实证：三连读图全部返回第一张且共用同一
     *  image_id）。同时 codeForFileName/hasCodeFormat 按 "12 位" 写的校验也一并
     *  修正（此前 upload_fetch 用可见的文件名前缀反而匹配不到）。 */
    private val NEW_NAME = Regex("^(\\d{8})(\\d{2,})_")

    /** 补位分配锁 —— 同一分钟并发上传不撞号 (v4.8.134: 与 FileUtils.createUploadFile 共用, 取号+创建原子) */
    internal val seqLock = Any()

    /** 历史码字符集（去易混字符 0/O/1/I/L） */
    private const val LEGACY_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    private fun formatPrefix(nowMs: Long): String =
        SimpleDateFormat("MMddHHmm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone(CN_TZ_ID) }
            .format(Date(nowMs))

    /** 生成下一个上传码（东八区钟面 + 目录扫描取序）。 */
    fun nextCode(uploadDir: File, nowMs: Long = System.currentTimeMillis()): String =
        synchronized(seqLock) { nextCodeLocked(uploadDir, nowMs) }

    /**
     * v4.8.134 (B155 根修): 无锁版取号 — 仅供已经持有 [seqLock] 的"取号+创建文件"
     * 原子流程内部使用 (FileUtils.createUploadFile)。原 nextCode 的锁只护住扫描取号,
     * 从返回名字到调用方 createNewFile 之间存在竞态窗口: 并行工具 (同时读多张图 →
     * 各自复制到 upload) 会双双拿到同一个号 → 同一文件被两次写入, 后写覆盖先写 →
     * 两个工具结果指向同一张图 (用户实证: 读 9-1/9-2 得到同一张图, 并行读图时高发)。
     * 现在取号与创建在同一临界区内完成, 下一个调用必然看到已创建的文件。
     */
    internal fun nextCodeLocked(uploadDir: File, nowMs: Long = System.currentTimeMillis()): String {
        if (!uploadDir.exists()) uploadDir.mkdirs()
        val prefix = formatPrefix(nowMs)
        var max = 0
        uploadDir.listFiles()?.forEach { f ->
            val m = NEW_NAME.find(f.name) ?: return@forEach
            if (m.groupValues[1] == prefix) {
                val n = m.groupValues[2].toIntOrNull() ?: 0
                if (n > max) max = n
            }
        }
        return prefix + "%02d".format(Locale.US, max + 1)
    }

    /** 文件名 → 码（新命名解码时间码；旧命名回退历史 8 位码，旧引用不断链）。 */
    fun codeForFileName(name: String): String {
        NEW_NAME.find(name)?.let { m ->
            return m.groupValues[1] + m.groupValues[2].padStart(2, '0')
        }
        return legacyCodeOf("upload/$name")
    }

    /** 由 file:// URL 或普通路径取码（不在 upload 目录 → null）。 */
    fun codeForLocation(location: String): String? = runCatching {
        val path = android.net.Uri.parse(location).path ?: return null
        codeForPath(path)
    }.getOrNull()

    /** 由文件系统路径取码（仅 upload 目录下的文件）。 */
    fun codeForPath(path: String): String? {
        val f = File(path)
        if (f.parentFile?.name != "upload") return null
        return codeForFileName(f.name)
    }

    /**
     * v4.8.90 旧格式：由相对路径派生的 8 位哈希码。
     * 仅用于历史文件（uuid 命名）——新上传一律时间码，不再生成哈希码。
     */
    fun legacyCodeOf(relativePath: String): String {
        val normalized = relativePath.trim().replace('\\', '/').lowercase()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        var acc = 0L
        for (i in 0 until 5) {                       // 取前 40 bit → 恰好 8 × 5 bit
            acc = (acc shl 8) or (digest[i].toLong() and 0xFFL)
        }
        val sb = StringBuilder(8)
        repeat(8) {
            sb.append(LEGACY_ALPHABET[(acc and 31L).toInt()])
            acc = acc shr 5
        }
        return sb.toString()
    }

    /** 归一化用户/模型输入里的码（去空格/连字符、转大写） */
    fun normalize(raw: String): String =
        raw.trim().uppercase().replace("-", "").replace(" ", "")

    /** 是否合法码：12-13 位数字（时间码）或 8 位历史码。 */
    fun hasCodeFormat(raw: String): Boolean {
        val t = normalize(raw)
        // v4.8.137: 实际时间码 = 8 位前缀 + ≥2 位序号 (10 位起最大 4 位序号)
        if (t.length in 10..12 && t.all { it.isDigit() }) return true
        return t.length == 8 && t.all { it in LEGACY_ALPHABET }
    }
}
