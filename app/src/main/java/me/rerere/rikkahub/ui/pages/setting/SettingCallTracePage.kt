package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingCallTracePage.kt — 原版无此文件
 * 来源: RinCore 自研新增 (功能与依赖见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.ai.CallTracer
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun SettingCallTracePage() {
    val trace by CallTracer.traceFlow.collectAsStateWithLifecycle()
    val traceId = CallTracer.getTraceId()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("运行日志") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        }
    ) { innerPadding ->
        if (trace.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp)) {
                Text(
                    "暂无运行日志\n\n发送一条消息后，此处将显示完整的代码运行轨迹。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${trace.size} events",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (CallTracer.isActive) {
                        Text(
                            text = "记录中...",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(trace) { event -> TraceItem(event) }
                }
            }
        }
    }
}

@Composable
private fun TraceItem(event: CallTracer.TraceEvent) {
    val phaseColor = when (event.phase) {
        "INIT" -> MaterialTheme.colorScheme.primary
        "STEP" -> MaterialTheme.colorScheme.tertiary
        "SEND" -> MaterialTheme.colorScheme.secondary
        "RECV" -> MaterialTheme.colorScheme.secondary
        "TOOL" -> MaterialTheme.colorScheme.error
        "FINISH" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "+${event.elapsedMs}ms",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = phaseColor.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = "${event.phase}/${event.step}",
                        fontSize = 10.sp,
                        color = phaseColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = event.detail,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (event.metrics.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.metrics.entries.joinToString(" | ") { "${it.key}=${it.value}" },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
