/* 【域 C·工具系统】 — 工具矩阵选择器（输入栏 /@） | 地图: docs/APP_MAP.md §C */
package me.rerere.rikkahub.ui.components.ai

/*
 * v4.8.90: 输入栏 `/@` 呼出的工具矩阵选择器。
 *
 * 与 WorkspaceCompletionProvider 的「空格 @」互不冲突 —— 那个由「@ 前必须是边界符
 * （空格/括号/引号）」触发，`/@` 的 @ 前是 '/'（不是边界符），故两边天然分流。
 *
 * 交互（用户定版）：根区 → 子区 → 工具，三级下钻、可上下滑动；
 * 点击工具后收回弹层，并在输入栏留下 `@工具名 ` 提醒模型精确使用该工具。
 * 数据全部来自与模型侧同源的 ZoneRouter（zoneMap）+ 全量工具池，绝不另建分类副本。
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.Tool
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.data.ai.tools.zoneRouterOf
import me.rerere.rikkahub.data.datastore.Settings

/** 选择器里的一个工具区条目 */
data class ToolPickerZone(
    val id: String,
    val label: String,
    val toolCount: Int,
    val childCount: Int,
    val hidden: Boolean,
)

/** 选择器里的一个工具条目（name = 模型将看到的有效名，含别名） */
data class ToolPickerTool(
    val name: String,
    val rawName: String,
    val description: String,
)

/** 三级下钻结构：根区 / 区 → 子区 / 区 → 直接工具 */
data class ToolMatrixPickerData(
    val roots: List<ToolPickerZone>,
    val byId: Map<String, ToolPickerZone>,
    val childrenOf: Map<String, List<ToolPickerZone>>,
    val toolsOf: Map<String, List<ToolPickerTool>>,
)

/**
 * 从「与模型侧同源」的 settings + 全量工具池构建选择器数据。
 * 工具池须用 buildToolList(...)（其内部就是 buildAssistantToolPool）生成，保证口径一致。
 */
fun buildToolMatrixPickerData(settings: Settings, tools: List<Tool>): ToolMatrixPickerData {
    val router = zoneRouterOf(settings)
    val map = router.zoneMap(tools)
    fun zoneOf(id: String) = ToolPickerZone(
        id = id,
        label = router.label(id),
        toolCount = map.counts[id] ?: 0,
        childCount = map.children[id]?.size ?: 0,
        hidden = id in settings.hiddenZones,
    )
    val byId = map.allIds.associateWith { zoneOf(it) }
    return ToolMatrixPickerData(
        roots = map.roots.map { zoneOf(it) },
        byId = byId,
        childrenOf = map.allIds.associateWith { id -> map.children[id].orEmpty().map { zoneOf(it) } },
        toolsOf = map.allIds.associateWith { id ->
            map.classified[id].orEmpty().map { tool ->
                ToolPickerTool(
                    name = settings.toolNameOverrides[tool.name] ?: tool.name,
                    rawName = tool.name,
                    description = tool.description,
                )
            }
        },
    )
}

/**
 * 工具矩阵选择器弹层 —— 外观与 CompletionPopup 一致的收展式面板（可上下滑动）。
 */
@Composable
fun ToolMatrixPickerPopup(
    data: ToolMatrixPickerData,
    zoneStack: List<String>,
    onPush: (String) -> Unit,
    onBack: () -> Unit,
    onPick: (ToolPickerTool) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentZone = zoneStack.lastOrNull()
    val zones = if (currentZone == null) data.roots else data.childrenOf[currentZone].orEmpty()
    val tools = if (currentZone == null) emptyList() else data.toolsOf[currentZone].orEmpty()
    val title = currentZone?.let { data.byId[it]?.label ?: it } ?: "工具矩阵"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 头部：返回（下钻后才有）· 当前层级 · 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentZone != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = HugeIcons.ArrowLeft01,
                            contentDescription = "返回上一级",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (currentZone == null) 10.dp else 0.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "关闭",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            ) {
                items(items = zones, key = { "zone:${it.id}" }) { zone ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPush(zone.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Folder01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = zone.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append("${zone.toolCount} 个工具")
                                    if (zone.childCount > 0) append(" · ${zone.childCount} 个子区")
                                    if (zone.hidden) append(" · 已隐藏")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = HugeIcons.ArrowRight01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(items = tools, key = { "tool:${it.rawName}" }) { tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(tool) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Wrench01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (tool.description.isNotBlank()) {
                                Text(
                                    text = tool.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (zones.isEmpty() && tools.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = if (currentZone == null) "工具矩阵还没有工具区" else "这个区里还没有工具",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}
