/**
 * 工具对照（开发者）— 信源统一验证页 (v3.5.43)
 *
 * 用户需求: 所有信息源头在设置中生成用户可见 UI, 方便开发者直接对照。
 * 本页渲染三个信源的同一份输出 (全部从 unifiedDomainView 派生):
 *   1. 系统 Prompt 工具调度地图 (buildLayer1)
 *   2. List Domains 内容 (unifiedDomainView 渲染)
 *   3. Invoke Tools 帮助内容 (buildHelpText)
 * 三个 Tab 应显示完全一致的域树/计数 — 任何差异即信源分裂 bug。
 */
package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject

@Composable
fun SettingToolComparePage(
    settings: Settings,
    onBack: () -> Unit,
) {
    val skillManager: SkillManager = koinInject()
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val mcpManager: me.rerere.rikkahub.data.ai.mcp.McpManager = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val settingsStore: SettingsStore = koinInject()

    val assistant = settings.getCurrentAssistant()
    // 单一源头: 与模型侧完全同源的工具池 + 统一视图
    val pool = remember(settings) {
        runCatching {
            buildAssistantToolPool(
                settings = settings,
                assistant = assistant,
                localTools = localTools,
                skillManager = skillManager,
                conversationRepo = conversationRepo,
                mcpManager = mcpManager,
                settingsStore = settingsStore,
            )
        }.getOrDefault(emptyList())
    }
    val router = remember(settings) {
        ToolRouter(
            settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains,
            settings.customDomainKeywords, settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains
        )
    }
    val view = remember(pool, settings) { router.unifiedDomainView(pool) }

    val layer1Text = remember(view) { router.buildLayer1(pool) }
    val listText = remember(view) { renderListDomainsText(view, router) }
    val helpText = remember(view) { router.buildHelpText(pool) }

    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具对照（开发者）") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(HugeIcons.ArrowLeft01, "返回")
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Text(
                "统一视图: ${view.classified.size}个域 · ${pool.size}个工具 — 三 Tab 应完全一致（同源）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("系统 Prompt 工具地图") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("List Domains") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Invoke Tools 帮助") })
            }
            val text = when (tab) {
                0 -> layer1Text
                1 -> listText
                else -> helpText
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            )
        }
    }
}

/** List Domains 渲染 — 与 DomainTools.list_domains execute 同一逻辑 (同源) */
private fun renderListDomainsText(
    view: ToolRouter.UnifiedDomainView,
    router: ToolRouter,
): String = buildString {
    appendLine("可用域 (${view.tree.size}个根域):")
    for ((root, subs) in view.tree) {
        val rootCount = view.counts[root] ?: 0
        val rootKw = router.getKeywords(root)
        val kwText = if (rootKw.isEmpty()) "" else " [触发: ${rootKw.take(8).joinToString("、")}]"
        appendLine("- $root [${rootCount}个工具]$kwText")
        for (sub in subs) {
            val subCount = view.counts[sub] ?: 0
            val subKw = router.getKeywords(sub)
            val subKwText = if (subKw.isEmpty()) "" else " [触发: ${subKw.take(8).joinToString("、")}]"
            appendLine("  - $sub [${subCount}个工具]$subKwText")
        }
    }
    appendLine()
    appendLine("调 invoke_tools(\"域名称\") 查看域内工具；所有工具均可直接调用。")
}
