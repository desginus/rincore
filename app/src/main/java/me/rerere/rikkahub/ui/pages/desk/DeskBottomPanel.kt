package me.rerere.rikkahub.ui.pages.desk

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons

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
            DeskNavItem(DeskTab.CHAT, "AI 对话", HugeIcons.Code, activeTab, onTabChange)
            DeskNavItem(DeskTab.BROWSER, "浏览器", HugeIcons.Earth, activeTab, onTabChange)
            DeskNavItem(DeskTab.FILES, "文件", HugeIcons.Folder01, activeTab, onTabChange)
        }
    }
}

@Composable
private fun DeskNavItem(
    tab: DeskTab, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: DeskTab, onClick: (DeskTab) -> Unit,
) {
    NavigationBarItem(
        selected = active == tab,
        onClick = { onClick(tab) },
        icon = { Icon(icon, label, Modifier.size(22.dp)) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

enum class DeskTab { CHAT, BROWSER, FILES }
