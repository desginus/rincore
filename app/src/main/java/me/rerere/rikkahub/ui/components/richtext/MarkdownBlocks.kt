/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】MarkdownBlocks.kt | v4.8.66 新增 (CS 安卓端适配移植)
 * 来源: Cherry Studio 安卓端 streamdown 块级流式方案 → Kotlin/Compose 独立实现。
 * 职责: 长文本按顶层块边界切分, 每块独立解析/渲染/缓存; 流式期间仅尾部块内容变化,
 *       稳定块内容串不变 → 渲染被跳过 (strong skipping)、解析走缓存命中 —
 *       "只为变化的尾部工作" (对齐 CS: 不为没人看得见/没变化的内容重复解析)。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle

/**
 * 按顶层块边界 (空行) 切分 Markdown。
 * fence (```/~~~) 与行式数学块 ($$) 内部不切分; 连续空行并入边界。
 * 流式追加时: 已完成块内容串逐字稳定 → 下游渲染/解析恒定命中。
 */
internal fun splitMarkdownBlocks(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val blocks = ArrayList<String>()
    val current = StringBuilder()
    var fenceOpen = false
    var fenceChar = ' '
    fun flush() {
        if (current.isNotEmpty()) {
            blocks.add(current.toString().trimEnd('\r', '\n'))
            current.setLength(0)
        }
    }
    for (line in text.split('\n')) {
        val trimmed = line.trim()
        val fenceDelim = when {
            trimmed.startsWith("```") -> '`'
            trimmed.startsWith("~~~") -> '~'
            trimmed == "$$" -> '$'
            else -> null
        }
        if (fenceDelim != null) {
            if (!fenceOpen) {
                fenceOpen = true
                fenceChar = fenceDelim
            } else if (fenceChar == fenceDelim) {
                fenceOpen = false
            }
        }
        if (!fenceOpen && line.isBlank()) {
            flush()
        } else {
            current.append(line).append('\n')
        }
    }
    flush()
    return blocks
}

/**
 * v4.8.66 (CS 安卓端移植): 块级流式 Markdown 渲染 —
 * 对长文本按块切分后逐块渲染: 流式期间只有尾部块组成变化,
 * 稳定块参数 (String 相等 + strong skipping) 未变 → 跳过重组; 块级解析缓存命中。
 * 视觉与整段渲染等价 (同树同节点边距)。
 */
@Composable
internal fun SplitMarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
) {
    if (content.isEmpty()) return
    val blocks = remember(content) { splitMarkdownBlocks(content) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            key(index) {
                MarkdownBlock(
                    content = block,
                    style = style,
                    onClickCitation = onClickCitation,
                )
            }
        }
    }
}
