package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.datastore.getCurrentAssistant

/** 内置工具 — 列出当前助手已启用的本地工具 ID (与「助手 → 本地工具」一致) */
@Composable
fun SettingBuiltinToolsPage(
    onBack: () -> Unit,
) {
    val settingsStore: SettingsStore = koinInject()
    val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools = koinInject()
    val settings = remember { settingsStore.settingsFlow.value }
    val assistant = settings.getCurrentAssistant()
    // 本地工具实际 ID (与模型侧注入同源) — 精确核对
    val enabledLocalTools = remember(settings) {
        runCatching { localTools.getTools(assistant.localTools) }.getOrDefault(emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("内置工具") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
        )
        Text(
            "当前助手已启用本地工具 ${enabledLocalTools.size} 个（精确 ID，与模型侧注入一致）",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(enabledLocalTools, key = { it.name }) { tool ->
                Text(
                    "  ${tool.name}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
