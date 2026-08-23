package me.rerere.rikkahub.ui.pages.usage

/* ───【自研】UsagePage.kt — OpenCode 用量查询页 (v3.8.2)
 * 4 个环形图: 滚动窗口(5h)/本周/本月/重置倒计时, 颜色取系统 UI 色
 * 单密钥: 竖列全屏; 多密钥: 卡片布局 (卡内 2x2 横排)
 * 非焦点密钥仅在其 3 个用量均有空余 (percent<100) 时显示
 * 右上角卡包: 密钥保存/切换/删除; 每次进入自动查询 + 下拉刷新
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

@Composable
fun UsagePage(onBack: () -> Unit = {}) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val apiKey = settings.opencodeApiKey
    val savedKeys = settings.opencodeApiKeys

    val scope = rememberCoroutineScope()
    var usages by remember { mutableStateOf<Map<String, UsageApi.UsageResult?>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(apiKey) }

    suspend fun doQuery() {
        if (apiKey.isBlank()) {
            error = "未配置 Open Code API Key，点击右上角设置后自动查询"
            usages = emptyMap()
            return
        }
        loading = true
        // 查询卡包内全部密钥 (焦点密钥 + 历史密钥), 供多密钥卡片展示
        val keys = (listOf(apiKey) + savedKeys).distinct()
        usages = keys.associateWith { UsageApi.fetchUsage(it) }
        error = if (usages[apiKey] == null) {
            "查询失败，请检查 API Key 或网络后下拉重试"
        } else {
            null
        }
        loading = false
    }

    // 进入页面自动查询一次; API Key 变化 (切换/删除) 时重新查询
    LaunchedEffect(apiKey, savedKeys) { doQuery() }

    val pullState = rememberPullToRefreshState()

    // 非焦点密钥: 3 个用量均有空余 (percent<100) 才显示; null 视为未满
    val otherVisible = savedKeys
        .filter { it != apiKey }
        .mapNotNull { k -> usages[k]?.let { k to it } }
        .filter { (_, u) ->
            listOf(u.rolling.percent, u.weekly.percent, u.monthly.percent)
                .all { p -> p == null || p < 100 }
        }
    val showCards = settings.usageViewMode == "cards" && otherVisible.isNotEmpty()
    val focusMode = settings.usageViewMode == "focus"

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("用量查询") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = {
                        val next = if (settings.usageViewMode == "cards") "focus" else "cards"
                        scope.launch {
                            settingsStore.update { it.copy(usageViewMode = next) }
                        }
                    }) {
                        Text(if (settings.usageViewMode == "cards") "焦点视图" else "多卡片")
                    }
                    IconButton(onClick = {
                        keyInput = apiKey
                        showKeyDialog = true
                    }) {
                        Icon(HugeIcons.Settings02, "API Key 卡包")
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
                    contentPadding = PaddingValues(16.dp),
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
                    } else if (error != null && usages[apiKey] == null) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(error ?: "", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    } else if (usages[apiKey] != null) {
                        val activeUsage = usages[apiKey]!!

                        if (showCards || focusMode) {
                            // ── 多卡片视图: 焦点卡 + 其他密钥卡 ──
                            // ── 焦点视图: 仅焦点密钥单卡 (与\"一张卡展示一毛一样\") ──
                            item {
                                KeyUsageCard(
                                    key = apiKey,
                                    usage = activeUsage,
                                    isActive = true,
                                )
                            }
                            if (showCards) {
                                items(otherVisible, key = { it.first }) { (k, u) ->
                                    KeyUsageCard(
                                        key = k,
                                        usage = u,
                                        isActive = false,
                                    )
                                }
                            }
                        } else {
                            // ── 单密钥: 竖列全屏 ──
                            item {
                                UsageRingCard(
                                    title = "滚动窗口",
                                    subtitle = "近 5 小时用量",
                                    percent = activeUsage.rolling.percent?.toFloat() ?: 0f,
                                    resetAt = activeUsage.rolling.resetsAt,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            item {
                                UsageRingCard(
                                    title = "本周",
                                    subtitle = "周限额用量",
                                    percent = activeUsage.weekly.percent?.toFloat() ?: 0f,
                                    resetAt = activeUsage.weekly.resetsAt,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            item {
                                UsageRingCard(
                                    title = "本月",
                                    subtitle = "月限额用量",
                                    percent = activeUsage.monthly.percent?.toFloat() ?: 0f,
                                    resetAt = activeUsage.monthly.resetsAt,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            item {
                                val resetInfo = nearestReset(activeUsage)
                                UsageRingCard(
                                    title = "重置倒计时",
                                    subtitle = "最近窗口重置",
                                    percent = resetInfo.elapsedPercent,
                                    bottomText = resetInfo.remainingText,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
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
        KeyCardDialog(
            settingsStore = settingsStore,
            currentKey = apiKey,
            savedKeys = savedKeys,
            initialInput = keyInput,
            onDismiss = { showKeyDialog = false },
        )
    }
}

// ── 多密钥卡片 (2x2 横排 4 用量) ──
@Composable
private fun KeyUsageCard(
    key: String,
    usage: UsageApi.UsageResult,
    isActive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    maskKey(key),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "使用中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniRing(usage.rolling.percent ?: 0, "5h", MaterialTheme.colorScheme.primary)
                MiniRing(usage.weekly.percent ?: 0, "周", MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniRing(usage.monthly.percent ?: 0, "月", MaterialTheme.colorScheme.tertiary)
                val resetInfo = nearestReset(usage)
                MiniRing(resetInfo.elapsedPercent.toInt(), "重置", MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MiniRing(percent: Int, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
            Canvas(Modifier.size(48.dp)) {
                val stroke = 5.dp.toPx()
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
                if (percent > 0) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = percent.coerceIn(0, 100) * 3.6f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Text(
                "$percent%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 单密钥竖列卡片 ──
@Composable
private fun UsageRingCard(
    title: String,
    subtitle: String,
    percent: Float,
    color: Color,
    resetAt: String? = null,
    bottomText: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            UsageRing(
                percent = percent.coerceIn(0f, 100f),
                color = color,
                centerText = "${percent.toInt()}%",
            )
            Spacer(Modifier.height(8.dp))
            Text(
                bottomText ?: resetAt?.let { formatRemaining(it) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UsageRing(
    percent: Float,
    color: Color,
    centerText: String,
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
        Canvas(Modifier.size(64.dp)) {
            val stroke = 6.dp.toPx()
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
        Text(
            centerText,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

// ── 卡包弹窗 (输入/列表/切换/删除) ──
@Composable
private fun KeyCardDialog(
    settingsStore: SettingsStore,
    currentKey: String,
    savedKeys: List<String>,
    initialInput: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf(initialInput) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Code API Key 卡包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("输入新密钥 sk-...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "已存密钥：点击卡片切换，删除后不再保留",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                savedKeys.forEach { savedKey ->
                    val isActive = savedKey == currentKey
                    Card(
                        onClick = {
                            if (!isActive) {
                                scope.launch {
                                    settingsStore.update { it.copy(opencodeApiKey = savedKey) }
                                }
                            }
                        },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    maskKey(savedKey),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                if (isActive) {
                                    Text(
                                        "使用中",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    settingsStore.update {
                                        it.copy(
                                            opencodeApiKeys = it.opencodeApiKeys - savedKey,
                                            opencodeApiKey = if (savedKey == it.opencodeApiKey) "" else it.opencodeApiKey,
                                        )
                                    }
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val newKey = keyInput.trim()
                if (newKey.isNotEmpty()) {
                    onDismiss()
                    scope.launch {
                        settingsStore.update {
                            it.copy(
                                opencodeApiKey = newKey,
                                opencodeApiKeys = (listOf(newKey) + it.opencodeApiKeys).distinct(),
                            )
                        }
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
    val elapsedPercent = (elapsed / w.windowMs.toFloat() * 100f).coerceIn(0f, 100f)

    // v3.8.9: 重置倒计时卡时间注释改为精确时间 — 12 小时制 + 时段标注
    // (用户: 中午 11-14点 / 下午 14-17:30 / 傍晚 17:30-19 / 夜晚 19-23:30 /
    // 深夜 23:30-次日3点 / 凌晨 3-6点 / 清晨 6-8点 / 早晨 8-11:30)
    val remainMs = (resetTs - now).coerceAtLeast(0L)
    val zdt = java.time.ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(resetTs), java.time.ZoneId.systemDefault()
    )
    val h = zdt.hour
    val m = zdt.minute
    val h12 = h % 12
    val h12d = if (h12 == 0) 12 else h12
    val minutePad = m.toString().padStart(2, '0')
    val remainingText = "${periodOf(h, m)} ${h12d}:${minutePad} 重置"
    return ResetInfo(elapsedPercent, remainingText)
}

// 时段划分 (分钟粒度, 边界右开左闭: 11:00 起算中午, 14:00 起下午...)
private fun periodOf(hour: Int, minute: Int): String {
    val t = hour * 60 + minute
    return when {
        t >= 11 * 60 && t < 14 * 60 -> "中午"
        t >= 14 * 60 && t < 17 * 60 + 30 -> "下午"
        t >= 17 * 60 + 30 && t < 19 * 60 -> "傍晚"
        t >= 19 * 60 && t < 23 * 60 + 30 -> "夜晚"
        t >= 23 * 60 + 30 || t < 3 * 60 -> "深夜"
        t < 6 * 60 -> "凌晨"
        t < 8 * 60 -> "清晨"
        else -> "早晨"
    }
}

private fun parseEpochMs(iso: String): Long? = runCatching {
    Instant.parse(iso).toEpochMilli()
}.getOrNull()

// v3.8.8: 重置时间显示从绝对时间改为剩余时间
// v3.8.9: 超出常见小时的额度正常进位 — 超 24 小时记天, 超 7 天记周
private fun formatRemaining(iso: String): String = runCatching {
    val resetTs = Instant.parse(iso).toEpochMilli()
    val remainMs = (resetTs - System.currentTimeMillis()).coerceAtLeast(0L)
    val days = remainMs / 86_400_000
    val hours = (remainMs % 86_400_000) / 3_600_000
    val mins = (remainMs % 3_600_000) / 60_000
    when {
        days >= 7 -> {
            val weeks = days / 7
            val remainDays = days % 7
            if (remainDays > 0) "${weeks}周 ${remainDays}天 后重置" else "${weeks}周 后重置"
        }
        days > 0 -> "${days}天 ${hours}小时 后重置"
        hours > 0 -> "${hours}小时 ${mins}分钟 后重置"
        mins > 0 -> "${mins}分钟 后重置"
        else -> "即将重置"
    }
}.getOrElse { iso }

// 密钥脱敏显示 (sk-abc...xyz)
private fun maskKey(key: String): String {
    if (key.length <= 8) return key
    return key.take(6) + "..." + key.takeLast(4)
}
