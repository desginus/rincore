package me.rerere.rikkahub.ui.components

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipInputStream

/**
 * 全文档类型渲染支持 (v3.9.1)：
 * - HTML/SVG: WebView 直接渲染（保留 JS/表单/图表交互）
 * - PDF: PdfRenderer 逐页渲染
 * - DOCX/DOC: zip+XML 提取段落文本, 转 HTML 排版渲染
 * - XLSX/XLS/CSV: 提取单元格转 HTML 表格渲染
 * - 文本类: 全屏等宽文本视图
 * 读取能力有限的老二进制格式 (doc/xls) 尽力而为, 失败给出明确提示。
 */
enum class RenderKind { HTML, PDF, DOC, SHEET, SLIDES, IMAGE, VIDEO, AUDIO, TEXT, NONE }

/** v3.9.4: XmlPullParser 非 namespace 模式下 getName 返回带前缀 qName (w:t/a:t),
 * 统一用 localName 匹配 — 此前 name == "t" 全失配导致 docx/pptx 白屏 */
private fun XmlPullParser.isTag(localName: String): Boolean =
    name == localName || name.endsWith(":$localName")

fun detectRenderKind(fileName: String): RenderKind {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm", "svg" -> RenderKind.HTML
        "pdf" -> RenderKind.PDF
        "docx" -> RenderKind.DOC
        "xlsx", "csv" -> RenderKind.SHEET
        "pptx" -> RenderKind.SLIDES
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico" -> RenderKind.IMAGE
        "mp4", "mkv", "webm", "3gp", "mov", "avi" -> RenderKind.VIDEO
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus" -> RenderKind.AUDIO
        "txt", "md", "json", "log", "xml", "yaml", "yml", "toml", "ini",
        "py", "js", "kt", "java", "c", "cpp", "h", "sh", "sql", "css", "ts", "jsx" -> RenderKind.TEXT
        else -> RenderKind.NONE
    }
}

/** PPTX: 读取全部 slideN.xml, 每页文本转 HTML 分页 (a:t 文本 + 页分隔) */
fun extractPptxHtml(bytes: ByteArray): String {
    val sb = StringBuilder()
    val entries = listZipEntries(bytes)
    val slideFiles = entries
        .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    if (slideFiles.isEmpty()) return "<p>未找到幻灯片内容</p>"

    sb.append("<style> .slide { page-break-after: always; margin-bottom: 24px; border-bottom: 1px solid #ddd; padding-bottom: 16px; } .slide:last-child { page-break-after: auto; border-bottom: none; } </style>")
    slideFiles.forEachIndexed { index, entry ->
        val xml = extractZipEntry(bytes, entry) ?: return@forEachIndexed
        sb.append("<div class='slide'><h3>第 ${index + 1} 页</h3>")
        var found = false
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.isTag("t")) {
                    val text = runCatching { parser.nextText() }.getOrDefault("")
                    if (text.isNotBlank()) {
                        sb.append("<p>").append(escapeHtml(text)).append("</p>")
                        found = true
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            sb.append("<p>本页解析失败</p>")
        }
        if (!found) sb.append("<p style='color:#888'>本页无文本内容（可能为纯图形页）</p>")
        sb.append("</div>")
    }
    return wrapHtml(sb.toString())
}

private fun listZipEntries(bytes: ByteArray): List<String> {
    val names = mutableListOf<String>()
    val zip = ZipInputStream(bytes.inputStream())
    try {
        var entry = zip.nextEntry
        while (entry != null) {
            names.add(entry.name)
            zip.closeEntry()
            entry = zip.nextEntry
        }
    } finally {
        zip.close()
    }
    return names
}

/** DOCX: 解压 word/document.xml, 提取段落结构转 HTML (标题/段落/列表近似还原) */
fun extractDocxHtml(bytes: ByteArray): String {
    val documentXml = extractZipEntry(bytes, "word/document.xml") ?: return "<p>无法解析此文档</p>"
    return runCatching {
        buildDocxHtml(documentXml)
    }.getOrElse {
        // 降级: 直接提取全部文本节点 (不保留结构)
        extractDocxPlainText(documentXml)
    }
}

private fun buildDocxHtml(documentXml: String): String {
    val sb = StringBuilder()
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    val parser = factory.newPullParser()
    parser.setInput(documentXml.reader())
    var inParagraph = false
    var pendingTag = ""
    val paragraphText = StringBuilder()
    val headingLevel = StringBuilder() // 记录当前段落标题级别
    var inCellText = false

    fun flush() {
        if (!inParagraph && pendingTag.isEmpty()) return
        if (pendingTag == "p") {
            val text = paragraphText.toString()
            val h = headingLevel.toString()
            if (text.isNotBlank() || h.isNotEmpty()) {
                when {
                    h.startsWith("Heading") || h.startsWith("标题") || h.contains("heading") -> {
                        val lvl = h.filter { it.isDigit() }.firstOrNull()?.digitToInt()?.coerceIn(1, 6) ?: 1
                        sb.append("<h$lvl>").append(escapeHtml(text)).append("</h$lvl>\n")
                    }
                    else -> sb.append("<p>").append(escapeHtml(text)).append("</p>\n")
                }
            }
            paragraphText.setLength(0)
            headingLevel.setLength(0)
            pendingTag = ""
            inParagraph = false
        }
    }

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                when {
                    parser.isTag("p") -> {
                        flush()
                        inParagraph = true
                        pendingTag = "p"
                    }
                    parser.isTag("tbl") -> {
                        flush()
                        sb.append("<table border='1' cellpadding='4' style='border-collapse:collapse'>")
                    }
                    parser.isTag("tr") -> sb.append("<tr>")
                    parser.isTag("tc") -> {
                        sb.append("<td>")
                        inCellText = true
                    }
                    parser.isTag("t") -> {
                        val text = parser.nextText()
                        if (inParagraph) paragraphText.append(text)
                        else if (inCellText) sb.append(escapeHtml(text))
                        else sb.append(escapeHtml(text))
                    }
                    parser.isTag("pStyle") -> {
                        val v = parser.getAttributeValue(null, "val") ?: ""
                        if (v.lowercase().contains("heading") || v.contains("标题")) {
                            headingLevel.append(v)
                        }
                    }
                    parser.isTag("tab") -> if (inParagraph) paragraphText.append(" ")
                    parser.isTag("br") -> if (inParagraph) paragraphText.append(" ")
                }
            }
            XmlPullParser.END_TAG -> {
                when {
                    parser.isTag("p") -> flush()
                    parser.isTag("tbl") -> sb.append("</table>\n")
                    parser.isTag("tr") -> sb.append("</tr>\n")
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
    return wrapHtml(sb.toString())
}

/** 降级: 不保留结构, 提取 document.xml 全部文本节点 */
private fun extractDocxPlainText(documentXml: String): String {
    val sb = StringBuilder("<pre style='font-family:monospace;white-space:pre-wrap;font-size:13px'>")
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(documentXml.reader())
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.isTag("t")) {
                val text = parser.nextText()
                if (text.isNotBlank()) sb.append(escapeHtml(text))
            }
            if (eventType == XmlPullParser.END_TAG && parser.isTag("p")) {
                sb.append("\n")
            }
            eventType = parser.next()
        }
    } catch (_: Exception) {
        return "<p>无法解析此 Word 文档（格式可能为旧版二进制 doc）</p>"
    }
    sb.append("</pre>")
    return wrapHtml(sb.toString())
}

/** XLSX: 全部工作表 + sharedStrings 富文本拼接 + inlineStr/str 类型支持, 转 HTML 表格 */
fun extractXlsxHtml(bytes: ByteArray): String {
    val entries = listZipEntries(bytes)
    val shared = extractZipEntry(bytes, "xl/sharedStrings.xml")?.let { parseSharedStrings(it) }
        ?: emptyList()
    val sheetFiles = entries
        .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    if (sheetFiles.isEmpty()) return "<p>未找到工作表内容</p>"

    val sb = StringBuilder()
    sheetFiles.forEachIndexed { sheetIndex, entry ->
        val sheetXml = extractZipEntry(bytes, entry) ?: return@forEachIndexed
        sb.append("<h3>Sheet ${sheetIndex + 1}</h3>")
        sb.append("<table border='1' cellpadding='4' style='border-collapse:collapse'>")
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
                            // t=inlineStr 时 <is><t>内联文本</t></is>; t=空白时 <v>数值</v>
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
                            parser.isTag("row") -> sb.append("</tr>\n")
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            sb.append("<tr><td>本表解析失败</td></tr>")
        }
        sb.append("</table>")
    }
    return wrapHtml(sb.toString())
}

/** CSV: 转 HTML 表格 (支持简单引号包裹单元格) */
fun csvToHtml(text: String): String {
    val sb = StringBuilder("<table border='1' cellpadding='4' style='border-collapse:collapse'>")
    text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
        sb.append("<tr>")
        parseCsvLine(line).forEach { cell ->
            sb.append("<td>").append(escapeHtml(cell)).append("</td>")
        }
        sb.append("</tr>\n")
    }
    sb.append("</table>")
    return wrapHtml(sb.toString())
}

private fun parseCsvLine(line: String): List<String> {
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

private fun extractZipEntry(bytes: ByteArray, entryName: String): String? {
    val zip = ZipInputStream(bytes.inputStream())
    try {
        var entry = zip.nextEntry
        while (entry != null) {
            if (entry.name == entryName) {
                return zip.readBytes().toString(Charsets.UTF_8)
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    } finally {
        zip.close()
    }
    return null
}

private fun parseSharedStrings(xml: String): List<String> {
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
    } catch (e: Exception) {
        // 解析失败返回空
    }
    return result
}

fun wrapHtml(body: String): String = """
    <!DOCTYPE html><html><head><meta charset="utf-8">
    <style>
      body { font-family: sans-serif; margin: 20px; line-height: 1.6; color: #1a1a1a; }
      h1,h2,h3,h4,h5,h6 { margin: 0.8em 0 0.4em; }
      table { border-collapse: collapse; width: 100%; font-size: 14px; }
      td { border: 1px solid #ccc; padding: 6px; }
      p { margin: 0.4em 0; }
    </style></head><body>$body</body></html>
""".trimIndent()

fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")