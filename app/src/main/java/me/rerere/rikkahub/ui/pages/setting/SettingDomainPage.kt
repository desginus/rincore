package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.foundation.layout.heightIn

data class ToolPreview(val name: String, val description: String)

/**
 * 构建嵌套域列表——严格只展示 ToolDomain 顶级域 + 自定义域。
 * 不展示 system / uncategorized / 任何过期覆盖产生的幽灵域。
 */
private fun buildNestedDomains(
    flatMap: Map<String, List<ToolPreview>>,
    router: ToolRouter,
): List<Pair<String, Map<String, MutableList<ToolPreview>>?>> {
    val domainChildren = mutableMapOf<String, MutableList<String>>()
    val allTopLevel = linkedSetOf<String>()

    for (td in ToolDomain.entries) {
        if (td.parent != null) {
            domainChildren.getOrPut(td.parent) { mutableListOf() }.add(td.label)
        } else {
            allTopLevel.add(td.label)
        }
    }
    // 自定义域作为顶级域追加
    for (cd in router.customDomains) {
        allTopLevel.add(cd.name)
    }

    // 过滤隐藏和已删除的域
    val visibleTopLevel = allTopLevel.filter { it !in router.hiddenDomains && it !in router.removedBuiltinDomains }

    val result = mutableListOf<Pair<String, Map<String, MutableList<ToolPreview>>?>>()

    for (parent in visibleTopLevel) {
        val myTools = flatMap[parent].orEmpty()
        val childDomains = domainChildren[parent].orEmpty()

        if (childDomains.isNotEmpty()) {
            val subMap = mutableMapOf<String, MutableList<ToolPreview>>()
            if (myTools.isNotEmpty()) subMap[parent] = myTools.toMutableList()
            childDomains.forEach { child ->
                flatMap[child]?.takeIf { it.isNotEmpty() }?.let { subMap[child] = it.toMutableList() }
            }
            result.add(parent to subMap)
        } else {
            result.add(parent to null)
        }
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
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = koinInject()

    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    var isClassifying by remember { mutableStateOf(false) }
    var classifyLog by remember { mutableStateOf("") }
    var showClassifierPrompt by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var editClassifierPrompt by remember(settings.classifierPrompt) { mutableStateOf(settings.classifierPrompt.ifBlank { ToolClassifier.DEFAULT_PROMPT }) }

    var showNewDomain by remember { mutableStateOf(false) }
    var showToolList by remember { mutableStateOf(false) }
    var editingDomain by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }
    var editKws by remember { mutableStateOf("") }

    if (showToolList) { SettingToolListPage(settings, vm, { showToolList = false }); return }

    val router = remember(settings) {
        ToolRouter(
            settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains,
            settings.customDomainKeywords, settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains
        )
    }

    // 完整工具清单——每次重组都重新读取真实数据源
    val previewTools: List<ToolPreview> = run {
        val list = mutableListOf<ToolPreview>()
        try {
            val allOptions = listOf(
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.JavascriptEngine,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.TimeInfo,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Clipboard,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Tts,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.AskUser,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.ScreenTime,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Calendar,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.CronJobs,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.ToastAndNotification,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.SubAgents,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.SystemIntents,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Share,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Location,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Battery,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.MediaPlayer,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.MediaScanner,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Files,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Download,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Archive,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.CostGuards,
                me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Browser,
            )
            localTools.getTools(allOptions).forEach { t ->
                list.add(ToolPreview(t.name, t.description))
            }
        } catch (_: Exception) {}
        try { skillManager.listSkills().forEach { s -> list.add(ToolPreview("use_skill", "技能:${s.name} - ${s.description}")) } } catch (_: Exception) {}
        try {
            mcpManager.getAllAvailableTools().forEach { (_, serverName, tool) ->
                list.add(ToolPreview("mcp__${serverName}__${tool.name}", tool.description ?: ""))
            }
        } catch (_: Exception) {}
        list
    }

    val flatDomainMap: Map<String, List<ToolPreview>> = remember(previewTools, settings.toolDescriptionOverrides, settings.toolDomainOverrides, settings.customDomainKeywords, revision) {
        previewTools.groupBy { router.classifyPreview(it.name, settings.toolDescriptionOverrides[it.name] ?: it.description) }
    }
    val nestedDomains = remember(flatDomainMap, settings.hiddenDomains, settings.removedBuiltinDomains, settings.customDomains, revision) {
        buildNestedDomains(flatDomainMap, router)
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(title = { Text("域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    // 刷新按钮：清理过期数据 + 强制重算（本地即时，不依赖网络）
                    IconButton(onClick = {
                        val valid = router.validDomainLabels
                        val cleanedOverrides = settings.toolDomainOverrides.filterValues { it in valid }
                        val cleanedKeywords = settings.customDomainKeywords.filterKeys { it in valid }
                        val cleanedDescs = settings.customDomainDescriptions.filterKeys { it in valid }
                        val cleanedNames = settings.domainNameOverrides.filterKeys { it in valid }
                        val removedCount = settings.toolDomainOverrides.size - cleanedOverrides.size
                        vm.updateSettings(settings.copy(
                            toolDomainOverrides = cleanedOverrides,
                            customDomainKeywords = cleanedKeywords,
                            customDomainDescriptions = cleanedDescs,
                            domainNameOverrides = cleanedNames,
                        ))
                        revision++
                        classifyLog = buildString {
                            append("已刷新 · ${previewTools.size}个工具 · ${nestedDomains.size}个域")
                            if (removedCount > 0) append(" · 清理${removedCount}个过期覆盖")
                        }
                    }) { Icon(HugeIcons.Refresh01, "同步") }
                    // AI 分类按钮（独立于刷新）
                    IconButton(onClick = { showClassifierPrompt = true }) { Icon(HugeIcons.AiMagic, "AI分类") }
                    IconButton(onClick = { showToolList = true }) { Icon(HugeIcons.View, "工具列表") }
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建") }
                }, colors = CustomColors.topBarColors)
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (classifyLog.isNotEmpty()) {
                item { Text(classifyLog, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            if (isClassifying) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
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
                val isMCPParent = subs != null
                var expanded by remember { mutableStateOf(false) }

                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isHidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("[${displayName}]", fontWeight = FontWeight.Bold, color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                if (isMCPParent) {
                                    val subCount = subs!!.size
                                    val toolCount = subs.values.sumOf { it.size } + (flatDomainMap[domain]?.size ?: 0)
                                    Text(" (${subCount}子域/${toolCount}工具)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    val toolCount = flatDomainMap[domain]?.size ?: 0
                                    if (toolCount > 0) Text(" (${toolCount}工具)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isHidden) Text(" [已隐藏]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row {
                                IconButton(onClick = {
                                    editingDomain = domain
                                    editName = displayName
                                    editDesc = settings.customDomainDescriptions[domain] ?: router.getTriggerDescription(domain)
                                    editKws = router.getKeywords(domain).joinToString(", ")
                                }, modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Edit01, null, modifier = Modifier.size(14.dp)) }
                                IconButton(onClick = {
                                    val hs = settings.hiddenDomains.toMutableSet()
                                    if (isHidden) hs.remove(domain) else hs.add(domain)
                                    vm.updateSettings(settings.copy(hiddenDomains = hs))
                                }, modifier = Modifier.size(24.dp)) { Icon(if (isHidden) HugeIcons.ViewOff else HugeIcons.View, null, modifier = Modifier.size(14.dp)) }
                                IconButton(onClick = { deleteConfirm = domain }, modifier = Modifier.size(24.dp)) {
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
                                        val subDisplay = settings.domainNameOverrides[sub] ?: sub.substringAfterLast("/")
                                        val subDesc = settings.customDomainDescriptions[sub] ?: router.getTriggerDescription(sub)
                                        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("[$subDisplay] ${subTools.size}个工具", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                    Text(subDesc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    subTools.take(5).forEach { t ->
                                                        Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                    if (subTools.size > 5) Text("  ... 等${subTools.size}个", style = MaterialTheme.typography.bodySmall)
                                                }
                                                // 子域编辑按钮——可编辑描述和关键词
                                                IconButton(onClick = {
                                                    editingDomain = sub
                                                    editName = subDisplay
                                                    editDesc = settings.customDomainDescriptions[sub] ?: router.getTriggerDescription(sub)
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
                                            Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    // 编辑域对话框（顶级域和子域通用）
    if (editingDomain != null) {
        val domain = editingDomain!!
        AlertDialog(onDismissRequest = { editingDomain = null }, title = { Text("编辑: $domain") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(editName, { editName = it }, label = { Text("显示名称") }, singleLine = true)
                OutlinedTextField(editDesc, { editDesc = it }, label = { Text("触发描述") }, maxLines = 3)
                OutlinedTextField(editKws, { editKws = it }, label = { Text("触发条件(逗号分隔)") })
            }},
            confirmButton = { TextButton(onClick = {
                var s = settings
                s = s.copy(customDomainDescriptions = s.customDomainDescriptions.toMutableMap().also { it[domain] = editDesc })
                s = s.copy(customDomainKeywords = s.customDomainKeywords.toMutableMap().also { it[domain] = editKws.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() } })
                if (editName != domain.substringAfterLast("/") && editName.isNotBlank()) {
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
                if (nn.isNotBlank()) vm.updateSettings(settings.copy(customDomains = settings.customDomains + CustomDomain(nn.trim(), nd.trim(), nk.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() })))
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

    // AI 分类逻辑
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
                    // 只接受合法域名的分类结果
                    val valid = router.validDomainLabels
                    val filtered = result.filterValues { it in valid }
                    val m = settings.toolDomainOverrides.toMutableMap()
                    filtered.forEach { (tool, domain) -> m[tool] = domain }
                    vm.updateSettings(settings.copy(toolDomainOverrides = m))
                    val skipped = result.size - filtered.size
                    classifyLog = "分类完成: ${filtered.size}个工具已归类" + if (skipped > 0) " · 跳过${skipped}个无效域名" else ""
                }
                .onFailure { e ->
                    classifyLog = "分类失败: ${e.message?.take(200) ?: "未知错误"}"
                }
        } catch (e: Exception) {
            classifyLog = "异常: ${e.message?.take(200) ?: ""}"
        }
        isClassifying = false
    }

    // 分类提示词编辑
    if (showClassifierPrompt) {
        AlertDialog(onDismissRequest = { showClassifierPrompt = false }, title = { Text("AI 分类提示词") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    Text("提示词发送给 AI 模型，用于自动将工具分配到场景。场景列表已自动同步当前域架构。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(editClassifierPrompt, { editClassifierPrompt = it }, modifier = Modifier.fillMaxWidth().weight(1f), maxLines = 20)
                }
            },
            confirmButton = { TextButton(onClick = {
                vm.updateSettings(settings.copy(classifierPrompt = editClassifierPrompt))
                showClassifierPrompt = false
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
