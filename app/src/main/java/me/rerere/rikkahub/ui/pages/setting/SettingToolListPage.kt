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
import me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.plus

private const val TOP_LEVEL_LABEL = "顶层直连"
private const val ALL_LABEL = "全部"

/**
 * 工具列表 —— 逐工具查看/调整归属（v4.8.83 重写）。
 * 归属只有两种状态：属于某个工具区，或顶层直连（始终注入、不参与归类）。
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

    val allTools: List<ToolPreview> = remember(settings, globalRevision) {
        runCatching {
            buildPreviewTools(
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

    // 工具 → 归属（工具区 id 或 顶层直连）
    val ownerMap: Map<String, String> = remember(zoneMap, topLevelNames) {
        buildMap {
            zoneMap.classified.forEach { (zoneId, tools) -> tools.forEach { put(it.name, zoneId) } }
            topLevelNames.forEach { put(it, TOP_LEVEL_LABEL) }
        }
    }

    val topLevelCount = allTools.count { it.name in topLevelNames }
    val filtered = remember(allTools, searchQuery, filterZone, ownerMap) {
        allTools.filter { t ->
            val q = searchQuery.lowercase()
            if (q.isNotEmpty() && !t.name.lowercase().contains(q) && !t.description.lowercase().contains(q)) return@filter false
            when (filterZone) {
                ALL_LABEL -> true
                TOP_LEVEL_LABEL -> t.name in topLevelNames
                else -> ownerMap[t.name] == filterZone
            }
        }
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("工具列表") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
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
                        items(zoneMap.allIds) { zoneId ->
                            val count = zoneMap.counts[zoneId] ?: 0
                            FilterChip(
                                selected = filterZone == zoneId,
                                onClick = { filterZone = zoneId },
                                label = { Text("${router.displayNameOf(zoneId)}($count)") },
                            )
                        }
                    }
                }
                item { Text("${filtered.size} 个工具", style = MaterialTheme.typography.bodySmall) }

                items(filtered) { tool ->
                    val owner = ownerMap[tool.name] ?: router.classify(tool.name, tool.description)
                    Card(Modifier.fillMaxWidth().clickable { selectedTool = tool }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
                            Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    selectedTool?.let { tool ->
        val currentOwner = ownerMap[tool.name] ?: TOP_LEVEL_LABEL
        var target by remember(tool.name) { mutableStateOf(currentOwner) }
        var editDesc by remember(tool.name) { mutableStateOf(settings.toolDescriptionOverrides[tool.name] ?: tool.description) }
        var editName by remember(tool.name) { mutableStateOf(settings.toolNameOverrides[tool.name] ?: "") }

        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text("归属: $target", fontWeight = FontWeight.SemiBold)
                    Text(
                        "顶层直连 = 始终注入请求体、不参与工具区归类；进工具区 = 经 invoke_tools 加载。使用任何工具都无需先移动它。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    Text("移动到:", style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { target = TOP_LEVEL_LABEL }) {
                        RadioButton(selected = target == TOP_LEVEL_LABEL, onClick = { target = TOP_LEVEL_LABEL })
                        Text(TOP_LEVEL_LABEL)
                    }
                    zoneMap.allIds.forEach { zoneId ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { target = zoneId }) {
                            RadioButton(selected = target == zoneId, onClick = { target = zoneId })
                            Text(router.label(zoneId))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    var s = settings
                    val links = s.toolZoneLinks.toMutableMap()
                    val additions = s.topLevelAdditions.toMutableSet()
                    val removals = s.topLevelRemovals.toMutableSet()
                    if (target == TOP_LEVEL_LABEL) {
                        links.remove(tool.name)
                        additions.add(tool.name)
                        removals.remove(tool.name)
                    } else {
                        links[tool.name] = target
                        additions.remove(tool.name)
                        if (tool.name in topLevelNames) removals.add(tool.name) else removals.remove(tool.name)
                    }
                    s = s.copy(toolZoneLinks = links, topLevelAdditions = additions, topLevelRemovals = removals)

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
                            var s = settings
                            s = s.copy(toolZoneLinks = s.toolZoneLinks.toMutableMap().also { it.remove(tool.name) })
                            s = s.copy(toolDescriptionOverrides = s.toolDescriptionOverrides.toMutableMap().also { it.remove(tool.name) })
                            s = s.copy(topLevelAdditions = s.topLevelAdditions - tool.name)
                            s = s.copy(topLevelRemovals = s.topLevelRemovals - tool.name)
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
