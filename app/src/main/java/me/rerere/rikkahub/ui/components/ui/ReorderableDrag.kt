/* 【域 F·主题渲染】 — UI 组件 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.ui

/* ───【原版对齐】ReorderableDrag.kt | 基线 2.5.5 (v4.8.64 适配移植)
 * 统一列表拖拽手势: 长按整卡拖拽 + 触感反馈 + 拖拽缩放 (CollectionItem / ListItem 两版)。
 * ───────────────────────────────────────────────────────────────*/

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableListItemScope

private const val DraggingScale = 0.95f

/**
 * 统一的列表拖拽排序手势：长按整个 item 开始拖拽，带触感反馈和拖拽中的缩放效果。
 *
 * 在 `ReorderableItem { isDragging -> }` 内使用，作为 item 的 modifier：
 * ```
 * ReorderableItem(state, key) { isDragging ->
 *     MyItem(modifier = longPressReorder(isDragging))
 * }
 * ```
 */
@Composable
fun ReorderableCollectionItemScope.longPressReorder(
    isDragging: Boolean,
    enabled: Boolean = true,
): Modifier {
    val haptic = LocalHapticFeedback.current
    return Modifier
        .draggingScale(isDragging)
        .longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = { haptic.dragStarted() },
            onDragStopped = { haptic.dragStopped() },
        )
}

/**
 * [longPressReorder] 的 `ReorderableColumn` / `ReorderableRow` 版本。
 */
@Composable
fun ReorderableListItemScope.longPressReorder(
    isDragging: Boolean,
    enabled: Boolean = true,
): Modifier {
    val haptic = LocalHapticFeedback.current
    return Modifier
        .draggingScale(isDragging)
        .longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = { haptic.dragStarted() },
            onDragStopped = { haptic.dragStopped() },
        )
}

@Composable
private fun Modifier.draggingScale(isDragging: Boolean): Modifier {
    val scale by animateFloatAsState(if (isDragging) DraggingScale else 1f, label = "reorder_scale")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

private fun HapticFeedback.dragStarted() = performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

private fun HapticFeedback.dragStopped() = performHapticFeedback(HapticFeedbackType.GestureEnd)
