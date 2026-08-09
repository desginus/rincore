package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.ui.components.ui.CardGroup

/** 工具管理上层入口 — 收口 工具对照 / 关于和分享 (MCP 已独立到能力模块) */
@Composable
fun SettingToolHubPage(
    onOpenCompare: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("工具管理") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
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
                    onClick = onOpenCompare,
                    supportingContent = { Text("系统工具地图 / List Domains / Invoke Tools 三信源对照") },
                    headlineContent = { Text("工具对照（开发者）") },
                )
                item(
                    onClick = onOpenAbout,
                    supportingContent = { Text("版本信息 / 分享 / 关于") },
                    headlineContent = { Text("关于和分享") },
                )
            }
        }
    }
}
