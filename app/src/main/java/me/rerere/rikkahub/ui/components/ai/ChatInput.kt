package me.rerere.rikkahub.ui.components.ai

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Popup
import me.rerere.hugeicons.stroke.Package
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.InboxSource
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.service.MessageQueueState
import me.rerere.rikkahub.service.QueuedMessage
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import me.rerere.rikkahub.ui.components.ui.KeepScreenOn
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.SoundEffectPlayer
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds
import me.rerere.rikkahub.ui.pages.chat.VoicePhase
import me.rerere.rikkahub.ui.pages.chat.VoiceSessionState
import kotlin.uuid.Uuid
import androidx.compose.runtime.DisposableEffect

@Composable
fun ChatInput(
    state: ChatInputState,
    loading: Boolean,
    settings: Settings,
    // 会话视角下的助手和模型：会话开始后以会话上固定的配置为准
    assistant: Assistant,
    chatModel: Model?,
    hazeState: HazeState,
    onUpdateSearchMode: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
    completionProviders: List<ChatCompletionProvider> = emptyList(),
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onMoreClick: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    messageQueue: MessageQueueState = MessageQueueState(),
    onRemoveQueuedMessage: (Uuid) -> Unit = {},
    onBeginEditQueuedMessage: (Uuid) -> QueuedMessage? = { null },
    onFinishEditQueuedMessage: (Uuid, List<UIMessagePart>?) -> Unit = { _, _ -> },
    onResumeMessageQueue: () -> Unit = {},
    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
    // v5.0.17 用户定案：输入栏 + 号 AI 交互窗（拍照 / 图片 / 上传文件）
    onTakePic: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickFile: () -> Unit = {},
    // v5.0.52：5.0.31 UI 版补回工具矩阵选择器数据源（@/ 提及用；由 ChatPage 提供）
    toolPickerProvider: (suspend () -> me.rerere.rikkahub.ui.components.ai.ToolMatrixPickerData?)? = null,
    // v5.0.92（用户定案）：输入栏获得焦点即预热（仅 Opencode go / CommandCode）
    onRequestProviderWarm: () -> Unit = {},
    // v5.1.2（用户要求）：偏好设置 → 主题 控制输入栏这两个键是否显示
    showSendButton: Boolean = true,
    showReasoningButton: Boolean = true,
    // v5.1.4（用户要求）：主对话页模型选择器里的「快速 / 极致」路由
    modelRouting: Int = 0,
    onSelectModelRouting: (Int) -> Unit = {},
) {
    val toaster = LocalToaster.current
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow

    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val themeShape = MaterialTheme.shapes.largeIncreased
    val containerShape = RoundedCornerShape(
        topStart = themeShape.topStart,
        topEnd = themeShape.topEnd,
        bottomEnd = themeShape.bottomEnd,
        bottomStart = themeShape.bottomStart,
    )
    val modelListState = rememberModelListState(
        modelId = chatModel?.id,
        providers = settings.providers,
        type = ModelType.CHAT,
    )

    fun sendMessage() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onSendClick()
    }

    fun sendMessageWithoutAnswer() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        if (loading && state.isEmpty()) onCancelClick() else onLongSendClick()
    }

    val asr = LocalASRState.current
    val asrState by asr.state.collectAsState()

    // v5.0.96 ★「输入栏凭空出现陌生文字」修法之三：
    // ASR 是 **Activity 级共享实例**（RouteActivity 里 LocalASRState provides asr），
    // 而转写回调 `onTranscriptChange` 捕获的是**本页**的 ChatInputState。
    // 离开页面时不停止录音 → 残留/迟到的转写回调继续把文本写进旧（甚至新）输入框；
    // 这就是"碎片文本串行"的来源之一。离开即停、开始新录音前先停上一轮。
    DisposableEffect(Unit) {
        onDispose { runCatching { asr.stop() } }
    }
    val hapticFeedback = LocalHapticFeedback.current
    val soundEffectPlayer: SoundEffectPlayer = koinInject()
    LaunchedEffect(Unit) {
        soundEffectPlayer.preload(R.raw.asr_start, R.raw.asr_stop)
    }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = asrPermission)
    var asrBaseText by remember { mutableStateOf("") }
    LaunchedEffect(asrState.status) {
        when (asrState.status) {
            ASRStatus.Listening -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                soundEffectPlayer.play(R.raw.asr_start)
            }

            ASRStatus.Stopping -> {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                soundEffectPlayer.play(R.raw.asr_stop)
            }

            else -> {}
        }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            toaster.show(message = message, type = ToastType.Error)
        }
    }

    // 底部渐变遮罩：从输入区顶部一直过渡到屏幕底部（键盘弹出时到键盘上沿），避免消息透到系统导航栏
    val scrimColor = MaterialTheme.colorScheme.background
    val imeInsets = WindowInsets.ime
    Surface(
        color = Color.Transparent,
        modifier = Modifier.drawWithCache {
            val brush = Brush.verticalGradient(
                colors = listOf(scrimColor.copy(alpha = 0f), scrimColor),
                startY = 0f,
                endY = (size.height - imeInsets.getBottom(this)).coerceAtLeast(1f),
            )
            onDrawBehind { drawRect(brush) }
        },
    ) {
        Column(
            modifier = modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MessageQueuePanel(
                state = messageQueue,
                onRemove = onRemoveQueuedMessage,
                onBeginEdit = onBeginEditQueuedMessage,
                onFinishEdit = onFinishEditQueuedMessage,
                onResume = onResumeMessageQueue,
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(containerShape)
                    .chatInputHaze(
                        hazeState = hazeState,
                        displaySetting = settings.displaySetting,
                        containerColor = containerColor,
                        shape = containerShape,
                    ),
                shape = containerShape,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                color = if (settings.displaySetting.enableBlurEffect) Color.Transparent else containerColor,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (voiceState.phase != VoicePhase.Off) {
                        VoiceModeRow(
                            state = voiceState,
                            onStop = onStopVoiceMode,
                            onRetry = { onStartVoiceMode?.invoke() },
                        )
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    if (state.messageContent.isNotEmpty()) {
                        MediaFileInputRow(state = state)
                    }

                    TextInputRow(
                        state = state,
                        assistant = assistant,
                        completionProviders = completionProviders,
                        onSendMessage = { sendMessage() },
                        toolPickerProvider = toolPickerProvider,
                        onRequestProviderWarm = onRequestProviderWarm,
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            // v5.0.24 用户实证：加号偏上 = 本行默认顶部对齐 + 30dp vs 36dp 高度差
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            // Model Picker
                            ModelSelectorButton(
                                modelRouting = modelRouting,
                                state = modelListState,
                                onlyIcon = true,
                                modifier = Modifier,
                            )

                            // v5.0.21 用户定案：AI 交互窗「+」位于模型选择与思考程度之间（输入栏下侧靠左）
                            var showPlusMenu by remember { mutableStateOf(false) }
                            ActionIconButton(
                                onClick = { showPlusMenu = true }
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Add01,
                                    contentDescription = "AI 交互"
                                )
                            }
                            if (showPlusMenu) {
                                Popup(
                                    // v5.0.25 用户定案：菜单相对加号稍微往右上偏
                                    alignment = Alignment.TopStart,
                                    offset = IntOffset(14, 4),
                                    onDismissRequest = { showPlusMenu = false },
                                    properties = PopupProperties(focusable = true),
                                ) {
                                    PlusActionMenu(
                                        onTakePic = onTakePic,
                                        onPickImage = onPickImage,
                                        onPickFile = onPickFile,
                                        onDismiss = { showPlusMenu = false },
                                    )
                                }
                            }

                            // v5.0.17：思考程度回到左侧（模型旁）
                            if (showReasoningButton &&
                                chatModel?.abilities?.contains(ModelAbility.REASONING) == true
                            ) {
                                ReasoningButton(
                                    reasoningLevel = assistant.reasoningLevel,
                                    onUpdateReasoningLevel = {
                                        onUpdateAssistant(assistant.copy(reasoningLevel = it))
                                    },
                                    onlyIcon = true,
                                )
                            }

                        }

                        // v5.0.20 用户定案：纸箱 = 工具箱（+ 已移到模型与思考之间）
                        ActionIconButton(
                            onClick = onMoreClick
                        ) {
                            Icon(
                                imageVector = HugeIcons.Package,
                                contentDescription = stringResource(R.string.more_options)
                            )
                        }

                        if (!voiceState.isActive && (asrState.isAvailable || asrState.isRecording)) {
                            AsrButton(
                                state = asrState,
                                onClick = {
                                    when (asrState.status) {
                                        ASRStatus.Listening -> asr.stop()
                                        ASRStatus.Idle, ASRStatus.Error -> {
                                            if (!asrPermission.allRequiredPermissionsGranted) {
                                                asrPermission.requestPermissions()
                                            } else {
                                                // v5.0.96：先停掉上一轮（清回调/缓冲），防旧转写残留串进新文本
                                                runCatching { asr.stop() }
                                                asrBaseText = state.textContent.text.toString()
                                                asr.start { transcript ->
                                                    val spacer =
                                                        if (asrBaseText.isBlank() || transcript.isBlank()) "" else " "
                                                    state.setMessageText(asrBaseText + spacer + transcript)
                                                }
                                            }
                                        }

                                        ASRStatus.Connecting, ASRStatus.Stopping -> {}
                                    }
                                }
                            )
                        }

                        if (loading) {
                            KeepScreenOn()
                        }



                        AnimatedVisibility(
                            // v5.1.2：主题设置里可关闭「发送键 / 终止键」
                            visible = !asrState.isRecording && showSendButton,
                            enter = fadeIn() + scaleIn(),
                            exit = fadeOut() + scaleOut(),
                        ) {
                            SendButton(
                                loading = loading,
                                empty = state.isEmpty(),
                                onClick = { sendMessage() },
                                onLongClick = { sendMessageWithoutAnswer() },
                            )
                        }
                    }
                }
            }

        }
    }

    ModelListSheet(
        state = modelListState,
        routing = modelRouting,
        onSelectRouting = onSelectModelRouting,
        onSelect = onUpdateChatModel,
    )
}

@Composable
private fun PlusActionMenu(
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    // v5.0.21 用户定案：交互窗 = 玻璃观感（半透明底 + 1dp 高光描边，与输入栏/工具箱同族）
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
        ),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PlusMenuItem(HugeIcons.Camera01, "拍照", onTakePic, onDismiss)
            PlusMenuItem(HugeIcons.Image02, "图片", onPickImage, onDismiss)
            PlusMenuItem(HugeIcons.File02, "上传文件", onPickFile, onDismiss)
        }
    }
}

@Composable
private fun PlusMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable {
                onDismiss()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SendButton(
    loading: Boolean,
    empty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showStop = loading && empty
    val containerColor = when {
        showStop -> MaterialTheme.colorScheme.errorContainer
        empty -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        showStop -> MaterialTheme.colorScheme.onErrorContainer
        empty -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(30.dp)
            .testTag("chat_send_button")
            .clip(CircleShape)
            .combinedClickable(
                enabled = showStop || !empty,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = containerColor,
            content = {},
        )
        Icon(
            imageVector = if (showStop) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
            contentDescription = stringResource(if (showStop) R.string.stop else R.string.send),
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ActionIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        // v5.0.24 用户实证：与 ModelSelectorButton 的 36dp 对齐（原 30dp 视觉偏上/偏小）
        modifier = Modifier.size(36.dp),
        shape = CircleShape,
        tonalElevation = 0.dp,
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun TextInputRow(
    state: ChatInputState,
    assistant: Assistant,
    completionProviders: List<ChatCompletionProvider>,
    onSendMessage: () -> Unit,
    // v5.0.64: /@ 触发工具矩阵选择器 —— 数据源由 ChatInput 外层传入（内层函数看不到外层参数）
    toolPickerProvider: (suspend () -> ToolMatrixPickerData?)? = null,
    // v5.0.92（用户定案）：输入栏一获得焦点就预热当前模型连接（Opencode go / CommandCode）
    onRequestProviderWarm: () -> Unit = {},
) {
    val settings = LocalSettings.current
    val filesManager: FilesManager = koinInject()
    // v5.0.78：粘贴内容（图片/长文本）的导入统一走收件箱门面 + 全进程 scope
    // （旧实现在 IME 内容接收管线里同步做磁盘 IO，大内容直接卡住输入处理）
    val inbox: me.rerere.rikkahub.data.files.InboxStore = koinInject()
    val appScope: me.rerere.rikkahub.AppScope = koinInject()
    val quickMessages = remember(settings.quickMessages, assistant.quickMessageIds) {
        settings.getQuickMessagesOfAssistant(assistant)
    }

    var isEditingReasoning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (state.isEditing()) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.editing))
                    Spacer(Modifier.weight(1f))
                    if (state.editingReasoning != null) {
                        TextButton(onClick = { isEditingReasoning = true }) {
                            Text(stringResource(R.string.chat_input_edit_reasoning))
                        }
                    }
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = stringResource(R.string.cancel_edit),
                        modifier = Modifier.clickable { state.clearInput() }
                    )
                }
            }
        }

        var isFocused by remember { mutableStateOf(false) }
        var isFullScreen by remember { mutableStateOf(false) }
        var completionList by remember { mutableStateOf<ChatCompletionList?>(null) }
        // v5.0.64 ★「/@ 快捷输入坏掉」根因修复：
        // 参数 toolPickerProvider 从 v5.0.52 起就在签名里，但**整个函数体从未使用它** ——
        // 输入 `/` 或 `@` 什么都不会发生（模型仍能收到 @提及 语义，但输入框没选择器）。
        // 现在补上触发 + 面板 + 插入三段。
        var toolPickerData by remember { mutableStateOf<ToolMatrixPickerData?>(null) }
        var toolPickerStack by remember { mutableStateOf<List<String>>(emptyList()) }

        // v5.0.78：内层作用域看不到外层 val（v5.0.64 同款坑）——粘贴处理所需依赖就地注入
        val toaster = LocalToaster.current
        val inbox: me.rerere.rikkahub.data.files.InboxStore = koinInject()
        val appScope: me.rerere.rikkahub.AppScope = koinInject()

        /** 把光标处的 `/@`（或 `/`、`@`）整段替换成 `@<name> `（工具/子区引用统一入口） */
        fun insertToolMentionAtCursor(mention: String) {
            val text = state.textContent.text.toString()
            val caret = state.textContent.selection.start.coerceIn(0, text.length)
            val before = text.take(caret)
            // `/@` 是两字符触发：起点取斜杠位置（maxOf 会只取到 '@' → 留下一个 '/'）
            val trigger = if (before.length >= 2 &&
                before[before.length - 1] == '@' &&
                before[before.length - 2] == '/'
            ) before.length - 2 else maxOf(before.lastIndexOf('/'), before.lastIndexOf('@'))
            if (trigger >= 0) {
                // state.textContent 是 TextFieldState（val，不可赋值）→ 必须用 edit{ replace }
                state.textContent.edit { replace(trigger, caret, mention) }
            } else {
                // 光标处没有触发字符（例如面板是从别处打开的）→ 直接插入到光标
                state.textContent.edit { replace(caret, caret, mention) }
            }
        }
        val receiveContentListener = remember(
            settings.displaySetting.pasteLongTextAsFile, settings.displaySetting.pasteLongTextThreshold
        ) {
            ReceiveContentListener { transferableContent ->
                when {
                    // v5.0.78 ★两处主线程磁盘 IO 消除：粘贴图片/长文本转文件一律收口到
                    // 收件箱门面（InboxStore，IO 线程）—— 旧实现在 IME 内容接收管线里
                    // 同步做 openInputStream/copyTo/createChatTextFile，大内容直接卡住
                    // 输入处理（粘贴即卡，selection 更新被延迟）。现在：先 consume 阻止
                    // 默认行为 + 异步门面导入，导入成功后才 addImages/addFiles + toast。
                    transferableContent.hasMediaType(MediaType.Image) -> {
                        transferableContent.consume { item ->
                            val uri = item.uri
                            if (uri != null) {
                                appScope.launch {
                                    try {
                                        val entry = inbox.import(InboxSource.FromUri(uri))
                                        state.addImages(listOf(entry.uri))
                                    } catch (e: Throwable) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        toaster.show(
                                            e.message ?: "粘贴图片保存失败：未知错误",
                                            type = ToastType.Error,
                                        )
                                    }
                                }
                            }
                            uri != null
                        }
                    }

                    settings.displaySetting.pasteLongTextAsFile && transferableContent.hasMediaType(MediaType.Text) -> {
                        transferableContent.consume { item ->
                            val text = item.text?.toString()
                            if (text != null && text.length > settings.displaySetting.pasteLongTextThreshold) {
                                appScope.launch {
                                    try {
                                        val entry = inbox.import(
                                            InboxSource.FromBytes(
                                                bytes = text.encodeToByteArray(),
                                                displayName = "paste_${System.currentTimeMillis()}.txt",
                                                mime = "text/plain",
                                            )
                                        )
                                        state.addFiles(
                                            listOf(
                                                UIMessagePart.Document(
                                                    url = entry.uri.toString(),
                                                    fileName = entry.displayName,
                                                    mime = entry.mime,
                                                )
                                            )
                                        )
                                        toaster.show("长文本已转为文件附件", type = ToastType.Success)
                                    } catch (e: Throwable) {
                                        if (e is kotlinx.coroutines.CancellationException) throw e
                                        toaster.show(
                                            e.message ?: "长文本转文件失败：未知错误",
                                            type = ToastType.Error,
                                        )
                                    }
                                }
                                true
                            } else {
                                false
                            }
                        }
                    }

                    else -> transferableContent
                }
            }
        }

        LaunchedEffect(completionProviders, isFocused) {
            if (!isFocused || completionProviders.isEmpty()) {
                completionList = null
                return@LaunchedEffect
            }

            snapshotFlow {
                ChatCompletionContext(
                    text = state.textContent.text.toString(),
                    selection = state.textContent.selection,
                )
            }.collectLatest { context ->
                val lists = completionProviders.mapNotNull { provider ->
                    try {
                        provider.complete(context)
                            ?.takeIf { it.items.isNotEmpty() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val primary = lists.firstOrNull()
                completionList = primary?.let { list ->
                    val mergedItems = lists
                        .filter { it.replacementRange == list.replacementRange }
                        .flatMap { it.items }
                        .distinctBy { it.label to it.insertText }
                        .sortedWith(
                            compareByDescending<ChatCompletionItem> { it.sortScore }
                                .thenBy { it.label.length }
                                .thenBy { it.label.lowercase() }
                        )
                        .take(8)
                    list.copy(items = mergedItems)
                }
            }
        }

        // v5.0.64：`/` 或 `@`（光标前最后一个 token 恰为单字符）→ 打开工具矩阵选择器
        LaunchedEffect(toolPickerProvider, isFocused) {
            val provider = toolPickerProvider
            if (provider == null) return@LaunchedEffect
            if (!isFocused) {
                toolPickerData = null
                return@LaunchedEffect
            }
            snapshotFlow {
                val text = state.textContent.text.toString()
                val caret = state.textContent.selection.start.coerceIn(0, text.length)
                text.take(caret)
            }.collectLatest { before ->
                // v5.0.68 ★用户纠正：这里**只认 `/@`（斜杠+艾特）**，不是 `/` 也不是 `@`。
                // 分工与 4.8.190 逐字一致（见其 detectToolMention 注释）：
                //   · 空格后的 `@`  → **Workspace 文件补全**（WorkspaceCompletionProvider）
                //   · 斜杠后的 `/@` → **工具矩阵**（ToolMatrixPickerPopup）
                // 两者天然分流：`@` 前是 `/` 就不是文件补全的边界。
                // v5.0.64 我错把 `@` 和 `/` 都接给工具矩阵，抢掉了 workspace 补全。
                val isSlashAt = before.length >= 2 &&
                    before[before.length - 1] == '@' &&
                    before[before.length - 2] == '/'
                if (isSlashAt) {
                    if (toolPickerData == null) {
                        toolPickerData = runCatching { provider() }.getOrNull()
                        toolPickerStack = emptyList()
                    }
                } else {
                    toolPickerData = null
                }
            }
        }

        toolPickerData?.let { data ->
            ToolMatrixPickerPopup(
                data = data,
                zoneStack = toolPickerStack,
                onPush = { id -> toolPickerStack = toolPickerStack + id },
                onBack = { toolPickerStack = toolPickerStack.dropLast(1) },
                onPick = { tool ->
                    // 用 rawName（真实工具名）：`name` 可能是 settings.toolNameOverrides 的别名，
                    // `@别名` 在 ToolMentionTransformer 里解析不到真实工具。
                    insertToolMentionAtCursor("@${tool.rawName} ")
                    toolPickerData = null
                    toolPickerStack = emptyList()
                },
                // v5.0.69 ★用户实测纠正：子区行右侧的 @ 图标 = **把该子区作为 @提及引用**
                // （插入输入框让模型加载/使用整个区），**不是**下钻。
                // 下钻由行主体的 onPush 负责；我上一版把 onPickZone 接到了 push 上，
                // 于是点 @ 图标会直接进区 → "这个 UI 完全失效"。
                onPickZone = { zone ->
                    // 与 4.8.190 逐字一致：插入 **完整路径 zone.id**（不是 label）。
                    // `@提及` 由 ZoneRouter.resolveCandidates(token) 解析，只认路径/工具名；
                    // label 只是显示名（叶子），插进去解析不到 → 等于没引用。
                    insertToolMentionAtCursor("@${zone.id} ")
                    toolPickerData = null
                    toolPickerStack = emptyList()
                },
                onDismiss = {
                    toolPickerData = null
                    toolPickerStack = emptyList()
                },
            )
        }

        completionList?.takeIf { it.items.isNotEmpty() }?.let { list ->
            CompletionPopup(
                completionList = list,
                onItemClick = { item ->
                    state.applyCompletion(list.replacementRange, item)
                    completionList = null
                },
            )
        }

        TextField(
            state = state.textContent,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("chat_input")
                .contentReceiver(receiveContentListener)
                .onFocusChanged {
                    isFocused = it.isFocused
                    // v5.0.92（用户定案）：每次打开输入栏（获得焦点）就预热一次。
                    // 幂等：同 baseUrl 已有常驻心跳时直接返回；只对 Opencode/CommandCode 生效。
                    if (it.isFocused) {
                        runCatching { onRequestProviderWarm() }
                    }
                },
            shape = MaterialTheme.shapes.largeIncreased,
            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (settings.displaySetting.sendOnEnter) ImeAction.Send else ImeAction.Default
            ),
            onKeyboardAction = {
                if (settings.displaySetting.sendOnEnter && !state.isEmpty()) {
                    onSendMessage()
                }
            },
            colors = TextFieldDefaults.colors().copy(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isFocused) {
                        IconButton(
                            onClick = {
                                isFullScreen = !isFullScreen
                            }) {
                            Icon(HugeIcons.Fullscreen, null)
                        }
                    }
                }
            },
            leadingIcon = if (quickMessages.isNotEmpty()) {
                {
                    QuickMessageButton(quickMessages = quickMessages, state = state)
                }
            } else null,
        )
        if (isFullScreen) {
            FullScreenEditor(
                textState = state.textContent,
                placeholder = "",
            ) {
                isFullScreen = false
            }
        }
        val reasoningState = state.editingReasoning
        if (isEditingReasoning && reasoningState != null) {
            FullScreenEditor(textState = reasoningState, placeholder = stringResource(R.string.chat_input_reasoning_placeholder)) {
                isEditingReasoning = false
            }
        }
    }
}

@Composable
private fun CompletionPopup(
    completionList: ChatCompletionList,
    onItemClick: (ChatCompletionItem) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 280.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            items(
                items = completionList.items,
                key = { item -> "${item.label}:${item.insertText}" },
            ) { item ->
                Surface(
                    onClick = { onItemClick(item) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            item.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ChatInputState.applyCompletion(
    replacementRange: TextRange,
    item: ChatCompletionItem,
) {
    val textLength = textContent.text.length
    val start = replacementRange.min.coerceIn(0, textLength)
    val end = replacementRange.max.coerceIn(start, textLength)
    textContent.edit {
        replace(start, end, item.insertText)
        selection = TextRange(start + item.insertText.length)
    }
}

@Composable
private fun QuickMessageButton(
    quickMessages: List<QuickMessage>,
    state: ChatInputState,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = {
            expanded = !expanded
        }) {
        Icon(HugeIcons.Zap, null)
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 200.dp, max = 360.dp)
        ) {
            quickMessages.forEach { quickMessage ->
                Surface(
                    onClick = {
                        state.appendText(quickMessage.content)
                        expanded = false
                    },
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = quickMessage.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = quickMessage.content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenEditor(
    textState: TextFieldState,
    placeholder: String,
    onDone: () -> Unit
) {
    BasicAlertDialog(
        onDismissRequest = {
            onDone()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight(0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row {
                        TextButton(
                            onClick = {
                                onDone()
                            }) {
                            Text(stringResource(R.string.chat_page_save))
                        }
                    }
                    TextField(
                        state = textState,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .fillMaxSize(),
                        shape = RoundedCornerShape(32.dp),
                        placeholder = {
                            Text(placeholder)
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        colors = TextFieldDefaults.colors().copy(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
