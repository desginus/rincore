/* 【域 A·对话核心】 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat


/* ───【原版对齐】Export | 差异 +290 行
 * 来源: 原版移植 + 自研 (导出格式扩展)
 * 功能: 会话导出 (图片/文本/Markdown)
 * 差异: 导出格式与样式自研调整
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.ui.context.Navigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.charts.ChartCard
import me.rerere.rikkahub.ui.components.charts.ChartSpec
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.ThinkingStep
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import com.dokar.sonner.rememberToasterState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toLocalString
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var exportOptions by remember { mutableStateOf(ExportOptions()) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))
                // v5.0.93（用户定案）：导出范围必须一眼可见 —— 只含你在对话里勾选的消息
                Text(
                    text = "本次仅导出已勾选的 ${selectedMessages.size} 条消息（未勾选的内容不会出现在文件里）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_page_export_include_reasoning)) },
                        trailingContent = {
                            Switch(
                                checked = exportOptions.includeReasoning,
                                onCheckedChange = {
                                    exportOptions = exportOptions.copy(includeReasoning = it)
                                }
                            )
                        }
                    )
                }

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        // v4.8.185 (审计重写): 图片内联为 data URI 属重活 (读盘+base64) — 移入协程
                        scope.launch {
                            // v5.0.88：真实结果反馈（旧实现无条件弹成功 → 用户以为导出成功但文件不存在）
                            exportToMarkdown(context, conversation, selectedMessages, exportOptions)
                                .onSuccess { savedPath ->
                                    toaster.show(
                                        "$markdownSuccessMessage（已保存到 $savedPath）",
                                        type = ToastType.Success
                                    )
                                }
                                .onFailure { e ->
                                    toaster.show(
                                        "Markdown 导出失败：${e.message ?: e::class.simpleName}",
                                        type = ToastType.Error
                                    )
                                }
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        },

                       ) {
                           Text(stringResource(id = R.string.chat_page_export_markdown))
                       }
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")

                val plainTextSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "TXT")

                OutlinedCard(
                    onClick = {
                        shareAsText(context, conversation, selectedMessages)
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_copy_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        },

                       ) {
                           Text(stringResource(id = R.string.chat_page_export_copy))
                       }
                }

                OutlinedCard(
                    onClick = {
                        // v5.0.93：TXT 也遵循"导出思维过程"开关 + 真落盘 + 真实结果反馈
                        scope.launch {
                            exportToPlainText(context, conversation, selectedMessages, exportOptions)
                                .onSuccess { savedPath ->
                                    toaster.show(
                                        "$plainTextSuccessMessage（已保存到 $savedPath）",
                                        type = ToastType.Success
                                    )
                                }
                                .onFailure { e ->
                                    toaster.show(
                                        "TXT 导出失败：${e.message ?: e::class.simpleName}",
                                        type = ToastType.Error
                                    )
                                }
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_plain_text_desc))
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        },

                       ) {
                           Text(stringResource(id = R.string.chat_page_export_plain_text))
                       }
                }

                // v5.0.93 ★新增：PDF 文档（原生 PdfDocument 排版，A4；思维过程跟随上方开关）
                val pdfSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "PDF")
                OutlinedCard(
                    onClick = {
                        scope.launch {
                            exportToPdf(context, conversation, selectedMessages, exportOptions)
                                .onSuccess { savedPath ->
                                    toaster.show(
                                        "$pdfSuccessMessage（已保存到 $savedPath）",
                                        type = ToastType.Success
                                    )
                                }
                                .onFailure { e ->
                                    toaster.show(
                                        "PDF 导出失败：${e.message ?: e::class.simpleName}",
                                        type = ToastType.Error
                                    )
                                }
                            onDismissRequest()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        supportingContent = {
                            Text("A4 文档（自动分页、中文正常），只含勾选消息的正文")
                        },
                        leadingContent = {
                            Icon(HugeIcons.File02, contentDescription = null)
                        },
                    ) {
                        Text("PDF 文档")
                    }
                }

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(HugeIcons.Image02, contentDescription = null)
                            },

                           ) {
                               Text(stringResource(id = R.string.chat_page_export_image))
                           }

                        HorizontalDivider()

                        ListItem(
                            trailingContent = {
                                Switch(
                                    checked = exportOptions.expandReasoning,
                                    enabled = exportOptions.includeReasoning,
                                    onCheckedChange = {
                                        exportOptions = exportOptions.copy(expandReasoning = it)
                                    }
                                )
                            },

                           ) {
                               Text(stringResource(R.string.chat_page_export_image_expand_reasoning))
                           }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                conversation = conversation,
                                                messages = selectedMessages,
                                                settings = settings,
                                                options = exportOptions
                                            )
                                        }.onSuccess {
                                            toaster.show(
                                                imageSuccessMessage,
                                                type = ToastType.Success
                                            )
                                        }.onFailure {
                                            it.printStackTrace()
                                            toaster.show(
                                                message = "Failed to export image: ${it.message}",
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                    onDismissRequest()
                                }
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun exportToPlainText(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): Result<String> = runCatching {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.txt"

    val sb = buildString {
        appendLine("${conversation.title}")
        appendLine("*Exported on ${LocalDateTime.now().toLocalString()}*")
        appendLine()

        messages.forEach { message ->
            val role = if (message.role == MessageRole.USER) "User" else "Assistant"
            appendLine("$role:")
            appendLine()
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        appendLine(stripMarkdown(part.text))
                    }
                    is UIMessagePart.Image -> {
                        appendLine("[Image]")
                    }
                    is UIMessagePart.Video -> {
                        appendLine("[Video]")
                    }
                    is UIMessagePart.Audio -> {
                        appendLine("[Audio]")
                    }
                    is UIMessagePart.Reasoning -> {
                        // v5.0.93 ★用户定案：未勾选"导出思维过程"时，任何格式都不得出现思考内容
                        if (options.includeReasoning) {
                            appendLine("[Thinking: ${stripMarkdown(part.reasoning)}]")
                        }
                    }
                    is UIMessagePart.Tool -> {
                        appendLine("[Tool: ${part.toolName}]")
                    }
                    is UIMessagePart.Document -> {
                        appendLine("[File: ${part.fileName}]")
                    }
                    else -> {}
                }
            }
            appendLine()
            appendLine("---")
            appendLine()
        }
    }

    // v5.0.93：先真落盘（下载/RinCore），再拉起分享（分享失败不影响"已保存"事实）
    val savedPath = withContext(Dispatchers.IO) {
        saveBytesToDownloads(context, filename, "text/plain", sb.toByteArray(Charsets.UTF_8))
    }
    // 分享用的临时副本在 IO 线程写，startActivity 必须回主线程
    val shareUri = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.appTempFolder
            val file = dir.resolve(filename)
            if (file.exists()) file.delete()
            FileOutputStream(file).use { it.write(sb.toByteArray(Charsets.UTF_8)) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
    withContext(Dispatchers.Main) {
        runCatching { shareUri?.let { shareFile(context, it, "text/plain") } }
    }
    savedPath
}

/** 去除 Markdown 格式标记, 保留纯文本, 适合分享到社交软件 */
private fun stripMarkdown(md: String): String {
    val result = StringBuilder()
    var i = 0
    val len = md.length
    while (i < len) {
        val c = md[i]
        when {
            // 代码块 ```...```
            c == '`' && i + 2 < len && md[i + 1] == '`' && md[i + 2] == '`' -> {
                val end = md.indexOf("```", i + 3)
                if (end >= 0) i = end + 3 else i += 3
            }
            // 行内代码 `...`
            c == '`' -> {
                val end = md.indexOf('`', i + 1)
                if (end >= 0) {
                    result.append(md.substring(i + 1, end))
                    i = end + 1
                } else {
                    result.append(c)
                    i++
                }
            }
            // 链接 [text](url)
            c == '[' -> {
                val closeBracket = md.indexOf(']', i)
                val openParen = if (closeBracket >= 0) closeBracket + 1 else -1
                if (openParen < len && openParen >= 0 && md[openParen] == '(') {
                    val closeParen = md.indexOf(')', openParen)
                    if (closeParen >= 0) {
                        // 保留链接文本
                        result.append(md.substring(i + 1, closeBracket))
                        i = closeParen + 1
                        continue
                    }
                }
                result.append(c)
                i++
            }
            // 图片 ![alt](url)
            c == '!' && i + 1 < len && md[i + 1] == '[' -> {
                val closeBracket = md.indexOf(']', i + 1)
                val openParen = if (closeBracket >= 0) closeBracket + 1 else -1
                if (openParen < len && openParen >= 0 && md[openParen] == '(') {
                    val closeParen = md.indexOf(')', openParen)
                    if (closeParen >= 0) {
                        result.append("[Image]")
                        i = closeParen + 1
                        continue
                    }
                }
                result.append(c)
                i++
            }
            // 粗体 **text** 或 __text__
            ((c == '*' || c == '_') && i + 1 < len && md[i + 1] == c) -> {
                val marker = c
                val endMarker = "$marker$marker"
                val end = md.indexOf(endMarker, i + 2)
                if (end > i + 2) {
                    result.append(md.substring(i + 2, end))
                    i = end + 2
                } else {
                    result.append(c)
                    i++
                }
            }
            // 斜体 *text* 或 _text_ (单标记)
            ((c == '*' || c == '_') && (i + 1 >= len || md[i + 1] != c)) -> {
                val marker = c
                // 找到下一个单独的同标记(不在 \n 后且不在行首)
                val end = md.indexOf(marker.toString(), i + 1)
                if (end > i + 1 && !md.substring(i + 1, end).contains('\n')) {
                    result.append(md.substring(i + 1, end))
                    i = end + 1
                } else {
                    result.append(c)
                    i++
                }
            }
            // 标题 #
            c == '#' && (i == 0 || md[i - 1] == '\n') -> {
                // 跳过标题标记直到遇到空格
                var j = i
                while (j < len && md[j] == '#') j++
                if (j < len && md[j] == ' ') {
                    i = j + 1 // 跳过 # 和空格
                } else {
                    result.append(c)
                    i++
                }
            }
            // 引用 >
            c == '>' && (i == 0 || md[i - 1] == '\n') -> {
                if (i + 1 < len && md[i + 1] == ' ') i += 2 else i++
            }
            // 水平线 ---
            c == '-' && (i == 0 || md[i - 1] == '\n') && i + 2 < len && md[i + 1] == '-' && md[i + 2] == '-' -> {
                // 跳过整行
                while (i < len && md[i] != '\n') i++
                result.appendLine()
            }
            else -> {
                result.append(c)
                i++
            }
        }
    }
    // 压缩连续空行
    return result.toString()
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/** 打开系统分享面板: 将选中对话纯文本通过 ACTION_SEND 分发, 与 share 工具同逻辑 */
private fun shareAsText(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
) {
    val sb = buildString {
        messages.forEachIndexed { index, message ->
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> append(stripMarkdown(part.text))
                    is UIMessagePart.Image -> appendLine("[Image]")
                    is UIMessagePart.Video -> appendLine("[Video]")
                    is UIMessagePart.Audio -> appendLine("[Audio]")
                    is UIMessagePart.Document -> appendLine("[File: ${part.fileName}]")
                    else -> {}
                }
            }
            if (index < messages.size - 1) {
                appendLine()
            }
        }
    }

    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, sb.trim())
    }
    val chooser = android.content.Intent.createChooser(intent, null)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    // v5.0.88：同样排除本应用（避免"分享文本给自己"把整段文字灌进输入框）
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        runCatching {
            val self = context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo }
                .filter { it.packageName == context.packageName }
                .map { android.content.ComponentName(it.packageName, it.name) }
                .toTypedArray()
            if (self.isNotEmpty()) {
                chooser.putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, self)
            }
        }
    }
    context.startActivity(chooser)
}

/**
 * v5.0.93 ★新增 PDF 导出（用户定案：像 markdowntoword.io 那样把 Markdown 简单变成文档，
 * 不需要复杂排版）。实现 = **Android 原生 PdfDocument + StaticLayout**，零第三方依赖、完全离线：
 *  - A4 595×842pt，页边距 44pt；标题 16pt 加粗、正文 11pt、页脚页码 9pt
 *  - 逐行 StaticLayout（自动换行、中文走系统字体），超出页底自动翻页
 *  - 内容只来自入参 messages（= 用户在对话里勾选的消息），**绝不读 conversation.messageNodes**
 *  - 思维过程严格跟随 includeReasoning：未勾选时导出正文里不会出现任何思考内容
 */
private suspend fun exportToPdf(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): Result<String> = runCatching {
    val filename = "chat-export-${
        LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
    }.pdf"

    val blocks = buildList {
        add(PdfBlock(conversation.title, true))
        add(PdfBlock("导出时间：${LocalDateTime.now().toLocalString()}", false))
        add(PdfBlock(" ", false))
        messages.forEach { message ->
            add(PdfBlock(if (message.role == MessageRole.USER) "用户" else "助手", true))
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        stripMarkdown(part.text).split("\n").forEach { add(PdfBlock(it, false)) }
                        add(PdfBlock(" ", false))
                    }

                    is UIMessagePart.Reasoning -> if (options.includeReasoning) {
                        add(PdfBlock("[思考]", false))
                        stripMarkdown(part.reasoning).split("\n").forEach { add(PdfBlock(it, false)) }
                        add(PdfBlock(" ", false))
                    }

                    is UIMessagePart.Tool -> add(PdfBlock("[工具: ${part.toolName}]", false))
                    is UIMessagePart.Image -> add(PdfBlock("[图片]", false))
                    is UIMessagePart.Video -> add(PdfBlock("[视频]", false))
                    is UIMessagePart.Audio -> add(PdfBlock("[音频]", false))
                    is UIMessagePart.Document -> add(PdfBlock("[文件: ${part.fileName}]", false))
                    else -> {}
                }
            }
            add(PdfBlock(" ", false))
        }
    }

    val bytes = withContext(Dispatchers.IO) { renderPdfBytes(blocks) }
    withContext(Dispatchers.IO) {
        saveBytesToDownloads(context, filename, "application/pdf", bytes)
    }
}

private data class PdfBlock(val text: String, val bold: Boolean)

private fun renderPdfBytes(blocks: List<PdfBlock>): ByteArray {
    val pageW = 595
    val pageH = 842
    val margin = 44f
    val contentW = (pageW - margin * 2).toInt()

    val body = android.text.TextPaint().apply {
        textSize = 11f
        color = android.graphics.Color.BLACK
        isAntiAlias = true
    }
    val head = android.text.TextPaint().apply {
        textSize = 16f
        isFakeBoldText = true
        color = android.graphics.Color.BLACK
        isAntiAlias = true
    }
    val footer = android.text.TextPaint().apply {
        textSize = 9f
        color = android.graphics.Color.GRAY
        isAntiAlias = true
    }

    val doc = android.graphics.pdf.PdfDocument()
    var pageNo = 0
    var page: android.graphics.pdf.PdfDocument.Page? = null
    var canvas: android.graphics.Canvas? = null
    var y = margin

    fun startPage() {
        page?.let { doc.finishPage(it) }
        pageNo += 1
        val p = doc.startPage(
            android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create()
        )
        page = p
        canvas = p.canvas
        y = margin
    }

    startPage()
    blocks.forEach { block ->
        val paint = if (block.bold) head else body
        val text = block.text.ifEmpty { " " }
        val layout = android.text.StaticLayout.Builder
            .obtain(text, 0, text.length, paint, contentW)
            .setIncludePad(false)
            .build()
        val h = layout.height.toFloat()
        if (y + h > pageH - margin - 18f) startPage()
        canvas?.let { c ->
            c.save()
            c.translate(margin, y)
            layout.draw(c)
            c.restore()
        }
        y += h + 3f
    }
    canvas?.let { c -> c.drawText("$pageNo", pageW / 2f, pageH - 20f, footer) }
    page?.let { doc.finishPage(it) }

    val out = java.io.ByteArrayOutputStream()
    doc.writeTo(out)
    doc.close()
    return out.toByteArray()
}

private suspend fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions,
): Result<String> = runCatching {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.md"

    // v4.8.185 (审计重写): 组装含 data URI 读盘/base64 — 移出主线程 (闭合见 try 前)。
    val sb = withContext(Dispatchers.IO) {
    buildAnnotatedString {
        append("# ${conversation.title}\n\n")
        append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

        messages.forEach { message ->
            val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
            append("$role:\n\n")
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        // v4.8.185 (审计重写): 本地图片地址内联为 data URI — 导出把图片带走
                        append(inlineLocalImageUrls(part.text))
                        appendLine()
                    }

                    is UIMessagePart.Image -> {
                        val dataUri = localImageDataUri(part.url)
                        append(if (dataUri != null) "![Image]($dataUri)" else "![Image](${part.url})")
                        appendLine()
                    }

                    is UIMessagePart.Reasoning -> {
                        if (!options.includeReasoning) return@forEach
                        part.reasoning.lines()
                            .filter { it.isNotBlank() }
                            .forEach {
                                append("> $it")
                                appendLine()
                            }
                        appendLine()
                    }

                    is UIMessagePart.Tool -> {
                        append("**Tool**: `${part.toolName}`")
                        appendLine()
                        if (part.toolCallId.isNotBlank()) {
                            append("- Call ID: `${part.toolCallId}`")
                            appendLine()
                        }

                        append("Input:")
                        appendLine()
                        append("```json")
                        appendLine()
                        append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                        appendLine()
                        append("```")
                        appendLine()

                        if (part.output.isNotEmpty()) {
                            append("Output:")
                            appendLine()
                            part.output.forEach { outputPart ->
                                when (outputPart) {
                                    is UIMessagePart.Text -> {
                                        append("```text")
                                        appendLine()
                                        append(outputPart.text)
                                        appendLine()
                                        append("```")
                                        appendLine()
                                    }

                                    is UIMessagePart.Reasoning -> {
                                        outputPart.reasoning.lines()
                                            .filter { it.isNotBlank() }
                                            .forEach {
                                                append("> $it")
                                                appendLine()
                                            }
                                    }

                                    is UIMessagePart.Image -> {
                                        val toolDataUri = localImageDataUri(outputPart.url)
                                        append(if (toolDataUri != null) "![Tool Image]($toolDataUri)" else "![Tool Image](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Document -> {
                                        append("[Document: ${outputPart.fileName}](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Video -> {
                                        append("[Video](${outputPart.url})")
                                        appendLine()
                                    }

                                    is UIMessagePart.Audio -> {
                                        append("[Audio](${outputPart.url})")
                                        appendLine()
                                    }

                                    else -> {}
                                }
                            }
                        }
                        appendLine()
                    }

                    else -> {}
                }
            }
            appendLine()
            append("---")
            appendLine()
        }
    }
    } // v4.8.185: withContext(Dispatchers.IO) 闭合

    // v5.0.88 ★用户实测「以 Markdown 文件形式分享 → 根本不写入手机存储」：
    // 旧实现只写 cache/temp（应用缓存，用户在文件管理器看不到），且异常被
    // catch + printStackTrace 吞掉、调用方无条件弹"成功" → 彻底静默失败。
    // 现在：①写入系统「下载/RinCore」（MediaStore，无需权限）②分享面板排除本应用
    // （否则点到自己会把整篇文本回灌输入框）③失败返回 Result.failure 由调用方提示。
    val markdown = sb.toString()
    val dir = context.appTempFolder
    val file = File(dir, filename)
    withContext(Dispatchers.IO) {
        if (file.exists()) file.delete()
        FileOutputStream(file).use { it.write(markdown.toByteArray()) }
    }
    val savedPath = withContext(Dispatchers.IO) {
        saveMarkdownToDownloads(context, filename, markdown)
    }
    // 分享是"附加动作"：FileProvider/chooser 失败不影响"已保存到下载"这一事实
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        shareFile(context, uri, "text/markdown")
    }
    savedPath
}

/**
 * v5.0.88：把导出内容真正写入手机存储（下载/RinCore）。
 * API 29+ 走 MediaStore.Downloads（无需任何权限）；更低版本写公共 Downloads。
 */
private fun saveMarkdownToDownloads(context: Context, filename: String, content: String): String =
    saveBytesToDownloads(context, filename, "text/markdown", content.toByteArray(Charsets.UTF_8))

/** v5.0.93：任意类型导出文件的统一下载落点（Markdown / TXT / PDF 共用）。 */
private fun saveBytesToDownloads(
    context: Context,
    filename: String,
    mimeType: String,
    bytes: ByteArray,
): String {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS + "/RinCore"
            )
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        ) ?: error("无法在下载目录创建文件")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("无法写入下载目录")
        return "下载/RinCore/$filename"
    }
    val dir = File(
        android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ),
        "RinCore"
    )
    if (!dir.exists() && !dir.mkdirs()) error("无法创建下载目录")
    File(dir, filename).writeBytes(bytes)
    return "下载/RinCore/$filename"
}

/** v4.8.185 (审计重写): 导出携图 — 应用本地图片地址词法 (规范形态无空白/引号/括号)。 */
private val LOCAL_IMAGE_URL_REGEX =
    Regex("(?:file:/{1,3}|workspace://)[^\\s\"'`<>\\[\\]()]+")

private val EXPORT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg")

private fun exportMimeFor(ext: String): String = when (ext.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    else -> "image/png"
}

/** 本地图片地址 → data URI (不识别/读失败返回 null, 调用方保留原文本)。 */
private fun localImageDataUri(url: String): String? {
    val trimmed = url.trim()
    if (!me.rerere.rikkahub.utils.isAppLocalImageUri(trimmed)) return null
    val file = me.rerere.rikkahub.utils.resolveLocalImageFile(trimmed) ?: return null
    if (file.extension.lowercase() !in EXPORT_IMAGE_EXTENSIONS) return null
    return runCatching {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) {
            null
        } else {
            "data:${exportMimeFor(file.extension)};base64," +
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }.getOrNull()
}

/** 正文 Markdown: 应用本地图片地址内联为 data URI (单文件自带图片), 其余原样。 */
private fun inlineLocalImageUrls(text: String): String {
    if (!text.contains("file:") && !text.contains("workspace://")) return text
    return LOCAL_IMAGE_URL_REGEX.replace(text) { m ->
        localImageDataUri(m.value) ?: m.value
    }
}

private suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ExportOptions = ExportOptions()
) {
    val filename = "chat-export-${LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))}.png"
    val composer = BitmapComposer(scope)
    val activity = context.getActivity() ?: error("Failed to get activity")

    val bitmap = composer.composableToBitmap(
        activity = activity,
        width = 540.dp,
        screenDensity = density,
        content = {
            CompositionLocalProvider(LocalSettings provides settings) {
                ExportedChatImage(
                    conversation = conversation,
                    messages = messages,
                    options = options
                )
            }
        }
    )

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
        }

        // Save to gallery
        context.exportImage(activity, bitmap, filename)

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "image/png")
    } finally {
        bitmap.recycle()
    }
}

data class ExportOptions(
    // v5.0.93 ★用户定案：默认**不导出**思维过程（导出默认只要正文；
    // 需要思考链时手动打开上方开关）。关闭时 Markdown/TXT/PDF/图片四种格式全部不出现思考内容。
    val includeReasoning: Boolean = false,
    val expandReasoning: Boolean = false,
)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ExportOptions = ExportOptions()
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toasterState
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = "${LocalDateTime.now().toLocalString()}  rikka-ai.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_foreground)
                        Image(
                            painter = painter,
                            contentDescription = "Logo",
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    messages.forEachIndexed { index, message ->
                        ExportedChatMessage(
                            message = message,
                            options = options,
                            prevMessage = messages.getOrNull(index - 1)
                        )
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    prevMessage: UIMessage? = null,
    options: ExportOptions = ExportOptions()
) {
    val parts = remember(message.parts, options.includeReasoning) {
        if (options.includeReasoning) message.parts else message.parts.filterNot { it is UIMessagePart.Reasoning }
    }
    if (parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelId?.let { settings.findModelById(it) }
    // Always show model icon for assistant messages in exported images
    val showModelIcon = message.role == MessageRole.ASSISTANT && prevMessage?.role == MessageRole.USER
    val iconLabel = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    val groupedParts = remember(parts) { parts.groupMessageParts() }
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            ChainOfThought(
                                steps = block.steps,
                                collapsedVisibleCount = block.steps.size
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        ExportedReasoningStep(
                                            reasoning = step.reasoning,
                                            expanded = options.expandReasoning
                                        )
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        ExportedToolStep(
                                            tool = step.tool
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ContentBlock -> {
                        when (val part = block.part) {
                            is UIMessagePart.Text -> {
                                if (part.text.isNotBlank()) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        if (message.role == MessageRole.USER) {
                                            Card(
                                                shape = MaterialTheme.shapes.medium,
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        } else {
                                            if (settings.displaySetting.showAssistantBubble) {
                                                Card(
                                                    shape = MaterialTheme.shapes.medium,
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                ) {
                                                    MarkdownBlock(
                                                        content = part.text,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                MarkdownBlock(
                                                    content = part.text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is UIMessagePart.Image -> {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(part.url)
                                        .allowHardware(false)
                                        .crossfade(false)
                                        .build(),
                                    contentDescription = "Image",
                                    modifier = Modifier
                                        .sizeIn(maxHeight = 300.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }

                            else -> {
                                // Other parts are not rendered in image export for now
                            }
                        }
                    }

                    // v4.8.94 (图表工具链): 导出同样渲染图表卡片
                    is MessagePartBlock.ChartBlock -> {
                        val spec = remember(block.tool.input) { ChartSpec.fromJson(block.tool.inputAsJson()) }
                        spec?.let { ChartCard(spec = it) }
                    }
                }
            }
        }
    }

    if (showModelIcon) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            AutoAIIcon(
                name = iconLabel,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
            )

            Text(
                text = iconLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    messageContent()
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            null
        },
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(
    tool: UIMessagePart.Tool
) {
    val memoryAction = runCatching {
        tool.inputAsJson().jsonObject["action"]?.jsonPrimitiveOrNull?.contentOrNull
    }.getOrNull()
    val title = when (tool.toolName) {
        "memory_tool" -> when (memoryAction) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "memory_tool" -> when (memoryAction) {
                        "create", "edit" -> HugeIcons.Book04
                        "delete" -> HugeIcons.Book02
                        else -> HugeIcons.Wrench01
                    }

                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    else -> HugeIcons.Wrench01
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false,
        content = null,
    )
}

private fun shareFile(context: Context, uri: Uri, mimeType: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = android.content.Intent.createChooser(
        intent,
        context.getString(R.string.chat_page_export_share_via)
    )
    // v5.0.88 ★排除本应用：导出分享的 chooser 里点到自己 → ACTION_SEND 回灌 ChatPage
    //（整篇 Markdown 进输入框并被写成草稿，重启后仍在——本次用户实测的总链条之一）
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        runCatching {
            val self = context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo }
                .filter { it.packageName == context.packageName }
                .map { android.content.ComponentName(it.packageName, it.name) }
                .toTypedArray()
            if (self.isNotEmpty()) {
                chooser.putExtra(android.content.Intent.EXTRA_EXCLUDE_COMPONENTS, self)
            }
        }
    }
    context.startActivity(chooser)
}
