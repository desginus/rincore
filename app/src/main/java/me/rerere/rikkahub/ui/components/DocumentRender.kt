package me.rerere.rikkahub.ui.components

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipInputStream

enum class RenderKind { HTML, PDF, DOC, SHEET, SLIDES, IMAGE, VIDEO, AUDIO, TEXT, NONE }


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

fun extractPptxHtml(bytes: ByteArray): String {
    val map = readZipMap(bytes)
    val slideFiles = map.keys
        .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    if (slideFiles.isEmpty()) return "<p>未找到幻灯片内容</p>"

    val sb = StringBuilder()
    sb.append("<style> .slide { page-break-after: always; margin-bottom: 24px; padding-bottom: 16px; } .slide:last-child { page-break-after: auto; } .slide-table { border-collapse: collapse; width: 100%; margin: 8px 0; } .slide-td { border: 1px solid #999; padding: 6px; vertical-align: top; } </style>")
    slideFiles.forEachIndexed { index, entry ->
        val slideXml = zipText(map, entry) ?: return@forEachIndexed
        val rels = parseRelMap(zipText(map, "ppt/slides/_rels/${entry.substringAfterLast('/')}.rels"))
        sb.append("<div class='slide'><h3>第 ${index + 1} 页</h3>")
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
                            parser.isTag("tbl") -> sb.append("<table class='slide-table'>")
                            parser.isTag("tr") -> sb.append("<tr>")
                            parser.isTag("tc") -> {
                                sb.append("<td class='slide-td'>")
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
                                    val dataUri = mediaToDataUri(map, mediaPath)
                                    if (dataUri != null) {
                                        sb.append("<img src='$dataUri' style='max-width:100%;display:block;margin:4px 0'/>")
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
                            parser.isTag("tr") -> sb.append("</tr>\n")
                            parser.isTag("tbl") -> sb.append("</table>")
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            sb.append("<p>本页解析失败</p>")
        }
        if (!found) sb.append("<p style='color:#888'>本页无文本或图片内容（可能为纯图形页）</p>")
        sb.append("</div>")
    }
    return wrapHtml(sb.toString())
}

private fun readZipMap(bytes: ByteArray): Map<String, ByteArray> {
    val map = HashMap<String, ByteArray>()
    val zip = ZipInputStream(bytes.inputStream())
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

private fun zipText(map: Map<String, ByteArray>, name: String): String? =
    map[name]?.toString(Charsets.UTF_8)

/** 通用: 解析 .rels 中 Id→Target 映射 */
private fun parseRelMap(relsXml: String?): Map<String, String> {
    val map = HashMap<String, String>()
    if (relsXml == null) return map
    val pattern = Regex("<Relationship[^>]*\\bId=\"([^\"]+)\"[^>]*\\bTarget=\"([^\"]+)\"")
    pattern.findAll(relsXml).forEach { m ->
        map[m.group(1) ?: ""] = m.group(2) ?: ""
    }
    return map
}

private fun normalizeRelPath(baseDir: String, target: String): String {
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

private fun mediaToDataUri(map: Map<String, ByteArray>, mediaPath: String): String? {
    val bytes = map[mediaPath] ?: return null
    val ext = mediaPath.substringAfterLast('.', "png").lowercase()
    val mime = when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "wmf" -> "image/x-wmf"
        else -> "image/$ext"
    }
    return "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

fun extractDocxHtml(bytes: ByteArray): String {
    val map = readZipMap(bytes)
    val documentXml = zipText(map, "word/document.xml") ?: return "<p>无法解析此文档</p>"
    val rels = parseRelMap(zipText(map, "word/_rels/document.xml.rels"))
    return runCatching {
        buildDocxHtml(documentXml, rels, map)
    }.getOrElse {
        extractDocxPlainText(map)
    }
}

private fun buildDocxHtml(
    documentXml: String,
    rels: Map<String, String>,
    map: Map<String, ByteArray>,
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
                    sb.append("<h$lvl>").append(escapeHtml(text)).append("</h$lvl>\n")
                }
                else -> sb.append("<p>").append(escapeHtml(text)).append("</p>\n")
            }
        } else if (sb.isEmpty() || !sb.toString().endsWith("<p></p>")) {
            // 空段落不输出
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
                        // 表格内的段落不中断表格
                        if (inTable && inCellText) {
                            // 单元格内段落文本继续收集 (保留换行)
                            paragraphText.append(inParagraph.takeIf { it }?.let { "" } ?: "")
                        } else {
                            flush()
                        }
                        inParagraph = true
                        pendingTag = "p"
                    }
                    parser.isTag("tbl") -> {
                        flush()
                        inTable = true
                        sb.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;width:100%'>")
                    }
                    parser.isTag("tr") -> sb.append("<tr>")
                    parser.isTag("tc") -> {
                        sb.append("<td style='border:1px solid #ccc;padding:6px;vertical-align:top'>")
                        inCellText = true
                    }
                    parser.isTag("t") -> {
                        val text = runCatching { parser.nextText() }.getOrDefault("")
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
                    parser.isTag("tab") -> if (inParagraph) paragraphText.append("&emsp;")
                    parser.isTag("br") -> if (inParagraph) paragraphText.append("<br/>")
                    parser.isTag("blip") -> {
                        // 嵌入图片: r:embed → rels → media → base64
                        val rid = parser.attr("embed")
                        val target = rid?.let { rels[it] }
                        if (target != null) {
                            val mediaPath = normalizeRelPath("word", target)
                            val dataUri = mediaToDataUri(map, mediaPath)
                            if (dataUri != null) {
                                val img = "<img src='$dataUri' style='max-width:100%;display:block;margin:4px 0'/>"
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
                        sb.append("</table>\n")
                        inTable = false
                    }
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

private fun extractDocxPlainText(map: Map<String, ByteArray>): String {
    val documentXml = zipText(map, "word/document.xml") ?: return "<p>无法解析此 Word 文档</p>"
    val sb = StringBuilder("<pre style='font-family:monospace;white-space:pre-wrap;font-size:13px'>")
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(documentXml.reader())
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.isTag("t")) {
                val text = runCatching { parser.nextText() }.getOrDefault("")
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

fun extractXlsxHtml(bytes: ByteArray): String {
    val map = readZipMap(bytes)
    val shared = zipText(map, "xl/sharedStrings.xml")?.let { parseSharedStrings(it) }
        ?: emptyList()
    val sheetFiles = map.keys
        .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sortedBy { it.filter { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE }
    if (sheetFiles.isEmpty()) return "<p>未找到工作表内容</p>"

    val sb = StringBuilder()
    sheetFiles.forEachIndexed { sheetIndex, entry ->
        val sheetXml = zipText(map, entry) ?: return@forEachIndexed
        sb.append("<h3>Sheet ${sheetIndex + 1}</h3>")
        sb.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse;width:100%'>")
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
                            // t=inlineStr 时 <is><t>内联文本</t></is>; 数值在 <v>
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
                                sb.append("<td style='border:1px solid #ccc;padding:6px'>").append(value).append("</td>")
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
