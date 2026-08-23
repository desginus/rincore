package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.SavedApiKey
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject

/**
 * 密钥快捷切换 (v3.9.11 重写自管理持久化):
 * 组件自身持有 SettingsStore 引用, 任何操作 (保存/切换/删除/改备注) 都直接
 * scope.launch settingsStore.update 把对应 provider 的 savedKeys 与当前 apiKey
 * 写入 settings.providers 对应项, 立即落盘. 不再走 onEdit 中转链, 不依赖页面
 * 保存按钮. 重启后从 PreferencesStore 序列化恢复, 不会丢失.
 *
 * UI 反向同步: apiKey/savedKeys 来自调用方传入的 provider 实例, 调用方应订阅
 * settingsStore.settingsFlow 让 provider 跟随 Settings 变化, 形成单向数据流.
 */
@Composable
fun ApiKeyQuickSwitcher(
    currentKey: String,
    savedKeys: List<SavedApiKey>,
    provider: ProviderSetting,
    onKeysChange: (List<SavedApiKey>) -> Unit,
    onSelectKey: (String) -> Unit,
) {
    val settingsStore = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()

    // 任何对 savedKeys 的改动直接落盘: 同时更新 settings.providers 中对应 provider
    fun persistKeys(keys: List<SavedApiKey>) {
        onKeysChange(keys) // 同步 UI 内存 (页面 internalProvider)
        scope.launch {
            settingsStore.update { s ->
                s.copy(providers = s.providers.map { p ->
                    if (p.id == provider.id) p.copyProvider(savedKeys = keys) else p
                })
            }
        }
    }

    // 切换当前 apiKey: 立即落盘, 不依赖页面保存
    fun persistApiKey(newKey: String) {
        onSelectKey(newKey) // 同步 UI 内存 (页面 internalProvider)
        scope.launch {
            settingsStore.update { s ->
                s.copy(providers = s.providers.map { p ->
                    if (p.id != provider.id) p
                    else when (p) {
                        is ProviderSetting.OpenAI -> p.copy(apiKey = newKey)
                        is ProviderSetting.Google -> p.copy(apiKey = newKey)
                        is ProviderSetting.Claude -> p.copy(apiKey = newKey)
                    }
                })
            }
        }
    }

    var showDialog by remember { mutableStateOf(false) }
    val title = "密钥快捷切换 (${savedKeys.size})"
    OutlinedButton(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(HugeIcons.Key01, null, modifier = Modifier.padding(end = 8.dp))
        Text(title)
    }

    if (showDialog) {
        ApiKeySwitcherDialog(
            currentKey = currentKey,
            savedKeys = savedKeys,
            onPersistKeys = { persistKeys(it) },
            onSelectKey = { persistApiKey(it) },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ApiKeySwitcherDialog(
    currentKey: String,
    savedKeys: List<SavedApiKey>,
    onPersistKeys: (List<SavedApiKey>) -> Unit,
    onSelectKey: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var noteInput by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf(-1) }
    var editingNote by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("密钥快捷切换") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("备注 (如 主要/备用/测试)") },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val key = currentKey.trim()
                            if (key.isNotEmpty()) {
                                val updated = savedKeys.toMutableList().apply {
                                    val idx = indexOfFirst { it.key == key }
                                    if (idx >= 0) set(idx, SavedApiKey(note = noteInput.trim(), key = key))
                                    else add(SavedApiKey(note = noteInput.trim(), key = key))
                                }
                                onPersistKeys(updated)
                                noteInput = ""
                            }
                        },
                    ) {
                        Text("保存当前密钥")
                    }
                }

                HorizontalDivider()

                if (savedKeys.isEmpty()) {
                    Text(
                        text = "暂无已保存密钥 — 输入备注后点保存当前密钥",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(savedKeys.size) { index ->
                            val item = savedKeys[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectKey(item.key)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    if (editingIndex == index) {
                                        OutlinedTextField(
                                            value = editingNote,
                                            onValueChange = { editingNote = it },
                                            label = { Text("备注") },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Text(
                                            text = item.note.ifEmpty { "（无备注）" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (item.key == currentKey) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Text(
                                        text = maskKey(item.key),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (editingIndex == index) {
                                    IconButton(
                                        onClick = {
                                            val updated = savedKeys.toMutableList().apply {
                                                set(index, item.copy(note = editingNote.trim()))
                                            }
                                            onPersistKeys(updated)
                                            editingIndex = -1
                                        },
                                    ) {
                                        Text("✓", style = MaterialTheme.typography.titleMedium)
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            editingIndex = index
                                            editingNote = item.note
                                        },
                                    ) {
                                        Icon(HugeIcons.PencilEdit01, "编辑备注")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        onPersistKeys(savedKeys.toMutableList().apply { removeAt(index) })
                                    },
                                ) {
                                    Icon(HugeIcons.Delete01, "删除")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun maskKey(key: String): String = if (key.length <= 8) {
    "****"
} else {
    key.take(4) + "****" + key.takeLast(4)
}