/* 【域 I·数据存储】 — 页面 | 地图: docs/APP_MAP.md §I */
package me.rerere.rikkahub.ui.pages.usage

/* ───【自研】UsagePage.kt — 用量查询页 (v4.8.59 整页重写 — 用户定版)
 * v3.8.2 初版 → v4.8.59 重写: 旧实现的问题 (用户实证) —
 *   ① 密钥族分两套页面 (sk→OpenCode 页 / user_→Command Code 页), 交互不一致,
 *      不同厂商密钥在大小视窗下无法正常运行;
 *   ② 视图模式切换 (cards/focus) 渲染分支与数据过滤错位, 满额密钥消失;
 *   ③ 返回失效 (RouteActivity 未接 onBack, 默认空 lambda);
 *   ④ 查询慢 — 逐密钥串行 HTTP, 无缓存, 每次进页全量等待。
 * 新交互 (用户定版): 进入即列表 — 全部密钥均为小卡片 (点击进详情);
 *   点击卡片弹出详细信息 = 原"大卡片全视图"形态 (环形大卡 ×4, 呈现不变);
 *   返回键/按钮只关闭详情弹层; 查询并行化 + 内存缓存直出 + 60s 新鲜窗口。
 * ───────────────────────────────────────────────────────────────*/
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.content.ClipData
import android.widget.Toast
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Settings02
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.usage.CommandCodeUsageApi
import me.rerere.rikkahub.data.usage.UsageApi
import me.rerere.rikkahub.data.usage.UsageMiniCardData
import me.rerere.rikkahub.data.usage.UsageQuery
import me.rerere.rikkahub.data.usage.KeyQueryState
import me.rerere.rikkahub.data.usage.openCodeMiniCard
import org.koin.compose.koinInject
import java.time.Instant

@Composable
fun UsagePage(onBack: () -> Unit = {}) {
    val settingsStore = koinInject<SettingsStore>()
    val settings by settingsStore.settingsFlow.collectAsState()
    val scope = rememberCoroutineScope()

    val activeKey = settings.opencodeApiKey
    val savedKeys = settings.opencodeApiKeys
    val allKeys = remember(activeKey, savedKeys) {
        (listOf(activeKey) + savedKeys).filter { it.isNotBlank() }.distinct()
    }

    // v4.8.59 重写: 缓存直出 (进入即显) + 后台并行刷新; 查询失败保留旧数据
    var usages by remember { mutableStateOf(UsageQuery.snapshot()) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var showKeyDialog by remember { mutableStateOf(false) }
    // v4.8.78: 空密钥折叠组展开态
    var showNoSub by remember { mutableStateOf(false) }

    suspend fun doQuery(force: Boolean) {
        if (allKeys.isEmpty()) {
            usages = emptyMap()
            errorText = null
            return
        }
        // 非强制时: 全部密钥均在新鲜窗口内 → 零网络 (进入页面秒开)
        if (!force && allKeys.all { UsageQuery.hasFresh(it) }) return
        loading = true
        val results = UsageQuery.fetchAll(allKeys)
        // 失败保留旧数据 (旧值仍展示; 刷新成功即覆盖)
        usages = buildMap {
            for (k in allKeys) {
                val r = results[k]
                if (r != null) put(k, r) else usages[k]?.let { prev -> put(k, prev) }
            }
        }
        // v4.8.78: 仅真失败计数 (空密钥 = 无套餐 → 折叠组, 不进错误横幅)
        val failed = allKeys.filter { results[it] is KeyQueryState.Failed }
        errorText = when {
            failed.isEmpty() -> null
            failed.size == allKeys.size -> "查询失败，请检查密钥或网络后下拉重试"
            else -> "部分密钥查询失败：${failed.joinToString("、") { maskKey(it) }}"
        }
        loading = false
    }

    // 进入页面: 缓存直出; 存在非新鲜密钥时才自动刷新 (并行)
    LaunchedEffect(allKeys) { doQuery(force = false) }

    // v3.18.0: 密钥统一保存收口 (保留) — 当前 key 不在卡包时自动收编
    LaunchedEffect(activeKey) {
        if (activeKey.isNotBlank() && activeKey !in savedKeys) {
            settingsStore.update { it.copy(opencodeApiKeys = (listOf(activeKey) + it.opencodeApiKeys).distinct()) }
        }
    }

    val pullState = rememberPullToRefreshState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("用量查询") },
            navigationIcon = {
                TextButton(onClick = onBack) { Text("返回") }
            },
            actions = {
                IconButton(onClick = { showKeyDialog = true }) {
                    Icon(HugeIcons.Settings02, "API Key 卡包")
                }
            },
        )

        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { scope.launch { doQuery(force = true) } },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (allKeys.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("未配置 API Key", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "点击右上角卡包填写 API Key 后自动查询（sk- 或 user_ 开头）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    errorText?.let { msg ->
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Text(
                                    msg,
                                    Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    val visibleKeys = allKeys.filter { usages[it] !is KeyQueryState.NoSubscription }
                    val noSubKeys = allKeys.filter { usages[it] is KeyQueryState.NoSubscription }
                    items(visibleKeys, key = { it }) { k ->
                        UsageKeyCard(
                            key = k,
                            data = (usages[k] as? KeyQueryState.Ok)?.data?.let { openCodeMiniCard(it) },
                            loading = loading,
                            onClick = { selectedKey = k },
                        )
                    }
                    // v4.8.78: 无套餐密钥折叠组 (不展示明细卡; 展开仅列密钥)
                    if (noSubKeys.isNotEmpty()) {
                        item(key = "__no_sub_fold__") {
                            NoSubscriptionFold(
                                keys = noSubKeys,
                                expanded = showNoSub,
                                onToggle = { showNoSub = !showNoSub },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showKeyDialog) {
        KeyCardDialog(
            settingsStore = settingsStore,
            currentKey = activeKey,
            savedKeys = savedKeys,
            initialInput = activeKey,
            onDismiss = { showKeyDialog = false },
        )
    }

    // v4.8.59: 详情弹层 (大卡片全视图, 呈现与旧焦点视图一致) — 自带返回处理:
    // 系统返回键 / 返回按钮均只关闭弹层 (修复旧版"返回 UI 失效")
    selectedKey?.let { key ->
        val state = usages[key]
        val data = (state as? KeyQueryState.Ok)?.data
        BackHandler { selectedKey = null }
        Dialog(
            onDismissRequest = { selectedKey = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { Text(maskKey(key)) },
                        navigationIcon = {
                            TextButton(onClick = { selectedKey = null }) { Text("返回") }
                        },
                        actions = {
                            // v4.8.61: 详情内直达刷新 (并行查询, 全密钥一起更新)
                            TextButton(onClick = { scope.launch { doQuery(force = true) } }) {
                                Text("刷新")
                            }
                        },
                    )
                    if (data == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // v4.8.78: 空密钥 (无套餐) 直达提示; 其余保持加载/不可用提示
                                if (state is KeyQueryState.NoSubscription) {
                                    Text(
                                        "该密钥当前无套餐（查询无可显示信息）",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    CircularProgressIndicator()
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "数据加载中或暂不可用，关闭后下拉重试",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item {
                                UsageRingCard(
                                    title = "滚动窗口",
                                    subtitle = "近 5 小时用量",
                                    percent = data.rolling.percent?.toFloat() ?: 0f,
                                    resetAt = data.rolling.resetsAt,
                                    color = usageGradientColor(data.rolling.percent ?: 0),
                                )
                            }
                            item {
                                UsageRingCard(
                                    title = "本周",
                                    subtitle = "周限额用量",
                                    percent = data.weekly.percent?.toFloat() ?: 0f,
                                    resetAt = data.weekly.resetsAt,
                                    color = usageGradientColor(data.weekly.percent ?: 0),
                                )
                            }
                            item {
                                UsageRingCard(
                                    title = "本月",
                                    subtitle = "月限额用量",
                                    percent = data.monthly.percent?.toFloat() ?: 0f,
                                    resetAt = data.monthly.resetsAt,
                                    color = usageGradientColor(data.monthly.percent ?: 0),
                                )
                            }
                            item {
                                val resetInfo = nearestReset(data)
                                // v3.12.8: 重置倒计时颜色与其他三卡相反 (用户定版):
                                // 刚用完 (等待久) 红 → 临近重置 (额度恢复) 绿
                                val resetColor = Color(
                                    CommandCodeUsageApi.resetColorArgb(
                                        (100f - resetInfo.elapsedPercent).let { p ->
                                            val nearest = nearestResetWindowMs(data)
                                            (nearest * (p / 100f)).toLong()
                                        },
                                        5 * 60 * 60 * 1000L,
                                    )
                                )
                                UsageRingCard(
                                    title = "重置倒计时",
                                    subtitle = "最近窗口重置",
                                    percent = resetInfo.elapsedPercent,
                                    bottomText = resetInfo.remainingText,
                                    color = resetColor,
                                    bottomColor = resetColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 小卡片 (列表形态; 点击进详情) — v4.8.59 重写 ──
@Composable
private fun UsageKeyCard(
    key: String,
    data: UsageMiniCardData?,
    loading: Boolean,
    onClick: () -> Unit,
) {
    // v4.8.60 (用户定版): 取消"使用中"标记 — 内容重写后所有密钥同权展示
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    maskKey(key),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (data == null) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            "查询失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (data != null) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MiniRing(data.p5 ?: 0, "5h", usageGradientColor(data.p5 ?: 0))
                    MiniRing(data.pw ?: 0, "周", usageGradientColor(data.pw ?: 0))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MiniRing(data.pm ?: 0, "月", usageGradientColor(data.pm ?: 0))
                    // 重置环红→绿 (与三环反向, v3.12.8 用户定版)
                    MiniRing(
                        data.resetElapsedPct, "重置",
                        Color(CommandCodeUsageApi.resetColorArgb(data.resetRemainingMs ?: 0L, data.resetWindowMs)),
                    )
                }
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
    bottomColor: Color? = null,
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
                color = bottomColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
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
    // v4.8.60 (用户定版): 「查看」弹层 — 显示完整密钥 (可选文本 + 一键复制)
    var viewingKey by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API Key 卡包") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it },
                    placeholder = { Text("输入新密钥 sk- 或 user_ 开头") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "已存密钥：「查看」显示完整密钥；删除后不再保留",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // v4.8.60 (用户定版): 取消"使用中"标记与点击切换 — 每个条目
                // 「查看」(完整密钥) + 「删除」; 查看位于删除左侧
                // v4.8.61 (用户硬规则): 列表弹窗必须可滑 + 高度限制 —
                // 密钥多时该栏会超出屏幕, 竖滚 + 320dp 上限
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                savedKeys.forEach { savedKey ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                maskKey(savedKey),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { viewingKey = savedKey }) {
                                Text("查看")
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

    // v4.8.60: 完整密钥查看弹层 (可选文本 + 复制)
    viewingKey?.let { vk ->
        AlertDialog(
            onDismissRequest = { viewingKey = null },
            title = { Text("完整密钥") },
            text = {
                SelectionContainer {
                    Text(vk, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("api_key", vk)))
                    }
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { viewingKey = null }) { Text("关闭") }
            },
        )
    }
}

// v3.12.5: 全密钥渐变 (Command Code 同源 ARGB)
private fun usageGradientColor(percent: Int): Color =
    Color(CommandCodeUsageApi.usageColorArgb(percent))

private fun nearestResetWindowMs(u: UsageApi.UsageResult): Long {
    val now = System.currentTimeMillis()
    data class W(val resetsAt: String?, val windowMs: Long)
    return listOf(
        W(u.rolling.resetsAt, 5 * 60 * 60 * 1000L),
        W(u.weekly.resetsAt, 7 * 24 * 60 * 60 * 1000L),
        W(u.monthly.resetsAt, 30 * 24 * 60 * 60 * 1000L),
    ).mapNotNull { w ->
        val ts = w.resetsAt?.let { parseEpochMs(it) } ?: return@mapNotNull null
        if (ts > now) w.windowMs else null
    }.minOrNull() ?: (5 * 60 * 60 * 1000L)
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

// ── v4.8.78: 无套餐 (空密钥) 折叠组 — 不展示明细卡, 展开仅列出密钥 ──
@Composable
private fun NoSubscriptionFold(
    keys: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "空密钥 · 无套餐 (${keys.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    keys.forEach { k ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(maskKey(k), style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "无套餐",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

