package me.rerere.rikkahub.utils

/* ───【原版对齐】DiffUtils.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils

private const val DEFAULT_CONTEXT_LINES = 3

/**
 * 生成 [oldText] 到 [newText] 的 unified diff 文本, 内容相同时返回 null
 */
fun generateUnifiedDiff(
    oldText: String,
    newText: String,
    path: String,
    contextLines: Int = DEFAULT_CONTEXT_LINES,
): String? {
    if (oldText == newText) return null
    val oldLines = oldText.lines()
    val newLines = newText.lines()
    val patch = DiffUtils.diff(oldLines, newLines)
    if (patch.deltas.isEmpty()) return null
    return UnifiedDiffUtils
        .generateUnifiedDiff("a/$path", "b/$path", oldLines, patch, contextLines)
        .joinToString("\n")
}
