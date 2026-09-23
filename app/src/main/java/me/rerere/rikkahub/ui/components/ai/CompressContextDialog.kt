package me.rerere.rikkahub.ui.components.ai


/* ───【域 A·对话核心】CompressContextDialog.kt
 * 职责: 压缩配置弹窗 (确认即关 — 无压缩态弹窗, v4.8.8 定版)
 * 常用改动: 参数项 → 表单区; 确认行为 → confirmButton (onConfirm + onDismiss)
 * 问题定位: 压缩弹窗阻塞全屏 → 本文件 (勿回退 loading 弹窗)
 * 基线: 原版移植 + 自研调整 | 地图: docs/APP_MAP.md §A | 历史: .claude/skills/rincore-bug-record
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import kotlinx.coroutines.Job
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun CompressContextDialog(
    onDismiss: () -> Unit,
    totalMessages: Int = 0,
    defaultKeep: Int = 0,
    onConfirm: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job
) {
    var additionalPrompt by remember { mutableStateOf("") }
    var selectedTokens by remember { mutableIntStateOf(4000) }
    // v3.8.28: 默认保留条数由 ContextCompressor 按对话轮 + token 60% 智能推荐,
    // 用户仍可按条数手动微调; 0 = 无可压缩空间 (不足一轮对话), 禁用确认
    var keepRecentMessages by remember { mutableIntStateOf(defaultKeep) }
    val canCompress = defaultKeep > 0
    val tokenOptions = listOf(500, 1000, 2000, 4000)

    AlertDialog(
        onDismissRequest = {
            // v4.8.6: 压缩进行中也可关闭 — "后台运行"语义 (Job 挂在 VM scope,
            // 关闭弹窗不影响压缩继续; 完成后自动应用, 失败走全局错误提示)。
            // 此前 loading 时点击外部/返回均无效且弹窗阻塞全景交互 (用户实证:
            // "处于压缩状态时整个应用无法进行任何交互")。
            onDismiss()
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                run {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    // Token size selector
                    Text(
                        text = stringResource(R.string.chat_page_compress_target_tokens),
                        style = MaterialTheme.typography.labelMedium
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        tokenOptions.forEachIndexed { index, tokens ->
                            SegmentedButton(
                                selected = selectedTokens == tokens,
                                onClick = { selectedTokens = tokens },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = tokenOptions.size
                                )
                            ) {
                                Text("$tokens")
                            }
                        }
                    }

                    // Keep recent messages input
                    if (!canCompress) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_not_enough_messages),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        OutlinedNumberInput(
                            value = keepRecentMessages,
                            onValueChange = { keepRecentMessages = it },
                            label = stringResource(R.string.chat_page_compress_keep_recent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            // v4.8.8: 确认即关 — 压缩在后台执行 (Job 挂 VM scope), 弹窗不进入
            // "压缩中"状态 (全屏模态压制从此不再出现); 进行中状态由对话页内
            // 非模态提示条呈现 (可随时取消), 用户可自由操作其他对话/页面。
            TextButton(
                enabled = canCompress,
                onClick = {
                    onConfirm(additionalPrompt, selectedTokens, keepRecentMessages)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
