/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.ai.tools.routing.CORE_MATRIX_TOOLS
import me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.plus

private const val ALL_LABEL = "全部"
private const val TOP_LEVEL_LABEL = "顶层直连"
private const val AUTO_LABEL = "自动归类"

/**
 * 工具列表 —— 逐个/批量调整工具归属（v4.8.85 重写）。
 *
 * 归属只有三种状态，且**移动即生效**（下一次发送立即按新归属组装请求）：
 *  · 顶层直连 —— 始终注入请求体，模型每轮都能直接调用（"框架工具"）
 *  · 某工具区 —— 模型经 invoke_tools 加载该区后才看到（省 token）
 *  · 自动归类 —— 按关键词自动落到某个工具区（清除手动归属）
 * v4.8.85 新增：多选批量归属（一次挪一堆）、效果提示、目标区筛选。
 */
@Composable
fun SettingToolListPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: me.rerere.rikkahub.data.files.SkillManager = koinInject()
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = koinInject()
    val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository = koinInject()
    val operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider = koinInject()
    val globalRevision by settingsStore.settingsRevision.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var filterZone by remember { mutableStateOf(ALL_LABEL) }
    var selectedTool by remember { mutableStateOf<ToolPreview?>(null) }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedNames by remember { mutableStateOf(emptySet<String>()) }
    var bulkPicker by remember { mutableStateOf(false) }
    var zoneQuery by remember { mutableStateOf("") }

    val allTools: List<ToolPreview> = remember(settings, globalRevision) {
        runCatching {
            buildToolList(
                settings, localTools, skillManager, mcpManager,
                conversationRepo = conversationRepo,
                settingsStore = settingsStore,
                workspaceRepository = workspaceRepository,
                operitToolProvider = operitToolProvider,
            )
        }.getOrDefault(emptyList())
    }
    val router = remember(settings, globalRevision) { zoneRouterOf(settings) }
    val zoneMap = remember(settings, globalRevision, allTools) { router.zoneMap(allTools.asShellTools()) }
    val topLevelNames = remember(settings) { topLevelToolSetOf(settings) }

    val ownerMap: Map<String, String> = remember(zoneMap, topLevelNames) {
        buildMap {
            zoneMap.classified.forEach { (zoneId, tools) -> tools.forEach { put(it.name, zoneId) } }
            topLevelNames.forEach { put(it, TOP_LEVEL_LABEL) }
        }
    }
    val topLevelCount = allTools.count { it.name in topLevelNames }

    /** 唯一的归属写入口 —— 单工具与批量共用，规则只有一份 */
    fun applyOwnership(names: Collection<String>, target: String) {
        if (names.isEmpty()) return
        var s = settings
        val links = s.toolZoneLinks.toMutableMap()
        val additions = s.topLevelAdditions.toMutableSet()
        val removals = s.topLevelRemovals.toMutableSet()
        names.forEach { name ->
            if (name in CORE_MATRIX_TOOLS) return@forEach // 核心件恒在顶层，不接受改归属
            when (target) {
                TOP_LEVEL_LABEL -> {
                    links.remove(name); additions.add(name); removals.remove(name)
                }
                AUTO_LABEL -> {
                    links.remove(name); additions.remove(name); removals.remove(name)
                }
                else -> {
                    links[name] = target
                    additions.remove(name)
                    if (name in topLevelNames) removals.add(name) else removals.remove(name)
                }
            }
        }
        s = s.copy(toolZoneLinks = links, topLevelAdditions = additions, topLevelRemovals = removals)
        vm.updateSettings(s)
    }

    val filtered = remember(allTools, searchQuery, filterZone, ownerMap) {
        allTools.filter { t ->
            val q = searchQuery.lowercase()
            if (q.isNotEmpty() && !t.name.lowercase().contains(q) && !t.description.lowercase().contains(q)) return@filter false
            when (filterZone) {
                ALL_LABEL -> true
                TOP_LEVEL_LABEL -> t.name in topLevelNames
                AUTO_LABEL -> t.name !in settings.toolZoneLinks && t.name !in topLevelNames
                else -> ownerMap[t.name] == filterZone
            }
        }
    }

    /** 效果说明 —— 让"移动之后到底发生了啥"一眼可见 */
    fun effectOf(target: String): String = when (target) {
        TOP_LEVEL_LABEL -> "顶层直连：始终注入请求体，模型每一轮都能直接调用（不省 token）。"
        AUTO_LABEL -> "自动归类：清掉手动归属，按关键词自动落到某个工具区。"
        else -> "工具区：模型经 invoke_tools 加载「$target」后才看到它（省 token）。"
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("工具列表") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = {
                        multiSelect = !multiSelect
                        if (!multiSelect) selectedNames = emptySet()
                    }) {
                        Icon(
                            HugeIcons.ListChecks, "多选批量",
                            tint = if (multiSelect) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { pad ->
        BackHandler { onBack() }
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    leadingIcon = { Icon(HugeIcons.GlobalSearch, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(HugeIcons.Cancel01, null) }
                    },
                )
            }
            if (multiSelect) {
                Text(
                    "多选模式：点工具即勾选，下方批量改动归属。核心件（${CORE_MATRIX_TOOLS.joinToString(" / ")}）恒在顶层，不参与改动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item {
                            FilterChip(
                                selected = filterZone == ALL_LABEL,
                                onClick = { filterZone = ALL_LABEL },
                                label = { Text("$ALL_LABEL(${allTools.size})") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = filterZone == TOP_LEVEL_LABEL,
                                onClick = { filterZone = TOP_LEVEL_LABEL },
                                label = { Text("$TOP_LEVEL_LABEL($topLevelCount)") },
                            )
                        }
                        item {
                            FilterChip(
                                selected = filterZone == AUTO_LABEL,
                                onClick = { filterZone = AUTO_LABEL },
                                label = { Text(AUTO_LABEL) },
                            )
                        }
                        items(zoneMap.allIds) { zoneId ->
                            FilterChip(
                                selected = filterZone == zoneId,
                                onClick = { filterZone = zoneId },
                                label = { Text("${router.displayNameOf(zoneId)}(${zoneMap.counts[zoneId] ?: 0})") },
                            )
                        }
                    }
                }
                item { Text("${filtered.size} 个工具", style = MaterialTheme.typography.bodySmall) }

                items(filtered) { tool ->
                    val owner = ownerMap[tool.name] ?: router.classify(tool.name, tool.description)
                    val isChecked = tool.name in selectedNames
                    val isCore = tool.name in CORE_MATRIX_TOOLS
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            if (multiSelect) {
                                if (isCore) return@clickable
                                selectedNames = if (isChecked) selectedNames - tool.name else selectedNames + tool.name
                            } else {
                                selectedTool = tool
                            }
                        },
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (multiSelect) {
                                Checkbox(checked = isChecked, onCheckedChange = null, enabled = !isCore)
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    (settings.toolDescriptionOverrides[tool.name] ?: tool.description).take(80),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AssistChip(
                                        onClick = { filterZone = owner },
                                        label = { Text(if (owner == TOP_LEVEL_LABEL) TOP_LEVEL_LABEL else router.label(owner), style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(24.dp),
                                    )
                                    if (tool.name in settings.toolZoneLinks) {
                                        AssistChip(onClick = {}, label = { Text("手动归属", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp))
                                    }
                                }
                            }
                            if (!multiSelect) Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (multiSelect) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("已选 ${selectedNames.size} 个工具", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val on = selectedNames.isNotEmpty()
                            TextButton(enabled = on, onClick = { zoneQuery = ""; bulkPicker = true }) { Text("移入工具区…") }
                            TextButton(enabled = on, onClick = { applyOwnership(selectedNames, TOP_LEVEL_LABEL); selectedNames = emptySet() }) { Text("移回顶层") }
                            TextButton(enabled = on, onClick = { applyOwnership(selectedNames, AUTO_LABEL); selectedNames = emptySet() }) { Text("交回自动") }
                        }
                    }
                }
            }
        }
    }

    // ── 批量：选目标工具区（带筛选） ──
    if (bulkPicker) {
        AlertDialog(
            onDismissRequest = { bulkPicker = false },
            title = { Text("把 ${selectedNames.size} 个工具移到…") },
            text = {
                Column {
                    OutlinedTextField(
                        value = zoneQuery, onValueChange = { zoneQuery = it },
                        label = { Text("筛选工具区") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val options = zoneMap.allIds.filter {
                        zoneQuery.isBlank() || router.label(it).contains(zoneQuery, ignoreCase = true)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(options) { zoneId ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    applyOwnership(selectedNames, zoneId)
                                    selectedNames = emptySet()
                                    bulkPicker = false
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(router.label(zoneId), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${zoneMap.counts[zoneId] ?: 0} 个工具 · ${router.descriptionOf(zoneId).take(30)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { bulkPicker = false }) { Text("取消") } },
        )
    }

    // ── 单工具归属 ──
    selectedTool?.let { tool ->
        val currentOwner = ownerMap[tool.name] ?: TOP_LEVEL_LABEL
        val isCore = tool.name in CORE_MATRIX_TOOLS
        var target by remember(tool.name) { mutableStateOf(if (isCore) TOP_LEVEL_LABEL else currentOwner) }
        var editDesc by remember(tool.name) { mutableStateOf(settings.toolDescriptionOverrides[tool.name] ?: tool.description) }
        var editName by remember(tool.name) { mutableStateOf(settings.toolNameOverrides[tool.name] ?: "") }
        var pickQuery by remember(tool.name) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text("当前归属: $currentOwner", fontWeight = FontWeight.SemiBold)
                    HorizontalDivider()
                    OutlinedTextField(
                        value = editName, onValueChange = { editName = it },
                        label = { Text("工具名称（改名）") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        supportingText = { Text("留空保持原名。仅允许字母、数字、下划线、连字符。") },
                    )
                    OutlinedTextField(
                        value = editDesc, onValueChange = { editDesc = it },
                        label = { Text("工具描述") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4,
                        supportingText = { Text("留空恢复默认。") },
                    )
                    HorizontalDivider()
                    Text("移动到:", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { target = TOP_LEVEL_LABEL }) {
                        RadioButton(selected = target == TOP_LEVEL_LABEL, onClick = { target = TOP_LEVEL_LABEL })
                        Text(TOP_LEVEL_LABEL)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { target = AUTO_LABEL }) {
                        RadioButton(selected = target == AUTO_LABEL, onClick = { target = AUTO_LABEL })
                        Text(AUTO_LABEL)
                    }
                    if (isCore) {
                        Text(
                            "「${tool.name}」是工具矩阵核心件，必须留在顶层 —— 移出后模型将无法加载任何工具区。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        OutlinedTextField(
                            value = pickQuery, onValueChange = { pickQuery = it },
                            label = { Text("筛选工具区") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        zoneMap.allIds
                            .filter { pickQuery.isBlank() || router.label(it).contains(pickQuery, ignoreCase = true) }
                            .forEach { zoneId ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { target = zoneId }) {
                                    RadioButton(selected = target == zoneId, onClick = { target = zoneId })
                                    Text(router.label(zoneId))
                                }
                            }
                    }
                    Text(effectOf(target), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    applyOwnership(listOf(tool.name), if (isCore) TOP_LEVEL_LABEL else target)
                    var s = settings
                    val descMap = s.toolDescriptionOverrides.toMutableMap()
                    if (editDesc.isNotBlank() && editDesc != tool.description) descMap[tool.name] = editDesc else descMap.remove(tool.name)
                    s = s.copy(toolDescriptionOverrides = descMap)
                    val nameMap = s.toolNameOverrides.toMutableMap()
                    val newName = editName.trim()
                    if (newName.isNotBlank() && newName.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' }) {
                        nameMap[tool.name] = newName
                    } else if (newName.isBlank()) {
                        nameMap.remove(tool.name)
                    }
                    s = s.copy(toolNameOverrides = nameMap)
                    vm.updateSettings(s)
                    selectedTool = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (tool.name in settings.toolZoneLinks || tool.name in settings.toolDescriptionOverrides ||
                        tool.name in settings.topLevelAdditions || tool.name in settings.topLevelRemovals
                    ) {
                        TextButton(onClick = {
                            applyOwnership(listOf(tool.name), AUTO_LABEL)
                            var s = settings
                            s = s.copy(toolDescriptionOverrides = s.toolDescriptionOverrides.toMutableMap().also { it.remove(tool.name) })
                            vm.updateSettings(s)
                            selectedTool = null
                        }) { Text("恢复自动归类") }
                    }
                    TextButton(onClick = { selectedTool = null }) { Text("取消") }
                }
            },
        )
    }
}
