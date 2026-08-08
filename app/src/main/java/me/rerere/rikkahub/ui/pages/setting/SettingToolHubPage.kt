package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.Screen
import me.rerere.rikkahub.ui.components.settings.CardGroup

/** 工具管理上层入口 — 收集 工具对照 / MCP 服务器 / 关于和分享 (设置页瘦身) */
@Composable
fun SettingToolHubPage(
    navController: NavHostController,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text("工具管理") },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(me.rerere.rikkahub.hugeicons.HugeIcons.ArrowLeft01, "返回")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            CardGroup(title = { Text("工具管理") }) {
                item(
                    onClick = { navController.navigate(Screen.SettingMcp) },
                    leadingContent = { Icon(me.rerere.rikkahub.hugeicons.HugeIcons.McpServer, null) },
                    supportingContent = { Text("MCP 服务器连接与管理（SSE / StreamableHTTP / STDIO）") },
                    headlineContent = { Text("MCP 服务器") },
                )
                item(
                    onClick = {
                        // 工具对照（开发者）— 同源验证页
                        navController.navigate(Screen.SettingToolCompare)
                    },
                    leadingContent = { Icon(me.rerere.rikkahub.hugeicons.HugeIcons.Puzzle, null) },
                    supportingContent = { Text("系统工具地图 / List Domains / Invoke Tools 三信源对照") },
                    headlineContent = { Text("工具对照（开发者）") },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingAbout) },
                    leadingContent = { Icon(me.rerere.rikkahub.hugeicons.HugeIcons.Clapping01, null) },
                    supportingContent = { Text("版本信息 / 分享 / 关于") },
                    headlineContent = { Text("关于和分享") },
                )
            }
        }
    }
}
