package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * 4.0.2: 工作区文档预览内容抽取 — 零新依赖 (OOXML=zip+XML, JDK 内置解析)。
 * docx/pptx/xlsx 抽取结构化文本供预览; 旧版二进制 (doc/ppt/xls) 无法纯解析,
 * 返回 null 走外部打开引导。
 */
internal object DocumentPreview {

    /** 文档类扩展名 → 是否可抽取 */
    val EXTRACTABLE_EXTS = setOf("docx", "pptx", "xlsx", "epub")
    val BINARY_LEGACY_EXTS = setOf("doc", "ppt", "xls")

    /**
     * 抽取文档文本大纲。失败/不可抽取返回 null (调用方显示引导)。
     * 输出截断由调用方负责 (与文本预览同一 200KB 上限)。
     */
    fun extract(file: File, ext: String): String? = runCatching {
        when (ext) {
            "docx" -> extractDocx(file)
            "pptx" -> extractPptx(file)
            "xlsx" -> extractXlsx(file)
            "epub" -> extractEpub(file)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** docx: word/document.xml 的 w:p 段落 / w:t 文本 */
    private fun extractDocx(file: File): String? {
        val text = collectXmlText(file, "word/document.xml")
        ?: return null
        return normalizeParagraphs(text)
    }

    /** pptx: 每张 slide 独立解析, 页前缀分隔 */
    private fun extractPptx(file: File): String? {
        ZipFile(file).use { zip ->
            val slides = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { it.filter { ch -> ch.isDigit() }.toInt() }
                .toList()
            if (slides.isEmpty()) return null
            val sb = StringBuilder()
            slides.forEachIndexed { index, name ->
                val entry = zip.getEntry(name)
                if (entry != null) {
                    val texts = readXmlTexts(zip.getInputStream(entry), "a:t", "p:t")
                    sb.append("── 第 ").append(index + 1).append(" 页 ──\n")
                    if (texts.isEmpty()) sb.append("(无文本内容)\n")
                    else texts.forEach { sb.append(it.trim()).append('\n') }
                    sb.append('\n')
                }
            }
            return sb.toString()
        }
    }

    /** xlsx: sharedStrings 字符串池 + 每个 sheet 按行拼单元格 */
    private fun extractXlsx(file: File): String? {
        ZipFile(file).use { zip ->
            val sharedEntry = zip.getEntry("xl/sharedStrings.xml")
            val shared = if (sharedEntry != null) {
                readXmlTexts(zip.getInputStream(sharedEntry), "t").filter { it.isNotBlank() }
            } else emptyList()

            val sheets = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.filter { ch -> ch.isDigit() }.toInt() }
                .toList()
            if (sheets.isEmpty() && shared.isEmpty()) return null
            val sb = StringBuilder()
            sheets.forEachIndexed { index, name ->
                sb.append("── 工作表 ").append(index + 1).append(" ──\n")
                val entry = zip.getEntry(name) ?: return@forEachIndexed
                val rows = parseSheetRows(zip.getInputStream(entry), shared)
                rows.forEach { row ->
                    sb.append(row.joinToString(" | ")).append('\n')
                }
                sb.append('\n')
            }
            return sb.toString()
        }
    }

    /** epub: 容器找出 OPF → spine 文档顺序抽 XHTML 文本 (简版: 直接抽全部 xhtml) */
    private fun extractEpub(file: File): String? {
        ZipFile(file).use { zip ->
            val htmls = zip.entries().asSequence()
                .map { it.name }
                .filter { it.endsWith(".xhtml") || it.endsWith(".html") }
                .sorted()
                .toList()
            if (htmls.isEmpty()) return null
            val sb = StringBuilder()
            htmls.forEach { name ->
                val entry = zip.getEntry(name)
                if (entry != null) {
                    val texts = readXmlTexts(zip.getInputStream(entry), "p", "h1", "h2", "h3", "h4", "div")
                    if (texts.isNotEmpty()) {
                        sb.append("── ").append(name.substringAfterLast('/')).append(" ──\n")
                        texts.forEach { sb.append(it.trim()).append('\n') }
                        sb.append('\n')
                    }
                }
            }
            return sb.toString()
        }
    }

    /** 单 XML 文件内指定标签的全部文本 (SAX 流式, 防 XXE 禁 external) */
    private fun collectXmlText(file: File, entryPath: String): String? {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryPath) ?: return null
            val texts = readXmlTexts(zip.getInputStream(entry), "w:t", "a:t")
            return normalizeParagraphs(texts.joinToString("\n"))
        }
    }

    /** sheet1.xml 行解析: row 内 c 单元格 (v=直接值, t=s 时引 sharedStrings 索引) */
    private fun parseSheetRows(input: java.io.InputStream, shared: List<String>): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        val parser = SAXParserFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }.newSAXParser()
        val handler = object : DefaultHandler() {
            var inValue = false
            var cellIsShared = false
            val buffer = StringBuilder()
            var currentRow: MutableList<String>? = null

            override fun startElement(uri: String?, local: String?, q: String?, attrs: Attributes?) {
                when (q) {
                    "row" -> { currentRow = mutableListOf(); rows.add(currentRow!!) }
                    "c" -> {
                        buffer.setLength(0)
                        cellIsShared = attrs?.getValue("t") == "s"
                        inValue = false
                    }
                    "v" -> { inValue = true; buffer.setLength(0) }
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inValue) buffer.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, q: String?) {
                when (q) {
                    "v" -> {
                        val raw = buffer.toString()
                        val value = if (cellIsShared) shared.getOrNull(raw.toIntOrNull() ?: -1) ?: "" else raw
                        currentRow?.add(value)
                        inValue = false
                    }
                    "row" -> { currentRow = null }
                }
            }
        }
        parser.parse(input, handler)
        return rows
    }

    /** 多标签文本 SAX 抽取 (任一匹配标签的文本内容) */
    private fun readXmlTexts(input: java.io.InputStream, vararg tags: String): List<String> {
        val wanted = tags.toSet()
        val out = mutableListOf<String>()
        val parser = SAXParserFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }.newSAXParser()
        val handler = object : DefaultHandler() {
            var inText = false
            val buffer = StringBuilder()

            override fun startElement(uri: String?, local: String?, q: String?, attrs: Attributes?) {
                if (q in wanted) { inText = true; buffer.setLength(0) }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (inText) buffer.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, q: String?) {
                if (q in wanted) {
                    out.add(buffer.toString())
                    inText = false
                }
            }
        }
        parser.parse(input, handler)
        return out
    }

    /** 段落规范化: 连续空行折叠 + 空白行修剪 */
    private fun normalizeParagraphs(raw: String): String {
        val lines = raw.split('\n')
            .map { it.trim() }
        val sb = StringBuilder()
        var blankRun = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blankRun++
                if (blankRun == 1) sb.append('\n')
            } else {
                blankRun = 0
                sb.append(line).append('\n')
            }
        }
        return sb.toString()
    }
}

/**
 * 4.0.2: PDF 内置预览 — android.graphics.pdf.PdfRenderer 前 3 页位图。
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.PdfPreviewPages(file: java.io.File) {
    val pages = remember(file.absolutePath) {
        runCatching {
            val renderer = android.graphics.pdf.PdfRenderer(
                android.os.ParcelFileDescriptor.open(
                    file,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ),
            )
            val pageCount = renderer.pageCount
            val bitmaps = mutableListOf<android.graphics.Bitmap>()
            try {
                for (i in 0 until minOf(3, pageCount)) {
                    renderer.openPage(i).use { page ->
                        val scale = 2
                        val bmp = android.graphics.Bitmap.createBitmap(
                            page.width * scale, page.height * scale,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmaps.add(bmp)
                    }
                }
            } finally {
                renderer.close()
            }
            bitmaps to pageCount
        }.getOrNull()
    }
    if (pages == null) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text("无法解析 PDF", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val (bitmaps, pageCount) = pages
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
    ) {
        bitmaps.forEach { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
        if (pageCount > bitmaps.size) {
            Text(
                "共 $pageCount 页, 已显示前 ${bitmaps.size} 页 (导出后可查看全部)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}
