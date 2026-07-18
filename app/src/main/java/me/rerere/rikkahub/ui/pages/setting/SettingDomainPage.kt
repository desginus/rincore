package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
    var showNewDomain by remember { mutableStateOf(false) }
    var showAddOverride by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("工具域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建分类") }
                },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddOverride = true }) {
                Icon(HugeIcons.Edit01, "添加覆盖")
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 头部概览
            item {
                val overrideCount = settings.toolDomainOverrides.size
                val customCount = settings.customDomains.size
                Text("${ToolDomain.entries.size + customCount} 个类别 · $overrideCount 个手动覆盖",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 所有域（内置+自定义）
            val allDomains = ToolDomain.entries.map { d -> Triple(d.label, d.triggerDescription, d.matchKeywords) } +
                settings.customDomains.map { Triple(it.name, it.description, it.keywords) }
            val customNames = settings.customDomains.map { it.name }.toSet()

            itemsIndexed(allDomains) { _, (name, defaultDesc, keywords) ->
                val isCustom = name in customNames
                val desc = settings.customDomainDescriptions[name] ?: defaultDesc
                val overrideTools = settings.toolDomainOverrides.filter { it.value == name }
                var expanded by remember { mutableStateOf(false) }
                var showEdit by remember { mutableStateOf(false) }
                var editText by remember(desc) { mutableStateOf(desc) }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        // 标题行
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("[$name]", fontWeight = FontWeight.Bold,
                                color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Row {
                                IconButton(onClick = { showEdit = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp))
                                }
                                if (isCustom) {
                                    IconButton(onClick = {
                                        vm.updateSettings(settings.copy(customDomains = settings.customDomains.filter { it.name != name }))
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(HugeIcons.Delete01, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                                    Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        // 描述
                        Text(desc, style = MaterialTheme.typography.bodySmall,
                            color = if (name in settings.customDomainDescriptions) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)

                        // 展开内容
                        AnimatedVisibility(expanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                // 关键词标签
                                if (keywords.isNotEmpty()) {
                                    Text("自动匹配关键词:", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        keywords.take(8).forEach { kw ->
                                            SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) })
                                        }
                                        if (keywords.size > 8) {
                                            Text("+${keywords.size - 8}", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))

                                // 手动覆盖到此域的工具
                                Text("手动归入此类的工具:", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                                if (overrideTools.isEmpty()) {
                                    Text("  (无)", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    overrideTools.forEach { (toolName, _) ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text("  $toolName", style = MaterialTheme.typography.bodySmall)
                                            IconButton(onClick = {
                                                val m = settings.toolDomainOverrides.toMutableMap(); m.remove(toolName)
                                                vm.updateSettings(settings.copy(toolDomainOverrides = m))
                                            }, modifier = Modifier.size(20.dp)) {
                                                Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }

                                // 添加工具到底这里
                                TextButton(onClick = { showAddOverride = true }) {
                                    Icon(HugeIcons.Add01, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加工具到此类别")
                                }
                            }
                        }
                    }
                }

                // 编辑描述对话框
                if (showEdit) {
                    AlertDialog(
                        onDismissRequest = { showEdit = false },
                        title = { Text("编辑 [$name]") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("AI 看到: 当用户需要${editText}时，调用 use_domain(\"$name\")",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(value = editText, onValueChange = { editText = it },
                                    label = { Text("触发描述") }, modifier = Modifier.fillMaxWidth(),
                                    singleLine = false, maxLines = 3)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val m = settings.customDomainDescriptions.toMutableMap(); m[name] = editText
                                vm.updateSettings(settings.copy(customDomainDescriptions = m))
                                showEdit = false
                            }) { Text("保存") }
                        },
                        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } }
                    )
                }
            }
        }
    }

    // 新建分类对话框
    if (showNewDomain) {
        var newName by remember { mutableStateOf("") }
        var newDesc by remember { mutableStateOf("") }
        var newKw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDomain = false },
            title = { Text("新建分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(value = newDesc, onValueChange = { newDesc = it }, label = { Text("触发描述") }, maxLines = 2)
                    OutlinedTextField(value = newKw, onValueChange = { newKw = it }, label = { Text("关键词(逗号分隔)") },
                        supportingText = { Text("匹配工具名/描述中的关键词来触发自动分类") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        val kws = newKw.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                        vm.updateSettings(settings.copy(customDomains = settings.customDomains + CustomDomain(newName.trim(), newDesc.trim(), kws)))
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
        val allNames = ToolDomain.entries.map { it.label } + settings.customDomains.map { it.name }
        AlertDialog(
            onDismissRequest = { showAddOverride = false },
            title = { Text("手动归入工具") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = toolName, onValueChange = { toolName = it }, label = { Text("工具名") },
                        supportingText = { Text("如 list_files, search_web, mcp__github__xxx") })
                    Text("可选类别: ${allNames.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = targetDomain, onValueChange = { targetDomain = it }, label = { Text("目标类别") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (toolName.isNotBlank() && targetDomain.isNotBlank()) {
                        val m = settings.toolDomainOverrides.toMutableMap(); m[toolName.trim()] = targetDomain.trim()
                        vm.updateSettings(settings.copy(toolDomainOverrides = m))
                        showAddOverride = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddOverride = false }) { Text("取消") } }
        )
    }
}
