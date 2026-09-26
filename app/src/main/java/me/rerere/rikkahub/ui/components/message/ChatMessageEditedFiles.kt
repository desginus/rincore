/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.ui.components.RenderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.render.RenderEngine
import me.rerere.rikkahub.ui.components.render.RenderResult
import me.rerere.rikkahub.ui.components.render.RenderViewDialog
import me.rerere.rikkahub.ui.components.detectRenderKind
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val DEFAULT_VISIBLE_COUNT = 3
private val WORKSPACE_FILE_TOOL_NAMES = setOf("workspace_show_file")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
    folderCwd: String? = null,
) {
    val workspaceId = assistant?.workspaceId?.toString() ?: return
    // v4.8.57: 多候选 CWD (项目包优先 → 助手级) — 文件可能在项目包 CWD 下生成,
    // 此前仅用助手级 cwd 解析 → 错位 → "File does not exist" / "读取文件失败"
    // (用户实证: 某助手项目包内的胶囊窗文件无法分享/渲染)。与 v4.8.25 effectiveCwd
    // 口径一致: 项目包优先, 助手级兜底; 导出层再做无 cwd 兜底。
    val cwdCandidates = remember(assistant, folderCwd) {
        listOfNotNull(normalizeCwdRel(folderCwd), normalizeCwdRel(assistant?.workspaceCwd)).distinct()
    }
    val editedFiles = remember(parts) {
        parts.filterIsInstance<UIMessagePart.Tool>()
            .filter { it.toolName in WORKSPACE_FILE_TOOL_NAMES && it.isExecuted }
            .mapNotNull { tool ->
                (tool.inputAsJson() as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull
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
    var renderResult by remember { mutableStateOf<RenderResult?>(null) }
    var renderFileName by remember { mutableStateOf("") }
    var renderLoading by remember { mutableStateOf(false) }
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
                outputStream.use { output ->
                    exportScopedFile(workspaceRepository, workspaceId, path, cwdCandidates, output)
                }
            }.onFailure { e ->
                toaster.show(
                    message = "导出失败: ${e.message?.take(120) ?: "未知错误"}",
                    type = ToastType.Error,
                )
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
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, p.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    exportScopedFile(workspaceRepository, workspaceId, p, cwdCandidates, output)
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
                            }.onFailure { e ->
                                toaster.show(
                                    message = "分享失败: ${e.message?.take(120) ?: "未知错误"}",
                                    type = ToastType.Error,
                                )
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
                // v3.9.6: 渲染机统一入口 — 导出缓存文件 → RenderEngine.render
                if (detectRenderKind(fileName) != RenderKind.NONE) {
                    Card(
                        onClick = {
                            val p = selectedPath ?: return@Card
                            selectedPath = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        renderFileName = p.substringAfterLast('/')
                                        val dir = File(context.cacheDir, "workspace_render")
                                        dir.deleteRecursively()
                                        dir.mkdirs()
                                        val file = File(dir, renderFileName)
                                        file.outputStream().use { output ->
                                            exportScopedFile(workspaceRepository, workspaceId, p, cwdCandidates, output)
                                        }
                                        val taskDir = File(dir, "task")
                                        renderResult = RenderEngine.render(file, taskDir, renderFileName)
                                    }.onFailure {
                                        renderResult = RenderResult.Unsupported(
                                            renderFileName,
                                            "读取文件失败: ${it.message}",
                                        )
                                    }
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

    if (renderResult != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { renderResult = null },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            RenderViewDialog(
                result = renderResult!!,
                onDismiss = { renderResult = null },
            )
        }
    }
}

private fun resolveWorkspacePath(path: String, cwdRel: String? = null): Pair<WorkspaceStorageArea, String> {
    val trimmed = path.trimEnd('/')
    return if (trimmed == "/workspace" || trimmed.startsWith("/workspace/")) {
        val rel = trimmed.removePrefix("/workspace").trimStart('/')
        // v4.5.27: CWD 专一空间 — 模型视角路径映射到助手文件夹;
        // 已含 cwd 前缀 (历史惯性) 的不再二次拼接。
        val scoped = when {
            cwdRel.isNullOrEmpty() || rel.isEmpty() -> rel
            rel == cwdRel || rel.startsWith("$cwdRel/") -> rel
            else -> "$cwdRel/$rel"
        }
        WorkspaceStorageArea.FILES to scoped
    } else {
        WorkspaceStorageArea.LINUX to trimmed.trimStart('/')
    }
}

/** v4.8.57: CWD 归一化 (同 WorkspaceTools 口径), null/空 = 无约束。
 *  接受助手级 workspaceCwd 与项目包 cwd (含 "/workspace/xxx" 绝对形态)。 */
private fun normalizeCwdRel(raw: String?): String? {
    val trimmed = raw?.trim('/') ?: return null
    val rel = (if (trimmed == "workspace") "" else trimmed.removePrefix("workspace/")).trim('/')
    return rel.ifBlank { null }
}

/** v4.8.57: 多候选 CWD 导出 — 依次尝试 (项目包 cwd → 助手 cwd → 无 cwd 兜底),
 *  首个命中即成功; 全部失败抛最后错误。失败均为写前解析失败 (require exists),
 *  重试无部分写入风险。根因: 文件可能在项目包 CWD 下生成, 单助手级 cwd 解析
 *  错位 → "File does not exist" (用户实证胶囊窗分享/渲染失败)。 */
private suspend fun exportScopedFile(
    repository: WorkspaceRepository,
    workspaceId: String,
    path: String,
    cwdCandidates: List<String>,
    output: java.io.OutputStream,
) {
    var lastError: Exception? = null
    val attempted = HashSet<String>()
    for (cwd in cwdCandidates + listOf("")) {
        val (area, rel) = resolveWorkspacePath(path, cwd.ifEmpty { null })
        if (!attempted.add("${area.name}|$rel")) continue
        try {
            repository.exportFile(workspaceId, area, rel, output)
            return
        } catch (e: Exception) {
            lastError = e
        }
    }
    throw lastError ?: IllegalStateException("导出失败: 无法解析文件路径: $path")
}
