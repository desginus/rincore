package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.datastore.CustomDomain
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/** 轻量预览工具 */
data class ToolPreview(val name: String, val description: String)

@Composable
fun SettingDomainPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: SkillManager = koinInject()
    var showNewDomain by remember { mutableStateOf(false) }
    var showAddOverride by remember { mutableStateOf(false) }

    // 构建路由预览器
    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions,
            settings.customDomains, settings.customDomainKeywords)
    }

    // 收集所有可预览工具: Skill + MCP
    val previewTools = remember(settings) {
        val list = mutableListOf<ToolPreview>()
        try {
            skillManager.listSkills().forEach { s ->
                list.add(ToolPreview("skill:${s.name}", s.description))
            }
        } catch (_: Exception) {}
        for (srv in settings.mcpServers) {
            for (t in srv.commonOptions.tools.filter { it.enable }) {
                list.add(ToolPreview("mcp:${srv.commonOptions.name}:${t.name}", t.description ?: ""))
            }
        }
        list
    }

    // 按域分类预览工具
    val domainPreviewMap = remember(previewTools, settings) {
        previewTools.groupBy { router.classifyPreview(it.name, it.description) }
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("工具域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建") }
                },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddOverride = true }) {
                Icon(HugeIcons.Edit01, "覆盖")
            }
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val mcpTotal = settings.mcpServers.sumOf { s -> s.commonOptions.tools.count { it.enable } }
                val skillTotal = skillManager.listSkills().size
                Text("${ToolDomain.entries.size + settings.customDomains.size}个类别 · ${previewTools.size}个工具 (MCP:$mcpTotal Skill:$skillTotal) · ${settings.toolDomainOverrides.size}个覆盖",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val allDomains = ToolDomain.entries.map { it.label to (settings.customDomainDescriptions[it.label] ?: it.triggerDescription) } +
                settings.customDomains.map { it.name to (settings.customDomainDescriptions[it.name] ?: it.description) }
            val customNames = settings.customDomains.map { it.name }.toSet()

            itemsIndexed(allDomains) { _, (name, desc) ->
                val isCustom = name in customNames
                val domainTools = domainPreviewMap[name].orEmpty()
                val overrideTools = settings.toolDomainOverrides.filter { it.value == name }
                val kws = router.getKeywords(name)
                var expanded by remember { mutableStateOf(false) }
                var showEdit by remember { mutableStateOf(false) }
                var editDesc by remember(desc) { mutableStateOf(desc) }
                var editKws by remember(kws) { mutableStateOf(kws.joinToString(", ")) }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("[$name]", fontWeight = FontWeight.Bold,
                                color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Row {
                                IconButton(onClick = { showEdit = true }, modifier = Modifier.size(24.dp)) {
                                    Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp))
                                }
                                // 重置按钮（内置域有自定义描述/关键词时显示）
                                val hasCustom = name in settings.customDomainDescriptions || name in settings.customDomainKeywords
                                if (!isCustom && hasCustom) {
                                    IconButton(onClick = {
                                        var s = settings
                                        if (name in s.customDomainDescriptions) {
                                            s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it.remove(name) })
                                        }
                                        if (name in s.customDomainKeywords) {
                                            s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it.remove(name) })
                                        }
                                        vm.updateSettings(s)
                                    }, modifier = Modifier.size(24.dp)) {
                                        Icon(HugeIcons.Refresh01, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                    }
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

                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${domainTools.size}工具${if(overrideTools.isNotEmpty())" +${overrideTools.size}覆盖" else ""}", style = MaterialTheme.typography.labelSmall)

                        AnimatedVisibility(expanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                // 关键词
                                Text("关键词:", style = MaterialTheme.typography.labelSmall)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    kws.take(10).forEach { kw ->
                                        SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) })
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider()

                                // Skill 和 MCP 工具
                                if (domainTools.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("自动归入工具:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    domainTools.take(15).forEach { t ->
                                        Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    }
                                    if (domainTools.size > 15) Text("  ...还有${domainTools.size - 15}个", style = MaterialTheme.typography.bodySmall)
                                }

                                // 手动覆盖
                                if (overrideTools.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("手动覆盖:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    overrideTools.forEach { (tn, _) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("  $tn", style = MaterialTheme.typography.bodySmall)
                                            IconButton(onClick = {
                                                val m = settings.toolDomainOverrides.toMutableMap(); m.remove(tn)
                                                vm.updateSettings(settings.copy(toolDomainOverrides = m))
                                            }, modifier = Modifier.size(20.dp)) {
                                                Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }

                                if (domainTools.isEmpty() && overrideTools.isEmpty()) {
                                    Text("此类别暂无工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                TextButton(onClick = { showAddOverride = true }) {
                                    Icon(HugeIcons.Add01, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("添加工具覆盖")
                                }
                            }
                        }
                    }
                }

                // 编辑对话框
                if (showEdit) {
                    AlertDialog(
                        onDismissRequest = { showEdit = false },
                        title = { Text("编辑 [$name]") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = editDesc, onValueChange = { editDesc = it },
                                    label = { Text("触发描述") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                                OutlinedTextField(value = editKws, onValueChange = { editKws = it },
                                    label = { Text("关键词(逗号分隔)") },
                                    supportingText = { Text("工具名/描述匹配这些词时自动归入此类") })
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                var s = settings
                                val descM = s.customDomainDescriptions.toMutableMap()
                                descM[name] = editDesc; s = s.copy(customDomainDescriptions = descM)
                                val parsed = editKws.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                                val kwM = s.customDomainKeywords.toMutableMap()
                                kwM[name] = parsed; s = s.copy(customDomainKeywords = kwM)
                                vm.updateSettings(s)
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
        var nn by remember { mutableStateOf("") }
        var nd by remember { mutableStateOf("") }
        var nk by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDomain = false },
            title = { Text("新建分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(nn, { nn = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(nd, { nd = it }, label = { Text("触发描述") }, maxLines = 2)
                    OutlinedTextField(nk, { nk = it }, label = { Text("关键词") }, supportingText = { Text("逗号分隔") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (nn.isNotBlank()) {
                        val kws = nk.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                        vm.updateSettings(settings.copy(customDomains = settings.customDomains + CustomDomain(nn.trim(), nd.trim(), kws)))
                        showNewDomain = false
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewDomain = false }) { Text("取消") } }
        )
    }

    // 添加覆盖对话框
    if (showAddOverride) {
        var tn by remember { mutableStateOf("") }
        var td by remember { mutableStateOf("") }
        val allNames = ToolDomain.entries.map { it.label } + settings.customDomains.map { it.name }
        AlertDialog(
            onDismissRequest = { showAddOverride = false },
            title = { Text("手动归入工具") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(tn, { tn = it }, label = { Text("工具名") }, supportingText = { Text("完整工具名，如 list_files") })
                    Text("类别: ${allNames.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(td, { td = it }, label = { Text("目标类别") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tn.isNotBlank() && td.isNotBlank()) {
                        val m = settings.toolDomainOverrides.toMutableMap(); m[tn.trim()] = td.trim()
                        vm.updateSettings(settings.copy(toolDomainOverrides = m))
                        showAddOverride = false
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddOverride = false }) { Text("取消") } }
        )
    }
}
