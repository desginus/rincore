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

/** 将扁平分类结果转换为嵌套结构。大型MCP工具集显示为父域+子域。 */
private fun buildNestedDomains(
    flatMap: Map<String, List<ToolPreview>>,
    allTools: List<ToolPreview>,
    router: ToolRouter,
): List<Pair<String, Map<String, List<ToolPreview>>?>> {
    val result = mutableListOf<Pair<String, Map<String, List<ToolPreview>>?>>()
    // 检测 MCP 工具集
    val mcpByServer = allTools.filter { it.name.startsWith("mcp__") }.groupBy {
        it.name.removePrefix("mcp__").split("__").firstOrNull() ?: "unknown"
    }
    val processedServers = mutableSetOf<String>()
    for ((server, sTools) in mcpByServer) {
        if (sTools.size >= 8) {
            processedServers.add(server)
            val subs = mutableMapOf<String, MutableList<ToolPreview>>()
            for (t in sTools) {
                val sub = classifyMcpSubdomainStatic(t.name, t.description)
                    subs.getOrPut(sub) { mutableListOf() }.add(t)
            }
            result.add(server to subs)
        }
    }
    // 其他域
    for ((domain, dTools) in flatMap.entries.sortedBy { (k, _) -> k }) {
        if (domain == "system" || domain == "uncategorized") continue
        val serverCheck = dTools.firstOrNull()?.name?.let { n ->
            if (n.startsWith("mcp__")) n.removePrefix("mcp__").split("__").firstOrNull() else null
        }
        if (serverCheck != null && serverCheck in processedServers) continue
        result.add(domain to null)
    }
    return result
}

private fun classifyMcpSubdomainStatic(name: String, desc: String): String {
    val text = "${name} ${desc}".lowercase()
    return when {
        text.contains("create") || text.contains("add_") || text.contains("new_") -> "创建"
        text.contains("get_") || text.contains("query") || text.contains("list_") || text.contains("find_") -> "查询"
        text.contains("set_") || text.contains("update") || text.contains("modify") || text.contains("config") -> "设置"
        text.contains("delete") || text.contains("remove") || text.contains("clear") || text.contains("destroy") -> "删除"
        text.contains("apply") || text.contains("simulate") || text.contains("compute") || text.contains("calculate") -> "计算"
        text.contains("load") || text.contains("save") || text.contains("export") || text.contains("import") -> "数据"
        else -> "其他"
    }
}

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

    if (showToolList) { SettingToolListPage(settings, vm, { showToolList = false }); return }

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains, settings.customDomainKeywords)
    }

    val previewTools = remember(settings) {
        val list = mutableListOf<ToolPreview>()
        try { skillManager.listSkills().forEach { s -> list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}")) } } catch (_: Exception) {}
        for (srv in settings.mcpServers) for (t in srv.commonOptions.tools.filter { it.enable }) list.add(ToolPreview("mcp__${srv.commonOptions.name}__${t.name}", t.description ?: ""))
        list
    }
    val flatDomainMap: Map<String, List<ToolPreview>> = remember(previewTools, settings) { previewTools.groupBy { router.classifyPreview(it.name, settings.toolDescriptionOverrides[it.name] ?: it.description) } }
    val nestedDomains = remember(flatDomainMap, previewTools, settings) { buildNestedDomains(flatDomainMap, previewTools, router) }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(title = { Text("域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = { showToolList = true }) { Icon(HugeIcons.View, "工具列表") }
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建") }
                },
                colors = CustomColors.topBarColors)
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val mcpLarge = previewTools.filter { it.name.startsWith("mcp__") }.groupBy { it.name.removePrefix("mcp__").split("__").first() }.count { it.value.size >= 8 }
                Text("${nestedDomains.size}个域 · ${previewTools.size}个工具 · ${mcpLarge}个大型MCP工具集 · ${settings.toolDomainOverrides.size}个覆盖 · ${settings.toolDescriptionOverrides.size}个描述修改",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            itemsIndexed(nestedDomains) { _, (name, subs) ->
                val isCustom = name in settings.customDomains.map { it.name }
                val desc = settings.customDomainDescriptions[name] ?: router.getTriggerDescription(name)
                val overrideTools = settings.toolDomainOverrides.filter { it.value.startsWith(name) }
                val isMCPParent = subs != null
                var expanded by remember { mutableStateOf(false) }
                var showEdit by remember { mutableStateOf(false) }
                var edDesc by remember(desc) { mutableStateOf(desc) }
                val kws = router.getKeywords(name)
                var edKws by remember(kws) { mutableStateOf(kws.joinToString(", ")) }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("[${name}]", fontWeight = FontWeight.Bold, color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                if (isMCPParent) Text(" (${subs.size}子域)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Row {
                                IconButton(onClick = { showEdit = true }, modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp)) }
                                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(24.dp)) {
                                    Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        AnimatedVisibility(expanded) {
                            Column(Modifier.padding(top = 8.dp)) {
                                if (isMCPParent) {
                                    Text("子域:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    subs!!.forEach { (sub, subTools) ->
                                        val subDesc = router.getTriggerDescription("$name/$sub")
                                        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                            Column(Modifier.padding(8.dp)) {
                                                Text("[$name/$sub] ${subTools.size}个工具", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                Text(subDesc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                subTools.take(5).forEach { t -> Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                                if (subTools.size > 5) Text("  ...还有${subTools.size - 5}个", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                } else {
                                    val domainTools = flatDomainMap[name].orEmpty()
                                    Text("功能触发条件:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        kws.take(8).forEach { kw -> SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) }) }
                                    }
                                    if (domainTools.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                                        Text("工具(${domainTools.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        domainTools.take(10).forEach { t -> Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                    }
                                }
                                TextButton(onClick = { showToolList = true }) { Icon(HugeIcons.Edit01, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("去工具列表操作") }
                            }
                        }
                    }
                }

                if (showEdit) {
                    AlertDialog(onDismissRequest = { showEdit = false }, title = { Text("编辑 [$name]") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(edDesc, { edDesc = it }, label = { Text("触发描述") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            OutlinedTextField(edKws, { edKws = it }, label = { Text("触发条件") }, supportingText = { Text("逗号分隔") })
                        }},
                        confirmButton = { TextButton(onClick = {
                            var s = settings
                            s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it[name] = edDesc })
                            s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it[name] = edKws.split(",","，").map{it.trim().lowercase()}.filter{it.isNotBlank()} })
                            vm.updateSettings(s); showEdit = false
                        }) { Text("保存") } },
                        dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } }
                    )
                }
            }
        }
    }

    if (showNewDomain) {
        var nn by remember { mutableStateOf("") }; var nd by remember { mutableStateOf("") }; var nk by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showNewDomain = false }, title = { Text("新建域") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nn, { nn = it }, label = { Text("名称，如 物理/力学") })
                OutlinedTextField(nd, { nd = it }, label = { Text("触发描述") }, maxLines = 2)
                OutlinedTextField(nk, { nk = it }, label = { Text("触发条件") })
            }},
            confirmButton = { TextButton(onClick = {
                if (nn.isNotBlank()) vm.updateSettings(settings.copy(customDomains = settings.customDomains + CustomDomain(nn.trim(), nd.trim(), nk.split(",","，").map{it.trim().lowercase()}.filter{it.isNotBlank()})))
                showNewDomain = false
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showNewDomain = false }) { Text("取消") } }
        )
    }
}
