package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.routing.ToolClassifier
import kotlinx.coroutines.launch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.mcp.McpManager

data class ToolPreview(val name: String, val description: String)

private fun buildNestedDomains(
    flatMap: Map<String, List<ToolPreview>>,
    allTools: List<ToolPreview>,
    router: ToolRouter,
): List<Pair<String, Map<String, MutableList<ToolPreview>>?>> {
    // ToolDomain 父子关系
    val domainChildren = mutableMapOf<String, MutableList<String>>()
    val allTopLevel = mutableSetOf<String>()
    for (td in ToolDomain.entries) {
        if (td.parent != null) domainChildren.getOrPut(td.parent) { mutableListOf() }.add(td.label)
        else allTopLevel.add(td.label)
    }
    val childKeys = domainChildren.values.flatten().toSet()

    // 排除 system/uncategorized/过期覆盖
    val customNames = router.customDomains.map { it.name }.toSet()
    val result = mutableListOf<Pair<String, Map<String, MutableList<ToolPreview>>?>>()
    val processed = mutableSetOf<String>()

    // 先处理子域（它们不单独出现，会被归入父域）
    for (parent in allTopLevel.sorted()) {
        val myTools = flatMap[parent].orEmpty()
        val childDomains = domainChildren[parent].orEmpty()
        val allMyTools = myTools.toMutableList()
        childDomains.forEach { allMyTools.addAll(flatMap[it].orEmpty()) }
        if (allMyTools.isEmpty()) continue  // 无工具不展示

        if (childDomains.isNotEmpty()) {
            val subMap = mutableMapOf<String, MutableList<ToolPreview>>()
            if (myTools.isNotEmpty()) subMap[parent] = myTools.toMutableList()
            childDomains.forEach { child ->
                flatMap[child]?.let { subMap[child] = it.toMutableList() }
            }
            result.add(parent to subMap)
        } else {
            result.add(parent to null)
        }
        processed.add(parent)
    }

    // 自定义域（不在 ToolDomain 中的）
    for ((key, tools) in flatMap.entries.sortedBy { it.key }) {
        if (key in processed || key in childKeys || key == "system" || key == "uncategorized") continue
        result.add(key to null)
    }

    return result
}

@Composable
fun SettingDomainPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: SkillManager = koinInject()
    val providerManager: ProviderManager = koinInject()
    val localTools: LocalTools = koinInject()
    val mcpManager: McpManager = koinInject()

    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    var isClassifying by remember { mutableStateOf(false) }
    var classifyLog by remember { mutableStateOf("") }
    var showClassifierPrompt by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var autoChecked by remember { mutableStateOf(false) }
    var editClassifierPrompt by remember(settings.classifierPrompt) { mutableStateOf(settings.classifierPrompt.ifBlank { ToolClassifier.DEFAULT_PROMPT }) }
    var autoClassified by remember { mutableStateOf(false) }

    // MCP工具变化时自动分类（仅在域管理页面打开时触发）
    LaunchedEffect(settings.mcpServers.size) {
        if (autoClassified || isClassifying) return@LaunchedEffect
        // 统计未分类的MCP工具
        val totalMcpTools = settings.mcpServers.sumOf { s -> s.commonOptions.tools.count { it.enable } }
        val classifiedCount = settings.toolDomainOverrides.count { it.key.startsWith("mcp__") }
        val unclassified = totalMcpTools - classifiedCount
        if (unclassified >= 3) {
            classifyLog = "检测到${unclassified}个新MCP工具，自动分类中..."
            autoClassified = true
            isClassifying = true
        }
    }
    var showNewDomain by remember { mutableStateOf(false) }
    var showToolList by remember { mutableStateOf(false) }
    var editingDomain by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }
    var editKws by remember { mutableStateOf("") }

    if (showToolList) { SettingToolListPage(settings, vm, { showToolList = false }); return }

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains,
            settings.customDomainKeywords, settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains)
    }

    val previewTools: List<ToolPreview> = run {
        val list = mutableListOf<ToolPreview>()
        // 1. 内置本地工具（全部列举，与ChatService一致）
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
        // 2. Skill工具
        try { skillManager.listSkills().forEach { s -> list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}")) } } catch (_: Exception) {}
        // 3. MCP工具（从McpManager获取实际工具，不是配置快照）
        try {
            mcpManager.getAllAvailableTools().forEach { (_, serverName, tool) ->
                list.add(ToolPreview("mcp__${serverName}__${tool.name}", tool.description ?: ""))
            }
        } catch (_: Exception) {}
        list
    }
    val flatDomainMap: Map<String, List<ToolPreview>> = remember(previewTools, settings.toolDescriptionOverrides, settings.toolDomainOverrides, revision) { previewTools.groupBy { router.classifyPreview(it.name, settings.toolDescriptionOverrides[it.name] ?: it.description) } }
    val nestedDomains = remember(flatDomainMap, settings.hiddenDomains, settings.removedBuiltinDomains) { buildNestedDomains(flatDomainMap, previewTools, router) }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(title = { Text("域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = {
                    // 清理无效覆盖 + 强制刷新
                    val validLabels = ToolDomain.entries.map { it.label }.toSet() + settings.customDomains.map { it.name }.toSet()
                    val cleaned = settings.toolDomainOverrides.filterValues { it in validLabels }
                    val removed = settings.toolDomainOverrides.size - cleaned.size
                    vm.updateSettings(settings.copy(toolDomainOverrides = cleaned))
                    revision++
                    classifyLog = "已刷新 (${previewTools.size}个工具, ${nestedDomains.size}个域)"
                    if (removed > 0) classifyLog += " · 清理${removed}个过期覆盖"
                    isClassifying = false
                }) { Icon(HugeIcons.Refresh01, "同步") }
                    IconButton(onClick = { showClassifierPrompt = true }) { Icon(HugeIcons.AiMagic, "分类") }
                    IconButton(onClick = { showToolList = true }) { Icon(HugeIcons.View, "工具列表") }
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建") }
                }, colors = CustomColors.topBarColors)
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isClassifying) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            if (classifyLog.isNotEmpty()) {
                item { Text(classifyLog, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            item {
                val mcpLarge = previewTools.filter { it.name.startsWith("mcp__") }.groupBy { it.name.removePrefix("mcp__").split("__").first() }.count { it.value.size >= 8 }
                Text("${nestedDomains.size}个域 · ${previewTools.size}个工具 · ${mcpLarge}个大型MCP工具集 · ${settings.toolDomainOverrides.size}个覆盖 · ${settings.toolDescriptionOverrides.size}个描述修改 · ${settings.hiddenDomains.size}个隐藏",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            itemsIndexed(nestedDomains) { _, (domain, subs) ->
                val displayName = settings.domainNameOverrides[domain] ?: domain
                val isHidden = domain in settings.hiddenDomains
                val isCustom = domain in settings.customDomains.map { it.name }
                val desc = settings.customDomainDescriptions[domain] ?: router.getTriggerDescription(domain)
                val overrideTools = settings.toolDomainOverrides.filter { it.value.startsWith(domain) }
                val isMCPParent = subs != null
                var expanded by remember { mutableStateOf(false) }

                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isHidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("[${displayName}]", fontWeight = FontWeight.Bold, color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                if (isMCPParent) Text(" (${subs!!.size}子域)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                if (isHidden) Text(" [已隐藏]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row {
                                // 编辑按钮
                                IconButton(onClick = {
                                    editingDomain = domain
                                    editName = displayName
                                    editDesc = desc
                                    val kws = router.getKeywords(domain)
                                    editKws = kws.joinToString(", ")
                                }, modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp)) }
                                // 隐藏/显示
                                IconButton(onClick = {
                                    val hs = settings.hiddenDomains.toMutableSet()
                                    if (isHidden) hs.remove(domain) else hs.add(domain)
                                    vm.updateSettings(settings.copy(hiddenDomains = hs))
                                }, modifier = Modifier.size(24.dp)) { Icon(if (isHidden) HugeIcons.ViewOff else HugeIcons.View, null, modifier = Modifier.size(14.dp)) }
                                // 删除
                                IconButton(onClick = {
                                    deleteConfirm = domain
                                }, modifier = Modifier.size(24.dp)) {
                                    Icon(HugeIcons.Delete01, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                }
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
                                        val subName = sub.substringAfter("/", sub)
                                        val subDisplay = settings.domainNameOverrides.getOrElse(sub) {
                                            settings.domainNameOverrides["$domain/$subName"] ?: subName
                                        }
                                        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("[$subDisplay] ${subTools.size}个工具", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                    subTools.take(5).forEach { t ->
                                                        Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                    if (subTools.size > 5) Text("  ... 等${subTools.size}个", style = MaterialTheme.typography.bodySmall)
                                                }
                                                IconButton(onClick = {
                                                    editingDomain = sub
                                                    editName = subName
                                                    editDesc = settings.customDomainDescriptions[sub] ?: ""
                                                    editKws = router.getKeywords(sub).joinToString(", ")
                                                }, modifier = Modifier.size(24.dp)) {
                                                    Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val domainTools = flatDomainMap[domain].orEmpty()
                                    val kws = router.getKeywords(domain)
                                    Text("触发条件:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        kws.take(8).forEach { kw -> SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) }) }
                                    }
                                    if (domainTools.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                                        Text("工具(${domainTools.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        domainTools.take(8).forEach { t ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                                                IconButton(onClick = { showToolList = true }, modifier = Modifier.size(18.dp)) {
                                                    Icon(HugeIcons.Edit01, null, modifier = Modifier.size(10.dp))
                                                }
                                            }
                                        }
                                        if (domainTools.size > 8) Text("  ...还有${domainTools.size - 8}个", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                TextButton(onClick = { showToolList = true }) {
                                    Icon(HugeIcons.Edit01, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("去工具列表详细操作")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑域对话框
    if (editingDomain != null) {
        val domain = editingDomain!!
        AlertDialog(onDismissRequest = { editingDomain = null }, title = { Text("编辑域") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editName, { editName = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(editDesc, { editDesc = it }, label = { Text("触发描述") }, maxLines = 3)
                OutlinedTextField(editKws, { editKws = it }, label = { Text("触发条件(逗号分隔)") })
            }},
            confirmButton = { TextButton(onClick = {
                var s = settings
                s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it[domain] = editDesc })
                s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it[domain] = editKws.split(",","，").map{it.trim().lowercase()}.filter{it.isNotBlank()} })
                if (editName != domain && editName.isNotBlank()) {
                    s = s.copy(domainNameOverrides = s.domainNameOverrides.toMutableMap().also { it[domain] = editName })
                } else {
                    s = s.copy(domainNameOverrides = s.domainNameOverrides.toMutableMap().also { it.remove(domain) })
                }
                vm.updateSettings(s); editingDomain = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editingDomain = null }) { Text("取消") } }
        )
    }

    // 新建域
    if (showNewDomain) {
        var nn by remember { mutableStateOf("") }; var nd by remember { mutableStateOf("") }; var nk by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showNewDomain = false }, title = { Text("新建域") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nn, { nn = it }, label = { Text("内部名称(英文)") }, supportingText = { Text("如 physics/sim，用作 use_domain 参数") })
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

    // 删除确认对话框
    if (deleteConfirm != null) {
        val domain = deleteConfirm!!
        val isCustom = domain in settings.customDomains.map { it.name }
        AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("删除域") },
            text = { Text(if (isCustom) "删除自定义域「${domain}」？此操作不可恢复。该域下的工具覆盖将一起清除。"
                     else "删除内置域「${domain}」？它将从场景地图中消失。可通过新建域恢复。") },
            confirmButton = { TextButton(onClick = {
                var s = settings
                if (isCustom) {
                    s = s.copy(customDomains = s.customDomains.filter { it.name != domain })
                } else {
                    s = s.copy(removedBuiltinDomains = s.removedBuiltinDomains + domain)
                }
                s = s.copy(
                    toolDomainOverrides = s.toolDomainOverrides.filter { !it.value.startsWith(domain) },
                    customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it.remove(domain) },
                    customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it.remove(domain) },
                    domainNameOverrides = s.domainNameOverrides.toMutableMap().also { it.remove(domain) },
                )
                vm.updateSettings(s)
                deleteConfirm = null
            }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("取消") } }
        )
    }

    // 自动分类按钮逻辑
    LaunchedEffect(isClassifying) {
        if (!isClassifying) return@LaunchedEffect
        classifyLog = "正在调用 AI 分类..."
        try {
            val toolList = previewTools.map { it.name to (settings.toolDescriptionOverrides[it.name] ?: it.description) }
            val modelId = settings.routingModelId ?: settings.chatModelId
            val providerSetting = settings.providers.find { p -> p.models.any { m -> m.id == modelId } }
            val model = providerSetting?.models?.find { it.id == modelId }
            if (providerSetting == null || model == null) {
                classifyLog = "错误: 未找到分类模型或提供商"
                isClassifying = false
                return@LaunchedEffect
            }
            val providerName = when (providerSetting) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> "openai"
                    is me.rerere.ai.provider.ProviderSetting.Google -> "google"
                    is me.rerere.ai.provider.ProviderSetting.Claude -> "claude"
                    else -> error("不支持的提供商类型")
                }
                @Suppress("UNCHECKED_CAST")
                val provider = providerManager.getProvider(providerName) as Provider<ProviderSetting>
            ToolClassifier.classify(toolList, model, provider, providerSetting, settings.classifierPrompt)
                .onSuccess { result ->
                    val m = settings.toolDomainOverrides.toMutableMap()
                    result.forEach { (tool, domain) -> m[tool] = domain }
                    vm.updateSettings(settings.copy(toolDomainOverrides = m))
                    classifyLog = "分类完成: ${result.size}个工具已归类"
                }
                .onFailure { e ->
                    classifyLog = "分类失败: ${e.message?.take(200) ?: "未知错误"}"
                }
        } catch (e: Exception) {
            classifyLog = "异常: ${e.message?.take(200) ?: ""}"
        }
        isClassifying = false
        autoClassified = false
    }

    // 分类提示词编辑
    if (showClassifierPrompt) {
        AlertDialog(onDismissRequest = { showClassifierPrompt = false }, title = { Text("分类提示词") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    Text("此提示词发送给 AI 模型，用于自动将工具分配到合适的场景。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(editClassifierPrompt, { editClassifierPrompt = it }, modifier = Modifier.fillMaxWidth().weight(1f), maxLines = 20)
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.updateSettings(settings.copy(classifierPrompt = editClassifierPrompt))
                showClassifierPrompt = false
                // 触发分类
                isClassifying = true
            }) { Text("保存并分类") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        editClassifierPrompt = ToolClassifier.DEFAULT_PROMPT
                        vm.updateSettings(settings.copy(classifierPrompt = ""))
                    }) { Text("恢复默认") }
                    TextButton(onClick = { showClassifierPrompt = false }) { Text("取消") }
                }
            }
        )
    }
}
