/* 【域 A·对话核心】 — AI 组件 | 地图: docs/APP_MAP.md §A */
package me.rerere.rikkahub.ui.components.ai


/* ───【原版对齐】WorkspaceCwdPicker.kt | 差异 ±16 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun WorkspaceCwdPickerSheet(
    workspaceId: String,
    currentCwd: String?,
    onSelectCwd: (String?) -> Unit,
    onDismiss: () -> Unit,
    // 4.8.25: 根限制 (绝对路径) — 浏览不可越界到该目录之上, 且无 currentCwd 时
    // 以该目录为起点; null = 工作区根 (无限制, 原行为)。项目包 CWD 选择传
    // 助手 CWD (语义: "只能当前助手已有的 CWD 空间内设置子 CWD")。
    rootPath: String? = null,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    val rootRel = remember(rootPath) { fromAbsolutePath(rootPath) }

    var browsePath by remember { mutableStateOf(fromAbsolutePath(currentCwd).ifBlank { rootRel }) }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(browsePath) {
        loading = true
        try {
            val result = withContext(Dispatchers.IO) {
                workspaceRepository.listFiles(workspaceId, WorkspaceStorageArea.FILES, browsePath)
            }
            entries = result.sortedWith(compareByDescending<WorkspaceFileEntry> { it.isDirectory }.thenBy { it.name })
            loading = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            entries = emptyList()
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_cwd_select_directory),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = stringResource(R.string.workspace_cwd_select_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    // 4.8.25: 根限制 — 到达 rootRel 时禁用返回 (不可越界)
                    enabled = browsePath.isNotBlank() && browsePath != rootRel,
                    onClick = {
                        val parent = browsePath.substringBeforeLast('/', missingDelimiterValue = "")
                        browsePath = if (parent.length < rootRel.length) rootRel else parent
                    },
                ) {
                    Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
                }
                Text(
                    text = toAbsolutePath(browsePath),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider()

            // v4.7.23: 布局稳定化 — 原 heightIn(max=350) 在加载前后高度跳变
            // (空->有内容), 打开后"抽动一下/按钮下移"。改固定高度: 打开即终态布局。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp),
            ) {
                val dirs = entries.filter { it.isDirectory }
                items(dirs, key = { it.path }) { entry ->
                    ListItem(
                        leadingContent = {
                            Icon(
                                imageVector = HugeIcons.Folder01,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            browsePath = entry.path
                        } ) {
Text(
                                text = entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
}
                }

                if (!loading && dirs.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.workspace_cwd_no_subdirectories),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentCwd != null) {
                    TextButton(onClick = {
                        onSelectCwd(null)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.workspace_cwd_reset))
                    }
                }
                FilledTonalButton(onClick = {
                    onSelectCwd(toAbsolutePath(browsePath))
                    onDismiss()
                }) {
                    Text(stringResource(R.string.workspace_cwd_set))
                }
            }
        }
    }
}

private const val WORKSPACE_PREFIX = "/workspace"

private fun toAbsolutePath(relativePath: String): String {
    return if (relativePath.isBlank()) WORKSPACE_PREFIX else "$WORKSPACE_PREFIX/$relativePath"
}

private fun fromAbsolutePath(absolutePath: String?): String {
    if (absolutePath.isNullOrBlank()) return ""
    return absolutePath.removePrefix("$WORKSPACE_PREFIX/").removePrefix(WORKSPACE_PREFIX)
}
