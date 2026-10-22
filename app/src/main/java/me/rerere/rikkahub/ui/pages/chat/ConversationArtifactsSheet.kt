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
 *
 * v4.8.136 (B157 用户实证"点了没反应/点不开, 时好时坏") 根修:
 *  - dismiss 僵尸态: Material3 的 onDismissRequest 是"关闭动画已完成"的通知 (sheet
 *    此刻已隐藏), 旧两步式 (只清 selected 不清 open) 留下 open=true+Hidden — 此后
 *    入口点击无状态变化无重组, 永远打不开 (页面级状态重建才偶发恢复)。现在 dismiss
 *    一律彻底关闭; 「选项→列表」回退改由系统返回键承担 (BackHandler)。
 *  - 长清单可达性: 产出清单可达 80 条, 无滚动时超出行高上限的胶囊被裁剪不可触达 —
 *    内容列加垂直滚动 + 高度上限 (嵌套滚动与 sheet 拖拽标准协同)。
 */
/**
 * v5.0.59：产出定位结果 —— **唯一路由键**（工作区 + rootfs 路径）。
 */
data class LocatedArtifact(
    /** 产出发生的工作区（旧记录才用得到） */
    val workspaceId: String,
    /** rootfs 形态路径（旧记录才用得到） */
    val rootfsPath: String,
    /**
     * v5.0.62 **宿主 File**：来自 `ProducedFile.hostPath`，一次 stat 就有。
     * 有它时后续动作（导出/分享/渲染）直接读它，不再经过 rootfs 解析。
     */
    val hostFile: File? = null,
)

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
            // v5.0.57：记录入库时已完成规范化（record 的 extractPath 带同一 cwd），
            // 这里再调一次 canonicalPath 会在 cwd 带前缀时叠出 /workspace/workspace/... 双前缀，
            // 导致「点任何产出都提示未找到文件」。直接使用已规范化路径。
            .map { f -> f }
            .distinctBy { it.path }
    }

    var selected by remember { mutableStateOf<ProducedFile?>(null) }
    var pendingExport by remember { mutableStateOf<ProducedFile?>(null) }
    var renderResult by remember { mutableStateOf<RenderResult?>(null) }
    var renderLoading by remember { mutableStateOf(false) }
    // v5.0.59: 定位改为"唯一路由键 + 最小 CWD 集合"精确探测，不存在多命中选择场景

    /**
     * v5.0.59（按"唯一对应码"重写）：产出文件定位。
     *
     * 输入是**记录本身**（[ProducedFile]），它携带定位键：
     *   · `workspaceId` —— 产出发生时所在工作区
     *   · `cwd`         —— 产出发生时生效的作用域（相对形态，与 workspaceScopeDir 同口径）
     *
     * 解析 = 对"最小 CWD 集合"做几次 `rootfsFileSize(wsId, path, cwd)` 精确探测：
     *   记录里的 cwd → 当前项目包 CWD → 当前助手 CWD → 根
     *
     * 全程**不做任何目录遍历**。找不到 = 文件确实不在 → 明确报错，
     * 不再出现"点了没反应"（旧分支解析失败后静默 return）。
     */
    suspend fun resolveOrAsk(hint: ProducedFile): LocatedArtifact? {
        val name = hint.path.substringAfterLast('/').ifBlank { hint.path }

        // ── ① 唯一对应码：记录时落盘的宿主绝对路径（v5.0.62 起）─────────────
        // 这就是"直接打开"：File(hostPath).isFile 一次 stat，不猜 CWD、不查工作区。
        val recorded = hint.hostPath
        if (!recorded.isNullOrBlank()) {
            val direct = File(recorded)
            if (direct.isFile) {
                return LocatedArtifact(
                    workspaceId = hint.workspaceId.orEmpty(),
                    rootfsPath = hint.path,
                    hostFile = direct,
                )
            }
            // 跨包名 / 跨设备迁移：绝对路径里的包名段变了，按 /files/ 之后重建
            val rel = recorded.substringAfter("/files/", "")
            if (rel.isNotBlank()) {
                val rebuilt = File(context.filesDir, rel)
                if (rebuilt.isFile) {
                    return LocatedArtifact(
                        workspaceId = hint.workspaceId.orEmpty(),
                        rootfsPath = hint.path,
                        hostFile = rebuilt,
                    )
                }
            }
        }

        // ── ② 旧记录（没有 hostPath）：按 rootfs 语义最小集合探测 ──────────

        val wsIds = buildList {
            hint.workspaceId?.takeIf { it.isNotBlank() }?.let { add(it) }
            workspaceId?.takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
        }
        if (wsIds.isEmpty()) {
            toaster.show(
                message = "未找到文件: $name（该对话没有绑定工作区，记录里也没有工作区信息）",
                type = ToastType.Error,
            )
            return null
        }

        val cwds = ArrayList<String?>()
        hint.cwd?.let { cwds.add(it) }
        cwdCandidates.forEach { if (it !in cwds) cwds.add(it) }
        cwds.add(null)

        val paths = ArrayList<String>()
        paths.add(hint.path)
        if (hint.path.startsWith("/workspace/workspace/")) {
            paths.add("/workspace/" + hint.path.removePrefix("/workspace/workspace/"))
        }

        val tried = ArrayList<String>()
        for (ws in wsIds) {
            for (p in paths) {
                for (cwd in cwds) {
                    tried.add("$p @${cwd ?: "/"}")
                    val ok = runCatching { workspaceRepository.rootfsFileSize(ws, p, cwd) }.isSuccess
                    if (ok) return LocatedArtifact(ws, p)
                }
            }
        }

        // v5.0.62: 找不到时**把记录原样打出来** —— 用户一眼就能看出是"记录的路径本身错了"
        // 还是"文件真的被删了"，不用再来回猜。
        toaster.show(
            message = buildString {
                append("未找到文件: ").append(name)
                append("\n记录路径: ").append(hint.path)
                append("\nCWD: ").append(hint.cwd ?: "/")
                append("\n宿主路径: ").append(hint.hostPath ?: "（旧记录未存）")
                if (tried.isNotEmpty()) append("\n已探 ${tried.size} 处")
            },
            type = ToastType.Error,
        )
        return null
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val target = pendingExport.also { pendingExport = null }
        if (target == null) {
            onOpenChange(false)
            return@rememberLauncherForActivityResult
        }
        if (uri == null) {
            onOpenChange(false)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                runCatching {
                    val loc = resolveOrAsk(target) ?: return@launch
                    val file = loc.hostFile ?: materializeWorkspaceFile(
                        context, workspaceRepository, loc.workspaceId, loc.rootfsPath,
                    )
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
            } finally {
                // 动作跑完才关 sheet（提前关会取消本协程 + 丢失 launcher 回调）
                onOpenChange(false)
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
                        val loc = resolveOrAsk(target)
                        if (loc == null) {
                            // v5.0.59: 旧实现在这里静默 return —— 用户点"渲染"只看到转圈消失，
                            // 什么都没发生（"点了没反应"的直接原因）。现在必须给出可见结果。
                            renderResult = RenderResult.Unsupported(
                                fileName,
                                "未找到该文件：${target.path}\n" +
                                    "已按记录的工作区与 CWD 精确探测过，均不存在。\n" +
                                    "可在「工作区」里确认文件是否仍存在，或重新生成一次。",
                            )
                            return@withContext
                        }
                        val resolvedName = loc.rootfsPath.substringAfterLast('/')
                        val file = loc.hostFile ?: materializeWorkspaceFile(
                            context, workspaceRepository, loc.workspaceId, loc.rootfsPath,
                        )
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
        // v4.8.136 (B157): dismiss 一律彻底关闭 —— 详细机制见文件头注释;
        // 「选项→列表」的一步回退在此用返回键实现, 不与 dismiss 语义混淆。
        androidx.activity.compose.BackHandler(enabled = selected != null) { selected = null }
        ModalBottomSheet(onDismissRequest = {
            selected = null
            onOpenChange(false)
        }) {
            val sel = selected
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // v4.8.136: 长清单可达性 — 无滚动时超出半展开位的胶囊被裁剪且无法
                    // 触达 (产出上限 80 条); 嵌套滚动与 sheet 拖拽标准协同。
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
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
                                // v5.0.63 ★关键修复：**不能在这里关 sheet**。
                                // onOpenChange(false) 会让调用方 if (showArtifactsSheet) 整块出栈，
                                // 本 Composable 被销毁 → rememberLauncherForActivityResult 的回调
                                // 直接丢失（用户选完文件什么都没发生）。
                                // 现在等系统文件选择器返回后再关。
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
                                // v5.0.63 ★同上：先关 sheet = 销毁 scope，异步动作被取消。
                                // 分享快（hostPath 一次 stat）所以碰巧能跑完，但仍是隐患，一并修。
                                scope.launch {
                                    try {
                                        runCatching {
                                            val loc = resolveOrAsk(sel) ?: return@launch
                                            val file = loc.hostFile ?: materializeWorkspaceFile(
                                                context, workspaceRepository, loc.workspaceId, loc.rootfsPath,
                                            )
                                            shareLocalFile(context, file)
                                        }.onFailure { e ->
                                            toaster.show(
                                                message = "分享失败: ${e.message?.take(120) ?: "未知错误"}",
                                                type = ToastType.Error,
                                            )
                                        }
                                    } finally {
                                        // 动作跑完才关 sheet（提前关会取消本协程）
                                        onOpenChange(false)
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
                                    // v5.0.63 ★渲染没反应的真正原因：
                                    // 旧代码先 onOpenChange(false) → 组件出栈 → rememberCoroutineScope
                                    // 被取消 → renderSmart 渲染到一半被中断 → renderResult 永远为 null
                                    // → 只有 renderLoading 转一圈消失，界面"毫无反应"。
                                    // 分享能成功只是因为 hostPath 快路径 + 分享本身极快，赶在取消前跑完。
                                    // 现在不关 sheet，等渲染结果 Dialog 关闭时再关。
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
                onDismiss = {
                    renderResult = null
                    // v5.0.63: 渲染结果关闭 → 这时才关产出面板
                    onOpenChange(false)
                },
            )
        }
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
