/* 【域 C·对话页】 — 导出 | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.ai.core.MessageRole
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import android.text.Spanned

/**
 * v5.0.94 ★PDF 导出重写（用户实测三条：①工具调用未过滤 ②本地图片未带入 ③没有任何 Markdown 渲染、无法读取）。
 *
 * 上一版（v5.0.93）用 `stripMarkdown` 把正文压成纯文本行 → 标题/列表/代码块全部丢失，读起来是一坨；
 * 且 Tool 分支照原样输出 `[工具: xxx]`；图片只写了 `[图片]` 占位。
 *
 * 本版：
 *  - **真 Markdown 渲染**：标题（#~####，16/14/12.5pt 加粗）、无序/有序列表（•/n.，按缩进层级）、
 *    代码块（等宽 9.5pt + 浅灰底）、引用（灰字 + 左侧竖线）、分隔线、段落；行内 `**粗**` / `*斜*` / `` `代码` ``
 *    走 Spannable（StyleSpan / TypefaceSpan / BackgroundColorSpan），用 StaticLayout 排版 —— 中文正常换行
 *  - **工具调用一律不输出**（用户定案：PDF 只要正文；Document 附件保留一行文件名）
 *  - **图片真内嵌**：`UIMessagePart.Image` 与正文里的 `![alt](url)` 都会被解析（data URI / file:// / 应用本地路径 /
 *    workspace://）→ BitmapFactory 解码 → 等比缩放到正文宽度绘制；同一 URL 只画一次
 *  - 每页页脚都有页码（上一版只有末页）
 */
private const val PDF_PAGE_W = 595
private const val PDF_PAGE_H = 842
private const val PDF_MARGIN = 44f
private const val PDF_CONTENT_W = (PDF_PAGE_W - PDF_MARGIN * 2).toInt()

private sealed interface PdfNode {
    data class Heading(val level: Int, val text: String) : PdfNode
    data class Para(val text: String) : PdfNode
    data class Bullet(val text: String, val ordered: Boolean, val index: Int, val depth: Int) : PdfNode
    data class Code(val text: String) : PdfNode
    data class Quote(val text: String) : PdfNode
    data object Divider : PdfNode
    data class Picture(val bitmap: Bitmap) : PdfNode
    data class Gap(val height: Float) : PdfNode
}

internal suspend fun exportConversationPdf(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): Result<String> = runCatching {
    val filename = "chat-export-${
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    }.pdf"

    val nodes = withContext(Dispatchers.IO) {
        buildPdfNodes(conversation, messages, options)
    }
    val bytes = withContext(Dispatchers.IO) { renderPdfDocument(nodes) }
    withContext(Dispatchers.IO) {
        saveBytesToDownloads(context, filename, "application/pdf", bytes)
    }
}

private fun buildPdfNodes(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): List<PdfNode> {
    val out = mutableListOf<PdfNode>()
    val seenImages = mutableSetOf<String>()

    out += PdfNode.Heading(1, conversation.title.ifBlank { "对话导出" })
    out += PdfNode.Para("导出时间：${LocalDateTime.now()}")
    out += PdfNode.Divider

    messages.forEach { message ->
        out += PdfNode.Heading(
            2,
            if (message.role == MessageRole.USER) "用户" else "助手",
        )
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> appendMarkdown(out, part.text, seenImages)
                is UIMessagePart.Reasoning -> if (options.includeReasoning) {
                    out += PdfNode.Heading(3, "思考过程")
                    appendMarkdown(out, part.reasoning, seenImages)
                }

                is UIMessagePart.Image -> {
                    if (seenImages.add(part.url)) {
                        decodeLocalImage(part.url)?.let { out += PdfNode.Picture(it) }
                    }
                }

                is UIMessagePart.Document -> out += PdfNode.Para("［附件：${part.fileName}］")
                // Tool / Video / Audio：**刻意不输出**（用户定案：PDF 只要正文）
                else -> Unit
            }
        }
        out += PdfNode.Gap(12f)
    }
    return out
}

/** 逐行解析 Markdown（够用的子集：标题/列表/代码块/引用/分隔线/图片/段落 + 行内样式）。 */
private fun appendMarkdown(out: MutableList<PdfNode>, markdown: String, seenImages: MutableSet<String>) {
    val lines = markdown.replace("\r\n", "\n").split("\n")
    val paragraph = StringBuilder()

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) out += PdfNode.Para(text)
        paragraph.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        val trimmed = line.trim()

        // 代码块
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val code = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                code.appendLine(lines[i])
                i++
            }
            out += PdfNode.Code(code.toString().trimEnd())
            i++
            continue
        }

        when {
            trimmed.isEmpty() -> flushParagraph()

            trimmed.matches(Regex("""^([-*_])\s*\1\s*\1[\s\-*_]*$""")) -> {
                flushParagraph()
                out += PdfNode.Divider
            }

            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out += PdfNode.Heading(level, trimmed.drop(level).trim())
            }

            trimmed.startsWith("> ") || trimmed == ">" -> {
                flushParagraph()
                out += PdfNode.Quote(trimmed.removePrefix(">").trim())
            }

            // 独占一行的图片
            Regex("""^!\[([^\]]*)]\((.*?)\)$""").find(trimmed) != null -> {
                flushParagraph()
                val match = Regex("""^!\[([^\]]*)]\((.*?)\)$""").find(trimmed)!!
                val url = match.groupValues[2]
                if (seenImages.add(url)) {
                    decodeLocalImage(url)?.let { out += PdfNode.Picture(it) }
                }
            }

            Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""").find(line) != null -> {
                flushParagraph()
                val match = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""").find(line)!!
                val indent = match.groupValues[1].length / 2
                val marker = match.groupValues[2]
                val ordered = marker[0].isDigit()
                val index = if (ordered) marker.filter { it.isDigit() }.toIntOrNull() ?: 1 else 0
                out += PdfNode.Bullet(match.groupValues[3], ordered, index, indent.coerceAtMost(3))
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        i++
    }
    flushParagraph()
}

/** 解析本地/内联图片为 Bitmap（data URI、file://、应用私有路径、workspace://）。 */
private fun decodeLocalImage(url: String): Bitmap? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("data:image", ignoreCase = true)) {
        val comma = trimmed.indexOf(',')
        if (comma < 0) return null
        return runCatching {
            val bytes = Base64.decode(trimmed.substring(comma + 1), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { shrinkForPdf(it) }
        }.getOrNull()
    }

    val file = runCatching { me.rerere.rikkahub.utils.resolveLocalImageFile(trimmed) }.getOrNull()
        ?: return null
    if (!file.isFile) return null
    return runCatching {
        // 先只读尺寸算采样率（多张 20MP 原图直接解码必 OOM）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.onFailure { Log.d("ExportPdf", "decode failed: ${it.message}") }
        .getOrNull()

/** data URI 图不超过 1600px（PDF 正文宽度 507pt，1600px 已远超印刷需要）。 */
private fun shrinkForPdf(bitmap: Bitmap): Bitmap {
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide <= 1600) return bitmap
    val ratio = 1600f / maxSide
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}
}

private fun renderPdfDocument(nodes: List<PdfNode>): ByteArray {
    val bodyPaint = TextPaint().apply {
        textSize = 11f
        color = Color.BLACK
        isAntiAlias = true
    }
    val codePaint = TextPaint().apply {
        textSize = 9.5f
        color = Color.rgb(40, 40, 40)
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }
    val quotePaint = TextPaint().apply {
        textSize = 11f
        color = Color.rgb(90, 90, 90)
        isAntiAlias = true
    }
    val footerPaint = TextPaint().apply {
        textSize = 9f
        color = Color.GRAY
        isAntiAlias = true
    }
    val dividerPaint = android.graphics.Paint().apply {
        color = Color.rgb(200, 200, 200)
        strokeWidth = 1f
    }
    val imagePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val codeBgPaint = android.graphics.Paint().apply { color = Color.rgb(245, 245, 245) }
    val quoteBarPaint = android.graphics.Paint().apply {
        color = Color.rgb(190, 190, 190)
        strokeWidth = 2.5f
    }

    fun headingPaint(level: Int): TextPaint = TextPaint().apply {
        textSize = when (level) {
            1 -> 20f
            2 -> 15f
            3 -> 13f
            else -> 11.5f
        }
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = if (level <= 2) Color.BLACK else Color.rgb(45, 45, 45)
        isAntiAlias = true
    }

    val document = PdfDocument()
    var pageNo = 0
    var page: PdfDocument.Page? = null
    var canvas: android.graphics.Canvas? = null
    var y = PDF_MARGIN
    val bottom = PDF_PAGE_H - PDF_MARGIN - 16f

    fun drawFooter() {
        canvas?.drawText("$pageNo", PDF_PAGE_W / 2f, PDF_PAGE_H - 20f, footerPaint)
    }

    fun startPage() {
        page?.let {
            drawFooter()
            document.finishPage(it)
        }
        pageNo += 1
        val p = document.startPage(PdfDocument.PageInfo.Builder(PDF_PAGE_W, PDF_PAGE_H, pageNo).create())
        page = p
        canvas = p.canvas
        y = PDF_MARGIN
    }

    fun ensure(need: Float) {
        if (y + need > bottom) startPage()
    }

    fun drawLayout(text: CharSequence, paint: TextPaint, width: Int = PDF_CONTENT_W, left: Float = PDF_MARGIN) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.15f)
            .build()
        val h = layout.height.toFloat()
        // 整块放不下但本页还是空的 → 逐行绘制（避免无限递归）
        if (y + h > bottom && y > PDF_MARGIN + 1f) startPage()
        if (y + h <= bottom) {
            canvas?.let { c ->
                c.save()
                c.translate(left, y)
                layout.draw(c)
                c.restore()
            }
            y += h
        } else {
            // 单块比一整页还长：按行切
            for (line in 0 until layout.lineCount) {
                val lineTop = layout.getLineTop(line)
                val lineBottom = layout.getLineBottom(line)
                val lineH = (lineBottom - lineTop).toFloat()
                if (y + lineH > bottom) startPage()
                val c = canvas ?: break
                c.save()
                c.translate(left - layout.getLineLeft(line), y - lineTop)
                layout.draw(c)
                c.restore()
                y += lineH + 1f
            }
        }
    }

    startPage()
    nodes.forEach { node ->
        when (node) {
            is PdfNode.Heading -> {
                ensure(34f)
                y += 6f
                drawLayout(inlineStyled(node.text), headingPaint(node.level))
                y += 6f
            }

            is PdfNode.Para -> drawLayout(inlineStyled(node.text), bodyPaint)

            is PdfNode.Bullet -> {
                val indent = PDF_MARGIN + node.depth * 14f
                val marker = if (node.ordered) "${node.index}. " else "• "
                val markerWidth = 14f
                val textWidth = (PDF_CONTENT_W - (indent - PDF_MARGIN) - markerWidth).toInt()
                // 注意：inlineStyled 去掉了 ** ` 等标记，长度会变短 —— end 必须取处理后的长度
                val bulletText = inlineStyled(node.text)
                val layout = StaticLayout.Builder
                    .obtain(bulletText, 0, bulletText.length, bodyPaint, textWidth)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.12f)
                    .build()
                val h = layout.height.toFloat()
                ensure(h)
                canvas?.let { c ->
                    c.drawText(marker, indent, y + bodyPaint.textSize, bodyPaint)
                    c.save()
                    c.translate(indent + markerWidth, y)
                    layout.draw(c)
                    c.restore()
                }
                y += h
            }

            is PdfNode.Code -> {
                y += 4f
                node.text.split("\n").forEach { codeLine ->
                    val layout = StaticLayout.Builder
                        .obtain(codeLine.ifEmpty { " " }, 0, codeLine.length, codePaint, PDF_CONTENT_W - 8)
                        .setIncludePad(false)
                        .build()
                    val h = layout.height.toFloat()
                    ensure(h + 2f)
                    canvas?.let { c ->
                        c.drawRect(
                            PDF_MARGIN - 2f, y - 1f,
                            PDF_MARGIN + PDF_CONTENT_W + 2f, y + h + 1f,
                            codeBgPaint,
                        )
                        c.save()
                        c.translate(PDF_MARGIN + 4f, y)
                        layout.draw(c)
                        c.restore()
                    }
                    y += h + 1f
                }
                y += 6f
            }

            is PdfNode.Quote -> {
                val quoteText = inlineStyled(node.text)
                val layout = StaticLayout.Builder
                    .obtain(quoteText, 0, quoteText.length, quotePaint, PDF_CONTENT_W - 12)
                    .setIncludePad(false)
                    .build()
                val h = layout.height.toFloat()
                ensure(h)
                canvas?.let { c ->
                    c.drawLine(PDF_MARGIN, y, PDF_MARGIN, y + h, quoteBarPaint)
                    c.save()
                    c.translate(PDF_MARGIN + 10f, y)
                    layout.draw(c)
                    c.restore()
                }
                y += h + 4f
            }

            PdfNode.Divider -> {
                ensure(12f)
                y += 5f
                canvas?.drawLine(PDF_MARGIN, y, PDF_MARGIN + PDF_CONTENT_W, y, dividerPaint)
                y += 7f
            }

            is PdfNode.Picture -> {
                val bmp = node.bitmap
                if (bmp.width <= 0 || bmp.height <= 0) return@forEach
                var w = PDF_CONTENT_W.toFloat()
                var h = w * bmp.height / bmp.width
                val maxH = bottom - PDF_MARGIN - 8f
                if (h > maxH) {
                    h = maxH
                    w = h * bmp.width / bmp.height
                }
                ensure(h + 6f)
                canvas?.let { c ->
                    c.drawBitmap(bmp, null, RectF(PDF_MARGIN, y, PDF_MARGIN + w, y + h), imagePaint)
                }
                y += h + 8f
            }

            is PdfNode.Gap -> y += node.height
        }
    }

    page?.let {
        drawFooter()
        document.finishPage(it)
    }

    val out = java.io.ByteArrayOutputStream()
    document.writeTo(out)
    document.close()
    return out.toByteArray()
}

/**
 * 行内 Markdown → Spannable：**粗** / __粗__ / *斜* / _斜_ / `代码` / [文字](链接)。
 * **标记字符会被真正去掉**（旧实现只挂 span 不去星号，屏幕上还是 `**粗**`，等于没渲染）。
 */
private fun inlineStyled(text: String): CharSequence {
    val out = StringBuilder(text.length)
    val spans = mutableListOf<Triple<Int, Int, Any>>()
    var i = 0

    while (i < text.length) {
        val c = text[i]
        when {
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    val from = out.length
                    out.append(text, i + 1, end)
                    spans += Triple(from, out.length, TypefaceSpan("monospace"))
                    i = end + 1
                } else {
                    out.append(c); i++
                }
            }

            c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    val from = out.length
                    out.append(text, i + 2, end)
                    spans += Triple(from, out.length, StyleSpan(Typeface.BOLD))
                    i = end + 2
                } else {
                    out.append(c); i++
                }
            }

            c == '_' && i + 1 < text.length && text[i + 1] == '_' -> {
                val end = text.indexOf("__", i + 2)
                if (end > i + 2) {
                    val from = out.length
                    out.append(text, i + 2, end)
                    spans += Triple(from, out.length, StyleSpan(Typeface.BOLD))
                    i = end + 2
                } else {
                    out.append(c); i++
                }
            }

            c == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && !text.substring(i + 1, end).contains('\n')) {
                    val from = out.length
                    out.append(text, i + 1, end)
                    spans += Triple(from, out.length, StyleSpan(Typeface.ITALIC))
                    i = end + 1
                } else {
                    out.append(c); i++
                }
            }

            c == '_' -> {
                val end = text.indexOf('_', i + 1)
                if (end > i + 1 && !text.substring(i + 1, end).contains('\n')) {
                    val from = out.length
                    out.append(text, i + 1, end)
                    spans += Triple(from, out.length, StyleSpan(Typeface.ITALIC))
                    i = end + 1
                } else {
                    out.append(c); i++
                }
            }

            // [文字](链接) → 只保留文字
            c == '[' -> {
                val close = text.indexOf(']', i + 1)
                if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                    val paren = text.indexOf(')', close + 2)
                    if (paren > close) {
                        out.append(text, i + 1, close)
                        i = paren + 1
                        continue
                    }
                }
                out.append(c); i++
            }

            else -> {
                out.append(c); i++
            }
        }
    }

    val spannable = SpannableString(out.toString())
    spans.forEach { (from, to, span) ->
        if (from >= 0 && to <= spannable.length && from < to) {
            spannable.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return spannable
}
