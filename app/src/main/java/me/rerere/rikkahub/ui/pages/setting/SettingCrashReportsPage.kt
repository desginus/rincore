/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

/* ───【自研】SettingCrashReportsPage.kt — 崩溃报告管理（v5.0.9）
 * 用途: 列出 CrashHandler 落盘的崩溃报告（Markdown 全量），支持 预览/复制/导出到下载/分享。
 * 目的: 崩溃对话框复制不可靠时的稳定反馈通道 —— 导出 Markdown 文件交给开发侧。
 * 入口: 设置→数据与日志→崩溃报告；崩溃后 SafeModeActivity 同功能按钮。
 * ───────────────────────────────────────────────────────────────*/
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.CrashHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingCrashReportsPage() {
    val navController = LocalNavController.current
    val context = LocalContext.current
    var reports by remember { mutableStateOf<List<File>>(emptyList()) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { reports = CrashHandler.listReports(context) }

    fun export(file: File) {
        val path = CrashHandler.exportToDownloads(context, file)
        Toast.makeText(
            context,
            if (path != null) context.getString(R.string.crash_report_exported, path)
            else context.getString(R.string.crash_report_export_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    fun share(file: File) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    CrashHandler.shareIntent(context, file),
                    context.getString(R.string.crash_report_share),
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setting_page_crash_reports)) },
                navigationIcon = {
                    TextButton(onClick = { navController.popBackStack() }) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { padding ->
        if (reports.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.crash_reports_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                item(key = "header") {
                    Text(
                        stringResource(R.string.crash_reports_desc),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(reports, key = { it.absolutePath }) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text("${fmtCrashTime(file)} · ${file.length() / 1024} KB") },
                        trailingContent = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = { share(file) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(stringResource(R.string.crash_report_share)) }
                                TextButton(
                                    onClick = { export(file) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) { Text(stringResource(R.string.crash_report_export)) }
                            }
                        },
                        modifier = Modifier.clickable {
                            previewFile = file
                            previewText = runCatching { file.readText() }.getOrDefault("(读取失败)")
                        },
                    )
                }
            }
        }
    }

    previewFile?.let { file ->
        AlertDialog(
            onDismissRequest = { previewFile = null },
            title = { Text(file.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { export(file) }) {
                    Text(stringResource(R.string.crash_report_export))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("crash", previewText))
                    }) { Text(stringResource(R.string.crash_report_copy)) }
                    TextButton(onClick = { previewFile = null }) { Text("关闭") }
                }
            },
        )
    }
}

private fun fmtCrashTime(file: File): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
        .format(Date(file.lastModified()))
