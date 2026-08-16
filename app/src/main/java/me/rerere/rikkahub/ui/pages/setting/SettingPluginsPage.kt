package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.plugin.PluginManager
import org.koin.compose.koinInject

@Composable
fun SettingPluginsPage(
    onBack: () -> Unit,
) {
    val pluginManager: PluginManager = koinInject()
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }

    // 每次 refreshTick 变化重新取快照 (refresh 是 suspend, 完成后自增触发重组)
    val plugins = remember(refreshTick) { pluginManager.pluginsUiSnapshot() }
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
        if (plugins.isEmpty()) {
            Text(
                "暂无插件。创建 workspace 文件区 .plugins/<插件名>/ 目录，" +
                    "内含 plugin.yaml（name/description/command）与可选 SKILL.md、桥接脚本，然后刷新。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
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
                        }
                    }
                }
            }
        }
    }
}
