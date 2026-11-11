/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.message


/* ───【原版对齐】ChatMessageNerdLine.kt | 差异 ±5 行
 * 来源: 原版移植 + 自研小调整 (未达专项标注阈值, 对齐细节见对齐地图)
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration
import androidx.compose.runtime.getValue

/**
 * 显示消息的技术统计信息（如 token 使用量）
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),

    isLast: Boolean = false,
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val usage = message.usage
                if (settings.showTokenUsage && usage != null) {
                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.promptTokens.formatNumber()} tokens")
                            // Cached tokens
                            if (usage.cachedTokens > 0) {
                                Text(
                                    text = "(${message.usage?.cachedTokens?.formatNumber() ?: "0"} cached)"
                                )
                            }
                        }
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${usage.completionTokens.formatNumber()} tokens")
                        }
                    )
                    // TPS
                    if (message.finishedAt != null) {
                        val duration = Duration.between(
                            message.createdAt.toJavaLocalDateTime(),
                            message.finishedAt!!.toJavaLocalDateTime()
                        )
                        // ★v5.3.73：tok/s 的分母必须是「真实生成时长」。
                        // 旧实现用 createdAt→finishedAt = 整轮任务墙钟（含工具执行/审批/多轮），
                        // 而 completionTokens 来自最后一次响应 → 工具一多就把速度算成 1/10。
                        // generationTimeMs 由 GenerationHandler 按「每步首个 chunk→流结束」累计。
                        val generationMs = message.generationTimeMs?.takeIf { it > 0L }
                        val denominatorMs = generationMs ?: duration.toMillis()
                        val tps = if (denominatorMs > 0) {
                            usage.completionTokens.toFloat() / denominatorMs * 1000
                        } else 0f
                        val seconds = (denominatorMs / 1000f).toFixed(1)
                        // ★v5.3.73：旧消息没有 generationTimeMs，无法还原真实生成时长；
                        // 不再用「整轮墙钟」算出一个误导性的 tok/s，只显示耗时。
                        if (generationMs != null) {
                            StatsItem(
                                icon = {
                                    Icon(
                                        imageVector = HugeIcons.Zap,
                                        contentDescription = "Speed",
                                        modifier = Modifier.size(12.dp)
                                    )
                                },
                                content = {
                                    Text(text = "${tps.toFixed(1)} tok/s")
                                }
                            )
                        }

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
