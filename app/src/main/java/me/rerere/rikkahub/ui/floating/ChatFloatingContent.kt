package me.rerere.rikkahub.ui.floating

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.pet.PetPack
import me.rerere.rikkahub.data.pet.PetRepository
import me.rerere.rikkahub.data.pet.PetSelection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.ASK_USER_TOOL_NAME
import me.rerere.rikkahub.data.model.GenerationPhase
import me.rerere.rikkahub.service.ChatError
import me.rerere.ui.markdown.Markdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** 悬浮窗展示的会话状态 */
data class FloatingChatUiState(
    val conversationId: Uuid,
    val title: String,
    val generating: Boolean,
    /** 网络重试等临时提示 */
    val processingStatus: String?,
    /** 本轮回复在做什么 */
    val phase: GenerationPhase,
    /** 本轮是否已经有回复 */
    val hasReply: Boolean,
    /** 本轮回复的正文（Markdown），还没写正文时是思考内容 */
    val preview: String,
    val previewIsReasoning: Boolean,
    val pendingTools: List<UIMessagePart.Tool>,
    val queuedCount: Int,
    val queuePaused: Boolean,
    /** 这一轮出现的错误 */
    val error: ChatError?,
) {
    val failed: Boolean get() = !generating && error != null
}

/** 收起时：鲸娘桌宠；不再有 logo 形变/旋转加载动画。 */
@Composable
fun ChatFloatingBubble(state: FloatingChatUiState, onClick: () -> Unit) {
    val pack = rememberSelectedPet()
    Box(modifier = Modifier.padding(4.dp)) {
        PetSprite(
            pack = pack,
            animationName = state.petAnimationName(),
            modifier = Modifier
                .size(72.dp)
                .clickable(onClick = onClick),
        )
    }
}

@Composable
private fun rememberSelectedPet(): PetPack? {
    val context = LocalContext.current
    val repo = remember(context) { PetRepository(context) }
    val revision by PetSelection.revision.collectAsStateWithLifecycle()
    return remember(revision) { repo.selected() }
}

/** 展开后的面板：看进度、审批工具、停止生成和快速回复。[maxHeight] 是窗口能占的高度 */
@Composable
fun ChatFloatingPanel(
    state: FloatingChatUiState,
    maxHeight: Dp,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onOpenConversation: () -> Unit,
    onToolApproval: (toolCallId: String, approved: Boolean) -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onResumeQueue: () -> Unit,
) {
    // 窗口和屏幕一样宽，大屏上限制宽度并居中
    Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PanelHeader(
                    state = state,
                    onOpenConversation = onOpenConversation,
                    onCollapse = onCollapse,
                    onDismiss = onDismiss,
                )
                // 多个审批、横屏或大字体时放不下，中间这段滚动，标题和输入栏始终可见
                if (state.preview.isNotEmpty() || state.pendingTools.isNotEmpty() || state.queuedCount > 0) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.preview.isNotEmpty()) {
                            MessagePreview(
                                text = state.preview,
                                isReasoning = state.previewIsReasoning,
                                generating = state.generating,
                            )
                        }
                        state.pendingTools.forEach { tool ->
                            PendingToolCard(
                                tool = tool,
                                onApproval = { approved -> onToolApproval(tool.toolCallId, approved) },
                                onOpenConversation = onOpenConversation,
                            )
                        }
                        if (state.queuedCount > 0) {
                            QueueRow(count = state.queuedCount, paused = state.queuePaused, onResume = onResumeQueue)
                        }
                    }
                }
                ReplyBar(
                    conversationId = state.conversationId,
                    generating = state.generating,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: FloatingChatUiState,
    onOpenConversation: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PetSprite(
            pack = rememberSelectedPet(),
            animationName = state.petAnimationName(),
            modifier = Modifier.size(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.statusText(),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onOpenConversation) {
            Icon(HugeIcons.Fullscreen, contentDescription = stringResource(R.string.floating_chat_open_conversation))
        }
        IconButton(onClick = onCollapse) {
            Icon(HugeIcons.Remove01, contentDescription = stringResource(R.string.floating_chat_collapse))
        }
        IconButton(onClick = onDismiss) {
            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.floating_chat_close))
        }
    }
}

// 用 ui 模块的通用 Markdown：应用里的 MarkdownBlock 依赖导航、Activity 等悬浮窗里没有的东西
@Composable
private fun MessagePreview(text: String, isReasoning: Boolean, generating: Boolean) {
    val scrollState = rememberScrollState()
    // 生成中跟随最新内容。Markdown 在后台解析，内容变高晚于文本变化，按可滚动距离跟随
    LaunchedEffect(generating) {
        if (generating) snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = if (isReasoning) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Markdown(
            content = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(scrollState)
                .padding(12.dp),
        )
    }
}

@Composable
private fun PendingToolCard(
    tool: UIMessagePart.Tool,
    onApproval: (approved: Boolean) -> Unit,
    onOpenConversation: () -> Unit,
) {
    val isAskUser = tool.toolName == ASK_USER_TOOL_NAME
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (isAskUser) {
                    stringResource(R.string.floating_chat_ask_user)
                } else {
                    stringResource(R.string.floating_chat_tool_request, tool.toolName.substringAfterLast("__"))
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!isAskUser && tool.input.isNotBlank()) {
                Text(
                    text = tool.input.take(300),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (isAskUser) {
                    // 问答有多个问题和选项，在应用里回答
                    Button(onClick = onOpenConversation) { Text(stringResource(R.string.floating_chat_answer)) }
                } else {
                    OutlinedButton(onClick = { onApproval(false) }) { Text(stringResource(R.string.chat_message_tool_deny)) }
                    Button(onClick = { onApproval(true) }) { Text(stringResource(R.string.chat_message_tool_approve)) }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(count: Int, paused: Boolean, onResume: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(
                if (paused) R.string.chat_page_queue_paused_count else R.string.chat_page_queue_pending_count,
                count,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (paused) {
            TextButton(onClick = onResume) { Text(stringResource(R.string.chat_page_queue_resume)) }
        }
    }
}

@Composable
private fun ReplyBar(
    conversationId: Uuid,
    generating: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var text by rememberSaveable(conversationId) { mutableStateOf("") }

    fun send() {
        val content = text.trim()
        if (content.isEmpty()) return
        onSend(content)
        text = ""
        focusManager.clearFocus()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            // 生成中发送的消息会排队，等这一轮结束后再发
            placeholder = {
                Text(
                    stringResource(
                        if (generating) R.string.floating_chat_reply_queued_placeholder else R.string.floating_chat_reply_placeholder
                    )
                )
            },
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
        )
        if (generating) {
            FilledTonalIconButton(onClick = onStop) {
                Icon(HugeIcons.Stop, contentDescription = stringResource(R.string.stop))
            }
        }
        FilledIconButton(onClick = ::send, enabled = text.isNotBlank()) {
            Icon(HugeIcons.ArrowUp02, contentDescription = stringResource(R.string.send))
        }
    }
}


@Composable
private fun FloatingChatUiState.statusText(): String {
    if (!generating) {
        return when {
            error != null -> error.title ?: error.error.message ?: stringResource(R.string.floating_chat_status_failed)
            pendingTools.any { it.toolName == ASK_USER_TOOL_NAME } -> stringResource(R.string.floating_chat_status_awaiting_answer)
            pendingTools.isNotEmpty() -> stringResource(R.string.floating_chat_status_awaiting_approval)
            hasReply -> stringResource(R.string.floating_chat_status_done)
            else -> stringResource(R.string.floating_chat_status_idle)
        }
    }
    // 网络重试等提示优先
    processingStatus?.let { return it }
    return when (phase) {
        is GenerationPhase.RunningTool -> stringResource(R.string.notification_live_update_tool, phase.toolName)
        is GenerationPhase.Thinking -> stringResource(R.string.notification_live_update_thinking)
        is GenerationPhase.Writing -> stringResource(R.string.notification_live_update_writing)
        GenerationPhase.Waiting -> stringResource(R.string.notification_live_update_title)
    }
}
