/* 【域 F·主题渲染】 — 消息/文档渲染 | 地图: docs/APP_MAP.md §F */
package me.rerere.rikkahub.ui.components.richtext

/* ───【原版对齐】MarkdownWeb.kt | 与 2.5.1 逐字节一致
 * 基线: 原版 2.5.1 (v4.1.6 拉齐工程标注补全)
 * ───────────────────────────────────────────────────────────────*/

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.resolveLocalImageFile
import me.rerere.rikkahub.utils.toCssHex

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(context: Context, markdown: String, colorScheme: ColorScheme): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }

    // v5.2.1 ★用户实测「点击气泡的表格渲染，本地图片不显示、以地址显示」真根因：
    // 旧实现把 **markdown 原文** 塞给 WebView 的 marked.js，而本地图片是
    // `file:///data/data/<pkg>/files/...` 形态 —— **WebView 没有权限/无法访问**，
    // 于是 <img> 加载失败，浏览器把它退化成 alt 文本/地址（用户看到的就是"地址"）。
    // 修法：在交给 WebView 之前，把可解析的本地图片**内联成 data URI**。
    // 预算：单图 ≤ 2MB、整篇累计 ≤ 6MB（避免超大图把 HTML 撑到几百 MB 卡死 WebView）。
    val inlined = inlineLocalImages(markdown)

    return htmlTemplate
        .replace("{{MARKDOWN_BASE64}}", inlined.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}


// ─── v5.2.1：WebView 本地图片内联 ───────────────────────────────────────────

/** 单图内联上限：超过则保留原地址（宁可不显示，也不把 WebView 撑爆） */
private const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024L

/** 整篇内联总预算 */
private const val MAX_INLINE_TOTAL_BYTES = 6 * 1024 * 1024L

/** `![alt](url)` —— url 里允许百分号编码，不允许空白与右括号 */
private val MD_IMAGE_REGEX = Regex("""!\[[^\]]*\]\(([^)\s]+)\)""")

/**
 * 把 markdown / HTML 里的**本地**图片地址替换为 base64 data URI。
 *
 * 只处理能通过 `resolveLocalImageFile` 解析到真实文件的地址
 * （`file://`、应用私有裸路径、workspace 语义、中文百分号编码）；
 * http(s)/data 一律原样保留。任何一步失败都退化为原地址，绝不抛异常。
 */
private fun inlineLocalImages(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    if (!markdown.contains("file://") && !markdown.contains("/files/") &&
        !markdown.contains("workspace://") && !markdown.contains("/workspace/") &&
        !markdown.contains(']')
    ) {
        return markdown
    }
    var budget = MAX_INLINE_TOTAL_BYTES
    fun dataUriFor(url: String): String? {
        if (url.startsWith("data:")) return null
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return null
        val file = runCatching { resolveLocalImageFile(url) }.getOrNull() ?: return null
        if (!file.isFile || !file.canRead()) return null
        val len = file.length()
        if (len <= 0L || len > MAX_INLINE_IMAGE_BYTES || len > budget) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        budget -= bytes.size
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            else -> "image/png"
        }
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return "data:$mime;base64,$b64"
    }

    // ① markdown 图片语法
    var out = MD_IMAGE_REGEX.replace(markdown) { m ->
        val url = m.groupValues[1]
        val uri = dataUriFor(url) ?: return@replace m.value
        m.value.replace(url, uri)
    }
    // ② HTML <img src="...">（气泡里也可能出现富文本片段）
    out = HTML_IMG_SRC_REGEX.replace(out) { m ->
        val quote = m.groupValues[1]
        val url = m.groupValues[2]
        val uri = dataUriFor(url) ?: return@replace m.value
        "src=$quote$uri$quote"
    }
    return out
}

private val HTML_IMG_SRC_REGEX = Regex("""src=(["'])([^"']+)""")
