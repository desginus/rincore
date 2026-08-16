package me.rerere.rikkahub.workflow.ui


/* ───【自研】WorkflowsScreen.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiEditing
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import org.koin.compose.koinInject

/** 工作流列表 — 浏览/启停/删除/手动运行 */
@Composable
fun WorkflowsScreen(
    onBack: () -> Unit,
) {
    val repository: WorkflowRepository = koinInject()
    val engine: WorkflowEngine = koinInject()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WorkflowRepository.Loaded>>(emptyList()) }

    LaunchedEffect(Unit) {
        items = repository.listAll()
    }

    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("工作流") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(HugeIcons.ArrowLeft01, null) } },
                colors = CustomColors.topBarColors
            )
        },
    ) { pad ->
        BackHandler { onBack() }
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp)
        ) {
            Text(
                "工作流 = 触发器 + 条件 + 动作序列。让 AI 用 workflow_create 创建，或用下方的触发器描述。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Text("暂无工作流。告诉 AI：\"创建一个每天早上 8 点执行 XXX 的工作流\"", style = MaterialTheme.typography.bodyLarge)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.entity.id }) { loaded ->
                        WorkflowCard(
                            def = loaded.definition,
                            onToggle = { enabled ->
                                scope.launch {
                                    repository.setEnabled(loaded.entity.id, enabled)
                                    items = repository.listAll()
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    repository.deleteCascading(loaded.entity.id)
                                    items = repository.listAll()
                                }
                            },
                            onRun = {
                                scope.launch {
                                    engine.fire(loaded.entity.id)
                                    items = repository.listAll()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    def: WorkflowDefinition,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (def.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(HugeIcons.AiEditing, null, modifier = Modifier.size(28.dp),
                tint = if (def.enabled) Color.Unspecified else Color.Gray)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(def.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${triggerLabel(def.trigger)} · ${def.actions.size} 个动作",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!def.description.isNullOrBlank()) {
                    Text(
                        def.description,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRun) { Icon(HugeIcons.ArrowRight01, "运行", modifier = Modifier.size(18.dp)) }
            Switch(checked = def.enabled, onCheckedChange = onToggle)
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onDelete) {
                Icon(HugeIcons.Delete01, "删除", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun triggerLabel(trigger: TriggerSpec): String = when (trigger) {
    is TriggerSpec.TimeCron -> trigger.timeOfDay?.let { "每天 $it" }
        ?: trigger.cron?.let { "cron: $it" } ?: "时间触发"
    is TriggerSpec.Manual -> "手动"
    is TriggerSpec.BootCompleted -> "开机"
    is TriggerSpec.BatteryBelow -> "电量低于 ${trigger.thresholdPercent}%"
    is TriggerSpec.BatteryAbove -> "电量高于 ${trigger.thresholdPercent}%"
    is TriggerSpec.PowerConnected -> "充电"
    is TriggerSpec.PowerDisconnected -> "断电"
    is TriggerSpec.WifiConnected -> "连接 WiFi${trigger.ssid?.let { ": $it" } ?: ""}"
    is TriggerSpec.WifiDisconnected -> "断开 WiFi${trigger.ssid?.let { ": $it" } ?: ""}"
    is TriggerSpec.GeofenceEnter -> "进入地理围栏"
    is TriggerSpec.GeofenceExit -> "离开地理围栏"
    is TriggerSpec.AppLaunched -> "打开应用: ${trigger.packageName}"
    is TriggerSpec.AppClosed -> "关闭应用: ${trigger.packageName}"
    is TriggerSpec.NotificationReceived -> "通知到达"
    is TriggerSpec.HeadphonesPlugged -> "插入耳机"
    is TriggerSpec.HeadphonesUnplugged -> "拔出耳机"
    is TriggerSpec.BluetoothDeviceConnected -> "蓝牙连接${trigger.deviceAddress?.let { ": $it" } ?: ""}"
    is TriggerSpec.BluetoothDeviceDisconnected -> "蓝牙断开${trigger.deviceAddress?.let { ": $it" } ?: ""}"
    is TriggerSpec.ScreenOn -> "屏幕亮起"
    is TriggerSpec.ScreenOff -> "屏幕熄灭"
}
