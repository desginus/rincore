package me.rerere.rikkahub.ui.pages.setting


/* ───【自研】SettingCallTracePage.kt — 原版无此文件 (v3.8.34 整段重写)
 * 来源: RinCore 自研 (原实现只显示内存中最近一条; 本次重写为持久化两级结构)
 * 结构: 列表 (最多 10 轮近期对话, 轮次 ID = 精确时间戳) → 点击进入该轮日志报告;
 *       顶部提供全量 Markdown 导出 (FileProvider 分享) 与清空。
 * ───────────────────────────────────────────────────────────────*/
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Upload01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.log.LogSessionStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTs(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ts))

/** 导出全部会话为 Markdown 文件, 返回可分享的 content:// Uri */
private fun exportSessionsMarkdown(context: Context): Uri? {
    val md = runBlocking { LogSessionStore.exportMarkdown() }
    return runCatching {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val file = File(
            dir,
            "rincore-log-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".md"
        )
        file.writeText(md)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/** 导出为分享 Intent (chooser 内可选 保存/发送) */
private fun shareMarkdown(context: Context) {
    val uri = exportSessionsMarkdown(context) ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "RinCore 运行日志")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "导出运行日志"))
}

@Composable
fun SettingCallTracePage() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sessions by LogSessionStore.sessionsFlow.collectAsStateWithLifecycle()
    var selectedSessionId by remember { mutableStateOf<String?>(null) }

    // 系统返回键: 详情态先退回列表
    androidx.activity.compose.BackHandler(enabled = selectedSessionId != null) {
        selectedSessionId = null
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(if (selectedSessionId == null) "运行日志" else "轮次报告") },
                navigationIcon = {
                    if (selectedSessionId == null) {
                        BackButton()
                    } else {
                        IconButton(onClick = { selectedSessionId = null }) {
                            Icon(HugeIcons.ArrowLeft01, "返回列表")
                        }
                    }
                },
                actions = {
                    if (selectedSessionId == null) {
                        IconButton(onClick = { shareMarkdown(context) }) {
                            Icon(HugeIcons.Upload01, "导出日志")
                        }
                        IconButton(onClick = {
                            scope.launch { LogSessionStore.deleteAll() }
                        }) {
                            Icon(HugeIcons.Delete01, null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val selected = selectedSessionId?.let { id ->
            sessions.firstOrNull { it.id == id }
        }
        if (selected != null) {
            SessionDetail(
                session = selected,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        } else {
            SessionList(
                sessions = sessions,
                onOpen = { selectedSessionId = it.id },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }
}

/** 列表: 最多 10 轮近期对话, 标题 = 精确时间戳 ID */
@Composable
private fun SessionList(
    sessions: List<LogSessionStore.LogSession>,
    onOpen: (LogSessionStore.LogSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) {
        Box(modifier = modifier.padding(32.dp)) {
            Text(
                "暂无运行日志\n\n发送一条消息后，每一轮对话的运行轨迹都会以时间戳轮次保存，最多保留 10 轮。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(session) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.id,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${formatTs(session.startedAt)} · ${session.events.size} 事件 · ${session.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (session.isActive) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = "记录中",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 详情: 该轮完整运行报告 */
@Composable
private fun SessionDetail(
    session: LogSessionStore.LogSession,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${session.id} · ${session.events.size} 事件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            if (session.isActive) {
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
            items(session.events, key = { "${it.ts}-${it.step}" }) { event ->
                TraceItem(event = event, startTs = session.startedAt)
            }
        }
    }
}

@Composable
private fun TraceItem(
    event: LogSessionStore.LogSessionEvent,
    startTs: Long,
) {
    val elapsedMs = event.ts - startTs
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
                    text = "+${elapsedMs}ms",
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