package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.ai.tools.routing.normalizedFullPath
import me.rerere.rikkahub.data.datastore.CustomDomain
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import androidx.compose.foundation.layout.heightIn

data class ToolPreview(val name: String, val description: String)

fun buildPreviewTools(
    settings: Settings,
    localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools,
    skillManager: SkillManager,
    mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager,
    conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository,
    settingsStore: SettingsStore,
    workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository? = null,
): List<ToolPreview> {
    // 全信源统一: 与模型侧完全同源 (buildAssistantToolPool) —
    // 域管理页计数/分区/工具列表 与 模型工具池 完全一致 (用户要求 v3.5.41)
    val assistant = settings.getCurrentAssistant()
    val pool = try {
        // 视图口径 (v3.5.52): 排除框架工具 — 与模型侧帮助/List Domains 完全一致
        buildAssistantToolPool(
            settings = settings,
            assistant = assistant,
            localTools = localTools,
            skillManager = skillManager,
            conversationRepo = conversationRepo,
            mcpManager = mcpManager,
            settingsStore = settingsStore,
            workspaceRepository = workspaceRepository,
        ))
    } catch (_: Exception) {
        emptyList()
    }
    return pool.map { ToolPreview(it.name, it.description) }
}

private fun buildNestedDomains(
    view: ToolRouter.UnifiedDomainView,
    router: ToolRouter,
): List<Pair<String, Map<String, MutableList<ToolPreview>>?>> {
    val result = mutableListOf<Pair<String, Map<String, MutableList<ToolPreview>>?>>()
    for ((parent, subs) in view.tree) {
        val myTools = view.classified[parent].orEmpty()
        if (subs.isNotEmpty()) {
            val subMap = mutableMapOf<String, MutableList<ToolPreview>>()
            if (myTools.isNotEmpty()) {
                subMap[parent] = myTools.map { ToolPreview(it.name, it.description) }.toMutableList()
            }
            for (child in subs) {
                subMap[child] = view.classified[child].orEmpty()
                    .map { ToolPreview(it.name, it.description) }
                    .toMutableList()
            }
            result.add(parent to subMap)
        } else {
            // 空壳过滤: 顶级域无任何工具 → 不显示
            if (myTools.isNotEmpty()) {
                result.add(parent to null)
            }
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
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = koinInject()
    val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository = koinInject()

    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    var isClassifying by remember { mutableStateOf(false) }
    var classifyLog by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }

    var showNewDomain by remember { mutableStateOf(false) }
    var showToolList by remember { mutableStateOf(false) }
    var editingDomain by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editDesc by remember { mutableStateOf("") }
    var editKws by remember { mutableStateOf("") }

    // 子域管理状态
    var subdomainParent by remember { mutableStateOf<String?>(null) }
    var showNewSubdomain by remember { mutableStateOf(false) }
    var managingSubdomain by remember { mutableStateOf<String?>(null) }
    var movingTool by remember { mutableStateOf<ToolPreview?>(null) }

    if (showToolList) { SettingToolListPage(settings, vm, { showToolList = false }); return }

    val router = remember(settings) {
        ToolRouter(
            settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains,
            settings.customDomainKeywords, settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains
        )
    }

    val previewTools: List<ToolPreview> = remember(settings, revision) {
        buildPreviewTools(
            settings, localTools, skillManager, mcpManager,
            conversationRepo = conversationRepo,
            settingsStore = settingsStore,
            workspaceRepository = workspaceRepository,
        )
    }

    // 统一视图 (v3.5.42 信源统一): 与 layer1/Invoke Tools/List Domains 完全同源
    val previewToolsAsTools = previewTools.map {
        me.rerere.ai.core.Tool(
            name = it.name,
            description = it.description,
            parameters = { me.rerere.ai.core.InputSchema.Obj(kotlinx.serialization.json.buildJsonObject {}) },
            execute = { listOf(me.rerere.ai.ui.UIMessagePart.Text("")) },
        )
    }
    val unifiedView = remember(previewToolsAsTools, settings.toolDomainOverrides, settings.customDomainKeywords, revision) {
        router.unifiedDomainView(previewToolsAsTools)
    }
    val nestedDomains = remember(unifiedView, revision) {
        buildNestedDomains(unifiedView, router)
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(title = { Text("域分类管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = {
                        val valid = router.validDomainLabels
                        val cleanedOverrides = settings.toolDomainOverrides.filterValues { it in valid }
                        val cleanedKeywords = settings.customDomainKeywords.filterKeys { it in valid }
                        val cleanedDescs = settings.customDomainDescriptions.filterKeys { it in valid }
                        val cleanedNames = settings.domainNameOverrides.filterKeys { it in valid }
                        vm.updateSettings(settings.copy(
                            toolDomainOverrides = cleanedOverrides,
                            customDomainKeywords = cleanedKeywords,
                            customDomainDescriptions = cleanedDescs,
                            domainNameOverrides = cleanedNames,
                        ))
                        revision++
                        classifyLog = "${previewTools.size}个工具 · ${nestedDomains.size}个域"
                    }) { Icon(HugeIcons.Refresh01, "同步") }
                    TextButton(onClick = { isClassifying = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(HugeIcons.AiMagic, "名称分类", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("名称分类", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { showToolList = true }) { Icon(HugeIcons.View, "工具列表") }
                    IconButton(onClick = { showNewDomain = true }) { Icon(HugeIcons.Add01, "新建") }
                }, colors = CustomColors.topBarColors)
        },
    ) { pad ->
        BackHandler { onBack() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (classifyLog.isNotEmpty()) {
                item { Text(classifyLog, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
            if (isClassifying) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item {
                val mcpLarge = 0
                val customSubCount = 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${nestedDomains.size}个域 · ${previewTools.size}个工具 · 内置/Skill/MCP 同层位统一计数",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                }
            }

            itemsIndexed(nestedDomains) { _, (domain, subs) ->
                // 统一域标签 (v3.5.52): 与系统提示/List Domains/invoke_tools 同格式
                val displayName = router.formatDomainLabel(domain)
                val isHidden = domain in settings.hiddenDomains
                val isCustom = domain in settings.customDomains.map { it.normalizedFullPath() }
                val desc = settings.customDomainDescriptions[domain] ?: router.getTriggerDescription(domain)
                var expanded by remember { mutableStateOf(false) }

                Card(Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (isHidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("[${displayName}]", fontWeight = FontWeight.Bold, color = if (isCustom) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                if (subs != null) {
                                    val subCount = subs.size
                                    val toolCount = unifiedView.classified[domain]?.size ?: 0
                                    Text(" (${subCount}子域/${toolCount}工具)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    val toolCount = unifiedView.classified[domain]?.size ?: 0
                                    if (toolCount > 0) Text(" (${toolCount}工具)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isHidden) Text(" [已隐藏]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Row {
                                // ⚙ 子域管理
                                IconButton(onClick = { subdomainParent = domain },
                                    modifier = Modifier.size(24.dp)) { Icon(HugeIcons.Settings01, "子域管理", modifier = Modifier.size(14.dp)) }
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
                                if (subs != null) {
                                    Text("子域:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    subs.forEach { (sub, subTools) ->
                                        val subDisplay = settings.domainNameOverrides[sub] ?: sub.substringAfterLast("/")
                                        val subDesc = settings.customDomainDescriptions[sub] ?: router.getTriggerDescription(sub)
                                        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("[$subDisplay] ${subTools.size}个工具", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                    Text(subDesc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    subTools.forEach { t ->
                                                        Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
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
                                    val domainTools = unifiedView.classified[domain].orEmpty().map { ToolPreview(it.name, it.description) }
                                    val kws = router.getKeywords(domain)
                                    if (kws.isNotEmpty()) {
                                        Text("触发条件:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            kws.take(8).forEach { kw -> SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) }) }
                                        }
                                    }
                                    // skill 挂载显示 (move_tool_to_domain 挂载的 skill:名)
                                    val mountedSkills = settings.toolDomainOverrides.entries
                                        .filter { it.key.startsWith("skill:") && it.value == domain }
                                        .map { it.key.removePrefix("skill:") }
                                    if (mountedSkills.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                                        Text("挂载的 Skills(${mountedSkills.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        mountedSkills.sorted().forEach { sname ->
                                            Text("  $sname", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (domainTools.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                                        Text("工具(${domainTools.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        domainTools.forEach { t ->
                                            Text("  ${t.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
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

    // === 子域管理对话框 ===
    if (subdomainParent != null) {
        val parentDomain = subdomainParent!!
        // 收集该父域下的所有子域：内置 + 自定义
        // 管理页规则: 真删除(removed)不显示; 隐藏(hidden)保留显示+[已隐藏]标记(可恢复)
        fun notRemoved(domain: String): Boolean {
            val root = domain.split("/").first()
            return domain !in settings.removedBuiltinDomains && root !in settings.removedBuiltinDomains
        }
        val builtinSubs = ToolDomain.entries.filter { it.parent == parentDomain }.map { it.label }.filter { notRemoved(it) }
        val customSubs = settings.customDomains.filter { it.parent == parentDomain }.map { it.name }.filter { notRemoved(it) }
        val allSubs = (builtinSubs + customSubs).sorted()
        val parentTools = unifiedView.classified[parentDomain].orEmpty().map { ToolPreview(it.name, it.description) }
        AlertDialog(
            onDismissRequest = { subdomainParent = null },
            title = { Text("管理子域: $parentDomain") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text("父域直接工具: ${parentTools.size}个", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                    }
                    if (allSubs.isEmpty()) {
                        item { Text("暂无子域", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    itemsIndexed(allSubs) { _, subFull ->
                        val subShort = subFull.substringAfterLast("/")
                        val isCustom = subFull in customSubs
                        val isSubHidden = subFull in settings.hiddenDomains
                        val subTools = unifiedView.classified[subFull].orEmpty().map { ToolPreview(it.name, it.description) }
                        val subDesc = settings.customDomainDescriptions[subFull] ?: router.getTriggerDescription(subFull)
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (isSubHidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(8.dp).fillMaxWidth()) {
                                Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(subShort + (if (isSubHidden) " [已隐藏]" else ""), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                        Text("${subTools.size}个工具 · ${subDesc.take(40)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row {
                                        TextButton(onClick = { managingSubdomain = subFull }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Text("管理工具", style = MaterialTheme.typography.labelSmall)
                                        }
                                        // 隐藏/显示切换 (内置子域)
                                        if (!isCustom) {
                                            IconButton(onClick = {
                                                val hs = settings.hiddenDomains.toMutableSet()
                                                if (isSubHidden) hs.remove(subFull) else hs.add(subFull)
                                                vm.updateSettings(settings.copy(hiddenDomains = hs))
                                                revision++
                                            }, modifier = Modifier.size(24.dp)) {
                                                Icon(if (isSubHidden) HugeIcons.ViewOff else HugeIcons.View, null, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                        // 删除按钮: 自定义域从 customDomains 移除, 内置域永久删除 (removedBuiltinDomains)
                                        IconButton(onClick = {
                                            if (isCustom) {
                                                // 删除自定义子域: 工具回归父域
                                                val newOverrides = settings.toolDomainOverrides.mapValues { (_, v) ->
                                                    if (v == subFull) parentDomain else v
                                                }
                                                vm.updateSettings(settings.copy(
                                                    customDomains = settings.customDomains.filter { it.name != subFull },
                                                    toolDomainOverrides = newOverrides,
                                                ))
                                            } else {
                                                // 真删除内置子域 (removedBuiltinDomains, 不可恢复) + 清理相关配置
                                                vm.updateSettings(settings.copy(
                                                    removedBuiltinDomains = settings.removedBuiltinDomains + subFull,
                                                    toolDomainOverrides = settings.toolDomainOverrides.filter { !it.value.startsWith(subFull) },
                                                    customDomainDescriptions = settings.customDomainDescriptions.toMutableMap().also { it.remove(subFull) },
                                                    customDomainKeywords = settings.customDomainKeywords.toMutableMap().also { it.remove(subFull) },
                                                    domainNameOverrides = settings.domainNameOverrides.toMutableMap().also { it.remove(subFull) },
                                                ))
                                            }
                                            revision++
                                        }, modifier = Modifier.size(24.dp)) {
                                            Icon(HugeIcons.Delete01, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                if (subTools.isNotEmpty()) {
                                    Text(subTools.take(3).joinToString(", ") { it.name }, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (subTools.size > 3) Text("...等${subTools.size}个", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { showNewSubdomain = true }) {
                            Icon(HugeIcons.Add01, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("新建子域")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { subdomainParent = null }) { Text("完成") } },
        )
    }

    // === 子域工具管理对话框 ===
    if (managingSubdomain != null) {
        val subFull = managingSubdomain!!
        val parentDomain = subFull.substringBefore("/")
        val subShort = subFull.substringAfterLast("/")
        val subTools = unifiedView.classified[subFull].orEmpty().map { ToolPreview(it.name, it.description) }
        // 可移动目标：父域 + 该父域下所有其他子域
        val moveTargets = (listOf(parentDomain) + (ToolDomain.entries.filter { it.parent == parentDomain }.map { it.label } + settings.customDomains.filter { it.parent == parentDomain }.map { it.name }).filter { it != subFull }).sorted()
        AlertDialog(
            onDismissRequest = { managingSubdomain = null },
            title = { Text("管理工具: $subShort") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (subTools.isEmpty()) {
                        item { Text("该子域暂无工具", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    itemsIndexed(subTools) { _, tool ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tool.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(tool.description.take(60), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { movingTool = tool }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text("移到", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managingSubdomain = null }) { Text("完成") } },
        )

        // 工具移动目标选择
        if (movingTool != null) {
            val tool = movingTool!!
            AlertDialog(
                onDismissRequest = { movingTool = null },
                title = { Text("移动 ${tool.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        moveTargets.forEach { target ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                                val newOverrides = settings.toolDomainOverrides.toMutableMap()
                                newOverrides[tool.name] = target
                                vm.updateSettings(settings.copy(toolDomainOverrides = newOverrides))
                                revision++
                                movingTool = null
                            }.padding(vertical = 8.dp)) {
                                RadioButton(selected = false, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(target, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { movingTool = null }) { Text("取消") } },
            )
        }
    }

    // 新建子域
    if (showNewSubdomain && subdomainParent != null) {
        val parentDomain = subdomainParent!!
        var sn by remember { mutableStateOf("") }
        var sd by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewSubdomain = false },
            title = { Text("新建子域: $parentDomain") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sn, { sn = it }, label = { Text("子域名称") },
                        supportingText = { Text("如 我的引擎。完整路径将为 $parentDomain/$sn") })
                    OutlinedTextField(sd, { sd = it }, label = { Text("描述(可选)") }, maxLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (sn.isNotBlank()) {
                        val full = "$parentDomain/$sn"
                        vm.updateSettings(settings.copy(
                            customDomains = settings.customDomains + CustomDomain(full, sd.trim(), parent = parentDomain)
                        ))
                        revision++
                    }
                    showNewSubdomain = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNewSubdomain = false }) { Text("取消") } }
        )
    }

    // 编辑域对话框
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
                OutlinedTextField(nn, { nn = it }, label = { Text("内部名称(英文)") }, supportingText = { Text("如 physics/sim，用作 invoke_tools 参数") })
                OutlinedTextField(nd, { nd = it }, label = { Text("触发描述") }, maxLines = 2)
                OutlinedTextField(nk, { nk = it }, label = { Text("触发条件") })
            }},
            confirmButton = { TextButton(onClick = {
                if (nn.isNotBlank()) {
                    val parts = nn.trim().split("/")
                    val parent = if (parts.size > 1) parts[0] else null
                    vm.updateSettings(settings.copy(customDomains = settings.customDomains + CustomDomain(nn.trim(), nd.trim(), nk.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() }, parent = parent)))
                }
                showNewDomain = false
            }) { Text("创建") } },
            dismissButton = { TextButton(onClick = { showNewDomain = false }) { Text("取消") } }
        )
    }

    // 删除确认对话框
    if (deleteConfirm != null) {
        val domain = deleteConfirm!!
        val isCustom = domain in settings.customDomains.map { it.normalizedFullPath() }
        AlertDialog(onDismissRequest = { deleteConfirm = null }, title = { Text("删除域") },
            text = { Text(if (isCustom) "删除自定义域「${domain}」？此操作不可恢复。子域和覆盖将一起清除。"
                     else "删除内置域「${domain}」？它将从场景地图中消失。可通过新建域恢复。") },
            confirmButton = { TextButton(onClick = {
                var s = settings
                if (isCustom) {
                    // 同时删除该域的子和所属覆盖
                    val allToRemove = s.customDomains.filter { it.name == domain || it.parent == domain }.map { it.name }.toSet()
                    s = s.copy(customDomains = s.customDomains.filter { it.name !in allToRemove })
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
        classifyLog = "正在按名称分类..."
        try {
            // 本地名称规则分类 (替代模型调用): 工具名第一字段为类别(MCP/Skill/其他),
            // 第二字段为分类字段 — Skill 归「技能/<名>」, MCP 保持映射+关键词, 本地按前缀/关键词
            val valid = router.validDomainLabels
            val m = settings.toolDomainOverrides.toMutableMap()
            var classified = 0
            var skipped = 0
            for (tp in previewTools) {
                val domain = router.classifyPreview(
                    tp.name,
                    settings.toolDescriptionOverrides[tp.name] ?: tp.description
                )
                if (domain in valid) {
                    m[tp.name] = domain
                    classified++
                } else {
                    skipped++
                }
            }
            vm.updateSettings(settings.copy(toolDomainOverrides = m))
            classifyLog = "名称分类完成: ${classified}个工具已归类" +
                if (skipped > 0) " · 跳过${skipped}个" else ""
        } catch (e: Exception) {
            classifyLog = "异常: ${e.message?.take(200) ?: ""}"
        }
        isClassifying = false
    }

}
