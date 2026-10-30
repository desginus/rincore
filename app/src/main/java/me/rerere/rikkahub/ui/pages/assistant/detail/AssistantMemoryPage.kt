package me.rerere.rikkahub.ui.pages.assistant.detail

import android.text.format.DateUtils
import androidx.annotation.StringRes
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiMagic
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

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
/**
 * v5.3.4 ★用户实测「模型在对话里创建的新记忆，用户根本没有办法从记忆页浏览和查找」：
 * 模型用的是**文件式**记忆工具（memory_read/write/str_replace/append/delete/list → MemoryFile 表），
 * 而记忆页只渲染**条目式**（AssistantMemory）。`MemoryFilesTab` 虽已实现 600+ 行，
 * **全库没有任何调用点 = 死代码** → 模型写的记忆在 UI 上完全不可见。
 * 这里新增 FILES 档专门浏览/搜索文件式记忆库（模型创建的记忆就在这）。
 */
private enum class MemoryCategory { ACTIVE, DREAM, FILES }

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
fun AssistantMemoryPage(id: String, conversationId: String? = null) {
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
    val folderMemories by vm.folderMemories.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    // v5.0.81：主动做梦/toast 反馈（点击即刻提示，运行中由底部状态卡承接）
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
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
                    // v5.0.80 主动做梦（用户指令："新增一个主动做梦记忆，记忆范围=当该功能被
                    // 触发的时候，开始记忆当前对话的内容"）：从工具箱进入时带当前对话 id，
                    // 点按钮立即对该对话做一次全量做梦整理（产物落为做梦记忆）。
                    if (memorySection == MemorySection.MEMORY && conversationId != null) {
                        val dreaming = vm.memoryConsolidation.collectAsStateWithLifecycle().value.running
                        // v5.0.81 点击即刻 toast（stringResource 须在组合态先取出，onClick 里不可调）
                        val toastStart = stringResource(R.string.memory_dreaming)
                        val toastDisabled = stringResource(R.string.memory_dream_require_enabled)
                        IconButton(
                            onClick = {
                                if (vm.triggerDreamConsolidation(conversationId)) {
                                    toaster.show(toastStart, type = com.dokar.sonner.ToastType.Info)
                                } else {
                                    toaster.show(toastDisabled, type = com.dokar.sonner.ToastType.Error)
                                }
                            },
                            enabled = !dreaming,
                        ) {
                            Icon(
                                imageVector = HugeIcons.AiMagic,
                                contentDescription = stringResource(R.string.assistant_page_memory_dream_now),
                            )
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
                    // v5.3.4：模型用 memory_write 创建的记忆落在这里（此前完全不可见）
                    FilterChip(
                        selected = memoryCategory == MemoryCategory.FILES,
                        onClick = { memoryCategory = MemoryCategory.FILES },
                        label = { Text("文件库") },
                    )
                }

                // v5.0.67 ★用户定案的二维语义（来源 × 存储）：
                //   来源标签：主动写入 = source != "dream"（模型调工具/用户手加）
                //             做梦整理 = source == "dream"（自动整理产物，Consolidator 同步落库）
                //   存储标签：助手记忆 = folderId/conversationId 都为空的条目
                //             项目包记忆 = 该包 folderId 层 ∪ 同包所有对话的「仅对话内可见」记忆
                //             单对话记忆 = conversationId 层的条目
                // 两个维度**叉乘**，任何组合都能查到对应内容。
                // v5.1.14 ★统一判据（唯一实现 = MemoryVisibility）：
                // 来源档位、存储层、管理页可见性全部从这里取，UI 不再各写一份。
                val matchSource: (me.rerere.rikkahub.data.model.AssistantMemory) -> Boolean = { m ->
                    me.rerere.rikkahub.data.repository.MemoryVisibility.matchesSource(
                        m, dream = memoryCategory == MemoryCategory.DREAM,
                    )
                }
                val folderConvIds by vm.folderConversationIds.collectAsStateWithLifecycle()
                // v5.0.87 ★可见性统一（用户定案："只要模型能看到，我这里也得看到"）：
                // 旧实现三档各只显示**单一存储层**（助手档只列助手级、包档只列 folder 层），
                // 而模型注入链是 base + folder + 包内对话 + 本对话 的**并集** → 出现"模型看得到、
                // 管理页看不到"。现在三档都改为「模型在该上下文实际可见的全部」：
                //   整个助手 = 助手级 + 全部包层（对话层在下方按对话分组列出）
                //   项目包   = 助手级 + 包层 + 包内对话层
                //   单对话   = 助手级 + 包层 + 本对话层
                // v5.0.87：与注入链的挂载分离保持一致（1=仅助手级 / 2=项目包内 / 3=仅当前对话）
                // v5.1.13 ★管理页不再受"挂载层级"过滤（用户报"设置到项目包后在用户视角消失"）：
                // mountAllowed 是**注入**判据（当前上下文模型能看到什么），把它用在管理页会导致
                // 「仅助手级 / 仅当前对话」档位下的项目包记忆**在管理页里彻底不可见**，
                // 用户既看不到也改不了 → 只能认为"消失了"。管理页必须始终显示该助手的全部记忆。
                //
                // 三档真正分流（v5.0.87 的"都显示同一批"会让人觉得重复）：
                //   助手记忆   = 助手级 + 全部项目包层（全集，保证任何一条都能被管理）
                //   项目包记忆 = 只显示项目包层
                //   单对话记忆 = 助手/包层清空，只显示下方按对话分组的对话层
                val scopedMemories = when (memoryScope) {
                    MemoryScope.ASSISTANT -> assistantMemories + folderMemories
                    MemoryScope.FOLDER -> folderMemories
                    MemoryScope.CONVERSATION -> emptyList()
                }
                    .filter { me.rerere.rikkahub.data.repository.MemoryVisibility.visibleForManagement(it) }
                    .filter(matchSource)
                    .distinctBy { it.id }
                val scopedConversationMemories = when (memoryScope) {
                    MemoryScope.ASSISTANT -> conversationMemories.filter(matchSource)
                    MemoryScope.FOLDER -> conversationMemories
                        .filter { it.conversationId != null && it.conversationId in folderConvIds }
                        .filter(matchSource)
                    // 单对话档：没有对话上下文（从助手设置进入）时显示该助手全部对话层，
                    // 否则只显示当前这条对话 —— 两种情况下都不再按 mount 层级隐藏。
                    MemoryScope.CONVERSATION -> conversationMemories
                        .filter { conversationId == null || it.conversationId == conversationId }
                        .filter(matchSource)
                }.distinctBy { it.id }
                // v5.0.81 ★做梦状态卡（用户："点击做梦之后会在上面有提示、抽屉页也会出现提示，
                // 你这个和其他的没对上"）—— 复用与设置 tab「立即整理」**同一个组件**，
                // 状态/转圈/最近一次结果完全一致；onOrganize 分流：带对话 id = 主动做梦
                //（只整理当前对话），否则 = 全库立即整理（与设置 tab 同语义）。
                if (conversationId != null) {
                    // stringResource 只能在组合态取（onClick 内不可调；ToastType 全限定免 import）
                    val noMemoryToast = stringResource(R.string.memory_dream_require_enabled)
                    MemoryConsolidationSection(
                        status = consolidation,
                        onOrganize = {
                            if (!vm.triggerDreamConsolidation(conversationId)) {
                                toaster.show(noMemoryToast, type = com.dokar.sonner.ToastType.Error)
                            }
                        },
                    )
                }
                if (memoryCategory == MemoryCategory.FILES) {
                    // v5.3.4：文件式记忆库（模型 memory_write/memory_append 的产物）
                    MemoryFilesTab(
                        memories = memories,
                        consolidation = null,
                        onOrganizeMemory = {},
                        onOpenMemory = { path -> navController.navigate(Screen.MemoryFile(vm.memoryId, path)) },
                        onDeleteMemory = { vm.deleteMemory(it) },
                    )
                } else {
                    MemoryMemoriesTab(
                        memories = scopedMemories,
                        conversationMemories = scopedConversationMemories,
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
            // v5.0.57 用户定案：上面两个旧开关（整个助手 / 单对话记忆）已删除——
            // 语义被下方「新建记忆归属」三锚点完整覆盖，留着只会互相打架。
            // v5.0.63 ★回滚：v5.0.62 误删了**设置 tab** 的这两块（用户警告"你删除的是设置里的，
            // 而不是记忆页的"）。设置里的归属/范围选择是功能的唯一入口，必须保留；
            // 真正该删的「管理记忆」区块在记忆 tab（见 MemoryMemoriesTab）。
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
            // （v5.0.62 曾误删上面两块，v5.0.63 已回滚 —— 记录在此避免重犯）
            // v5.0.86 ★挂载分离（用户定案）：主动写入 / 做梦记录各自的挂载区块
            item(
                headlineContent = { Text("主动写入记忆的挂载") },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "模型主动写入（含手动添加）的记忆注入到哪些区块",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                1 to "仅助手级",
                                2 to "项目包内",
                                3 to "仅当前对话",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = assistant.manualMemoryMount == value,
                                    onClick = { onUpdateAssistant(assistant.copy(manualMemoryMount = value)) },
                                    label = { Text(label) },
                                    enabled = assistant.enableMemory,
                                )
                            }
                        }
                    }
                },
            )
            item(
                headlineContent = { Text("做梦记忆的挂载") },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "做梦整理产生的记忆注入到哪些区块",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                1 to "仅助手级",
                                2 to "项目包内",
                                3 to "仅当前对话",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = assistant.dreamMemoryMount == value,
                                    onClick = { onUpdateAssistant(assistant.copy(dreamMemoryMount = value)) },
                                    label = { Text(label) },
                                    enabled = assistant.enableMemory,
                                )
                            }
                        }
                    }
                },
            )
            // v5.0.16 用户定案：做梦（记忆整理）独立开关
            switchItem(
                checked = assistant.memoryDreamEnabled,
                onCheckedChange = {
                    onUpdateAssistant(assistant.copy(memoryDreamEnabled = it))
                },
                enabled = assistant.enableMemory,
                supportingContent = { Text("允许模型在对话结束后通过做梦主动整理并记录记忆") },
                headlineContent = { Text("做梦主动记录") },
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
    // v5.3.4 ★用户「根本没有办法浏览和查找」—— 除接入本 tab 外，这里补**搜索**：
    // 按 路径 / 正文 / frontmatter description 三处模糊匹配（忽略大小写）
    var query by remember { mutableStateOf("") }
    val filteredMemories = remember(memories, query) {
        if (query.isBlank()) memories
        else memories.filter { m ->
            m.path.contains(query, ignoreCase = true) ||
                m.content.contains(query, ignoreCase = true) ||
                m.description.contains(query, ignoreCase = true)
        }
    }
    val memoryGroups = remember(filteredMemories) { filteredMemories.groupByDirectory() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // v5.3.4：记忆文件搜索（路径 / 正文 / 描述）
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索记忆（路径 / 内容 / 描述）") },
        )
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
    // v5.0.89：编辑对话框里点「单对话」但该记忆尚未归属对话时的提示位
    var pendingScopeHint by remember { mutableStateOf(false) }

    // v4.8.92: 记忆范围管理 —— [整个助手] / [单对话记忆]
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
        // v5.1.3 ★用户要求：每条记忆可改归属到**项目包**（此前只有助手级/单对话两档）。
        val folderRepo: me.rerere.rikkahub.data.repository.FolderRepository = org.koin.compose.koinInject()
        val settingsStoreForFolders: me.rerere.rikkahub.data.datastore.SettingsStore = org.koin.compose.koinInject()
        // 该 lambda 不捕获外层 Composable 的 assistant（CI 实证 Unresolved reference）→ 直接读当前助手
        val folderOwnerId = settingsStoreForFolders.settingsFlow.value.assistantId
        val folderList by remember(folderOwnerId) {
            folderRepo.getFoldersOfAssistant(folderOwnerId)
        }.collectAsStateWithLifecycle(initialValue = emptyList())
        var showFolderPicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { memoryDialogState.dismiss() },
            title = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = memory.content,
                        onValueChange = { update(memory.copy(content = it)) },
                        label = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
                        minLines = 2,
                        maxLines = 8,
                    )
                    // v5.0.89 ★用户定案「让我自己确定把它放在哪个范围内」：
                    // 这里可直接改归属；单对话归属必须由「+ → 选择归属对话」创建时指定。
                    Text(
                        text = "归属：" + when {
                            memory.conversationId != null -> "仅该对话可见"
                            memory.folderId != null -> "项目包内可见"
                            else -> "助手全部对话可见"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = memory.conversationId == null && memory.folderId == null,
                            onClick = {
                                update(memory.copy(folderId = null, conversationId = null))
                            },
                            label = { Text("助手级") },
                        )
                        FilterChip(
                            selected = memory.folderId != null && memory.conversationId == null,
                            onClick = { showFolderPicker = true },
                            label = { Text("项目包") },
                        )
                        FilterChip(
                            selected = memory.conversationId != null,
                            onClick = {
                                // 已属于某对话 → 保持；未归属 → 提示走新建入口
                                if (memory.conversationId == null) {
                                    pendingScopeHint = true
                                }
                            },
                            label = { Text("单对话") },
                        )
                    }
                    if (pendingScopeHint && memory.conversationId == null) {
                        Text(
                            text = "单对话归属请在新建时指定（右上角 + → 选择归属对话）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
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

        // v5.1.3 ★选项目包归属：选中即 folderId 落位、conversationId 清空
        // → 该记忆对**这个项目包下的全部对话**可见（注入链 getMemoriesOfFolder + sibling 已支持）
        if (showFolderPicker) {
            AlertDialog(
                onDismissRequest = { showFolderPicker = false },
                title = { Text("归属项目包（当前助手）") },
                text = {
                    if (folderList.isEmpty()) {
                        Text(
                            "该助手还没有项目包。先在抽屉里新建一个项目包，再回来设置归属。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            folderList.forEach { folder ->
                                TextButton(
                                    onClick = {
                                        update(memory.copy(folderId = folder.id.toString(), conversationId = null))
                                        showFolderPicker = false
                                    },
                                ) { Text(folder.name) }
                            }
                            TextButton(
                                onClick = {
                                    update(memory.copy(folderId = null, conversationId = null))
                                    showFolderPicker = false
                                },
                            ) { Text("取消归属（回到助手级）") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFolderPicker = false }) { Text("关闭") }
                },
            )
        }
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
                    // v5.0.63: 原 manageConversationScope 已随两个 Chip 一起删除，
                    // 改用「当前范围是否单对话」判断（顶部范围 Chip 选中单对话时
                    // conversationMemories 才非空）
                    if (conversationMemories.isNotEmpty()) {
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

        // v5.0.63 用户定案：这里原本还有「整个助手 / 单对话记忆」两个 FilterChip ——
        // 归属范围已经由记忆页顶部的范围 Chip（助手记忆 / 项目包记忆 / 单对话记忆）承担，
        // 在「管理记忆」里再摆一套属于重复 UI（用户原话："这两个 UI 本不应该存在，
        // 它们现在本应该被合并到上面去"）。现在直接按顶部选中的范围决定视图：
        //   conversationMemories 非空 = 当前范围是「单对话记忆」→ 按归属对话分组
        //   否则 = 助手/项目包范围 → 平铺
        if (conversationMemories.isEmpty()) {
            memories.fastForEach { memory ->
                key(memory.id) {
                    MemoryItem(
                        memory = memory,
                        tag = listOfNotNull(
                            memoryScopeHint(memory),
                            memory.sourceConversationId?.let { sid ->
                                memoryTitles[sid]?.let { "来自「$it」" } ?: "来自已删除的对话"
                            },
                        ).joinToString("｜"),
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
                            tag = "仅「$title」可见" + if (memory.source == "dream") "｜做梦产物" else "",
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

/** v5.0.89：记忆条目的「区间 / 性质」小标签（用户定案：每条记忆必须有明确 tag） */
@Composable
private fun MemoryChip(
    text: String,
    container: Color,
    content: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/** v5.0.87：条目可见范围提示（页面与模型可见性一致的直白标注） */
private fun memoryScopeHint(m: me.rerere.rikkahub.data.model.AssistantMemory): String =
    when {
        m.conversationId != null -> "仅本对话可见"
        m.folderId != null -> "项目包内可见（包内全部对话）"
        else -> "本助手全部对话可见"
    } + if (m.source == "dream") "｜做梦产物" else ""

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
                // v5.0.89 ★用户定案：每条记忆必须能一眼看出「哪个区间」+「主动还是做梦」
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemoryChip(
                        text = when {
                            memory.conversationId != null -> "单对话"
                            memory.folderId != null -> "项目包"
                            else -> "助手级"
                        },
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    MemoryChip(
                        text = if (memory.source == "dream") "做梦" else "主动",
                        container = if (memory.source == "dream") {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        content = if (memory.source == "dream") {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                    tag?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
