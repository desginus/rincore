package me.rerere.rikkahub.ui.pages.desk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DeskBottomPanel(
    activeTab: DeskTab,
    onTabChange: (DeskTab) -> Unit,
    messages: List<DeskMessage>,
    isRunning: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onFileSelect: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                DeskTab.CHAT -> DeskChatPanel(messages, isRunning, onSend, onStop, Modifier.fillMaxSize())
                DeskTab.BROWSER -> BrowserAgentOverlay(Modifier.fillMaxSize())
                DeskTab.FILES -> FileTreePlaceholder(onFileSelect, Modifier.fillMaxSize())
            }
        }
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp,
        ) {
            NavigationBarItem(
                selected = activeTab == DeskTab.CHAT,
                onClick = { onTabChange(DeskTab.CHAT) },
                icon = { Text("💬", style = MaterialTheme.typography.titleSmall) },
                label = { Text("AI 对话", style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            NavigationBarItem(
                selected = activeTab == DeskTab.BROWSER,
                onClick = { onTabChange(DeskTab.BROWSER) },
                icon = { Text("🌐", style = MaterialTheme.typography.titleSmall) },
                label = { Text("浏览器", style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
            NavigationBarItem(
                selected = activeTab == DeskTab.FILES,
                onClick = { onTabChange(DeskTab.FILES) },
                icon = { Text("📁", style = MaterialTheme.typography.titleSmall) },
                label = { Text("文件", style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

enum class DeskTab { CHAT, BROWSER, FILES }
