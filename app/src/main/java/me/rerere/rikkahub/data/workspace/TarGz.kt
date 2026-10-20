package me.rerere.rikkahub.data.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * v5.0.34：极简 tar(.gz) 读写 —— 用于工作区「完整」导出/导入。
 *
 * 为什么不用 zip：zip 无法表达 **符号链接**，而 rootfs（linux/）里大量
 * `/bin → usr/bin`、`/lib → usr/lib` 这类链接是系统可启动的前提；
 * 更致命的是把链接当目录递归会导致同一批文件被打包多遍（用户实测 25G → 30G 还在涨）。
 *
 * 本实现遵循 POSIX ustar：
 *  - 普通文件 typeflag='0'；目录 '5'；软链接 '2'（linkname 存目标）；
 *  - 非 ASCII/超长名字走 ustar prefix 拆分（prefix 155 + name 100）；
 *  - 导入端拒绝 `..` 与绝对路径（防路径穿越）。
 *
 * 只服务于工作区备份，不追求 GNU 扩展（长名/稀疏文件）。
 */
object TarGz {

    private const val BLOCK = 512

    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
        val linkTarget: String,
        val mode: Int,
        val size: Long,
    )

    // ─────────────────────────── 写 ───────────────────────────

    fun write(
        output: OutputStream,
        roots: List<Pair<Path, String>>,   // (磁盘路径, tar 内前缀)
        excludeDirNames: Set<String> = emptySet(),
        onProgress: (files: Int, bytes: Long) -> Unit = { _, _ -> },
    ) {
        var files = 0
        var bytes = 0L
        GZIPOutputStream(output.buffered(), 64 * 1024).use { gz ->
            for ((root, prefix) in roots) {
                if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue
                writeTree(gz, root, prefix.trimEnd('/'), excludeDirNames) { added, sz ->
                    files += added
                    bytes += sz
                    if (files % 64 == 0) onProgress(files, bytes)
                }
            }
            // tar 结束：两个全零块
            gz.write(ByteArray(BLOCK * 2))
        }
        onProgress(files, bytes)
    }

    private fun writeTree(
        out: OutputStream,
        path: Path,
        nameInTar: String,
        excludeDirNames: Set<String>,
        onFile: (Int, Long) -> Unit,
    ) {
        val attrs = runCatching { Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return
        when {
            attrs.isSymbolicLink -> {
                val target = runCatching { Files.readSymbolicLink(path).toString() }.getOrDefault("")
                writeHeader(out, nameInTar, 0L, mode = 0b111_111_111, typeflag = '2', linkTarget = target)
            }
            attrs.isDirectory -> {
                writeHeader(out, "$nameInTar/", 0L, mode = 0b111_101_101, typeflag = '5', linkTarget = "")
                val children = runCatching { Files.newDirectoryStream(path).use { it.toList() } }.getOrDefault(emptyList())
                for (child in children) {
                    val childName = child.fileName.toString()
                    if (childName in excludeDirNames) continue
                    writeTree(out, child, "$nameInTar/$childName", excludeDirNames, onFile)
                }
            }
            else -> {
                val size = attrs.size()
                writeHeader(out, nameInTar, size, mode = 0b110_100_100, typeflag = '0', linkTarget = "")
                runCatching {
                    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                }
                // 内容对齐到 512
                val pad = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
                if (pad > 0) out.write(ByteArray(pad))
                onFile(1, size)
            }
        }
    }

    private fun writeHeader(
        out: OutputStream,
        name: String,
        size: Long,
        mode: Int,
        typeflag: Char,
        linkTarget: String,
    ) {
        val header = ByteArray(BLOCK)
        var (prefix, shortName) = splitName(name)
        putString(header, 0, 100, shortName)
        putOctal(header, 100, 8, mode.toLong())
        putOctal(header, 108, 8, 0L)          // uid
        putOctal(header, 116, 8, 0L)          // gid
        putOctal(header, 124, 12, size)
        putOctal(header, 136, 12, System.currentTimeMillis() / 1000)
        // checksum 先填空格
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = typeflag.code.toByte()
        putString(header, 157, 100, linkTarget)
        putString(header, 257, 6, "ustar")
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        putString(header, 265, 32, "root")
        putString(header, 297, 32, "root")
        putString(header, 345, 155, prefix)
        // checksum
        var sum = 0L
        for (b in header) sum += (b.toInt() and 0xFF)
        val cs = java.lang.String.format("%06o", sum)
        putString(header, 148, 6, cs)
        header[154] = 0
        header[155] = ' '.code.toByte()
        out.write(header)
    }

    /** ustar：name ≤100；否则 prefix(155)+name(100) */
    private fun splitName(full: String): Pair<String, String> {
        if (full.toByteArray(StandardCharsets.UTF_8).size <= 100) return "" to full
        val parts = full.trimStart('/').split('/')
        var name = parts.last()
        var prefix = parts.dropLast(1).joinToString("/")
        while (name.toByteArray(StandardCharsets.UTF_8).size > 100 && prefix.isNotEmpty()) {
            val p = prefix.split('/')
            name = p.last() + "/" + name
            prefix = p.dropLast(1).joinToString("/")
        }
        return prefix to name
    }

    private fun putString(buf: ByteArray, offset: Int, len: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val n = minOf(bytes.size, len)
        System.arraycopy(bytes, 0, buf, offset, n)
    }

    private fun putOctal(buf: ByteArray, offset: Int, len: Int, value: Long) {
        val s = java.lang.String.format("%0${len - 1}o", value)
        putString(buf, offset, len - 1, s)
        buf[offset + len - 1] = 0
    }

    // ─────────────────────────── 读 ───────────────────────────

    /**
     * 逐个解析条目。**约定**：回调必须从传入的 InputStream 读取完该文件内容
     * （有界流，最多 [Entry.size] 字节）；目录/软链接传入的空流无需读。
     * reader 负责跳过未读部分与 512 对齐（容错）。
     */
    fun read(input: InputStream, onEntry: (Entry, InputStream) -> Unit) {
        GZIPInputStream(input.buffered(), 64 * 1024).use { gz ->
            while (true) {
                val header = ByteArray(BLOCK)
                var read = 0
                while (read < BLOCK) {
                    val n = gz.read(header, read, BLOCK - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < BLOCK) break
                if (header.all { it == 0.toByte() }) break

                val nameRaw = getString(header, 0, 100)
                val prefix = getString(header, 345, 155)
                val name = if (prefix.isNotBlank()) "$prefix/$nameRaw" else nameRaw
                val typeflag = header[156].toInt().toChar()
                val size = getOctal(header, 124, 12)
                val mode = getOctal(header, 100, 8).toInt()
                val link = getString(header, 157, 100)

                val entry = Entry(
                    name = name,
                    isDirectory = typeflag == '5',
                    isSymlink = typeflag == '2',
                    linkTarget = link,
                    mode = mode,
                    size = size,
                )

                if (typeflag == '0') {
                    val bounded = BoundedStream(gz, size)
                    onEntry(entry, bounded)
                    var rest = size - bounded.consumed
                    while (rest > 0) {
                        val n = gz.skip(rest)
                        if (n <= 0) break
                        rest -= n
                    }
                    val pad = ((BLOCK - (size % BLOCK)) % BLOCK)
                    if (pad > 0) gz.skip(pad)
                } else {
                    onEntry(entry, java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
    }

    /** 只允许读 [limit] 字节的包装流（用于单文件内容边界） */
    private class BoundedStream(private val src: InputStream, private val limit: Long) : InputStream() {
        var consumed: Long = 0L
            private set

        override fun read(): Int {
            if (consumed >= limit) return -1
            val b = src.read()
            if (b >= 0) consumed++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= limit) return -1
            val want = minOf(len.toLong(), limit - consumed).toInt()
            val n = src.read(b, off, want)
            if (n > 0) consumed += n
            return n
        }
    }

    private fun getString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        while (end < offset + len && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun getOctal(buf: ByteArray, offset: Int, len: Int): Long {
        val s = getString(buf, offset, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }
}
