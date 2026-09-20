package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingPluginsPage.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.operit.market.InstalledPackage
import me.rerere.rikkahub.data.operit.market.InstalledPackageStore
import me.rerere.rikkahub.data.operit.market.MarketInstallService
import me.rerere.rikkahub.data.operit.runtime.OperitToolProvider
import org.koin.compose.koinInject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun SettingPluginsPage(
    onBack: () -> Unit,
) {
    val pluginManager: PluginManager = koinInject()
    val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = koinInject()
    // v4.5.33: 来自市场的插件 (Operit script/package) — 融入本页分类展示
    val installedStore: InstalledPackageStore = koinInject()
    val operitToolProvider: OperitToolProvider = koinInject()
    val marketInstallService: MarketInstallService = koinInject()
    val installedPackages by installedStore.installedFlow.collectAsState(initial = emptyList())
    // v4.6.3: 只显示真正从市场安装的 (内置工具包归 生态模块页, 不在本页混淆)
    val operitPackages = installedPackages.filter {
        (it.type == "script" || it.type == "package") &&
            !me.rerere.rikkahub.data.operit.runtime.OperitBuiltinPackages.isBuiltin(it)
    }
    val settings = settingsStore.settingsFlow.value
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }
    // v4.5.33: 市场插件详情弹窗 (工具清单)
    var detailPkg by remember { mutableStateOf<InstalledPackage?>(null) }

    // 每次 refreshTick 变化重新取快照 (refresh 是 suspend, 完成后自增触发重组)
    // v3.6.110: claw 插件变化也触发刷新 (plugin_install 装完立即出现在列表)
    val clawPluginsTick by me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry.plugins.collectAsState()
    val plugins = remember(refreshTick, clawPluginsTick) { pluginManager.pluginsUiSnapshot() }
    val refreshing = remember { androidx.compose.runtime.mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("插件") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
            actions = {
                TextButton(
                    enabled = !refreshing.value,
                    onClick = {
                        scope.launch {
                            refreshing.value = true
                            runCatching { pluginManager.refresh() }
                            refreshTick++
                            refreshing.value = false
                        }
                    },
                ) { Text(if (refreshing.value) "刷新中" else "刷新") }
            },
        )
        Text(
            "插件是独立能力模块：目录放入 workspace 文件区 .plugins/<插件名>/ 即安装。" +
                "插件技能经独立工具读取，桥接工具经 workspace 沙箱常驻启动（与 STDIO MCP 同机制）。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            // v4.5.33: 来自市场的插件区块 (Operit 生态 — 应用市场安装)
            if (operitPackages.isNotEmpty()) {
                item(key = "operit_header") {
                    Text(
                        "来自市场的插件",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(operitPackages, key = { "operit_" + it.entryId }) { pkg ->
                    OperitPluginCard(
                        pkg = pkg,
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
                item(key = "ws_header") {
                    Text(
                        "workspace 插件",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (plugins.isEmpty() && operitPackages.isEmpty()) {
                item {
                    Text(
                        "暂无插件。可在应用市场安装，或创建 workspace 文件区 .plugins/<插件名>/ 目录，" +
                            "内含 plugin.yaml（name/description/command）与可选 SKILL.md、桥接脚本，然后刷新。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            run {
                items(plugins, key = { it.name }) { plugin ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    plugin.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                ) {
                                    Text(
                                        plugin.bridgeStatus,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                                // v3.6.117: ClawHub 插件可删除 (目录删除 + 桥接清理)
                                if (plugin.bridgeStatus == "已安装（ClawHub）") {
                                    TextButton(onClick = {
                                        scope.launch {
                                            me.rerere.rikkahub.ecosystem.plugin.ClawPluginRegistry
                                                .removePlugin(plugin.name, settingsStore)
                                        }
                                    }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                            if (plugin.description.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    plugin.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                buildString {
                                    if (plugin.hasSkill) append("技能")
                                    if (plugin.hasSkill && plugin.hasBridge) append(" + ")
                                    if (plugin.hasBridge) append("桥接工具")
                                    if (!plugin.hasSkill && !plugin.hasBridge) append("声明仅")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            // v3.6.105: 对话开始时强制启动 (技能正文注入 system)
                            if (plugin.hasSkill) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "对话开始时强制启动",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = settings.getCurrentAssistant().forcedSkills.contains("plugin__${plugin.name}__skill"),
                                        onCheckedChange = { checked ->
                                            val cur = settings.getCurrentAssistant().forcedSkills
                                            val newSet = if (checked) {
                                                cur + "plugin__${plugin.name}__skill"
                                            } else {
                                                cur - "plugin__${plugin.name}__skill"
                                            }
                                            scope.launch {
                                                settingsStore.update { s ->
                                                    val curAssistant = s.getCurrentAssistant()
                                                    s.copy(
                                                        assistants = s.assistants.map { a ->
                                                            if (a.id == curAssistant.id) a.copy(forcedSkills = newSet) else a
                                                        }
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // v4.5.33: 工具清单弹窗
        detailPkg?.let { pkg ->
            OperitPluginDetailDialog(
                pkg = pkg,
                tools = runCatching { operitToolProvider.describePackage(pkg) }.getOrDefault(emptyList()),
                onDismiss = { detailPkg = null },
            )
        }
    }
}


/**
 * v4.5.33: 市场插件卡片 (Operit script/package) —
 * 与 workspace 插件同页展示 (插件页分类融合)
 */
@Composable
private fun OperitPluginCard(
    pkg: InstalledPackage,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onUninstall: () -> Unit,
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
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        if (pkg.type == "script") "脚本" else "工具包",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "v${pkg.version} · 来自应用市场",
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
                Switch(
                    checked = pkg.enabled,
                    onCheckedChange = onToggle,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUninstall) {
                    Text("卸载", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}


/**
 * v4.5.33: 市场插件详情弹窗 — 工具清单 (替代 Operit 的 UI 面板:
 * RinCore 中插件交互由模型对话驱动, 此弹窗展示"装了什么、能干什么")
 */
@Composable
private fun OperitPluginDetailDialog(
    pkg: InstalledPackage,
    tools: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    // v4.5.34: ModalBottomSheet + LazyColumn — 长清单可滚 (弹窗铁律),
    // 修复 AlertDialog 内容超高卡住无法上滑的问题
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
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
                "v${pkg.version} · ${if (pkg.type == "script") "脚本插件" else "工具包 (ToolPkg)"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (tools.isEmpty()) {
                    item {
                        Text(
                            "未解析到工具。此插件可能为 UI 型 (其面板在 Operit 中提供) — " +
                                "在 RinCore 中交互由模型对话驱动。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    item {
                        Text(
                            "工具清单 (${tools.size})",
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
                else "未启用 — 打开上方开关后, 在对话中让 AI 使用这些工具",
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
