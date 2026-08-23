package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.SavedApiKey
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.PenNew01
import me.rerere.hugeicons.stroke.Checkmark01

/**
 * 密钥快捷切换 (v3.9.8):
 * 点击打开弹窗, 展示已保存密钥 (备注+本体), 点击行即切换到该密钥,
 * 可保存当前密钥 (带备注), 可编辑备注, 可删除。
 */
@Composable
fun ApiKeyQuickSwitcher(
    currentKey: String,
    savedKeys: List<SavedApiKey>,
    onKeysChange: (List<SavedApiKey>) -> Unit,
    onSelectKey: (String) -> Unit,
) {
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
            onKeysChange = onKeysChange,
            onSelectKey = onSelectKey,
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ApiKeySwitcherDialog(
    currentKey: String,
    savedKeys: List<SavedApiKey>,
    onKeysChange: (List<SavedApiKey>) -> Unit,
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
                                onKeysChange(updated)
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
                                            onKeysChange(updated)
                                            editingIndex = -1
                                        },
                                    ) {
                                        Icon(HugeIcons.Checkmark01, "保存备注")
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            editingIndex = index
                                            editingNote = item.note
                                        },
                                    ) {
                                        Icon(HugeIcons.PenNew01, "编辑备注")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        onKeysChange(savedKeys.toMutableList().apply { removeAt(index) })
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