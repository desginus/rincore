package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.tools.buildAssistantToolPool
import me.rerere.rikkahub.data.ai.tools.routing.ToolRouter
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import org.koin.compose.koinInject

/** 内置工具 — 精确到工具 ID 与数量 (与模型侧完全同源, 核对工具总数用) */
@Composable
fun SettingBuiltinToolsPage(
    onBack: () -> Unit,
) {
    val settingsStore: SettingsStore = koinInject()
    val skillManager: SkillManager = koinInject()
    val localTools: LocalTools = koinInject()
    val mcpManager: McpManager = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val workspaceRepository: WorkspaceRepository = koinInject()

    val settings = remember { settingsStore.settingsFlow.value }
    val assistant = settings.getCurrentAssistant()
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
                workspaceRepository = workspaceRepository,
            )
        }.getOrDefault(emptyList())
    }
    val router = remember(settings) {
        ToolRouter(
            settings.toolDomainOverrides, settings.customDomainDescriptions, settings.customDomains,
            settings.customDomainKeywords, settings.domainNameOverrides, settings.hiddenDomains, settings.removedBuiltinDomains
        )
    }
    // 分类结果 (与模型侧同源) — 精确核对: 每个工具 ID 归属的域
    val classified = remember(pool, settings) { router.classifyAll(pool) }
    val unclassified = pool.filter { classified[router.classifyTool(it)]?.contains(it) != true }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text("内置工具") },
            navigationIcon = {
                androidx.compose.material3.TextButton(onClick = onBack) { Text("返回") }
            },
        )
        Text(
            "工具池共 ${pool.size} 个工具 · ${classified.size} 个域（与模型侧同源精确统计）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            classified.toSortedMap().forEach { (domain, tools) ->
                item(key = "header_$domain") {
                    Text(
                        "▸ $domain [${tools.size}个]",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(tools.sortedBy { it.name }, key = { "tool_$domain${it.name}" }) { tool ->
                    Text(
                        "  ${tool.name}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
