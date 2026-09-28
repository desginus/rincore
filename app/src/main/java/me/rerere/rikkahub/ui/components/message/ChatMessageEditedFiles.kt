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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.serialization.json.JsonArray
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.render.RenderEngine
import me.rerere.rikkahub.ui.components.render.RenderResult
import me.rerere.rikkahub.ui.components.render.RenderViewDialog
import me.rerere.rikkahub.ui.components.detectRenderKind
import org.koin.compose.koinInject
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val DEFAULT_VISIBLE_COUNT = 3
private val WORKSPACE_FILE_TOOL_NAMES = setOf("workspace_show_file")

/** v4.8.63: 胶囊窗文件条目 — 展示用原始路径 + 工具执行回执中已验证的解析路径。 */
private data class ChipFile(val raw: String, val resolvedHint: String?)

/** v4.8.63: 模糊匹配多命中时的用户选择状态 (选择后继续原动作; 取消则放弃, 不报错)。 */
private data class FileChooserState(
    val options: List<String>,
    val choice: CompletableDeferred<String?>,
)

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
            .flatMap { tool -> chipFilesOf(tool) }
            .distinct()
    }
    if (editedFiles.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    // v4.8.63: 解析入口 — 多候选直取 → 后缀模糊; 多命中弹窗让用户选 (取消/找不到
    // 返回 null, toast 提示不报错)。文件解析规范重写: CWD 只用于产物整理规划,
    // 不是沙箱锁; 回执解析路径优先, 导出统一以全根语义 (cwd=null) 消费。
    var fileChooser by remember { mutableStateOf<FileChooserState?>(null) }
    suspend fun resolveOrAsk(entry: ChipFile): String? =
        when (val r = resolveScopedFile(workspaceRepository, workspaceId, entry, cwdCandidates)) {
            is ScopedResolution.Resolved -> r.rootfsPath
            is ScopedResolution.Ambiguous -> {
                val deferred = CompletableDeferred<String?>()
                fileChooser = FileChooserState(r.candidates, deferred)
                deferred.await()
            }
            is ScopedResolution.NotFound -> {
                toaster.show(message = r.detail.take(160), type = ToastType.Error)
                null
            }
        }

    var selectedFile by remember { mutableStateOf<ChipFile?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var renderResult by remember { mutableStateOf<RenderResult?>(null) }
    var renderFileName by remember { mutableStateOf("") }
    var renderLoading by remember { mutableStateOf(false) }
    val visibleFiles = if (expanded) editedFiles else editedFiles.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = editedFiles.size > DEFAULT_VISIBLE_COUNT

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = selectedFile.also { selectedFile = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                outputStream.use { output ->
                    val resolved = resolveOrAsk(entry) ?: return@runCatching
                    exportResolvedFile(workspaceRepository, workspaceId, resolved, output)
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
        visibleFiles.forEach { entry ->
            val fileName = remember(entry) { entry.raw.substringAfterLast('/') }
            Surface(
                onClick = { selectedFile = entry },
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

    if (selectedFile != null) {
        val entry = selectedFile!!
        val fileName = remember(entry) { entry.raw.substringAfterLast('/') }
        ModalBottomSheet(
            onDismissRequest = { selectedFile = null },
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
                        val e = selectedFile ?: return@Card
                        exportLauncher.launch(e.raw.substringAfterLast('/'))
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
                        val entry2 = selectedFile ?: return@Card
                        selectedFile = null
                        scope.launch {
                            runCatching {
                                val resolved = resolveOrAsk(entry2) ?: return@runCatching
                                val dir = File(context.cacheDir, "workspace_share").apply { mkdirs() }
                                val file = File(dir, resolved.substringAfterLast('/'))
                                file.outputStream().use { output ->
                                    exportResolvedFile(workspaceRepository, workspaceId, resolved, output)
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
                            val entry2 = selectedFile ?: return@Card
                            selectedFile = null
                            scope.launch {
                                val resolved = resolveOrAsk(entry2) ?: return@launch
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val resolvedName = resolved.substringAfterLast('/')
                                        renderFileName = resolvedName
                                        val dir = File(context.cacheDir, "workspace_render")
                                        dir.deleteRecursively()
                                        dir.mkdirs()
                                        val file = File(dir, resolvedName)
                                        file.outputStream().use { output ->
                                            exportResolvedFile(workspaceRepository, workspaceId, resolved, output)
                                        }
                                        val taskDir = File(dir, "task")
                                        renderResult = RenderEngine.render(file, taskDir, resolvedName)
                                    }.onFailure {
                                        renderResult = RenderResult.Unsupported(
                                            renderFileName.ifBlank { entry2.raw.substringAfterLast('/') },
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

    // v4.8.63: 模糊匹配多命中 — 弹出文件位置让用户选择 (不报错)
    fileChooser?.let { chooser ->
        AlertDialog(
            onDismissRequest = {
                chooser.choice.complete(null)
                fileChooser = null
            },
            title = { Text("选择文件") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "按文件名匹配到多个文件，请选择要使用的位置：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    chooser.options.forEach { option ->
                        Card(
                            onClick = {
                                chooser.choice.complete(option)
                                fileChooser = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                option,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    chooser.choice.complete(null)
                    fileChooser = null
                }) { Text("取消") }
            },
        )
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

/** v4.8.57: CWD 归一化 (同 WorkspaceTools 口径), null/空 = 无约束。
 *  接受助手级 workspaceCwd 与项目包 cwd (含 "/workspace/xxx" 绝对形态)。 */
private fun normalizeCwdRel(raw: String?): String? {
    val trimmed = raw?.trim('/') ?: return null
    val rel = (if (trimmed == "workspace") "" else trimmed.removePrefix("workspace/")).trim('/')
    return rel.ifBlank { null }
}

/** v4.8.63 (用户定版) 规范重写: 从工具调用提取文件条目 —
 *  输入路径 (path/paths) + 结果回执解析路径 (工具执行时已验证存在的 rootfs
 *  路径; 与输入同序, 缺失/失败为 null)。 */
private fun chipFilesOf(tool: UIMessagePart.Tool): List<ChipFile> {
    val obj = tool.inputAsJson() as? JsonObject ?: return emptyList()
    val raws = buildList {
        obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(it) }
        (obj["paths"] as? JsonArray)?.forEach { e ->
            e.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
    if (raws.isEmpty()) return emptyList()
    val resolved = runCatching {
        val text = tool.output.joinToString("") { part -> (part as? UIMessagePart.Text)?.text.orEmpty() }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
            ?: return@runCatching emptyList()
        json["files"]?.let { el ->
            (el as? JsonArray)?.mapNotNull { e ->
                (e as? JsonObject)?.takeIf { (it["status"]?.jsonPrimitive?.contentOrNull) == "shown" }
                    ?.get("path")?.jsonPrimitive?.contentOrNull
            }
        } ?: listOfNotNull(
            json.takeIf { (it["status"]?.jsonPrimitive?.contentOrNull) == "shown" }
                ?.get("path")?.jsonPrimitive?.contentOrNull
        )
    }.getOrDefault(emptyList())
    return raws.mapIndexed { index, raw -> ChipFile(raw, resolved.getOrNull(index)) }
}

/** v4.8.63 (用户定版) 规范重写: 胶囊窗文件解析。
 *  背景: CWD 只用于模型产物整理规划, 不是沙箱锁; 模型给出的地址可能为回执形式、
 *  全根绝对、scope 相对 (/workspace/... 含或不含 CWD 段) 或裸相对, 且项目包 CWD
 *  可能已变更 → 旧地址错位 ("File does not exist", 用户实证)。
 *  策略: ①回执解析路径 → ②原始输入 × {项目包 CWD、助手 CWD、根} 多候选直取 →
 *  ③仍未命中则按文件名全库模糊匹配 (后缀深度择优; 仍并列 → 交由调用方弹窗让用户
 *  选择, 不报错)。解析产物: 全根语义 Rootfs 绝对路径 (导出统一 cwd=null 消费)。 */
private sealed interface ScopedResolution {
    data class Resolved(val rootfsPath: String) : ScopedResolution
    data class Ambiguous(val candidates: List<String>) : ScopedResolution
    data class NotFound(val detail: String) : ScopedResolution
}

private suspend fun resolveScopedFile(
    repository: WorkspaceRepository,
    workspaceId: String,
    chip: ChipFile,
    cwdCandidates: List<String>,
): ScopedResolution {
    val probes = LinkedHashSet<String>()
    fun addPathCandidate(p: String?) {
        val v = p?.trim().orEmpty()
        if (v.isEmpty()) return
        if (!v.startsWith("/")) {
            // 裸相对: 按 CWD 自动拼接 (项目包 → 助手 → 根)
            for (cwd in cwdCandidates) probes.add("/workspace/" + cwd.trim('/') + "/" + v)
            probes.add("/workspace/$v")
        } else {
            probes.add(v)
            if (v.startsWith("/workspace/")) {
                // scope 相对形: "/workspace/xxx" 在旧 CWD 下实际位于 /workspace/<cwd>/xxx
                val rel = v.removePrefix("/workspace/")
                for (cwd in cwdCandidates) probes.add("/workspace/" + cwd.trim('/') + "/" + rel)
            }
        }
    }
    addPathCandidate(chip.resolvedHint)
    addPathCandidate(chip.raw)

    suspend fun exists(p: String): Boolean =
        runCatching { repository.rootfsFileSize(workspaceId, p, null); true }.getOrDefault(false)
    for (p in probes) if (exists(p)) return ScopedResolution.Resolved(p)

    // ③ 模糊: 按文件名全库查找 (排除版本快照), 后缀深度择优
    val base = chip.raw.substringAfterLast('/').trim()
    if (base.isEmpty()) return ScopedResolution.NotFound("路径无效: ${chip.raw}")
    val safeName = base.replace("'", "")
    val stdout = runCatching {
        repository.executeCommand(
            workspaceId,
            "find /workspace -type f -name '$safeName' -not -path '*/.versions/*' 2>/dev/null | head -50",
            "",
            me.rerere.workspace.WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        ).stdout.orEmpty()
    }.getOrDefault("")
    val hits = stdout.lineSequence().map { it.trim() }.filter { it.startsWith("/workspace/") }.distinct().toList()
    if (hits.isEmpty()) return ScopedResolution.NotFound("未找到文件: ${chip.raw}")
    if (hits.size == 1) return ScopedResolution.Resolved(hits[0])
    val rawSegs = chip.raw.split('/').filter { it.isNotBlank() }
    fun suffixDepth(hit: String): Int {
        val hitSegs = hit.split('/').filter { it.isNotBlank() }
        var d = 0
        while (d < rawSegs.size && d < hitSegs.size &&
            rawSegs[rawSegs.size - 1 - d] == hitSegs[hitSegs.size - 1 - d]
        ) d++
        return d
    }
    val maxDepth = hits.maxOf { suffixDepth(it) }
    val best = hits.filter { suffixDepth(it) == maxDepth }.sorted()
    return if (best.size == 1) ScopedResolution.Resolved(best[0]) else ScopedResolution.Ambiguous(best)
}

/** v4.8.63: 导出已解析文件 (全根语义, 单一出口 — '解析路径'与'导出语义'不再双轨)。 */
private suspend fun exportResolvedFile(
    repository: WorkspaceRepository,
    workspaceId: String,
    rootfsPath: String,
    output: java.io.OutputStream,
) {
    repository.exportRootfsFile(workspaceId, rootfsPath, output, null)
}
