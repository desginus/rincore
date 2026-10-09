package org.intellij.markdown.html

import org.intellij.markdown.ast.ASTNode

private val UNSAFE_LINK_REGEX = Regex("^(vbscript|javascript|file|data):", RegexOption.IGNORE_CASE)
private val ALLOWED_DATA_LINK_REGEX = Regex("^data:image/(gif|png|jpeg|webp);", RegexOption.IGNORE_CASE)

/**
 * v4.8.113: file: 放行判定挂点 — 应用侧可注入「本应用私有目录 file:// 放行」。
 * 背景 (用户实证): 工具产图 (matplotlib / officecli) 的 render_url 为
 * file:///data/data/<pkg>/files/workspaces/... 形态, 此前被 XSS 策略整体替换为
 * "#" → 模型在正文原样复述也无法显示, 图片只停留在工具结果里。
 * 渲染端是 Compose + Coil (非 WebView), 仅放行本应用私有目录无任意文件泄露面;
 * 未注入判定 (默认 null) 时维持原版严格行为 (file 一律拒)。
 */
fun makeXssSafeDestination(s: CharSequence, fileAllowed: ((String) -> Boolean)? = null): CharSequence {
    val trimmed = s.trim()
    return s.takeIf {
        if (UNSAFE_LINK_REGEX.containsMatchIn(trimmed))
            ALLOWED_DATA_LINK_REGEX.containsMatchIn(trimmed) ||
                (fileAllowed != null &&
                    trimmed.startsWith("file:", ignoreCase = true) &&
                    fileAllowed.invoke(trimmed))
        else
            true
    } ?: "#"
}

fun LinkGeneratingProvider.makeXssSafe(
    useSafeLinks: Boolean = true,
    fileAllowed: ((String) -> Boolean)? = null,
): LinkGeneratingProvider {
    if (!useSafeLinks) return this

    return object : LinkGeneratingProvider(baseURI, resolveAnchors) {
        override fun renderLink(
            visitor: HtmlGenerator.HtmlGeneratingVisitor,
            text: String,
            node: ASTNode,
            info: RenderInfo
        ) {
            this@makeXssSafe.renderLink(visitor, text, node, info)
        }

        override fun getRenderInfo(text: String, node: ASTNode): RenderInfo? {
            return this@makeXssSafe.getRenderInfo(text, node)?.let {
                it.copy(destination = makeXssSafeDestination(it.destination, fileAllowed))
            }
        }
    }
}
