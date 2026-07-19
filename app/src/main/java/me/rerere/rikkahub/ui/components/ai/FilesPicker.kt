package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Package01
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.File01

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    mcpManager: McpManager,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
) {
    val settings = LocalSettings.current
    val provider = settings.getCurrentChatModel()?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    var showWorkspaceFileSheet by remember { mutableStateOf(false) }
    val workspaceId = assistant.workspaceId?.toString()
    val inputState = state

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton(onClick = onPickVideo)

                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)

            WorkspaceFilePickButton(onClick = {
                showWorkspaceFileSheet = true
            })
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        if (workspaces.isNotEmpty()) {
            WorkspacePickerListItem(
                assistant = assistant,
                conversation = conversation,
                workspaces = workspaces,
                onUpdateAssistant = onUpdateAssistant,
                onUpdateConversation = onUpdateConversation,
                onNavigateToDetail = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(id))
                },
                onNavigateToTerminal = { id ->
                    onDismiss()
                    navController.navigate(Screen.WorkspaceTerminal(id))
                },
                onNavigateToManage = {
                    onDismiss()
                    navController.navigate(Screen.Workspaces)
                },
            )
        }

        if (settings.mcpServers.isNotEmpty()) {
            McpPickerListItem(
                assistant = assistant,
                servers = settings.mcpServers,
                mcpManager = mcpManager,
                onUpdateAssistant = onUpdateAssistant,
            )
        }

        // Extensions (Quick Messages + Prompt Injections + Skills)
        val modeAndLorebookCount =
            if (assistant.allowConversationPromptInjection) {
                conversation.modeInjectionIds.size + conversation.lorebookIds.size
            } else {
                assistant.modeInjectionIds.size + assistant.lorebookIds.size
            }
        val activeCount =
            assistant.quickMessageIds.size +
                modeAndLorebookCount +
                assistant.enabledSkills.size
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = stringResource(R.string.assistant_page_tab_extensions),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.assistant_page_tab_extensions))
            },
            trailingContent = {
                if (activeCount > 0) {
                    Text(
                        text = activeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowInjectionSheetChange(true)
                },
        )

        // Compress History Button
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package01,
                    contentDescription = stringResource(R.string.chat_page_compress_context),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.chat_page_compress_context))
            },
            trailingContent = {
                if (conversation.messageNodes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_page_message_count, conversation.messageNodes.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    onShowCompressDialogChange(true)
                },
        )

        // Workspace CWD
        val boundWorkspace = remember(workspaces, assistant.workspaceId) {
            workspaces.find { it.id == assistant.workspaceId?.toString() }
        }
        if (boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name) {
            var showCwdSheet by remember { mutableStateOf(false) }
            TextButton(
                onClick = { showCwdSheet = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.Folder01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = conversation.workspaceCwd ?: "/workspace",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showCwdSheet) {
                WorkspaceCwdPickerSheet(
                    workspaceId = boundWorkspace.id,
                    currentCwd = conversation.workspaceCwd,
                    onSelectCwd = { newCwd ->
                        onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                    },
                    onDismiss = { showCwdSheet = false },
                )
            }
        }
    }

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { onShowInjectionSheetChange(false) },
            onDismissAll = onDismiss,
        )
    }

    // Compress Context Dialog
    if (showWorkspaceFileSheet && workspaceId != null) {
        WorkspaceFilePickerSheet(assistant, state, { showWorkspaceFileSheet = false })
    }

    if (showCompressDialog) {
        CompressContextDialog(
            totalMessages = conversation.currentMessages.size,
            onDismiss = {
            onShowCompressDialogChange(false)
            onDismiss()
        }, onConfirm = { additionalPrompt, targetTokens, keepRecentMessages ->
            onCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
        })
    }
}

@Composable
private fun WorkspacePickerListItem(
    assistant: Assistant,
    conversation: Conversation,
    workspaces: List<WorkspaceEntity>,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToTerminal: (String) -> Unit,
    onNavigateToManage: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }

    ListItem(
        leadingContent = {
            Icon(
                imageVector = HugeIcons.Codesandbox,
                contentDescription = stringResource(R.string.assistant_page_workspace),
            )
        },
        headlineContent = {
            Text(stringResource(R.string.assistant_page_workspace))
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
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { showSheet = true },
    )

    if (showSheet) {
        WorkspaceSelectSheet(
            assistant = assistant,
            workspaces = workspaces,
            onSelect = { workspaceId ->
                val newId = workspaceId?.let { Uuid.parse(it) }
                if (newId != assistant.workspaceId) {
                    onUpdateAssistant(assistant.copy(workspaceId = newId))
                    if (conversation.workspaceCwd != null) {
                        onUpdateConversation(conversation.copy(workspaceCwd = null))
                    }
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

@Composable
private fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
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
}

@Composable
private fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Camera01, null)
    }, text = {
        Text(stringResource(R.string.take_picture))
    }) {
        onLaunchCamera()
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}

@Composable
fun WorkspaceFilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Folder01, null)
    }, text = {
        Text("应用文件")
    }) {
        onClick()
    }
}

@Composable
private fun WorkspaceFilePickerSheet(
    assistant: Assistant,
    state: ChatInputState,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaceId = assistant.workspaceId?.toString() ?: return
    val toaster = me.rerere.rikkahub.ui.hooks.LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var browsePath by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedFile by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    LaunchedEffect(browsePath) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, browsePath)
            }
            entries = result.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
        } catch (_: Exception) { entries = emptyList() }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("应用文件", style = MaterialTheme.typography.titleLarge)
            Text("从工作区选择文件添加到对话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(enabled = browsePath.isNotBlank(), onClick = { browsePath = browsePath.substringBeforeLast("/", "") }) { Icon(HugeIcons.ArrowTurnBackward, null) }
                Text(if (browsePath.isBlank()) "/workspace" else "/workspace/$browsePath", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider()
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (!entry.isDirectory) { { Text(formatFileSize(entry.size), style = MaterialTheme.typography.bodySmall) } } else null,
                            leadingContent = { Icon(if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File01, null, Modifier.size(22.dp), tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = ListItemDefaults.colors(containerColor = if (selectedFile?.path == entry.path) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
                            modifier = Modifier.clickable { if (entry.isDirectory) browsePath = entry.path else selectedFile = entry }
                        )
                    }
                }
            }
            if (selectedFile != null) {
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = { selectedFile = null }) { Text("取消") }
                    FilledTonalButton(onClick = {
                        try {
                            val wsFile = java.io.File("/workspace/${selectedFile!!.path}")
                            if (!wsFile.exists()) { toaster.show("文件不存在", type = ToastType.Error); return@FilledTonalButton }
                            val tempFile = java.io.File(context.cacheDir, "ws_pick_${selectedFile!!.name}")
                            wsFile.copyTo(tempFile, overwrite = true)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(tempFile.extension) ?: "application/octet-stream"
                            state.addFiles(listOf(UIMessagePart.Document(url = uri.toString(), fileName = selectedFile!!.name, mime = mime)))
                            onDismiss()
                        } catch (e: Exception) { toaster.show("导入失败: ${e.message}", type = ToastType.Error) }
                    }) { Text("导入到对话") }
                }
            }
        }
    }
}
private fun formatFileSize(bytes: Long): String = when { bytes < 1024 -> "${bytes}B"; bytes < 1024 * 1024 -> "${bytes / 1024}KB"; else -> "${String.format("%.1f", bytes.toDouble() / (1024 * 1024))}MB" }
