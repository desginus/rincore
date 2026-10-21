package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.File02
import java.io.File

/**
 * v5.0.40 用户定案：**应用文件浏览器** —— 把应用私有目录彻底暴露，让用户自己看清、自己删。
 *
 * 根目录 = `context.filesDir.parentFile`（即 /data/data/<pkg>/），
 * 列出全部子目录与大小（含 databases / cache / code_cache / files / shared_prefs …），
 * 可逐级进入、可删除单项（删目录需二次确认）。
 *
 * 说明：不做任何"智能清理"，只提供真相 —— 用户自己判断删什么。
 */
@Composable
fun SettingAppStoragePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val root: File = remember { context.filesDir?.parentFile ?: context.filesDir }

    var current by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<Pair<File, Long>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    fun reload(dir: File) {
        loading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                dir.listFiles()?.map { f ->
                    f to runCatching {
                        if (f.isFile) f.length()
                        else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }.getOrDefault(0L)
                }?.sortedByDescending { it.second }.orEmpty()
            }
            entries = list
            current = dir
            loading = false
        }
    }

    LaunchedEffect(root) { reload(root) }

    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text("应用文件", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = current.absolutePath,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            val parent = current.parentFile
                            val boundary = root.absolutePath
                            if (parent != null && current.absolutePath != boundary) reload(parent)
                            else onBack()
                        },
                    ) { Text("返回") }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                CardGroup {
                    item(
                        headlineContent = { Text("总计：${human(entries.sumOf { it.second })}") },
                        supportingContent = { Text("当前目录下 ${entries.size} 项（含子目录递归）") },
                    )
                }
            }
            items(entries, key = { it.first.absolutePath }) { (file, size) ->
                CardGroup {
                    item(
                        onClick = {
                            if (file.isDirectory) reload(file)
                        },
                        leadingContent = {
                            CardGroupIcon(if (file.isDirectory) HugeIcons.Folder01 else HugeIcons.File02)
                        },
                        headlineContent = {
                            Text(
                                file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(human(size))
                                    if (file.isDirectory) append(" · 目录（点击进入）")
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        trailingContent = {
                            androidx.compose.material3.IconButton(onClick = { pendingDelete = file }) {
                                androidx.compose.material3.Icon(HugeIcons.Delete01, contentDescription = "删除")
                            }
                        },
                    )
                }
            }
        }
    }

    // 删除确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${target.name}？") },
            text = {
                Text(
                    buildString {
                        append("路径：${target.absolutePath}\n\n")
                        append("这是真实文件操作，删除后不可恢复。")
                        if (target.name == "workspaces") append("\n\n⚠ 这是工作区目录，删了全部工作区数据就没了。")
                        if (target.name == "files") append("\n\n⚠ 这是 files 根，会连带 workspaces 一起删。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { target.deleteRecursively() }.getOrDefault(false) }
                        pendingDelete = null
                        toaster.show(if (ok) "已删除 ${target.name}" else "删除失败（可能被占用）")
                        reload(current)
                    }
                }) { Text("确认删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}
