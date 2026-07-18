package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.ai.tools.routing.ToolDomain
import me.rerere.rikkahub.data.datastore.CustomDomain
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingDomainPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    var showAddOverride by remember { mutableStateOf(false) }
    var showNewDomain by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = { showNewDomain = true }) {
                        Icon(HugeIcons.Add01, "新建分类")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddOverride = true }) {
                Icon(HugeIcons.Edit01, "添加工具覆盖")
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 域列表
            item {
                Text("类别管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("编辑描述或删除自定义类别", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val allDomains = ToolDomain.entries.map { d -> Pair(d.label, d.triggerDescription) } +
                settings.customDomains.map { Pair(it.name, it.description) }
            val customNames = settings.customDomains.map { it.name }.toSet()

            items(allDomains) { (name, defaultDesc) ->
                val isCustom = name in customNames
                val desc = settings.customDomainDescriptions[name] ?: defaultDesc
                var showEdit by remember { mutableStateOf(false) }
                var editText by remember(desc) { mutableStateOf(desc) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("[$name]", fontWeight = FontWeight.SemiBold,
                                color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Row {
                                IconButton(onClick = { showEdit = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp))
                                }
                                if (isCustom) {
                                    IconButton(onClick = {
                                        vm.updateSettings(settings.copy(
                                            customDomains = settings.customDomains.filter { it.name != name }
                                        ))
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(HugeIcons.Delete01, null, modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                        Text(desc, style = MaterialTheme.typography.bodySmall,
                            color = if (name in settings.customDomainDescriptions)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (showEdit) {
                    AlertDialog(
                        onDismissRequest = { showEdit = false },
                        title = { Text("编辑 [$name] 描述") },
                        text = {
                            OutlinedTextField(value = editText, onValueChange = { editText = it },
                                label = { Text("触发描述") }, modifier = Modifier.fillMaxWidth(),
                                singleLine = false, maxLines = 3)
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val m = settings.customDomainDescriptions.toMutableMap()
                                m[name] = editText
                                vm.updateSettings(settings.copy(customDomainDescriptions = m))
                                showEdit = false
                            }) { Text("保存") }
                        },
                        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } }
                    )
                }
            }

            // 工具覆盖
            item {
                Spacer(Modifier.height(8.dp))
                Text("工具手动覆盖", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("强制指定某工具归属的类别。优先于自动分类。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (settings.toolDomainOverrides.isEmpty()) {
                item {
                    Text("暂无手动覆盖", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(settings.toolDomainOverrides.entries.toList()) { (tool, domain) ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool, style = MaterialTheme.typography.bodyMedium)
                                Text("→ [$domain]", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = {
                                val m = settings.toolDomainOverrides.toMutableMap(); m.remove(tool)
                                vm.updateSettings(settings.copy(toolDomainOverrides = m))
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(HugeIcons.Delete01, null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建自定义域对话框
    if (showNewDomain) {
        var newName by remember { mutableStateOf("") }
        var newDesc by remember { mutableStateOf("") }
        var newKw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDomain = false },
            title = { Text("新建分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it },
                        label = { Text("类别名称") }, singleLine = true)
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it },
                        label = { Text("触发描述") }, singleLine = false, maxLines = 2)
                    OutlinedTextField(value = newKw, onValueChange = { newKw = it },
                        label = { Text("关键词(逗号分隔)") }, singleLine = true,
                        supportingText = { Text("自动分类时匹配工具名和描述中的关键词") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank() && newDesc.isNotBlank()) {
                        val kws = newKw.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                        vm.updateSettings(settings.copy(
                            customDomains = settings.customDomains + CustomDomain(newName.trim(), newDesc.trim(), kws)
                        ))
                        showNewDomain = false
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewDomain = false }) { Text("取消") } }
        )
    }

    // 添加工具覆盖对话框
    if (showAddOverride) {
        var toolName by remember { mutableStateOf("") }
        var targetDomain by remember { mutableStateOf("") }
        val allDomainNames = ToolDomain.entries.map { it.label } + settings.customDomains.map { it.name }
        AlertDialog(
            onDismissRequest = { showAddOverride = false },
            title = { Text("添加工具覆盖") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = toolName, onValueChange = { toolName = it },
                        label = { Text("工具名称") }, singleLine = true,
                        supportingText = { Text("如 list_files, search_web, mcp__github__xxx") })
                    OutlinedTextField(value = targetDomain, onValueChange = { targetDomain = it },
                        label = { Text("目标类别") }, singleLine = true,
                        supportingText = { Text("可用: ${allDomainNames.joinToString("、")}") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (toolName.isNotBlank() && targetDomain.isNotBlank()) {
                        val m = settings.toolDomainOverrides.toMutableMap()
                        m[toolName.trim()] = targetDomain.trim()
                        vm.updateSettings(settings.copy(toolDomainOverrides = m))
                        showAddOverride = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddOverride = false }) { Text("取消") } }
        )
    }
}
