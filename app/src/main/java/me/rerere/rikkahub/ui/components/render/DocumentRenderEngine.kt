package me.rerere.rikkahub.ui.components.render

import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 渲染机核心 (v3.9.6)
 *
 * 统一管线: 输入文件 -> RenderEngine.render -> RenderResult (产物目录或原生视图)
 *
 * 设计要点:
 * 1. 文本提取类产物文件化: outDir/index.html + assets/ 图片落盘 (不用 base64, 大图稳定)
 * 2. 多页文档分页产物: page1.html..pageN.html + meta.json, 一页一载不卡
 * 3. 深色模式: CSS 变量 + html.dark 类, JS 切换不重载
 * 4. 缩放手势: WebView builtInZoomControls / 图片与 PDF 原生手势
 */

/** 渲染结果 */
sealed class RenderResult {
    abstract val title: String

    /** 文本提取类: 分页 HTML 产物目录 */
    data class HtmlPages(
        override val title: String,
        val workDir: File,
        val pageCount: Int,
    ) : RenderResult()

    /** PDF: 原生逐页渲染 */
    data class PdfView(override val title: String, val pdfFile: File) : RenderResult()

    /** 图片: 原生缩放查看 */
    data class ImageView(override val title: String, val imageFile: File) : RenderResult()

    /** 视频: 原生播放 */
    data class VideoView(override val title: String, val videoFile: File) : RenderResult()

    /** 音频: 原生播放控制 */
    data class AudioView(override val title: String, val audioFile: File) : RenderResult()

    /** 无法处理 */
    data class Unsupported(override val title: String, val message: String) : RenderResult()
}

/** 文本提取器接口: 把文件提取为分页 HTML 产物, 返回页数 */
interface DocumentExtractor {
    fun extract(input: File, outDir: File): Int
}

/** 渲染机 */
object RenderEngine {

    fun render(input: File, workDir: File, title: String): RenderResult {
        workDir.mkdirs()
        val ext = input.name.substringAfterLast('.', "").lowercase()
        val extractor: DocumentExtractor? = when (ext) {
            "html", "htm", "svg" -> HtmlDocExtractor()
            "docx" -> DocxExtractor()
            "xlsx" -> XlsxExtractor()
            "csv" -> CsvExtractor()
            "pptx" -> PptxExtractor()
            "txt", "md", "json", "log", "xml", "yaml", "yml", "toml", "ini",
            "py", "js", "kt", "java", "c", "cpp", "h", "sh", "sql", "css", "ts", "jsx" -> TextExtractor()
            "pdf" -> return RenderResult.PdfView(title, input)
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico" ->
                return RenderResult.ImageView(title, input)
            "mp4", "mkv", "webm", "3gp", "mov", "avi" -> return RenderResult.VideoView(title, input)
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus" ->
                return RenderResult.AudioView(title, input)
            "doc", "xls", "ppt" -> return RenderResult.Unsupported(
                title, "旧版二进制格式 ${ext.uppercase()} 无法在应用内解析，请使用 Office 另存为新格式后打开"
            )
            else -> return RenderResult.Unsupported(title, "暂不支持 ${ext.uppercase()} 格式")
        }
        val pageCount = runCatching { extractor.extract(input, workDir) }.getOrElse {
            writeFile(File(workDir, "page1.html"), buildPage("提取失败", "<p>此文件无法解析：${it.message}</p>"))
            1
        }
        writeMeta(workDir, pageCount)
        return RenderResult.HtmlPages(title, workDir, pageCount)
    }

    private fun writeMeta(workDir: File, pages: Int) {
        writeFile(File(workDir, "meta.json"), """{"pages":$pages}""")
    }
}

/** ===== 公共产物模板 ===== */

internal const val RENDER_CSS = """
:root { --bg:#ffffff; --fg:#1a1a1a; --border:#cccccc; --head:#111111; --muted:#666666; }
html.dark { --bg:#121212; --fg:#e0e0e0; --border:#444444; --head:#f0f0f0; --muted:#9e9e9e; }
* { box-sizing: border-box; }
body { background: var(--bg); color: var(--fg); margin: 0; padding: 16px; font-family: -apple-system, 'Noto Sans SC', sans-serif; line-height: 1.6; font-size: 15px; }
h1,h2,h3,h4,h5,h6 { color: var(--head); margin: 0.8em 0 0.4em; }
table { border-collapse: collapse; width: 100%; font-size: 14px; margin: 8px 0; }
td, th { border: 1px solid var(--border); padding: 6px; vertical-align: top; }
p { margin: 0.4em 0; }
pre { font-family: monospace; white-space: pre-wrap; word-break: break-word; font-size: 13px; }
img { max-width: 100%; height: auto; display: block; margin: 4px 0; }
"""

internal fun buildPage(title: String, bodyHtml: String): String =
    "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
        "<title>$title</title><style>$RENDER_CSS</style></head><body>$bodyHtml</body></html>"

internal fun writeFile(file: File, content: String) {
    file.parentFile?.mkdirs()
    file.writeText(content)
}

/** ===== 提取器实现 ===== */

/** HTML/SVG: 原样复制, 深色由查看器注入 CSS 类 */
internal class HtmlDocExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val content = input.readText()
        // 若已有完整 html 结构则复用, 否则包一层
        val html = if (content.trimStart().startsWith("<!DOCTYPE") || content.contains("<html")) {
            content
        } else {
            buildPage(input.name, content)
        }
        writeFile(File(outDir, "page1.html"), html)
        return 1
    }
}

/** 文本族: 等宽渲染 */
internal class TextExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val text = input.readText().let { escapeHtml(it) }
        writeFile(File(outDir, "page1.html"), buildPage(input.name, "<pre>$text</pre>"))
        return 1
    }
}

/** CSV: 表格渲染 */
internal class CsvExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val sb = StringBuilder("<table><tr>")
        input.readLines().filter { it.isNotBlank() }.forEach { line ->
            sb.append("<tr>")
            parseCsvLine(line).forEach { cell ->
                sb.append("<td>").append(escapeHtml(cell)).append("</td>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        writeFile(File(outDir, "page1.html"), buildPage(input.name, sb.toString()))
        return 1
    }
}

/** DOCX: 段落/标题/表格 + 嵌入图片落盘 assets */
internal class DocxExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val map = readZipMap(input)
        val documentXml = map["word/document.xml"]?.toString(Charsets.UTF_8)
            ?: run {
                writeFile(File(outDir, "page1.html"), buildPage(input.name, "<p>无法解析此文档</p>"))
                return 1
            }
        val rels = parseRelMap(map["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8))
        val assets = File(outDir, "assets").apply { mkdirs() }
        val html = buildDocxHtml(documentXml, rels, map, assets)
        writeFile(File(outDir, "page1.html"), buildPage(input.name, html))
        return 1
    }
}

/** XLSX: 每工作表一页 */
internal class XlsxExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val map = readZipMap(input)
        val shared = map["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { parseSharedStrings(it) }
            ?: emptyList()
        val sheetFiles = map.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
        if (sheetFiles.isEmpty()) {
            writeFile(File(outDir, "page1.html"), buildPage(input.name, "<p>未找到工作表内容</p>"))
            return 1
        }
        for ((index, entry) in sheetFiles.withIndex()) {
            val sheetXml = map[entry]?.toString(Charsets.UTF_8) ?: continue
            val html = buildSheetHtml(sheetXml, shared)
            writeFile(
                File(outDir, "page${index + 1}.html"),
                buildPage(input.name + " - Sheet ${index + 1}", html),
            )
        }
        return sheetFiles.size
    }
}

/** PPTX: 每页幻灯片一页 (解决整页大 DOM 卡顿) */
internal class PptxExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val map = readZipMap(input)
        val slideFiles = map.keys
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
        if (slideFiles.isEmpty()) {
            writeFile(File(outDir, "page1.html"), buildPage(input.name, "<p>未找到幻灯片内容</p>"))
            return 1
        }
        val assets = File(outDir, "assets").apply { mkdirs() }
        for ((index, entry) in slideFiles.withIndex()) {
            val slideXml = map[entry]?.toString(Charsets.UTF_8) ?: continue
            val rels = parseRelMap(map["ppt/slides/_rels/${entry.substringAfterLast('/')}.rels"]?.toString(Charsets.UTF_8))
            val html = buildSlideHtml(slideXml, rels, map, assets)
            writeFile(
                File(outDir, "page${index + 1}.html"),
                buildPage(input.name + " - 第 ${index + 1} 页", html),
            )
        }
        return slideFiles.size
    }
}

/** ===== 解析工具 ===== */

internal fun XmlPullParser.isTag(localName: String): Boolean =
    name == localName || name.endsWith(":$localName")

internal fun XmlPullParser.attr(vararg names: String): String? {
    for (i in 0 until attributeCount) {
        val n = getAttributeName(i)
        if (names.any { n == it || n.endsWith(":$it") }) {
            return getAttributeValue(i)
        }
    }
    return null
}

internal fun readZipMap(file: File): Map<String, ByteArray> {
    val map = HashMap<String, ByteArray>()
    val zip = ZipInputStream(file.inputStream())
    try {
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                map[entry.name] = zip.readBytes()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    } finally {
        zip.close()
    }
    return map
}

internal fun parseRelMap(relsXml: String?): Map<String, String> {
    val map = HashMap<String, String>()
    if (relsXml == null) return map
    val pattern = Regex("<Relationship[^>]*\\bId=\"([^\"]+)\"[^>]*\\bTarget=\"([^\"]+)\"")
    pattern.findAll(relsXml).forEach { m ->
        map[m.groupValues.getOrNull(1) ?: ""] = m.groupValues.getOrNull(2) ?: ""
    }
    return map
}

internal fun normalizeRelPath(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.trimStart('/')
    val parts = (baseDir.split('/').filter { it.isNotBlank() } + target.split('/'))
    val stack = ArrayDeque<String>()
    parts.forEach { part ->
        when (part) {
            "", "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(part)
        }
    }
    return stack.joinToString("/")
}

/** 图片落盘 assets, 返回相对引用路径 */
internal fun saveMedia(map: Map<String, ByteArray>, mediaPath: String, assetsDir: File): String? {
    val bytes = map[mediaPath] ?: return null
    val ext = mediaPath.substringAfterLast('.', "png").lowercase()
    val safeName = "img_" + mediaPath.substringAfterLast('/').replace(Regex("[^\\w.]"), "_")
    val out = File(assetsDir, safeName)
    out.writeBytes(bytes)
    return "assets/$safeName"
}

internal fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    cur.append('"'); i++
                } else inQuotes = !inQuotes
            }
            ch == ',' && !inQuotes -> { result.add(cur.toString().trim()); cur.setLength(0) }
            else -> cur.append(ch)
        }
        i++
    }
    result.add(cur.toString().trim())
    return result
}

internal fun parseSharedStrings(xml: String): List<String> {
    val result = mutableListOf<String>()
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        var current = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.isTag("t")) {
                val text = runCatching { parser.nextText() }.getOrDefault("")
                current.append(text)
            }
            if (eventType == XmlPullParser.END_TAG && parser.isTag("si")) {
                result.add(current.toString())
                current = StringBuilder()
            }
            eventType = parser.next()
        }
    } catch (_: Exception) {
    }
    return result
}

internal fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** ===== DOCX 结构提取 ===== */

internal fun buildDocxHtml(
    documentXml: String,
    rels: Map<String, String>,
    map: Map<String, ByteArray>,
    assetsDir: File,
): String {
    val sb = StringBuilder()
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    val parser = factory.newPullParser()
    parser.setInput(documentXml.reader())
    var inParagraph = false
    var pendingTag = ""
    val paragraphText = StringBuilder()
    val headingLevel = StringBuilder()
    var inCellText = false
    var inTable = false

    fun flush() {
        if (pendingTag != "p") return
        val text = paragraphText.toString()
        val h = headingLevel.toString()
        if (text.isNotBlank() || h.isNotEmpty()) {
            when {
                h.lowercase().contains("heading") || h.contains("标题") -> {
                    val lvl = h.filter { it.isDigit() }.firstOrNull()?.digitToInt()?.coerceIn(1, 6) ?: 1
                    sb.append("<h$lvl>").append(escapeHtml(text)).append("</h$lvl>")
                }
                else -> sb.append("<p>").append(escapeHtml(text)).append("</p>")
            }
        }
        paragraphText.setLength(0)
        headingLevel.setLength(0)
        pendingTag = ""
        inParagraph = false
    }

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                when {
                    parser.isTag("p") -> {
                        if (!(inTable && inCellText)) flush()
                        inParagraph = true
                        pendingTag = "p"
                    }
                    parser.isTag("tbl") -> {
                        flush()
                        inTable = true
                        sb.append("<table>")
                    }
                    parser.isTag("tr") -> sb.append("<tr>")
                    parser.isTag("tc") -> {
                        sb.append("<td>")
                        inCellText = true
                    }
                    parser.isTag("t") -> {
                        val text = runCatching { parser.nextText() }.getOrDefault("")
                        if (inParagraph) paragraphText.append(text)
                        else sb.append(escapeHtml(text))
                    }
                    parser.isTag("pStyle") -> {
                        val v = parser.getAttributeValue(null, "val") ?: ""
                        if (v.lowercase().contains("heading") || v.contains("标题")) {
                            headingLevel.append(v)
                        }
                    }
                    parser.isTag("tab") -> if (inParagraph) paragraphText.append("&emsp;")
                    parser.isTag("br") -> if (inParagraph) paragraphText.append("<br/>")
                    parser.isTag("blip") -> {
                        val rid = parser.attr("embed")
                        val target = rid?.let { rels[it] }
                        if (target != null) {
                            val mediaPath = normalizeRelPath("word", target)
                            val ref = saveMedia(map, mediaPath, assetsDir)
                            if (ref != null) {
                                val img = "<img src='$ref' alt='image'/>"
                                if (inParagraph) paragraphText.append(img)
                                else sb.append(img)
                            }
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> {
                when {
                    parser.isTag("p") -> flush()
                    parser.isTag("tbl") -> {
                        sb.append("</table>")
                        inTable = false
                    }
                    parser.isTag("tr") -> sb.append("</tr>")
                    parser.isTag("tc") -> {
                        sb.append("</td>")
                        inCellText = false
                    }
                }
            }
        }
        eventType = parser.next()
    }
    flush()
    return sb.toString()
}

/** ===== XLSX 工作表提取 ===== */

internal fun buildSheetHtml(sheetXml: String, shared: List<String>): String {
    val sb = StringBuilder("<table>")
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(sheetXml.reader())
        var cellText = StringBuilder()
        var cellType = ""
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        parser.isTag("row") -> sb.append("<tr>")
                        parser.isTag("c") -> {
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cellText.setLength(0)
                        }
                        parser.isTag("t") || parser.isTag("v") -> {
                            val text = runCatching { parser.nextText() }.getOrDefault("")
                            cellText.append(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when {
                        parser.isTag("c") -> {
                            val raw = cellText.toString()
                            val value = when {
                                cellType == "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                                raw.isBlank() -> "&nbsp;"
                                else -> escapeHtml(raw)
                            }
                            sb.append("<td>").append(value).append("</td>")
                        }
                        parser.isTag("row") -> sb.append("</tr>")
                    }
                }
            }
            eventType = parser.next()
        }
    } catch (_: Exception) {
        sb.append("<tr><td>本表解析失败</td></tr>")
    }
    sb.append("</table>")
    return sb.toString()
}

/** ===== PPTX 幻灯片提取 ===== */

internal fun buildSlideHtml(
    slideXml: String,
    rels: Map<String, String>,
    map: Map<String, ByteArray>,
    assetsDir: File,
): String {
    val sb = StringBuilder()
    var found = false
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(slideXml.reader())
        var inCell = false
        var cellText = StringBuilder()
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        parser.isTag("tbl") -> sb.append("<table>")
                        parser.isTag("tr") -> sb.append("<tr>")
                        parser.isTag("tc") -> {
                            sb.append("<td>")
                            inCell = true
                            cellText.setLength(0)
                        }
                        parser.isTag("t") -> {
                            val text = runCatching { parser.nextText() }.getOrDefault("")
                            if (inCell) cellText.append(escapeHtml(text))
                            else if (text.isNotBlank()) {
                                sb.append("<p>").append(escapeHtml(text)).append("</p>")
                                found = true
                            }
                        }
                        parser.isTag("blip") -> {
                            val rid = parser.attr("embed")
                            val target = rid?.let { rels[it] }
                            if (target != null) {
                                val mediaPath = normalizeRelPath("ppt/slides", target)
                                val ref = saveMedia(map, mediaPath, assetsDir)
                                if (ref != null) {
                                    sb.append("<img src='$ref' alt='image'/>")
                                    found = true
                                }
                            }
                        }
                        parser.isTag("br") -> if (inCell) cellText.append("<br/>")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when {
                        parser.isTag("tc") -> {
                            sb.append(cellText).append("</td>")
                            inCell = false
                        }
                        parser.isTag("tr") -> sb.append("</tr>")
                        parser.isTag("tbl") -> sb.append("</table>")
                    }
                }
            }
            eventType = parser.next()
        }
    } catch (_: Exception) {
        sb.append("<p>本页解析失败</p>")
    }
    if (!found) sb.append("<p style='color:var(--muted)'>本页无文本或图片内容</p>")
    return sb.toString()
}