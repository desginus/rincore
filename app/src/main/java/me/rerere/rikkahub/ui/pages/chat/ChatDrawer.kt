package me.rerere.rikkahub.ui.pages.chat


/* ───【域 A·对话核心】ChatDrawer.kt
 * 职责: 对话抽屉 (会话列表/文件夹/助手切换/新建)
 * 常用改动: 入口导航 → navigate 调用; 新建归属 → drawerVm.selectedFolderId
 * 问题定位: 抽屉内容/新建归属错误 → 本文件 + ChatDrawerVM
 * 基线: 原版移植 + 自研 (文件夹/入口) | 地图: docs/APP_MAP.md §A | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.ui.components.ai.WorkspaceCwdPickerSheet
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.rerere.rikkahub.ui.theme.extendColors
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Share03
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.InLove
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.TransactionHistory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Folder
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.BackupReminderCard
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateCard
import androidx.compose.ui.draw.clip
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import com.dokar.sonner.ToastType
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toDp
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun ChatDrawerContent(
    navController: Navigator,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    // v4.8.110: 抽屉收起回调 (小屏 Modal 传入; 大屏常驻抽屉为 null 且无需收起)
    onRequestCloseDrawer: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val isPlayStore = rememberIsPlayStoreVersion()

    val activity = context as ComponentActivity
    val drawerVm: ChatDrawerVM = koinViewModel(viewModelStoreOwner = activity)

    val conversations = drawerVm.conversations.collectAsLazyPagingItems()
    val folders by drawerVm.folders.collectAsStateWithLifecycle()
    val selectedFolderId by drawerVm.selectedFolderId.collectAsStateWithLifecycle()
    // v4.8.58 (用户定版): 项目包智能推荐排序 — 本次选中(缓存) → 最近 3 次去重 →
    // 3 天频次 → 默认序; 数据源 = 持久化点击统计 (过期真删见 LaunchedEffect)
    val packClickStats by drawerVm.packClickStatsJson.collectAsStateWithLifecycle()
    // v4.8.62: 项目包任务绿点数据 (含正在执行对话的项目包)
    val packsWithRunning by drawerVm.packsWithRunning.collectAsStateWithLifecycle()
    val rankedFolders = remember(folders, selectedFolderId, packClickStats) {
        rankProjectPacks(folders, selectedFolderId, packClickStats)
    }
    // v4.8.58/63: 过期统计真删 — 目录变更时写回 (无变化不写); 剪枝内部以
    // 全助手全量目录为存在性全集 (v4.8.63 修复跨助手误删)
    LaunchedEffect(folders) {
        drawerVm.prunePackStats()
    }
    val conversationListState = rememberLazyListState(
        initialFirstVisibleItemIndex = drawerVm.scrollIndex,
        initialFirstVisibleItemScrollOffset = drawerVm.scrollOffset,
    )

    LaunchedEffect(conversationListState) {
        snapshotFlow {
            conversationListState.firstVisibleItemIndex to
                conversationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collectLatest { (index, offset) ->
                drawerVm.saveScrollPosition(index, offset)
            }
    }

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    // 移动对话状态
    var showMoveToAssistantSheet by remember { mutableStateOf(false) }
    var conversationToMove by remember { mutableStateOf<Conversation?>(null) }
    val bottomSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)

    // 文件夹相关状态
    var showMoveToFolderSheet by remember { mutableStateOf(false) }
    var conversationToMoveFolder by remember { mutableStateOf<Conversation?>(null) }
    val folderSheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var folderToRename by remember { mutableStateOf<Folder?>(null) }
    var folderToDelete by remember { mutableStateOf<Folder?>(null) }
    var folderToExtract by remember { mutableStateOf<Folder?>(null) }
    // 4.8.24 项目包: 折叠状态 (启动默认折叠), 设置弹窗, CWD 选择
    var packBarExpanded by remember { mutableStateOf(false) }
    // v4.8.62: 进入助手落地 — 项目包选择(true)/任务包直达(false) 应用一次
    val landingExpand by drawerVm.landingExpand.collectAsStateWithLifecycle()
    LaunchedEffect(landingExpand) {
        when (landingExpand) {
            true -> packBarExpanded = true
            false -> packBarExpanded = false
            null -> {}
        }
    }
    var showPackSettingsDialog by remember { mutableStateOf(false) }
    var createPackCwd by remember { mutableStateOf<String?>(null) }
    var cwdPickerForNew by remember { mutableStateOf(false) }
    var cwdPickerForFolder by remember { mutableStateOf<Folder?>(null) }

    // Menu popup 状态

    // v4.8.66 (CS 安卓端移植): 抽屉宽度 = min(400dp, 屏宽-64dp) — 右侧留一条
    // 可点击关闭的对话露边条 (front 型覆盖滑动; 对齐 CS appSidebar 参数 400/64)。
    val drawerScreenWidth = LocalConfiguration.current.screenWidthDp.dp
    val drawerSheetWidth = minOf(400.dp, drawerScreenWidth - 64.dp)
    ModalDrawerSheet(
        modifier = Modifier.width(drawerSheetWidth)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (settings.displaySetting.showUpdates && !isPlayStore) {
                UpdateCard(vm)
            }

            BackupReminderCard(
                settings = settings,
                onClick = { navController.navigate(Screen.Backup) },
            )

            // 用户头像和昵称自定义区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                        )

                        Icon(
                            imageVector = HugeIcons.PencilEdit01,
                            contentDescription = "Edit",
                            modifier = Modifier
                                .onClick {
                                    nicknameEditState.open(settings.displaySetting.userNickname)
                                }
                                .size(LocalTextStyle.current.fontSize.toDp())
                        )
                    }
                    Greeting(
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            var showCleanupDialog by remember { mutableStateOf(false) }
            DrawerActions(
                navController = navController,
                onCleanupClick = { showCleanupDialog = true },
            )
            if (showCleanupDialog) {
                CleanupChatDialog(
                    onCleanup = { cutoffEpochMs ->
                        showCleanupDialog = false
                        drawerVm.cleanupConversations(cutoffEpochMs) { removed, skipped ->
                            toaster.show(
                                if (skipped > 0) "已清理 $removed 个对话，跳过 $skipped 个（置顶或含收藏）"
                                else "已清理 $removed 个对话"
                            )
                        }
                    },
                    onDismiss = { showCleanupDialog = false },
                )
            }

            ProjectPackBar(
                folders = folders,
                selectedFolderId = selectedFolderId,
                expanded = packBarExpanded,
                onToggleExpand = { packBarExpanded = !packBarExpanded },
                onCreate = {
                    createPackCwd = null
                    showCreateFolderDialog = true
                },
                onSettings = { showPackSettingsDialog = true },
            )

            // v4.8.58 (用户定版): 展开态 — 对话记录区替换为竖向项目包列表 (智能排序,
            // UI 对齐对话行); 折叠态 — 正常对话记录。选中项目包后自动折叠返回。
            if (packBarExpanded) {
                ProjectPackList(
                    folders = rankedFolders,
                    selectedFolderId = selectedFolderId,
                    packsWithRunning = packsWithRunning,
                    onSelect = { id ->
                        drawerVm.selectFolder(id)
                        packBarExpanded = false
                    },
                    onRename = { folderToRename = it },
                    onDelete = { folderToDelete = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                listState = conversationListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    navigateToChatPage(navController, it.id)
                },
                onRegenerateTitle = {
                    vm.generateTitle(it, true)
                },
                onDelete = {
                    scope.launch {
                        vm.deleteConversation(it).join()
                        conversations.refresh()
                        if (it.id == current.id) {
                            navigateToChatPage(navController, folderId = selectedFolderId)
                        }
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onMoveToAssistant = {
                    conversationToMove = it
                    showMoveToAssistantSheet = true
                },
                onMoveToFolder = {
                    conversationToMoveFolder = it
                    showMoveToFolderSheet = true
                }
            )
            }

            // 助手选择器
            AssistantPicker(
                settings = settings,
                onUpdateSettings = {
                    val updateJob = vm.updateSettings(it)
                    scope.launch {
                        updateJob.join()
                        // v4.8.111 (用户定版·终版, 二次强调): 切换助手 = 只切助手 —
                        // 不做任何导航。停留在他/她打开抽屉时的页面与会话
                        // (对话页同样不跳到新助手的会话; 旧实现"跳默认对话页"已被用户明确否决)。
                        // 仅收起小屏 Modal 抽屉; 大屏常驻抽屉无此回调、无需动作。
                        onRequestCloseDrawer?.invoke()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                onClickSetting = {
                    val currentAssistantId = settings.assistantId
                    navController.navigate(Screen.AssistantDetail(id = currentAssistantId.toString()))
                }
            )

            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {

                // v3.8.22: 删除"助手"入口 — 用量/收藏/热力顺位替补, 设置不动
                // v3.8.0: 交互选项 (翻译/图像生成) → 用量查询
                DrawerAction(
                    icon = {
                        Icon(HugeIcons.Clock02, null)
                    },
                    label = {
                        Text("用量查询")
                    },
                    onClick = {
                        navController.navigate(Screen.Usage)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.InLove, stringResource(R.string.favorite_page_title))
                    },
                    label = {
                        Text(stringResource(R.string.favorite_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Favorite)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.ChartColumn, "统计数据")
                    },
                    label = {
                        Text("统计数据")
                    },
                    onClick = {
                        navController.navigate(Screen.Stats)
                    },
                )

                Spacer(Modifier.weight(1f))

                DrawerAction(
                    icon = {
                        Icon(HugeIcons.Settings03, null)
                    },
                    label = { Text(stringResource(R.string.settings)) },
                    onClick = {
                        navController.navigate(Screen.Setting)
                    },
                )
            }
        }
    }

    // 昵称编辑对话框
    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到文件夹 Bottom Sheet
    if (showMoveToFolderSheet) {
        val doMove: (Uuid?) -> Unit = { folderId ->
            conversationToMoveFolder?.let { conversation ->
                drawerVm.moveConversationToFolder(conversation.id, folderId)
                scope.launch {
                    folderSheetState.hide()
                    showMoveToFolderSheet = false
                    conversationToMoveFolder = null
                    conversations.refresh()
                }
            }
        }
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToFolderSheet = false
                conversationToMoveFolder = null
            },
            sheetState = folderSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_folder),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 移出文件夹（未归类）
                Surface(
                    onClick = { doMove(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = if (conversationToMoveFolder?.folderId == null) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(HugeIcons.Folder01, null)
                        Text(
                            text = stringResource(R.string.chat_page_remove_from_folder),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders) { folder ->
                        val isCurrent = folder.id == conversationToMoveFolder?.folderId
                        Surface(
                            onClick = { doMove(folder.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            // 4.8.28: 选中态统一 secondaryContainer (深色可辨)
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            tonalElevation = if (isCurrent) 2.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(HugeIcons.Folder01, null)
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else androidx.compose.ui.graphics.Color.Unspecified,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建文件夹对话框
    // 4.8.24 新建项目包 (名称 + CWD 锚定)
    if (showCreateFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = { Text("新建项目包") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_page_folder_name)) }
                    )
                    Surface(
                        onClick = { cwdPickerForNew = true },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(HugeIcons.Folder01, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = createPackCwd ?: "未设置 CWD（使用助手默认目录）",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.createFolder(name, createPackCwd)
                        showCreateFolderDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 4.8.24 项目包设置弹窗 (管理: CWD / 重命名 / 删除)
    if (showPackSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showPackSettingsDialog = false },
            title = { Text("项目包设置") },
            text = {
                if (folders.isEmpty()) {
                    Text("暂无项目包。项目包让同一助手执行不同方向的命令（独立 CWD 锚定），点击 + 新建。")
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        folders.forEach { folder ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(folder.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            folder.cwd ?: "未设置 CWD",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { cwdPickerForFolder = folder }) {
                                        Icon(
                                            HugeIcons.Folder01,
                                            contentDescription = "设置 CWD",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(onClick = {
                                        showPackSettingsDialog = false
                                        folderToRename = folder
                                    }) {
                                        Icon(
                                            HugeIcons.PencilEdit01,
                                            contentDescription = "重命名",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(onClick = {
                                        showPackSettingsDialog = false
                                        folderToExtract = folder
                                    }) {
                                        Icon(
                                            HugeIcons.Share03,
                                            contentDescription = "移出为助手",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    IconButton(onClick = {
                                        showPackSettingsDialog = false
                                        folderToDelete = folder
                                    }) {
                                        Icon(
                                            HugeIcons.Delete01,
                                            contentDescription = "删除",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPackSettingsDialog = false }) { Text("完成") }
            },
        )
    }

    // 4.8.24 项目包 CWD 选择器 (复用工作区目录选择; 限定助手 CWD 空间内)
    val assistantWorkspaceIdForCwd = settings.getCurrentAssistant().workspaceId?.toString()
    if (assistantWorkspaceIdForCwd != null) {
        // 4.8.25: rootPath = 助手 CWD — 项目包 CWD 只能在当前助手 CWD 空间内选择
        val assistantCwdForPicker = settings.getCurrentAssistant().workspaceCwd
        if (cwdPickerForNew) {
            WorkspaceCwdPickerSheet(
                workspaceId = assistantWorkspaceIdForCwd,
                currentCwd = createPackCwd,
                rootPath = assistantCwdForPicker,
                onSelectCwd = {
                    createPackCwd = it
                    cwdPickerForNew = false
                },
                onDismiss = { cwdPickerForNew = false },
            )
        }
        cwdPickerForFolder?.let { folder ->
            WorkspaceCwdPickerSheet(
                workspaceId = assistantWorkspaceIdForCwd,
                currentCwd = folder.cwd,
                rootPath = assistantCwdForPicker,
                onSelectCwd = {
                    drawerVm.updateFolderCwd(folder.id, it)
                    cwdPickerForFolder = null
                },
                onDismiss = { cwdPickerForFolder = null },
            )
        }
    }

    // 重命名文件夹对话框
    folderToRename?.let { folder ->
        var name by remember(folder.id) { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { folderToRename = null },
            title = { Text(stringResource(R.string.chat_page_rename_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        drawerVm.renameFolder(folder.id, name)
                        folderToRename = null
                    },
                    enabled = name.isNotBlank()
                ) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToRename = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 删除文件夹确认
    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text(stringResource(R.string.chat_page_delete_folder)) },
            text = { Text(stringResource(R.string.chat_page_delete_folder_confirm, folder.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.deleteFolder(folder.id)) {
                            folderToDelete = null
                            conversations.refresh()
                        } else {
                            toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                        }
                    }
                ) { Text(stringResource(R.string.chat_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // v4.8.77: 项目包移出为助手 — 确认 (包内对话全部转移给新助手, CWD 绑定保持)
    folderToExtract?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToExtract = null },
            title = { Text("移出为助手") },
            text = {
                Text(
                    "将项目包「${folder.name}」合并为新助手：包内全部对话将转移为该助手的对话记录，" +
                        "CWD 保持「${folder.cwd ?: "默认"}」不变；原助手不再保留该项目包。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (drawerVm.extractFolderAsAssistant(folder)) {
                            folderToExtract = null
                            conversations.refresh()
                        } else {
                            toaster.show(context.getString(R.string.chat_page_delete_folder_generating), type = ToastType.Warning)
                        }
                    }
                ) { Text("移出") }
            },
            dismissButton = {
                TextButton(onClick = { folderToExtract = null }) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }

    // 移动到助手 Bottom Sheet
    if (showMoveToAssistantSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showMoveToAssistantSheet = false
                conversationToMove = null
            },
            sheetState = bottomSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_page_move_to_assistant),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(settings.assistants) { assistant ->
                        AssistantItem(
                            assistant = assistant,
                            isCurrentAssistant = assistant.id == conversationToMove?.assistantId,
                            onClick = {
                                conversationToMove?.let { conversation ->
                                    vm.moveConversationToAssistant(conversation, assistant.id)
                                    scope.launch {
                                        bottomSheetState.hide()
                                        showMoveToAssistantSheet = false
                                        conversationToMove = null
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// v3.8.15: 块状三入口 — 搜索 / 查询(历史) / 清理, 最上行为"聊天历史"标题
@Composable
private fun DrawerActions(navController: Navigator, onCleanupClick: () -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.chat_page_history),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrawerActionBlock(
                label = stringResource(R.string.chat_page_search_chats),
                icon = HugeIcons.Search01,
                onClick = { navController.navigate(Screen.MessageSearch) },
                modifier = Modifier.weight(1f),
            )
            DrawerActionBlock(
                label = "查询",
                icon = HugeIcons.TransactionHistory,
                onClick = { navController.navigate(Screen.History) },
                modifier = Modifier.weight(1f),
            )
            DrawerActionBlock(
                label = "清理",
                icon = HugeIcons.Delete01,
                onClick = onCleanupClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DrawerActionBlock(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
                label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}

/**
 * 4.8.24: 项目包栏 — 文件夹 → 项目包。
 * 折叠态: [聊天] ... [新建][设置][展开]; 展开态: [聊天] [项目包列表(可横滑)] [新建][设置][折叠]。
 * 项目包 = 同一助手执行不同方向的命令 (锚定独立 CWD); 「聊天」= 全助手权限默认空间。
 */
@Composable
private fun ProjectPackBar(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
) {
    // v4.8.58 (用户定版): 展开不再横向铺项目包图标 — 项目包列表已移入对话记录区
    // (竖向, 智能排序); 顶栏固定为 [当前选区(点击=展开/折叠)] + [新建][设置][箭头]。
    val selectedFolder = folders.find { it.id == selectedFolderId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FolderChip(
            label = selectedFolder?.name ?: stringResource(R.string.chat_page_folder_default),
            icon = if (selectedFolder != null) HugeIcons.Folder01 else null,
            selected = true,
            onClick = { onToggleExpand() },
            onLongClick = {},
        )
        Spacer(Modifier.weight(1f))
        PackBarIconButton(icon = HugeIcons.FolderAdd, onClick = onCreate)
        PackBarIconButton(icon = HugeIcons.Settings01, onClick = onSettings)
        PackBarIconButton(
            // v4.8.59 (用户定版): 展开/收起指示改为上下方向
            icon = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
            onClick = onToggleExpand,
        )
    }
}

@Composable
private fun PackBarIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    icon: ImageVector? = null,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** v4.8.58 (用户定版): 展开态竖向项目包列表 — 排列 UI 对齐对话记录行 (同款 pill
 *  圆角/内边距/选中色 secondaryContainer/长按菜单); 排序走智能推荐 (rankProjectPacks)。 */
@Composable
private fun ProjectPackList(
    folders: List<Folder>,
    selectedFolderId: Uuid?,
    packsWithRunning: Set<Uuid>,
    onSelect: (Uuid?) -> Unit,
    onRename: (Folder) -> Unit,
    onDelete: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 「聊天」入口 (未归类对话视图)
        item(key = "pack_row_chat") {
            PackRow(
                label = stringResource(R.string.chat_page_folder_default),
                icon = null,
                selected = selectedFolderId == null,
                running = false,
                onClick = { onSelect(null) },
            )
        }
        items(folders, key = { it.id.toString() }) { folder ->
            PackRow(
                label = folder.name,
                icon = HugeIcons.Folder01,
                selected = selectedFolderId == folder.id,
                running = folder.id in packsWithRunning,
                onClick = { onSelect(folder.id) },
                onRename = { onRename(folder) },
                onDelete = { onDelete(folder) },
            )
        }
    }
}

@Composable
private fun PackRow(
    label: String,
    icon: ImageVector?,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = onRename != null || onDelete != null
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(50f),
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (hasMenu) menuExpanded = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else Color.Unspecified,
                )
                Spacer(Modifier.weight(1f))
                // v4.8.62 (用户定版): 与对话行同款绿色任务标记 —
                // 包内存在正在执行任务的对话时, 行右侧亮起
                AnimatedVisibility(visible = running) {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.extendColors.green6)
                            .size(4.dp)
                            .semantics {
                                contentDescription = "Loading"
                            }
                    )
                }
            }
        }
        if (hasMenu) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                onRename?.let { cb ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_rename)) },
                        leadingIcon = { Icon(HugeIcons.PencilEdit01, null) },
                        onClick = {
                            menuExpanded = false
                            cb()
                        },
                    )
                }
                onDelete?.let { cb ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_page_delete)) },
                        leadingIcon = { Icon(HugeIcons.Delete01, null) },
                        onClick = {
                            menuExpanded = false
                            cb()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    isCurrentAssistant: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        // 4.8.28: 选中态统一 secondaryContainer (深色可辨)
        color = if (isCurrentAssistant) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        tonalElevation = if (isCurrentAssistant) 2.dp else 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UIAvatar(
                name = assistant.name,
                value = assistant.avatar,
                onUpdate = {},
                modifier = Modifier.size(40.dp),
            )
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrentAssistant) {
                    Text(
                        text = stringResource(R.string.assistant_page_current_assistant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


// v3.8.15: 清理聊天内容对话框 — 三档时间选项, 置顶/含收藏对话自动跳过
@Composable
private fun CleanupChatDialog(
    onCleanup: (cutoffEpochMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val now = System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清理聊天内容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "选择清理时间范围，置顶对话或含收藏内容的对话将自动跳过",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CleanupOption(
                    label = "清理最近 3 个月之前的聊天内容",
                    onClick = { onCleanup(now - 90L * 24 * 3600 * 1000) },
                )
                CleanupOption(
                    label = "清理最近 1 个月之前的聊天内容",
                    onClick = { onCleanup(now - 30L * 24 * 3600 * 1000) },
                )
                CleanupOption(
                    label = "清理最近 1 周之前的聊天内容",
                    onClick = { onCleanup(now - 7L * 24 * 3600 * 1000) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun CleanupOption(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}