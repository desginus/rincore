/* 【域 E·设置体系】 | 地图: docs/APP_MAP.md §E */
package me.rerere.rikkahub.ui.pages.setting

/* ───【自研】SettingUploadInboxPage.kt — v4.8.146 (用户定版); v4.8.186 修订
 * 数据与日志 → 收件箱 (Upload): 上传文件管理 UI。
 * v4.8.186 (用户定版): ① 删除"未被使用"标注 — 有引用才标注, 无引用不显示任何文案；
 *   ② 引用面补齐 — 此前只扫 用户头像/助手头像/助手背景 三个面, 消息附件作为
 *   主使用面未扫描 ("全是未被使用"的根因)。匹配 = 上传码 (ASCII 前缀, 唯一,
 *   兼容 file:// 百分号编码形态) → message_node.messages 子串查 → 对话标题标注。
 * v4.8.189 (L-A3): 码解析经 UploadRef 单一解析器 (撤本地手搓前缀)。
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.UploadRef
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.compose.koinInject
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingUploadInboxPage(onBack: () -> Unit) {
    val filesManager: FilesManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val conversationRepo: ConversationRepository = koinInject()
    val files by filesManager.observe(FileFolders.UPLOAD).collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()

    // v4.8.186: 引用面扫描 — 设置快照 (头像/背景, 同步部分) + 消息附件 (DB 子串查)。
    // 异步执行 (消息查询走 message_node), 加载完成前不显示标注, 完成后自动重组。
    val usages by produceState(initialValue = emptyMap<Long, List<String>>(), files, settings) {
        value = withContext(Dispatchers.IO) {
            files.associate { f -> f.id to uploadUsageLabels(f, settings, conversationRepo) }
        }
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
                        // v4.8.186 (用户定版): 有引用才标注; 无引用不显示任何文案
                        // (撤除旧版"未被使用"四字)。
                        if (labels.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
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

/** 物理名 → 上传码（经 UploadRef 单一解析器; v4.8.189 L-A3 收口）。
 *  仅展示时间码形态 (10-12 位数字); 历史哈希码不展示 (与旧行为一致)。 */
private fun uploadCodeOf(f: ManagedFileEntity): String? {
    val ref = UploadRef.fromPhysicalName(f.relativePath.substringAfterLast('/'))
    return ref.code.takeIf { it.length in 10..12 && it.all(Char::isDigit) }
}

/**
 * 使用位置标注 — v4.8.146 引用面: 用户头像 / 各助手头像 / 各助手对话背景；
 * v4.8.186 增补: 消息附件 (经上传码 uniqueness 定位引用对话, 标对话标题)。
 * 匹配 = 引用串(含 file:// 百分号编码形态) 包含该文件物理名或显示名 —
 * 上传文件物理名带唯一上传码, 误配面可忽略。
 */
private suspend fun uploadUsageLabels(
    f: ManagedFileEntity,
    settings: Settings,
    conversationRepo: ConversationRepository,
): List<String> {
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

    // v4.8.186: 消息附件引用 — 上传码唯一 (ASCII, 不随中文名的 percent 编码失配),
    // 路径片段 "upload/<码>" 只在消息/工具结果真实引用了该文件时出现
    // (模型正文单独提码不会命中, 因为缺 "upload/" 前缀)。
    val code = uploadCodeOf(f)
    if (code != null) {
        val ids = runCatching { conversationRepo.conversationIdsReferencing("upload/$code") }
            .getOrDefault(emptyList())
        if (ids.isNotEmpty()) {
            val titles = ids.take(3).mapNotNull { id ->
                runCatching { conversationRepo.conversationTitleByIdRaw(id) }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            }
            val head = if (titles.isNotEmpty()) {
                titles.joinToString("、") { "「$it」" }
            } else {
                "共 ${ids.size} 个对话"
            }
            val tail = if (titles.isNotEmpty() && ids.size > titles.size) {
                " 等 ${ids.size} 个对话"
            } else {
                ""
            }
            out += "消息附件 · $head$tail"
        }
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
