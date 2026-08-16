package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingPermissionsPage.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkahub.data.permissions.PermissionInventory
import me.rerere.rikkahub.ui.theme.CustomColors

/**
 * 权限自动发现与管理 — 扫描声明权限 + 特殊权限, 按状态分组,
 * 一键跳转系统授予页。保活关键权限 (精确闹钟/电池优化) 置顶。
 */
@Composable
fun SettingPermissionsPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<PermissionInventory.Row>>(emptyList()) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        rows = PermissionInventory.build(context)
    }

    // 运行时权限请求 launcher — 返回后刷新
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("权限管理") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                actions = {
                    IconButton(onClick = { refreshTick++ }) { Icon(HugeIcons.Refresh01, "刷新") }
                },
                colors = CustomColors.topBarColors
            )
        },
    ) { pad ->
        BackHandler { onBack() }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "后台保障链: 精确闹钟 + 电池优化豁免 + 通知 — 确保定时任务在后台被杀后仍能准时执行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(rows, key = { it.id }) { row ->
                PermissionRow(
                    row = row,
                    onGrant = {
                        when (val g = row.grant) {
                            is PermissionInventory.GrantAction.Runtime ->
                                runCatching { runtimeLauncher.launch(g.permission) }
                            is PermissionInventory.GrantAction.SystemSettings ->
                                runCatching {
                                    context.startActivity(g.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure {
                                    android.widget.Toast.makeText(
                                        context, "无法打开设置页: ${it.message}", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            PermissionInventory.GrantAction.None -> {}
                        }
                        refreshTick++
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    row: PermissionInventory.Row,
    onGrant: () -> Unit,
) {
    val granted = row.status == PermissionInventory.Status.GRANTED
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !granted) { onGrant() },
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (granted) "已授予" else "未授予",
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!granted) {
                Spacer(Modifier.width(4.dp))
                Icon(HugeIcons.ArrowRight01, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}
