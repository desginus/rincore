package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.routing.ToolDomain
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingToolListPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: SkillManager = koinInject()
    val localTools: LocalTools = koinInject()
    val mcpManager: McpManager = koinInject()
    var searchQuery by remember { mutableStateOf("") }
    var filterDomain by remember { mutableStateOf("全部") }
    var selectedTool by remember { mutableStateOf<ToolPreview?>(null) }

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions,
            settings.customDomains, settings.customDomainKeywords,
            settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains)
    }

    // 完整工具清单——依赖真实数据源而非settings快照
    val allTools: List<ToolPreview> = run {
        val list = mutableListOf<ToolPreview>()
        // 内置本地工具
        try {
            val allOptions = listOf(
                LocalToolOption.JavascriptEngine, LocalToolOption.TimeInfo, LocalToolOption.Clipboard,
                LocalToolOption.Tts, LocalToolOption.AskUser, LocalToolOption.ScreenTime,
                LocalToolOption.Calendar, LocalToolOption.CronJobs, LocalToolOption.ToastAndNotification,
                LocalToolOption.SubAgents, LocalToolOption.SystemIntents, LocalToolOption.Share,
                LocalToolOption.Location, LocalToolOption.Battery, LocalToolOption.MediaPlayer,
                LocalToolOption.MediaScanner, LocalToolOption.Files, LocalToolOption.Download,
                LocalToolOption.Archive, LocalToolOption.CostGuards, LocalToolOption.Browser,
            )
            localTools.getTools(allOptions).forEach { t ->
                list.add(ToolPreview(t.name, t.description))
            }
        } catch (_: Exception) {}
        // Skill工具
        try { skillManager.listSkills().forEach { s -> list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}")) } } catch (_: Exception) {}
        // MCP工具
        try {
            mcpManager.getAllAvailableTools().forEach { (_, serverName, tool) ->
                list.add(ToolPreview("mcp__${serverName}__${tool.name}", tool.description ?: ""))
            }
        } catch (_: Exception) {}
        list
    }

    // 只展示顶级域名 + 自定义域（用于筛选）
    val allDomainNames = remember(settings) {
        ToolDomain.entries.filter { it.parent == null }.map { it.label } + settings.customDomains.map { it.name }
    }

    val filtered = remember(allTools, searchQuery, filterDomain) {
        allTools.filter { t ->
            val q = searchQuery.lowercase()
            if (q.isNotEmpty() && !t.name.lowercase().contains(q) && !t.description.lowercase().contains(q)) return@filter false
            if (filterDomain == "全部") return@filter true
            val d = router.classifyPreview(t.name, settings.toolDescriptionOverrides[t.name] ?: t.description)
            d == filterDomain || d.startsWith("$filterDomain/")
        }
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = { TopAppBar(title = { Text("工具列表") }, navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } }, colors = CustomColors.topBarColors) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    leadingIcon = { Icon(HugeIcons.GlobalSearch, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(HugeIcons.Cancel01, null) } })
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = filterDomain == "全部", onClick = { filterDomain = "全部" }, label = { Text("全部(${allTools.size})") })
                        allDomainNames.take(6).forEach { dn ->
                            val count = allTools.count {
                                val d = router.classifyPreview(it.name, settings.toolDescriptionOverrides[it.name] ?: it.description)
                                d == dn || d.startsWith("$dn/")
                            }
                            FilterChip(selected = filterDomain == dn, onClick = { filterDomain = dn }, label = { Text("${router.displayName(dn)}($count)") })
                        }
                    }
                }
                item { Text("${filtered.size}个工具", style = MaterialTheme.typography.bodySmall) }

                items(filtered) { tool ->
                    val domain = router.classifyPreview(tool.name, settings.toolDescriptionOverrides[tool.name] ?: tool.description)
                    val displayDomain = domain.substringBefore("/")
                    val hasDesc = tool.name in settings.toolDescriptionOverrides
                    val overridden = tool.name in settings.toolDomainOverrides
                    Card(Modifier.fillMaxWidth().clickable {
                        selectedTool = tool
                    }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((settings.toolDescriptionOverrides[tool.name] ?: tool.description).take(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // 域名标签——点击可筛选
                                    AssistChip(
                                        onClick = { filterDomain = domain },
                                        label = { Text(displayDomain, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(24.dp)
                                    )
                                    // 覆盖标签——点击打开对话框编辑
                                    if (overridden) {
                                        AssistChip(
                                            onClick = { selectedTool = tool },
                                            label = { Text("覆盖", style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp),
                                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                        )
                                    }
                                    if (hasDesc) {
                                        AssistChip(
                                            onClick = { selectedTool = tool },
                                            label = { Text("描述", style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp),
                                            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                        )
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

    // 工具操作对话框
    if (selectedTool != null) {
        val tool = selectedTool!!
        var moveTarget by remember(tool) {
            val fullDomain = settings.toolDomainOverrides[tool.name] ?: router.classifyPreview(tool.name, settings.toolDescriptionOverrides[tool.name] ?: tool.description)
            // 提取顶级域名（去掉子路径，如 "搜索/搜索引擎" → "搜索"）
            mutableStateOf(fullDomain.substringBefore("/"))
        }
        var editDescText by remember(tool) { mutableStateOf(settings.toolDescriptionOverrides[tool.name] ?: tool.description) }
        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前分类: ${moveTarget}", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editDescText,
                        onValueChange = { editDescText = it },
                        label = { Text("工具描述") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        supportingText = { Text("修改后影响自动分类。留空恢复默认。") }
                    )
                    Text("移动到:", style = MaterialTheme.typography.labelSmall)
                    allDomainNames.forEach { dn ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = moveTarget == dn, onClick = { moveTarget = dn })
                            Text(dn, Modifier.clickable { moveTarget = dn })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    var s = settings
                    // 移动
                    val m = s.toolDomainOverrides.toMutableMap()
                    m[tool.name] = moveTarget; s = s.copy(toolDomainOverrides = m)
                    // 描述
                    val dm = s.toolDescriptionOverrides.toMutableMap()
                    if (editDescText.isNotBlank() && editDescText != tool.description) dm[tool.name] = editDescText
                    else dm.remove(tool.name)
                    s = s.copy(toolDescriptionOverrides = dm)
                    vm.updateSettings(s)
                    selectedTool = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (tool.name in settings.toolDomainOverrides || tool.name in settings.toolDescriptionOverrides) {
                        TextButton(onClick = {
                            var s = settings
                            s = s.copy(toolDomainOverrides = s.toolDomainOverrides.toMutableMap().also { it.remove(tool.name) })
                            s = s.copy(toolDescriptionOverrides = s.toolDescriptionOverrides.toMutableMap().also { it.remove(tool.name) })
                            vm.updateSettings(s)
                            selectedTool = null
                        }) { Text("清除所有覆盖") }
                    }
                    TextButton(onClick = { selectedTool = null }) { Text("取消") }
                }
            }
        )
    }
}
