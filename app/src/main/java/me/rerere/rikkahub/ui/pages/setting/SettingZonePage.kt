/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.*
import me.rerere.rikkahub.data.ai.tools.routing.FALLBACK_ZONE_ID
import me.rerere.rikkahub.data.ai.tools.routing.ToolZone
import me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter
import me.rerere.rikkahub.data.ai.tools.routing.ZoneOps
import me.rerere.rikkahub.data.ai.tools.topLevelToolSetOf
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import me.rerere.rikkahub.utils.plus

/**
 * 工具矩阵 —— 全部工具区的唯一管理界面（v4.8.83 重写）。
 *
 * 设计要点：
 *  - 视图 100% 从 settings 直接派生（remember 键含 settings 本身 + 全局写版本号），
 *    不存在「写入成功但列表还是旧的」的陈旧快照问题；
 *  - 工具区无内置/自定义之分：每个区都能改名、改描述、改条件、隐藏、删除；
 *  - 0 个工具的工具区**不会消失**，收进「暂无工具」分组（仍可编辑/删除）；
 *  - 顶层直连工具与工具区在此页统一呈现与调整（旧「框架工具页」已并入本页）。
 */
@Composable
fun SettingZonePage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: me.rerere.rikkahub.data.files.SkillManager = koinInject()
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = koinInject()
    val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository = koinInject()
    val operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider = koinInject()

    val globalRevision by settingsStore.settingsRevision.collectAsState()

    var showToolList by remember { mutableStateOf(false) }
    var showNewZone by remember { mutableStateOf(false) }
    var newZoneParent by remember { mutableStateOf<String?>(null) }
    var editingZone by remember { mutableStateOf<String?>(null) }
    var deleteConfirm by remember { mutableStateOf<String?>(null) }
    var managingSubZones by remember { mutableStateOf<String?>(null) }
    var movingTool by remember { mutableStateOf<String?>(null) }
    /** 操作回执：与模型侧同源（都来自 ZoneOps）—— 校验失败时如实提示，绝不静默吞掉 */
    var zoneNotice by remember { mutableStateOf<String?>(null) }

    if (showToolList) {
        SettingToolListPage(settings, vm) { showToolList = false }
        return
    }

    // v4.8.90: LocalContext 必须在 composable 上下文先取（不能在 runCatching lambda 内读）
    val filesRootForTools = LocalContext.current.applicationContext.filesDir
    val previewTools = remember(settings, globalRevision) {
        runCatching {
            buildToolList(
                settings, localTools, skillManager, mcpManager,
                conversationRepo = conversationRepo,
                settingsStore = settingsStore,
                workspaceRepository = workspaceRepository,
                operitToolProvider = operitToolProvider,
                // v4.8.90: 与模型侧同口径（read_image / upload_fetch 也进 UI 清单）
                filesRoot = filesRootForTools,
            )
        }.getOrDefault(emptyList())
    }
    val router = remember(settings, globalRevision) { zoneRouterOf(settings) }
    val zoneMap = remember(settings, globalRevision, previewTools) {
        router.zoneMap(previewTools.asShellTools())
    }
    val topLevelNames = remember(settings) { topLevelToolSetOf(settings) }
    val topLevelTools = remember(settings, previewTools) {
        previewTools.filter { it.name in topLevelNames }.map { it.name }.sorted()
    }

    // 有工具 = 直接或子区有工具；空区收进独立分组（存在但低噪音）
    val nonEmptyRoots = zoneMap.roots.filter { (zoneMap.subtreeCounts[it] ?: 0) > 0 }
    val emptyRoots = zoneMap.roots.filter { (zoneMap.subtreeCounts[it] ?: 0) == 0 }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("工具矩阵") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    // v4.8.92 (用户定版): 「恢复出厂」按钮已移除 —— 误触会把已删的模板区整批复活,
                    // 对用户精心裁剪过的矩阵是不可逆破坏; 缺失模板区可由用户手动重建。
                    IconButton(onClick = { showToolList = true }) { Icon(HugeIcons.View, "工具列表") }
                    IconButton(onClick = { newZoneParent = null; showNewZone = true }) { Icon(HugeIcons.Add01, "新建") }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { pad ->
        BackHandler { onBack() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "${zoneMap.counts.size} 个工具区 · ${zoneMap.counts.values.sum()} 个区工具 · ${topLevelTools.size} 个顶层直连",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "工具区即模型看到的分类：描述=这个区是干什么的，触发条件=关键词。隐藏只影响是否出现在模型每轮地图里（省 token），" +
                        "帮助与关键词反查仍能找到并加载它。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            itemsIndexed(nonEmptyRoots) { _, root ->
                ZoneCard(
                    root = root,
                    router = router,
                    zoneMap = zoneMap,
                    settings = settings,
                    onEdit = { editingZone = it },
                    onDelete = { deleteConfirm = it },
                    onManageSubZones = { managingSubZones = it },
                    onToggleHidden = { id ->
                        val hidden = id !in settings.hiddenZones
                        vm.applyZoneOp({ cur -> ZoneOps.setHidden(cur, id, hidden) }, onDone = { zoneNotice = it })
                    },
                    onAddSubZone = { parent -> newZoneParent = parent; showNewZone = true },
                    onShowToolList = { showToolList = true },
                )
            }

            if (emptyRoots.isNotEmpty()) {
                item { EmptyZonesSection(emptyRoots, router, zoneMap, settings, vm, { editingZone = it }, { deleteConfirm = it }) }
            }

            item {
                TopLevelSection(
                    tools = topLevelTools,
                    isDefault = { it in me.rerere.rikkahub.data.ai.tools.routing.DEFAULT_TOP_LEVEL_TOOLS },
                    onMove = { movingTool = it },
                )
            }
        }
    }

    // ── 新建 / 新建子区 ──
    if (showNewZone) {
        NewZoneDialog(
            parentLabel = newZoneParent?.let { router.label(it) },
            onDismiss = { showNewZone = false },
            onCreate = { zoneName, title, desc, keywords ->
                vm.applyZoneOp(
                    { cur -> ZoneOps.create(
                        cur,
                        name = zoneName,
                        parentId = newZoneParent,
                        title = title,
                        description = desc,
                        keywords = keywords,
                    ) },
                    onDone = { zoneNotice = it },
                )
                showNewZone = false
            },
        )
    }

    // ── 操作回执（与模型侧同一套 ZoneOps 文案） ──
    zoneNotice?.let { msg ->
        AlertDialog(
            onDismissRequest = { zoneNotice = null },
            title = { Text("工具矩阵") },
            text = { Text(msg, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { zoneNotice = null }) { Text("知道了") } },
        )
    }

    // ── 编辑工具区 ──
    editingZone?.let { id ->
        val zone = router.zone(id) ?: return@let
        EditZoneDialog(
            zone = zone,
            onDismiss = { editingZone = null },
            onSave = { title, desc, keywords ->
                vm.applyZoneOp(
                    { cur -> ZoneOps.update(cur, id, title = title, description = desc, keywords = keywords) },
                    onDone = { zoneNotice = it },
                )
                editingZone = null
            },
        )
    }

    // ── 删除确认（规则只有一条：只删这一个区，子区自动上移一级） ──
    deleteConfirm?.let { id ->
        val id2 = router.resolve(id) ?: id
        val children = zoneMap.children[id2].orEmpty()
        val linked = settings.toolZoneLinks.count { it.value == id2 }
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("删除工具区") },
            text = {
                Text(
                    buildString {
                        append("删除「${router.label(id2)}」？\n\n")
                        append("· 其 ${children.size} 个子区自动上移一级（不连带删除）\n")
                        append("· 归属到该区的 $linked 个手动挂载交回自动归类\n")
                        append("· 该区的描述/触发条件一并删除，不可恢复")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.applyZoneOp({ cur -> ZoneOps.delete(cur, id2) }, onDone = { zoneNotice = it })
                    deleteConfirm = null
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = null }) { Text("取消") } },
        )
    }

    // ── 子区管理 ──
    managingSubZones?.let { parentId ->
        val subs = zoneMap.children[parentId].orEmpty()
        AlertDialog(
            onDismissRequest = { managingSubZones = null },
            title = { Text("子区: ${router.label(parentId)}") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Text(
                            "直接工具: ${zoneMap.counts[parentId] ?: 0} 个 · 子树共 ${zoneMap.subtreeCounts[parentId] ?: 0} 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        HorizontalDivider()
                    }
                    if (subs.isEmpty()) {
                        item { Text("暂无子区", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(subs) { subId ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    router.label(subId) + if (subId in settings.hiddenZones) " [已隐藏]" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${zoneMap.counts[subId] ?: 0} 个工具 · ${router.descriptionOf(subId).take(30)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { editingZone = subId; managingSubZones = null }, modifier = Modifier.size(28.dp)) {
                                Icon(HugeIcons.Edit01, "编辑", modifier = Modifier.size(15.dp))
                            }
                            IconButton(
                                onClick = {
                                    val hidden = subId !in settings.hiddenZones
                                    vm.applyZoneOp({ cur -> ZoneOps.setHidden(cur, subId, hidden) }, onDone = { zoneNotice = it })
                                },
                                modifier = Modifier.size(28.dp),
                            ) { Icon(if (subId in settings.hiddenZones) HugeIcons.ViewOff else HugeIcons.View, "隐藏", modifier = Modifier.size(15.dp)) }
                            IconButton(onClick = { deleteConfirm = subId; managingSubZones = null }, modifier = Modifier.size(28.dp)) {
                                Icon(HugeIcons.Delete01, "删除", modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = { newZoneParent = parentId; showNewZone = true; managingSubZones = null }) {
                            Icon(HugeIcons.Add01, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("新建子区")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managingSubZones = null }) { Text("完成") } },
        )
    }

    // ── 移动工具（归属） ──
    movingTool?.let { tool ->
        val zoneOptions = zoneMap.allIds.filter { it in zoneMap.visibleIds }
        AlertDialog(
            onDismissRequest = { movingTool = null },
            title = { Text("移动 $tool") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item {
                        Text(
                            "移回顶层 = 始终注入请求体、不参与工具区归类。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.applyZoneOp(
                                    { cur -> ZoneOps.assign(cur, tool, ZoneOps.TARGET_TOP_LEVEL) },
                                    onDone = { zoneNotice = it },
                                )
                                movingTool = null
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Text("顶层（始终可用）", style = MaterialTheme.typography.bodyMedium) }
                    }
                    items(zoneOptions) { zoneId ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.applyZoneOp({ cur -> ZoneOps.assign(cur, tool, zoneId) }, onDone = { zoneNotice = it })
                                movingTool = null
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) { Text(router.label(zoneId), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingTool = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ZoneCard(
    root: String,
    router: ZoneRouter,
    zoneMap: ZoneRouter.ZoneMap,
    settings: Settings,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onManageSubZones: (String) -> Unit,
    onToggleHidden: (String) -> Unit,
    onAddSubZone: (String) -> Unit,
    onShowToolList: () -> Unit,
) {
    val subs = zoneMap.children[root].orEmpty()
    val isHidden = root in settings.hiddenZones
    var expanded by remember { mutableStateOf(false) }
    val toolCount = zoneMap.subtreeCounts[root] ?: 0

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isHidden) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "[${router.label(root)}]" + if (isHidden) " [已隐藏]" else "",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        if (subs.isEmpty()) "$toolCount 个工具"
                        else "${subs.size} 个子区 / $toolCount 个工具",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row {
                    IconButton(onClick = { onManageSubZones(root) }, modifier = Modifier.size(26.dp)) {
                        Icon(HugeIcons.Settings01, "子区管理", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = { onEdit(root) }, modifier = Modifier.size(26.dp)) {
                        Icon(HugeIcons.Edit01, "编辑", modifier = Modifier.size(15.dp))
                    }
                    IconButton(onClick = { onToggleHidden(root) }, modifier = Modifier.size(26.dp)) {
                        Icon(if (isHidden) HugeIcons.ViewOff else HugeIcons.View, "隐藏", modifier = Modifier.size(15.dp))
                    }
                    IconButton(
                        onClick = { if (root != FALLBACK_ZONE_ID) onDelete(root) },
                        enabled = root != FALLBACK_ZONE_ID,
                        modifier = Modifier.size(26.dp),
                    ) {
                        Icon(
                            HugeIcons.Delete01, "删除", modifier = Modifier.size(15.dp),
                            tint = if (root == FALLBACK_ZONE_ID) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(26.dp)) {
                        Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, "展开", modifier = Modifier.size(15.dp))
                    }
                }
            }
            val desc = router.descriptionOf(root)
            if (desc.isNotBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }

            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    val kws = router.keywordsOf(root)
                    if (kws.isNotEmpty()) {
                        Text("触发条件:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            kws.take(8).forEach { kw ->
                                SuggestionChip(onClick = {}, label = { Text(kw, style = MaterialTheme.typography.labelSmall) })
                            }
                        }
                    }
                    if (subs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        Text("子区:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        subs.forEach { sub ->
                            Card(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(Modifier.padding(8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "[${router.label(sub)}] ${zoneMap.counts[sub] ?: 0} 个工具",
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            router.descriptionOf(sub),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(onClick = { onEdit(sub) }, modifier = Modifier.size(26.dp)) {
                                        Icon(HugeIcons.Edit01, "编辑", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                    val directTools = zoneMap.classified[root].orEmpty().map { it.name }
                    if (directTools.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        Text("直接工具(${directTools.size}):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        directTools.forEach {
                            Text("  $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row {
                        TextButton(onClick = { onAddSubZone(root) }) {
                            Icon(HugeIcons.Add01, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("新建子区", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = onShowToolList) {
                            Icon(HugeIcons.Edit01, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("去工具列表调整归属", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyZonesSection(
    emptyRoots: List<String>,
    router: ZoneRouter,
    zoneMap: ZoneRouter.ZoneMap,
    settings: Settings,
    vm: SettingVM,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("暂无工具的 ${emptyRoots.size} 个工具区", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("它们不会自动消失 —— 随时可以往里面放工具", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(16.dp))
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    emptyRoots.forEach { id ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                router.label(id) + if (id in settings.hiddenZones) " [已隐藏]" else "",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(onClick = { onEdit(id) }, modifier = Modifier.size(26.dp)) {
                                Icon(HugeIcons.Edit01, "编辑", modifier = Modifier.size(14.dp))
                            }
                            IconButton(
                                onClick = { if (id != FALLBACK_ZONE_ID) onDelete(id) },
                                enabled = id != FALLBACK_ZONE_ID,
                                modifier = Modifier.size(26.dp),
                            ) { Icon(HugeIcons.Delete01, "删除", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopLevelSection(
    tools: List<String>,
    isDefault: (String) -> Boolean,
    onMove: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("顶层直连工具（${tools.size}）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "始终注入请求体、不参与工具区归类。可把任意工具移进工具区（届时改由 invoke_tools 加载）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(16.dp))
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    tools.forEach { name ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                if (name in me.rerere.rikkahub.data.ai.tools.routing.CORE_MATRIX_TOOLS) {
                                    Text("工具矩阵核心件 · 必须留在顶层", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                } else if (!isDefault(name)) {
                                    Text("用户提升到顶层", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            if (name !in me.rerere.rikkahub.data.ai.tools.routing.CORE_MATRIX_TOOLS) {
                                TextButton(onClick = { onMove(name) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                    Text("移进工具区", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewZoneDialog(
    parentLabel: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, title: String, description: String, keywords: List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var kws by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parentLabel == null) "新建工具区" else "在「$parentLabel」下新建子区") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    supportingText = { Text("段名（不含 '/'，层级由父区决定）。路径由层级自动派生") },
                )
                OutlinedTextField(title, { title = it }, label = { Text("显示名(可选)") }, singleLine = true,
                    supportingText = { Text("留空则显示路径末段") })
                OutlinedTextField(desc, { desc = it }, label = { Text("触发描述(可选)") }, maxLines = 2,
                    supportingText = { Text("这一区负责什么 — 模型据此判断") })
                OutlinedTextField(kws, { kws = it }, label = { Text("触发条件(可选, 逗号分隔)") },
                    supportingText = { Text("关键词，用于自动归类与关键词反查") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(
                        name.trim(),
                        title.trim(),
                        desc.trim(),
                        kws.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() },
                    )
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditZoneDialog(
    zone: ToolZone,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, keywords: List<String>) -> Unit,
) {
    var title by remember(zone.id) { mutableStateOf(zone.title) }
    var desc by remember(zone.id) { mutableStateOf(zone.description) }
    var kws by remember(zone.id) { mutableStateOf(zone.keywords.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑: ${zone.id}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("路径（id）创建后不可变 —— 需要换路径请删除后重建。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it }, label = { Text("显示名") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("触发描述") }, maxLines = 3)
                OutlinedTextField(kws, { kws = it }, label = { Text("触发条件(逗号分隔)") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(title.trim(), desc.trim(), kws.split(",", "，").map { it.trim().lowercase() }.filter { it.isNotBlank() })
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
