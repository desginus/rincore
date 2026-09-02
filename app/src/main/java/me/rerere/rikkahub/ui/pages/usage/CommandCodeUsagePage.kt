package me.rerere.rikkahub.ui.pages.usage

/* ───【自研】CommandCodeUsagePage.kt — Command Code 用量查询页 (v3.12.2)
 * 与 OpenCode UsagePage 完全对齐: 同位置/同交互 (进入自动查询+下拉刷新+
 * 卡包管理+focus/cards 视图切换), 数据源为 Command Code 双接口 (credits+
 * subscriptions 并行), 四卡: 5h 窗口 / 7d 窗口 / 本月余额 / 加油包。
 * Key 分流: UsagePage 按前缀路由 — User 开头 → 本页, sk 开头 → 原逻辑。
 * 语义: limited:false 只显示余额; exceeded 卡变红; belowThreshold 红点;
 * resetAt=0 当无重置; GOAT 目录三重校验失败静默降级只报剩余。
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.View
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.usage.CommandCodeUsageApi
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CommandCodeUsagePage(onBack: () -> Unit = {}) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val apiKey = settings.opencodeApiKey
    val savedKeys = settings.opencodeApiKeys

    val scope = rememberCoroutineScope()
    var usages by remember { mutableStateOf<Map<String, CommandCodeUsageApi.CommandCodeUsageResult?>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }
    var lastSuccessAt by remember { mutableStateOf<Long?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf(apiKey) }

    suspend fun doQuery() {
        if (apiKey.isBlank()) {
            error = "未配置 Command Code API Key，点击右上角设置后自动查询"
            usages = emptyMap()
            return
        }
        loading = true
        // 查询卡包内全部密钥 (焦点 + 历史), 多密钥卡片展示
        val keys = (listOf(apiKey) + savedKeys).distinct()
        val outcomes = keys.associateWith { CommandCodeUsageApi.fetchUsage(it) }
        val fetched = outcomes.mapValues { it.value.result }
        val activeOk = fetched[apiKey] != null
        if (activeOk) {
            usages = fetched
            stale = false
            lastSuccessAt = System.currentTimeMillis()
            error = null
        } else {
            // 拉取失败: 保留旧数据标灰 (stale), 显示具体失败原因 (HTTP code/
            // 服务端 message/网络异常类型), 供直接定位
            val reason = outcomes[apiKey]?.error ?: "未知错误"
            if (usages.isEmpty()) {
                error = "查询失败: $reason"
                usages = emptyMap()
            }
            stale = true
        }
        loading = false
    }

    LaunchedEffect(apiKey, savedKeys) { doQuery() }

    val pullState = rememberPullToRefreshState()

    // 非焦点密钥: 各窗口均有空余才显示 (与 OpenCode 同规则)
    val otherVisible = savedKeys
        .filter { it != apiKey }
        .mapNotNull { k -> usages[k]?.let { k to it } }
        .filter { (_, u) ->
            val p5 = u.fiveHour?.percent
            val pw = u.weekly?.percent
            val pm = u.monthlyUsedPercent
            listOf(p5, pw, pm).all { p -> p == null || p < 100 }
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
                    if (stale && lastSuccessAt != null) {
                        Text(
                            text = "数据已过期",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        val next = if (settings.usageViewMode == "cards") "focus" else "cards"
                        scope.launch {
                            settingsStore.update { it.copy(usageViewMode = next) }
                        }
                    }) {
                        Icon(
                            imageVector = if (settings.usageViewMode == "cards") HugeIcons.FileView else HugeIcons.View,
                            contentDescription = "切换多卡片/焦点视图",
                        )
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
                                        "点击右上角齿轮填写 Command Code API Key (user_ 开头) 后，页面将实时动态查询",
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
                        val active = usages[apiKey]!!
                        val dataTime = lastSuccessAt?.let {
                            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm"))
                        } ?: ""

                        if (showCards || focusMode) {
                            item {
                                CommandCodeKeyCard(
                                    key = apiKey,
                                    usage = active,
                                    isActive = true,
                                    stale = stale,
                                    dataTime = dataTime,
                                )
                            }
                            if (showCards) {
                                items(otherVisible, key = { it.first }) { (k, u) ->
                                    CommandCodeKeyCard(
                                        key = k,
                                        usage = u,
                                        isActive = false,
                                        stale = stale,
                                        dataTime = dataTime,
                                    )
                                }
                            }
                        } else {
                            // v3.12.7: 单密钥竖列四卡 — 恒显四卡 (与 OpenCode 一致,
                            // 无窗口数据时显示 0% + 无重置信息)
                            val fh = active.fiveHour
                            val wk = active.weekly
                                    // v3.12.7: 四卡完全对齐 OpenCode — 竖列 (环居中,
                            // 上名字下重置时间), 顺序 近5小时→周→月→重置倒计时(最下),
                            // 颜色统一用量渐变 (占比越大越红, 与 OpenCode 同向)
                            item {
                                CCUsageRingCard(
                                    title = "近 5 小时用量",
                                    subtitle = "5 小时窗口 \$ of usage",
                                    percent = fh?.percent ?: 0,
                                    bottomText = if (fh?.exceeded == true) "限额已用尽"
                                    else "剩余 " + (fh?.remaining?.let { "$" + fmt(it) } ?: "未知") + " · " +
                                        (CommandCodeUsageApi.countdownText(fh?.resetAtMs) ?: "无重置信息"),
                                    color = usageGradientColor(fh?.percent ?: 0),
                                )
                            }
                            item {
                                CCUsageRingCard(
                                    title = "周限额用量",
                                    subtitle = "7 天窗口 \$ of usage",
                                    percent = wk?.percent ?: 0,
                                    bottomText = if (wk?.exceeded == true) "限额已用尽"
                                    else "剩余 " + (wk?.remaining?.let { "$" + fmt(it) } ?: "未知") + " · " +
                                        (CommandCodeUsageApi.countdownText(wk?.resetAtMs) ?: "无重置信息"),
                                    color = usageGradientColor(wk?.percent ?: 0),
                                )
                            }
                            item {
                                CCUsageRingCard(
                                    title = "月限额用量",
                                    subtitle = "月度积分池" + if (active.catalogMatched) "" else " · 目录未匹配",
                                    percent = active.monthlyUsedPercent ?: 0,
                                    bottomText = "剩余 " + "$" + fmt(active.monthlyRemaining) +
                                        (active.monthlyTotal?.let { " / $" + fmt(it) } ?: "") +
                                        " · " + (CommandCodeUsageApi.periodEndText(active.currentPeriodEnd) ?: "重置时间未知"),
                                    color = usageGradientColor(active.monthlyUsedPercent ?: 0),
                                )
                            }
                            item {
                                // 重置倒计时: 三窗口取最近, 占比越大越红 (用户定版, 与用量卡同向)
                                val now = System.currentTimeMillis()
                                data class CW(val resetsAtMs: Long?, val windowMs: Long)
                                val windows = listOf(
                                    CW(fh?.resetAtMs, 5L * 3_600_000),
                                    CW(wk?.resetAtMs, 7L * 24 * 3_600_000),
                                    CW(active.currentPeriodEnd?.let {
                                        runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
                                    }, 30L * 24 * 3_600_000),
                                )
                                val nearest = windows
                                    .mapNotNull { w -> w.resetsAtMs?.let { w to it } }
                                    .filter { (_, ts) -> ts > now }
                                    .minByOrNull { (_, ts) -> ts - now }
                                val elapsedPct = if (nearest == null) 0f else {
                                    val (w, ts) = nearest
                                    val elapsed = (now - (ts - w.windowMs)).coerceAtLeast(0L).toFloat() / w.windowMs * 100f
                                    elapsed.coerceIn(0f, 100f)
                                }
                                val resetTs = nearest?.second
                                // v3.12.8: 重置倒计时颜色与其他三卡相反 (用户定版):
                                // 刚用完 (等待久) 红 → 临近重置 (额度恢复) 绿
                                val resetColor = if (resetTs != null && nearest != null) {
                                    Color(CommandCodeUsageApi.resetColorArgb(
                                        (resetTs - now).coerceAtLeast(0L), nearest.first.windowMs))
                                } else {
                                    usageGradientColor(elapsedPct.toInt())
                                }
                                CCUsageRingCard(
                                    title = "重置倒计时",
                                    subtitle = "最近窗口重置",
                                    percent = elapsedPct.toInt(),
                                    bottomText = if (resetTs != null) ccPeriodClockText(resetTs) else "未知",
                                    color = resetColor,
                                    bottomColor = resetColor,
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
        CommandCodeKeyCardDialog(
            settingsStore = settingsStore,
            currentKey = apiKey,
            savedKeys = savedKeys,
            initialInput = keyInput,
            onDismiss = { showKeyDialog = false },
        )
    }
}

// UsagePage.kt 的 maskKey/MiniRing 为文件级 private, 此处副本对齐同视觉
private fun maskKey(key: String): String =
    if (key.length <= 12) key.take(4) + "****" else key.take(8) + "****" + key.takeLast(4)

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

// 时段划分与 OpenCode 重置卡同款 (12 小时制 + 时段标注)
private fun ccPeriodClockText(resetTsMs: Long): String {
    val zdt = java.time.ZonedDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(resetTsMs), java.time.ZoneId.systemDefault()
    )
    val h = zdt.hour
    val m = zdt.minute
    val period = when (h) {
        in 11..13 -> "中午"
        in 14..17 -> if (h < 17 || m < 30) "下午" else "傍晚"
        in 17..18 -> "傍晚"
        in 19..22 -> "夜晚"
        23 -> "深夜"
        in 0..2 -> "深夜"
        in 3..5 -> "凌晨"
        in 6..7 -> "清晨"
        else -> "早晨"
    }
    val h12 = h % 12
    val h12d = if (h12 == 0) 12 else h12
    return "$period ${h12d}:${m.toString().padStart(2, '0')} 重置"
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

// v3.12.4: 0→100% 绿→黄→橙→红连续渐变 (Api 层 ARGB 换算)
private fun usageGradientColor(percent: Int): Color = Color(CommandCodeUsageApi.usageColorArgb(percent))

@Composable
private fun CCUsageRingCard(
    title: String,
    subtitle: String,
    percent: Int,
    bottomText: String,
    color: Color,
    bottomColor: Color? = null,
) {
    // v3.12.7: 布局完全复刻 UsagePage.UsageRingCard — Column 居中,
    // 环 64dp, 上名字下重置时间; exceeded 时容器 errorContainer
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
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                bottomText,
                style = MaterialTheme.typography.labelSmall,
                color = bottomColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}


@Composable
private fun CommandCodeKeyCard(
    key: String,
    usage: CommandCodeUsageApi.CommandCodeUsageResult,
    isActive: Boolean,
    stale: Boolean,
    dataTime: String,
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
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (stale) "已过期" else dataTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val fh = usage.fiveHour
            val wk = usage.weekly
            if (usage.limited && fh != null && wk != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MiniRing(fh.percent ?: 0, "5h", usageGradientColor(fh.percent ?: 0))
                    MiniRing(wk.percent ?: 0, "周", usageGradientColor(wk.percent ?: 0))
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniRing(usage.monthlyUsedPercent ?: 0, "月", usageGradientColor(usage.monthlyUsedPercent ?: 0))
            }
        }
    }
}

@Composable
private fun CommandCodeKeyCardDialog(
    settingsStore: SettingsStore,
    currentKey: String,
    savedKeys: List<String>,
    initialInput: String,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initialInput) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command Code API Key 卡包") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("API Key (user_ 开头)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                if (savedKeys.isNotEmpty()) {
                    Text("已保存密钥:", style = MaterialTheme.typography.labelMedium)
                    savedKeys.forEach { k ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                kotlinx.coroutines.runBlocking {
                                    settingsStore.update { it.copy(opencodeApiKey = k) }
                                }
                                onDismiss()
                            }) {
                                Text(maskKey(k), style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                kotlinx.coroutines.runBlocking {
                                    settingsStore.update { s ->
                                        s.copy(opencodeApiKeys = s.opencodeApiKeys - k)
                                    }
                                }
                            }) {
                                Text("删除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = input.trim()
                if (k.isNotBlank()) {
                    kotlinx.coroutines.runBlocking {
                        settingsStore.update { s ->
                            s.copy(
                                opencodeApiKey = k,
                                opencodeApiKeys = (s.opencodeApiKeys + listOf(currentKey))
                                    .filter { it.isNotBlank() && it != k }
                                    .distinct(),
                            )
                        }
                    }
                }
                onDismiss()
            }) { Text("保存并查询") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
