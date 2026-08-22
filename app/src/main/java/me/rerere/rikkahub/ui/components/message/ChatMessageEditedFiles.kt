package me.rerere.rikkahub.ui.components.message


/* ───【原版对齐】ChatMessageEditedFiles.kt | 差异 ±2 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Share08
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.HtmlRenderDialog
import me.rerere.rikkahub.ui.components.ImageRenderDialog
import me.rerere.rikkahub.ui.components.PdfRenderDialog
import me.rerere.rikkahub.ui.components.RenderKind
import me.rerere.rikkahub.ui.components.VideoRenderDialog
import me.rerere.rikkahub.ui.components.AudioRenderDialog
import me.rerere.rikkahub.ui.components.csvToHtml
import me.rerere.rikkahub.ui.components.detectRenderKind
import me.rerere.rikkahub.ui.components.escapeHtml
import me.rerere.rikkahub.ui.components.extractDocxHtml
import me.rerere.rikkahub.ui.components.extractPptxHtml
import me.rerere.rikkahub.ui.components.extractXlsxHtml
import me.rerere.rikkahub.ui.components.wrapHtml
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File

private const val DEFAULT_VISIBLE_COUNT = 3
private val WORKSPACE_FILE_TOOL_NAMES = setOf("workspace_show_file")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    val editedFiles = remember(parts) {
        parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName in WORKSPACE_FILE_TOOL_NAMES && it.isExecuted }
            .mapNotNull { tool ->
                tool.inputAsJson().jsonObject["path"]?.jsonPrimitive?.contentOrNull
            }
            .distinct()
    }
    if (editedFiles.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    var selectedPath by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var renderHtml by remember { mutableStateOf<String?>(null) }
    var renderFileName by remember { mutableStateOf("") }
    var renderPdfFile by remember { mutableStateOf<java.io.File?>(null) }
    var renderMediaFile by remember { mutableStateOf<java.io.File?>(null) }
    var renderMediaKind by remember { mutableStateOf(RenderKind.NONE) }
    val visibleFiles = if (expanded) editedFiles else editedFiles.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = editedFiles.size > DEFAULT_VISIBLE_COUNT

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val path = selectedPath.also { selectedPath = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val (area, relativePath) = resolveWorkspacePath(path)
                outputStream.use { output ->
                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                }
            }
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleFiles.forEach { path ->
            val fileName = remember(path) { path.substringAfterLast('/') }
            Surface(
                onClick = { selectedPath = path },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 200.dp),
                    )
                }
            }
        }
        if (hasMore && !expanded) {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "+${editedFiles.size - DEFAULT_VISIBLE_COUNT}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (selectedPath != null) {
        val path = selectedPath!!
        val fileName = remember(path) { path.substringAfterLast('/') }
        ModalBottomSheet(
            onDismissRequest = { selectedPath = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        exportLauncher.launch(p.substringAfterLast('/'))
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileImport,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_export),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Card(
                    onClick = {
                        val p = selectedPath ?: return@Card
                        selectedPath = null
                        scope.launch {
                            runCatching {
                                val (area, relativePath) = resolveWorkspacePath(p)
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, p.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                }
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Share08,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                        )
                        Text(
                            text = stringResource(R.string.common_share),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                // v3.9.1: 渲染选项 — 全文档类型 (HTML/SVG/PDF/DOCX/XLSX/CSV/文本),
                // 按格式分发: WebView / PdfRenderer / 提取转 HTML
                if (detectRenderKind(fileName) != RenderKind.NONE) {
                    Card(
                        onClick = {
                            val p = selectedPath ?: return@Card
                            selectedPath = null
                            val kind = detectRenderKind(fileName)
                            scope.launch {
                                runCatching {
                                    val (area, relativePath) = resolveWorkspacePath(p)
                                    renderFileName = p.substringAfterLast('/')
                                    when (kind) {
                                        RenderKind.PDF -> {
                                            val dir = File(context.cacheDir, "workspace_render").apply { mkdirs() }
                                            val file = File(dir, renderFileName)
                                            file.outputStream().use { output ->
                                                workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                            }
                                            renderPdfFile = file
                                        }
                                        RenderKind.IMAGE, RenderKind.VIDEO, RenderKind.AUDIO -> {
                                            val dir = File(context.cacheDir, "workspace_render").apply { mkdirs() }
                                            val file = File(dir, renderFileName)
                                            file.outputStream().use { output ->
                                                workspaceRepository.exportFile(workspaceId, area, relativePath, output)
                                            }
                                            renderMediaFile = file
                                            renderMediaKind = kind
                                        }
                                        RenderKind.DOC -> {
                                            val bytes = readBytes(workspaceRepository, workspaceId, area, relativePath, p)
                                            renderHtml = extractDocxHtml(bytes)
                                        }
                                        RenderKind.SHEET -> {
                                            if (renderFileName.substringAfterLast('.', "").lowercase() == "csv") {
                                                val text = workspaceRepository.readText(workspaceId, relativePath)
                                                renderHtml = csvToHtml(text)
                                            } else {
                                                val bytes = readBytes(workspaceRepository, workspaceId, area, relativePath, p)
                                                renderHtml = extractXlsxHtml(bytes)
                                            }
                                        }
                                        RenderKind.SLIDES -> {
                                            val bytes = readBytes(workspaceRepository, workspaceId, area, relativePath, p)
                                            renderHtml = extractPptxHtml(bytes)
                                        }
                                        RenderKind.TEXT -> {
                                            val text = workspaceRepository.readText(workspaceId, relativePath)
                                            renderHtml = wrapHtml("<pre style='font-family:monospace;white-space:pre-wrap;font-size:13px'>${escapeHtml(text)}</pre>")
                                        }
                                        else -> {
                                            val text = workspaceRepository.readText(workspaceId, relativePath)
                                            renderHtml = text
                                        }
                                    }
                                }.onFailure {
                                    toaster.show("读取文件失败: ${it.message}", type = ToastType.Error)
                                }
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = HugeIcons.FileView,
                                contentDescription = null,
                                modifier = Modifier.padding(4.dp),
                            )
                            Text(
                                text = "渲染",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (renderHtml != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { renderHtml = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            HtmlRenderDialog(
                htmlContent = renderHtml!!,
                fileName = renderFileName,
                onDismiss = { renderHtml = null },
            )
        }
    }
    if (renderPdfFile != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { renderPdfFile = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            PdfRenderDialog(
                pdfFile = renderPdfFile!!,
                fileName = renderFileName,
                onDismiss = { renderPdfFile = null },
            )
        }
    }
    if (renderMediaFile != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { renderMediaFile = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            val mediaFile = renderMediaFile!!
            val mediaName = renderFileName
            when (renderMediaKind) {
                RenderKind.VIDEO -> VideoRenderDialog(
                    videoFile = mediaFile,
                    fileName = mediaName,
                    onDismiss = { renderMediaFile = null },
                )
                RenderKind.AUDIO -> AudioRenderDialog(
                    audioFile = mediaFile,
                    fileName = mediaName,
                    onDismiss = { renderMediaFile = null },
                )
                else -> ImageRenderDialog(
                    imageFile = mediaFile,
                    fileName = mediaName,
                    onDismiss = { renderMediaFile = null },
                )
            }
        }
    }
}

private suspend fun readBytes(
    repository: WorkspaceRepository,
    workspaceId: String,
    area: me.rerere.workspace.WorkspaceStorageArea,
    relativePath: String,
    displayPath: String,
): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    repository.exportFile(workspaceId, area, relativePath, bos)
    return bos.toByteArray()
}

private fun resolveWorkspacePath(path: String): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        WorkspaceStorageArea.FILES to trimmed.removePrefix("/workspace").trimStart('/')
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}
