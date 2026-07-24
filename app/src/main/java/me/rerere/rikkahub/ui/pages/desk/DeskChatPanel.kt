package me.rerere.rikkahub.ui.pages.desk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons

@Composable
fun DeskChatPanel(
    messages: List<DeskMessage>,
    isRunning: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text("RinCore Desk", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("人机协同编码工作站", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("直接告诉我你要做什么，我会先出规划再执行。\n你随时可中断，手动修改后让我继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.weight(1f))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp), reverseLayout = true) {
                    items(messages.reversed(), key = { it.hashCode() }) { msg ->
                        DeskMessageItem(msg)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                placeholder = { Text("描述你要做什么...", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                maxLines = 4,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isRunning) {
                IconButton(onClick = onStop) {
                    Icon(HugeIcons.StopCircle, "中断",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                }
            } else {
                IconButton(onClick = { if (input.isNotBlank()) { onSend(input); input = "" } },
                    enabled = input.isNotBlank()) {
                    Icon(HugeIcons.ArrowUp02, "发送",
                        tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DeskMessageItem(msg: DeskMessage) {
    val isUser = msg.role == DeskMessageRole.USER
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val align = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = align,
    ) {
        Box(modifier = Modifier.background(bg, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(msg.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

data class DeskMessage(val role: DeskMessageRole, val content: String)
enum class DeskMessageRole { USER, AI, SYSTEM }
