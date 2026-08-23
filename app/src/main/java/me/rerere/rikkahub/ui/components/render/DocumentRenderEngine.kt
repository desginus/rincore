package me.rerere.rikkahub.ui.components.render

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

    /** 文本提取类: 分页 HTML 产物目录. canDark=false 表示页面为绝对配色(表格/幻灯片), 不提供深色反转 */
    data class HtmlPages(
        override val title: String,
        val workDir: File,
        val pageCount: Int,
        val canDark: Boolean = true,
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
        val pageCount = runCatching { extractor?.extract(input, workDir) ?: 1 }.getOrElse {
            writeFile(File(workDir, "page1.html"), buildPage("提取失败", "<p>此文件无法解析：${it.message}</p>"))
            1
        }
        writeMeta(workDir, pageCount)
        // xlsx/pptx 为绝对配色产物(填充色/背景色), 不提供深色反转
        val canDark = ext !in setOf("xlsx", "pptx")
        return RenderResult.HtmlPages(title, workDir, pageCount, canDark)
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
        val stylesXml = map["word/styles.xml"]?.toString(Charsets.UTF_8)
        val html = buildDocxHtml(documentXml, rels, map, assets, stylesXml)
        writeFile(File(outDir, "page1.html"), buildPage(input.name, html))
        return 1
    }
}

/** XLSX: 每工作表一页, 支持填充色/合并单元格/列宽 (v3.9.8 样式升级) */
internal class XlsxExtractor : DocumentExtractor {
    override fun extract(input: File, outDir: File): Int {
        val map = readZipMap(input)
        val shared = map["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { parseSharedStrings(it) }
            ?: emptyList()
        val cellFills = map["xl/styles.xml"]?.toString(Charsets.UTF_8)?.let { parseCellFills(it) }
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
            val html = buildSheetHtml(sheetXml, shared, cellFills)
            writeFile(
                File(outDir, "page${index + 1}.html"),
                buildPage(input.name + " - Sheet ${index + 1}", html),
            )
        }
        return sheetFiles.size
    }
}

/** styles.xml: 提取 cellXfs s 属性 -> 填充色, 返回 fill 颜色数组 (等长 xfs) */
internal fun parseCellFills(stylesXml: String): List<String?> {
    val result = mutableListOf<String?>()
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(stylesXml.reader())
        val fills = mutableListOf<String?>()
        var inFill = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.isTag("fill")) {
                inFill = true
                fills.add(null) // 占位: 每个 fill 保索引, 无颜色的系统默认位保持 null
            }
            if (eventType == XmlPullParser.START_TAG && inFill) {
                val color = when {
                    parser.isTag("fgColor") -> {
                        val rgb = parser.attr("rgb")
                        val indexed = parser.attr("indexed")
                        if (rgb != null && rgb.length >= 6) "#" + rgb.takeLast(6)
                        else if (indexed != null) indexedToHex(indexed.toIntOrNull() ?: 0)
                        else null
                    }
                    parser.isTag("srgbClr") -> parser.attr("val")?.let { "#$it" }
                    else -> null
                }
                if (color != null && fills.isNotEmpty()) fills[fills.size - 1] = color
            }
            if (eventType == XmlPullParser.END_TAG && parser.isTag("fill")) inFill = false
            eventType = parser.next()
        }
        // 解析 cellXfs: xf 按序, 取 fillId
        val xfFills = mutableListOf<String?>()
        val parser2 = factory.newPullParser()
        parser2.setInput(stylesXml.reader())
        var inXfs = false
        var ev = parser2.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when {
                    parser2.isTag("cellXfs") -> inXfs = true
                    parser2.isTag("xf") && inXfs -> {
                        val fillId = parser2.attr("fillId")?.toIntOrNull() ?: 0
                        // Excel fills 前两个为系统默认 (none/gray125), 无颜色定义
                        val color = fills.getOrNull(fillId)
                        xfFills.add(color)
                    }
                }
            }
            if (ev == XmlPullParser.END_TAG && parser2.isTag("cellXfs")) inXfs = false
            ev = parser2.next()
        }
        return xfFills
    } catch (_: Exception) {
        return result
    }
}

/** Excel indexed 调色板常用色 */
private fun indexedToHex(index: Int): String? = when (index) {
    0 -> "#000000"
    1 -> "#FFFFFF"
    2 -> "#FF0000"
    3 -> "#00FF00"
    4 -> "#0000FF"
    5 -> "#FFFF00"
    6 -> "#FF00FF"
    7 -> "#00FFFF"
    8 -> "#000000"
    9 -> "#FFFFFF"
    10 -> "#FF0000"
    11 -> "#00FF00"
    12 -> "#0000FF"
    13 -> "#FFFF00"
    14 -> "#FF00FF"
    15 -> "#00FFFF"
    16 -> "#800000"
    17 -> "#008000"
    18 -> "#000080"
    19 -> "#808000"
    20 -> "#800080"
    21 -> "#008080"
    22 -> "#C0C0C0"
    23 -> "#808080"
    24 -> "#9999FF"
    25 -> "#993366"
    26 -> "#FFFFCC"
    27 -> "#CCFFFF"
    28 -> "#660066"
    29 -> "#FF8080"
    30 -> "#0066CC"
    31 -> "#CCCCFF"
    32 -> "#000080"
    33 -> "#FF00FF"
    34 -> "#FFFF00"
    35 -> "#00FFFF"
    36 -> "#800080"
    37 -> "#800000"
    38 -> "#008080"
    39 -> "#0000FF"
    40 -> "#00CCFF"
    41 -> "#CCFFFF"
    42 -> "#CCFFCC"
    43 -> "#FFFF99"
    44 -> "#99CCFF"
    45 -> "#FF99CC"
    46 -> "#CC99FF"
    47 -> "#FFCC99"
    48 -> "#3366FF"
    49 -> "#33CCCC"
    50 -> "#99CC00"
    51 -> "#FFCC00"
    52 -> "#FF9900"
    53 -> "#FF6600"
    54 -> "#666699"
    55 -> "#969696"
    56 -> "#003366"
    57 -> "#339966"
    58 -> "#003300"
    59 -> "#333300"
    60 -> "#993300"
    61 -> "#993366"
    62 -> "#333399"
    63 -> "#333333"
    64 -> "#FFFFFF"
    65 -> "#000000"
    66 -> "#C0C0C0"
    67 -> "#FF0000"
    68 -> "#FFFF00"
    69 -> "#00FF00"
    70 -> "#00FFFF"
    71 -> "#FF00FF"
    72 -> "#0000FF"
    73 -> "#000000"
    74 -> "#FFFFFF"
    75 -> "#00008B"
    76 -> "#008B8B"
    77 -> "#A9A9A9"
    78 -> "#006400"
    79 -> "#BDB76B"
    80 -> "#8B008B"
    81 -> "#556B2F"
    82 -> "#FF8C00"
    83 -> "#9932CC"
    84 -> "#8B0000"
    85 -> "#E9967A"
    86 -> "#9400D3"
    87 -> "#FF00FF"
    88 -> "#FFD700"
    89 -> "#008000"
    90 -> "#4B0082"
    91 -> "#F0E68C"
    92 -> "#ADD8E6"
    93 -> "#E0FFFF"
    94 -> "#90EE90"
    95 -> "#D3D3D3"
    96 -> "#FFB6C1"
    97 -> "#FFA07A"
    98 -> "#20B2AA"
    99 -> "#87CEFA"
    100 -> "#778899"
    101 -> "#B0C4DE"
    102 -> "#FFFFE0"
    103 -> "#00FF00"
    104 -> "#32CD32"
    105 -> "#FAF0E6"
    106 -> "#FF00FF"
    107 -> "#800000"
    108 -> "#000080"
    109 -> "#808000"
    110 -> "#800080"
    111 -> "#008080"
    112 -> "#C0C0C0"
    113 -> "#000000"
    else -> null
}

/** 合并单元格解析: 返回 (被合并格集合, 首格范围 map: "c:r" -> (colspan, rowspan)) */
internal fun parseMergeRanges(sheetXml: String): Pair<Set<String>, Map<String, Pair<Int, Int>>> {
    val merged = HashSet<String>()
    val starts = HashMap<String, Pair<Int, Int>>()
    val pattern = Regex("<mergeCell[^>]*ref=\"([A-Z]+\\d+:[A-Z]+\\d+)\"")
    pattern.findAll(sheetXml).forEach { m ->
        val ref = m.groupValues.getOrNull(1) ?: return@forEach
        val (a, b) = ref.split(':')
        val colA = colToNum(a.filter { it.isLetter() })
        val rowA = a.filter { it.isDigit() }.toIntOrNull() ?: 0
        val colB = colToNum(b.filter { it.isLetter() })
        val rowB = b.filter { it.isDigit() }.toIntOrNull() ?: 0
        starts["$colA:$rowA"] = (colB - colA + 1) to (rowB - rowA + 1)
        for (r in rowA..rowB) {
            for (c in colA..colB) {
                if (r != rowA || c != colA) merged.add("$c:$r")
            }
        }
    }
    return merged to starts
}

internal fun colToNum(col: String): Int {
    var n = 0
    col.uppercase().forEach { ch -> n = n * 26 + (ch - 'A' + 1) }
    return n
}

internal fun buildSheetHtml(sheetXml: String, shared: List<String>, cellFills: List<String?>): String {
    // v3.9.8: 表格按内容宽度 (max-content), 窄表满宽, 超宽横向滚动, 不再强压竖屏
    val sb = StringBuilder("<table style='table-layout:auto;width:max-content;min-width:100%;white-space:nowrap;border-collapse:collapse'>")
    val (merged, mergeStarts) = parseMergeRanges(sheetXml)
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(sheetXml.reader())
        var cellText = StringBuilder()
        var cellType = ""
        var cellStyle = -1
        var cellCol = -1
        var rowIndex = 0
        var colIndex = 0
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        parser.isTag("row") -> {
                            sb.append("<tr>")
                            rowIndex++
                            colIndex = 0
                        }
                        parser.isTag("c") -> {
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            cellStyle = parser.attr("s")?.toIntOrNull() ?: -1
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            cellCol = if (ref.isNotEmpty()) {
                                colToNum(ref.filter { it.isLetter() })
                            } else colIndex + 1
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
                            while (colIndex + 1 < cellCol) {
                                // 行首跳跃/空隙: 补空单元格保列位
                                sb.append("<td></td>")
                                colIndex++
                            }
                            if ("$cellCol:$rowIndex" in merged) {
                                // 被合并格, 跳过
                            } else {
                                val fill = cellFills.getOrNull(cellStyle)
                                val style = if (fill != null) " style='background-color:$fill'" else ""
                                val span = mergeStarts["$cellCol:$rowIndex"]
                                val spanAttr = if (span != null) {
                                    val (cs, rs) = span
                                    " colspan='$cs' rowspan='$rs'"
                                } else ""
                                sb.append("<td$spanAttr$style>").append(value).append("</td>")
                            }
                            colIndex++
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

/** PPTX: 每页幻灯片一页 — 形状级渲染 (v3.9.8):
 * 背景色/形状位置/填充色/字号/颜色/粗体/图片/表格 近似还原 */
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

private const val EMU_PER_PX = 9525.0

private fun emuToPx(emu: Long): Int = (emu / EMU_PER_PX).toInt()

/** Office 默认主题色映射 (schemeClr) — 多数 PPT 未改主题 */
private fun schemeToHex(scheme: String): String? = when (scheme.lowercase()) {
    "accent1" -> "#4472C4"
    "accent2" -> "#ED7D31"
    "accent3" -> "#A5A5A5"
    "accent4" -> "#FFC000"
    "accent5" -> "#5B9BD5"
    "accent6" -> "#70AD47"
    "dk1", "tx1" -> "#000000"
    "lt1", "bg1" -> "#FFFFFF"
    "dk2" -> "#44546A"
    "lt2" -> "#E7E6E6"
    "hlink" -> "#0563C1"
    "folHlink" -> "#954F72"
    else -> null
}

/** srgbClr 16 进制转 #rrggbb */
private fun argbToHex(v: String): String = if (v.length >= 6) "#" + v.take(6) else "#FFFFFF"

/** 亮度判断: 深色背景给白字, 浅色背景给黑字 */
private fun contrastColor(bgHex: String?): String {
    if (bgHex == null || bgHex.length < 7) return "#000000"
    val r = bgHex.substring(1, 3).toIntOrNull(16) ?: 255
    val g = bgHex.substring(3, 5).toIntOrNull(16) ?: 255
    val b = bgHex.substring(5, 7).toIntOrNull(16) ?: 255
    return if (0.299 * r + 0.587 * g + 0.114 * b > 160) "#000000" else "#FFFFFF"
}

internal fun buildSlideHtml(
    slideXml: String,
    rels: Map<String, String>,
    map: Map<String, ByteArray>,
    assetsDir: File,
): String {
    val sb = StringBuilder()
    var slideW = 12192000L
    var slideH = 6858000L
    var bgColor: String? = null
    var found = false
    try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(slideXml.reader())

        // 形状上下文
        var curX = 0L; var curY = 0L; var curW = 0L; var curH = 0L
        var shapeFill: String? = null
        var inText = false
        var inRotPr = false
        var runSize = 1800.0
        var runBold = false
        var runColor: String? = null
        var paraAlign = ""
        val runs = StringBuilder()
        var inTbl = false
        var inRow = false
        var inCell = false
        val cellText = StringBuilder()
        var shapeStackDepth = 0
        var inSpPr = false
        var inPPr = false
        var defSize = 1800.0
        var defBold = false
        var defColor: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when {
                        parser.isTag("sldSz") -> {
                            slideW = parser.attr("cx")?.toLongOrNull() ?: slideW
                            slideH = parser.attr("cy")?.toLongOrNull() ?: slideH
                        }
                        parser.isTag("bg") -> {
                            // 背景: p:bg/p:bgPr/a:solidFill/a:srgbClr
                            bgColor = null
                        }
                        parser.isTag("bgPr") -> {}
                        parser.isTag("sp") -> found = true
                        parser.isTag("spPr") -> inSpPr = true
                        parser.isTag("xfrm") -> {
                            // 位置: a:off x/y + a:ext cx/cy
                            curX = 0L; curY = 0L; curW = 0L; curH = 0L
                        }
                        parser.isTag("off") -> {
                            curX = parser.attr("x")?.toLongOrNull() ?: 0L
                            curY = parser.attr("y")?.toLongOrNull() ?: 0L
                        }
                        parser.isTag("ext") -> {
                            curW = parser.attr("cx")?.toLongOrNull() ?: 0L
                            curH = parser.attr("cy")?.toLongOrNull() ?: 0L
                        }
                        parser.isTag("solidFill") -> {
                            // 背景/形状/文本 共用: 在 bg 内取背景, spPr 内取形状填充, rPr 内取文本色
                            if (inRotPr) {
                                runColor = null
                            } else if (inSpPr) {
                                shapeFill = null
                            }
                        }
                        parser.isTag("srgbClr") || parser.isTag("schemeClr") -> {
                            val hex = if (parser.isTag("srgbClr")) {
                                argbToHex(parser.attr("val") ?: "")
                            } else {
                                schemeToHex(parser.attr("val") ?: "") ?: ""
                            }
                            if (hex.isEmpty()) {
                                // 未知主题色: 不设置
                            } else if (inRotPr) {
                                runColor = hex
                            } else if (inSpPr) {
                                shapeFill = hex
                            } else {
                                bgColor = hex
                            }
                        }
                        parser.isTag("gradFill") -> {
                            // 渐变填充: 不解析渐变, 保持原填充色 (首色由 gsLst 提供, 简化跳过)
                            shapeFill = null
                        }
                        parser.isTag("rPr") -> {
                            inRotPr = true
                            runSize = (parser.attr("sz")?.toDoubleOrNull() ?: defSize)
                            runBold = parser.attr("b") == "1" || defBold
                            runColor = null
                        }
                        parser.isTag("txBody") -> {
                            inText = true
                            runs.setLength(0)
                            paraAlign = ""
                        }
                        parser.isTag("pPr") -> inPPr = true
                        parser.isTag("defRPr") -> {
                            defSize = parser.attr("sz")?.toDoubleOrNull() ?: 1800.0
                            defBold = parser.attr("b") == "1"
                            defColor = null
                        }
                        parser.isTag("t") -> {
                            // 文本: 可能出现在 run 或单元格
                            val text = runCatching { parser.nextText() }.getOrDefault("")
                            if (inCell) cellText.append(escapeHtml(text))
                            else if (inText) {
                                runs.append(escapeHtml(text))
                                found = true
                            }
                        }
                        parser.isTag("tbl") -> {
                            inTbl = true
                            sb.append("<div style='position:absolute;left:${emuToPx(curX)}px;top:${emuToPx(curY)}px;width:${emuToPx(curW)}px;height:${emuToPx(curH)}px;overflow:auto'><table style='width:100%;border-collapse:collapse'>")
                        }
                        parser.isTag("tr") -> { inRow = true; sb.append("<tr>") }
                        parser.isTag("tc") -> {
                            inCell = true
                            cellText.setLength(0)
                        }
                        parser.isTag("blip") -> {
                            val rid = parser.attr("embed")
                            val target = rid?.let { rels[it] }
                            if (target != null) {
                                val mediaPath = normalizeRelPath("ppt/slides", target)
                                val ref = saveMedia(map, mediaPath, assetsDir)
                                if (ref != null) {
                                    sb.append("<img src='$ref' style='position:absolute;left:${emuToPx(curX)}px;top:${emuToPx(curY)}px;width:${emuToPx(curW)}px;height:${emuToPx(curH)}px;object-fit:contain'>")
                                    found = true
                                }
                            }
                        }
                        parser.isTag("br") -> runs.append("<br/>")
                    }
                }
                XmlPullParser.END_TAG -> {
                    when {
                        parser.isTag("p") -> {
                            // 段落结束: 文本换行 (形状内用 br, 单元格内用换行)
                            if (inCell) cellText.append("<br/>")
                            else if (inText) runs.append("<br/>")
                        }
                        parser.isTag("bg") -> {}
                        parser.isTag("bgPr") -> {}
                        parser.isTag("spPr") -> inSpPr = false
                        parser.isTag("pPr") -> if (inPPr) inPPr = false
                        parser.isTag("rPr") -> inRotPr = false
                        parser.isTag("txBody") -> {
                            if (inText && !inCell) {
                                // 仅形状的 txBody 输出; 表格单元格内由 td 承载
                                val fill = shapeFill
                                val textColor = runColor ?: defColor ?: contrastColor(fill ?: bgColor)
                                val sizeUsed = if (runColor == null && defColor != null) defSize else runSize
                                val boldUsed = runBold || (runColor == null && defBold)
                                val fontSizePx = (sizeUsed / 100.0 * 4.0 / 3.0).toInt().coerceAtLeast(8)
                                val bold = if (boldUsed) "font-weight:bold;" else ""
                                val alignStyle = when (paraAlign) {
                                    "ctr" -> "text-align:center;"
                                    "r" -> "text-align:right;"
                                    else -> ""
                                }
                                val hasSize = curW > 0 && curH > 0
                                val posStyle = if (hasSize) {
                                    "position:absolute;left:${emuToPx(curX)}px;top:${emuToPx(curY)}px;" +
                                        "width:${emuToPx(curW)}px;height:${emuToPx(curH)}px;"
                                } else {
                                    "width:100%;margin:4px 0;"
                                }
                                val overflowStyle = if (hasSize) "overflow:hidden;" else ""
                                sb.append(
                                    "<div style='$posStyle$overflowStyle" +
                                        (if (fill != null) "background-color:$fill;" else "") +
                                        "color:$textColor;font-size:${fontSizePx}px;$bold$alignStyle" +
                                        "word-wrap:break-word;padding:4px;box-sizing:border-box'>" +
                                        runs + "</div>"
                                )
                                runs.setLength(0)
                                inText = false
                                shapeFill = null
                                runColor = null
                                defColor = null
                            }
                        }
                        parser.isTag("tbl") -> { inTbl = false; sb.append("</table></div>") }
                        parser.isTag("tr") -> { inRow = false; sb.append("</tr>") }
                        parser.isTag("tc") -> { sb.append("<td style='border:1px solid #999;padding:4px'>").append(cellText).append("</td>"); inCell = false }
                    }
                }
            }
            eventType = parser.next()
        }
    } catch (_: Exception) {
        sb.append("<p>本页解析失败</p>")
    }
    sb.append("</div>")
    if (!found) sb.append("<p style='color:#888'>本页无文本或图片内容</p>")
    // v3.9.11: PPT 容器固定 1280x720px, 在手机 360px viewport 下看不到内容
    //   加横向滚动包装层让用户左右滑动 + WebView 双指缩放看清细节
    val container = StringBuilder()
    container.append("<div style='width:100%;overflow:auto;-webkit-overflow-scrolling:touch'>")
    container.append("<div style='position:relative;width:${emuToPx(slideW)}px;height:${emuToPx(slideH)}px;")
    if (bgColor != null) container.append("background-color:$bgColor;")
    container.append("'>").append(sb)
    container.append("</div></div>")
    return container.toString()
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

private data class DocxDefaults(
    val fontSize: Int = 22, // 半 pt, 默认 11pt
    val fontName: String = "",
    val spacingAfter: Int = 200, // 默认 10pt
    val lineSpacing: Int = 276, // 默认 1.15x
    val lineRule: String = "auto",
)

private fun parseDocxDefaults(stylesXml: String?): DocxDefaults {
    if (stylesXml.isNullOrBlank()) return DocxDefaults()
    return try {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val p = factory.newPullParser()
        p.setInput(stylesXml.reader())
        var inDefaults = false; var inRPrDef = false; var inPPrDef = false
        var sz = 22; var font = ""; var after = 200; var line = 276; var rule = "auto"
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when {
                    p.isTag("docDefaults") -> inDefaults = true
                    p.isTag("rPrDefault") && inDefaults -> inRPrDef = true
                    p.isTag("pPrDefault") && inDefaults -> inPPrDef = true
                    p.isTag("sz") && inRPrDef -> sz = p.attr("val")?.toIntOrNull() ?: sz
                    p.isTag("rFonts") && inRPrDef -> font = p.attr("ascii") ?: font
                    p.isTag("spacing") && inPPrDef -> {
                        after = p.attr("after")?.toIntOrNull() ?: after
                        line = p.attr("line")?.toIntOrNull() ?: line
                        rule = p.attr("lineRule") ?: rule
                    }
                }
                XmlPullParser.END_TAG -> when {
                    p.isTag("docDefaults") -> inDefaults = false
                    p.isTag("rPrDefault") -> inRPrDef = false
                    p.isTag("pPrDefault") -> inPPrDef = false
                }
            }
            ev = p.next()
        }
        DocxDefaults(sz, font, after, line, rule)
    } catch (_: Exception) { DocxDefaults() }
}

internal fun buildDocxHtml(
    documentXml: String,
    rels: Map<String, String>,
    map: Map<String, ByteArray>,
    assetsDir: File,
    stylesXml: String? = null,
): String {
    val sb = StringBuilder()
    val factory = XmlPullParserFactory.newInstance()
    factory.isNamespaceAware = false
    val parser = factory.newPullParser()
    parser.setInput(documentXml.reader())
    val defaults = parseDocxDefaults(stylesXml)
    var inParagraph = false
    var pendingTag = ""
    val paragraphContent = StringBuilder() // 累积 HTML (含 span 包裹的 run)
    val headingLevel = StringBuilder()
    var inCellText = false
    var inTable = false
    var inRun = false
    var inRPr = false
    // run rPr 字段
    var runSize: Int? = null
    var runFont: String? = null
    var runColor: String? = null
    var runBold = false
    var runItalic = false
    // 段落字段
    var paraAlign = ""
    var paraIndent = 0.0
    var paraSpacingAfter: Int? = null
    var paraSpacingBefore: Int? = null
    var paraLineSpacing: Int? = null
    var paraLineRule = ""

    fun flush() {
        if (pendingTag != "p") return
        val content = paragraphContent.toString()
        val h = headingLevel.toString()
        if (content.isNotBlank() || h.isNotEmpty()) {
            when {
                h.lowercase().contains("heading") || h.contains("标题") -> {
                    val lvl = h.filter { it.isDigit() }.firstOrNull()?.digitToInt()?.coerceIn(1, 6) ?: 1
                    sb.append("<h$lvl>").append(content).append("</h$lvl>")
                }
                else -> {
                    // v3.9.11 原生排版: 对齐/缩进/段前段后间距/行距 (按 lineRule 换算)
                    val style = StringBuilder()
                    when (paraAlign) {
                        "center" -> style.append("text-align:center;")
                        "right" -> style.append("text-align:right;")
                        "both", "distribute" -> style.append("text-align:justify;")
                    }
                    if (paraIndent > 0) style.append("margin-left:${(paraIndent / 567.0).coerceAtLeast(0.2)}em;")
                    val after = paraSpacingAfter ?: defaults.spacingAfter
                    if (after > 0) style.append("margin-bottom:${after / 20.0}pt;")
                    val before = paraSpacingBefore ?: 0
                    if (before > 0) style.append("margin-top:${before / 20.0}pt;")
                    val line = paraLineSpacing ?: defaults.lineSpacing
                    val rule = if (paraLineRule.isNotEmpty()) paraLineRule else defaults.lineRule
                    if (line > 0) {
                        when (rule) {
                            "exact", "atLeast" -> style.append("line-height:${line / 20.0}pt;")
                            else -> style.append("line-height:${(line / 240.0 * 100).toInt() / 100.0};")
                        }
                    }
                    val styleAttr = if (style.isNotEmpty()) " style='$style'" else ""
                    sb.append("<p$styleAttr>").append(content).append("</p>")
                }
            }
        }
        paragraphContent.setLength(0)
        headingLevel.setLength(0)
        pendingTag = ""
        inParagraph = false
        paraAlign = ""
        paraIndent = 0.0
        paraSpacingAfter = null
        paraSpacingBefore = null
        paraLineSpacing = null
        paraLineRule = ""
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
                    parser.isTag("r") -> {
                        inRun = true
                        runSize = null; runFont = null; runColor = null
                        runBold = false; runItalic = false
                    }
                    parser.isTag("rPr") -> inRPr = true
                    parser.isTag("rFonts") -> {
                        if (inRPr) runFont = parser.attr("ascii")
                    }
                    parser.isTag("b") -> { if (inRPr) runBold = true }
                    parser.isTag("i") -> { if (inRPr) runItalic = true }
                    parser.isTag("color") -> {
                        if (inRPr) runColor = parser.attr("val")?.let { "#${it.take(6)}" }
                    }
                    parser.isTag("sz") -> {
                        if (inRPr) runSize = parser.attr("val")?.toIntOrNull()
                    }
                    parser.isTag("t") -> {
                        val text = runCatching { parser.nextText() }.getOrDefault("")
                        if (inParagraph) {
                            // v3.9.11 run 级 span 包裹 (字号/字体/颜色/粗细/斜体)
                            val style = StringBuilder()
                            val szPt = (runSize ?: defaults.fontSize) / 2.0
                            style.append("font-size:${szPt}pt;")
                            if (runFont != null) style.append("font-family:'${runFont}',sans-serif;")
                            if (runColor != null) style.append("color:${runColor};")
                            if (runBold) style.append("font-weight:bold;")
                            if (runItalic) style.append("font-style:italic;")
                            paragraphContent.append("<span style='$style'>").append(escapeHtml(text)).append("</span>")
                        } else sb.append(escapeHtml(text))
                    }
                    parser.isTag("pStyle") -> {
                        val v = parser.getAttributeValue(null, "val") ?: ""
                        if (v.lowercase().contains("heading") || v.contains("标题")) {
                            headingLevel.append(v)
                        }
                    }
                    parser.isTag("jc") -> {
                        paraAlign = parser.getAttributeValue(null, "val") ?: ""
                    }
                    parser.isTag("ind") -> {
                        val l = parser.getAttributeValue(null, "left") ?: "0"
                        paraIndent = l.toDoubleOrNull() ?: 0.0
                    }
                    parser.isTag("spacing") -> {
                        paraLineSpacing = parser.getAttributeValue(null, "line")?.toIntOrNull()
                        paraLineRule = parser.getAttributeValue(null, "lineRule") ?: ""
                        paraSpacingAfter = parser.getAttributeValue(null, "after")?.toIntOrNull()
                        paraSpacingBefore = parser.getAttributeValue(null, "before")?.toIntOrNull()
                    }
                    parser.isTag("tab") -> if (inParagraph) paragraphContent.append("&emsp;")
                    parser.isTag("br") -> if (inParagraph) paragraphContent.append("<br/>")
                    parser.isTag("blip") -> {
                        val rid = parser.attr("embed")
                        val target = rid?.let { rels[it] }
                        if (target != null) {
                            val mediaPath = normalizeRelPath("word", target)
                            val ref = saveMedia(map, mediaPath, assetsDir)
                            if (ref != null) {
                                val img = "<img src='$ref' alt='image'/>"
                                if (inParagraph) paragraphContent.append(img)
                                else sb.append(img)
                            }
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> {
                when {
                    parser.isTag("p") -> flush()
                    parser.isTag("r") -> inRun = false
                    parser.isTag("rPr") -> inRPr = false
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



/** ===== PPTX 幻灯片提取 ===== */

