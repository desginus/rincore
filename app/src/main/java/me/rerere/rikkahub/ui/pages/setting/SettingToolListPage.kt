package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingToolListPage.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.routing.ToolDomain
import me.rerere.rikkahub.data.ai.tools.routing.normalizedFullPath
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
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
    val localTools: LocalTools = koinInject()
    val mcpManager: McpManager = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()
    var searchQuery by remember { mutableStateOf("") }
    var filterDomain by remember { mutableStateOf("全部") }
    var selectedTool by remember { mutableStateOf<ToolPreview?>(null) }

    val router = remember(settings) {
        ToolRouter(settings.toolDomainOverrides, settings.customDomainDescriptions,
            settings.customDomains, settings.customDomainKeywords,
            settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains,
            exemptFromDomainTools = settings.exemptFromDomainTools)
    }

    // 完整工具清单——与实际对话注入一致
    val allTools: List<ToolPreview> = remember(settings) {
        buildPreviewTools(
            settings, localTools, skillManager, mcpManager,
            conversationRepo = conversationRepo,
            settingsStore = settingsStore,
            workspaceRepository = workspaceRepository,
        )
    }

    // v3.8.24: 统一信息源头 — 移动目标列表/筛选 chips 与域分类管理页完全同源
    // (unifiedDomainView.tree, 即 layer1/invoke_tools/list_domains 同一上游)。
    // 不再自拼 ToolDomain.entries + customDomains (会翻出历史遗留/空壳幽灵域)。
    // tree 已含: 已删/隐藏域过滤(isValidDomain) + 内置空壳剔除 + 自定义空域
    // 保留 + 路径排序。下游老老实实用上游最新信息。
    val allToolsAsTools = remember(allTools) {
        allTools.map {
            me.rerere.ai.core.Tool(
                name = it.name,
                description = it.description,
                parameters = { me.rerere.ai.core.InputSchema.Obj(kotlinx.serialization.json.buildJsonObject {}) },
                execute = { listOf(me.rerere.ai.ui.UIMessagePart.Text("")) },
            )
        }
    }
    val unifiedView = remember(allToolsAsTools, settings) { router.unifiedDomainView(allToolsAsTools) }
    // 根域 + 子域 (tree 已排序: 前缀聚合, 根在前子域随后)
    val allDomainNames = remember(unifiedView) {
        buildList {
            for ((root, subs) in unifiedView.tree) {
                add(root)
                if (subs.isNotEmpty()) addAll(subs.sorted())
            }
        }
    }
    // 预计算 工具名→域 一次 (unifiedView.classified 同源), 点击筛选零重分类 → 无卡顿
    val toolDomainMap: Map<String, String> = remember(unifiedView) {
        unifiedView.classified.flatMap { (domain, ts) -> ts.map { it.name to domain } }.toMap()
    }

    val filtered = remember(allTools, searchQuery, filterDomain, toolDomainMap) {
        allTools.filter { t ->
            val q = searchQuery.lowercase()
            if (q.isNotEmpty() && !t.name.lowercase().contains(q) && !t.description.lowercase().contains(q)) return@filter false
            if (filterDomain == "全部") return@filter true
            val d = toolDomainMap[t.name] ?: return@filter true
            d == filterDomain || d.startsWith("$filterDomain/")
        }
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = { TopAppBar(title = { Text("工具列表") }, navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } }, colors = CustomColors.topBarColors) },
    ) { pad ->
        BackHandler { onBack() }
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("搜索") },
                    modifier = Modifier.weight(1f), singleLine = true,
                    leadingIcon = { Icon(HugeIcons.GlobalSearch, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(HugeIcons.Cancel01, null) } })
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = filterDomain == "全部", onClick = { filterDomain = "全部" }, label = { Text("全部(${allTools.size})") }) }
                        items(allDomainNames) { dn ->
                            val count = toolDomainMap.count { (name, d) -> d == dn || d.startsWith("$dn/") }
                            FilterChip(selected = filterDomain == dn, onClick = { filterDomain = dn }, label = { Text("${router.displayName(dn)}($count)") })
                        }
                    }
                }
                item { Text("${filtered.size}个工具", style = MaterialTheme.typography.bodySmall) }

                items(filtered) { tool ->
                    val domain = toolDomainMap[tool.name] ?: router.classifyPreview(tool.name, settings.toolDescriptionOverrides[tool.name] ?: tool.description)
                    val displayDomain = domain.substringBefore("/")
                    Card(Modifier.fillMaxWidth().clickable {
                        selectedTool = tool
                    }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((settings.toolDescriptionOverrides[tool.name] ?: tool.description).take(80), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AssistChip(
                                        onClick = { filterDomain = domain },
                                        label = { Text(displayDomain, style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(24.dp)
                                    )
                                    if (tool.name in settings.exemptFromDomainTools) {
                                        AssistChip(
                                            onClick = {},
                                            label = { Text("已移出域管理", style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp)
                                        )
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

    // 工具操作对话框
    if (selectedTool != null) {
        val tool = selectedTool!!
        var moveTarget by remember(tool) {
            val fullDomain = settings.toolDomainOverrides[tool.name] ?: (toolDomainMap[tool.name] ?: tool.name)
            mutableStateOf(fullDomain)
        }
        var editDescText by remember(tool) { mutableStateOf(settings.toolDescriptionOverrides[tool.name] ?: tool.description) }
        var exemptChecked by remember(tool) { mutableStateOf(tool.name in settings.exemptFromDomainTools) }
        var editNameText by remember(tool) { mutableStateOf(settings.toolNameOverrides[tool.name] ?: "") }
        AlertDialog(
            onDismissRequest = { selectedTool = null },
            title = { Text(tool.name) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text("当前分类: ${moveTarget}", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = editNameText,
                        onValueChange = { editNameText = it },
                        label = { Text("工具名称（改名）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("留空保持原名。仅允许字母、数字、下划线、连字符 — 汉语名工具模型难以识别，建议改为英文名。") },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("移出域管理", fontWeight = FontWeight.SemiBold)
                            Text(
                                "开启后该工具不再并入工具域分类，与框架工具一样始终暴露在请求中，不参与域统计。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = exemptChecked, onCheckedChange = { exemptChecked = it })
                    }
                    HorizontalDivider()
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
                    val m = s.toolDomainOverrides.toMutableMap()
                    m[tool.name] = moveTarget; s = s.copy(toolDomainOverrides = m)
                    val dm = s.toolDescriptionOverrides.toMutableMap()
                    if (editDescText.isNotBlank() && editDescText != tool.description) dm[tool.name] = editDescText
                    else dm.remove(tool.name)
                    s = s.copy(toolDescriptionOverrides = dm)
                    // v3.6.90: 移出域管理开关 — 豁免集增减
                    val em = s.exemptFromDomainTools.toMutableSet()
                    if (exemptChecked) em.add(tool.name) else em.remove(tool.name)
                    s = s.copy(exemptFromDomainTools = em)
                    // v3.6.102: 工具改名 — 仅合法名写入 (非法输入静默忽略)
                    val nm = s.toolNameOverrides.toMutableMap()
                    val newName = editNameText.trim()
                    if (newName.isNotBlank() && newName.all { ch -> ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-' }) {
                        nm[tool.name] = newName
                    } else if (newName.isBlank()) {
                        nm.remove(tool.name)
                    }
                    s = s.copy(toolNameOverrides = nm)
                    vm.updateSettings(s)
                    selectedTool = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
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
