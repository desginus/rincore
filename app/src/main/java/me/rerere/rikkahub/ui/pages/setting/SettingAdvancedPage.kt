package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import me.rerere.rikkahub.Screen
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Megaphone01
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.data.datastore.Settings

import me.rerere.rikkahub.ui.components.ui.CardGroup

/**
 * 高级功能统一入口 (v3.6.16)
 *
 * 聚合原分散在设置各分组的入口：搜索服务 / 语音服务 / Web 服务器 / 扩展管理 /
 * 权限管理 / 工具对照（开发者）。设置页保持六大分组整洁，本页承载服务与开发者工具。
 *
 * 分组划分：
 *  - 服务：搜索服务、语音服务、Web 服务器
 *  - 扩展与权限：扩展管理、权限管理
 *  - 开发者：工具对照（三信源核对）
 */
@Composable
fun SettingAdvancedPage(
    settings: Settings,
    onBack: () -> Unit,
) {
    val navController = me.rerere.rikkahub.ui.context.LocalNavController.current
    var showToolCompare by remember { mutableStateOf(false) }

    // 工具对照页 (内部状态 — 与设置页原行为一致)
    if (showToolCompare) {
        SettingToolComparePage(
            settings = settings,
            onBack = { showToolCompare = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("高级功能") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        androidx.compose.material3.Icon(me.rerere.hugeicons.stroke.ArrowLeft01, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── 服务 ──────────────────────────────────────────────
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text("服务") },
            ) {
                item(
                    onClick = { navController.navigate(Screen.SettingSearch) },
                    leadingContent = { Icon(HugeIcons.GlobalSearch, null) },
                    supportingContent = { Text(me.rerere.rikkahub.R.string.setting_page_search_service_desc) },
                    headlineContent = { Text(me.rerere.rikkahub.R.string.setting_page_search_service) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingSpeech) },
                    leadingContent = { Icon(HugeIcons.Megaphone01, null) },
                    supportingContent = { Text(me.rerere.rikkahub.R.string.setting_page_tts_service_desc) },
                    headlineContent = { Text(me.rerere.rikkahub.R.string.setting_page_tts_service) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingWeb) },
                    leadingContent = { Icon(HugeIcons.ServerStack01, null) },
                    supportingContent = { Text(me.rerere.rikkahub.R.string.setting_page_web_server_desc) },
                    headlineContent = { Text(me.rerere.rikkahub.R.string.setting_page_web_server) },
                )
            }

            // ── 扩展与权限 ────────────────────────────────────────
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text("扩展与权限") },
            ) {
                item(
                    onClick = { navController.navigate(Screen.Extensions) },
                    leadingContent = { Icon(HugeIcons.Package, null) },
                    supportingContent = { Text("插件 / 生态扩展管理") },
                    headlineContent = { Text("扩展管理") },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingPermissions) },
                    leadingContent = { Icon(HugeIcons.Settings01, null) },
                    supportingContent = { Text("权限自动发现 · 后台保障链引导") },
                    headlineContent = { Text("权限管理") },
                )
            }

            // ── 开发者 ────────────────────────────────────────────
            CardGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
                title = { Text("开发者") },
            ) {
                item(
                    onClick = { showToolCompare = true },
                    leadingContent = { Icon(HugeIcons.Settings03, null) },
                    supportingContent = { Text("系统工具地图 / List Domains / Invoke Tools 三信源对照") },
                    headlineContent = { Text("工具对照（开发者）") },
                )
            }

            Text(
                text = "以上入口原分散在设置各分组，v3.6.16 起统一聚合于此。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
