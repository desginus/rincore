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

data class ToolPreview(val name: String, val description: String)

@Composable
fun SettingDomainPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: SkillManager = koinInject()
    var showNewDomain by remember { mutableStateOf(false) }
    var showToolList by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf<String?>(null) }

    if (showToolList) {
        SettingToolListPage(settings = settings, vm = vm, onBack = { showToolList = false })
        return
    }

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions,
            settings.customDomains, settings.customDomainKeywords)
    }

    val previewTools = remember(settings) {
        val list = mutableListOf<ToolPreview>()
        try { skillManager.listSkills().forEach { s -> list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}")) } } catch (_: Exception) {}
        for (srv in settings.mcpServers) for (t in srv.commonOptions.tools.filter { it.enable }) list.add(ToolPreview("mcp__${srv.commonOptions.name}__${t.name}", t.description ?: ""))
        list
    }

    val domainPreviewMap = remember(previewTools, settings) { previewTools.groupBy { router.classifyPreview(it.name, it.description) } }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = { showToolList = true }) { Icon(HugeIcons.View, "工具列表") }
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建域") }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("${ToolDomain.entries.size + settings.customDomains.size}个域 · ${previewTools.size}个工具 · ${settings.toolDomainOverrides.size}个覆盖 · ${settings.toolDescriptionOverrides.size}个描述修改",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            val allDomains = ToolDomain.entries.map { it.label } + settings.customDomains.map { it.name }
            val customNames = settings.customDomains.map { it.name }.toSet()

            itemsIndexed(allDomains) { _, name ->
                val isCustom = name in customNames
                val desc = settings.customDomainDescriptions[name] ?: router.getTriggerDescription(name)
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
                                IconButton(onClick = { showEdit = true }, modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp)) }
                                val hasCustom = name in settings.customDomainDescriptions || name in settings.customDomainKeywords
                                if (!isCustom && hasCustom) {
                                    IconButton(onClick = {
                                        var s = settings
                                        if (name in s.customDomainDescriptions) s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it.remove(name) })
                                        if (name in s.customDomainKeywords) s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it.remove(name) })
                                        vm.updateSettings(s)
                                    }, modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Refresh01, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
                                }
                                // 删除按钮（所有域都有）
                                IconButton(onClick = {
                                    if (isCustom) deleteConfirm = name
                                    else {
                                        // 内置域：清除所有自定义
                                        var s = settings
                                        s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it.remove(name) })
                                        s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it.remove(name) })
                                        s = s.copy(toolDomainOverrides = s.toolDomainOverrides.filter { it.value != name })
                                        vm.updateSettings(s)
                                    }
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(if (isCustom) HugeIcons.Delete01 else HugeIcons.Refresh01, null,
                                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                }
                                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                                    Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${domainTools.size}个工具 + ${overrideTools.size}个覆盖", style = MaterialTheme.typography.labelSmall)

                        AnimatedVisibility(expanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                Text("功能触发条件:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    kws.take(8).forEach { kw -> SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) }) }
                                }
                                if (domainTools.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                                    Text("自动归入工具:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    domainTools.take(10).forEach { t -> Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                    if (domainTools.size > 10) Text("  ...还有${domainTools.size - 10}个", style = MaterialTheme.typography.bodySmall)
                                }
                                if (overrideTools.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("手动覆盖:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    overrideTools.forEach { (tn, _) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("  $tn", style = MaterialTheme.typography.bodySmall)
                                            IconButton(onClick = {
                                                val m = settings.toolDomainOverrides.toMutableMap(); m.remove(tn)
                                                vm.updateSettings(settings.copy(toolDomainOverrides = m))
                                            }, modifier = Modifier.size(20.dp)) { Icon(HugeIcons.Cancel01, null, modifier = Modifier.size(12.dp)) }
                                        }
                                    }
                                }
                                TextButton(onClick = { showToolList = true }) {
                                    Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp)); Text("去工具列表移动/修改")
                                }
                            }
                        }
                    }
                }

                if (showEdit) {
                    AlertDialog(
                        onDismissRequest = { showEdit = false }, title = { Text("编辑 [$name]") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(editDesc, { editDesc = it }, label = { Text("触发描述") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                                OutlinedTextField(editKws, { editKws = it }, label = { Text("功能触发条件") }, supportingText = { Text("逗号分隔，按功能意图编写") })
                            }
                        },
                        confirmButton = { TextButton(onClick = {
                            var s = settings
                            s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it[name] = editDesc })
                            s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it[name] = editKws.split(",", "，").map{it.trim().lowercase()}.filter{it.isNotBlank()} })
                            vm.updateSettings(s); showEdit = false
                        }) { Text("保存") } },
                        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } }
                    )
                }
            }
        }
    }

    // 新建域
    if (showNewDomain) {
        var nn by remember { mutableStateOf("") }; var nd by remember { mutableStateOf("") }; var nk by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showNewDomain = false }, title = { Text("新建域") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nn, { nn = it }, label = { Text("名称") })
                OutlinedTextField(nd, { nd = it }, label = { Text("触发描述") }, maxLines = 2)
                OutlinedTextField(nk, { nk = it }, label = { Text("触发条件") }, supportingText = { Text("逗号分隔") })
            }},
            confirmButton = { TextButton(onClick = {
                if (nn.isNotBlank()) { vm.updateSettings(settings.copy(customDomains = settings.customDomains + CustomDomain(nn.trim(), nd.trim(), nk.split(",", "，").map{it.trim().lowercase()}.filter{it.isNotBlank()}))) }
                showNewDomain = false
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showNewDomain = false }) { Text("取消") } }
        )
    }

    // 删除自定义域确认
    if (deleteConfirm != null) {
        AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("删除分类") },
            text = { Text("删除「${deleteConfirm}」分类？此操作不可恢复。该域下的工具覆盖将失效。") },
            confirmButton = { TextButton(onClick = {
                val name = deleteConfirm!!
                vm.updateSettings(settings.copy(
                    customDomains = settings.customDomains.filter { it.name != name },
                    toolDomainOverrides = settings.toolDomainOverrides.filter { it.value != name },
                    customDomainDescriptions = settings.customDomainDescriptions.toMutableMap().also { it.remove(name) },
                    customDomainKeywords = settings.customDomainKeywords.toMutableMap().also { it.remove(name) },
                ))
                deleteConfirm = null
            }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("取消") } }
        )
    }
}
