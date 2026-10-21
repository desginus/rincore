package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import androidx.annotation.StringRes
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Settings03
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.MemoryConsolidationStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import me.rerere.rikkahub.data.model.groupByDirectory
import me.rerere.rikkahub.data.model.memoryTitleOf
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.memory.resultText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import me.rerere.hugeicons.stroke.Book02
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// 记忆文件放第一页：从侧边栏的整理入口进来时能直接看到整理状态
private enum class MemoryTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    FILES(HugeIcons.Brain02, R.string.assistant_page_manage_memory_title),
    // 5.0.0 回贴：我方记忆条目（助手级 + 单对话记忆）
    MEMORIES(HugeIcons.Book02, R.string.assistant_page_memory),
    SETTINGS(HugeIcons.Settings03, R.string.settings);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

/** v5.0.25：记忆页大分区 */
private enum class MemorySection { MEMORY, SETTINGS }

/** v5.0.25：记忆范围（层级） */
private enum class MemoryScope { ASSISTANT, FOLDER, CONVERSATION }

/** v5.0.25：记忆来源（主动写入 / 做梦整理） */
private enum class MemoryCategory { ACTIVE, DREAM }

/** 大卡：半屏宽、方块圆角、选中态高亮 */
@Composable
private fun MemorySectionCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun AssistantMemoryPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val consolidation by vm.memoryConsolidation.collectAsStateWithLifecycle()
    // 5.0.0 回贴：我方记忆条目（助手级）+ 单对话记忆
    val assistantMemories by vm.assistantMemories.collectAsStateWithLifecycle()
    val conversationMemories by vm.conversationMemories.collectAsStateWithLifecycle()
    val folderMemories by vm.folderMemories.collectAsStateWithLifecycle(initialValue = emptyList())
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // v5.0.25 用户定案：记忆页分「记忆 / 设置」两个大卡（横向各半屏）
    var memorySection by remember { mutableStateOf(MemorySection.MEMORY) }
    var memoryScope by remember { mutableStateOf(MemoryScope.ASSISTANT) }
    var memoryCategory by remember { mutableStateOf(MemoryCategory.ACTIVE) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_memory))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    if (memorySection == MemorySection.MEMORY && memoryCategory == MemoryCategory.DREAM) {
                        IconButton(onClick = { navController.navigate(Screen.MemoryFile(vm.memoryId, null)) }) {
                            Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.add))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // v5.0.25：两个大卡（左记忆 / 右设置）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MemorySectionCard(
                    label = stringResource(R.string.assistant_page_memory),
                    icon = HugeIcons.Book02,
                    selected = memorySection == MemorySection.MEMORY,
                    modifier = Modifier.weight(1f),
                ) { memorySection = MemorySection.MEMORY }
                MemorySectionCard(
                    label = stringResource(R.string.settings),
                    icon = HugeIcons.Settings03,
                    selected = memorySection == MemorySection.SETTINGS,
                    modifier = Modifier.weight(1f),
                ) { memorySection = MemorySection.SETTINGS }
            }

            if (memorySection == MemorySection.MEMORY) {
                // 范围选择（助手 / 项目包 / 单对话）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = memoryScope == MemoryScope.ASSISTANT,
                        onClick = { memoryScope = MemoryScope.ASSISTANT },
                        label = { Text("助手记忆") },
                    )
                    FilterChip(
                        selected = memoryScope == MemoryScope.FOLDER,
                        onClick = { memoryScope = MemoryScope.FOLDER },
                        label = { Text("项目包记忆") },
                    )
                    FilterChip(
                        selected = memoryScope == MemoryScope.CONVERSATION,
                        onClick = { memoryScope = MemoryScope.CONVERSATION },
                        label = { Text("单对话记忆") },
                    )
                }
                // 来源分类（主动写入 / 做梦整理）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = memoryCategory == MemoryCategory.ACTIVE,
                        onClick = { memoryCategory = MemoryCategory.ACTIVE },
                        label = { Text("主动写入") },
                    )
                    FilterChip(
                        selected = memoryCategory == MemoryCategory.DREAM,
                        onClick = { memoryCategory = MemoryCategory.DREAM },
                        label = { Text("做梦整理") },
                    )
                }

                if (memoryCategory == MemoryCategory.DREAM) {
                    // 做梦整理 = 文件式记忆库（上游记忆 2.0）
                    MemoryFilesTab(
                        memories = memories,
                        consolidation = consolidation.takeIf { assistant.enableMemory },
                        onOrganizeMemory = { vm.organizeMemory() },
                        onOpenMemory = { path -> navController.navigate(Screen.MemoryFile(vm.memoryId, path)) },
                        onDeleteMemory = { vm.deleteMemory(it) },
                    )
                } else {
                    // 主动写入 = 条目式记忆，按范围过滤
                    // v5.0.26：主动写入 = manual/user 来源的条目（dream 来源归「做梦整理」分类）
                    val scopedMemories = when (memoryScope) {
                        MemoryScope.ASSISTANT -> assistantMemories
                        MemoryScope.FOLDER -> folderMemories
                        MemoryScope.CONVERSATION -> emptyList()
                    }.filter { it.source != "dream" }
                    MemoryMemoriesTab(
                        memories = scopedMemories,
                        conversationMemories = if (memoryScope == MemoryScope.CONVERSATION) conversationMemories else emptyList(),
                        loadTitle = { vm.conversationTitle(it) },
                        loadRecentConversations = { vm.recentConversations() },
                        onAddMemory = { vm.addMemory(it) },
                        onUpdateMemory = { vm.updateMemory(it) },
                        onDeleteMemory = { vm.deleteMemory(it) },
                    )
                }
            } else {
                MemorySettingsTab(
                    assistant = assistant,
                    onUpdateAssistant = { vm.update(it) },
                )
            }
        }
    }
}

@Composable
private fun MemorySettingsTab(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
) {
    var showTimeReminderIntervalDialog by remember(assistant.id) { mutableStateOf(false) }
    var timeReminderIntervalInput by remember(assistant.id) { mutableStateOf("") }

    if (showTimeReminderIntervalDialog) {
        val interval = timeReminderIntervalInput.toIntOrNull()?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { showTimeReminderIntervalDialog = false },
            title = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
            text = {
                TextField(
                    value = timeReminderIntervalInput,
                    onValueChange = { timeReminderIntervalInput = it },
                    label = { Text(stringResource(R.string.assistant_page_time_reminder_interval_label)) },
                    supportingText = { Text(stringResource(R.string.assistant_page_time_reminder_interval_hint)) },
                    isError = interval == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = interval != null,
                    onClick = {
                        interval?.let {
                            onUpdateAssistant(assistant.copy(timeReminderIntervalMinutes = it))
                        }
                        showTimeReminderIntervalDialog = false
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimeReminderIntervalDialog = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            switchItem(
                checked = assistant.enableMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableMemory = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_memory)) },
            )
            switchItem(
                checked = assistant.useGlobalMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            useGlobalMemory = it
                        )
                    )
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text(stringResource(R.string.assistant_page_global_memory_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_global_memory)) },
            )
            // 5.0.0 回贴：我方单对话记忆开关
            switchItem(
                checked = assistant.conversationScopedMemory,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(conversationScopedMemory = it)
                    )
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text("开启后，新记忆只挂载到当前对话：仅在该对话内可见，对话删除即销毁。") },
                headlineContent = { Text("单对话记忆") },
            )
            // v5.0.54 用户定案：**新建记忆归属范围** — 三个锚点（助手 / 项目包 / 当前对话）
            // 决定模型主动写入的记忆挂到哪个区块：助手级全助手可见；项目包内仅该包可见；对话级仅该对话可见。
            item(
                headlineContent = { Text("新建记忆归属") },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "模型主动写入（含做梦整理）的新记忆将挂到所选区块",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                0 to "助手级",
                                1 to "项目包内",
                                2 to "当前对话",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = assistant.memoryWriteScope == value,
                                    onClick = { onUpdateAssistant(assistant.copy(memoryWriteScope = value)) },
                                    label = { Text(label) },
                                    enabled = assistant.enableMemory,
                                )
                            }
                        }
                    }
                },
            )
            // v5.0.16 用户定案：做梦（记忆整理）独立开关 + 范围限定
            switchItem(
                checked = assistant.memoryDreamEnabled,
                onCheckedChange = {
                    onUpdateAssistant(assistant.copy(memoryDreamEnabled = it))
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text("允许模型在对话结束后通过做梦主动整理并记录记忆") },
                headlineContent = { Text("做梦主动记录") },
            )
            item(
                headlineContent = { Text("做梦范围") },
                supportingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            0 to "整个助手",
                            1 to "项目包内",
                            2 to "当前对话",
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = assistant.memoryDreamScope == value,
                                onClick = { onUpdateAssistant(assistant.copy(memoryDreamScope = value)) },
                                label = { Text(label) },
                                enabled = assistant.enableMemory,
                            )
                        }
                    }
                },
            )
            switchItem(
                checked = assistant.enableRecentChatsReference,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableRecentChatsReference = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_recent_chats_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_recent_chats)) },
            )
        }

        CardGroup {
            switchItem(
                checked = assistant.enableTimeReminder,
                onCheckedChange = {
                    onUpdateAssistant(
                        assistant.copy(
                            enableTimeReminder = it
                        )
                    )
                },
                supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_desc)) },
                headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder)) },
            )
            if (assistant.enableTimeReminder) {
                item(
                    headlineContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval)) },
                    supportingContent = { Text(stringResource(R.string.assistant_page_time_reminder_interval_desc)) },
                    trailingContent = {
                        Text(
                            text = stringResource(
                                R.string.assistant_page_time_reminder_interval_value,
                                assistant.timeReminderIntervalMinutes
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        timeReminderIntervalInput = assistant.timeReminderIntervalMinutes.toString()
                        showTimeReminderIntervalDialog = true
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryFilesTab(
    memories: List<MemoryFile>,
    // 为 null 时不显示整理入口
    consolidation: MemoryConsolidationStatus?,
    onOrganizeMemory: () -> Unit,
    onOpenMemory: (path: String) -> Unit,
    onDeleteMemory: (MemoryFile) -> Unit,
) {
    var pendingDeleteMemory by remember { mutableStateOf<MemoryFile?>(null) }
    val memoryGroups = remember(memories) { memories.groupByDirectory() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (consolidation != null) {
            MemoryConsolidationSection(
                status = consolidation,
                onOrganize = onOrganizeMemory,
            )
        }

        memoryGroups.fastForEach { (directory, files) ->
            CardGroup(
                title = { Text(memorySectionTitle(directory)) },
            ) {
                files.fastForEach { memory ->
                    item(
                        onClick = { onOpenMemory(memory.path) },
                        trailingContent = {
                            ItemActionMenu(
                                actions = listOf(
                                    ItemAction(
                                        text = stringResource(R.string.delete),
                                        icon = HugeIcons.Delete01,
                                        destructive = true,
                                        onClick = { pendingDeleteMemory = memory },
                                    ),
                                )
                            )
                        },
                        headlineContent = {
                            Text(
                                text = memory.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (memory.description.isNotEmpty()) {
                                    Text(
                                        text = memory.description,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.assistant_page_memory_updated_at,
                                        relativeTimeOf(memory.updatedAt),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.path.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

/** 后台整理的状态和手动入口 */
@Composable
private fun MemoryConsolidationSection(
    status: MemoryConsolidationStatus,
    onOrganize: () -> Unit,
) {
    val lastRun = status.lastRun
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.memory_dream_title)) },
            supportingContent = { Text(memoryConsolidationSummary(status)) },
            trailingContent = {
                if (status.running) {
                    ContainedLoadingIndicator(modifier = Modifier.size(32.dp))
                } else {
                    FilledTonalButton(onClick = onOrganize) {
                        Text(
                            stringResource(
                                if (lastRun?.error != null) {
                                    R.string.assistant_page_memory_dream_retry
                                } else {
                                    R.string.assistant_page_memory_dream_now
                                }
                            )
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun memoryConsolidationSummary(status: MemoryConsolidationStatus): String {
    val lastRun = status.lastRun
    return when {
        status.running -> stringResource(R.string.memory_dreaming)
        lastRun?.error != null -> lastRun.resultText()
        else -> listOfNotNull(
            status.pendingTurns.takeIf { it > 0 }?.let {
                stringResource(R.string.assistant_page_memory_dream_pending, it)
            },
            lastRun?.let { "${relativeTimeOf(it.finishedAt)}: ${it.resultText()} (${durationTextOf(it.duration)})" },
        ).joinToString("\n").ifEmpty { stringResource(R.string.assistant_page_memory_dream_hint) }
    }
}

// 按整秒显示成 "42s"、"1m 5s"
private fun durationTextOf(duration: Duration): String = duration.inWholeSeconds.coerceAtLeast(1).seconds.toString()

// 根目录下是 profile 和 preferences，都是关于用户本人的；记忆格式约定的几个目录有译名，其余的从目录名推
@Composable
private fun memorySectionTitle(directory: String): String = when (directory) {
    "/" -> stringResource(R.string.assistant_page_memory_section_you)
    "/topics" -> stringResource(R.string.assistant_page_memory_section_topics)
    "/areas" -> stringResource(R.string.assistant_page_memory_section_areas)
    "/people" -> stringResource(R.string.assistant_page_memory_section_people)
    else -> memoryTitleOf(directory.removePrefix("/"))
}

// 一周内显示"41 分钟前"这样的相对时间，更早的显示日期
private fun relativeTimeOf(time: Long): String = DateUtils.getRelativeTimeSpanString(
    time,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_MONTH,
).toString()

// ─────────────────────────────────────────────────────────────────────────────
// 5.0.0 回贴：我方记忆条目 Tab —— 助手级记忆 + 单对话记忆（v4.8.92 语义完整保留）
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun MemoryMemoriesTab(
    memories: List<AssistantMemory>,
    conversationMemories: List<AssistantMemory>,
    loadTitle: suspend (String) -> String?,
    loadRecentConversations: suspend () -> List<Conversation>,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0L) onAddMemory(it) else onUpdateMemory(it)
    }
    var pendingDeleteMemory by remember { mutableStateOf<AssistantMemory?>(null) }

    // v4.8.92: 记忆范围管理 —— [整个助手] / [单对话记忆]
    var manageConversationScope by remember { mutableStateOf(false) }
    var showConversationPicker by remember { mutableStateOf(false) }
    // 对话标题缓存（来源标签 / 分组头；已删除对话 → 显示"已删除的对话"）
    val memoryTitles by produceState<Map<String, String>>(
        initialValue = emptyMap(), memories, conversationMemories,
    ) {
        val ids = (
            memories.mapNotNull { it.sourceConversationId } +
                conversationMemories.mapNotNull { it.conversationId }
            ).toSet()
        val map = HashMap<String, String>()
        ids.forEach { cid -> loadTitle(cid)?.let { t -> map[cid] = t } }
        value = map
    }

    // 记忆编辑/新建对话框
    memoryDialogState.EditStateContent { memory, update ->
        AlertDialog(
            onDismissRequest = { memoryDialogState.dismiss() },
            title = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = { update(memory.copy(content = it)) },
                    label = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
                    minLines = 2,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(onClick = { memoryDialogState.confirm() }) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { memoryDialogState.dismiss() }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    // v4.8.92: 新建单对话记忆 → 先选归属对话
    if (showConversationPicker) {
        val recentConversations by produceState<List<Conversation>>(initialValue = emptyList()) {
            value = runCatching { loadRecentConversations() }.getOrDefault(emptyList())
        }
        AlertDialog(
            onDismissRequest = { showConversationPicker = false },
            title = { Text("选择归属对话") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    recentConversations.forEach { conv ->
                        TextButton(
                            onClick = {
                                showConversationPicker = false
                                memoryDialogState.open(
                                    AssistantMemory(0, "", conversationId = conv.id.toString())
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = conv.title.ifBlank { "新对话" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (recentConversations.isEmpty()) {
                        Text("没有可用对话", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showConversationPicker = false }) { Text("取消") }
            },
        )
    }

    RikkaConfirmDialog(
        show = pendingDeleteMemory != null,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.confirm),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            pendingDeleteMemory?.let(onDeleteMemory)
            pendingDeleteMemory = null
        },
        onDismiss = { pendingDeleteMemory = null },
        text = {
            Text(
                text = pendingDeleteMemory?.content.orEmpty(),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.assistant_page_manage_memory_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            IconButton(
                onClick = {
                    if (manageConversationScope) {
                        // 单对话记忆：先选归属对话
                        showConversationPicker = true
                    } else {
                        memoryDialogState.open(AssistantMemory(0, ""))
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(imageVector = HugeIcons.Add01, contentDescription = null)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !manageConversationScope,
                onClick = { manageConversationScope = false },
                label = { Text("整个助手") },
            )
            FilterChip(
                selected = manageConversationScope,
                onClick = { manageConversationScope = true },
                label = { Text("单对话记忆") },
            )
        }

        if (!manageConversationScope) {
            memories.fastForEach { memory ->
                key(memory.id) {
                    MemoryItem(
                        memory = memory,
                        tag = memory.sourceConversationId?.let { sid ->
                            memoryTitles[sid]?.let { "来自「$it」" } ?: "来自已删除的对话"
                        },
                        onEditMemory = { memoryDialogState.open(it) },
                        onDeleteMemory = { pendingDeleteMemory = it },
                    )
                }
            }
        } else {
            // 单对话记忆：按归属对话分组（组头 = 对话标题；已删除 → 明示）
            conversationMemories.groupBy { it.conversationId }.forEach { (cid, list) ->
                val title = cid?.let { memoryTitles[it] } ?: "已删除的对话"
                Text(
                    text = "$title（${list.size}）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                list.fastForEach { memory ->
                    key(memory.id) {
                        MemoryItem(
                            memory = memory,
                            tag = "仅在该对话内可见",
                            onEditMemory = { memoryDialogState.open(it) },
                            onDeleteMemory = { pendingDeleteMemory = it },
                        )
                    }
                }
            }
            if (conversationMemories.isEmpty()) {
                Text(
                    text = "还没有单对话记忆。点右上角 + 选择归属对话后创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    tag: String? = null,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
) {
    Card(
        onClick = { onEditMemory(memory) },
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = memory.content,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                // v4.8.92: 小标签（来源对话 / 单对话可见性说明）
                tag?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                    )
                }
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { onDeleteMemory(memory) },
                    ),
                )
            )
        }
    }
}
