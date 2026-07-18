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

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions,
            settings.customDomains, settings.customDomainKeywords)
    }

    // 收集所有工具
    val allTools = remember(settings) {
        val list = mutableListOf<ToolPreview>()
        try {
            skillManager.listSkills().forEach { s ->
                list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}"))
            }
        } catch (_: Exception) {}
        for (srv in settings.mcpServers) {
            for (t in srv.commonOptions.tools.filter { it.enable }) {
                list.add(ToolPreview("mcp__${srv.commonOptions.name}__${t.name}", t.description ?: ""))
            }
        }
        list
    }

    val allDomainNames = remember(settings) {
        ToolDomain.entries.map { it.label } + settings.customDomains.map { it.name }
    }

    // 过滤
    val filtered = remember(allTools, searchQuery, filterDomain, settings) {
        allTools.filter { t ->
            val q = searchQuery.lowercase()
            val matchesQuery = q.isEmpty() || t.name.lowercase().contains(q) || t.description.lowercase().contains(q)
            if (!matchesQuery) return@filter false
            if (filterDomain == "全部") return@filter true
            val classifyResult = router.classifyPreview(t.name, t.description)
            classifyResult == filterDomain
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            // 搜索+过滤
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索工具") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = { Icon(HugeIcons.GlobalSearch, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty())
                            IconButton(onClick = { searchQuery = "" }) { Icon(HugeIcons.Cancel01, null) }
                    },
                )
            }
            // 域过滤 chips
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selected = filterDomain == "全部", onClick = { filterDomain = "全部" },
                            label = { Text("全部(${allTools.size})") })
                        allDomainNames.take(5).forEach { dn ->
                            val count = allTools.count { router.classifyPreview(it.name, it.description) == dn }
                            FilterChip(selected = filterDomain == dn, onClick = { filterDomain = dn },
                                label = { Text("$dn($count)") })
                        }
                    }
                }
                item { Text("共 ${filtered.size} 个工具", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }

                items(filtered) { tool ->
                    val domain = router.classifyPreview(tool.name, tool.description)
                    val overridden = tool.name in settings.toolDomainOverrides
                    Card(Modifier.fillMaxWidth().clickable { selectedTool = tool }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(tool.description.take(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AssistChip(onClick = {}, label = { Text(domain, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(24.dp))
                                    if (overridden) {
                                        AssistChip(onClick = {}, label = { Text("覆盖", style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp), colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer))
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

    // 选择工具后的操作对话框
    if (selectedTool != null) {
        val tool = selectedTool!!
        val currentDomain = router.classifyPreview(tool.name, tool.description)
        var targetDomain by remember { mutableStateOf(currentDomain) }
        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前分类: $currentDomain", fontWeight = FontWeight.SemiBold)
                    Text("描述: ${tool.description.take(200)}", style = MaterialTheme.typography.bodySmall)
                    Text("移动到:", style = MaterialTheme.typography.labelSmall)
                    allDomainNames.forEach { dn ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = targetDomain == dn, onClick = { targetDomain = dn })
                            Text(dn, Modifier.clickable { targetDomain = dn })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val m = settings.toolDomainOverrides.toMutableMap()
                    m[tool.name] = targetDomain
                    vm.updateSettings(settings.copy(toolDomainOverrides = m))
                    selectedTool = null
                }) { Text("移动到此域") }
            },
            dismissButton = {
                Row {
                    if (tool.name in settings.toolDomainOverrides) {
                        TextButton(onClick = {
                            val m = settings.toolDomainOverrides.toMutableMap(); m.remove(tool.name)
                            vm.updateSettings(settings.copy(toolDomainOverrides = m))
                            selectedTool = null
                        }) { Text("清除覆盖") }
                    }
                    TextButton(onClick = { selectedTool = null }) { Text("取消") }
                }
            }
        )
    }
}
