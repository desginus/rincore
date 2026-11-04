package me.rerere.rikkahub.ui.pages.chatmode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * ═══ 聊天模式入口（v5.3.47 双模式外壳）═══
 *
 * 目标形态：`[工作 = RinCore]` 与 `[聊天 = Mika]` 是同一个 APK 里两个**完全独立**的应用，
 * 通过抽屉底部的一个按钮切换，两者不共享任何运行时状态。
 *
 * 当前进度：**外壳已就位（模式状态 + 切换 + 持久化）**，Mika 的业务代码尚未接入。
 * 这里刻意不做假的界面 —— 一个假的聊天页只会让人误以为已经接上了。
 *
 * 接入 Mika 时要满足的边界（写在这里，防止将来"不知不觉又合并了"）：
 *  1. **各自的依赖容器**：RinCore 的 Koin 与 Mika 的容器互不可见；
 *  2. **各自的数据库**：不同 db 文件名，绝不共表；
 *  3. **各自的偏好**：Mika 不读 RinCore 的 DataStore，反之亦然；
 *  4. **各自的技能 / 对话 / MCP 服务器**：三者在两边是两套独立数据；
 *  5. **唯一共享**：`files/workspaces/`（Linux 沙箱）与这个外壳 APK。
 */
@Composable
fun ChatModeEntry(
    onSwitchBackToWork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "聊天模式",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Mika",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "外壳与切换已经就位，Mika 的业务代码尚未接入这一版。\n\n" +
                    "接入后这里会是完全独立的一套：自己的对话、技能、MCP 服务器与偏好，" +
                    "与工作模式互不可见；两者唯一共用的是 Linux 沙箱（工作区目录）。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onSwitchBackToWork, modifier = Modifier.fillMaxWidth()) {
                Text("返回工作模式")
            }
        }
    }
}
