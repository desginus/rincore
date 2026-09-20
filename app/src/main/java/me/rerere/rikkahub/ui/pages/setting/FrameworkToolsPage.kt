package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】框架工具页 (v4.6.5 — 域分类管理页"框架工具"入口的新页面)
 * 展示顶层框架工具 (FRAMEWORK_TOOL_SET + 用户移出域的豁免工具, 排除已降级的),
 * 支持把工具"移进被管理的域"或"移回顶层"的移动操作 —
 * demotedFrameworkTools 记录被域接管的框架工具 (GenerationHandler 注入判定同源)。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.data.ai.tools.FRAMEWORK_TOOL_SET
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.datastore.Settings
import org.koin.compose.koinInject

@Composable
fun FrameworkToolsPage(
    settings: Settings,
    vm: SettingVM,
    onBack: () -> Unit,
) {
    val skillManager: me.rerere.rikkahub.data.files.SkillManager = koinInject()
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = koinInject()
    val conversationRepo: me.rerere.rikkahub.data.repository.ConversationRepository = koinInject()
    val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore = koinInject()
    val workspaceRepository: me.rerere.rikkahub.data.repository.WorkspaceRepository = koinInject()
    val operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider = koinInject()

    val router = remember(settings) {
        ToolRouter(
            settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains,
            settings.customDomainKeywords, settings.domainNameOverrides, settings.hiddenDomains,
            settings.removedBuiltinDomains,
            exemptFromDomainTools = settings.exemptFromDomainTools,
        )
    }
    val descByName = remember(settings) {
        runCatching {
            buildPreviewTools(
                settings, localTools, skillManager, mcpManager,
                conversationRepo = conversationRepo,
                settingsStore = settingsStore,
                workspaceRepository = workspaceRepository,
                operitToolProvider = operitToolProvider,
            ).associate { it.name to it.description }
        }.getOrDefault(emptyMap())
    }

    // 顶层框架工具 = (静态框架集 ∪ 用户豁免) − 已降级
    val topFramework = remember(settings) {
        (FRAMEWORK_TOOL_SET + settings.exemptFromDomainTools)
            .filter { it !in settings.demotedFrameworkTools }
            .sorted()
    }
    val demoted = remember(settings) { settings.demotedFrameworkTools.sorted() }
    val domains = remember(settings) { router.validDomainLabels.sorted() }

    var movingTool by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("框架工具") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pad + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "框架工具始终注入请求体（顶层）。移进域后改由域管理接管（经 invoke_tools 加载该域时可见）；" +
                        "移回顶层则恢复到始终注入。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(
                    "顶层框架工具（${topFramework.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(topFramework, key = { "ft_$it" }) { name ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Row(
                        Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val desc = descByName[name].orEmpty()
                            if (desc.isNotBlank()) {
                                Text(desc.take(70), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        TextButton(onClick = { movingTool = name }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                            Text("移进域", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (demoted.isNotEmpty()) {
                item {
                    Text(
                        "已移进域的框架工具（${demoted.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(demoted, key = { "dm_$it" }) { name ->
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "已由域管理接管" + (settings.toolDomainOverrides[name]?.let { " · 域: $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = {
                                    // 移回顶层: 静态框架工具直接恢复; 非常规工具回"豁免(顶层)"态
                                    val newDemoted = settings.demotedFrameworkTools - name
                                    val newExempt = if (name in FRAMEWORK_TOOL_SET) settings.exemptFromDomainTools
                                    else settings.exemptFromDomainTools + name
                                    vm.updateSettings(settings.copy(demotedFrameworkTools = newDemoted, exemptFromDomainTools = newExempt))
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("移回顶层", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // 目标域选择
    movingTool?.let { tool ->
        AlertDialog(
            onDismissRequest = { movingTool = null },
            title = { Text("移动 $tool") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (domains.isEmpty()) {
                        item { Text("暂无可用域 — 先在域分类管理新建/恢复域。", style = MaterialTheme.typography.bodySmall) }
                    }
                    items(domains) { domain ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                // 移进域: 从豁免移除 (如有) + 标记降级 + 归属域
                                val newExempt = settings.exemptFromDomainTools - tool
                                val newDemoted = settings.demotedFrameworkTools + tool
                                val newOverrides = settings.toolDomainOverrides + (tool to domain)
                                vm.updateSettings(
                                    settings.copy(
                                        exemptFromDomainTools = newExempt,
                                        demotedFrameworkTools = newDemoted,
                                        toolDomainOverrides = newOverrides,
                                    )
                                )
                                movingTool = null
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(domain, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingTool = null }) { Text("取消") } },
        )
    }
}
