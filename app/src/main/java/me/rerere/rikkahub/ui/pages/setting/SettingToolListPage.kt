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
    var searchQuery by remember { mutableStateOf("") }
    var filterDomain by remember { mutableStateOf("全部") }
    var selectedTool by remember { mutableStateOf<ToolPreview?>(null) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var showEditDesc by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf("") }
    var editDescText by remember { mutableStateOf("") }

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions,
            settings.customDomains, settings.customDomainKeywords,
            settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains)
    }

    val allTools = remember(settings) {
        val list = mutableListOf<ToolPreview>()
        try { skillManager.listSkills().forEach { s -> list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}")) } } catch (_: Exception) {}
        for (srv in settings.mcpServers) for (t in srv.commonOptions.tools.filter { it.enable }) list.add(ToolPreview("mcp__${srv.commonOptions.name}__${t.name}", t.description ?: ""))
        list
    }

    val allDomainNames = remember(settings) {
        ToolDomain.entries.map { it.label } + settings.customDomains.map { it.name }
    }

    val filtered = remember(allTools, searchQuery, filterDomain) {
        allTools.filter { t ->
            val q = searchQuery.lowercase()
            if (q.isNotEmpty() && !t.name.lowercase().contains(q) && !t.description.lowercase().contains(q)) return@filter false
            if (filterDomain == "全部") return@filter true
            router.classifyPreview(t.name, settings.toolDescriptionOverrides[t.name] ?: t.description) == filterDomain
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
                            val count = allTools.count { router.classifyPreview(it.name, settings.toolDescriptionOverrides[it.name] ?: it.description) == dn }
                            FilterChip(selected = filterDomain == dn, onClick = { filterDomain = dn }, label = { Text("${router.displayName(dn)}($count)") })
                        }
                    }
                }
                item { Text("${filtered.size}个工具", style = MaterialTheme.typography.bodySmall) }

                items(filtered) { tool ->
                    val domain = router.classifyPreview(tool.name, settings.toolDescriptionOverrides[tool.name] ?: tool.description)
                    val hasDesc = tool.name in settings.toolDescriptionOverrides
                    val overridden = tool.name in settings.toolDomainOverrides
                    Card(Modifier.fillMaxWidth().clickable {
                        selectedTool = tool
                        moveTarget = domain
                        editDescText = settings.toolDescriptionOverrides[tool.name] ?: tool.description
                    }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((settings.toolDescriptionOverrides[tool.name] ?: tool.description).take(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AssistChip(onClick = {}, label = { Text(router.displayName(domain), style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp))
                                    if (overridden) AssistChip(onClick = {}, label = { Text("覆盖", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp), colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer))
                                    if (hasDesc) AssistChip(onClick = {}, label = { Text("描述", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.height(24.dp), colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
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
        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前分类: $moveTarget", fontWeight = FontWeight.SemiBold)
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
                    // 清除覆盖
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
