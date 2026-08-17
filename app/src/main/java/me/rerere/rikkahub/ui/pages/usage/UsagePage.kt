package me.rerere.rikkahub.ui.pages.usage

/* ───【自研】UsagePage.kt — OpenCode 用量查询页 (v3.8.0)
 * 4 个环形图: 滚动窗口(5h)/本周/本月/重置倒计时, 颜色取系统 UI 色
 * 右上角 API Key 入口, 进入自动查询 + 下拉刷新
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.usage.UsageApi
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun UsagePage(onBack: () -> Unit = {}) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val apiKey = settings.opencodeApiKey

    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<UsageApi.UsageResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(apiKey) }

    suspend fun doQuery() {
        if (apiKey.isBlank()) {
            error = "未配置 Open Code API Key，点击右上角设置后自动查询"
            usage = null
            return
        }
        loading = true
        usage = UsageApi.fetchUsage(apiKey)
        error = if (usage == null) "查询失败，请检查 API Key 或网络后下拉重试" else null
        loading = false
    }

    // 进入页面自动查询一次; API Key 变化时重新查询 (实时动态)
    LaunchedEffect(apiKey) { doQuery() }

    val pullState = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("用量查询") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    IconButton(onClick = {
                        keyInput = apiKey
                        showKeyDialog = true
                    }) {
                        Icon(HugeIcons.Settings02, "API Key")
                    }
                },
            )

            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = { scope.launch { doQuery() } },
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (apiKey.isBlank()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("未配置 API Key", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "点击右上角齿轮填写 Open Code API Key 后，页面将实时动态查询",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (usage == null && error != null) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(error ?: "", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    } else if (usage != null) {
                        val u = usage!!
                        item {
                            UsageRingCard(
                                title = "滚动窗口",
                                subtitle = "近 5 小时用量",
                                percent = u.rolling.percent?.toFloat() ?: 0f,
                                resetAt = u.rolling.resetsAt,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        item {
                            UsageRingCard(
                                title = "本周",
                                subtitle = "周限额用量",
                                percent = u.weekly.percent?.toFloat() ?: 0f,
                                resetAt = u.weekly.resetsAt,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        item {
                            UsageRingCard(
                                title = "本月",
                                subtitle = "月限额用量",
                                percent = u.monthly.percent?.toFloat() ?: 0f,
                                resetAt = u.monthly.resetsAt,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        item {
                            val resetInfo = nearestReset(u)
                            UsageRingCard(
                                title = "重置倒计时",
                                subtitle = "最近窗口重置",
                                percent = resetInfo.elapsedPercent,
                                centerText = resetInfo.remainingText,
                                resetAt = null,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showKeyDialog) {
        AlertDialog(
            onDismissRequest = { showKeyDialog = false },
            title = { Text("Open Code API Key") },
            text = {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showKeyDialog = false
                    scope.launch {
                        settingsStore.update { it.copy(opencodeApiKey = keyInput.trim()) }
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showKeyDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UsageRingCard(
    title: String,
    subtitle: String,
    percent: Float,
    color: Color,
    resetAt: String? = null,
    centerText: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            UsageRing(
                percent = percent.coerceIn(0f, 100f),
                color = color,
                centerTop = centerText ?: "${percent.toInt()}%",
                centerBottom = if (centerText != null) "已消耗" else "已使用",
            )
            if (resetAt != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "重置于 ${formatResetAt(resetAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageRing(
    percent: Float,
    color: Color,
    centerTop: String,
    centerBottom: String,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Canvas(Modifier.size(160.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = color.copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (percent > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = percent * 3.6f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                centerTop,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Text(
                centerBottom,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── 重置倒计时计算 ──
private data class ResetInfo(val elapsedPercent: Float, val remainingText: String)

private fun nearestReset(u: UsageApi.UsageResult): ResetInfo {
    val now = System.currentTimeMillis()
    data class W(val label: String, val resetsAt: String?, val windowMs: Long)

    val windows = listOf(
        W("rolling", u.rolling.resetsAt, 5 * 60 * 60 * 1000L),
        W("weekly", u.weekly.resetsAt, 7 * 24 * 60 * 60 * 1000L),
        W("monthly", u.monthly.resetsAt, 30 * 24 * 60 * 60 * 1000L),
    )

    // 取最近的未来重置点
    val nearest = windows
        .mapNotNull { w ->
            val ts = w.resetsAt?.let { parseEpochMs(it) } ?: return@mapNotNull null
            w to ts
        }
        .filter { (_, ts) -> ts > now }
        .minByOrNull { (_, ts) -> ts - now }

    if (nearest == null) return ResetInfo(0f, "未知")

    val (w, resetTs) = nearest
    val windowStart = resetTs - w.windowMs
    val elapsed = (now - windowStart).coerceAtLeast(0L).toFloat()
    val total = w.windowMs.toFloat()
    val elapsedPercent = (elapsed / total * 100f).coerceIn(0f, 100f)

    val remainMs = (resetTs - now).coerceAtLeast(0L)
    val hours = remainMs / 3_600_000
    val mins = (remainMs % 3_600_000) / 60_000
    val remainingText = when {
        hours > 0 -> "${hours}h ${mins}m 后重置"
        mins > 0 -> "${mins}m 后重置"
        else -> "即将重置"
    }
    return ResetInfo(elapsedPercent, remainingText)
}

private fun parseEpochMs(iso: String): Long? = runCatching {
    Instant.parse(iso).toEpochMilli()
}.getOrNull()

private fun formatResetAt(iso: String): String = runCatching {
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
    formatter.format(Instant.parse(iso))
}.getOrElse { iso }
