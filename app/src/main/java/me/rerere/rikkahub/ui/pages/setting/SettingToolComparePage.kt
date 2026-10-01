/**
 * 工具对照（开发者）— 信源统一验证页
 *
 * 同一份 ZoneMap 渲染三个面向模型的输出：
 *   1. 系统提示里的工具矩阵地图（buildMatrixMap）
 *   2. list_zones 的内容
 *   3. invoke_tools("帮助") 的内容（buildHelpText）
 * 三者必须完全一致 —— 任何差异即信源分裂。页顶另做算术自校验：
 * Σ(各区直接工具数) + 顶层直连工具数 == 工具池总数。
 */
/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.ai.tools.routing.ZoneRouter
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
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
    val workspaceRepository: WorkspaceRepository = koinInject()
    val operitToolProvider: me.rerere.rikkahub.data.operit.runtime.OperitToolProvider = koinInject()

    val assistant = settings.getCurrentAssistant()
    val globalRevision by settingsStore.settingsRevision.collectAsState()
    val pool = remember(settings, globalRevision) {
        runCatching {
            buildAssistantToolPool(
                settings = settings,
                assistant = assistant,
                localTools = localTools,
                skillManager = skillManager,
                conversationRepo = conversationRepo,
                mcpManager = mcpManager,
                settingsStore = settingsStore,
                workspaceRepository = workspaceRepository,
                operitToolProvider = operitToolProvider,
            )
        }.getOrDefault(emptyList())
    }
    val router = remember(settings, globalRevision) { zoneRouterOf(settings) }
    val view = remember(pool, router) { router.zoneMap(pool) }

    val matrixText = remember(view, router) { router.buildMatrixMap(pool) }
    val listText = remember(view, router) { renderListZonesText(view, router) }
    val helpText = remember(view, router) { router.buildHelpText(pool) }

    var tab by remember { mutableIntStateOf(0) }
    val consistent = router.selfCheck(view, pool)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具对照（开发者）") },
                navigationIcon = { androidx.compose.material3.TextButton(onClick = onBack) { Text("返回") } },
                colors = CustomColors.topBarColors,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            BackHandler { onBack() }
            Text(
                "统一视图: ${view.counts.size} 个工具区 · ${pool.distinctBy { it.name }.size} 个工具 · " +
                    "区直属 ${view.counts.values.sum()} + 顶层直连 ${view.topLevel.size} — " +
                    if (consistent) "完全一致" else "请对照 bug",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("工具矩阵地图") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("List Zones") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Invoke Tools 帮助") })
            }
            val text = when (tab) {
                0 -> matrixText
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

/** list_zones 渲染 —— 与 ZoneTools.listZonesTool 同一逻辑（同源） */
private fun renderListZonesText(view: ZoneRouter.ZoneMap, router: ZoneRouter): String = buildString {
    appendLine("可用工具区 (共 ${view.counts.size} 个, ${view.roots.size} 个顶级):")
    for (root in view.roots) {
        val direct = view.counts[root] ?: 0
        appendLine("- ${router.label(root)} [$direct 个工具]${kw(router.keywordsOf(root))}")
        for (child in view.children[root].orEmpty()) {
            appendLine("  - ${router.label(child)} [${view.counts[child] ?: 0} 个工具]${kw(router.keywordsOf(child))}")
        }
    }
    appendLine()
    appendLine("顶层直连工具 (${view.topLevel.size} 个): ${view.topLevel.map { it.name }.sorted().joinToString("、")}")
    appendLine("调 invoke_tools(\"工具区名\") 查看工具区内的工具；所有工具均可直接调用。")
}

private fun kw(keywords: List<String>): String =
    if (keywords.isEmpty()) "" else " [触发条件] ${keywords.joinToString(" ")}"
