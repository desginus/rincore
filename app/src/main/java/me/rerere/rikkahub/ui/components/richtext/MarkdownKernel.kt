package me.rerere.rikkahub.ui.components.richtext

/* ───【自研】MarkdownKernel.kt — v3.10.8 新渲染内核
 * 背景: 原自研 AST→Compose 渲染层节点映射不全 (表格/嵌套列表/任务列表/
 *       HTML/引用等"该解析的没解析")。引入 mikepenz/multiplatform-markdown-
 *       renderer 0.44 (成熟 GFM 全覆盖 + StreamingMarkdownState 流式支持),
 *       替换渲染内核。LaTeX/Mermaid 等扩展在旧内核 (Markdown.kt), 由
 *       MarkdownBlock 按内容特征路由。
 * ───────────────────────────────────────────────────────────────*/
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

object MarkdownKernel {
    /** 新内核总开关 — 出现渲染回归可在此一键回旧 */
    const val ENABLED: Boolean = true

    /** 需要旧内核扩展能力的内容特征 (LaTeX/图/特殊 HTML) */
    fun needsLegacyKernel(content: String): Boolean =
        content.contains("\$\$") ||
            content.contains("\\$") ||
            content.contains("```mermaid") ||
            content.contains("```Mermaid") ||
            content.contains("```latex") ||
            content.contains("<mermaid") ||
            content.contains("```graphviz")
}

/**
 * 新渲染内核 — mikepenz markdown (GFM 全覆盖: 表格/任务列表/嵌套列表/
 * 引用/HTML/图片/删除线/自动链接), 流式增量渲染由 MikepenzState 承担。
 */
@Composable
fun MarkdownKernel(
    content: String,
    modifier: Modifier = Modifier,
) {
    // v3.10.9: 初版用默认主题 (MarkdownColors 构造参数 API 跨版本不稳,
    // 主题适配后续轮次按需定制); StreamingMarkdownState 增量解析随后跟进。
    // 注意: 解析在主线程 (mikepenz 设计) — 仅用于已完成的静态文本,
    // 流式增量仍走旧内核 (MarkdownBlock 异步解析, 见 SmoothStreamingText)
    Markdown(
        content = content,
        modifier = modifier,
    )
}

/**
 * 流式内核 — 增量 append 渲染 (LLM token 流), 避免每 token 全量重解析。
 * 供消息流式输出接入 (v3.10.8 预留, 下轮接入 StreamingMarkdownState)。
 */
@Composable
fun MarkdownKernelStreaming(
    content: String,
    modifier: Modifier = Modifier,
) {
    // 初版与静态内核一致; StreamingMarkdownState 接入后改为增量
    MarkdownKernel(content = content, modifier = modifier)
}
