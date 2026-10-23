package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import me.rerere.ai.ui.UIMessagePart
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ui.sketch.SketchResult
import me.rerere.ui.sketch.SketchDialog
import me.rerere.rikkahub.ui.pages.chat.ConversationArtifactsSheet
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.ai.tools.ProducedFile
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Activity01
import me.rerere.hugeicons.stroke.LanguageCircle
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Gauge
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.Voice
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.lastContextTokens
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject

private enum class FilesPickerPage {
    MAIN,
    MCP,
    EXTENSIONS,
}

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    // 会话视角下的助手和模型：会话开始后以会话上固定的配置为准
    assistant: Assistant,
    chatModel: Model?,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateWorkspaceCwd: (String?) -> Unit = {},
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    onSketch: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
    // v5.0.20 用户定版：工具箱 12 格 → 延迟自动回复开关
    onToggleDeferAutoReply: (Boolean) -> Unit = {},
    // v5.0.46：承载版兼容当前 ChatPage 调用（注入快捷配置 / 压缩留存 / 会话更新）
    showInjectionSheet: Boolean = false,
    onShowInjectionSheetChange: ((Boolean) -> Unit)? = null,
    onRestoreCompressAt: ((Long) -> Unit)? = null,
    onUpdateConversation: ((Conversation) -> Unit)? = null,
    deferAutoReply: Boolean = false,
) {
    val settings = LocalSettings.current
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    // v5.0.51 用户定案：恢复 4.8.190 的 CWD 解析链 —— **项目包 CWD 优先，否则助手级**。
    // 此前直接用 conversation.workspaceCwd，导致项目包锚定的 CWD 在工具箱里看不到、也重置不干净。
    val folderRepository: me.rerere.rikkahub.data.repository.FolderRepository = koinInject()
    val folderCwd by remember(conversation.folderId) {
        conversation.folderId?.let { fid ->
            folderRepository.getFolderFlow(fid).map { it?.cwd }
        } ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val effectiveCwd = me.rerere.rikkahub.data.workspace.EffectiveCwd.pick(folderCwd, assistant.workspaceCwd)

    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    val showCwd = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    var showCwdSheet by remember { mutableStateOf(false) }

    // v5.0.17 用户定案：工具箱收纳对话功能入口（原「更多」菜单四项 + 本对话产出 + 草图），
    // 移除 MCP / 技能（已归位设置页），空位放原版草图
    // v5.0.77：死变量清理 —— 旧 sketchScope（rememberCoroutineScope）已不再用于任何落盘，
    // 草图走 sketchAppScope + InboxStore 门面；此变量删除。
    val filesManager: FilesManager = koinInject()
    // v5.0.73 ★草图第二个入口（加号菜单 → FilesPicker，用户实际用的就是这条）。
    // 旧实现：`showSketch=false` → `sketchScope.launch { createChatFileBySketch()?.let{...} }`
    //          → **立即 `onDismiss()` 关闭整个 FilesPicker sheet**。
    // sheet 一关，`rememberCoroutineScope` 立刻被取消 → 编码/写文件根本没跑完 → 图彻底消失，
    // 而且 `?.let` 让 null 也不报错 → 既无图也无提示 = 用户说的"静默消失"。
    // 现在：同步校验 → AppScope 落盘（不随 sheet 销毁而取消）→ 成功才关、失败必报。
    val sketchToaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val sketchAppScope: me.rerere.rikkahub.AppScope = koinInject()
    // v5.0.77：收件箱单一门面（草图字节导入经 InboxStore.FromBytes）
    val sketchInbox: me.rerere.rikkahub.data.files.InboxStore = koinInject()
    val subAgentRegistry: SubAgentRegistry = koinInject()
    // v5.0.57：预算面板需要 SubAgentBudget（limit/used/reset/setLimit）
    val subAgentBudget = koinInject<me.rerere.rikkahub.subagent.SubAgentEngine>().budget
    var showSketch by remember { mutableStateOf(false) }
    var showSubAgentSheet by remember { mutableStateOf(false) }
    var showSubAgentBudgetSheet by remember { mutableStateOf(false) }
    var showArtifactsSheet by remember { mutableStateOf(false) }
    val subAgentRuns by subAgentRegistry.runs.collectAsStateWithLifecycle()
    val conversationRuns = remember(subAgentRuns, conversation.id) {
        subAgentRuns.values
            .filter { it.parentChatId == conversation.id.toString() }
            .sortedByDescending { it.startedAtMs }
    }
    // v5.0.21 用户定案：按模型最后交互时间自上而下（新→旧），去空去重
    val producedFiles = remember(settings.conversationProducedFiles, conversation.id) {
        settings.conversationProducedFiles[conversation.id.toString()].orEmpty()
            .filter { it.path.isNotBlank() }
            .distinctBy { it.path }
            .sortedByDescending { it.atMs }
    }

    var page by remember { mutableStateOf(FilesPickerPage.MAIN) }
    // 在子页面时，返回键回到主页面而不是关闭 sheet
    BackHandler(enabled = page != FilesPickerPage.MAIN) {
        page = FilesPickerPage.MAIN
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != FilesPickerPage.MAIN) {
                slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith
                    slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "FilesPickerPage"
    ) { currentPage ->
        when (currentPage) {
            FilesPickerPage.MCP -> McpPickerPage(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
            )

            FilesPickerPage.EXTENSIONS -> ExtensionPickerPage(
                assistant = assistant,
                settings = settings,
                onUpdateAssistant = onUpdateAssistant,
                onBack = { page = FilesPickerPage.MAIN },
                onDismissAll = onDismiss,
            )

            // v5.0.20 用户定版：工具箱 = 12 个方形入口（4 × 3），只要方形 UI 不要条形 UI。
            // 删除拍照 / 照片 / 上传文件（草图保留）；整格使用输入栏同源玻璃卡片风格。
            FilesPickerPage.MAIN -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // v5.0.54 ★用户实证修复：压缩后**上下文留存管理横条**曾丢失（数据一直在，入口没了）。
                // 用户看不到压缩记录、也无法撤销 → 误以为历史被彻底丢弃。恢复 4.8.190 的横条 + 位点弹窗。
                run {
                    val retentions = conversation.compressRetentions
                    if (retentions.isNotEmpty() || conversation.compressedContext != null) {
                        var showRetentionDialog by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { showRetentionDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    HugeIcons.ArrowTurnBackward,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                                Text(
                                    "上下文压缩管理",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        if (showRetentionDialog) {
                            CompressRetentionDialog(
                                retentions = retentions,
                                hasLegacy = conversation.compressedContext != null && retentions.isEmpty(),
                                legacyNodes = conversation.compressedContext?.savedMessageNodes,
                                onRestore = { index ->
                                    onRestoreCompressAt?.invoke(index.toLong())
                                    showRetentionDialog = false
                                },
                                onDismiss = { showRetentionDialog = false },
                            )
                        }
                    }
                }

                val contextTokens = remember(conversation.messageNodes) { conversation.lastContextTokens() }
                val contextLength = chatModel?.contextLength?.takeIf { it > 0 }
                val remainingText = if (contextTokens != null && contextLength != null) {
                    "剩 " + ((contextLength - contextTokens).coerceAtLeast(0) / 1000) + "K"
                } else null
                val messageText = conversation.messageNodes.size.toString() + " 条"

                // 第 1 行：草图 / 翻译 / 媒体生成 / 子代理情况
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassToolTile(HugeIcons.PaintBoard, "草图", modifier = Modifier.weight(1f)) { showSketch = true }
                    GlassToolTile(HugeIcons.LanguageCircle, "翻译", modifier = Modifier.weight(1f)) {
                        onDismiss()
                        navController.navigate(Screen.Translator)
                    }
                    GlassToolTile(HugeIcons.Image02, "媒体生成", modifier = Modifier.weight(1f)) {
                        onDismiss()
                        navController.navigate(Screen.MediaCreationSessions)
                    }
                    GlassToolTile(
                        HugeIcons.AiBrain01, "子代理", badge = conversationRuns.size, modifier = Modifier.weight(1f)
                    ) { showSubAgentSheet = true }
                }

                // 第 2 行：观测 / 本对话产出 / 上下文速览 / 压缩历史
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // v5.0.24 用户定案：观测删除 → 记忆（三范围：单对话 / 项目包 / 助手）
                    GlassToolTile(HugeIcons.Book02, "记忆", modifier = Modifier.weight(1f)) {
                        onDismiss()
                        navController.navigate(Screen.AssistantMemory(assistant.id.toString()))
                    }
                    GlassToolTile(
                        HugeIcons.Folder01, "本对话产出", badge = producedFiles.size, modifier = Modifier.weight(1f)
                    ) { showArtifactsSheet = true }
                    // v5.0.24 用户定案：环形 UI（基准固定 1M，亮色填充=已用占比，中心=当前消息条数）
                    GlassToolTile(
                        HugeIcons.BubbleChatQuestion, "上下文速览",
                        ringProgress = ((contextTokens ?: 0).toFloat() / CONTEXT_WINDOW_BASELINE_TOKENS)
                            .coerceIn(0f, 1f),
                        ringCenterText = conversation.messageNodes.size.toString(),
                        modifier = Modifier.weight(1f)
                    ) { onShowCompressDialogChange(true) }
                    GlassToolTile(
                        HugeIcons.Package01, "压缩历史", modifier = Modifier.weight(1f)
                    ) { onShowCompressDialogChange(true) }
                }

                // 第 3 行：延迟回复 / 应用文件 / 工作区 / 工作目录
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassToolTile(
                        HugeIcons.Clock01, "延迟回复",
                        active = settings.deferAutoReply,
                        modifier = Modifier.weight(1f),
                    ) { onToggleDeferAutoReply(!settings.deferAutoReply) }
                    GlassToolTile(HugeIcons.Files02, "应用文件", modifier = Modifier.weight(1f)) {
                        // v5.0.51 用户定案（对齐 4.8.190）：**直接进文件页**（此前 initialTab 缺省=0 → 落到基本页）。
                        // 工作区回退：会话未绑定工作区时取第一个；无任何工作区才去列表页。
                        val targetId = assistant.workspaceId?.toString() ?: workspaces.firstOrNull()?.id
                        onDismiss()
                        if (targetId != null) {
                            navController.navigate(
                                Screen.WorkspaceDetail(
                                    id = targetId,
                                    initialTab = 1,
                                    initialPath = effectiveCwd,
                                )
                            )
                        } else {
                            navController.navigate(Screen.Workspaces)
                        }
                    }
                    // v5.0.25 用户定案：第 12 格 = 子代理预算（与「子代理」运行详情并列）
                    GlassToolTile(
                        HugeIcons.Gauge, "子代理预算",
                        badge = conversationRuns.size,
                        modifier = Modifier.weight(1f),
                    ) { showSubAgentBudgetSheet = true }
                    GlassToolTile(HugeIcons.ComputerTerminal01, "工作目录", modifier = Modifier.weight(1f)) {
                        showCwdSheet = true
                    }
                }
            }
        }
    }

    if (showSketch) {
        SketchDialog(
            onDismiss = { showSketch = false },
            onConfirm = { result: SketchResult ->
                // 1) 同步校验导出结果（为空/异常 → 报错且**不关对话框**，可重试）
                val bytes = runCatching { result.encode() }.getOrElse { e ->
                    sketchToaster.show(
                        "草图导出失败：${e.message ?: e::class.simpleName}",
                        type = com.dokar.sonner.ToastType.Error,
                    )
                    null
                }
                if (bytes == null || bytes.isEmpty()) {
                    if (bytes != null) {
                        sketchToaster.show(
                            "草图导出失败：图片编码为空",
                            type = com.dokar.sonner.ToastType.Error,
                        )
                    }
                } else {
                    // 2) AppScope 落盘：经收件箱门面（IO 线程导入 + 失败带来源 + 自动清临时件）
                    sketchAppScope.launch {
                        try {
                            val entry = sketchInbox.import(
                                me.rerere.rikkahub.data.files.InboxSource.FromBytes(
                                    bytes = bytes,
                                    displayName = "sketch.${result.extension}",
                                    mime = result.mimeType,
                                )
                            )
                            state.addImages(listOf(entry.uri))
                            sketchToaster.show(
                                "草图已添加到输入框",
                                type = com.dokar.sonner.ToastType.Success,
                            )
                            // 3) 只有真正落盘成功后才关闭（先关 sketch 再关 sheet）
                            showSketch = false
                            onDismiss()
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            sketchToaster.show(
                                e.message ?: "草图保存失败：未知错误",
                                type = com.dokar.sonner.ToastType.Error,
                            )
                        }
                    }
                }
            },
        )
    }

    if (showSubAgentSheet) {
        SubAgentDetailSheet(
            runs = conversationRuns,
            onCancel = { runId -> subAgentRegistry.requestCancel(runId) },
            onDismiss = { showSubAgentSheet = false },
        )
    }

    if (showSubAgentBudgetSheet) {
        SubAgentBudgetSheet(
            runs = conversationRuns,
            assistant = assistant,
            onUpdateAssistant = onUpdateAssistant,
            budget = subAgentBudget,
            conversationId = conversation.id.toString(),
            onDismiss = { showSubAgentBudgetSheet = false },
        )
    }

    if (showArtifactsSheet) {
        ConversationArtifactsSheet(
            open = showArtifactsSheet,
            files = producedFiles,
            assistant = assistant,
            // v5.0.59 ★「本对话产出点任何文件都未找到」的真根因：
            // 这里原来传的是 conversation.workspaceCwd —— 该字段已"退役为数据壳"
            // （全库没有任何路径再写它，恒为 null），于是 cwdCandidates 只剩助手级 CWD，
            // 项目包对话产出的 /workspace/<包cwd>/xxx 永远探不到。
            // 正确来源是上面 167 行已取好的 folderCwd（项目包 CWD）。
            folderCwd = folderCwd,
            onOpenChange = { showArtifactsSheet = it },
        )
    }

    if (showCwdSheet && boundWorkspace != null) {
        val cwdScope = rememberCoroutineScope()
        WorkspaceCwdPickerSheet(
            workspaceId = boundWorkspace.id,
            currentCwd = effectiveCwd,
            onSelectCwd = { newCwd ->
                // v5.0.51 对齐 4.8.190 / v4.8.138：项目包对话写包级 CWD；**重置(null)时必须连助手级一起清零**，
                // 否则清掉包级后助手级仍生效 → 界面与沙箱毫无变化（用户"连重置都做不到"的根因）。
                val fid = conversation.folderId
                if (fid != null) {
                    cwdScope.launch {
                        folderRepository.updateCwd(fid, newCwd)
                        if (newCwd == null && assistant.workspaceCwd != null) {
                            onUpdateAssistant(assistant.copy(workspaceCwd = null))
                        }
                    }
                } else {
                    onUpdateAssistant(assistant.copy(workspaceCwd = newCwd))
                }
                onUpdateWorkspaceCwd(newCwd)
            },
            onDismiss = { showCwdSheet = false },
        )
    }

    // Compress Context Dialog
    if (showCompressDialog) {
        // v5.0.53 ★用户实证修复：此前调用**没传 totalMessages/defaultKeep** → 默认 0 →
        // canCompress=false 且 runCompression 里 `keepRecentMessages > 0` 为假 →
        // 一律抛"消息数量不够"。补上 ContextCompressor 的智能推荐（按对话轮 + token 60%，始终保留最新轮）。
        val recommendedKeep = remember(conversation.messageNodes) {
            me.rerere.rikkahub.service.ContextCompressor.recommendedKeepMessages(conversation.currentMessages)
        }
        CompressContextDialog(
            totalMessages = conversation.currentMessages.size,
            defaultKeep = recommendedKeep,
            onDismiss = {
                onShowCompressDialogChange(false)
                onDismiss()
            },
            onConfirm = onCompressContext,
        )
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    workspaces: List<WorkspaceEntity>,
    shapes: ListItemShapes,
    onUpdateAssistant: (Assistant) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    SegmentedListItem(
        onClick = { showSheet = true },
        shapes = shapes,
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = null,
            )
        },
        supportingContent = {
            Text(
                text = boundWorkspace?.name ?: stringResource(R.string.assistant_page_workspace_unbound),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (boundWorkspace != null) {
                    IconButton(onClick = { onNavigateToDetail(boundWorkspace.id) }) {
                        Icon(
                            imageVector = HugeIcons.Settings02,
                            contentDescription = stringResource(R.string.workspace_detail),
                        )
                    }
                    if (boundWorkspace.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { onNavigateToTerminal(boundWorkspace.id) }) {
                            Icon(
                                imageVector = HugeIcons.ComputerTerminal01,
                                contentDescription = stringResource(R.string.workspace_terminal),
                            )
                        }
                    }
                }
            }
        },
    ) {
        Text(stringResource(R.string.assistant_page_workspace))
    }

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    // 会话的工作目录随工作区一并重置
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                }
                showSheet = false
            },
            onManage = {
                showSheet = false
                onNavigateToManage()
            },
            onDismiss = { showSheet = false },
        )
    }
}

// 扩展选择页，作为子页面嵌在加号 sheet 里
@Composable
private fun ExtensionPickerPage(
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onBack: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val navController = LocalNavController.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.75f)
            .padding(horizontal = 16.dp),
    ) {
        SheetHeader(
            title = stringResource(R.string.assistant_page_tab_extensions),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
            },
        )
        ExtensionSelector(
            assistant = assistant,
            settings = settings,
            onUpdate = onUpdateAssistant,
            modifier = Modifier.weight(1f),
            onNavigateToQuickMessages = {
                onDismissAll()
                navController.navigate(Screen.QuickMessages)
            },
            onNavigateToPrompts = {
                onDismissAll()
                navController.navigate(Screen.Prompts)
            },
            onNavigateToSkills = {
                onDismissAll()
                navController.navigate(Screen.Skills)
            })

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private class AttachmentAction(
    val icon: ImageVector,
    @param:StringRes val label: Int,
    val onClick: () -> Unit,
)

// 附件入口：一行最多四个，再多就均分成两行
/**
 * 工具箱方形入口（v5.0.20 用户定版）：输入栏同源玻璃卡片——
 * 半透明底 + 圆角 + 1dp 高光描边，4 列 × 3 行，只要方形不要条形。
 */
/** v5.0.24 用户定案：上下文环基准恒定 1M（不随模型 contextLength 变化）。 */
private const val CONTEXT_WINDOW_BASELINE_TOKENS = 1_000_000f

@Composable
private fun GlassToolTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: Int = 0,
    active: Boolean = false,
    // v5.0.24（用户定案）：上下文速览=环形 UI（基准 1M，亮色填充，中心消息条数）
    ringProgress: Float? = null,
    ringCenterText: String? = null,
    onClick: () -> Unit,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val secondary = subtitle ?: if (badge > 0) badge.toString() + " 项" else null

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (ringProgress != null) {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { ringProgress },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeWidth = 3.dp,
                )
                Text(
                    text = ringCenterText.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = contentColor,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (secondary != null) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AttachmentGrid(
    actions: List<AttachmentAction>,
    modifier: Modifier = Modifier,
) {
    val perRow = if (actions.size <= 4) actions.size else (actions.size + 1) / 2
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.chunked(perRow).fastForEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.fastForEach { action ->
                    AttachmentTile(
                        action = action,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachmentTile(
    action: AttachmentAction,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = action.onClick,
        // 按下时圆角收紧
        shapes = ButtonDefaults.shapes(
            shape = MaterialTheme.shapes.extraLarge,
            pressedShape = MaterialTheme.shapes.medium,
        ),
        modifier = modifier.heightIn(min = 88.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(action.label),
                // 四个并排时较长的译文放不下，自动缩小字号
                autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 列表项尾部的已启用数量
@Composable
internal fun CountBadge(count: Int) {
    Badge(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(count.toString())
    }
}

// 上下文使用量进度环，接近上限时变色提醒压缩
@Composable
private fun ContextUsageRing(remaining: Float) {
    val color = when {
        remaining <= 0.1f -> MaterialTheme.colorScheme.error
        remaining <= 0.3f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    CircularProgressIndicator(
        progress = { remaining.coerceIn(0f, 1f) },
        modifier = Modifier.size(24.dp),
        color = color,
        strokeWidth = 3.dp,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Preview(showBackground = true)
@Composable
private fun AttachmentGridPreview() {
    AttachmentGrid(
        actions = listOf(
            AttachmentAction(HugeIcons.Camera01, R.string.take_picture) {},
            AttachmentAction(HugeIcons.Image02, R.string.photo) {},
            AttachmentAction(HugeIcons.Video01, R.string.video) {},
            AttachmentAction(HugeIcons.MusicNote03, R.string.audio) {},
            AttachmentAction(HugeIcons.Files02, R.string.upload_file) {},
        ),
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun CompressRetentionDialog(
    retentions: List<me.rerere.rikkahub.data.model.CompressRetention>,
    hasLegacy: Boolean,
    legacyNodes: List<MessageNode>? = null,
    onRestore: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var viewNodes by remember { mutableStateOf<List<MessageNode>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上下文压缩管理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "恢复某个位点将一并撤销其之后的所有压缩",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (retentions.isEmpty() && hasLegacy) {
                    RetentionItem(
                        label = "旧版压缩记录",
                        note = "完整对话已被压缩",
                        onView = { viewNodes = legacyNodes },
                        onRestore = { onRestore(0) },
                    )
                }
                retentions.forEachIndexed { index, r ->
                    RetentionItem(
                        label = r.retentionLabel.ifBlank { "压缩留存" },
                        note = if (index > 0) "恢复将同时撤销其后的 $index 个压缩" else "恢复到此点状态",
                        onView = { viewNodes = r.summaryMessageNodes.ifEmpty { r.savedMessageNodes } },
                        onRestore = { onRestore(index) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    viewNodes?.let { nodes ->
        RetentionDetailDialog(nodes = nodes) { viewNodes = null }
    }
}

// v3.8.19: 位点条目改条状窄 UI — 单行横排, 时间戳可收缩, 操作按钮紧凑
@Composable
private fun RetentionItem(
    label: String,
    note: String,
    onView: () -> Unit,
    onRestore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.isNotBlank()) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            TextButton(
                onClick = onView,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("查看", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = onRestore,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("恢复", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// v3.8.19: 查看显示本次压缩得到的摘要 (不再累积原文)
@Composable
private fun RetentionDetailDialog(nodes: List<MessageNode>, onDismiss: () -> Unit) {
    val text = remember(nodes) {
        nodes.joinToString("\n\n---\n\n") { node ->
            runCatching { node.currentMessage }.getOrNull()?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString("\n") { it.text }
                ?: ""
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩摘要") },
        text = {
            Text(
                text.takeIf { it.isNotBlank() } ?: "(无文本内容)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
