/* 【域 K·生态扩展】 | 地图: docs/APP_MAP.md §K */
package me.rerere.rikkahub.ui.pages.operiteco


/* ───【自研】岔路口计划·生态模块 v2 (v4.6.4 综合统一)
 * "生态与插件"统一页 — 三 Tab:
 *   ① 工具包: 内置 31 包 + 市场包 (script/package — 启用即注册模型工具)
 *   ② 插件:   workspace 插件 (目录即安装, 桥接状态可见)
 *   ③ 面板:   带 UI 的插件面板 (OperitUiTabContent — 独立 WebView 渲染管线)
 * 与主聊天双并行, 数据全线走 InstalledPackageStore / PluginManager 单一信源。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.operit.market.InstalledPackage
import me.rerere.rikkahub.data.operit.market.InstalledPackageStore
import me.rerere.rikkahub.data.operit.market.MarketInstallService
import me.rerere.rikkahub.data.operit.runtime.OperitBuiltinPackages
import me.rerere.rikkahub.data.operit.runtime.OperitToolProvider
import me.rerere.rikkahub.data.plugin.PluginManager
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperitEcoPage(
    onBack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("生态与插件") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
        )
        TabRow(selectedTabIndex = tab) {
            listOf("工具包", "插件", "面板").forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title) },
                )
            }
        }
        when (tab) {
            0 -> EcoPackagesTab()
            1 -> EcoPluginsTab()
            else -> me.rerere.rikkahub.ui.pages.operitui.OperitUiTabContent()
        }
    }
}

/** Tab① 工具包 — 内置 31 包 + 市场包 (脚本/工具包, 启用即注册模型工具) */
@Composable
private fun EcoPackagesTab() {
    val installedStore: InstalledPackageStore = koinInject()
    val operitToolProvider: OperitToolProvider = koinInject()
    val marketInstallService: MarketInstallService = koinInject()
    val installedPackages by installedStore.installedFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val ecoPackages = installedPackages.filter { it.type == "script" || it.type == "package" }
    val builtin = ecoPackages.filter { OperitBuiltinPackages.isBuiltin(it) }
    val fromMarket = ecoPackages.filter { !OperitBuiltinPackages.isBuiltin(it) }

    // 进入即触发一次幂等播种 (首次进入内置包出现在列表)
    LaunchedEffect(Unit) {
        runCatching { operitToolProvider.refresh() }
    }

    var detailPkg by remember { mutableStateOf<InstalledPackage?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "eco_hint") {
            Text(
                "工具包提供 AI 可调用的能力 (启用后注册到「插件」域, 对话中让 AI 使用即可)。" +
                    "内置包随应用自带, 可随时开关。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (builtin.isNotEmpty()) {
            item(key = "builtin_header") {
                Text(
                    "内置工具包（${builtin.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(builtin, key = { "b_" + it.entryId }) { pkg ->
                EcoPackageCard(
                    pkg = pkg,
                    builtin = true,
                    onClick = { detailPkg = pkg },
                    onToggle = { enabled ->
                        scope.launch {
                            installedStore.setEnabled(pkg.entryId, enabled)
                            runCatching { operitToolProvider.refresh() }
                        }
                    },
                )
            }
        }
        if (fromMarket.isNotEmpty()) {
            item(key = "market_header") {
                Text(
                    "来自市场（${fromMarket.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(fromMarket, key = { "m_" + it.entryId }) { pkg ->
                EcoPackageCard(
                    pkg = pkg,
                    builtin = false,
                    onClick = { detailPkg = pkg },
                    onToggle = { enabled ->
                        scope.launch {
                            installedStore.setEnabled(pkg.entryId, enabled)
                            runCatching { operitToolProvider.refresh() }
                        }
                    },
                    onUninstall = {
                        scope.launch {
                            runCatching { marketInstallService.uninstall(pkg.entryId) }
                            runCatching { operitToolProvider.refresh() }
                        }
                    },
                )
            }
        }
        if (ecoPackages.isEmpty()) {
            item(key = "empty") {
                Text(
                    "生态为空 — 内置工具包将在首次刷新后自动释放入库。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    detailPkg?.let { pkg ->
        val tools = remember(pkg.entryId) {
            runCatching { operitToolProvider.describePackage(pkg) }.getOrDefault(emptyList())
        }
        EcoDetailSheet(
            pkg = pkg,
            tools = tools,
            onDismiss = { detailPkg = null },
        )
    }
}

/** Tab② 插件 — workspace 插件 (目录即安装, 桥接状态可见) */
@Composable
private fun EcoPluginsTab() {
    val pluginManager: PluginManager = koinInject()
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val plugins = remember(refreshTick) { pluginManager.pluginsUiSnapshot() }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "plugin_hint") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "workspace 插件：目录放入文件区 .plugins/<插件名>/ 即安装（plugin.yaml + 可选 SKILL.md）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !refreshing,
                    onClick = {
                        scope.launch {
                            refreshing = true
                            runCatching { pluginManager.refresh() }
                            refreshTick++
                            refreshing = false
                        }
                    },
                ) { Text(if (refreshing) "刷新中" else "刷新") }
            }
        }
        if (plugins.isEmpty()) {
            item(key = "plugin_empty") {
                Text(
                    "暂无 workspace 插件。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(plugins, key = { "pl_" + it.name }) { plugin ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        if (plugin.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                plugin.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                if (plugin.hasSkill) append("技能 ")
                                append("桥接: ").append(plugin.bridgeStatus)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EcoPackageCard(
    pkg: InstalledPackage,
    builtin: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUninstall: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pkg.title.ifBlank { pkg.entryId },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (builtin) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        if (builtin) "内置" else "市场",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "v${pkg.version} · ${if (pkg.type == "script") "脚本工具包" else "工具包 (ToolPkg)"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pkg.enabled) "已启用 — 工具已注册到「插件」域" else "未启用 — 工具不注入模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (pkg.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = pkg.enabled, onCheckedChange = onToggle)
                if (onUninstall != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onUninstall) {
                        Text("卸载", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcoDetailSheet(
    pkg: InstalledPackage,
    tools: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                pkg.title.ifBlank { pkg.entryId },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "v${pkg.version} · ${if (OperitBuiltinPackages.isBuiltin(pkg)) "内置工具包" else "来自市场"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (tools.isEmpty()) {
                    item {
                        Text(
                            "未解析到工具 — 此包可能依赖需完整内核的能力。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    item {
                        Text(
                            "工具清单（${tools.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(tools) { (name, desc) ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text("· $name", style = MaterialTheme.typography.labelLarge)
                            if (desc.isNotBlank()) {
                                Text(
                                    "  $desc",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                if (pkg.enabled) "已启用 — 在对话中让 AI 使用这些工具即可"
                else "未启用 — 打开开关后, 在对话中让 AI 使用这些工具",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("关闭") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
