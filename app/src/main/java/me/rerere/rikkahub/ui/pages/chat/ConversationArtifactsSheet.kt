/* 【域 A·对话核心】 — 本对话产出胶囊窗 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.pages.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Share08
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ProducedFile
import me.rerere.rikkahub.data.ai.tools.ProducedFiles
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.RenderKind
import me.rerere.rikkahub.ui.components.detectRenderKind
import me.rerere.rikkahub.ui.components.message.ChipFile
import me.rerere.rikkahub.ui.components.message.ScopedResolution
import me.rerere.rikkahub.ui.components.message.exportLocalFileTo
import me.rerere.rikkahub.ui.components.message.materializeWorkspaceFile
import me.rerere.rikkahub.ui.components.message.normalizeCwdRel
import me.rerere.rikkahub.ui.components.message.resolveScopedFile
import me.rerere.rikkahub.ui.components.message.shareLocalFile
import me.rerere.rikkahub.ui.components.render.RenderEngine
import me.rerere.rikkahub.ui.components.render.RenderResult
import me.rerere.rikkahub.ui.components.render.RenderViewDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject
import java.io.File

/**
 * v4.8.121→v4.8.129 (用户定版「本对话产出」): 模型产出/修改过的文件清单 —
 * 胶囊窗列表展示; 点击 → **选项弹层 [导出 / 分享 / 渲染]**。
 *
 * v4.8.129 (用户指令, 回滚 v4.8.125 的「用其他应用打开」): 与对话内文件胶囊
 * 逻辑路径**完全一致** (共享 ChatFileActions 统一核心: 解析 → 物化 → 动作) —
 *   - 导出 = 系统另存 (CreateDocument) → exportLocalFileTo;
 *   - 分享 = shareLocalFile (FileProvider → ACTION_SEND 选择器);
 *   - 渲染 = RenderEngine.renderSmart → RenderViewDialog (仅可渲染类型显示)。
 * 「用其他应用打开」整体移除 (v4.8.125 引入 — 用户实证点击"提示文档不存在"
 * 假失败: 其解析链 (openWorkspaceLink) 与导出/渲染链不同源)。
 * 保留: 列表路径规范化去重 (v4.8.125) / 多候选解析 + 多命中弹层 (v4.8.63 同源) /
 * 全链可见反馈 / Host 恒驻组合 (渲染进度态保全)。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationArtifactsSheet(
    open: Boolean,
    files: List<ProducedFile>,
    assistant: Assistant?,
    folderCwd: String?,
    onOpenChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaceId = assistant?.workspaceId?.toString()
    val cwdCandidates = remember(folderCwd, assistant) {
        listOfNotNull(normalizeCwdRel(folderCwd), normalizeCwdRel(assistant?.workspaceCwd)).distinct()
    }
    // v4.8.125: 显示侧形态收敛 + 兜底去重 — 历史记录 (v4.8.121-124 持久化的多形态路径)
    // 也在此归一后再去重, 同一文件不多列; 解析链本就有多候选探针, 归一后仍可命中。
    val displayFiles = remember(files, assistant) {
        files
            .map { f -> f.copy(path = ProducedFiles.canonicalPath(f.path, assistant?.workspaceCwd)) }
            .distinctBy { it.path }
    }

    var selected by remember { mutableStateOf<ProducedFile?>(null) }
    var pendingExport by remember { mutableStateOf<ProducedFile?>(null) }
    var renderResult by remember { mutableStateOf<RenderResult?>(null) }
    var renderLoading by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<Pair<List<String>, CompletableDeferred<String?>>?>(null) }

    /** 解析入口: 多候选直取 → 文件名模糊; 多命中弹层让用户选 (取消/找不到返回 null 并 toast, 不报错) */
    suspend fun resolveOrAsk(raw: String): String? {
        val wsId = workspaceId
        if (wsId == null) {
            toaster.show(message = "该助手未绑定工作区", type = ToastType.Error)
            return null
        }
        return when (val r = resolveScopedFile(workspaceRepository, wsId, ChipFile(raw, null), cwdCandidates)) {
            is ScopedResolution.Resolved -> r.rootfsPath
            is ScopedResolution.Ambiguous -> {
                val deferred = CompletableDeferred<String?>()
                chooser = r.candidates to deferred
                deferred.await()
            }

            is ScopedResolution.NotFound -> {
                toaster.show(message = r.detail.take(160), type = ToastType.Error)
                null
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = pendingExport.also { pendingExport = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val wsId = workspaceId ?: error("该助手未绑定工作区")
                val resolved = resolveOrAsk(target.path) ?: return@launch
                val file = materializeWorkspaceFile(context, workspaceRepository, wsId, resolved)
                exportLocalFileTo(context, file, uri)
                toaster.show(
                    message = "已导出: ${target.path.substringAfterLast('/')}",
                    type = ToastType.Success,
                )
            }.onFailure { e ->
                toaster.show(
                    message = "导出失败: ${e.message?.take(120) ?: "未知错误"}",
                    type = ToastType.Error,
                )
            }
        }
    }

    fun startRender(target: ProducedFile) {
        val fileName = target.path.substringAfterLast('/').ifBlank { target.path }
        renderLoading = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val wsId = workspaceId ?: error("该助手未绑定工作区")
                        val resolved = resolveOrAsk(target.path) ?: return@withContext
                        val resolvedName = resolved.substringAfterLast('/')
                        val file = materializeWorkspaceFile(context, workspaceRepository, wsId, resolved)
                        val taskDir = File(context.cacheDir, "file_actions_task").apply {
                            deleteRecursively()
                            mkdirs()
                        }
                        renderResult = RenderEngine.renderSmart(file, taskDir, resolvedName)
                    }.onFailure {
                        renderResult = RenderResult.Unsupported(fileName, "读取文件失败: ${it.message}")
                    }
                }
            } finally {
                renderLoading = false
            }
        }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = {
            if (selected != null) selected = null else onOpenChange(false)
        }) {
            val sel = selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            ) {
                if (sel == null) {
                    Text(
                        text = stringResource(R.string.conversation_output_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    if (displayFiles.isEmpty()) {
                        Text(
                            text = stringResource(R.string.conversation_output_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            displayFiles.forEach { file ->
                                Surface(
                                    onClick = { selected = file },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            imageVector = HugeIcons.File01,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = file.path.substringAfterLast('/').ifBlank { file.path },
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // v4.8.129: 选项 = [导出 / 分享 / 渲染] — 与对话内胶囊窗逻辑路径完全一致
                    val fileName = sel.path.substringAfterLast('/').ifBlank { sel.path }
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ArtifactOptionCard(
                            icon = { Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.padding(4.dp)) },
                            label = stringResource(R.string.common_export),
                            onClick = {
                                pendingExport = sel
                                selected = null
                                onOpenChange(false)
                                runCatching {
                                    exportLauncher.launch(fileName)
                                }.onFailure { e ->
                                    pendingExport = null
                                    toaster.show(
                                        message = "导出失败: ${e.message?.take(120) ?: "未知错误"}",
                                        type = ToastType.Error,
                                    )
                                }
                            },
                        )
                        ArtifactOptionCard(
                            icon = { Icon(HugeIcons.Share08, contentDescription = null, modifier = Modifier.padding(4.dp)) },
                            label = stringResource(R.string.common_share),
                            onClick = {
                                selected = null
                                onOpenChange(false)
                                scope.launch {
                                    runCatching {
                                        val wsId = workspaceId ?: error("该助手未绑定工作区")
                                        val resolved = resolveOrAsk(sel.path) ?: return@launch
                                        val file = materializeWorkspaceFile(context, workspaceRepository, wsId, resolved)
                                        shareLocalFile(context, file)
                                    }.onFailure { e ->
                                        toaster.show(
                                            message = "分享失败: ${e.message?.take(120) ?: "未知错误"}",
                                            type = ToastType.Error,
                                        )
                                    }
                                }
                            },
                        )
                        if (detectRenderKind(fileName) != RenderKind.NONE) {
                            ArtifactOptionCard(
                                icon = { Icon(HugeIcons.FileView, contentDescription = null, modifier = Modifier.padding(4.dp)) },
                                label = "渲染",
                                onClick = {
                                    selected = null
                                    onOpenChange(false)
                                    startRender(sel)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // v4.8.102 同款: 渲染等待加载遮罩
    if (renderLoading && renderResult == null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = stringResource(R.string.render_loading),
                        color = Color.White,
                    )
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

    // v4.8.63 同款: 模糊匹配多命中 — 弹层让用户选择 (不报错)
    chooser?.let { (options, deferred) ->
        AlertDialog(
            onDismissRequest = {
                deferred.complete(null)
                chooser = null
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
                    options.forEach { option ->
                        Card(
                            onClick = {
                                deferred.complete(option)
                                chooser = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = option,
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
                    deferred.complete(null)
                    chooser = null
                }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ArtifactOptionCard(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
