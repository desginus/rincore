/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

/* ───【自研】SettingUploadInboxPage.kt — v4.8.146 (用户定版)
 * 数据与日志 → 收件箱 (Upload): 上传文件管理 UI。
 * 每个文件标注"正在被什么地方使用" — 引用面: 用户头像 / 各助手头像 / 各助手对话背景
 * (v4.8.146 首版; 消息附件等引用面见交付说明)。匹配口径 = 文件名后缀 (物理名带
 * 上传码, 唯一; 兼容 file:// 百分号编码形态)。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Avatar
import org.koin.compose.koinInject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingUploadInboxPage(onBack: () -> Unit) {
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val files by filesManager.observe(FileFolders.UPLOAD).collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // 引用面扫描 (设置快照 × 文件清单; 变化时重算)
    val usages = remember(files, settings) {
        files.associate { f -> f.id to uploadUsageLabels(f, settings) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收件箱") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item(key = "header") {
                Text(
                    "Upload 上传文件（${files.size}）— 标注每个文件当前被使用的位置。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (files.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "暂无上传文件。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(files, key = { it.id }) { f ->
                    val labels = usages[f.id].orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                f.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatSize(f.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            buildString {
                                uploadCodeOf(f)?.let { append("码 ").append(it).append(" · ") }
                                append(formatTime(f.createdAt))
                                if (f.mimeType.isNotBlank()) append(" · ").append(f.mimeType)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        if (labels.isEmpty()) {
                            Text(
                                "未被使用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "使用中：",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                labels.forEach { label ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 物理文件名 `[码_]<原名>` — 上传码 = 前缀 10-12 位数字。 */
private fun uploadCodeOf(f: ManagedFileEntity): String? {
    val base = f.relativePath.substringAfterLast('/')
    val code = base.substringBefore('_')
    return code.takeIf { it.length in 10..12 && it.all(Char::isDigit) }
}

/**
 * 使用位置标注 (v4.8.146 引用面: 用户头像 / 各助手头像 / 各助手对话背景)。
 * 匹配 = 引用串(含 file:// 百分号编码形态) 包含该文件物理名或显示名 —
 * 上传文件物理名带唯一上传码, 误配面可忽略。
 */
private fun uploadUsageLabels(f: ManagedFileEntity, settings: Settings): List<String> {
    val physical = f.relativePath.substringAfterLast('/')
    val candidates = listOf(physical, f.displayName).filter { it.isNotBlank() }
    fun hit(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return candidates.any { c -> raw.contains(c) || decoded.contains(c) }
    }

    val out = mutableListOf<String>()
    if (hit((settings.displaySetting.userAvatar as? Avatar.Image)?.url)) {
        out += "用户头像"
    }
    settings.assistants.forEach { a ->
        val name = a.name.ifBlank { "未命名" }
        if (hit((a.avatar as? Avatar.Image)?.url)) out += "助手「$name」头像"
        if (hit(a.background)) out += "助手「$name」对话背景"
    }
    return out
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
