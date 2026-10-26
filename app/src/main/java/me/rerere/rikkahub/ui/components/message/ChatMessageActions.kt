package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FavouriteCircle
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Share04
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.hugeicons.stroke.Translate
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.utils.copyMessageToClipboard
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.toMessageTimeString
import me.rerere.ui.components.RikkaConfirmDialog
import java.util.Locale

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onTranslate: ((UIMessage, Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    // v5.1.9（用户要求）：气泡下这一排的「语音播放」与「更多」里的「网页视图渲染」互换位置
    onWebViewPreview: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var isPendingDelete by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(isPendingDelete) {
        if (isPendingDelete) {
            delay(3000) // 3秒后自动取消
            isPendingDelete = false
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ChatMessageActionButton(
            icon = HugeIcons.Copy01,
            contentDescription = stringResource(R.string.copy),
            onClick = { context.copyMessageToClipboard(message) },
        )

        ChatMessageActionButton(
            icon = HugeIcons.Refresh03,
            contentDescription = stringResource(R.string.regenerate),
            onClick = {
                if (message.role == MessageRole.USER) {
                    showRegenerateConfirm = true
                } else {
                    onRegenerate()
                }
            },
        )

        // v5.1.9（用户要求）：此处原本是「语音播放」按钮，现与「更多」里的
        // 「网页视图渲染」互换 —— 气泡下直接可点渲染，语音播放移到更多菜单里。
        if (onWebViewPreview != null && message.role == MessageRole.ASSISTANT) {
            ChatMessageActionButton(
                icon = HugeIcons.WebDesign01,
                contentDescription = "网页视图渲染",
                onClick = onWebViewPreview,
            )
        }

        // Translation button
        if (onTranslate != null) {
            ChatMessageActionButton(
                icon = HugeIcons.Translate,
                contentDescription = stringResource(R.string.translate),
                onClick = { showTranslateDialog = true },
            )
        }

        ChatMessageActionButton(
            icon = HugeIcons.MoreVertical,
            contentDescription = stringResource(R.string.more_options),
            onClick = onOpenActionSheet,
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
        )

        if (settings.displaySetting.showDateTimeInMessage) {
            Text(
                text = message.createdAt.toJavaLocalDateTime().toMessageTimeString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    // Translation dialog
    if (showTranslateDialog && onTranslate != null) {
        LanguageSelectionDialog(
            onLanguageSelected = { language ->
                showTranslateDialog = false
                onTranslate(message, language)
            },
            onClearTranslation = {
                showTranslateDialog = false
                onClearTranslation(message)
            },
            onDismissRequest = {
                showTranslateDialog = false
            },
        )
    }

    // Regenerate confirmation dialog
    RikkaConfirmDialog(
        show = showRegenerateConfirm,
        title = stringResource(R.string.regenerate),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showRegenerateConfirm = false
            onRegenerate()
        },
        onDismiss = { showRegenerateConfirm = false },
        text = { Text(stringResource(R.string.regenerate_confirm_message)) }
    )
}

private val ActionIconSize = 18.dp

// 消息下方的小号图标按钮，按下时圆形收成方角
@Composable
internal fun ChatMessageActionButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 按钮排得密，不让 48dp 的最小触控尺寸把间距撑开
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        IconButton(
            onClick = onClick,
            shapes = IconButtonDefaults.shapes(),
            modifier = modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(ActionIconSize),
            )
        }
    }
}

private class MessageSheetAction(
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        // WebView Preview (only show if message has text content)
        val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
        val actions = buildList {
            add(MessageSheetAction(HugeIcons.TextSelection, stringResource(R.string.select_and_copy), onClick = onSelectAndCopy))
            // v5.1.9（用户要求）：原来这里是「网页视图渲染」，已与气泡下的
            // 「语音播放」互换 —— 语音播放移进更多菜单，渲染放到气泡下。
            if (message.role == MessageRole.ASSISTANT) {
                val tts = LocalTTSState.current
                val isSpeaking by tts.isSpeaking.collectAsState()
                val isAvailable by tts.isAvailable.collectAsState()
                add(
                    MessageSheetAction(
                        icon = if (isSpeaking) HugeIcons.StopCircle else HugeIcons.VolumeHigh,
                        label = if (isSpeaking) "停止朗读" else "语音播放",
                        onClick = {
                            if (isSpeaking) {
                                tts.stop()
                            } else if (isAvailable) {
                                var textToSpeak = message.toText()
                                if (settings.displaySetting.ttsOnlyReadQuoted) {
                                    textToSpeak = textToSpeak.extractQuotedContentAsText() ?: textToSpeak
                                }
                                if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                                    textToSpeak = textToSpeak.removeBracketedContent() ?: textToSpeak
                                }
                                tts.speak(textToSpeak)
                            }
                        },
                    )
                )
            }
            add(MessageSheetAction(HugeIcons.Edit01, stringResource(R.string.edit), onClick = onEdit))
            add(MessageSheetAction(HugeIcons.Share04, stringResource(R.string.share), onClick = onShare))
            add(MessageSheetAction(HugeIcons.GitFork, stringResource(R.string.create_fork), onClick = onFork))
            if (onToggleFavorite != null) {
                add(
                    MessageSheetAction(
                        icon = HugeIcons.FavouriteCircle,
                        label = stringResource(
                            if (isFavorite) R.string.chat_message_remove_favorite
                            else R.string.chat_message_add_favorite
                        ),
                        onClick = onToggleFavorite,
                    )
                )
            }
            add(MessageSheetAction(HugeIcons.Delete01, stringResource(R.string.delete), destructive = true, onClick = onDelete))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // v5.1.8 ★用户实测"某条消息删不掉"的真因：这是 ModalBottomSheet，
                // 默认高度受限；操作项有 7~8 个（复制/重新生成/朗读/翻译/收藏/更多…），
                // 删除是**最后一项** → 没有滚动时它直接落在可视区之外，用户根本点不到。
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                actions.fastForEachIndexed { index, action ->
                    SegmentedListItem(
                        onClick = {
                            onDismissRequest()
                            action.onClick()
                        },
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = actions.size),
                        colors = if (action.destructive) {
                            ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                leadingContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            ListItemDefaults.segmentedColors()
                        },
                        leadingContent = {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                            )
                        },
                    ) {
                        Text(action.label)
                    }
                }
            }

            // Message Info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                    if (model != null) {
                        Text(model.displayName)
                    }
                }
            }
        }
    }
}
